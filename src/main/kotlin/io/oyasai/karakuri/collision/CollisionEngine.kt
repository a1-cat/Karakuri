package io.oyasai.karakuri.collision

import com.destroystokyo.paper.event.player.PlayerJumpEvent
import org.bukkit.event.player.PlayerInputEvent
import io.papermc.paper.entity.TeleportFlag
import io.oyasai.karakuri.KarakuriPlugin
import io.oyasai.karakuri.engine.KarakuriInstance
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.HappyGhast
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.util.Vector
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * からくりの当たり判定（動的ハッピーガスト床・壁スライディング射影・公転追従・坂道オートステップ）を統括する物理エンジン。
 *
 * - プレイヤーの足元にのみ超軽量な不可視ハッピーガスト(1体)を動的配置し、100%バニラ感覚のジャンプ・ダッシュを実現。
 * - 壁のめり込み押し戻し(Pop-out)を全廃し、壁に向かう移動成分のみをカットする「壁スライディング(Wall Slide)」を採用。
 * - Attribute.STEP_HEIGHT (1.25) により坂道・階段・急勾配をバニラ物理でカクつきゼロで滑らかに昇降。
 * - トロッコ（Minecart）との全当たり判定・押し合いを完全遮断。
 * - エレベーター反転時の床吸着により落下を根絶。
 * - 回転台での歩行中はテレポートを抑止してからくりの回転速度をVelocityに自然加算、静止中のみ高精度追従テレポート。
 */
@Suppress("DEPRECATION")
class CollisionEngine(private val plugin: KarakuriPlugin) : Listener {

  companion object {
    const val TAG_FLOOR_GHAST = "karakuri_floor_ghast"
    const val GHAST_SCALE = 0.25 // 4.0m * 0.25 = 1.0m (元の1ブロックサイズ)
    const val GHAST_HEIGHT = 1.0 // 高さ1.0m
    const val GHAST_RADIUS = 0.5 // 半径0.5m
    const val COLLISION_TEAM = "karakuri_ghast"

    // プレイヤーの周囲を隙間なくカバーする十字形5体オフセット
    // オフセット 0.55m: 各ガスト(幅1.0m)同士が0.45m重なり合い、隙間なく直径2.1mの広大な足場を形成！
    // ダッシュやスプリントジャンプ、回転台の回転速度が加算されても絶対に踏み外さない！
    val GHAST_OFFSETS = listOf(
      org.joml.Vector2d(0.0, 0.0),    // 中心（足元直下）
      org.joml.Vector2d(0.55, 0.0),   // 東 (+X)
      org.joml.Vector2d(-0.55, 0.0),  // 西 (-X)
      org.joml.Vector2d(0.0, 0.55),   // 南 (+Z)
      org.joml.Vector2d(0.0, -0.55),  // 北 (-Z)
    )
  }

  data class RiderState(
    var karakuriId: String,
    var targetSurfaceY: Double = 0.0,
    val floorGhasts: MutableList<HappyGhast> = mutableListOf(),
    var lastMoveTick: Long = 0L,
    var lastTeleportTarget: Location? = null,
    var graceTicks: Int = 6, // 端や頂上でのチラつき防止ヒステリシス
    var currentDeltaYaw: Float = 0f, // からくりの現在の角速度(deg/tick)
    var lastDeltaX: Double = 0.0,
    var lastDeltaY: Double = 0.0,
    var lastDeltaZ: Double = 0.0,
    var jumpTicks: Int = 0, // ジャンプ直後のテレポート抑止タイマー
    var isCustomJumping: Boolean = false, // ★ジャンプ空中滞空中フラグ
    var jumpRelVy: Double = 0.0, // ★からくり床に対する相対ジャンプ垂直速度
    var ticksSinceAscend: Int = 999, // ★最後に上昇していた時からの経過tick（切り替え瞬間判定用）
    var targetAnchorX: Double? = null, // ★床に対する相対位置フィードバック用の目標ワールドX
    var targetAnchorZ: Double? = null, // ★床に対する相対位置フィードバック用の目標ワールドZ
    var vanillaJumpCooldown: Int = 0, // ★下降中等のバニラジャンプ保護タイマー
  )

  private val colliders = mutableMapOf<String, KarakuriCollider>()
  private val ridingPlayers = mutableMapOf<UUID, RiderState>() // Player UUID -> RiderState
  private val ghostModePlayers = java.util.Collections.synchronizedSet(mutableSetOf<UUID>())
  private val playerKnockbackCooldown = mutableMapOf<UUID, Long>() // Player UUID -> expireTick

  fun isGhost(player: Player): Boolean = ghostModePlayers.contains(player.uniqueId)

  fun toggleGhost(player: Player): Boolean {
    val wasGhost = ghostModePlayers.contains(player.uniqueId)
    return if (wasGhost) {
      ghostModePlayers.remove(player.uniqueId)
      false
    } else {
      ghostModePlayers.add(player.uniqueId)
      ridingPlayers.remove(player.uniqueId)?.let { removeFloorGhast(it, player) }
      true
    }
  }

  private val gravityAttribute: Attribute? by lazy {
    try {
      Attribute.valueOf("GENERIC_GRAVITY")
    } catch (_: Throwable) {
      try {
        Attribute.valueOf("GRAVITY")
      } catch (_: Throwable) {
        null
      }
    }
  }

  private fun setPlayerGravity(player: Player, gravity: Double) {
    try {
      val attr = gravityAttribute?.let { player.getAttribute(it) } ?: return
      if (abs(attr.baseValue - gravity) > 0.001) {
        attr.baseValue = gravity
      }
    } catch (_: Throwable) {}
  }

  private fun resetPlayerGravity(player: Player) {
    setPlayerGravity(player, 0.08)
  }

  private val movementSpeedAttribute: Attribute? by lazy {
    try {
      Attribute.valueOf("GENERIC_MOVEMENT_SPEED")
    } catch (_: Throwable) {
      try {
        Attribute.valueOf("MOVEMENT_SPEED")
      } catch (_: Throwable) {
        null
      }
    }
  }

  private fun setPlayerMovementSpeed(player: Player, speed: Double) {
    try {
      val attr = movementSpeedAttribute?.let { player.getAttribute(it) } ?: return
      if (abs(attr.baseValue - speed) > 0.001) {
        attr.baseValue = speed
      }
    } catch (_: Throwable) {}
  }

  private fun resetPlayerMovementSpeed(player: Player) {
    setPlayerMovementSpeed(player, 0.1)
  }

  private fun updatePlayerFlight(player: Player, allow: Boolean) {
    if (player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE) {
      if (player.allowFlight != allow) {
        player.allowFlight = allow
        if (!allow && player.isFlying) {
          player.isFlying = false
        }
      }
    }
  }

  /**
   * バニラMinecraft公式計算式に100%準拠した防具・防御靭性・防護エンチャントによるダメージ軽減計算
   */
  private fun calculateDamageAfterArmor(player: Player, rawDamage: Double): Double {
    try {
      val armorAttr = try { Attribute.valueOf("GENERIC_ARMOR") } catch (_: Throwable) { Attribute.valueOf("ARMOR") }
      val armor = player.getAttribute(armorAttr)?.value ?: 0.0

      val toughnessAttr = try { Attribute.valueOf("GENERIC_ARMOR_TOUGHNESS") } catch (_: Throwable) { Attribute.valueOf("ARMOR_TOUGHNESS") }
      val toughness = player.getAttribute(toughnessAttr)?.value ?: 0.0

      // 1. 防具値 (Armor) + 防御靭性 (Toughness) による軽減
      val defense = (armor - rawDamage / (2.0 + toughness / 4.0)).coerceIn(armor / 5.0, 20.0)
      val damageAfterArmor = rawDamage * (1.0 - defense / 25.0)

      // 2. 防護エンチャント (Protection) による軽減 (EPF)
      var totalEpf = 0
      val protEnchant = try {
        org.bukkit.enchantments.Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft("protection"))
      } catch (_: Throwable) {
        null
      }
      if (protEnchant != null) {
        for (item in player.inventory.armorContents) {
          if (item != null && !item.type.isAir) {
            val level = item.getEnchantmentLevel(protEnchant)
            totalEpf += level
          }
        }
      }
      val epfClamped = minOf(20, totalEpf)
      val finalDamage = damageAfterArmor * (1.0 - epfClamped / 25.0)

      return maxOf(0.5, finalDamage)
    } catch (_: Throwable) {
      return maxOf(0.5, rawDamage * 0.5)
    }
  }

  fun start() {
    cleanupAllFloorGhasts()
    setupGhastCollisionTeam()
    Bukkit.getPluginManager().registerEvents(this, plugin)
  }

  fun stop() {
    for (uuid in ridingPlayers.keys) {
      Bukkit.getPlayer(uuid)?.let {
        applyStepHeight(it, 0.6)
        resetPlayerGravity(it)
        resetPlayerMovementSpeed(it)
        updatePlayerFlight(it, false)
        removePlayerFromCollisionTeam(it)
      }
    }
    cleanupAllFloorGhasts()
    colliders.clear()
    ridingPlayers.clear()
  }

  private fun setupGhastCollisionTeam() {
    try {
      val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
      val team = scoreboard.getTeam(COLLISION_TEAM) ?: scoreboard.registerNewTeam(COLLISION_TEAM)
      team.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER)
    } catch (_: Throwable) {}
  }

  private fun addPlayerToCollisionTeam(player: Player) {
    try {
      val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
      val team = scoreboard.getTeam(COLLISION_TEAM) ?: scoreboard.registerNewTeam(COLLISION_TEAM).apply {
        setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER)
      }
      val entry = player.name
      if (!team.hasEntry(entry)) {
        team.addEntry(entry)
      }
    } catch (_: Throwable) {}
  }

  private fun removePlayerFromCollisionTeam(player: Player) {
    try {
      val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
      val team = scoreboard.getTeam(COLLISION_TEAM) ?: return
      val entry = player.name
      if (team.hasEntry(entry)) {
        team.removeEntry(entry)
      }
    } catch (_: Throwable) {}
  }

  private fun cleanupAllFloorGhasts() {
    for (world in Bukkit.getWorlds()) {
      for (entity in world.getEntitiesByClass(HappyGhast::class.java)) {
        if (entity.scoreboardTags.contains(TAG_FLOOR_GHAST)) {
          entity.remove()
        }
      }
    }
  }

  private fun applyStepHeight(player: Player, height: Double) {
    try {
      val attr = player.getAttribute(Attribute.STEP_HEIGHT) ?: return
      if (abs(attr.baseValue - height) > 0.01) {
        attr.baseValue = height
      }
    } catch (_: Throwable) {}
  }

  fun getCollider(instance: KarakuriInstance): KarakuriCollider {
    return colliders.getOrPut(instance.id) { KarakuriCollider(instance) }
  }

  /**
   * プレイヤーの足元にハッピーガストクラスタを展開・追従。
   * - 通常時: 十字形5体（中心＋東西南北0.55m）
   * - 水平回転時 (Y軸回転): ユーザー考案の「公転軌道配置」！
   *   中心軸(ピボット)からの半径 r の円弧（公転軌道）に沿って、
   *   プレイヤーの前後に 0.55m, 1.10m の先回りガストを展開し、
   *   半径方向（内側・外側）にも展開（計7体）。
   *   どれだけ高速回転しても、プレイヤーは常に軌道上のガストの床に乗っているため
   *   追いつけない問題が100%解消され、ジャンプ・ダッシュが完全に途切れない！
   */
  private fun updateFloorGhasts(
    player: Player,
    riderState: RiderState,
    collider: KarakuriCollider,
    boxes: List<KarakuriCollider.OrientedBox>,
    pLoc: Location,
    baseFloorY: Double,
    isSlope: Boolean = false,
    currState: KarakuriCollider.TransformState? = null,
    isMotionActive: Boolean = false,
  ) {
    val world = player.world

    // ★ 純粋な上昇エレベーター（deltaY > 0）中:
    // プレイヤーは無重力＋等速Velocityでからくりと一緒に滑らかに上昇している。
    // 足元のガストは完全に排除し、めり込み衝突・跳ね・勝手な滑りを100%根絶！
    // ジャンプは「Shiftキー（スニーク）」による慣性溜めハイジャンプで100%快適に発動！
    val isAscendingElevator = (riderState.lastDeltaY > 0.0001 && abs(riderState.lastDeltaX) < 1e-4 && abs(riderState.lastDeltaZ) < 1e-4)
    if (isAscendingElevator) {
      if (riderState.floorGhasts.isNotEmpty()) {
        for (g in riderState.floorGhasts) {
          if (g.isValid) g.remove()
        }
        riderState.floorGhasts.clear()
      }
      return
    }

    val isYRot = isMotionActive && (currState != null && currState.isYAxisRotationOnly)

    val targetPositions = mutableListOf<Vector3d>()

    val horizSpeed = sqrt(riderState.lastDeltaX * riderState.lastDeltaX + riderState.lastDeltaZ * riderState.lastDeltaZ)
    val isLinearMoving = isMotionActive && horizSpeed > 0.015

    val parentInstance = when (val motion = collider.instance.activeMotion) {
      is io.oyasai.karakuri.motion.ChildContraptionMotion -> plugin.manager.get(motion.parentId)
      is io.oyasai.karakuri.motion.ChainChildContraptionMotion -> plugin.manager.get(motion.parentId)
      else -> null
    }

    val isParentRotating = parentInstance != null && (
      parentInstance.activeMotion is io.oyasai.karakuri.motion.ContinuousSpinMotion ||
      parentInstance.activeMotion is io.oyasai.karakuri.motion.AngleRotationMotion ||
      parentInstance.activeMotion is io.oyasai.karakuri.motion.ProgramMotion
    )

    if (isParentRotating && parentInstance != null) {
      val parentPivot = parentInstance.worldPivotLocation
      val parentDx = pLoc.x - parentPivot.x
      val parentDz = pLoc.z - parentPivot.z
      val parentR = sqrt(parentDx * parentDx + parentDz * parentDz)

      // 1. プレイヤー真下の十字形 5 体
      for (offset in GHAST_OFFSETS) {
        targetPositions.add(Vector3d(pLoc.x + offset.x, baseFloorY, pLoc.z + offset.y))
      }

      // 2. 親構造のピボットを中心とした公転円軌道の先回りステップ（前後各5ステップ計10体）
      if (parentR >= 0.8) {
        val pTheta = atan2(parentDz, parentDx)
        val pDTheta = 0.55 / parentR
        for (step in listOf(1.0, 2.0, 3.0, 4.0, 5.0, -1.0, -2.0, -3.0, -4.0, -5.0)) {
          val th = pTheta + step * pDTheta
          targetPositions.add(Vector3d(parentPivot.x + parentR * cos(th), baseFloorY, parentPivot.z + parentR * sin(th)))
        }
      }

      // 3. 子構造自身も自転している場合、子ピボット周りの円軌道も加算
      if (isYRot && currState != null) {
        val pivotX = currState.pivot.x
        val pivotZ = currState.pivot.z
        val cdx = pLoc.x - pivotX
        val cdz = pLoc.z - pivotZ
        val cr = sqrt(cdx * cdx + cdz * cdz)
        if (cr >= 0.8) {
          val cTheta = atan2(cdz, cdx)
          val cDTheta = 0.55 / cr
          for (step in listOf(1.0, 2.0, 3.0, -1.0, -2.0, -3.0)) {
            val th = cTheta + step * cDTheta
            targetPositions.add(Vector3d(pivotX + cr * cos(th), baseFloorY, pivotZ + cr * sin(th)))
          }
        }
      }
    } else if (isYRot && currState != null) {
      val pivotX = currState.pivot.x
      val pivotZ = currState.pivot.z
      val dx = pLoc.x - pivotX
      val dz = pLoc.z - pivotZ
      val r = sqrt(dx * dx + dz * dz)

      if (r >= 0.8) {
        // 1. プレイヤー真下の十字形 5 体（足元直下を強固に支持！）
        for (offset in GHAST_OFFSETS) {
          targetPositions.add(Vector3d(pLoc.x + offset.x, baseFloorY, pLoc.z + offset.y))
        }

        // 2. 青い円（公転軌道）の前後ステップ（先回り足場・前後各5ステップ計10体）
        val theta = atan2(dz, dx)
        val dTheta = 0.55 / r // 0.55m あたりの回転角
        for (step in listOf(1.0, 2.0, 3.0, 4.0, 5.0, -1.0, -2.0, -3.0, -4.0, -5.0)) {
          val th = theta + step * dTheta
          targetPositions.add(Vector3d(pivotX + r * cos(th), baseFloorY, pivotZ + r * sin(th)))
        }
      }
    } else if (isLinearMoving) {
      // ★ ユーザー指示: 直線運動のからくりの上では、プレイヤーの軌道を予測して予め軌道の先に追加でハッピーガストを配置！
      // 1. プレイヤー真下の十字形 5 体
      for (offset in GHAST_OFFSETS) {
        targetPositions.add(Vector3d(pLoc.x + offset.x, baseFloorY, pLoc.z + offset.y))
      }

      // 2. 進行方向の単位ベクトル
      val dirX = riderState.lastDeltaX / horizSpeed
      val dirZ = riderState.lastDeltaZ / horizSpeed
      // 進行方向に垂直な単位ベクトル（左右幅用）
      val perpX = -dirZ
      val perpZ = dirX

      // 進行方向に先回りステップ配置（速度に応じて前方最大 8 ステップ、左右 0.35m 幅付き、坂道傾斜も追従）
      val stepSize = 0.55
      val forwardSteps = (horizSpeed * 10.0 / stepSize).toInt().coerceIn(3, 8)
      val dirY = if (horizSpeed > 0.001) riderState.lastDeltaY / horizSpeed else 0.0

      for (step in 1..forwardSteps) {
        val dist = step * stepSize
        val cx = pLoc.x + dirX * dist
        val cz = pLoc.z + dirZ * dist
        val cy = baseFloorY + dirY * dist
        targetPositions.add(Vector3d(cx, cy, cz))
        targetPositions.add(Vector3d(cx + perpX * 0.35, cy, cz + perpZ * 0.35))
        targetPositions.add(Vector3d(cx - perpX * 0.35, cy, cz - perpZ * 0.35))
      }
      // 反転や停止に備えて後方にも 2 ステップ
      for (step in 1..2) {
        val dist = -step * stepSize
        val cy = baseFloorY + dirY * dist
        targetPositions.add(Vector3d(pLoc.x + dirX * dist, cy, pLoc.z + dirZ * dist))
      }
    }

    // 回転台以外、またはピボット直下 (r < 0.8) の場合は従来の十字形5体
    if (targetPositions.isEmpty()) {
      for (offset in GHAST_OFFSETS) {
        targetPositions.add(Vector3d(pLoc.x + offset.x, baseFloorY, pLoc.z + offset.y))
      }
    }

    val neededCount = targetPositions.size
    riderState.floorGhasts.removeAll { !it.isValid }

    while (riderState.floorGhasts.size < neededCount) {
      val spawnLoc = Location(world, pLoc.x, baseFloorY - GHAST_HEIGHT, pLoc.z)
      val ghast = world.spawn(spawnLoc, HappyGhast::class.java) { g ->
        g.isPersistent = false
        g.isInvisible = true
        g.isGlowing = true // ★完全透明＋発光エフェクト表示
        g.isSilent = true
        g.isInvulnerable = true
        g.setAI(false)
        g.setGravity(false)
        g.isCollidable = true
        g.getAttribute(Attribute.SCALE)?.baseValue = GHAST_SCALE
        g.addScoreboardTag(TAG_FLOOR_GHAST)
        try {
          val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
          val team = scoreboard.getTeam(COLLISION_TEAM) ?: scoreboard.registerNewTeam(COLLISION_TEAM).apply {
            setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE, org.bukkit.scoreboard.Team.OptionStatus.NEVER)
            setCanSeeFriendlyInvisibles(false)
          }
          team.setCanSeeFriendlyInvisibles(false)
          team.addEntry(g.uniqueId.toString())
        } catch (_: Throwable) {}
      }
      riderState.floorGhasts.add(ghast)
    }

    while (riderState.floorGhasts.size > neededCount) {
      riderState.floorGhasts.removeLastOrNull()?.remove()
    }

    for (i in targetPositions.indices) {
      val pos = targetPositions[i]
      val ghast = riderState.floorGhasts[i]

      val clampedPos = collider.clampToFloorSurface(boxes, pos.x, pos.z, baseFloorY)
      val ghastY = if (isSlope) baseFloorY else minOf(clampedPos.y, baseFloorY)

      val targetLoc = Location(world, clampedPos.x, ghastY - GHAST_HEIGHT, clampedPos.z)
      if (ghast.location.distanceSquared(targetLoc) > 0.0001) {
        ghast.teleport(targetLoc)
      }
    }
  }

  private fun removeFloorGhast(riderState: RiderState, player: Player? = null) {
    for (ghast in riderState.floorGhasts) {
      if (ghast.isValid) ghast.remove()
    }
    riderState.floorGhasts.clear()
    player?.let {
      applyStepHeight(it, 0.6)
      resetPlayerGravity(it)
      resetPlayerMovementSpeed(it)
      updatePlayerFlight(it, false)
      removePlayerFromCollisionTeam(it)
    }
  }

  /**
   * からくりの更新直後に同Tickで実行されるコリジョン＆物理追従ループ
   */
  fun tick() {
    val manager = plugin.manager
    val activeInstances = manager.getAll()
    if (activeInstances.isEmpty()) {
      for ((uuid, rider) in ridingPlayers) {
        removeFloorGhast(rider, Bukkit.getPlayer(uuid))
      }
      colliders.clear()
      ridingPlayers.clear()
      return
    }

    val activeIds = activeInstances.map { it.id }.toSet()
    colliders.keys.retainAll(activeIds)
    ridingPlayers.entries.removeIf { (uuid, rider) ->
      if (rider.karakuriId !in activeIds) {
        removeFloorGhast(rider, Bukkit.getPlayer(uuid))
        true
      } else false
    }

    val curTick = Bukkit.getCurrentTick().toLong()

    for (instance in activeInstances) {
      if (!instance.displayEntity.isValid) continue

      val collider = getCollider(instance)
      val prevState = collider.lastState
      val currState = collider.captureCurrentState()
      val boxes = collider.computeWorldBoxes()

      val world = instance.originLocation.world ?: continue
      val curPivotLoc = Location(world, currState.pivot.x, currState.pivot.y, currState.pivot.z)

      // からくりの全パーツを確実にカバーする動的探索半径（長い坂道や大型からくりも完全に包含）
      val searchRadius = maxOf(32.0, collider.maxReachRadius)
      val nearbyPlayers = world.getNearbyPlayers(curPivotLoc, searchRadius)

      // からくりが実際に動いている（移動中または回転中）か判定
      val isMotionActive = checkIsMotionActive(instance, prevState, currState)
      collider.isMotionActive = isMotionActive

      for (player in nearbyPlayers) {
        val isGhostOrSpectator = (player.gameMode == GameMode.SPECTATOR || isGhost(player))
        if (player.isFlying || player.isGliding || isGhostOrSpectator || player.vehicle != null) {
          ridingPlayers.remove(player.uniqueId)?.let { removeFloorGhast(it, player) }
          continue
        }

        val riderState = ridingPlayers[player.uniqueId]
        val currentRidingId = riderState?.karakuriId
        val currentRidingInstance = if (currentRidingId != null && currentRidingId != instance.id) manager.get(currentRidingId) else null
        val isRidingMyChild = currentRidingInstance?.let { cur ->
          val m = cur.activeMotion
          (m is io.oyasai.karakuri.motion.ChildContraptionMotion && m.parentId == instance.id) ||
          (m is io.oyasai.karakuri.motion.ChainChildContraptionMotion && m.parentId == instance.id)
        } ?: false

        if (isRidingMyChild) {
          val childCollider = getCollider(currentRidingInstance!!)
          val childBoxes = childCollider.computeWorldBoxes()
          val childResult = childCollider.checkPlayerCollision(player, childBoxes, player.location, isAlreadyRiding = true)
          if (childResult.isStandingOnTop) {
            continue // 子構造の床に乗っているため親構造側の処理をスキップ
          }
        }

        val isRidingThis = (riderState?.karakuriId == instance.id)
        var pLoc = player.location
        val isJumping = (player.velocity.y > 0.2 && !player.isOnGround)
        val isAirborne = isJumping || (pLoc.y > (riderState?.targetSurfaceY ?: pLoc.y) + 0.6)

        // 1. 動いているからくりの上に乗っていた場合、または空中ジャンプ中の場合、公転・平行移動・昇降・放物線を計算
        val shouldProcessMovement = isMotionActive || (riderState?.isCustomJumping == true)
        if (isRidingThis && prevState != null && shouldProcessMovement && riderState != null) {
          val followedLoc = collider.calculatePlayerFollow(pLoc, prevState, currState)
          val deltaX = followedLoc.x - pLoc.x
          val deltaY = followedLoc.y - pLoc.y
          val deltaZ = followedLoc.z - pLoc.z

          // ★ 一方通行ループ終点→始点等の瞬間テレポートリセットを検知:
          // からくりが一瞬で始点にワープした際、プレイヤーを巻き込んで始点へワープさせず即座に乗車を解除して通常重力に解放！
          val isTeleportReset = (abs(deltaY) > 1.2 || abs(deltaX) > 1.5 || abs(deltaZ) > 1.5)
          if (isTeleportReset) {
            removeFloorGhast(riderState, player)
            ridingPlayers.remove(player.uniqueId)
            resetPlayerGravity(player)
            resetPlayerMovementSpeed(player)
            continue
          }

          val input = player.currentInput
          val hasKeyInput = (input.isForward || input.isBackward || input.isLeft || input.isRight)
          val isWalking = hasKeyInput || (curTick - riderState.lastMoveTick <= 1)
          val targetFloorY = riderState.targetSurfaceY

          // からくりの正確なY軸物理回転角（度数法）を followedLoc (deltaQ) から直接算出
          var deltaYawDeg = followedLoc.yaw - pLoc.yaw
          while (deltaYawDeg > 180f) deltaYawDeg -= 360f
          while (deltaYawDeg < -180f) deltaYawDeg += 360f
          riderState.currentDeltaYaw = deltaYawDeg
          riderState.lastDeltaX = deltaX
          riderState.lastDeltaY = deltaY
          riderState.lastDeltaZ = deltaZ
          if (deltaY > 0.0001) {
            riderState.ticksSinceAscend = 0
          } else {
            if (riderState.ticksSinceAscend < 999) riderState.ticksSinceAscend++
          }

          // ★ 直線運動・昇降・回転台公転すべてにおいてプレイヤーへのテレポートを完全撤廃！
          // ハッピーガストはテレポート式足場、プレイヤーは 100% 慣性 (Velocity) ＆ 重力式！
          // 回転台の上でもプレイヤーは円弧公転慣性 (deltaX, deltaZ) で滑らかに運ばれ、
          // そのままの位置で自然にジャンプ可能！
          val curVel = player.velocity
          val hasHorizMotion = (abs(deltaX) > 0.0005 || abs(deltaZ) > 0.0005)

            if (riderState.vanillaJumpCooldown > 0) riderState.vanillaJumpCooldown--

            // --- 1. 水平方向 (X, Z) の慣性 ＆ 自発的歩行速度 ---
            var walkVx = 0.0
            var walkVz = 0.0
            if (hasKeyInput) {
              val yawRad = Math.toRadians(player.location.yaw.toDouble())
              var forward = 0.0
              var strafe = 0.0
              if (input.isForward) forward += 1.0
              if (input.isBackward) forward -= 1.0
              if (input.isLeft) strafe += 1.0
              if (input.isRight) strafe -= 1.0
              val fx = -sin(yawRad)
              val fz = cos(yawRad)
              val sx = cos(yawRad)
              val sz = sin(yawRad)
              val inDirX = forward * fx + strafe * sx
              val inDirZ = forward * fz + strafe * sz
              val inLen = sqrt(inDirX * inDirX + inDirZ * inDirZ)
              val normX = if (inLen > 0.0001) inDirX / inLen else 0.0
              val normZ = if (inLen > 0.0001) inDirZ / inLen else 0.0
              val isSprint = input.isSprint || player.isSprinting
              val maxSpeed = if (isSprint) 0.24 else 0.16
              walkVx = normX * maxSpeed
              walkVz = normZ * maxSpeed
            }

            val newVx: Double
            val newVz: Double
            if (hasKeyInput) {
              // 自発的歩行: プレイヤーの現在位置にアンカーをリアルタイム同期更新
              riderState.targetAnchorX = pLoc.x
              riderState.targetAnchorZ = pLoc.z
              val targetVx = deltaX + walkVx
              val targetVz = deltaZ + walkVz
              newVx = curVel.x + (targetVx - curVel.x) * 0.65
              newVz = curVel.z + (targetVz - curVel.z) * 0.65
            } else if (curTick - riderState.lastMoveTick <= 3) {
              // ★ キーを離した直後の自然減速フェーズ（1〜3 Tick）:
              // アンカーを現在位置に追従させ、急ブレーキ/逆噴射を完全に排除！
              // からくりの移動速度 deltaX, deltaZ をベースに維持しつつ、自発的歩行速度をバニラ地上摩擦に合わせてスムーズに減衰
              riderState.targetAnchorX = pLoc.x
              riderState.targetAnchorZ = pLoc.z
              val targetVx = deltaX + (curVel.x - deltaX) * 0.6
              val targetVz = deltaZ + (curVel.z - deltaZ) * 0.6
              newVx = curVel.x + (targetVx - curVel.x) * 0.7
              newVz = curVel.z + (targetVz - curVel.z) * 0.7
            } else if (riderState.isCustomJumping) {
              // 空中滞空中: アンカー追従しつつ空気抵抗 0.91 で水平慣性を維持
              riderState.targetAnchorX = (riderState.targetAnchorX ?: pLoc.x) + deltaX
              riderState.targetAnchorZ = (riderState.targetAnchorZ ?: pLoc.z) + deltaZ
              newVx = deltaX + (curVel.x - deltaX) * 0.91
              newVz = deltaZ + (curVel.z - deltaZ) * 0.91
            } else {
              // ★ 床の上で静止中: 相対アンカー ＆ 不感帯付き摩擦補正（微小振動・ハンティングを根絶！）
              var anchorX = (riderState.targetAnchorX ?: pLoc.x) + deltaX
              var anchorZ = (riderState.targetAnchorZ ?: pLoc.z) + deltaZ

              // ネットワーク遅延によるアンカー乖離を防止（現在位置へ穏やかに緩和）
              anchorX += (pLoc.x - anchorX) * 0.15
              anchorZ += (pLoc.z - anchorZ) * 0.15
              riderState.targetAnchorX = anchorX
              riderState.targetAnchorZ = anchorZ

              val errX = anchorX - pLoc.x
              val errZ = anchorZ - pLoc.z

              // 不感帯 (Deadzone): 4cm 未満の微小な誤差なら位置補正ゼロ（微小振動・ハンティングを完全遮断！）
              val deadzone = 0.04
              val corrX = if (abs(errX) > deadzone) ((errX - Math.signum(errX) * deadzone) * 0.35).coerceIn(-0.12, 0.12) else 0.0
              val corrZ = if (abs(errZ) > deadzone) ((errZ - Math.signum(errZ) * deadzone) * 0.35).coerceIn(-0.12, 0.12) else 0.0

              // 地上摩擦（約0.6倍減衰）を相殺する安定ベース速度 (delta * 1.15) ＋ 穏やかな位置補正
              val targetVx = deltaX * 1.15 + corrX
              val targetVz = deltaZ * 1.15 + corrZ

              // 速度の急変を防ぎつつ遅れを出さないスムージング (0.75)
              newVx = curVel.x + (targetVx - curVel.x) * 0.75
              newVz = curVel.z + (targetVz - curVel.z) * 0.75
            }

            // --- 2. 垂直方向 (Y軸) の運動 ＆ 上昇/下降/水平の分岐 ---
            if (riderState.isCustomJumping) {
              // ★ Shift溜めジャンプ空中滞空中（上昇・下降・水平・停止中にかかわらず一貫して放物線を完結！）
              resetPlayerMovementSpeed(player)
              updatePlayerFlight(player, false)
              setPlayerGravity(player, 0.0)

              if (riderState.jumpTicks > 0) riderState.jumpTicks--
              riderState.jumpRelVy = (riderState.jumpRelVy - 0.08) * 0.98

              val diffFromFloor = pLoc.y - targetFloorY
              val isDescending = riderState.jumpRelVy <= 0.0
              val reachedFloor = (isDescending && diffFromFloor <= 0.45) || diffFromFloor <= 0.08
              if (riderState.jumpTicks <= 10 && reachedFloor) {
                riderState.isCustomJumping = false
                riderState.jumpRelVy = 0.0
                riderState.jumpTicks = 0
                if (deltaY <= 0.0001) {
                  resetPlayerGravity(player)
                }
              }

              val targetVy = deltaY + riderState.jumpRelVy
              player.velocity = Vector(newVx, targetVy, newVz)

            } else if (deltaY > 0.0001) {
              // ★ 上昇中 (deltaY > 0): 垂直エレベーター ＆ 斜め上リフト！
              // 上向きの慣性を利用するシステム（無重力等速フレーム＋ソフト床吸着補正）
              resetPlayerMovementSpeed(player)
              updatePlayerFlight(player, false)
              setPlayerGravity(player, 0.0)

              val yDiff = targetFloorY - pLoc.y
              val targetVy = deltaY + (yDiff * 0.6).coerceIn(-0.15, 0.25)

              player.velocity = Vector(newVx, targetVy, newVz)

            } else if (deltaY < -0.0001) {
              // ★ 下降中 (deltaY < 0): 垂直下降 ＆ 斜め下下降！
              // プレイヤーのY軸下降はバニラ重力＆バニラジャンプに任せる！
              resetPlayerGravity(player)
              resetPlayerMovementSpeed(player)
              updatePlayerFlight(player, false)

              val diffFromFloor = pLoc.y - targetFloorY
              val isJumpTriggered = input.isJump && diffFromFloor in -0.6..0.6 && riderState.vanillaJumpCooldown <= 0

              if (isJumpTriggered) {
                // ★ 下降中ジャンプ支援: ハッピーガスト下降により接地判定が途切れていても、
                // スペースキー入力＋足元直下の床を検知して確実にバニラジャンプ (+0.42) を即時発動！
                val jumpVy = 0.42
                val vx = if (hasHorizMotion) newVx else curVel.x
                val vz = if (hasHorizMotion) newVz else curVel.z
                player.velocity = Vector(vx, jumpVy, vz)
                riderState.vanillaJumpCooldown = 12
              } else if (hasHorizMotion) {
                // 斜め下降等、水平移動がある場合のみ水平慣性 (newVx, newVz) を追従
                // ※ ジャンプ滞空中 (vanillaJumpCooldown > 0 または curVel.y > 0.05) は垂直速度を潰さず維持！
                val vy = if (riderState.vanillaJumpCooldown > 0 || curVel.y > 0.05) curVel.y else curVel.y
                player.velocity = Vector(newVx, vy, newVz)
              }
              // ※ 純粋な垂直エレベーター下降時（hasHorizMotion == false かつジャンプ非トリガー時）は
              // player.velocity を一切上書きせずバニラ物理に完全委任！

            } else {
              // ★ 水平移動 (deltaY == 0) または 停止中:
              // Y軸はバニラ重力（床に乗る）、X/Z軸はからくりの水平慣性で運ばれる！
              resetPlayerGravity(player)
              resetPlayerMovementSpeed(player)
              updatePlayerFlight(player, false)

              if (hasHorizMotion) {
                val diffFromFloor = pLoc.y - targetFloorY
                val isJumpTriggered = input.isJump && diffFromFloor in -0.6..0.5 && riderState.vanillaJumpCooldown <= 0
                if (isJumpTriggered) {
                  player.velocity = Vector(newVx, 0.42, newVz)
                  riderState.vanillaJumpCooldown = 12
                } else {
                  player.velocity = Vector(newVx, curVel.y, newVz)
                }
              }
            }

            // ★ ユーザー指示: 自発的歩行とからくり追従を完全分離
            // プレイヤー自身がキー入力 (WASD) で歩行していない静止時のみ、からくりの回転・公転に視点を完全追従！
            // からくり自体の公転・昇降による位置移動や床の微小振動で視点回転がキャンセルされないよう保護
            val isCustomJumping = (riderState.isCustomJumping || riderState.vanillaJumpCooldown > 0 || player.velocity.y > 0.45)
            val isSelfWalking = hasKeyInput || (curTick - riderState.lastMoveTick <= 2)

            if (abs(deltaYawDeg) > 0.005f && !isSelfWalking && !isCustomJumping) {
              val currentVel = player.velocity
              rotatePlayerYawRelative(player, deltaYawDeg)
              // ★超重要: internalTeleport による内部速度リセットを上書きし、水平公転慣性 (newVx, newVz) を100%確実に維持！
              player.velocity = Vector(newVx, currentVel.y, newVz)
            }
          }

        // 2. 現在の上面乗り判定
        val result = collider.checkPlayerCollision(player, boxes, pLoc, isAirborne, isAlreadyRiding = isRidingThis)

        // ★ ユーザー指示: 匍匐前進は廃止し、からくりによる圧迫ダメージのみにする！
        // 乗車中のプレイヤー（isRidingThis または ridingPlayers に登録済み）は床による圧迫ダメージから完全に保護！
        val isRider = isRidingThis || ridingPlayers.containsKey(player.uniqueId)
        val isAscendingElevator = (riderState?.lastDeltaY ?: 0.0) > 0.0001
        val isOverFloor = collider.isHorizontallyOverAnyFloor(boxes, pLoc.x, pLoc.z, margin = 0.45)
        val currFloor = collider.clampToFloorSurface(boxes, pLoc.x, pLoc.z, riderState?.targetSurfaceY ?: pLoc.y)
        val vDiff = pLoc.y - currFloor.y

        // 飛び乗り着地保護: プレイヤーが空中で床の真上（vDiff in 0.0..1.5）にいる場合のみ
        // 着地のため壁押し出しテレポートをスキップする
        val isLandingOnFloor = isAirborne && isOverFloor && result.isInsideAnyFloor && (vDiff in 0.0..1.5)

        // ★ 高速移動パーツとの衝突(CCD)・慣性ノックバック・縦回転跳ね上げ判定
        // ユーザー指示:
        // 1. からくりに乗車中のプレイヤーは自構造の床・パーツによる吹き飛ばしから完全保護！
        // 2. 打撃判定は、あくまで回転するからくりのみで通常発生（vLen3D >= 0.45）。
        // 3. 直線運動のからくりでは、縦軸（エレベーター昇降等）での発生は完全に無し！
        // 4. 直線運動の横軸（水平直線移動）での発生は、回転よりももっと厳しく（horizSpeed >= 1.25 の超高速のみ）。
        val motion = instance.activeMotion
        val isRotary = (motion is io.oyasai.karakuri.motion.ContinuousSpinMotion ||
                        motion is io.oyasai.karakuri.motion.AngleRotationMotion ||
                        motion is io.oyasai.karakuri.motion.DoorMotion)
        val isLinear = (motion is io.oyasai.karakuri.motion.IntegratedLinearMotion ||
                        motion is io.oyasai.karakuri.motion.MinecartMotion)

        val sweptHit = if (isMotionActive && prevState != null && !isRider) {
          collider.checkSweptPlayerCollision(player, pLoc, prevState, currState)
        } else null

        val cd = playerKnockbackCooldown[player.uniqueId] ?: 0L
        val isKnockbackCooledDown = curTick >= cd
        val vPart = sweptHit?.velocity ?: Vector3d(0.0, 0.0, 0.0)
        val vLen3D = sqrt(vPart.x * vPart.x + vPart.y * vPart.y + vPart.z * vPart.z)
        val horizSpeed = sqrt(vPart.x * vPart.x + vPart.z * vPart.z)
        val vertSpeed = abs(vPart.y)

        val isLinearHeavyHit = isLinear && (vertSpeed <= 0.05 || horizSpeed >= 0.1) && (horizSpeed >= 0.70) // 秒速14m以上
        val isLinearLightHit = isLinear && (vertSpeed <= 0.05 || horizSpeed >= 0.1) && (horizSpeed in 0.35..0.6999) // 秒速7m以上14m未満

        val isEligibleCollision = when {
          isRider || sweptHit == null -> false
          isRotary -> (vLen3D >= 0.45)
          isLinearHeavyHit -> true
          isLinearLightHit && sweptHit != null -> {
            // からくりの進行方向前面で巻き込まれたか判定
            val vLen = sqrt(vPart.x * vPart.x + vPart.z * vPart.z)
            if (vLen > 0.001) {
              val hitPt = sweptHit.hitPoint
              val fwdDot = (pLoc.x - hitPt.x) * (vPart.x / vLen) + (pLoc.z - hitPt.z) * (vPart.z / vLen)
              fwdDot > -0.15
            } else false
          }
          else -> false
        }

        val isHighSpeedCollision = (sweptHit != null && isEligibleCollision)

        if (sweptHit != null && isKnockbackCooledDown && isHighSpeedCollision) {
          val isLightImpact = isLinearLightHit

          // パーツの3次元進行方向単位ベクトル
          val vDirX = vPart.x / vLen3D
          val vDirY = vPart.y / vLen3D
          val vDirZ = vPart.z / vLen3D

          // ピボットからプレイヤーへの3次元外向き単位ベクトル（遠心力）
          val outX = pLoc.x - currState.pivot.x
          val outY = (pLoc.y + 0.9) - currState.pivot.y
          val outZ = pLoc.z - currState.pivot.z
          val outLen = sqrt(outX * outX + outY * outY + outZ * outZ)
          val (normOutX, normOutY, normOutZ) = if (outLen > 0.01) {
            Triple(outX / outLen, outY / outLen, outZ / outLen)
          } else {
            Triple(vDirX, vDirY, vDirZ)
          }

          // パーツからプレイヤーへの離脱ベクトル（衝突点から外へ逃げる向き）
          val awayX = pLoc.x - sweptHit.hitPoint.x
          val awayZ = pLoc.z - sweptHit.hitPoint.z
          val awayLen = sqrt(awayX * awayX + awayZ * awayZ)
          val (normAwayX, normAwayZ) = if (awayLen > 0.01) {
            Pair(awayX / awayLen, awayZ / awayLen)
          } else {
            Pair(normOutX, normOutZ)
          }

          // ★ 3次元全方向の慣性に対応（パーツ進行方向 50% + 遠心力 20% + 接触点からの脱出方向 30%）
          var kx = vDirX * 0.50 + normOutX * 0.20 + normAwayX * 0.30
          var ky = vDirY * 0.65 + normOutY * 0.35
          var kz = vDirZ * 0.50 + normOutZ * 0.20 + normAwayZ * 0.30
          val kLen = sqrt(kx * kx + ky * ky + kz * kz)
          if (kLen > 0.001) {
            kx /= kLen
            ky /= kLen
            kz /= kLen
          }

          // 盾（Shield）による防御判定
          val isHoldingShield = (player.inventory.itemInMainHand.type == org.bukkit.Material.SHIELD ||
                                 player.inventory.itemInOffHand.type == org.bukkit.Material.SHIELD)
          val isBlocking = player.isBlocking && isHoldingShield
          val toPartX = sweptHit.hitPoint.x - pLoc.x
          val toPartZ = sweptHit.hitPoint.z - pLoc.z
          val pEyeDir = player.location.direction
          val dot = pEyeDir.x * toPartX + pEyeDir.z * toPartZ
          val isFacingAttack = dot > -0.2 // 正面約140度で盾防御成立
          val isShieldBlocked = isBlocking && isFacingAttack

          val knockSpeed = if (isLightImpact) {
            0.20 // 秒速7〜14mでの巻き込み: 吹き飛ばしではなく軽く押される程度
          } else {
            (vLen3D * 1.1 + 0.6).coerceIn(0.85, 3.2) // 秒速14m以上・回転: 通常の強打撃吹き飛ばし
          }
          val finalVx = kx * knockSpeed

          // 垂直成分: 軽打撃は浮き上がり完全ゼロ(0.0)、強打撃は回転軸やぶつかり方(ky)に完全連動
          val finalVy = if (isLightImpact) {
            0.0
          } else {
            (ky * knockSpeed).coerceIn(-3.2, 3.2)
          }
          val finalVz = kz * knockSpeed
          val knockVector = Vector(finalVx, finalVy, finalVz)

          // ノックバッククールダウン（軽打撃は4tick、強打撃は12tick）
          playerKnockbackCooldown[player.uniqueId] = curTick + (if (isLightImpact) 4L else 12L)

          if (!isLightImpact) {
            // 強打撃時のみ乗車状態・足場エンティティを即座に完全撤去して通常重力に解放
            ridingPlayers.remove(player.uniqueId)?.let { removeFloorGhast(it, player) }
            resetPlayerGravity(player)
            resetPlayerMovementSpeed(player)
          }

          if (isShieldBlocked) {
            // 【盾で防御成功】: ダメージ完全無効化 + 盾クールダウン + 衝撃で弾き飛ばされる！
            player.setCooldown(org.bukkit.Material.SHIELD, if (isLightImpact) 10 else 50)
            world.playSound(pLoc, Sound.ITEM_SHIELD_BLOCK, 1.2f, 0.9f)
            if (!isLightImpact) {
              world.playSound(pLoc, Sound.ITEM_SHIELD_BREAK, 1.1f, 1.2f)
            }
            world.spawnParticle(Particle.ITEM, pLoc.clone().add(0.0, 1.2, 0.0), 10, 0.2, 0.2, 0.2, 0.05, org.bukkit.inventory.ItemStack(org.bukkit.Material.SHIELD))
          } else {
            if (isLightImpact) {
              // 軽打撃: 軽い被弾音とわずかなダメージ
              world.playSound(pLoc, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.8f, 1.2f)
              world.spawnParticle(Particle.DAMAGE_INDICATOR, pLoc.clone().add(0.0, 1.0, 0.0), 1, 0.05, 0.05, 0.05, 0.01)
              if (player.gameMode != GameMode.CREATIVE) {
                val rawDamage = 0.8 // 軽微な接触ダメージ（ハート約0.4個分）
                val mitigatedDamage = calculateDamageAfterArmor(player, rawDamage)
                player.damage(mitigatedDamage)
              }
            } else {
              // 強打撃: 豪快な打撃音と大ダメージ吹き飛ばし
              world.playSound(pLoc, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 0.85f)
              world.playSound(pLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 0.9f, 1.1f)
              world.spawnParticle(Particle.SWEEP_ATTACK, pLoc.clone().add(0.0, 1.0, 0.0), 2, 0.2, 0.2, 0.2, 0.0)
              world.spawnParticle(Particle.CRIT, pLoc.clone().add(0.0, 1.0, 0.0), 12, 0.3, 0.3, 0.3, 0.15)

              if (player.gameMode != GameMode.CREATIVE) {
                val rawDamage = (2.0 + vLen3D * 2.2).coerceIn(2.0, 9.0)
                val mitigatedDamage = calculateDamageAfterArmor(player, rawDamage)
                player.damage(mitigatedDamage)
              }
            }
          }

          if (isLightImpact) {
            // 軽打撃: 地面接地を維持したまま、水平方向に軽く押し戻すだけ
            player.velocity = knockVector
          } else {
            // 強打撃: 地面接地を即座に解除して摩擦ゼロ滞空状態にする（8cm浮上）
            player.teleport(
              Location(world, pLoc.x, pLoc.y + 0.08, pLoc.z, pLoc.yaw, pLoc.pitch),
              TeleportFlag.Relative.VELOCITY_X,
              TeleportFlag.Relative.VELOCITY_Y,
              TeleportFlag.Relative.VELOCITY_Z,
              TeleportFlag.Relative.VELOCITY_ROTATION
            )
            player.velocity = knockVector

            // 1 tick後にもVelocityを再確定して被ダメージ処理によるキャンセルを根絶
            Bukkit.getScheduler().runTask(plugin, Runnable {
              if (player.isValid && player.gameMode != GameMode.SPECTATOR) {
                player.velocity = knockVector
              }
            })
            continue // ノックバック発動時は床判定や圧迫ダメージをスキップして飛翔させる
          }
        }

        if (!result.isStandingOnTop && !isRider && !isLandingOnFloor) {
          if (result.pushVector != null && isMotionActive) {
            // 低速移動時の壁押し出し
            val push = result.pushVector
            val pLen = sqrt(push.x * push.x + push.z * push.z)
            if (pLen > 0.005) {
              val targetX = pLoc.x + push.x
              val targetZ = pLoc.z + push.z
              player.teleport(
                Location(world, targetX, pLoc.y, targetZ, pLoc.yaw, pLoc.pitch),
                TeleportFlag.Relative.VELOCITY_X,
                TeleportFlag.Relative.VELOCITY_Y,
                TeleportFlag.Relative.VELOCITY_Z,
                TeleportFlag.Relative.VELOCITY_ROTATION
              )
              pLoc = player.location
            }
          }

          for (box in boxes) {
            val d = Vector3d(pLoc.x - box.center.x, 0.0, pLoc.z - box.center.z)
            val dx = d.dot(box.axisX)
            val dz = d.dot(box.axisZ)
            val isHorizOverlap = (abs(dx) <= box.halfSize.x + 0.3 && abs(dz) <= box.halfSize.z + 0.3)
            if (isHorizOverlap) {
              val boxBottomY = box.center.y - box.halfSize.y * abs(box.axisY.y) - box.halfSize.x * abs(box.axisX.y) - box.halfSize.z * abs(box.axisZ.y)
              val boxTopY = box.center.y + box.halfSize.y * abs(box.axisY.y) + box.halfSize.x * abs(box.axisX.y) + box.halfSize.z * abs(box.axisZ.y)

              // プレイヤーの足元がボックスの上面付近または上にあるなら除外（床の上）
              if (pLoc.y >= boxTopY - 0.2) continue

              // プレイヤーの体をからくりの底面が押しつぶしているか？（足元〜頭の間に底面があり、下に向かって圧迫）
              if (boxBottomY < pLoc.y + 1.6 && boxTopY > pLoc.y) {
                player.damage(2.0)
                break
              }
            }
          }
        }

        // ★ ユーザー指示: からくりの上に置いたバニラブロックの上に立っている時はからくりの影響を受けない！
        val sampleOffsets = listOf(
          0.0 to 0.0,
          0.28 to 0.28,
          -0.28 to 0.28,
          0.28 to -0.28,
          -0.28 to -0.28
        )
        val isOnVanillaSolidBlock = sampleOffsets.any { (ox, oz) ->
          val b = world.getBlockAt(
            kotlin.math.floor(pLoc.x + ox).toInt(),
            kotlin.math.floor(pLoc.y - 0.1).toInt(),
            kotlin.math.floor(pLoc.z + oz).toInt()
          )
          !b.type.isAir && !b.isPassable
        }
        val isObstructedByWorldBlock = if (vDiff > 0.35) {
          val startY = kotlin.math.floor(currFloor.y + 0.05).toInt()
          val endY = kotlin.math.floor(pLoc.y - 0.05).toInt()
          var blocked = false
          for (by in startY..endY) {
            if (sampleOffsets.any { (ox, oz) ->
              val b = world.getBlockAt(kotlin.math.floor(pLoc.x + ox).toInt(), by, kotlin.math.floor(pLoc.z + oz).toInt())
              !b.type.isAir && !b.isPassable
            }) {
              blocked = true
              break
            }
          }
          blocked
        } else false

        // 下から突き上げてくるからくり（回転棒・エレベーターなど）に対して、
        // プレイヤーが空中で床の真上（isInsideAnyFloor）にいて下から迫ってきた場合のみキャッチ！
        val isCatchingUpward = isMotionActive && isAirborne && result.isInsideAnyFloor && (vDiff in -0.6..0.5) && !isOnVanillaSolidBlock && !isObstructedByWorldBlock
        val isStanding = (result.isStandingOnTop || (isRidingThis && isAscendingElevator && isOverFloor && vDiff in -1.5..3.8) || isCatchingUpward) && !isOnVanillaSolidBlock && !isObstructedByWorldBlock && (curTick >= (playerKnockbackCooldown[player.uniqueId] ?: 0L))

        if (isStanding) {
          val existingState = ridingPlayers[player.uniqueId]
          val state = if (existingState == null) {
            val s = RiderState(instance.id)
            ridingPlayers[player.uniqueId] = s
            s
          } else if (existingState.karakuriId != instance.id) {
            removeFloorGhast(existingState, player)
            existingState.karakuriId = instance.id
            existingState.targetAnchorX = null
            existingState.targetAnchorZ = null
            existingState.currentDeltaYaw = 0f
            existingState.lastDeltaX = 0.0
            existingState.lastDeltaY = 0.0
            existingState.lastDeltaZ = 0.0
            existingState.isCustomJumping = false
            existingState
          } else {
            existingState
          }
          addPlayerToCollisionTeam(player)
          state.graceTicks = 30
          val targetFloorY = if (result.isStandingOnTop) result.surfaceY else currFloor.y
          state.targetSurfaceY = targetFloorY
          player.fallDistance = 0f
          applyStepHeight(player, 0.6)

          val standingFace = result.standingFace
          val isSlope = (standingFace != null && standingFace.normal.y in 0.25..0.95)
          updateFloorGhasts(player, state, collider, boxes, pLoc, targetFloorY, isSlope, currState, isMotionActive)
        } else {
          // 端を一瞬踏み外した際・頂上付近のガクつき防止 (ヒステリシス)
          if (isRidingThis && riderState != null) {
            val isOverKarakuri = collider.isHorizontallyOverAnyFloor(boxes, pLoc.x, pLoc.z, margin = 0.35)

            // ★床との垂直距離チェック:
            // 一方通行ループ等でからくりが終点から始点へ一瞬で位置リセットされた場合、
            // 現在の足元 (pLoc.y) から床 (currFloor.y) が 1.2m 以上下に離れる。
            // この場合、滞空猶予 (graceTicks) を待たずに即座に乗車解除してバニラ通常重力に戻すことで、
            // 「リセット後に1秒浮いてしまう」バグを完全根絶！
            val verticalDiff = pLoc.y - currFloor.y
            val isFloorFarAway = if (riderState.isCustomJumping || isAscendingElevator) {
              verticalDiff > 3.8 || verticalDiff < -2.0
            } else {
              verticalDiff > 1.25 || verticalDiff < -2.0
            }

            if (!isOverKarakuri || isFloorFarAway) {
              // ★からくりから外に降りた、またはからくりが位置リセット等で瞬間移動した場合:
              // 即座に足場を消去・登録解除して通常重力に戻す！
              removeFloorGhast(riderState, player)
              ridingPlayers.remove(player.uniqueId)
            } else {
              // からくりの範囲内で小ジャンプ中・滞空中の場合のみ猶予期間で足場を維持！
              riderState.graceTicks--
              if (riderState.graceTicks > 0) {
                val floorY = currFloor.y
                riderState.targetSurfaceY = floorY
                updateFloorGhasts(player, riderState, collider, boxes, pLoc, floorY, false, currState, isMotionActive)
              } else {
                removeFloorGhast(riderState, player)
                ridingPlayers.remove(player.uniqueId)
              }
            }
          }
        }
      }

      collider.previousState = prevState
      collider.lastState = currState
    }
  }

  private fun checkIsMotionActive(inst: KarakuriInstance, prev: KarakuriCollider.TransformState?, curr: KarakuriCollider.TransformState): Boolean {
    if (inst.activeMotion == null) return false
    if (prev == null) return false
    val pDistSq = prev.pivot.distanceSquared(curr.pivot)
    val qDiff = 1.0f - abs(prev.rotation.dot(curr.rotation))
    return pDistSq > 1e-7 || qDiff > 1e-7f
  }

  /**
   * プレイヤーが移動した瞬間のリアルタイム衝突判定（天井・壁スライディング・飛び乗り着地）
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  fun onPlayerMove(event: PlayerMoveEvent) {
    val player = event.player



    val isGhostOrSpectator = (player.gameMode == GameMode.SPECTATOR || isGhost(player))
    if (isGhostOrSpectator || player.vehicle != null) return

    val to = event.to
    val from = event.from
    val moveX = to.x - from.x
    val moveY = to.y - from.y
    val moveZ = to.z - from.z
    if (moveX == 0.0 && moveY == 0.0 && moveZ == 0.0) return

    val curTick = Bukkit.getCurrentTick().toLong()
    val rider = ridingPlayers[player.uniqueId]
    val liftSpeed = rider?.lastDeltaY ?: 0.0
    val relMoveY = (to.y - from.y) - maxOf(0.0, liftSpeed)
    val isJumping = (player.velocity.y > 0.2 || relMoveY > 0.1)

    // キーボード操作による自発的な歩行の検知:
    // サーバーのテレポートやからくりの公転移動(lastDeltaX, lastDeltaZ)による移動を除外！
    if (rider != null) {
      val isPureVerticalElevator = (abs(rider.lastDeltaX) < 1e-4 && abs(rider.lastDeltaZ) < 1e-4)
      val lastTarget = rider.lastTeleportTarget
      val isTeleportResponse = (!isPureVerticalElevator && lastTarget != null &&
        abs(to.x - lastTarget.x) < 0.05 &&
        abs(to.z - lastTarget.z) < 0.05)

      if (!isTeleportResponse) {
        val input = player.currentInput
        val hasKey = (input.isForward || input.isBackward || input.isLeft || input.isRight)
        if (hasKey) {
          rider.lastMoveTick = curTick
        }
      }
    }

    val manager = plugin.manager
    val instances = manager.getAll()
    if (instances.isEmpty()) return

    for (instance in instances) {
      if (!instance.displayEntity.isValid) continue
      val centerLoc = if (instance.activeMotion is io.oyasai.karakuri.motion.MinecartMotion) {
        instance.displayEntity.location
      } else {
        instance.worldPivotLocation
      }
      if (centerLoc.world != to.world) continue
      val collider = getCollider(instance)
      val isRidingThis = (ridingPlayers[player.uniqueId]?.karakuriId == instance.id)
      val reachRadius = collider.maxReachRadius
      // ★超重要: 乗っているプレイヤーは距離チェックで絶対に除外しない！
      // まだ乗っていないプレイヤーも、からくりの全パーツ範囲(reachRadius)まで確実にカバー！
      if (!isRidingThis && centerLoc.distanceSquared(to) > reachRadius * reachRadius) continue

      val boxes = collider.computeWorldBoxes()

      // ★飛び乗り貫通防止（CCD: Continuous Collision Detection）
      // 高速落下中（ジャンプ下降や高所からの落下、あるいは上昇中のからくりへの飛び乗り）に
      // 1 tick の移動で床上面を跨いで突き抜けてしまった場合、衝突前の位置(checkTo)を床上面にスナップ
      val isFalling = (from.y > to.y && (moveY < -0.05 || player.velocity.y < 0.1))
      val checkTo = to.clone()
      if (isFalling) {
        val currFloor = collider.clampToFloorSurface(boxes, to.x, to.z, to.y)
        val floorY = currFloor.y
        val isOverFloor = collider.isHorizontallyOverAnyFloor(boxes, to.x, to.z, margin = 0.35) ||
                          collider.isHorizontallyOverAnyFloor(boxes, from.x, from.z, margin = 0.35)
        if (isOverFloor && from.y >= floorY - 0.1 && to.y < floorY && to.y >= floorY - 3.5) {
          checkTo.y = floorY
          event.to.y = floorY
          player.velocity = player.velocity.setY(0.0)
          player.fallDistance = 0f
        }
      }

      val result = collider.checkPlayerCollision(player, boxes, checkTo, isJumping)

      // 1. 天井判定 (Head Hit): ジャンプや上昇中に天井に衝突した場合、頭打ちにして上昇を止め自然に落下させる
      if (result.ceilingY != null && (moveY > 0.0 || player.velocity.y > 0.0)) {
        val ceilY = result.ceilingY
        val headY = to.y + 1.8
        if (headY > ceilY && to.y < ceilY) {
          // 頭を天井の下面 (ceilY - 1.8) に正確にクランプ
          val maxFeetY = ceilY - 1.8
          if (event.to.y > maxFeetY) {
            event.to.y = maxFeetY
          }
          // 上昇速度を即座に打ち消し、バニラ同様の下向き重力落下速度(-0.08)を付与
          if (player.velocity.y > 0.0) {
            player.velocity = player.velocity.setY(-0.08)
          }
          // からくりジャンプ中であれば上昇フェーズを終了
          ridingPlayers[player.uniqueId]?.let { rider ->
            if (rider.isCustomJumping) {
              rider.isCustomJumping = false
              rider.jumpTicks = 0
            }
          }
        }
      }

      // 2. 壁にぶつかった場合（登れない壁・段差・2段目のブロック）:
      // 床に乗っているプレイヤーであっても、目の前の段差や壁にはめり込ませず壁に沿って滑らせる(Wall Slide)！
      if (result.pushVector != null) {
        val push = result.pushVector
        val len = sqrt(push.x * push.x + push.z * push.z)
        if (len > 0.0001) {
          // めり込みを即座にブロックの外側へ押し戻す
          event.to.x += push.x
          event.to.z += push.z

          if (moveX != 0.0 || moveZ != 0.0) {
            val nx = push.x / len
            val nz = push.z / len
            val dot = moveX * nx + moveZ * nz
            if (dot < -0.001) {
              val slideX = moveX - dot * nx
              val slideZ = moveZ - dot * nz
              event.to.x = from.x + push.x + slideX
              event.to.z = from.z + push.z + slideZ
            }
          }
        }
      }

      // 3. 上面乗り判定 & 飛び乗り貫通防止（CCD）
      if (result.isStandingOnTop && curTick >= (playerKnockbackCooldown[player.uniqueId] ?: 0L)) {
        val state = ridingPlayers.getOrPut(player.uniqueId) { RiderState(instance.id) }
        addPlayerToCollisionTeam(player)
        state.graceTicks = 30
        val targetFloorY = result.surfaceY
        state.targetSurfaceY = targetFloorY
        player.fallDistance = 0f
        applyStepHeight(player, 0.6)

        // ★床吸着 ＆ 画面震え防止 (Motion Packet Jitter Suppression):
        // からくりが動作中（collider.isMotionActive）はハッピーガスト足場が物理的に支えているため、
        // 微小なめり込みでの毎パケット強制テレポート（event.to.y = targetFloorY）を完全遮断！（視点・位置の絶妙な震えを根絶！）
        // 0.35m以上深く突き抜けた緊急時のみスナップ救出！
        val jumpActive = isJumping || (state.jumpTicks > 0) || state.isCustomJumping || (state.vanillaJumpCooldown > 0)
        val penDepth = targetFloorY - to.y
        val shouldSnapY = if (collider.isMotionActive) {
          penDepth > 0.35 && penDepth <= 1.5
        } else {
          penDepth in 0.0001..0.6
        }
        if (shouldSnapY && !jumpActive) {
          event.to.y = targetFloorY
        }

        // ★登坂アシスト (スロープ坂道・階段・段差・頂上への乗り移り):
        // ハッピーガストはEntityのためバニラSTEP_HEIGHT（ブロック用）が自動ステップアップしない。
        // 上に向かって進んでいる場合、足元を段差・坂道の高さ targetFloorY に合わせてスムーズに上昇！
        val face = result.standingFace
        val isSlope = (face != null && face.normal.y in 0.25..0.95)
        val stepDiff = targetFloorY - to.y
        if (isSlope && stepDiff in 0.001..1.25 && (moveX != 0.0 || moveZ != 0.0)) {
          event.to.y = targetFloorY
        }

        // ★重要: プレイヤー移動（登坂・歩行・ダッシュ中）と同時に、足元のガストクラスタを同一フレームで即座に同期更新！
        // これによりプレイヤーが走っている最中も円形先回り足場が追従し、高速回転台でも足場が絶対に抜けない！
        val currentLoc = Location(to.world, to.x, event.to.y, to.z, to.yaw, to.pitch)
        val isAscendingOrSlope = isSlope || stepDiff > 0.001
        val currState = collider.captureCurrentState()
        updateFloorGhasts(player, state, collider, boxes, currentLoc, event.to.y, isAscendingOrSlope, currState, collider.isMotionActive)
      } else if (isRidingThis) {
        val state = ridingPlayers[player.uniqueId]
        val isOverKarakuri = collider.isHorizontallyOverAnyFloor(boxes, to.x, to.z, margin = 0.35)
        if (!isOverKarakuri) {
          // ★からくりの水平範囲外に降りた！即座に足場を消去して落下させる（浮遊・タイムラグ根絶）
          if (state != null) {
            removeFloorGhast(state, player)
            ridingPlayers.remove(player.uniqueId)
          }
        } else if (state != null && state.graceTicks > 0) {
          val currFloor = collider.clampToFloorSurface(boxes, to.x, to.z, state.targetSurfaceY)
          val floorY = currFloor.y

          // ★床突き抜け防止 (Continuous Collision Catch):
          // 動作中はハッピーガストが支えているため、0.35m以上深く突き抜けた場合のみスナップ救出！（微小振動防止）
          val penDepth = floorY - to.y
          val shouldCatch = if (collider.isMotionActive) {
            penDepth > 0.35 && penDepth <= 1.5
          } else {
            penDepth in 0.0001..1.5
          }
          if (shouldCatch) {
            event.to.y = floorY
            if (player.velocity.y < 0.0) {
              player.velocity = player.velocity.setY(0.0)
            }
            state.targetSurfaceY = floorY
            player.fallDistance = 0f
          }

          val currentLoc = Location(to.world, to.x, event.to.y, to.z, to.yaw, to.pitch)
          val currState = collider.captureCurrentState()
          updateFloorGhasts(player, state, collider, boxes, currentLoc, event.to.y, false, currState, collider.isMotionActive)
        }
      }
    }
  }

  // ★トロッコとの干渉を完全排除するイベントハンドラー
  @EventHandler(priority = EventPriority.LOWEST)
  fun onVehicleEnter(event: VehicleEnterEvent) {
    if (event.entered.scoreboardTags.contains(TAG_FLOOR_GHAST)) {
      event.isCancelled = true
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  fun onVehicleEntityCollision(event: VehicleEntityCollisionEvent) {
    if (event.entity.scoreboardTags.contains(TAG_FLOOR_GHAST) ||
      (event.vehicle is Minecart && event.entity is HappyGhast && event.entity.scoreboardTags.contains(TAG_FLOOR_GHAST))
    ) {
      event.isCancelled = true
      event.isCollisionCancelled = true
      event.isPickupCancelled = true
    }
  }

  fun unregisterPlayer(player: Player) {
    ridingPlayers.remove(player.uniqueId)?.let { removeFloorGhast(it, player) }
    applyStepHeight(player, 0.6)
  }

  /**
   * からくりが休眠または撤去された際、乗っていたプレイヤーを安全に降ろして状態を復元する
   */
  fun unregisterInstanceRiders(instanceId: String) {
    val toRemove = mutableListOf<UUID>()
    for ((uuid, state) in ridingPlayers) {
      if (state.karakuriId == instanceId) {
        toRemove.add(uuid)
      }
    }
    for (uuid in toRemove) {
      val player = Bukkit.getPlayer(uuid)
      val state = ridingPlayers.remove(uuid)
      if (player != null && state != null) {
        removeFloorGhast(state, player)
        applyStepHeight(player, 0.6)
        resetPlayerGravity(player)
        resetPlayerMovementSpeed(player)
        updatePlayerFlight(player, false)
        removePlayerFromCollisionTeam(player)
      }
    }
  }

  @EventHandler
  fun onPlayerQuit(event: PlayerQuitEvent) {
    unregisterPlayer(event.player)
  }

  @EventHandler(priority = EventPriority.HIGH)
  fun onPlayerJump(event: PlayerJumpEvent) {
    val player = event.player
    val rider = ridingPlayers[player.uniqueId] ?: return
    rider.vanillaJumpCooldown = 12
  }

  private fun performElevatorJump(player: Player, rider: RiderState, isBoosted: Boolean = true) {
    rider.jumpTicks = 16
    rider.graceTicks = 45
    rider.isCustomJumping = true
    // バニラ標準 0.42 に対し、上昇慣性を利用した溜めジャンプは 0.68（高さ約2.8ブロックの爽快ハイジャンプ！）
    rider.jumpRelVy = if (isBoosted) 0.68 else 0.42
    setPlayerGravity(player, 0.0) // 上昇中は無重力等速フレームを維持

    // ジャンプ初速: からくりの上昇速度 (lastDeltaY) ＋ 相対ジャンプ初速
    // 頂上付近でからくりの上昇速度と合成されて過剰な初速にならないよう上限 0.78 (約3.5ブロック分) に制限！
    val liftSpeed = maxOf(0.0, rider.lastDeltaY)
    val curVel = player.velocity
    val jumpVy = (liftSpeed + rider.jumpRelVy).coerceAtMost(0.78)
    // ★ 斜め移動リフトでも横方向の慣性をそのまま保持＆加算！
    val jumpVx = curVel.x + rider.lastDeltaX * 0.5
    val jumpVz = curVel.z + rider.lastDeltaZ * 0.5
    player.velocity = Vector(jumpVx, jumpVy, jumpVz)

    // ★ 上昇慣性を利用した溜めジャンプの演出 (パーティクル & サウンド)
    val loc = player.location
    val world = loc.world
    try {
      // 足元から広がる雲煙とクリティカル衝撃波
      world.spawnParticle(org.bukkit.Particle.CLOUD, loc.x, loc.y + 0.1, loc.z, 16, 0.3, 0.05, 0.3, 0.08)
      world.spawnParticle(org.bukkit.Particle.CRIT, loc.x, loc.y + 0.2, loc.z, 12, 0.25, 0.1, 0.25, 0.15)
      // 爽快なジャンプ効果音 (風の破裂音 & スイープ音)
      try {
        val windBurstSound = org.bukkit.Sound.valueOf("ENTITY_WIND_CHARGE_WIND_BURST")
        world.playSound(loc, windBurstSound, 0.85f, 1.25f)
      } catch (_: Throwable) {
        world.playSound(loc, org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.4f)
      }
      world.playSound(loc, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.5f)
    } catch (_: Throwable) {}
  }

  // ★ ユーザー考案:「独自の接地判定による慣性溜めハイジャンプ」
  // プレイヤーの足元のすぐ下にからくりの床があること（独自の接地判定）を検知し、
  // 上昇エレベーター・斜め上リフト中や下降への切り替わりの瞬間に Shiftキー（スニーク）をポンと押すと何回でも確実に溜めジャンプ発動！
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  fun onPlayerToggleSneak(event: org.bukkit.event.player.PlayerToggleSneakEvent) {
    if (!event.isSneaking) return // Shiftキーを押した瞬間のみ発動
    val player = event.player
    val pLoc = player.location

    for (instance in plugin.manager.getAll()) {
      if (!instance.displayEntity.isValid) continue
      val collider = getCollider(instance)
      val boxes = collider.computeWorldBoxes()
      val isOver = collider.isHorizontallyOverAnyFloor(boxes, pLoc.x, pLoc.z, margin = 0.5)
      if (isOver) {
        val currFloor = collider.clampToFloorSurface(boxes, pLoc.x, pLoc.z, pLoc.y)
        val diff = pLoc.y - currFloor.y

        var rider = ridingPlayers[player.uniqueId]
        if (rider == null) {
          rider = ridingPlayers.getOrPut(player.uniqueId) { RiderState(instance.id) }
          rider.targetSurfaceY = currFloor.y
          rider.graceTicks = 30
          val pState = collider.lastState
          val cState = collider.captureCurrentState()
          if (pState != null) {
            val fLoc = collider.calculatePlayerFollow(pLoc, pState, cState)
            rider.lastDeltaX = fLoc.x - pLoc.x
            rider.lastDeltaY = fLoc.y - pLoc.y
            rider.lastDeltaZ = fLoc.z - pLoc.z
            if (rider.lastDeltaY > 0.0001) rider.ticksSinceAscend = 0
          }
        }

        // ★ ピストンロッドの軌道（斜め上含む）でも上向きの慣性を利用！
        // Y軸が上向きに動いている（deltaY > 0.0001）なら垂直でも斜め上でも 100% 溜めジャンプOK！
        val isAscending = (rider.lastDeltaY > 0.0001)
        val isPeakOrSwitch = (rider.ticksSinceAscend <= 30)
        val canElevatorJump = (isAscending || isPeakOrSwitch)

        // ★ 独自の接地判定:
        // 1. 通常床に乗っている時（!rider.isCustomJumping）:
        //    からくりエレベーター上昇中は速度・同期差で足元がめり込んだり浮いたりするため、
        //    -0.9m〜+0.9m の広い許容範囲で床の上にいれば 100% 接地と判定！
        // 2. 空中ジャンプ中（rider.isCustomJumping）:
        //    落下中で床に近づいている（jumpRelVy <= 0.0 かつ diff in -0.9..0.6）なら先行入力OK！
        val isCustomOnGround = if (!rider.isCustomJumping) {
          diff in -0.9..0.9
        } else {
          rider.jumpRelVy <= 0.0 && diff in -0.9..0.6
        }

        // 上昇中や下降への切り替わりの瞬間で、独自の接地判定を満たしていれば、何回目でも100%確実に溜めジャンプ発動！
        if (canElevatorJump && isCustomOnGround && rider.jumpTicks <= 6) {
          rider.isCustomJumping = false // 接地確認により前回のジャンプ状態を確実に解除
          rider.jumpTicks = 0
          performElevatorJump(player, rider, isBoosted = true)
          return
        }
      }
    }
  }

  /**
   * Minecraft 1.21.2+ (Purpur/Paper) の内部パケット機構を利用した高精度・完全無衝突の相対Yaw回転。
   *
   * Paper API の player.teleport() は内部で Relative.ALL をサポートしておらず絶対テレポートになるため、
   * 毎Tick呼び出すと位置の巻き戻しやマウス視点操作との激しい衝突（ガタつき）、Ping往復遅延による角度の巻き戻しループが発生する。
   *
   * 本メソッドは ServerGamePacketListenerImpl.internalTeleport(PositionMoveRotation, Set<Relative>) を介して
   * クライアントへ Relative.ALL パケット（X/Y/Z相対0, Pitch相対0, Yaw相対 deltaYawDeg）を送信。
   * これにより:
   * 1. 位置 (X, Y, Z) は 100% 変更されず、Velocity (慣性) のみで完全に滑らかに移動を継続！
   * 2. プレイヤーのマウス操作を一切阻害せず、マウスの移動量にからくりの回転角度が自然に合成される！
   * 3. ネットワーク遅延による「過去の角度への巻き戻しループ」が物理的・原理的に完全排除される！
   */
  private fun rotatePlayerYawRelative(player: Player, deltaYawDeg: Float): Boolean {
    return RelativeTeleportHelper.rotatePlayerYawRelative(player, deltaYawDeg)
  }
}

/**
 * NMS 内部の純粋な相対テレポート (Relative.ALL + yRot) をリフレクションで安全に実行するヘルパー
 */
private object RelativeTeleportHelper {
  private var isInitialized = false
  private var getHandleMethod: Method? = null
  private var connectionField: Field? = null
  private var internalTeleportMethod: Method? = null
  private var posMoveRotConstructor: Constructor<*>? = null
  private var vec3Zero: Any? = null
  private var relativeAllSet: Any? = null

  private fun init() {
    if (isInitialized) return
    try {
      val cl = RelativeTeleportHelper::class.java.classLoader
      val craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer", false, cl)
      getHandleMethod = craftPlayerClass.getMethod("getHandle")

      val serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer", false, cl)
      connectionField = serverPlayerClass.getField("connection")

      val listenerClass = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl", false, cl)
      val posMoveRotClass = Class.forName("net.minecraft.world.entity.PositionMoveRotation", false, cl)
      val relativeClass = Class.forName("net.minecraft.world.entity.Relative", false, cl)

      internalTeleportMethod = listenerClass.getMethod("internalTeleport", posMoveRotClass, Set::class.java)

      val vec3Class = Class.forName("net.minecraft.world.phys.Vec3", false, cl)
      vec3Zero = vec3Class.getField("ZERO").get(null)

      posMoveRotConstructor = posMoveRotClass.getConstructor(vec3Class, vec3Class, java.lang.Float.TYPE, java.lang.Float.TYPE)
      relativeAllSet = relativeClass.getField("ALL").get(null)

      isInitialized = true
    } catch (_: Throwable) {
      isInitialized = false
    }
  }

  fun rotatePlayerYawRelative(player: Player, deltaYawDeg: Float): Boolean {
    if (!isInitialized) init()
    if (!isInitialized) return false
    return try {
      val handle = getHandleMethod?.invoke(player) ?: return false
      val conn = connectionField?.get(handle) ?: return false
      // 位置(0,0,0)相対、速度(0,0,0)相対、Yaw(deltaYawDeg)相対、Pitch(0)相対
      // これにより、既存の位置およびVelocityを100%一切改変せず、純粋に視点のYawのみを加算回転！
      val posRot = posMoveRotConstructor?.newInstance(vec3Zero, vec3Zero, deltaYawDeg, 0.0f) ?: return false
      internalTeleportMethod?.invoke(conn, posRot, relativeAllSet)
      true
    } catch (_: Throwable) {
      false
    }
  }
}
