package io.oyasai.karakuri.engine

import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * からくり組み込みカーペット専用の二重ハイブリッド座席マネージャー。
 *
 * 1. 非回転時（静止・直線移動・トロッコ等）:
 *    - 不可視マーカー防具立て (ArmorStand: 台座なし) にプレイヤーが搭乗。
 *    - 自由な視線見回しを維持し、慣性 (velocity) 駆動 + 周期テレポート補正で滑らかに追従。
 *
 * 2. 回転時（水車・風車・プログラム回転等）:
 *    - 台座なし不可視マーカー防具立てに乗車したマネキン (Mannequin: EntityType.MANNEQUIN) を生成。
 *    - 乗車によってマネキン自身がバニラの正規座りポーズ（足を曲げた着席姿勢）になる。
 *    - プレイヤーをスペクテイターモードに切り替え、マネキンに憑依 (/spectate)。
 *    - 毎Tick防具立てを公転テレポートさせ、マネキンは setRotation で首・視線を同期。
 *
 * 3. カーペット専用 Interaction エンティティ:
 *    - 中心軸から離れた位置のカーペットにも常に Interaction を追従配置し、
 *      素手右クリックパケットが確実にサーバーへ届くよう保証。
 *
 * 4. 高速公転補正:
 *    - クライアント側のエンティティ補間遅延（1 tick遅れ）を相殺するリード角補正を適用。
 */
class KarakuriSeatManager(private val manager: KarakuriManager) {

  companion object {
    const val TAG_CARPET_INTERACTION = "karakuri_carpet_seat_inter"
  }

  data class SeatSession(
    val playerId: UUID,
    val instanceId: String,
    val carpetLocalLoc: Location,
    val carpetScaleY: Float,
    var originalGameMode: GameMode,
    var initialPlayerYaw: Float,
    var accumulatedYaw: Float,
    var isSpectating: Boolean,
    var seatEntity: Entity?,
    var mannequinEntity: Entity? = null,
    var lastTeleportTick: Long = 0L,
    var transitionGraceTicks: Int = 10,
  )

  data class CarpetHit(
    val instance: KarakuriInstance,
    val blockLoc: Location,
    val scaleY: Float,
    val hitDistanceSq: Double,
  )

  private val sessions = ConcurrentHashMap<UUID, SeatSession>()
  // からくりごとのカーペット専用クリック判定 Interaction エンティティ (からくりID -> (ブロック位置 -> Interaction))
  private val carpetInteractions = ConcurrentHashMap<String, MutableMap<Location, Interaction>>()
  // 視点公転追従（スペクテイター憑依）を無効化し、自由見回しモードにしているプレイヤー
  private val spectateDisabledPlayers = ConcurrentHashMap.newKeySet<UUID>()
  // 座席モードの動的切り替え（非回転 ↔ 回転）の最中にあるプレイヤー（EntityDismountEvent 等の誤爆を100%遮断）
  private val transitioningPlayers = ConcurrentHashMap.newKeySet<UUID>()

  fun isTransitioning(player: Player): Boolean = transitioningPlayers.contains(player.uniqueId)

  fun isSpectateSeatEnabled(player: Player): Boolean {
    return !spectateDisabledPlayers.contains(player.uniqueId)
  }

  fun toggleSpectateSeat(player: Player): Boolean {
    val enabled = if (spectateDisabledPlayers.contains(player.uniqueId)) {
      spectateDisabledPlayers.remove(player.uniqueId)
      true
    } else {
      spectateDisabledPlayers.add(player.uniqueId)
      false
    }

    // 現在着席中なら即座に座席モードを切り替える
    val session = sessions[player.uniqueId]
    if (session != null) {
      val instance = manager.get(session.instanceId)
      if (instance != null && instance.isValid()) {
        val targetTopLoc = getCarpetTopWorldLocation(instance, session.carpetLocalLoc, session.carpetScaleY)
        val shouldSpectate = enabled && isCurrentlyRotating(instance)
        if (shouldSpectate != session.isSpectating) {
          switchSeatMode(player, session, shouldSpectate, targetTopLoc)
        }
      }
    }
    return enabled
  }

  fun getSession(player: Player): SeatSession? = sessions[player.uniqueId]

  fun isSeated(player: Player): Boolean = sessions.containsKey(player.uniqueId)

  /**
   * プレイヤーの視線から最も手前にあるからくりブロックをレイキャストし、
   * それがカーペットであれば CarpetHit を返す。
   */
  fun rayTraceCarpet(player: Player, maxDistance: Double = 4.5): CarpetHit? {
    val world = player.world
    val eyeLoc = player.eyeLocation
    val startVec = eyeLoc.toVector()
    val dir = eyeLoc.direction.normalize()

    var closestHitDistSq = maxDistance * maxDistance
    var closestIsCarpet = false
    var closestHit: CarpetHit? = null

    for (instance in manager.getAll()) {
      if (!instance.isValid() || instance.displayEntity.world != world) continue

      val curRot = instance.currentRotation
      val pivotLoc = instance.worldPivotLocation

      // からくりを構成するブロックを収集
      val allParts = mutableListOf<Triple<Location, org.bukkit.block.data.BlockData?, Float>>()
      allParts.add(Triple(instance.originLocation, instance.originalBlockData, instance.scale.y))
      for (part in instance.additionalParts) {
        if (part.displayEntity.isValid) {
          allParts.add(Triple(part.originLocation, part.originalBlockData, part.partScale.y))
        }
      }

      for ((origLoc, blockData, scaleY) in allParts) {
        val mat = blockData?.material ?: Material.AIR
        val isCarpet = mat.name.contains("CARPET")

        // 現在のカーペット上面中心のワールド座標を高精度幾何計算
        val curTopLoc = getCarpetTopWorldLocation(instance, origLoc, scaleY)
        val h = if (isCarpet) (0.0625 * scaleY).coerceAtLeast(0.15) else 1.0
        val halfH = h * 0.5
        val box = BoundingBox(
          curTopLoc.x - 0.55, curTopLoc.y - h - 0.05, curTopLoc.z - 0.55,
          curTopLoc.x + 0.55, curTopLoc.y + 0.05, curTopLoc.z + 0.55
        )

        val hit = box.rayTrace(startVec, dir, maxDistance)
        if (hit != null) {
          val distSq = startVec.distanceSquared(hit.hitPosition)
          if (distSq < closestHitDistSq) {
            closestHitDistSq = distSq
            closestIsCarpet = isCarpet
            if (isCarpet) {
              closestHit = CarpetHit(instance, origLoc.clone(), scaleY, distSq)
            } else {
              closestHit = null
            }
          }
        }
      }
    }

    return if (closestIsCarpet) closestHit else null
  }

  /**
   * カーペットに着席
   */
  fun sit(player: Player, instance: KarakuriInstance, carpetBlockLoc: Location, carpetScaleY: Float): Boolean {
    // 既存セッションの安全終了
    if (isSeated(player)) {
      dismount(player)
    }

    val curTopLoc = getCarpetTopWorldLocation(instance, carpetBlockLoc, carpetScaleY)
    val isRotating = isCurrentlyRotating(instance)
    val shouldSpectate = isRotating && isSpectateSeatEnabled(player)
    val initialYaw = player.location.yaw

    val session = SeatSession(
      playerId = player.uniqueId,
      instanceId = instance.id,
      carpetLocalLoc = carpetBlockLoc.clone(),
      carpetScaleY = carpetScaleY,
      originalGameMode = player.gameMode,
      initialPlayerYaw = initialYaw,
      accumulatedYaw = initialYaw,
      isSpectating = shouldSpectate,
      seatEntity = null,
      mannequinEntity = null,
      transitionGraceTicks = 10
    )
    sessions[player.uniqueId] = session

    if (shouldSpectate) {
      spawnMannequinSeat(player, session, curTopLoc)
    } else {
      spawnArmorStandSeat(player, session, curTopLoc)
    }

    player.playSound(curTopLoc, Sound.BLOCK_WOOL_STEP, 1.0f, 1.0f)
    player.sendActionBar(Component.text("🪑 カーペットに着席しました (Shiftで降車)", NamedTextColor.GREEN))
    return true
  }

  private fun spawnMannequinSeat(player: Player, session: SeatSession, topLoc: Location) {
    session.transitionGraceTicks = 20
    val world = topLoc.world ?: return
    val instance = manager.get(session.instanceId)
    val upVector = if (instance != null) getCarpetUpVector(instance, useLead = false) else Vector3f(0f, 1f, 0f)
    val isUpsideDown = upVector.y < -0.3f
    val yOffset = if (isUpsideDown) -1.0 else 0.0

    val seatY = topLoc.y + yOffset
    val spawnLoc = Location(world, topLoc.x, seatY, topLoc.z, session.accumulatedYaw, 0f)

    // 1. マネキンを乗せるための不可視マーカー防具立てを生成（台座なし setBasePlate(false)）
    val armorStand = world.spawn(spawnLoc, ArmorStand::class.java) { stand ->
      stand.isInvisible = true
      stand.isMarker = true
      stand.isInvulnerable = true
      stand.setGravity(false)
      stand.isSmall = true
      stand.setBasePlate(false) // 台座を完全消滅！
      stand.isCustomNameVisible = false
      stand.addScoreboardTag("karakuri_seat_${player.uniqueId}")
    }

    // 2. プレイヤースキンのマネキンを生成
    val mannequin = world.spawn(spawnLoc, Mannequin::class.java) { entity ->
      try {
        entity.profile = ResolvableProfile.resolvableProfile(player.playerProfile)
      } catch (_: Exception) {}
      entity.isInvulnerable = true
      entity.setImmovable(true)
      entity.setGravity(false)
      if (isUpsideDown) {
        entity.customName(Component.text("Dinnerbone"))
      }
      entity.isCustomNameVisible = false
      entity.addScoreboardTag("karakuri_seat_${player.uniqueId}")
    }

    // 3. 防具立てにマネキンを乗車させる（バニラの正規乗車姿勢となり、足を曲げて座る！）
    armorStand.addPassenger(mannequin)

    session.seatEntity = armorStand
    session.mannequinEntity = mannequin
    session.isSpectating = true

    // 4. プレイヤーをスペクテイターモードにしてマネキンに憑依
    player.gameMode = GameMode.SPECTATOR
    try {
      player.spectatorTarget = mannequin
    } catch (_: Exception) {}

    // Paperの内部パケット同期遅延に備え、1 tick 後にも spectatorTarget を再保証
    org.bukkit.Bukkit.getScheduler().runTaskLater(io.oyasai.karakuri.KarakuriPlugin.instance, Runnable {
      if (session.isSpectating && player.isOnline && mannequin.isValid) {
        if (player.spectatorTarget != mannequin) {
          try {
            player.spectatorTarget = mannequin
          } catch (_: Exception) {}
        }
      }
    }, 1L)
  }

  private fun spawnArmorStandSeat(player: Player, session: SeatSession, topLoc: Location) {
    session.transitionGraceTicks = 20
    val world = topLoc.world ?: return
    val seatY = topLoc.y
    val spawnLoc = Location(world, topLoc.x, seatY, topLoc.z, session.accumulatedYaw, 0f)

    val armorStand = world.spawn(spawnLoc, ArmorStand::class.java) { stand ->
      stand.isInvisible = true
      stand.isMarker = true
      stand.isInvulnerable = true
      stand.setGravity(false)
      stand.isSmall = true
      stand.setBasePlate(false) // 台座を完全消滅！
      stand.isCustomNameVisible = false
      stand.addScoreboardTag("karakuri_seat_${player.uniqueId}")
    }

    session.seatEntity = armorStand
    session.mannequinEntity = null
    session.isSpectating = false
    armorStand.addPassenger(player)
  }

  /**
   * 非回転座席（防具立て搭乗）と回転座席（マネキン搭乗＋スペクテイター憑依）を安全に動的相互切り替え
   */
  fun switchSeatMode(
    player: Player,
    session: SeatSession,
    toSpectator: Boolean,
    targetTopLoc: Location
  ) {
    if (!player.isOnline || player.isDead) return
    transitioningPlayers.add(player.uniqueId)
    session.transitionGraceTicks = 20 // 1秒間の保護猶予
    try {
      if (toSpectator) {
        // (1) 非スペクテイター -> スペクテイター
        session.seatEntity?.let { oldSeat ->
          oldSeat.removePassenger(player)
          oldSeat.remove()
        }
        session.seatEntity = null
        spawnMannequinSeat(player, session, targetTopLoc)
      } else {
        // (2) スペクテイター -> 通常座席
        if (player.gameMode == GameMode.SPECTATOR) {
          try {
            player.spectatorTarget = null
          } catch (_: Exception) {}
        }
        player.gameMode = session.originalGameMode
        session.mannequinEntity?.let { m ->
          session.seatEntity?.removePassenger(m)
          m.remove()
        }
        session.mannequinEntity = null
        session.seatEntity?.remove()
        session.seatEntity = null
        spawnArmorStandSeat(player, session, targetTopLoc)
      }
    } finally {
      // セッションマップに確実に保持されていることを再保証！
      sessions[player.uniqueId] = session
      transitioningPlayers.remove(player.uniqueId)
    }
  }

  /**
   * 毎Tick呼び出される座席追従＆動的切り替えループ
   */
  fun tick(currentTick: Long) {
    // 1. 各からくりのカーペット専用 Interaction エンティティの同期（離れた位置でもクリック可能に）
    tickCarpetInteractions()

    // 2. 各座席セッションの追従と動的切り替え
    val iterator = sessions.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      val session = entry.value
      val player = org.bukkit.Bukkit.getPlayer(session.playerId)
      val instance = manager.get(session.instanceId)

      // プレイヤーがログアウト、死亡、またはからくりが撤去された場合
      if (player == null || !player.isOnline || player.isDead || instance == null || !instance.isValid()) {
        cleanupSessionEntity(session, player)
        iterator.remove()
        continue
      }

      // モード切り替え猶予期間中は自発的降車チェックを行わない（誤判定によるセッション破棄・マネキン孤立を100%防止）
      if (session.transitionGraceTicks > 0) {
        session.transitionGraceTicks--
      } else {
        // スペクテイターモード中にShift等で自発的に憑依解除された場合
        if (session.isSpectating && (session.mannequinEntity == null || !session.mannequinEntity!!.isValid || player.spectatorTarget != session.mannequinEntity)) {
          cleanupSessionEntity(session, player)
          iterator.remove()
          val safeLoc = getCarpetTopWorldLocation(instance, session.carpetLocalLoc, session.carpetScaleY).add(0.0, 0.05, 0.0)
          player.teleport(Location(safeLoc.world, safeLoc.x, safeLoc.y, safeLoc.z, player.location.yaw, player.location.pitch))
          player.sendActionBar(Component.text("🪑 カーペットから降りました", NamedTextColor.YELLOW))
          continue
        }
      }

      val isRotating = isCurrentlyRotating(instance)
      val shouldSpectate = isRotating && isSpectateSeatEnabled(player)

      // からくりのYaw増分を累積加算（instance.lastDeltaYaw には親回転・自転の合算角速度が既に正確に反映されている）
      val deltaYaw = instance.lastDeltaYaw
      session.accumulatedYaw += deltaYaw

      // 回転中・憑依中は速度に応じた先回り（リード角）補正を適用（高速公転時の遅れを根絶！）
      val leadTicks = 1.5f
      val targetTopLoc = getCarpetTopWorldLocation(instance, session.carpetLocalLoc, session.carpetScaleY, useLead = session.isSpectating, leadTicks = leadTicks)

      // 動的モード切り替え:
      // (1) 非スペクテイター -> スペクテイター開始 (回転開始 かつ 公転追従ON)
      if (shouldSpectate && !session.isSpectating) {
        switchSeatMode(player, session, true, targetTopLoc)
        continue
      }

      // (2) スペクテイター中 -> スペクテイター解除 (回転停止 または 自由視線設定)
      if (!shouldSpectate && session.isSpectating) {
        switchSeatMode(player, session, false, targetTopLoc)
        continue
      }

      // 各座席モードにおける追従更新
      if (session.isSpectating) {
        val stand = session.seatEntity as? ArmorStand
        val mannequin = session.mannequinEntity as? Mannequin

        if (stand == null || !stand.isValid || mannequin == null || !mannequin.isValid) {
          spawnMannequinSeat(player, session, targetTopLoc)
          continue
        }

        // 先回り（leadTicks 分）に合わせたYaw補正
        val leadYaw = session.accumulatedYaw + (deltaYaw * leadTicks)

        // 逆さ判定 (法線ベクトルが -0.3f 未満まで傾いた時のみ逆さ判定。通常姿勢の範囲を広めに確保)
        val upVector = getCarpetUpVector(instance, useLead = true, leadTicks = leadTicks)
        val currentlyUpsideDown = (mannequin.customName() != null)
        val isUpsideDown = if (currentlyUpsideDown) {
          upVector.y < -0.2f // 戻る時のヒステリシス
        } else {
          upVector.y < -0.3f // 逆さ判定になる角度を狭め（約107.5度以上傾斜）、通常姿勢を広くする
        }

        // Dinnerbone 設定 (逆さま時は上下反転モデルにする)
        if (isUpsideDown) {
          if (mannequin.customName() == null) {
            mannequin.customName(Component.text("Dinnerbone"))
            mannequin.isCustomNameVisible = false
          }
        } else {
          if (mannequin.customName() != null) {
            mannequin.customName(null)
            mannequin.isCustomNameVisible = false
          }
        }

        // 逆さま時のみマネキン自体の高さを1マス（1.0ブロック）下げる
        val yOffset = if (isUpsideDown) -1.0 else 0.0
        val targetY = targetTopLoc.y + yOffset

        // 1. 乗り物（防具立て）を公転位置へテレポート（乗客マネキンを維持！）
        val targetLoc = Location(targetTopLoc.world, targetTopLoc.x, targetY, targetTopLoc.z, leadYaw, 0f)
        stand.teleport(targetLoc, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS)

        // 2. 乗客（マネキン）の向きを setRotation で完全同期（降車を起こさずに首・視線を同期！）
        mannequin.setRotation(leadYaw, 0f)
        mannequin.bodyYaw = leadYaw
      } else {
        // 非回転時または自由視線モード（防具立て座席にプレイヤー自身が乗車）
        val seat = session.seatEntity as? ArmorStand
        if (seat == null || !seat.isValid) {
          spawnArmorStandSeat(player, session, targetTopLoc)
          continue
        }

        if (session.transitionGraceTicks <= 0 && !seat.passengers.contains(player)) {
          // プレイヤーが自発的に降りた (Shift)
          cleanupSessionEntity(session, player)
          iterator.remove()
          val safeLoc = targetTopLoc.clone().add(0.0, 0.05, 0.0)
          player.teleport(Location(safeLoc.world, safeLoc.x, safeLoc.y, safeLoc.z, player.location.yaw, player.location.pitch))
          player.sendActionBar(Component.text("🪑 カーペットから降りました", NamedTextColor.YELLOW))
          continue
        }

        val seatTargetLoc = targetTopLoc.clone()
        val curLoc = seat.location
        val delta = Vector(
          seatTargetLoc.x - curLoc.x,
          seatTargetLoc.y - curLoc.y,
          seatTargetLoc.z - curLoc.z
        )

        // 基本的には慣性（velocity）で滑らかに動かし、一定周期（10 ticks）またはズレ発生時にテレポート補正
        seat.velocity = delta
        val distSq = delta.lengthSquared()
        if (currentTick - session.lastTeleportTick >= 10 || distSq > 0.0025) {
          seat.teleport(seatTargetLoc)
          session.lastTeleportTick = currentTick
        }
      }
    }
  }

  /**
   * カーペット位置に配置するクリック検出用 Interaction エンティティの追従管理
   */
  private fun tickCarpetInteractions() {
    val activeInstanceIds = manager.getAll().map { it.id }.toSet()

    // 撤去されたからくりの Interaction を削除
    val toRemove = carpetInteractions.keys.filter { !activeInstanceIds.contains(it) }
    for (id in toRemove) {
      carpetInteractions.remove(id)?.values?.forEach { if (it.isValid) it.remove() }
    }

    for (instance in manager.getAll()) {
      if (!instance.isValid() || instance.isDormant) {
        carpetInteractions.remove(instance.id)?.values?.forEach { if (it.isValid) it.remove() }
        continue
      }

      // からくり内の全カーペットを検出
      val carpetLocs = mutableListOf<Pair<Location, Float>>()
      if (instance.originalBlockData?.material?.name?.contains("CARPET") == true) {
        carpetLocs.add(instance.originLocation to instance.scale.y)
      }
      for (part in instance.additionalParts) {
        if (part.displayEntity.isValid && part.originalBlockData?.material?.name?.contains("CARPET") == true) {
          carpetLocs.add(part.originLocation to part.partScale.y)
        }
      }

      if (carpetLocs.isEmpty()) {
        carpetInteractions.remove(instance.id)?.values?.forEach { if (it.isValid) it.remove() }
        continue
      }

      val instMap = carpetInteractions.computeIfAbsent(instance.id) { ConcurrentHashMap() }
      val world = instance.originLocation.world ?: continue

      for ((cLoc, scaleY) in carpetLocs) {
        val curTopLoc = getCarpetTopWorldLocation(instance, cLoc, scaleY)
        val interLoc = Location(world, curTopLoc.x, curTopLoc.y - (0.0625 * scaleY * 0.5), curTopLoc.z)

        var inter = instMap[cLoc]
        if (inter == null || !inter.isValid) {
          inter = world.spawn(interLoc, Interaction::class.java) { ent ->
            ent.isPersistent = false
            ent.interactionWidth = 1.0f
            ent.interactionHeight = (0.0625f * scaleY).coerceAtLeast(0.25f)
            ent.isResponsive = true
            ent.addScoreboardTag(TAG_CARPET_INTERACTION)
            ent.addScoreboardTag("karakuri_instance_${instance.id}")
          }
          instMap[cLoc] = inter
        } else {
          inter.teleport(interLoc)
        }
      }
    }
  }

  /**
   * プレイヤーを降車させる
   */
  fun dismount(player: Player) {
    if (isTransitioning(player)) return
    val session = sessions.remove(player.uniqueId) ?: return
    cleanupSessionEntity(session, player)

    val instance = manager.get(session.instanceId)
    if (instance != null && instance.isValid()) {
      val targetTopLoc = getCarpetTopWorldLocation(instance, session.carpetLocalLoc, session.carpetScaleY)
      val safeLoc = targetTopLoc.clone().add(0.0, 0.05, 0.0)
      player.teleport(Location(safeLoc.world, safeLoc.x, safeLoc.y, safeLoc.z, player.location.yaw, player.location.pitch))
    }
    player.sendActionBar(Component.text("🪑 カーペットから降りました", NamedTextColor.YELLOW))
  }

  private fun cleanupSessionEntity(session: SeatSession, player: Player?) {
    if (player != null && player.isOnline) {
      if (session.isSpectating || player.gameMode == GameMode.SPECTATOR) {
        try {
          player.spectatorTarget = null
        } catch (_: Exception) {}
        player.gameMode = session.originalGameMode
      }
    }

    session.mannequinEntity?.let { m ->
      session.seatEntity?.removePassenger(m)
      m.remove()
    }
    session.mannequinEntity = null

    session.seatEntity?.let { entity ->
      if (player != null) {
        entity.removePassenger(player)
      }
      entity.remove()
    }
    session.seatEntity = null

    // 安全策: 当該プレイヤーの座席タグが付いた残骸エンティティをワールドから完全掃討
    if (player != null) {
      try {
        val tag = "karakuri_seat_${player.uniqueId}"
        player.world.entities.forEach { ent ->
          if (ent.scoreboardTags.contains(tag)) {
            ent.remove()
          }
        }
      } catch (_: Exception) {}
    }
  }

  /**
   * からくりが現在回転運動を行っているかどうかを判定
   */
  fun isCurrentlyRotating(instance: KarakuriInstance): Boolean {
    val cur = instance.currentRotation
    val prev = instance.prevRotation
    val isQuatChanging = kotlin.math.abs(cur.x - prev.x) > 1e-4f ||
                         kotlin.math.abs(cur.y - prev.y) > 1e-4f ||
                         kotlin.math.abs(cur.z - prev.z) > 1e-4f ||
                         kotlin.math.abs(cur.w - prev.w) > 1e-4f
    if (isQuatChanging || kotlin.math.abs(instance.lastDeltaYaw) > 0.001f) {
      return true
    }

    val motion = instance.activeMotion
    if (motion is io.oyasai.karakuri.motion.ContinuousSpinMotion) {
      if (motion.isRunning && motion.rpm > 0.01f) return true
    }
    if (motion is io.oyasai.karakuri.motion.AngleRotationMotion) {
      return true
    }
    if (motion is io.oyasai.karakuri.motion.ProgramMotion && motion.isRotating) {
      return true
    }
    if (motion is io.oyasai.karakuri.motion.ChildContraptionMotion) {
      if (motion.isSpinActive) return true
      if (motion.followParentRotation) {
        val parent = manager.get(motion.parentId)
        if (parent != null && isCurrentlyRotating(parent)) return true
      }
    }
    if (motion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
      if (motion.isSpinActive) return true
      if (motion.followParentRotation) {
        val parent = manager.get(motion.parentId)
        if (parent != null && isCurrentlyRotating(parent)) return true
      }
    }
    return false
  }

  /**
   * からくりの回転速度（角速度）に応じて、座席エンティティ（ArmorStand / Mannequin）の位置を
   * 未来側へ先回り（リード）させる回転クォータニオンを取得。
   * クライアント側のエンティティ補間遅延・カメラ遅延を相殺し、高速回転時でもカーペットから遅れずに完全追従する。
   */
  fun getLeadRotation(instance: KarakuriInstance, leadTicks: Float = 1.0f): Quaternionf {
    val cur = instance.currentRotation
    val prev = instance.prevRotation
    if (prev.lengthSquared() < 0.001f || (cur.x == prev.x && cur.y == prev.y && cur.z == prev.z && cur.w == prev.w)) {
      return Quaternionf(cur)
    }
    val qPrev = Quaternionf(prev)
    val qCur = Quaternionf(cur)
    if (qPrev.dot(qCur) < 0f) {
      qCur.set(-qCur.x, -qCur.y, -qCur.z, -qCur.w)
    }

    // 1 tick 分の回転差分クォータニオン: deltaQ = qCur * qPrev^-1
    val deltaQ = Quaternionf(qCur).mul(Quaternionf(qPrev).conjugate())

    // deltaQ の回転角・回転軸を抽出して leadTicks 倍にスケール
    val angle = 2.0 * kotlin.math.acos(deltaQ.w.coerceIn(-1f, 1f).toDouble())
    if (kotlin.math.abs(angle) < 1e-5) {
      return Quaternionf(cur)
    }
    val sinHalf = kotlin.math.sin(angle / 2.0)
    val axisX = if (kotlin.math.abs(sinHalf) > 1e-5) (deltaQ.x / sinHalf).toFloat() else 0f
    val axisY = if (kotlin.math.abs(sinHalf) > 1e-5) (deltaQ.y / sinHalf).toFloat() else 1f
    val axisZ = if (kotlin.math.abs(sinHalf) > 1e-5) (deltaQ.z / sinHalf).toFloat() else 0f

    val leadAngle = (angle * leadTicks).toFloat()
    val leadDeltaQ = Quaternionf().rotateAxis(leadAngle, axisX, axisY, axisZ)

    // 現在の回転 qCur に対して leadDeltaQ だけ先回り
    return Quaternionf(leadDeltaQ).mul(qCur)
  }

  /**
   * カーペットの上面法線ベクトル（初期状態ではワールド上向き (0, 1, 0)）を取得。
   * Y成分が負（< 0.0f）の場合は、からくりの回転によってカーペットが逆さまになっていることを示す。
   */
  fun getCarpetUpVector(
    instance: KarakuriInstance,
    useLead: Boolean = false,
    leadTicks: Float = 1.5f,
  ): Vector3f {
    val motion = instance.activeMotion
    return if (motion is io.oyasai.karakuri.motion.ChildContraptionMotion) {
      val parent = manager.get(motion.parentId)
      if (parent != null && parent.isValid()) {
        val parentRot = if (useLead) getLeadRotation(parent, leadTicks) else Quaternionf(parent.currentRotation)
        val childRot = if (useLead) getLeadRotation(instance, leadTicks) else Quaternionf(instance.currentRotation)
        Vector3f(0f, 1f, 0f).rotate(Quaternionf(parentRot).mul(childRot))
      } else {
        Vector3f(0f, 1f, 0f).rotate(if (useLead) getLeadRotation(instance, leadTicks) else Quaternionf(instance.currentRotation))
      }
    } else if (motion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
      val parent = manager.get(motion.parentId)
      if (parent != null && parent.isValid()) {
        val parentRot = if (useLead) getLeadRotation(parent, leadTicks) else Quaternionf(parent.currentRotation)
        val childRot = if (useLead) getLeadRotation(instance, leadTicks) else Quaternionf(instance.currentRotation)
        Vector3f(0f, 1f, 0f).rotate(Quaternionf(parentRot).mul(childRot))
      } else {
        Vector3f(0f, 1f, 0f).rotate(if (useLead) getLeadRotation(instance, leadTicks) else Quaternionf(instance.currentRotation))
      }
    } else {
      val rot = if (useLead) getLeadRotation(instance, leadTicks) else Quaternionf(instance.currentRotation)
      Vector3f(0f, 1f, 0f).rotate(rot)
    }
  }

  /**
   * カーペット上面の中心ワールド座標を純粋幾何学計算。
   * エンティティ統合（isOptimized）の有無や複数ブロック構成に関わらず、
   * 親構造・子構造（粘着ピストン・鎖直線運動）の公転・自転・平行移動すべてを高精度に合成して算出。
   */
  fun getCarpetTopWorldLocation(
    instance: KarakuriInstance,
    carpetLocalLoc: Location,
    carpetScaleY: Float,
    useLead: Boolean = false,
    leadTicks: Float = 1.5f,
  ): Location {
    val world = instance.worldPivotLocation.world ?: instance.originLocation.world

    val motion = instance.activeMotion
    if (motion is io.oyasai.karakuri.motion.ChildContraptionMotion) {
      val parent = manager.get(motion.parentId)
      if (parent != null && parent.isValid()) {
        val parentRot = if (useLead) getLeadRotation(parent, leadTicks) else Quaternionf(parent.currentRotation)
        val childRot = if (useLead) getLeadRotation(instance, leadTicks) else Quaternionf(instance.currentRotation)

        // 親からくりにおける現在の粘着ピストンのワールド位置を計算
        val relPiston = Vector3f(
          (motion.pistonOriginLoc.blockX + 0.5 - parent.worldPivotLocation.x).toFloat(),
          (motion.pistonOriginLoc.blockY + 0.5 - parent.worldPivotLocation.y).toFloat(),
          (motion.pistonOriginLoc.blockZ + 0.5 - parent.worldPivotLocation.z).toFloat()
        )
        val rotatedRelPiston = Vector3f(relPiston).rotate(parentRot)

        val parentRootOffset = parent.getBaseEntityOffset(parent.displayEntity, parent.originalBlockData)
        val parentDeltaX = parent.displayEntity.location.x - (parent.originLocation.x + parentRootOffset.x)
        val parentDeltaY = parent.displayEntity.location.y - (parent.originLocation.y + parentRootOffset.y)
        val parentDeltaZ = parent.displayEntity.location.z - (parent.originLocation.z + parentRootOffset.z)

        val curPistonX = parent.worldPivotLocation.x + parentDeltaX + rotatedRelPiston.x
        val curPistonY = parent.worldPivotLocation.y + parentDeltaY + rotatedRelPiston.y
        val curPistonZ = parent.worldPivotLocation.z + parentDeltaZ + rotatedRelPiston.z

        // 子からくりの初期ピストン中心からカーペット上面中心への相対ベクトル
        val initPistonCenter = Vector3f(
          (motion.pistonOriginLoc.blockX + 0.5).toFloat(),
          (motion.pistonOriginLoc.blockY + 0.5).toFloat(),
          (motion.pistonOriginLoc.blockZ + 0.5).toFloat()
        )
        val carpetInitCenter = Vector3f(
          (carpetLocalLoc.blockX + 0.5).toFloat(),
          (carpetLocalLoc.blockY + (0.0625 * carpetScaleY)).toFloat(),
          (carpetLocalLoc.blockZ + 0.5).toFloat()
        )
        val relCarpet = Vector3f(carpetInitCenter).sub(initPistonCenter)
        val rotatedRelCarpet = Vector3f(relCarpet).rotate(childRot)

        return Location(
          world,
          curPistonX + rotatedRelCarpet.x,
          curPistonY + rotatedRelCarpet.y,
          curPistonZ + rotatedRelCarpet.z
        )
      }
    } else if (motion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
      val parent = manager.get(motion.parentId)
      if (parent != null && parent.isValid()) {
        val parentRot = if (useLead) getLeadRotation(parent, leadTicks) else Quaternionf(parent.currentRotation)
        val childRot = if (useLead) getLeadRotation(instance, leadTicks) else Quaternionf(instance.currentRotation)

        val curRelPos = if (motion.totalDistance > 0.001) {
          Vector3f(motion.minChainRel).lerp(motion.maxChainRel, motion.progress.toFloat())
        } else {
          Vector3f(motion.minChainRel)
        }
        val rotatedRelPos = Vector3f(curRelPos).rotate(parentRot)

        val parentRootOffset = parent.getBaseEntityOffset(parent.displayEntity, parent.originalBlockData)
        val parentDeltaX = parent.displayEntity.location.x - (parent.originLocation.x + parentRootOffset.x)
        val parentDeltaY = parent.displayEntity.location.y - (parent.originLocation.y + parentRootOffset.y)
        val parentDeltaZ = parent.displayEntity.location.z - (parent.originLocation.z + parentRootOffset.z)

        val curPivotX = parent.worldPivotLocation.x + parentDeltaX + rotatedRelPos.x
        val curPivotY = parent.worldPivotLocation.y + parentDeltaY + rotatedRelPos.y
        val curPivotZ = parent.worldPivotLocation.z + parentDeltaZ + rotatedRelPos.z

        val initConnectCenter = Vector3f(
          (motion.initialConnectLoc.blockX + 0.5).toFloat(),
          (motion.initialConnectLoc.blockY + 0.5).toFloat(),
          (motion.initialConnectLoc.blockZ + 0.5).toFloat()
        )
        val carpetInitCenter = Vector3f(
          (carpetLocalLoc.blockX + 0.5).toFloat(),
          (carpetLocalLoc.blockY + (0.0625 * carpetScaleY)).toFloat(),
          (carpetLocalLoc.blockZ + 0.5).toFloat()
        )
        val relCarpet = Vector3f(carpetInitCenter).sub(initConnectCenter)
        val rotatedRelCarpet = Vector3f(relCarpet).rotate(childRot)

        return Location(
          world,
          curPivotX + rotatedRelCarpet.x,
          curPivotY + rotatedRelCarpet.y,
          curPivotZ + rotatedRelCarpet.z
        )
      }
    }

    // 通常からくりの場合: ピボット中心からの純粋幾何学計算
    val rot = if (useLead) getLeadRotation(instance, leadTicks) else Quaternionf(instance.currentRotation)
    val pivot = Vector3d(
      instance.worldPivotLocation.x,
      instance.worldPivotLocation.y,
      instance.worldPivotLocation.z
    )
    val rootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
    val curRootLoc = instance.displayEntity.location
    val delta = Vector3d(
      curRootLoc.x - (instance.originLocation.x + rootOffset.x),
      curRootLoc.y - (instance.originLocation.y + rootOffset.y),
      curRootLoc.z - (instance.originLocation.z + rootOffset.z)
    )

    val pInit = Vector3f(
      (carpetLocalLoc.blockX + 0.5 - pivot.x).toFloat(),
      (carpetLocalLoc.blockY + (0.0625 * carpetScaleY) - pivot.y).toFloat(),
      (carpetLocalLoc.blockZ + 0.5 - pivot.z).toFloat()
    )
    val pRot = Vector3f(pInit).rotate(rot)

    return Location(
      world,
      pivot.x + delta.x + pRot.x,
      pivot.y + delta.y + pRot.y,
      pivot.z + delta.z + pRot.z
    )
  }

  fun cleanupAll() {
    for (session in sessions.values) {
      val player = org.bukkit.Bukkit.getPlayer(session.playerId)
      cleanupSessionEntity(session, player)
    }
    sessions.clear()

    // 全ワールドの座席残骸エンティティを消去
    for (world in org.bukkit.Bukkit.getWorlds()) {
      for (entity in world.entities) {
        if (entity.scoreboardTags.any { it.startsWith("karakuri_seat_") }) {
          entity.remove()
        }
      }
    }

    for (instMap in carpetInteractions.values) {
      for (inter in instMap.values) {
        if (inter.isValid) inter.remove()
      }
    }
    carpetInteractions.clear()
    transitioningPlayers.clear()
  }
}
