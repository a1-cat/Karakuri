package io.oyasai.karakuri.engine

import io.oyasai.karakuri.KarakuriPlugin
import io.oyasai.karakuri.motion.ChainChildContraptionMotion
import io.oyasai.karakuri.motion.ChildContraptionMotion
import io.oyasai.karakuri.motion.ContinuousSpinMotion
import io.oyasai.karakuri.motion.ProgramMotion
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Lightable
import org.bukkit.block.data.type.Observer
import org.bukkit.entity.Player
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * レッドストーン連動ブロック (RS連動ブロック) 管理マネージャー
 *
 * 1. レッドストーンランプ (REDSTONE_LAMP):
 *    - 点灯 (false -> true) を検知してからくりを手動作動 (triggerManual)
 *
 * 2. 銅の電球系ブロック (COPPER_BULB 各種全8種):
 *    - 点灯状態 (isLit == true) でからくりの回転方向を反転 (1からくりにつき最大1個)
 *
 * 3. オブザーバー (OBSERVER):
 *    - からくり内に組み込まれた統合されていないオブザーバーの顔と、
 *      ワールド側のオブザーバーの顔が接触 (距離1ブロック未満) した際に
 *      ワールド側オブザーバーからレッドストーン信号 (1 RS tick パルス) を出力
 *
 * 中心軸 (worldPivotLocation) から 64 ブロック以内の実体ブロックを登録可能。
 */
class RedstoneLinkManager(private val manager: KarakuriManager) {

  enum class RedstoneLinkType {
    LAMP,
    COPPER_BULB_ROTATION,
    COPPER_BULB_REVERSE,
    OBSERVER,
  }

  data class RedstoneLink(
    val type: RedstoneLinkType,
    val blockLocation: Location,
    var lastLitState: Boolean = false,
    var lastObserverPulseTick: Long = 0L,
  )

  companion object {
    const val MAX_DISTANCE = 64.0
    const val MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE

    fun isRotationBulb(material: Material): Boolean {
      val name = material.name
      return name == "COPPER_BULB" ||
             name == "WAXED_COPPER_BULB" ||
             name == "EXPOSED_COPPER_BULB" ||
             name == "WAXED_EXPOSED_COPPER_BULB"
    }

    fun isReverseBulb(material: Material): Boolean {
      val name = material.name
      return name == "WEATHERED_COPPER_BULB" ||
             name == "WAXED_WEATHERED_COPPER_BULB" ||
             name == "OXIDIZED_COPPER_BULB" ||
             name == "WAXED_OXIDIZED_COPPER_BULB"
    }

    fun isCopperBulb(material: Material): Boolean = isRotationBulb(material) || isReverseBulb(material)

    fun isCopperBulbType(type: RedstoneLinkType): Boolean =
      type == RedstoneLinkType.COPPER_BULB_ROTATION || type == RedstoneLinkType.COPPER_BULB_REVERSE

    fun isRedstoneLinkableBlock(material: Material): Boolean {
      return material == Material.REDSTONE_LAMP ||
             isCopperBulb(material) ||
             material == Material.OBSERVER
    }

    fun getLinkType(material: Material): RedstoneLinkType? {
      return when {
        material == Material.REDSTONE_LAMP -> RedstoneLinkType.LAMP
        isRotationBulb(material) -> RedstoneLinkType.COPPER_BULB_ROTATION
        isReverseBulb(material) -> RedstoneLinkType.COPPER_BULB_REVERSE
        material == Material.OBSERVER -> RedstoneLinkType.OBSERVER
        else -> null
      }
    }

    fun getTypeName(type: RedstoneLinkType): String {
      return when (type) {
        RedstoneLinkType.LAMP -> "レッドストーンランプ"
        RedstoneLinkType.COPPER_BULB_ROTATION -> "銅の電球 (回転反転)"
        RedstoneLinkType.COPPER_BULB_REVERSE -> "錆びた/酸化した銅の電球 (逆再生)"
        RedstoneLinkType.OBSERVER -> "オブザーバー"
      }
    }
  }

  // プレイヤーUUID -> 選択中のRS連動ブロック座標
  val selectingRedstoneLinks = ConcurrentHashMap<UUID, Location>()

  // ワールド名 -> 登録済みオブザーバー座標(Longパック)セット (バニラ反応抑制のためのO(1)高速インデックス)
  val linkedObserverPositions = ConcurrentHashMap<String, MutableSet<Long>>()

  // ワールド名 -> 現在からくりと接触中のオブザーバー座標(Longパック)セット
  val activeCollidingObservers = ConcurrentHashMap<String, MutableSet<Long>>()

  fun packBlockPos(x: Int, y: Int, z: Int): Long {
    return ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
  }

  fun registerObserverPos(worldName: String, x: Int, y: Int, z: Int) {
    linkedObserverPositions.computeIfAbsent(worldName) { ConcurrentHashMap.newKeySet() }
      .add(packBlockPos(x, y, z))
  }

  fun unregisterObserverPos(worldName: String, x: Int, y: Int, z: Int) {
    linkedObserverPositions[worldName]?.remove(packBlockPos(x, y, z))
    activeCollidingObservers[worldName]?.remove(packBlockPos(x, y, z))
  }

  fun isLinkedObserver(worldName: String, x: Int, y: Int, z: Int): Boolean {
    val set = linkedObserverPositions[worldName] ?: return false
    return set.contains(packBlockPos(x, y, z))
  }

  fun isObserverColliding(worldName: String, x: Int, y: Int, z: Int): Boolean {
    val set = activeCollidingObservers[worldName] ?: return false
    return set.contains(packBlockPos(x, y, z))
  }

  fun setObserverColliding(worldName: String, x: Int, y: Int, z: Int, colliding: Boolean) {
    val key = packBlockPos(x, y, z)
    if (colliding) {
      activeCollidingObservers.computeIfAbsent(worldName) { ConcurrentHashMap.newKeySet() }.add(key)
    } else {
      activeCollidingObservers[worldName]?.remove(key)
    }
  }

  fun rebuildObserverIndex() {
    linkedObserverPositions.clear()
    activeCollidingObservers.clear()
    for (instance in manager.getAll()) {
      for (link in instance.redstoneLinks) {
        if (link.type == RedstoneLinkType.OBSERVER) {
          val loc = link.blockLocation
          val w = loc.world?.name ?: continue
          registerObserverPos(w, loc.blockX, loc.blockY, loc.blockZ)
        }
      }
    }
  }

  fun isSelecting(player: Player): Boolean = selectingRedstoneLinks.containsKey(player.uniqueId)

  fun startSelecting(player: Player, blockLoc: Location) {
    val block = blockLoc.block
    val type = getLinkType(block.type) ?: return

    selectingRedstoneLinks[player.uniqueId] = blockLoc.clone()

    val pLoc = blockLoc.clone().add(0.5, 0.5, 0.5)
    player.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 25, 0.3, 0.3, 0.3, 0.05)
    player.playSound(pLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f)
    player.playSound(pLoc, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.8f, 1.8f)

    val typeName = getTypeName(type)
    player.sendMessage(
      Component.text("⚡ 【RS連動ブロック登録モード開始】", NamedTextColor.RED, TextDecoration.BOLD)
    )
    player.sendMessage(
      Component.text("  ▶ 対象ブロック: $typeName (${blockLoc.blockX}, ${blockLoc.blockY}, ${blockLoc.blockZ})", NamedTextColor.GOLD)
    )
    player.sendMessage(
      Component.text("  ▶ 連動させたいからくりを調律師のレンチで【右クリック】して紐付け！", NamedTextColor.YELLOW)
    )
    player.sendMessage(
      Component.text("  ▶ 解除・キャンセルするには【Shift + 左クリック】", NamedTextColor.GRAY)
    )
    player.sendActionBar(
      Component.text("連動させたいからくりをレンチで右クリック (解除: Shift左クリック)", NamedTextColor.YELLOW)
    )
  }

  fun cancelSelecting(player: Player) {
    if (selectingRedstoneLinks.remove(player.uniqueId) != null) {
      player.sendMessage(Component.text("🚫 RS連動ブロック登録モードをキャンセルしました。", NamedTextColor.YELLOW))
      player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)
    }
  }

  /**
   * 選択中のRS連動ブロックを指定からくりに紐付け登録
   */
  fun linkToInstance(player: Player, instance: KarakuriInstance): Boolean {
    val blockLoc = selectingRedstoneLinks.remove(player.uniqueId) ?: return false
    val block = blockLoc.block
    val type = getLinkType(block.type)
    if (type == null) {
      player.sendMessage(Component.text("⚠️ 選択されたブロックが無効です。", NamedTextColor.RED))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
      return false
    }

    // 距離判定: 中心軸 (worldPivotLocation) から 64 ブロック以内
    val pivot = instance.worldPivotLocation
    if (pivot.world != blockLoc.world || pivot.distanceSquared(blockLoc) > MAX_DISTANCE_SQ) {
      val dist = if (pivot.world == blockLoc.world) String.format("%.1f", pivot.distance(blockLoc)) else "別ワールド"
      player.sendMessage(Component.text("⚠️ からくりの中心軸から64ブロック以内のRS連動ブロックのみ登録できます！(現在距離: ${dist}m)", NamedTextColor.RED))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
      return false
    }

    // 銅の電球の制約: 通常・風化・錆び・酸化を合わせて1つのからくりに1つまで
    if (isCopperBulbType(type)) {
      instance.redstoneLinks.removeIf { isCopperBulbType(it.type) }
      resetBulbState(instance)
    }

    // 同一ブロックの重複登録を除去
    instance.redstoneLinks.removeIf {
      it.blockLocation.blockX == blockLoc.blockX &&
      it.blockLocation.blockY == blockLoc.blockY &&
      it.blockLocation.blockZ == blockLoc.blockZ
    }

    val litState = (block.blockData as? Lightable)?.isLit ?: false
    val link = RedstoneLink(
      type = type,
      blockLocation = blockLoc.clone(),
      lastLitState = litState,
      lastObserverPulseTick = 0L
    )
    instance.redstoneLinks.add(link)

    // オブザーバーの高速インデックス更新
    if (type == RedstoneLinkType.OBSERVER) {
      val w = blockLoc.world?.name
      if (w != null) {
        registerObserverPos(w, blockLoc.blockX, blockLoc.blockY, blockLoc.blockZ)
      }
    }

    // DB保存
    try {
      KarakuriPlugin.instance.database.saveRedstoneLinks(instance)
    } catch (_: Exception) {}

    // 初期状態を即時反映（銅の電球の回転反転・逆再生など）
    applyBulbState(instance, link, litState)

    val pLoc = instance.displayEntity.location
    player.spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 35, 0.5, 0.5, 0.5, 0.1)
    player.spawnParticle(Particle.ELECTRIC_SPARK, blockLoc.clone().add(0.5, 0.5, 0.5), 25, 0.3, 0.3, 0.3, 0.1)
    player.playSound(pLoc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
    player.playSound(pLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f)

    val typeName = getTypeName(type)
    player.sendMessage(Component.text("⚡ 【RS連動ブロックを登録しました！】", NamedTextColor.GREEN, TextDecoration.BOLD))
    player.sendMessage(Component.text("  ▶ からくり: [${instance.id}]", NamedTextColor.AQUA))
    player.sendMessage(Component.text("  ▶ 登録ブロック: $typeName (${blockLoc.blockX}, ${blockLoc.blockY}, ${blockLoc.blockZ})", NamedTextColor.YELLOW))
    when (type) {
      RedstoneLinkType.LAMP -> {
        player.sendMessage(Component.text("  ▶ ランプ点灯時にからくりが自動作動 (トグル) します！", NamedTextColor.GRAY))
      }
      RedstoneLinkType.COPPER_BULB_ROTATION -> {
        player.sendMessage(Component.text("  ▶ 電球が光っている間、からくりの回転方向が反転します！(1個限定)", NamedTextColor.GRAY))
      }
      RedstoneLinkType.COPPER_BULB_REVERSE -> {
        player.sendMessage(Component.text("  ▶ 電球が光っている間、からくりが逆再生します！(回転反転兼用・1個限定)", NamedTextColor.GRAY))
      }
      RedstoneLinkType.OBSERVER -> {
        player.sendMessage(Component.text("  ▶ からくり側のオブザーバーの顔が接触した際にレッドストーン信号を出力します！", NamedTextColor.GRAY))
      }
    }
    return true
  }

  fun removeLink(instance: KarakuriInstance, blockLoc: Location): Boolean {
    val removed = instance.redstoneLinks.removeIf { link ->
      val match = link.blockLocation.blockX == blockLoc.blockX &&
                  link.blockLocation.blockY == blockLoc.blockY &&
                  link.blockLocation.blockZ == blockLoc.blockZ
      if (match && link.type == RedstoneLinkType.OBSERVER) {
        val w = blockLoc.world?.name
        if (w != null) {
          unregisterObserverPos(w, blockLoc.blockX, blockLoc.blockY, blockLoc.blockZ)
        }
      }
      match
    }
    if (removed) {
      // 銅の電球が解除された場合、反転・逆再生をリセット
      resetBulbState(instance)
      turnOffObserverIfPowered(blockLoc)
      try {
        KarakuriPlugin.instance.database.saveRedstoneLinks(instance)
      } catch (_: Exception) {}
    }
    return removed
  }

  fun removeAllLinks(instance: KarakuriInstance): Int {
    val count = instance.redstoneLinks.size
    instance.redstoneLinks.forEach { link ->
      if (link.type == RedstoneLinkType.OBSERVER) {
        val w = link.blockLocation.world?.name
        if (w != null) {
          unregisterObserverPos(w, link.blockLocation.blockX, link.blockLocation.blockY, link.blockLocation.blockZ)
        }
        turnOffObserverIfPowered(link.blockLocation)
      }
    }
    instance.redstoneLinks.clear()
    resetBulbState(instance)
    try {
      KarakuriPlugin.instance.database.saveRedstoneLinks(instance)
    } catch (_: Exception) {}
    return count
  }

  private fun turnOffObserverIfPowered(blockLoc: Location) {
    try {
      val block = blockLoc.block
      if (block.type == Material.OBSERVER) {
        val data = block.blockData as? Observer ?: return
        if (data.isPowered) {
          data.isPowered = false
          block.setBlockData(data, true)
          val backBlock = block.getRelative(data.facing.oppositeFace)
          if (!backBlock.type.isAir) {
            backBlock.state.update(false, true)
          }
        }
      }
    } catch (_: Exception) {}
  }

  fun resetBulbState(instance: KarakuriInstance) {
    resetBulbStateRecursive(instance)
  }

  private fun resetBulbStateRecursive(instance: KarakuriInstance) {
    val motion = instance.activeMotion
    when (motion) {
      is ContinuousSpinMotion -> motion.isBulbInverted = false
      is ChildContraptionMotion -> motion.isBulbInverted = false
      is ChainChildContraptionMotion -> {
        motion.isBulbInverted = false
        motion.setBulbReversing(false, instance)
      }
      is io.oyasai.karakuri.motion.IntegratedLinearMotion -> motion.setBulbReversing(false, instance)
      is ProgramMotion -> {
        motion.setBulbRotationInverted(false, instance)
        motion.setBulbReversing(false, instance)
      }
      is io.oyasai.karakuri.motion.MinecartMotion -> {
        motion.isBulbInverted = false
        motion.setBulbReversing(false, instance)
      }
    }

    // 接続されているすべての子構造 (粘着ピストン・鎖直線運動) へ再帰リセット
    for (child in manager.getAll()) {
      if (!child.isValid() || child.id == instance.id) continue
      val cMotion = child.activeMotion
      val isDirectChild = when (cMotion) {
        is ChildContraptionMotion -> cMotion.parentId == instance.id
        is ChainChildContraptionMotion -> cMotion.parentId == instance.id
        else -> false
      }
      if (isDirectChild) {
        resetBulbStateRecursive(child)
      }
    }
  }

  fun applyBulbState(instance: KarakuriInstance, link: RedstoneLink, isLit: Boolean) {
    applyBulbStateRecursive(instance, link.type, isLit)
  }

  private fun applyBulbStateRecursive(instance: KarakuriInstance, type: RedstoneLinkType, isLit: Boolean) {
    val motion = instance.activeMotion
    when (type) {
      RedstoneLinkType.COPPER_BULB_ROTATION -> {
        when (motion) {
          is ContinuousSpinMotion -> motion.isBulbInverted = isLit
          is ChildContraptionMotion -> motion.isBulbInverted = isLit
          is ChainChildContraptionMotion -> motion.isBulbInverted = isLit
          is ProgramMotion -> motion.setBulbRotationInverted(isLit, instance)
          is io.oyasai.karakuri.motion.MinecartMotion -> motion.isBulbInverted = isLit
          else -> {}
        }
      }
      RedstoneLinkType.COPPER_BULB_REVERSE -> {
        when (motion) {
          is ContinuousSpinMotion -> motion.isBulbInverted = isLit
          is ChildContraptionMotion -> motion.isBulbInverted = isLit
          is ChainChildContraptionMotion -> {
            motion.isBulbInverted = isLit
            motion.setBulbReversing(isLit, instance)
          }
          is io.oyasai.karakuri.motion.IntegratedLinearMotion -> motion.setBulbReversing(isLit, instance)
          is ProgramMotion -> motion.setBulbReversing(isLit, instance)
          is io.oyasai.karakuri.motion.MinecartMotion -> motion.setBulbReversing(isLit, instance)
          else -> {}
        }
      }
      else -> {}
    }

    // 接続されているすべての子構造 (粘着ピストン・鎖直線運動) へ再帰適用
    for (child in manager.getAll()) {
      if (!child.isValid() || child.id == instance.id) continue
      val cMotion = child.activeMotion
      val isDirectChild = when (cMotion) {
        is ChildContraptionMotion -> cMotion.parentId == instance.id
        is ChainChildContraptionMotion -> cMotion.parentId == instance.id
        else -> false
      }
      if (isDirectChild) {
        applyBulbStateRecursive(child, type, isLit)
      }
    }
  }

  /**
   * 毎Tick呼び出されるRS連動監視・実行ループ
   */
  fun tick(currentTick: Long) {
    for (instance in manager.getAll()) {
      if (!instance.isValid() || instance.isDormant) continue
      val links = instance.redstoneLinks
      if (links.isEmpty()) continue

      for (link in links) {
        val loc = link.blockLocation
        val world = loc.world ?: continue

        val isChunkLoaded = world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)
        if (!isChunkLoaded) {
          // チャンク未ロード時: 最後に記録された点灯・反転状態をからくりに維持・継続適用！
          if (isCopperBulbType(link.type)) {
            applyBulbState(instance, link, link.lastLitState)
          }
          continue
        }

        val block = loc.block

        when (link.type) {
          RedstoneLinkType.LAMP -> {
            if (block.type == Material.REDSTONE_LAMP) {
              val isLit = (block.blockData as? Lightable)?.isLit ?: false
              // 立ち上がりエッジ検知 (消灯 -> 点灯)
              if (!link.lastLitState && isLit) {
                // 素手Shift右クリックと同じ手動作動を実行！
                manager.triggerManual(player = null, targetKarakuri = instance)
                world.spawnParticle(Particle.ELECTRIC_SPARK, loc.clone().add(0.5, 0.5, 0.5), 10, 0.2, 0.2, 0.2, 0.05)
              }
              link.lastLitState = isLit
            }
          }

          RedstoneLinkType.COPPER_BULB_ROTATION, RedstoneLinkType.COPPER_BULB_REVERSE -> {
            if (isCopperBulb(block.type)) {
              val isLit = (block.blockData as? Lightable)?.isLit ?: false
              if (link.lastLitState != isLit) {
                link.lastLitState = isLit
                applyBulbState(instance, link, isLit)
                // 状態変化時にDBへ即時非同期保存！(チャンク外に移動しても永続化される)
                KarakuriPlugin.instance.database.saveRedstoneLinks(instance)
              }
            }
          }

          RedstoneLinkType.OBSERVER -> {
            if (block.type == Material.OBSERVER) {
              checkObserverCollision(instance, link, block, currentTick)
            }
          }
        }
      }
    }
  }

  /**
   * オブザーバー同士の顔接触判定 (距離1ブロック未満) と信号出力
   * パルスではなく「接触している間、常に出力（ONを維持）」し、離れたらOFFにする。
   * からくり側が斜め向きであっても、顔の四角形面との最短距離計算により高精度に検知する。
   */
  private fun checkObserverCollision(
    instance: KarakuriInstance,
    link: RedstoneLink,
    worldBlock: org.bukkit.block.Block,
    currentTick: Long
  ) {
    val worldData = worldBlock.blockData as? Observer ?: return

    val isColliding = isObserverColliding(instance, worldData, worldBlock)
    val worldName = worldBlock.world.name
    setObserverColliding(worldName, worldBlock.x, worldBlock.y, worldBlock.z, isColliding)

    if (isColliding) {
      if (!worldData.isPowered) {
        // [ON化]: 接触開始！信号を常時出力開始！
        worldData.isPowered = true
        worldBlock.setBlockData(worldData, true)

        val backBlock = worldBlock.getRelative(worldData.facing.oppositeFace)
        if (!backBlock.type.isAir) {
          backBlock.state.update(false, true)
        }

        val worldFace = worldData.facing
        val pLoc = Location(
          worldBlock.world,
          worldBlock.x + 0.5 + worldFace.modX * 0.5,
          worldBlock.y + 0.5 + worldFace.modY * 0.5,
          worldBlock.z + 0.5 + worldFace.modZ * 0.5
        )
        worldBlock.world.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 15, 0.2, 0.2, 0.2, 0.05)
        worldBlock.world.playSound(pLoc, Sound.BLOCK_DISPENSER_FAIL, 0.6f, 1.8f)
      } else {
        // 接触持続中: 4 tick ごとに微小パーティクル
        if (currentTick % 4L == 0L) {
          val worldFace = worldData.facing
          val pLoc = Location(
            worldBlock.world,
            worldBlock.x + 0.5 + worldFace.modX * 0.5,
            worldBlock.y + 0.5 + worldFace.modY * 0.5,
            worldBlock.z + 0.5 + worldFace.modZ * 0.5
          )
          worldBlock.world.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 3, 0.1, 0.1, 0.1, 0.02)
        }
      }
    } else {
      if (worldData.isPowered) {
        // [OFF化]: 接触終了！信号を停止！
        worldData.isPowered = false
        worldBlock.setBlockData(worldData, true)

        val backBlock = worldBlock.getRelative(worldData.facing.oppositeFace)
        if (!backBlock.type.isAir) {
          backBlock.state.update(false, true)
        }

        val worldFace = worldData.facing
        val pLoc = Location(
          worldBlock.world,
          worldBlock.x + 0.5 + worldFace.modX * 0.5,
          worldBlock.y + 0.5 + worldFace.modY * 0.5,
          worldBlock.z + 0.5 + worldFace.modZ * 0.5
        )
        worldBlock.world.playSound(pLoc, Sound.BLOCK_DISPENSER_FAIL, 0.5f, 1.2f)
      }
    }
  }

  /**
   * からくり内の統合されていないオブザーバーのいずれかが、ワールド側オブザーバーの顔に接触しているか判定
   */
  private fun isObserverColliding(
    instance: KarakuriInstance,
    worldData: Observer,
    worldBlock: org.bukkit.block.Block
  ): Boolean {
    // バニラオブザーバーの顔(検知面)は facing そのもの
    val worldFace = worldData.facing
    val worldObsFacePos = Vector3d(
      worldBlock.x + 0.5 + worldFace.modX * 0.5,
      worldBlock.y + 0.5 + worldFace.modY * 0.5,
      worldBlock.z + 0.5 + worldFace.modZ * 0.5
    )

    // からくり内の「エンティティ統合されていないオブザーバー」を走査
    val karakuriObservers = mutableListOf<Pair<Location, BlockFace>>()

    // ルートブロック
    if (instance.originalBlockData?.material == Material.OBSERVER && !instance.isBlockMerged(instance.originLocation)) {
      val facing = (instance.originalBlockData as? Observer)?.facing ?: BlockFace.SOUTH
      karakuriObservers.add(instance.originLocation to facing)
    }

    // 追加パーツ
    for (part in instance.additionalParts) {
      if (part.displayEntity.isValid && part.originalBlockData?.material == Material.OBSERVER) {
        if (!instance.isBlockMerged(part.originLocation)) {
          val facing = (part.originalBlockData as? Observer)?.facing ?: BlockFace.SOUTH
          karakuriObservers.add(part.originLocation to facing)
        }
      }
    }

    // constituentBlocks (パーツ未生成の個別ブロックデータ)
    for ((loc, data) in instance.constituentBlocks) {
      if (data.material == Material.OBSERVER && !instance.isBlockMerged(loc)) {
        if (karakuriObservers.none { it.first.blockX == loc.blockX && it.first.blockY == loc.blockY && it.first.blockZ == loc.blockZ }) {
          val facing = (data as? Observer)?.facing ?: BlockFace.SOUTH
          karakuriObservers.add(loc to facing)
        }
      }
    }

    if (karakuriObservers.isEmpty()) return false

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
    val curRot = Quaternionf(instance.currentRotation)

    for ((origLoc, facing) in karakuriObservers) {
      val localFace = facing
      val localFaceOffset = Vector3f(
        (origLoc.blockX + 0.5 - pivot.x).toFloat() + (localFace.modX.toFloat() * 0.5f),
        (origLoc.blockY + 0.5 - pivot.y).toFloat() + (localFace.modY.toFloat() * 0.5f),
        (origLoc.blockZ + 0.5 - pivot.z).toFloat() + (localFace.modZ.toFloat() * 0.5f)
      )

      val rotatedOffset = Vector3f(localFaceOffset).rotate(curRot)
      val curFaceWorldPos = Vector3d(
        pivot.x + delta.x + rotatedOffset.x,
        pivot.y + delta.y + rotatedOffset.y,
        pivot.z + delta.z + rotatedOffset.z
      )

      // 1. 中心間距離
      val centerDistSq = curFaceWorldPos.distanceSquared(worldObsFacePos)
      if (centerDistSq < 1.0) {
        return true
      }

      // 2. 斜め向き完全対応: からくり側オブザーバーの顔の矩形面 (1.0 x 1.0) とワールド側顔中心との最近接距離
      val quadDist = getDistanceToFaceQuad(curFaceWorldPos, facing, curRot, worldObsFacePos)
      if (quadDist < 1.0) {
        return true
      }
    }

    return false
  }

  /**
   * 任意の姿勢で回転した 1x1 の矩形面と、目標点との最短距離を計算
   */
  private fun getDistanceToFaceQuad(
    faceCenter: Vector3d,
    facing: BlockFace,
    rot: Quaternionf,
    targetPoint: Vector3d
  ): Double {
    val (uVec, vVec) = when (facing) {
      BlockFace.UP, BlockFace.DOWN -> Vector3f(0.5f, 0f, 0f) to Vector3f(0f, 0f, 0.5f)
      BlockFace.NORTH, BlockFace.SOUTH -> Vector3f(0.5f, 0f, 0f) to Vector3f(0f, 0.5f, 0f)
      BlockFace.EAST, BlockFace.WEST -> Vector3f(0f, 0f, 0.5f) to Vector3f(0f, 0.5f, 0f)
      else -> Vector3f(0.5f, 0f, 0f) to Vector3f(0f, 0.5f, 0f)
    }

    val tangentU = Vector3f(uVec).rotate(rot)
    val tangentV = Vector3f(vVec).rotate(rot)

    val diffX = targetPoint.x - faceCenter.x
    val diffY = targetPoint.y - faceCenter.y
    val diffZ = targetPoint.z - faceCenter.z

    val u = ((diffX * tangentU.x + diffY * tangentU.y + diffZ * tangentU.z) / 0.25).coerceIn(-1.0, 1.0)
    val v = ((diffX * tangentV.x + diffY * tangentV.y + diffZ * tangentV.z) / 0.25).coerceIn(-1.0, 1.0)

    val closestX = faceCenter.x + u * tangentU.x + v * tangentV.x
    val closestY = faceCenter.y + u * tangentU.y + v * tangentV.y
    val closestZ = faceCenter.z + u * tangentU.z + v * tangentV.z

    val dx = targetPoint.x - closestX
    val dy = targetPoint.y - closestY
    val dz = targetPoint.z - closestZ
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
  }
}
