package io.oyasai.karakuri.listener

import io.oyasai.karakuri.engine.GlueManager
import io.oyasai.karakuri.engine.KarakuriManager
import io.oyasai.karakuri.engine.KarakuriSounds
import io.oyasai.karakuri.gui.KarakuriChainChildGUI
import io.oyasai.karakuri.gui.KarakuriChildGUI
import io.oyasai.karakuri.gui.KarakuriCommandGUI
import io.oyasai.karakuri.gui.KarakuriGUI
import io.oyasai.karakuri.gui.KarakuriLinearGUI
import io.oyasai.karakuri.gui.KarakuriMinecartGUI
import io.oyasai.karakuri.gui.KarakuriProgramGUI
import io.oyasai.karakuri.gui.KarakuriStepEditGUI
import io.oyasai.karakuri.gui.KarakuriToolboxGUI
import io.oyasai.karakuri.motion.ChainChildContraptionMotion
import io.oyasai.karakuri.motion.DoorMotion
import io.oyasai.karakuri.motion.IntegratedLinearMotion
import io.oyasai.karakuri.motion.LinearLoopMode
import io.oyasai.karakuri.motion.ProgramMotion
import io.oyasai.karakuri.tool.KarakuriGlue
import io.oyasai.karakuri.tool.KarakuriPistonRod
import io.oyasai.karakuri.tool.KarakuriToolbox
import io.oyasai.karakuri.tool.KarakuriWrench
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.minecart.RideableMinecart
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KarakuriListener(
  private val manager: KarakuriManager,
  private val glueManager: GlueManager,
) : Listener {

  companion object {
    // プレイヤーUUID -> 選択中のトロッコUUID (トロッコ選択後にからくりをクリックするモード)
    val selectingMinecarts = ConcurrentHashMap<UUID, UUID>()

    data class ParentChildSession(
      val parentInstance: io.oyasai.karakuri.engine.KarakuriInstance,
      val pistonLoc: Location,
      val pistonFacing: org.bukkit.block.BlockFace,
    )

    // プレイヤーUUID -> 接続待機中の親子セッション (粘着ピストン接続)
    val selectingChildKarakuri = ConcurrentHashMap<UUID, ParentChildSession>()

    data class ChainChildSession(
      val parentInstance: io.oyasai.karakuri.engine.KarakuriInstance,
      val chainAxis: org.bukkit.Axis,
      val clickedLoc: Location,
      val minChainLoc: Location,
      val maxChainLoc: Location,
    )

    // プレイヤーUUID -> 接続待機中の鎖親子セッション (鎖直線接続)
    val selectingChainChildKarakuri = ConcurrentHashMap<UUID, ChainChildSession>()

    // プレイヤーUUID -> 乗せたいからくりID (GUI発信のトロッコ待ち受けモード)
    val selectingMinecartForInstance = ConcurrentHashMap<UUID, String>()

    fun clearAllSelectingSessions(uuid: UUID) {
      selectingMinecarts.remove(uuid)
      selectingChildKarakuri.remove(uuid)
      selectingChainChildKarakuri.remove(uuid)
      selectingMinecartForInstance.remove(uuid)
      io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager.selectingRedstoneLinks.remove(uuid)
      io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager.selectingPlayers.remove(uuid)
    }

    fun startSelectingMinecartForInstance(player: org.bukkit.entity.Player, instance: io.oyasai.karakuri.engine.KarakuriInstance) {
      clearAllSelectingSessions(player.uniqueId)
      selectingMinecartForInstance[player.uniqueId] = instance.id

      player.sendMessage(net.kyori.adventure.text.Component.text("🚂 【トロッコ合体モード開始】", net.kyori.adventure.text.format.NamedTextColor.GOLD, net.kyori.adventure.text.format.TextDecoration.BOLD))
      player.sendMessage(net.kyori.adventure.text.Component.text("  ▶ [${instance.id}] を乗せたいトロッコを【右クリック】してください！", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
      player.sendMessage(net.kyori.adventure.text.Component.text("  ▶ からくりの中心軸がトロッコの中央に乗ります", net.kyori.adventure.text.format.NamedTextColor.GRAY))
      player.sendMessage(net.kyori.adventure.text.Component.text("  ▶ 解除するには【Shift + 左クリック】してください", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
      player.sendActionBar(net.kyori.adventure.text.Component.text("乗せたいトロッコを右クリックしてください (解除: Shift左クリック)", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
      player.playSound(player.location, org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  fun onInteract(event: PlayerInteractEvent) {
    if (event.hand != EquipmentSlot.HAND) return
    val action = event.action
    val player = event.player
    val item = player.inventory.itemInMainHand

    val isHoldingToolbox = KarakuriToolbox.isToolbox(item)
    val isHoldingGlue = KarakuriGlue.isGlue(item)
    val isHoldingWrench = KarakuriWrench.isWrench(item)
    val isHoldingRod = KarakuriPistonRod.isRod(item)
    val isHoldingRemote = io.oyasai.karakuri.tool.KarakuriRemote.isRemote(item)
    val couplingManager = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager
    val isIronChainItem = item.type == Material.IRON_CHAIN || item.type.name == "CHAIN"

    // ==========================================
    // ★ Shift + 左クリックによる全選択・連結モード解除 (手持ちアイテム問わず！)
    // ==========================================
    if (player.isSneaking && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)) {
      if (couplingManager.selectingPlayers.containsKey(player.uniqueId) ||
          selectingMinecartForInstance.containsKey(player.uniqueId) ||
          selectingMinecarts.containsKey(player.uniqueId) ||
          selectingChildKarakuri.containsKey(player.uniqueId) ||
          selectingChainChildKarakuri.containsKey(player.uniqueId)
      ) {
        event.isCancelled = true
        clearAllSelectingSessions(player.uniqueId)
        player.sendMessage(Component.text("🚫 各種接続・合体モードを解除しました。", NamedTextColor.YELLOW))
        player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)
        return
      }
    }

    // ==========================================
    // ★ 鉄の鎖によるからくりトロッコ連結（右クリックで視線レイキャスト検出！）
    // ==========================================
    if (isIronChainItem && (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR)) {
      val targetCart = rayTraceMinecartTarget(player)
        ?: event.clickedBlock?.let { resolveMinecartTarget(null, it.location.add(0.5, 0.5, 0.5)) }
      if (targetCart != null) {
        event.isCancelled = true
        if (handleCouplingInteraction(player, targetCart)) {
          return
        }
      }
    }

    // ==========================================
    // ★ からくり組み込みカーペットへの着席（素手通常右クリック最優先）
    // ==========================================
    if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) &&
        !player.isSneaking &&
        (item.isEmpty || item.type == Material.AIR)
    ) {
      val seatManager = io.oyasai.karakuri.KarakuriPlugin.instance.seatManager
      val carpetHit = seatManager.rayTraceCarpet(player)
      if (carpetHit != null) {
        event.isCancelled = true
        seatManager.sit(player, carpetHit.instance, carpetHit.blockLoc, carpetHit.scaleY)
        return
      }
    }

    // ==========================================
    // 0. 調律師の工具箱
    // ==========================================
    if (isHoldingToolbox) {
      if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
        event.isCancelled = true
        if (player.isSneaking) {
          // Shift + 右クリック: レッドストーンリモコン専用収納を開く！
          io.oyasai.karakuri.gui.KarakuriRemoteStorageGUI.open(player)
        } else {
          // 通常右クリック: 工具箱GUIを開く
          KarakuriToolboxGUI.open(player)
        }
        return
      }
      if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
        event.isCancelled = true
        KarakuriCommandGUI.open(player)
        return
      }
    }

    // ==========================================
    // 0-B. レッドストーンリモコン (周波数登録 & 信号送信)
    // ==========================================
    if (isHoldingRemote) {
      val wirelessManager = io.oyasai.karakuri.KarakuriPlugin.instance.wirelessRedstoneManager

      // (A) 送信機または受信機をクリックした場合: 周波数登録
      val clickedBlock = event.clickedBlock
      if (clickedBlock != null) {
        val node = wirelessManager.getNodeAt(clickedBlock) ?: wirelessManager.detectStructure(clickedBlock)?.let {
          wirelessManager.getNodeAt(it.rodBlock) ?: wirelessManager.getNodeAt(it.noteBlock)
        }
        if (node != null) {
          event.isCancelled = true
          val freq = node.frequency
          val mode = if (player.isSneaking) io.oyasai.karakuri.tool.RemoteSignalMode.TOGGLE else io.oyasai.karakuri.tool.RemoteSignalMode.PULSE
          val modeName = if (mode == io.oyasai.karakuri.tool.RemoteSignalMode.TOGGLE) "切り替え式" else "パルス式"
          if (action == Action.RIGHT_CLICK_BLOCK) {
            io.oyasai.karakuri.tool.KarakuriRemote.setRightFrequency(item, freq, mode)
            player.sendMessage(Component.text("📡 [リモコン] 右クリック送信周波数を【${freq.displayName}】($modeName) に登録しました！", NamedTextColor.GOLD, TextDecoration.BOLD))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f)
            player.spawnParticle(Particle.ELECTRIC_SPARK, clickedBlock.location.add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0.1)
          } else if (action == Action.LEFT_CLICK_BLOCK) {
            io.oyasai.karakuri.tool.KarakuriRemote.setLeftFrequency(item, freq, mode)
            player.sendMessage(Component.text("📡 [リモコン] 左クリック送信周波数を【${freq.displayName}】($modeName) に登録しました！", NamedTextColor.AQUA, TextDecoration.BOLD))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f)
            player.spawnParticle(Particle.ELECTRIC_SPARK, clickedBlock.location.add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0.1)
          }
          return
        }
      }

      // (B) 信号送信 (空中クリック、または一般ブロックをクリック時)
      if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
        event.isCancelled = true
        val freq = io.oyasai.karakuri.tool.KarakuriRemote.getRightFrequency(item)
        if (freq != null) {
          val mode = io.oyasai.karakuri.tool.KarakuriRemote.getRightMode(item)
          val result = wirelessManager.triggerRemoteSignal(player, freq, mode)
          if (result.totalReceivers > 0) {
            if (mode == io.oyasai.karakuri.tool.RemoteSignalMode.TOGGLE) {
              val stateText = if (result.toggledOnCount > 0) "切替ON" else "切替OFF"
              player.sendActionBar(Component.text("⚡ [右送信:$stateText] ${freq.displayName} (受信機: ${result.totalReceivers}台)", NamedTextColor.GOLD))
            } else {
              player.sendActionBar(Component.text("⚡ [右送信:パルス] ${freq.displayName} (受信機: ${result.totalReceivers}台)", NamedTextColor.GOLD))
            }
          } else {
            player.sendActionBar(Component.text("📡 [右送信] ${freq.displayName} (64m以内に受信機なし)", NamedTextColor.GRAY))
          }
        } else {
          player.sendActionBar(Component.text("⚠️ 右クリック送信周波数が未登録です (送信機/受信機をクリックで登録)", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f)
        }
        return
      }

      if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
        event.isCancelled = true
        val freq = io.oyasai.karakuri.tool.KarakuriRemote.getLeftFrequency(item)
        if (freq != null) {
          val mode = io.oyasai.karakuri.tool.KarakuriRemote.getLeftMode(item)
          val result = wirelessManager.triggerRemoteSignal(player, freq, mode)
          if (result.totalReceivers > 0) {
            if (mode == io.oyasai.karakuri.tool.RemoteSignalMode.TOGGLE) {
              val stateText = if (result.toggledOnCount > 0) "切替ON" else "切替OFF"
              player.sendActionBar(Component.text("⚡ [左送信:$stateText] ${freq.displayName} (受信機: ${result.totalReceivers}台)", NamedTextColor.AQUA))
            } else {
              player.sendActionBar(Component.text("⚡ [左送信:パルス] ${freq.displayName} (受信機: ${result.totalReceivers}台)", NamedTextColor.AQUA))
            }
          } else {
            player.sendActionBar(Component.text("📡 [左送信] ${freq.displayName} (64m以内に受信機なし)", NamedTextColor.GRAY))
          }
        } else {
          player.sendActionBar(Component.text("⚠️ 左クリック送信周波数が未登録です (送信機/受信機をクリックで登録)", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f)
        }
        return
      }
    }

    // ==========================================
    // 0-C. 登録済み音符ブロックの音階変更保護
    // ==========================================
    if (action == Action.RIGHT_CLICK_BLOCK && event.clickedBlock?.type == Material.NOTE_BLOCK) {
      val wirelessManager = io.oyasai.karakuri.KarakuriPlugin.instance.wirelessRedstoneManager
      val node = wirelessManager.getNodeAt(event.clickedBlock!!)
      if (node != null) {
        event.isCancelled = true
        player.sendMessage(Component.text("🔒 登録済みのワイヤレス機器のため、音階や音色は変更できません。", NamedTextColor.RED))
        player.sendMessage(Component.text("  ▶ 調律師のレンチで【左クリック】して解除すると変更可能になります。", NamedTextColor.GRAY))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
        return
      }
    }

    // ==========================================
    // 1. 調律師の接着剤の操作系
    // ==========================================
    if (isHoldingGlue) {
      val selection = KarakuriGlue.getSelection(player.uniqueId)

      // (A) Shift + 右クリック: 接着範囲の作成
      if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) && player.isSneaking) {
        event.isCancelled = true
        if (!selection.isComplete) {
          player.sendMessage(Component.text("先に始点(左クリック)と終点(右クリック)を選択してください。", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        val area = glueManager.addArea(selection.startPoint!!, selection.endPoint!!)
        selection.startPoint = null
        selection.endPoint = null

        val center = Location(
          player.world,
          (area.minX + area.maxX) / 2.0 + 0.5,
          (area.minY + area.maxY) / 2.0 + 0.5,
          (area.minZ + area.maxZ) / 2.0 + 0.5
        )
        player.spawnParticle(Particle.HAPPY_VILLAGER, center, 25, 0.5, 0.5, 0.5, 0.1)
        player.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.8f)
        player.sendMessage(Component.text("✔ 【接着範囲を作成しました！】 (${area.minX},${area.minY},${area.minZ} 〜 ${area.maxX},${area.maxY},${area.maxZ})", NamedTextColor.GREEN))
        player.sendMessage(Component.text("  ▶ 調律師のレンチで接着範囲内のブロックを右クリックすると丸ごとからくり化できます！", NamedTextColor.YELLOW))
        return
      }

      // (B) Shift + 左クリック: 接着範囲の消去
      if ((action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) && player.isSneaking) {
        event.isCancelled = true
        val targetArea = glueManager.rayTraceArea(player)
        if (targetArea != null) {
          glueManager.removeArea(targetArea.id)
          player.playSound(player.location, Sound.BLOCK_LAVA_EXTINGUISH, 0.8f, 1.4f)
          player.sendMessage(Component.text("🗑️ 視線先の接着範囲を消去しました。", NamedTextColor.RED))
        } else {
          player.sendMessage(Component.text("消去したい接着範囲に向けてShift+左クリックしてください。", NamedTextColor.YELLOW))
        }
        return
      }

      // (C) 通常 左クリック: 始点選択
      if (action == Action.LEFT_CLICK_BLOCK && !player.isSneaking) {
        val block = event.clickedBlock ?: return
        event.isCancelled = true
        selection.startPoint = block.location.clone()

        val pLoc = block.location.clone().add(0.5, 0.5, 0.5)
        player.spawnParticle(Particle.WAX_ON, pLoc, 15, 0.2, 0.2, 0.2, 0.05)
        player.playSound(pLoc, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 1.2f)
        player.sendMessage(Component.text("✦ 【接着始点を設定しました！】 (${block.x}, ${block.y}, ${block.z})", NamedTextColor.GOLD))
        if (selection.isComplete) {
          player.sendMessage(Component.text("  ▶ 【Shift + 右クリック】で接着範囲を作成できます！", NamedTextColor.YELLOW))
        } else {
          player.sendMessage(Component.text("  ▶ 対角のブロックを【右クリック】して終点を設定してください。", NamedTextColor.YELLOW))
        }
        return
      }

      // (D) 通常 右クリック: 終点選択
      if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking) {
        val block = event.clickedBlock ?: return
        event.isCancelled = true
        selection.endPoint = block.location.clone()

        val pLoc = block.location.clone().add(0.5, 0.5, 0.5)
        player.spawnParticle(Particle.WAX_ON, pLoc, 20, 0.3, 0.3, 0.3, 0.05)
        player.playSound(pLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f)
        player.sendMessage(Component.text("✦ 【接着終点を設定しました！】 (${block.x}, ${block.y}, ${block.z})", NamedTextColor.AQUA))
        if (selection.isComplete) {
          player.sendMessage(Component.text("  ▶ 【Shift + 右クリック】で接着範囲を作成できます！", NamedTextColor.YELLOW))
        } else {
          player.sendMessage(Component.text("  ▶ 始点が未設定です。【左クリック】で始点を設定してください。", NamedTextColor.YELLOW))
        }
        return
      }
    }

    // ==========================================
    // 2. 調律師のピストンロッド
    // ==========================================
    if (isHoldingRod) {
      val rodLine = KarakuriPistonRod.getLine(player.uniqueId)

      // (A) Shift + 左クリック: 設定したラインをからくりに適用
      if ((action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) && player.isSneaking) {
        event.isCancelled = true

        var targetKarakuri = manager.rayTrace(player.eyeLocation, player.location.direction, 5.0)
        val clickedBlock = event.clickedBlock

        if (targetKarakuri == null && clickedBlock != null && !clickedBlock.type.isAir) {
          targetKarakuri = manager.spawn(clickedBlock)
        }

        if (targetKarakuri != null) {
          applyPistonRodLine(player, targetKarakuri)
        } else {
          player.sendMessage(Component.text("からくりまたはブロックに視線を合わせてShift+左クリックしてください。", NamedTextColor.RED))
        }
        return
      }

      // (B) Shift + 右クリック: 直線運動設定盤を開く
      if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) && player.isSneaking) {
        val targetKarakuri = manager.rayTrace(player.eyeLocation, player.location.direction, 5.0)
        if (targetKarakuri != null) {
          event.isCancelled = true
          KarakuriLinearGUI(targetKarakuri).open(player)
          return
        }
      }

      // (C) 通常 左クリック: 始点 (Point A) を指定
      if (action == Action.LEFT_CLICK_BLOCK && !player.isSneaking) {
        val block = event.clickedBlock ?: return
        event.isCancelled = true

        val loc = block.location
        rodLine.startPoint = loc.clone()

        val pLoc = loc.clone().add(0.5, 0.5, 0.5)
        player.spawnParticle(Particle.END_ROD, pLoc, 15, 0.2, 0.2, 0.2, 0.05)
        player.playSound(pLoc, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 1.2f)

        player.sendMessage(Component.text("✦ 【始点 A を設定しました！】 (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})", NamedTextColor.GOLD))
        if (rodLine.isComplete) {
          showLineSummary(player, rodLine)
        } else {
          player.sendMessage(Component.text("  ▶ 次に目標地点のブロックを【右クリック】して終点 B を設定してください！", NamedTextColor.YELLOW))
        }
        return
      }

      // (D) 通常 右クリック: 終点 (Point B) を指定
      if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking) {
        val block = event.clickedBlock
        if (block != null) {
          event.isCancelled = true
          val loc = block.location
          rodLine.endPoint = loc.clone()

          val pLoc = loc.clone().add(0.5, 0.5, 0.5)
          player.spawnParticle(Particle.GLOW, pLoc, 20, 0.3, 0.3, 0.3, 0.05)
          player.playSound(pLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f)

          player.sendMessage(Component.text("✦ 【終点 B を設定しました！】 (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})", NamedTextColor.AQUA))
          if (rodLine.isComplete) {
            showLineSummary(player, rodLine)
          } else {
            player.sendMessage(Component.text("  ▶ 始点が未設定です。動かしたいブロックを【左クリック】して始点を設定してください。", NamedTextColor.YELLOW))
          }
          return
        }
      }
    }

    // ==========================================
    // 3. 調律師のレンチの操作系
    // ==========================================
    if (isHoldingWrench) {
      val hit = manager.rayTraceDetailed(player.eyeLocation, player.location.direction, 5.0)

      // (A) Shift + 左クリック: トロッコ合体モード解除、または初期位置リセット
      if ((action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) && player.isSneaking) {
        val rsLinkManager = io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager
        if (rsLinkManager.isSelecting(player)) {
          event.isCancelled = true
          rsLinkManager.cancelSelecting(player)
          return
        }
        if (selectingChildKarakuri.containsKey(player.uniqueId)) {
          event.isCancelled = true
          selectingChildKarakuri.remove(player.uniqueId)
          player.sendMessage(Component.text("🚫 子構造接続モードをキャンセルしました。", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)
          return
        }
        if (selectingChainChildKarakuri.containsKey(player.uniqueId)) {
          event.isCancelled = true
          selectingChainChildKarakuri.remove(player.uniqueId)
          player.sendMessage(Component.text("🚫 鎖・直線子構造接続モードをキャンセルしました。", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)
          return
        }
        if (selectingMinecartForInstance.containsKey(player.uniqueId)) {
          event.isCancelled = true
          selectingMinecartForInstance.remove(player.uniqueId)
          player.sendMessage(Component.text("🚫 トロッコ合体モードを解除しました。", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)
          return
        }
        if (selectingMinecarts.containsKey(player.uniqueId)) {
          event.isCancelled = true
          selectingMinecarts.remove(player.uniqueId)
          player.sendMessage(Component.text("🚫 トロッコ合体モードを解除しました。", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)
          return
        }
        if (hit != null) {
          event.isCancelled = true
          hit.instance.resetToBase()
          player.sendMessage(Component.text("⏮️ [${hit.instance.id}] 初期位置・初期姿勢にリセットしました！", NamedTextColor.LIGHT_PURPLE))
          player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f)
          return
        }
      }

      // (B-0) レンチ左クリックによるRS連動ブロック・ワイヤレス機器の登録解除
      if (action == Action.LEFT_CLICK_BLOCK) {
        val clickedBlock = event.clickedBlock
        if (clickedBlock != null) {
          val wirelessManager = io.oyasai.karakuri.KarakuriPlugin.instance.wirelessRedstoneManager
          val wirelessNode = wirelessManager.getNodeAt(clickedBlock)
          if (wirelessNode != null) {
            event.isCancelled = true
            wirelessManager.unregisterNode(wirelessNode.id)
            io.oyasai.karakuri.KarakuriPlugin.instance.database.deleteWirelessNode(wirelessNode.id)

            val pLoc = clickedBlock.location.add(0.5, 0.5, 0.5)
            player.spawnParticle(Particle.WAX_OFF, pLoc, 20, 0.3, 0.3, 0.3, 0.1)
            player.playSound(pLoc, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)

            val typeName = if (wirelessNode.type == io.oyasai.karakuri.engine.WirelessNodeType.TRANSMITTER) "ワイヤレス送信機" else "ワイヤレス受信機"
            player.sendMessage(Component.text("🔓 【${typeName}】の登録を解除しました！", NamedTextColor.YELLOW))
            player.sendMessage(Component.text("  ▶ もう一度通常通り左クリックでブロックを破壊できます。", NamedTextColor.GRAY))
            return
          }

          val rsLinkManager = io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager
          for (inst in manager.getAll()) {
            val link = inst.redstoneLinks.find {
              it.blockLocation.world == clickedBlock.world &&
              it.blockLocation.blockX == clickedBlock.x &&
              it.blockLocation.blockY == clickedBlock.y &&
              it.blockLocation.blockZ == clickedBlock.z
            }
            if (link != null) {
              event.isCancelled = true
              rsLinkManager.removeLink(inst, clickedBlock.location)

              val pLoc = clickedBlock.location.add(0.5, 0.5, 0.5)
              player.spawnParticle(Particle.WAX_OFF, pLoc, 20, 0.3, 0.3, 0.3, 0.1)
              player.playSound(pLoc, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)

              val typeName = io.oyasai.karakuri.engine.RedstoneLinkManager.getTypeName(link.type)
              player.sendMessage(Component.text("🔓 【${typeName}】のRS連動登録を解除しました！ (からくり: [${inst.id}])", NamedTextColor.YELLOW))
              player.sendMessage(Component.text("  ▶ もう一度通常通り左クリックでブロックを破壊できます。", NamedTextColor.GRAY))
              return
            }
          }
        }
      }

      // (B) 通常 左クリック: 複数ブロックからくりの回転軸（中心ピボット）を設定！
      if ((action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) && !player.isSneaking && hit != null) {
        event.isCancelled = true
        val pivotLoc = hit.hitBlockLocation
        hit.instance.setPivotToBlock(pivotLoc)

        val center = Location(pivotLoc.world, pivotLoc.blockX + 0.5, pivotLoc.blockY + 0.5, pivotLoc.blockZ + 0.5)
        player.spawnParticle(Particle.END_ROD, center, 20, 0.2, 0.2, 0.2, 0.05)
        player.spawnParticle(Particle.CRIT, center, 15, 0.3, 0.3, 0.3, 0.1)
        player.playSound(center, Sound.BLOCK_ANVIL_USE, 0.8f, 1.6f)

        player.sendActionBar(Component.text("🎯 [${hit.instance.id}] 回転軸の中心を (${pivotLoc.blockX}, ${pivotLoc.blockY}, ${pivotLoc.blockZ}) に設定しました！", NamedTextColor.GOLD))
        player.sendMessage(Component.text("🎯 【回転軸の中心を設定しました！】 (${pivotLoc.blockX}, ${pivotLoc.blockY}, ${pivotLoc.blockZ})", NamedTextColor.GOLD))
        return
      }

      // (C) 右クリック: からくり設定盤 (GUI) を開く、または新規からくり化（建築物接着 / 単一）
      if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
        event.isCancelled = true

        val rsLinkManager = io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager
        val clickedBlock = event.clickedBlock

        // (RS-W) ワイヤレスRS送信機・受信機の登録: 避雷針または音符ブロックを Shift + 右クリック
        if (player.isSneaking && clickedBlock != null && action == Action.RIGHT_CLICK_BLOCK) {
          val wirelessManager = io.oyasai.karakuri.KarakuriPlugin.instance.wirelessRedstoneManager
          val structure = wirelessManager.detectStructure(clickedBlock)
          if (structure != null) {
            val existing = wirelessManager.getNodeAt(clickedBlock)
            if (existing != null) {
              val exName = if (existing.type == io.oyasai.karakuri.engine.WirelessNodeType.TRANSMITTER) "送信機" else "受信機"
              player.sendMessage(Component.text("⚠️ このブロックはすでにワイヤレス${exName}として登録されています！", NamedTextColor.RED))
              player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
              return
            }

            val node = io.oyasai.karakuri.engine.WirelessNode(
              type = structure.type,
              rodLocation = structure.rodBlock.location,
              noteBlockLocation = structure.noteBlock.location,
              baseBlockLocation = structure.baseBlock.location,
              frequency = structure.frequency
            )
            wirelessManager.registerNode(node)
            io.oyasai.karakuri.KarakuriPlugin.instance.database.saveWirelessNode(node)

            val pLoc = structure.rodBlock.location.clone().add(0.5, 0.5, 0.5)
            player.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 30, 0.3, 0.3, 0.3, 0.1)
            player.playSound(pLoc, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f)
            player.playSound(pLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f)

            val typeName = if (structure.type == io.oyasai.karakuri.engine.WirelessNodeType.TRANSMITTER) "ワイヤレス送信機" else "ワイヤレス受信機"
            player.sendMessage(Component.text("⚡ 【${typeName}を登録しました！】", NamedTextColor.GREEN, TextDecoration.BOLD))
            player.sendMessage(Component.text("  ▶ 周波数: 【${structure.frequency.displayName}】", NamedTextColor.YELLOW))
            if (structure.type == io.oyasai.karakuri.engine.WirelessNodeType.TRANSMITTER) {
              player.sendMessage(Component.text("  ▶ 音符ブロックに通電中、64m以内の同周波数受信機へ信号を送信します！", NamedTextColor.GRAY))
            } else {
              player.sendMessage(Component.text("  ▶ 64m以内の同周波数送信機から信号を受信中、避雷針からRS信号を出力します！", NamedTextColor.GRAY))
            }
            player.sendMessage(Component.text("  ▶ 解除するにはレンチで【左クリック】してください。", NamedTextColor.DARK_GRAY))
            return
          }
        }

        // (RS-0) RS連動ブロック登録モード開始: ワールド側のRS連動ブロックを Shift + 右クリック
        if (player.isSneaking && clickedBlock != null && action == Action.RIGHT_CLICK_BLOCK) {
          val isPartOfKarakuri = hit != null && hit.instance.getBlockDataAt(clickedBlock.location) != null
          if (!isPartOfKarakuri && io.oyasai.karakuri.engine.RedstoneLinkManager.isRedstoneLinkableBlock(clickedBlock.type)) {
            clearAllSelectingSessions(player.uniqueId)
            rsLinkManager.startSelecting(player, clickedBlock.location)
            return
          }
        }

        // (RS-1) RS連動ブロック登録モード中の場合: からくりを右クリックで紐付け！
        if (rsLinkManager.isSelecting(player)) {
          if (hit != null) {
            rsLinkManager.linkToInstance(player, hit.instance)
          } else {
            player.sendMessage(Component.text("⚠️ 連動させたいからくりを調律師のレンチで右クリックしてください！", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          }
          return
        }

        // 1. もし子構造接続モード中の場合：別のからくりを右クリックで子構造として設定！
        val childSession = selectingChildKarakuri[player.uniqueId]
        if (childSession != null) {
          if (hit != null) {
            if (hit.instance.id == childSession.parentInstance.id) {
              player.sendMessage(Component.text("⚠️ 自分自身を子構造に設定することはできません！別のからくりをクリックしてください。", NamedTextColor.RED))
              player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
              return
            }

            // 親が動作中、または初期位置・初期姿勢にない場合は接続不可
            if (!canAttachChildContraption(childSession.parentInstance)) {
              player.sendMessage(Component.text("⚠️ 親構造のからくりが動作中、または初期位置・初期姿勢にありません！", NamedTextColor.RED))
              player.sendMessage(Component.text("  ▶ からくりを停止させ、初期位置・初期姿勢に戻してから接続してください。", NamedTextColor.GRAY))
              player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
              return
            }

            // 子構造候補がすでに別の子構造として接続されている場合は不可（ネスト禁止）
            if (hit.instance.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                hit.instance.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
              player.sendMessage(Component.text("⚠️ このからくりは既に別の子構造として接続されています！", NamedTextColor.RED))
              player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
              return
            }

            // 1. スマートワープ配置（ピストン前方へ密着ワープ）
            warpChildToStickyPistonFront(hit.instance, childSession.pistonLoc, childSession.pistonFacing)

            // 2. 子構造として接続！（デフォルトで独自自転OFF: isSpinActive = false）
            val motion = io.oyasai.karakuri.motion.ChildContraptionMotion(
              parentId = childSession.parentInstance.id,
              pistonOriginLoc = childSession.pistonLoc,
              pistonFacing = childSession.pistonFacing,
              followParentRotation = true,
              rpm = 16.0f,
              isClockwise = true,
              isSpinActive = false
            )
            hit.instance.setMotion(motion)
            selectingChildKarakuri.remove(player.uniqueId)

            val pLoc = hit.instance.displayEntity.location
            player.spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 35, 0.5, 0.5, 0.5, 0.1)
            player.playSound(pLoc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
            player.playSound(pLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f)
            player.sendMessage(Component.text("🔗 【子構造の接続が完了しました！】", NamedTextColor.GREEN, TextDecoration.BOLD))
            player.sendMessage(Component.text("  ▶ [${hit.instance.id}] を [${childSession.parentInstance.id}] の粘着ピストン前方に自動配置して接続しました！", NamedTextColor.AQUA))
            player.sendMessage(Component.text("  ▶ 親の回転軸を中心に公転しつつ、粘着ピストンの向き (${childSession.pistonFacing}) に連動します！", NamedTextColor.YELLOW))
            player.sendMessage(Component.text("  ▶ 子構造のからくりを【Shift + 右クリック】すると、独自自転や公転追従の設定盤が開きます！", NamedTextColor.GOLD))
          }
          return
        }

        // 1.5 もし鎖・直線子構造接続モード中の場合：別のからくりを右クリックで鎖子構造として設定！
        val chainSession = selectingChainChildKarakuri[player.uniqueId]
        if (chainSession != null) {
          if (hit != null) {
            if (hit.instance.id == chainSession.parentInstance.id) {
              player.sendMessage(Component.text("⚠️ 自分自身を子構造に設定することはできません！別のからくりをクリックしてください。", NamedTextColor.RED))
              player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
              return
            }

            if (!canAttachChildContraption(chainSession.parentInstance)) {
              player.sendMessage(Component.text("⚠️ 親構造のからくりが動作中、または初期位置・初期姿勢にありません！", NamedTextColor.RED))
              player.sendMessage(Component.text("  ▶ からくりを停止させ、初期位置・初期姿勢に戻してから接続してください。", NamedTextColor.GRAY))
              player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
              return
            }

            if (hit.instance.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                hit.instance.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
              player.sendMessage(Component.text("⚠️ このからくりは既に別の子構造として接続されています！", NamedTextColor.RED))
              player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
              return
            }

            // ワープ配置：子構造の中心軸が選択した鎖のブロック位置に重なるように配置
            val targetChainCenter = Location(
              chainSession.clickedLoc.world,
              chainSession.clickedLoc.blockX + 0.5,
              chainSession.clickedLoc.blockY + 0.5,
              chainSession.clickedLoc.blockZ + 0.5
            )
            warpChildToChainLocation(hit.instance, targetChainCenter)

            val motion = io.oyasai.karakuri.motion.ChainChildContraptionMotion(
              parentId = chainSession.parentInstance.id,
              chainAxis = chainSession.chainAxis,
              minChainLoc = chainSession.minChainLoc,
              maxChainLoc = chainSession.maxChainLoc,
              initialConnectLoc = chainSession.clickedLoc,
              speed = 2.0,
              linearMode = io.oyasai.karakuri.motion.LinearLoopMode.AUTO_PING_PONG,
              isRunning = false,
              followParentRotation = true,
              spinAxis = null,
              rpm = 16.0f,
              isClockwise = true,
              isSpinActive = false
            )
            hit.instance.setMotion(motion)
            selectingChainChildKarakuri.remove(player.uniqueId)

            val pLoc = hit.instance.displayEntity.location
            player.spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 35, 0.5, 0.5, 0.5, 0.1)
            player.playSound(pLoc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
            player.playSound(pLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f)
            player.sendMessage(Component.text("🔗 【鎖・直線子構造の接続が完了しました！】", NamedTextColor.GREEN, TextDecoration.BOLD))
            player.sendMessage(Component.text("  ▶ [${hit.instance.id}] の中心軸を [${chainSession.parentInstance.id}] の鎖に配置して接続しました！", NamedTextColor.AQUA))
            player.sendMessage(Component.text("  ▶ 鎖の直線に沿った移動と、自転回転を同時に設定できます！", NamedTextColor.YELLOW))
            io.oyasai.karakuri.gui.KarakuriChainChildGUI(hit.instance, motion).open(player)
            return
          }
          return
        }

        // 2. もしトロッコ選択モード中の場合：
        val selectedCartId = selectingMinecarts[player.uniqueId]
        if (selectedCartId != null) {
          if (hit != null) {
            val cart = Bukkit.getEntity(selectedCartId) as? Minecart
            if (cart != null && cart.isValid) {
              manager.mountToMinecart(hit.instance, cart, followRotation = true)
              selectingMinecarts.remove(player.uniqueId)

              player.spawnParticle(Particle.HAPPY_VILLAGER, hit.instance.displayEntity.location, 30, 0.5, 0.5, 0.5, 0.1)
              player.playSound(hit.instance.displayEntity.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f)
              player.sendMessage(Component.text("🎉 [${hit.instance.id}] をトロッコに合体させました！", NamedTextColor.GREEN))
              player.sendMessage(Component.text("  ▶ トロッコを走らせるとからくりが連動して移動します！", NamedTextColor.YELLOW))
              return
            } else {
              selectingMinecarts.remove(player.uniqueId)
              player.sendMessage(Component.text("❌ 対象のトロッコが見つかりませんでした。", NamedTextColor.RED))
              return
            }
          }
          // からくり以外をクリックした場合は何もしない（通常機能のGUIや新規からくり化は実行しない）
          return
        }

        // 3. 既存からくりの場合
        if (hit != null) {
          // (3-A) Shift + 右クリック時:
          if (player.isSneaking) {
            // (ア) もし既に子構造の場合 -> 専用の子構造設定メニューを開く！
            val childMotion = hit.instance.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion
            if (childMotion != null) {
              io.oyasai.karakuri.gui.KarakuriChildGUI(hit.instance, childMotion).open(player)
              return
            }

            val chainMotion = hit.instance.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion
            if (chainMotion != null) {
              io.oyasai.karakuri.gui.KarakuriChainChildGUI(hit.instance, chainMotion).open(player)
              return
            }

            // (イ) からくりに組み込まれた粘着ピストンをクリック -> 回転子構造接続モード開始！
            val blockData = hit.instance.getBlockDataAt(hit.hitBlockLocation)
            if (blockData?.material == Material.STICKY_PISTON) {
              // 子構造のからくりに組み込まれた粘着ピストンはただのピストンとして扱い、子構造の子構造は作らせない
              if (hit.instance.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                  hit.instance.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
                player.sendMessage(Component.text("⚠️ 子構造のからくりにさらに子構造を接続することはできません。", NamedTextColor.RED))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              // 親構造の子構造上限チェック (最大8個)
              if (getChildContraptions(hit.instance).size >= 8) {
                player.sendMessage(Component.text("⚠️ この親構造には既に最大数（8個）の子構造が接続されています！", NamedTextColor.RED))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              // 同一粘着ピストンへの重複接続チェック
              if (isPistonAlreadyOccupied(hit.instance, hit.hitBlockLocation)) {
                player.sendMessage(Component.text("⚠️ この粘着ピストンには既に別の子構造が接続されています！", NamedTextColor.RED))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              // 親が動作中、または初期位置・初期姿勢にない場合は開始不可
              if (!canAttachChildContraption(hit.instance)) {
                player.sendMessage(Component.text("⚠️ 親構造のからくりが動作中、または初期位置・初期姿勢にありません！", NamedTextColor.RED))
                player.sendMessage(Component.text("  ▶ からくりを停止させ、初期位置・初期姿勢に戻してから接続してください。", NamedTextColor.GRAY))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              // 排他制御
              selectingMinecarts.remove(player.uniqueId)
              selectingMinecartForInstance.remove(player.uniqueId)
              selectingChainChildKarakuri.remove(player.uniqueId)

              val facing = (blockData as? org.bukkit.block.data.Directional)?.facing ?: org.bukkit.block.BlockFace.UP
              selectingChildKarakuri[player.uniqueId] = ParentChildSession(hit.instance, hit.hitBlockLocation.clone(), facing)

              val bCenter = Location(hit.hitBlockLocation.world, hit.hitBlockLocation.blockX + 0.5, hit.hitBlockLocation.blockY + 0.5, hit.hitBlockLocation.blockZ + 0.5)
              player.spawnParticle(Particle.PORTAL, bCenter, 30, 0.3, 0.3, 0.3, 0.1)
              player.playSound(bCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)
              player.playSound(bCenter, Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.2f)

              val axisName = io.oyasai.karakuri.motion.ChildContraptionMotion.getPistonAxisName(facing)
              player.sendMessage(Component.text("🔗 【回転・子構造接続モード開始】", NamedTextColor.GOLD, TextDecoration.BOLD))
              player.sendMessage(Component.text("  ▶ 接続したい第二のからくりを調律師のレンチで【右クリック】してください！", NamedTextColor.YELLOW))
              player.sendMessage(Component.text("  ▶ 粘着ピストン位置: (${hit.hitBlockLocation.blockX}, ${hit.hitBlockLocation.blockY}, ${hit.hitBlockLocation.blockZ}) / 向き: $facing", NamedTextColor.WHITE))
              player.sendMessage(Component.text("  ▶ 子構造の回転軸: $axisName （粘着ピストンの場所が回転の中心軸になります）", NamedTextColor.AQUA))
              player.sendMessage(Component.text("  ▶ 解除するには【Shift + 左クリック】してください", NamedTextColor.GRAY))
              player.sendActionBar(Component.text("接続したい別のからくりをレンチで右クリック (解除: Shift左クリック)", NamedTextColor.YELLOW))
              return
            }

            // (ウ) からくりに組み込まれた鉄の鎖・非錆止め銅の鎖をクリック -> 鎖・直線子構造接続モード開始！
            if (isSupportedChainMaterial(blockData?.material ?: Material.AIR)) {
              if (hit.instance.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                  hit.instance.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
                player.sendMessage(Component.text("⚠️ 子構造のからくりにさらに子構造を接続することはできません。", NamedTextColor.RED))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              // 親構造の子構造上限チェック (最大8個)
              if (getChildContraptions(hit.instance).size >= 8) {
                player.sendMessage(Component.text("⚠️ この親構造には既に最大数（8個）の子構造が接続されています！", NamedTextColor.RED))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              // 同一鎖への重複接続チェック
              if (isChainAlreadyOccupied(hit.instance, hit.hitBlockLocation)) {
                player.sendMessage(Component.text("⚠️ この鎖には既に別の子構造が接続されています！", NamedTextColor.RED))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              if (!canAttachChildContraption(hit.instance)) {
                player.sendMessage(Component.text("⚠️ 親構造のからくりが動作中、または初期位置・初期姿勢にありません！", NamedTextColor.RED))
                player.sendMessage(Component.text("  ▶ からくりを停止させ、初期位置・初期姿勢に戻してから接続してください。", NamedTextColor.GRAY))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              val scan = scanConnectedChains(hit.instance, hit.hitBlockLocation)
              if (scan == null) {
                player.sendMessage(Component.text("⚠️ 鎖ブロックの検出に失敗しました。", NamedTextColor.RED))
                return
              }

              // 鉄の鎖がエンティティ統合されている場合は子構造システムに使用不可
              val hasMergedIronChain = scan.allLocations.any { loc ->
                val data = hit.instance.getBlockDataAt(loc)
                val mName = data?.material?.name ?: ""
                (mName == "CHAIN" || mName == "IRON_CHAIN") && hit.instance.isBlockMerged(loc)
              }
              if (hasMergedIronChain) {
                player.sendMessage(Component.text("⚠️ 選択した鎖（または直線上の鎖）にエンティティ統合された鉄の鎖が含まれているため、子構造システムには使用できません。", NamedTextColor.RED))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
                return
              }

              selectingMinecarts.remove(player.uniqueId)
              selectingChildKarakuri.remove(player.uniqueId)

              selectingChainChildKarakuri[player.uniqueId] = ChainChildSession(
                parentInstance = hit.instance,
                chainAxis = scan.axis,
                clickedLoc = scan.clickedLoc,
                minChainLoc = scan.minLoc,
                maxChainLoc = scan.maxLoc
              )

              val bCenter = Location(hit.hitBlockLocation.world, hit.hitBlockLocation.blockX + 0.5, hit.hitBlockLocation.blockY + 0.5, hit.hitBlockLocation.blockZ + 0.5)
              player.spawnParticle(Particle.PORTAL, bCenter, 30, 0.3, 0.3, 0.3, 0.1)
              player.playSound(bCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)
              player.playSound(bCenter, Sound.BLOCK_CHAIN_PLACE, 1.0f, 1.2f)

              player.sendMessage(Component.text("⛓️ 【鎖・直線子構造接続モード開始】", NamedTextColor.GOLD, TextDecoration.BOLD))
              player.sendMessage(Component.text("  ▶ 接続したい第二のからくりを調律師のレンチで【右クリック】してください！", NamedTextColor.YELLOW))
              player.sendMessage(Component.text("  ▶ 選択された鎖の長さ: ${scan.totalChains} ブロック (軸: ${scan.axis} 軸)", NamedTextColor.WHITE))
              player.sendMessage(Component.text("  ▶ 解除するには【Shift + 左クリック】してください", NamedTextColor.GRAY))
              player.sendActionBar(Component.text("接続したい別のからくりをレンチで右クリック (解除: Shift左クリック)", NamedTextColor.YELLOW))
              return
            }
          }

          // (3-B) 通常右クリック時:
          val childMotion = hit.instance.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion
          if (childMotion != null) {
            io.oyasai.karakuri.gui.KarakuriChildGUI(hit.instance, childMotion).open(player)
            return
          }

          val chainMotion = hit.instance.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion
          if (chainMotion != null) {
            io.oyasai.karakuri.gui.KarakuriChainChildGUI(hit.instance, chainMotion).open(player)
            return
          }

          val cartMotion = hit.instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
          if (cartMotion != null) {
            io.oyasai.karakuri.gui.KarakuriMinecartGUI(hit.instance, cartMotion).open(player)
          } else {
            KarakuriGUI(hit.instance).open(player)
          }
          return
        }

        if (clickedBlock != null && !clickedBlock.type.isAir && clickedBlock.type != Material.BEDROCK) {
          // 接着範囲内にあるか判定！
          val connectedAreas = glueManager.getConnectedAreasForBlock(clickedBlock.location)
          if (connectedAreas.isNotEmpty()) {
            // 【建築物の丸ごとからくり化！】
            val solidBlocks = glueManager.getSolidBlocksInAreas(connectedAreas)
            if (solidBlocks.isNotEmpty()) {
              manager.spawnCompositeAsync(solidBlocks, clickedBlock, player) { instance ->
                instance.setMotion(DoorMotion())

                // 使用した接着範囲を消去
                connectedAreas.forEach { glueManager.removeArea(it.id) }

                val pLoc = clickedBlock.location.clone().add(0.5, 0.5, 0.5)
                player.spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 40, 1.0, 1.0, 1.0, 0.1)
                player.playSound(pLoc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
                player.playSound(pLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.8f)

                player.sendMessage(Component.text("✦ 接着された建築物 (${solidBlocks.size}ブロック) を丸ごと『からくり』に変換しました！", NamedTextColor.GOLD))
                player.sendMessage(Component.text("  ▶ 調律師のレンチで右クリックすると設定盤(GUI)が開きます", NamedTextColor.YELLOW))
                player.sendMessage(Component.text("  ▶ 調律師のレンチで左クリックすると回転軸の中心ブロックを変更できます", NamedTextColor.AQUA))
              }
              return
            }
          }

          // 単一ブロックのからくり化
          val matName = clickedBlock.type.name
          val spawnLoc = clickedBlock.location.clone()
          manager.spawnCompositeAsync(listOf(clickedBlock), clickedBlock, player) { instance ->
            instance.setMotion(DoorMotion())
            player.sendMessage(Component.text("✦ ブロック ($matName) を『からくり』に変換しました！", NamedTextColor.GOLD))
            player.sendActionBar(Component.text("調律師のレンチで右クリックすると設定盤(GUI)が開きます", NamedTextColor.YELLOW))
            player.playSound(spawnLoc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
            player.playSound(spawnLoc, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f)
          }
          return
        }
      }
    }

    // ==========================================
    // 4. 素手での手動作動 (Shift + 右クリックのみ)
    // ==========================================
    if (player.isSneaking && (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR)) {
      if (!isHoldingWrench && !isHoldingRod && !isHoldingGlue && !isHoldingToolbox) {
        val targetKarakuri = manager.rayTrace(player.eyeLocation, player.location.direction, 5.0)
        if (targetKarakuri != null) {
          event.isCancelled = true
          if (targetKarakuri.isImmovable) {
            player.sendActionBar(Component.text("🗿 このからくりは「不動」に設定されているため作動しません", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.8f, 0.8f)
            return
          }
          triggerManual(player, targetKarakuri)
        }
      }
    }
  }

  /**
   * エンティティまたは座標から、関連するトロッコ（Minecart）を検出
   * からくりのパーツ(Interaction / Display)やブロックがクリックされた場合も、
   * そのからくりが合体しているトロッコをスマートに特定する
   */
  private fun resolveMinecartTarget(entity: org.bukkit.entity.Entity?, loc: Location?): Minecart? {
    if (entity is Minecart) return entity

    // 1. からくりのエンティティから特定
    if (entity != null) {
      val inst = manager.getByEntity(entity)
      val cartMotion = inst?.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
      if (cartMotion != null && cartMotion.minecart.isValid) {
        return cartMotion.minecart
      }
    }

    // 2. 座標からからくりを特定
    if (loc != null) {
      val hit = manager.rayTraceDetailed(loc, org.bukkit.util.Vector(0.0, -1.0, 0.0), 2.0)
      val inst = hit?.instance
      val cartMotion = inst?.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
      if (cartMotion != null && cartMotion.minecart.isValid) {
        return cartMotion.minecart
      }

      // 直下または周辺2.0m以内のトロッコを検索
      val nearbyCarts = loc.world.getNearbyEntities(loc, 2.0, 2.0, 2.0).filterIsInstance<Minecart>()
      val karakuriCart = nearbyCarts.firstOrNull { manager.isKarakuriMinecart(it) }
      if (karakuriCart != null) return karakuriCart
      if (nearbyCarts.isNotEmpty()) return nearbyCarts.first()
    }

    // 3. エンティティ周辺2.0m以内のトロッコ
    if (entity != null) {
      val nearbyCarts = entity.world.getNearbyEntities(entity.location, 2.0, 2.0, 2.0).filterIsInstance<Minecart>()
      val karakuriCart = nearbyCarts.firstOrNull { manager.isKarakuriMinecart(it) }
      if (karakuriCart != null) return karakuriCart
      if (nearbyCarts.isNotEmpty()) return nearbyCarts.first()
    }

    return null
  }

  /**
   * からくり化レール等の直上（Y -0.2m 〜 +1.2m、水平0.65m）にあるトロッコを検出
   */
  private fun findCartAboveEntity(entity: Entity): Minecart? {
    val loc = entity.location
    val world = entity.world
    val checkLoc = loc.clone().add(0.0, 0.4, 0.0)
    val nearby = world.getNearbyEntities(checkLoc, 0.65, 0.8, 0.65).filterIsInstance<Minecart>()
    val kCart = nearby.firstOrNull { manager.isKarakuriMinecart(it) }
    if (kCart != null) return kCart
    return nearby.firstOrNull()
  }

  /**
   * プレイヤーの視線（クロスヘア）から対象のトロッコを高精度に特定する
   * からくりエンティティやレール・床越しであっても、視線が捉えているトロッコを貫通検出！
   */
  private fun rayTraceMinecartTarget(player: Player, maxDist: Double = 8.0): Minecart? {
    val world = player.world
    val eyeLoc = player.eyeLocation
    val dir = eyeLoc.direction

    // 1. エンティティに対するレイキャスト
    val entityRay = world.rayTraceEntities(eyeLoc, dir, maxDist) { e ->
      e != player && (e is Minecart || e is Display || e is Interaction)
    }
    val hitEntity = entityRay?.hitEntity
    if (hitEntity != null) {
      if (hitEntity is Minecart) return hitEntity

      // からくりエンティティから特定
      val inst = manager.getByEntity(hitEntity)
      val cartMotion = inst?.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
      if (cartMotion != null && cartMotion.minecart.isValid) {
        return cartMotion.minecart
      }

      // ヒット位置直下・周辺のトロッコ
      val hitLoc = entityRay.hitPosition.toLocation(world)
      val nearbyCarts = world.getNearbyEntities(hitLoc, 2.0, 2.0, 2.0).filterIsInstance<Minecart>()
      val kCart = nearbyCarts.firstOrNull { manager.isKarakuriMinecart(it) }
      if (kCart != null) return kCart
      if (nearbyCarts.isNotEmpty()) return nearbyCarts.first()
    }

    // 2. ブロック/空間に対するレイキャスト
    val blockRay = world.rayTraceBlocks(eyeLoc, dir, maxDist)
    val checkLoc = blockRay?.hitPosition?.toLocation(world)
      ?: eyeLoc.clone().add(dir.clone().multiply(3.0))

    val nearby = world.getNearbyEntities(checkLoc, 2.0, 2.0, 2.0).filterIsInstance<Minecart>()
    val kCart = nearby.firstOrNull { manager.isKarakuriMinecart(it) }
    if (kCart != null) return kCart
    if (nearby.isNotEmpty()) return nearby.first()

    return null
  }

  /**
   * トロッコ連結インタラクションの共通処理
   */
  private fun handleCouplingInteraction(player: Player, targetCart: Minecart): Boolean {
    val couplingManager = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager
    val firstCart = couplingManager.selectingPlayers[player.uniqueId]

    if (firstCart == null) {
      // 1両目を指定して連結モード開始 (通常右クリックでもShift右クリックでもOK！)
      clearAllSelectingSessions(player.uniqueId)
      couplingManager.selectingPlayers[player.uniqueId] = targetCart
      player.spawnParticle(Particle.PORTAL, targetCart.location.clone().add(0.0, 0.5, 0.0), 25, 0.3, 0.3, 0.3, 0.1)
      player.sendMessage(Component.text("⛓️ 【トロッコ連結モード開始】 1両目を指定しました！", NamedTextColor.GOLD, TextDecoration.BOLD))
      player.sendMessage(Component.text("  ▶ 連結したい【2両目のトロッコ】を鉄の鎖で【右クリック】してください！", NamedTextColor.YELLOW))
      player.sendMessage(Component.text("  ▶ 最大連結距離: 16ブロック (解除: 空中/地面を Shift + 左クリック)", NamedTextColor.GRAY))
      player.sendActionBar(Component.text("⛓️ 連結したい2両目のトロッコを鉄の鎖で右クリック (解除: Shift左クリック)", NamedTextColor.YELLOW))
      return true
    } else {
      // 2両目を右クリック: 連結実行
      if (firstCart == targetCart) {
        player.sendMessage(Component.text("⚠️ 1両目と同じトロッコです！別のトロッコを右クリックしてください (解除: Shift左クリック)。", NamedTextColor.RED))
        return true
      }

      // 1. 各トロッコの接続数制限 (最大2台: 中間車両にさらに繋ぐ分岐を防止)
      if (couplingManager.getCouplingDegree(firstCart) >= 2) {
        player.sendMessage(Component.text("❌ 1両目のトロッコは既に前後の2両と接続されています（これ以上分岐させることはできません）。", NamedTextColor.RED))
        return true
      }
      if (couplingManager.getCouplingDegree(targetCart) >= 2) {
        player.sendMessage(Component.text("❌ 2両目のトロッコは既に前後の2両と接続されています（これ以上分岐させることはできません）。", NamedTextColor.RED))
        return true
      }

      // 2. 閉路チェック: 既に同じ編成に含まれるトロッコ同士の接続（ループ）を完全防止！
      if (couplingManager.areCartsInSameTrain(firstCart, targetCart)) {
        player.sendMessage(Component.text("❌ 既に同じ列車編成に含まれているトロッコ同士を接続して輪（ループ）を作ることはできません。", NamedTextColor.RED))
        return true
      }

      // 3. 通常トロッコのみの組み合わせは不可（編成全体で少なくとも1両はからくり合体トロッコであること）
      val hasKarakuriA = couplingManager.trainContainsKarakuri(firstCart)
      val hasKarakuriB = couplingManager.trainContainsKarakuri(targetCart)
      if (!hasKarakuriA && !hasKarakuriB) {
        player.sendMessage(Component.text("❌ 通常のトロッコ同士のみを連結することはできません。少なくとも1両はからくりトロッコである必要があります。", NamedTextColor.RED))
        return true
      }

      val dist = firstCart.location.distance(targetCart.location)
      if (dist > io.oyasai.karakuri.engine.MinecartCouplingManager.MAX_COUPLING_DISTANCE) {
        player.sendMessage(Component.text("❌ トロッコ同士の距離が遠すぎます (${String.format("%.1f", dist)}m > 16m)。", NamedTextColor.RED))
        return true
      }

      val link = couplingManager.linkCarts(firstCart, targetCart)
      couplingManager.selectingPlayers.remove(player.uniqueId)
      if (link != null) {
        player.sendMessage(Component.text("🎉 トロッコ同士を連結しました！ (車間距離: ${String.format("%.1f", link.distance)}m)", NamedTextColor.GREEN, TextDecoration.BOLD))
        player.sendMessage(Component.text("  ▶ 連結したトロッコは先頭車両の速度・レール軌跡に沿って一体となって走行します。", NamedTextColor.YELLOW))
        player.sendMessage(Component.text("  ▶ 連結を解除するには、車両間の鎖を【Shift + 左クリック】してください。", NamedTextColor.GRAY))
        player.spawnParticle(Particle.HAPPY_VILLAGER, targetCart.location.clone().add(0.0, 0.5, 0.0), 30, 0.5, 0.5, 0.5, 0.1)
      } else {
        player.sendMessage(Component.text("❌ 連結に失敗しました（既に直接連結されているか無効な状態です）。", NamedTextColor.RED))
      }
      return true
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  fun onInteractEntity(event: PlayerInteractEntityEvent) {
    if (event.hand != EquipmentSlot.HAND) return
    val entity = event.rightClicked
    val player = event.player
    val item = player.inventory.itemInMainHand
    val isHoldingWrench = KarakuriWrench.isWrench(item)

    // 0-A. 鉄の鎖によるトロッコ連結モード（からくりのInteraction/Displayクリック時も完全検出！）
    val isIronChainItem = item.type == Material.IRON_CHAIN || item.type.name == "CHAIN"
    if (isIronChainItem) {
      val targetCart = resolveMinecartTarget(entity, entity.location) ?: rayTraceMinecartTarget(player)
      if (targetCart != null) {
        event.isCancelled = true
        if (handleCouplingInteraction(player, targetCart)) {
          return
        }
      }
    }

    // ★ 0-B. からくり化レール等の上にトロッコが存在する場合、レールの判定よりトロッコへのクリックを最優先！
    if (entity is Interaction || entity is Display) {
      val cartAbove = findCartAboveEntity(entity)
      if (cartAbove != null && !isIronChainItem) {
        val cartMotion = (manager.getKarakuriByMinecart(cartAbove)?.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion)
        val kInst = manager.getKarakuriByMinecart(cartAbove)

        if (isHoldingWrench && kInst != null && cartMotion != null) {
          event.isCancelled = true
          io.oyasai.karakuri.gui.KarakuriMinecartGUI(kInst, cartMotion).open(player)
          return
        }

        // 素手等の右クリック時、乗車可能トロッコなら乗車！
        if (!player.isSneaking && cartAbove is RideableMinecart && cartAbove.isEmpty) {
          event.isCancelled = true
          cartAbove.addPassenger(player)
          return
        }
      }
    }

    // 0. GUI発信のトロッコ待ち受けモード中（スロット14から発信）
    val pendingInstanceId = selectingMinecartForInstance[player.uniqueId]
    if (pendingInstanceId != null) {
      event.isCancelled = true
      if (entity is RideableMinecart) {
        val targetKarakuri = manager.get(pendingInstanceId)
        if (targetKarakuri == null || !targetKarakuri.isValid()) {
          selectingMinecartForInstance.remove(player.uniqueId)
          player.sendMessage(Component.text("❌ 対象のからくりが見つからないか無効です。", NamedTextColor.RED))
          return
        }
        if (manager.isKarakuriMinecart(entity)) {
          player.sendMessage(Component.text("⚠️ このトロッコには既に別のからくりが乗っています！", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        manager.mountToMinecart(targetKarakuri, entity, followRotation = true)
        selectingMinecartForInstance.remove(player.uniqueId)

        player.spawnParticle(Particle.HAPPY_VILLAGER, targetKarakuri.displayEntity.location, 30, 0.5, 0.5, 0.5, 0.1)
        player.playSound(targetKarakuri.displayEntity.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f)
        player.sendMessage(Component.text("🎉 [${targetKarakuri.id}] をトロッコに合体させました！", NamedTextColor.GREEN))
        player.sendMessage(Component.text("  ▶ トロッコを走らせるとからくりが連動して移動します！", NamedTextColor.YELLOW))
        player.sendMessage(Component.text("  ▶ レンチで右クリックすると設定盤(進行方向追従の切替など)が開きます。", NamedTextColor.AQUA))
        return
      } else {
        player.sendMessage(Component.text("⚠️ 普通のトロッコを右クリックしてください（解除: Shift + 左クリック）", NamedTextColor.YELLOW))
        return
      }
    }

    // ★ カーペット専用クリック判定エンティティに対する素手右クリック
    if (entity.scoreboardTags.contains(io.oyasai.karakuri.engine.KarakuriSeatManager.TAG_CARPET_INTERACTION)) {
      if (!player.isSneaking && (item.isEmpty || item.type == Material.AIR)) {
        event.isCancelled = true
        val seatManager = io.oyasai.karakuri.KarakuriPlugin.instance.seatManager
        val carpetHit = seatManager.rayTraceCarpet(player)
        if (carpetHit != null) {
          seatManager.sit(player, carpetHit.instance, carpetHit.blockLoc, carpetHit.scaleY)
          return
        }
      }
    }

    // 1. からくりトロッコへの一般乗車を禁止
    if (entity is Minecart && manager.isKarakuriMinecart(entity)) {
      if (!isHoldingWrench) {
        event.isCancelled = true
        player.sendActionBar(Component.text("🚫 からくりが乗っているトロッコには乗車できません", NamedTextColor.RED))
        return
      }
    }

    // 対象のからくりインスタンスを特定（直接クリック、またはトロッコ経由）
    var targetKarakuri = manager.getByEntity(entity)
    if (targetKarakuri == null && entity is Minecart) {
      targetKarakuri = manager.getKarakuriByMinecart(entity)
    }

    // 2. 空のトロッコに対する操作 (targetKarakuri == null)
    if (entity is Minecart && targetKarakuri == null) {
      if (isHoldingWrench) {
        event.isCancelled = true

        if (entity !is RideableMinecart) {
          player.sendMessage(Component.text("⚠️ 普通のトロッコのみ合体可能です（チェスト・ホッパー付き等は不可）", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        // Shift + 右クリック: トロッコ合体モード開始
        if (player.isSneaking) {
          clearAllSelectingSessions(player.uniqueId)
          selectingMinecarts[player.uniqueId] = entity.uniqueId
          player.spawnParticle(Particle.HAPPY_VILLAGER, entity.location.clone().add(0.0, 0.5, 0.0), 25, 0.3, 0.3, 0.3, 0.1)
          player.playSound(entity.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)
          player.sendMessage(Component.text("🚂 【トロッコ合体モード開始】", NamedTextColor.GOLD))
          player.sendMessage(Component.text("  ▶ 乗せたいからくりを調律師のレンチで【右クリック】してください！", NamedTextColor.YELLOW))
          player.sendMessage(Component.text("  ▶ からくりの中心軸（回転軸）ブロックがトロッコの中央に乗ります（左クリックで変更可能）", NamedTextColor.GRAY))
          player.sendMessage(Component.text("  ▶ 解除するには【Shift + 左クリック】してください", NamedTextColor.GRAY))
          player.sendActionBar(Component.text("乗せたいからくりをレンチで右クリックしてください (解除: Shift左クリック)", NamedTextColor.YELLOW))
          return
        }
      }
      return
    }

    // 3. からくり（またはからくりが乗ったトロッコ）に対する操作
    if (targetKarakuri == null) return
    event.isCancelled = true

    // ★からくり組み込みカーペットへの着席（素手通常右クリック最優先）
    if (!player.isSneaking && (item.isEmpty || item.type == Material.AIR)) {
      val seatManager = io.oyasai.karakuri.KarakuriPlugin.instance.seatManager
      val carpetHit = seatManager.rayTraceCarpet(player)
      if (carpetHit != null) {
        seatManager.sit(player, carpetHit.instance, carpetHit.blockLoc, carpetHit.scaleY)
        return
      }
    }

    if (isHoldingWrench) {
      // (0) RS連動ブロック登録モード中の場合：クリックしたからくりに紐付け！
      val rsLinkManager = io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager
      if (rsLinkManager.isSelecting(player)) {
        rsLinkManager.linkToInstance(player, targetKarakuri)
        return
      }

      // (1) 子構造選択モード中の場合：クリックしたからくりを子構造に設定！
      val childSession = selectingChildKarakuri[player.uniqueId]
      if (childSession != null) {
        if (targetKarakuri.id == childSession.parentInstance.id) {
          player.sendMessage(Component.text("⚠️ 自分自身を子構造に設定することはできません！別のからくりをクリックしてください。", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        if (!canAttachChildContraption(childSession.parentInstance)) {
          player.sendMessage(Component.text("⚠️ 親構造のからくりが動作中、または初期位置・初期姿勢にありません！", NamedTextColor.RED))
          player.sendMessage(Component.text("  ▶ からくりを停止させ、初期位置・初期姿勢に戻してから接続してください。", NamedTextColor.GRAY))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        if (targetKarakuri.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
            targetKarakuri.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
          player.sendMessage(Component.text("⚠️ このからくりは既に別の子構造として接続されています！", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        // スマートワープ配置（ピストン前方へ密着ワープ）
        warpChildToStickyPistonFront(targetKarakuri, childSession.pistonLoc, childSession.pistonFacing)

        // 子構造として接続
        val motion = io.oyasai.karakuri.motion.ChildContraptionMotion(
          parentId = childSession.parentInstance.id,
          pistonOriginLoc = childSession.pistonLoc,
          pistonFacing = childSession.pistonFacing,
          followParentRotation = true,
          rpm = 16.0f,
          isClockwise = true,
          isSpinActive = false
        )
        targetKarakuri.setMotion(motion)
        selectingChildKarakuri.remove(player.uniqueId)

        val pLoc = targetKarakuri.displayEntity.location
        player.spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 35, 0.5, 0.5, 0.5, 0.1)
        player.playSound(pLoc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
        player.playSound(pLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f)
        player.sendMessage(Component.text("🔗 【子構造の接続が完了しました！】", NamedTextColor.GREEN, TextDecoration.BOLD))
        player.sendMessage(Component.text("  ▶ [${targetKarakuri.id}] を [${childSession.parentInstance.id}] の粘着ピストン前方に自動配置して接続しました！", NamedTextColor.AQUA))
        player.sendMessage(Component.text("  ▶ 親の回転軸を中心に公転しつつ、粘着ピストンの向き (${childSession.pistonFacing}) に連動します！", NamedTextColor.YELLOW))
        player.sendMessage(Component.text("  ▶ 子構造のからくりを【Shift + 右クリック】すると、独自自転や公転追従の設定盤が開きます！", NamedTextColor.GOLD))
        return
      }

      // (2) 鎖・直線子構造選択モード中の場合：クリックしたからくりを鎖子構造に設定！
      val chainSession = selectingChainChildKarakuri[player.uniqueId]
      if (chainSession != null) {
        if (targetKarakuri.id == chainSession.parentInstance.id) {
          player.sendMessage(Component.text("⚠️ 自分自身を子構造に設定することはできません！別のからくりをクリックしてください。", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        if (!canAttachChildContraption(chainSession.parentInstance)) {
          player.sendMessage(Component.text("⚠️ 親構造のからくりが動作中、または初期位置・初期姿勢にありません！", NamedTextColor.RED))
          player.sendMessage(Component.text("  ▶ からくりを停止させ、初期位置・初期姿勢に戻してから接続してください。", NamedTextColor.GRAY))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        if (targetKarakuri.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
            targetKarakuri.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
          player.sendMessage(Component.text("⚠️ このからくりは既に別の子構造として接続されています！", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return
        }

        // ワープ配置：子構造の中心軸が選択した鎖のブロック位置に重なるように配置
        val targetChainCenter = Location(
          chainSession.clickedLoc.world,
          chainSession.clickedLoc.blockX + 0.5,
          chainSession.clickedLoc.blockY + 0.5,
          chainSession.clickedLoc.blockZ + 0.5
        )
        warpChildToChainLocation(targetKarakuri, targetChainCenter)

        val motion = io.oyasai.karakuri.motion.ChainChildContraptionMotion(
          parentId = chainSession.parentInstance.id,
          chainAxis = chainSession.chainAxis,
          minChainLoc = chainSession.minChainLoc,
          maxChainLoc = chainSession.maxChainLoc,
          initialConnectLoc = chainSession.clickedLoc,
          speed = 2.0,
          linearMode = io.oyasai.karakuri.motion.LinearLoopMode.AUTO_PING_PONG,
          isRunning = false,
          followParentRotation = true,
          spinAxis = null,
          rpm = 16.0f,
          isClockwise = true,
          isSpinActive = false
        )
        targetKarakuri.setMotion(motion)
        selectingChainChildKarakuri.remove(player.uniqueId)

        val pLoc = targetKarakuri.displayEntity.location
        player.spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 35, 0.5, 0.5, 0.5, 0.1)
        player.playSound(pLoc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
        player.playSound(pLoc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.6f)
        player.sendMessage(Component.text("🔗 【鎖・直線子構造の接続が完了しました！】", NamedTextColor.GREEN, TextDecoration.BOLD))
        player.sendMessage(Component.text("  ▶ [${targetKarakuri.id}] の中心軸を [${chainSession.parentInstance.id}] の鎖に配置して接続しました！", NamedTextColor.AQUA))
        player.sendMessage(Component.text("  ▶ 鎖の直線に沿った移動と、自転回転を同時に設定できます！", NamedTextColor.YELLOW))
        io.oyasai.karakuri.gui.KarakuriChainChildGUI(targetKarakuri, motion).open(player)
        return
      }

      // (3) トロッコ合体モード中の場合
      val selectedCartId = selectingMinecarts[player.uniqueId]
      if (selectedCartId != null) {
        val cart = Bukkit.getEntity(selectedCartId) as? Minecart
        if (cart != null && cart.isValid) {
          manager.mountToMinecart(targetKarakuri, cart, followRotation = true)
          selectingMinecarts.remove(player.uniqueId)

          player.spawnParticle(Particle.HAPPY_VILLAGER, targetKarakuri.displayEntity.location, 30, 0.5, 0.5, 0.5, 0.1)
          player.playSound(targetKarakuri.displayEntity.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f)
          player.sendMessage(Component.text("🎉 [${targetKarakuri.id}] をトロッコに合体させました！", NamedTextColor.GREEN))
          player.sendMessage(Component.text("  ▶ トロッコを走らせるとからくりが連動して移動します！", NamedTextColor.YELLOW))
          player.sendMessage(Component.text("  ▶ レンチで右クリックすると設定盤(進行方向追従の切替など)が開きます。", NamedTextColor.AQUA))
          return
        } else {
          selectingMinecarts.remove(player.uniqueId)
          player.sendMessage(Component.text("❌ 対象のトロッコが見つかりませんでした。", NamedTextColor.RED))
          return
        }
      }

      // (4) Shift + 右クリック時:
      if (player.isSneaking) {
        // もし対象からくり自体が既に子構造なら、その設定GUIを開く
        val childMotion = targetKarakuri.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion
        if (childMotion != null) {
          io.oyasai.karakuri.gui.KarakuriChildGUI(targetKarakuri, childMotion).open(player)
          return
        }

        val chainMotion = targetKarakuri.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion
        if (chainMotion != null) {
          io.oyasai.karakuri.gui.KarakuriChainChildGUI(targetKarakuri, chainMotion).open(player)
          return
        }

        // 視線の先のブロックを精査（粘着ピストンまたは鎖なら子構造接続モードを優先！）
        val hit = manager.rayTraceDetailed(player.eyeLocation, player.location.direction, 5.0)
        val activeTarget = hit?.instance ?: targetKarakuri
        val hitLoc = hit?.hitBlockLocation
        val blockData = if (hitLoc != null) activeTarget.getBlockDataAt(hitLoc) else null

        // (ア) 粘着ピストン検知 -> 回転子構造接続モード開始
        if (blockData?.material == Material.STICKY_PISTON && hitLoc != null) {
          if (activeTarget.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
              activeTarget.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
            player.sendMessage(Component.text("⚠️ 子構造のからくりにさらに子構造を接続することはできません。", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          if (getChildContraptions(activeTarget).size >= 8) {
            player.sendMessage(Component.text("⚠️ この親構造には既に最大数（8個）の子構造が接続されています！", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          if (isPistonAlreadyOccupied(activeTarget, hitLoc)) {
            player.sendMessage(Component.text("⚠️ この粘着ピストンには既に別の子構造が接続されています！", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          if (!canAttachChildContraption(activeTarget)) {
            player.sendMessage(Component.text("⚠️ 親構造のからくりが動作中、または初期位置・初期姿勢にありません！", NamedTextColor.RED))
            player.sendMessage(Component.text("  ▶ からくりを停止させ、初期位置・初期姿勢に戻してから接続してください。", NamedTextColor.GRAY))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          clearAllSelectingSessions(player.uniqueId)

          val facing = (blockData as? org.bukkit.block.data.Directional)?.facing ?: org.bukkit.block.BlockFace.UP
          selectingChildKarakuri[player.uniqueId] = ParentChildSession(activeTarget, hitLoc.clone(), facing)

          val bCenter = Location(hitLoc.world, hitLoc.blockX + 0.5, hitLoc.blockY + 0.5, hitLoc.blockZ + 0.5)
          player.spawnParticle(Particle.PORTAL, bCenter, 30, 0.3, 0.3, 0.3, 0.1)
          player.playSound(bCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)
          player.playSound(bCenter, Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.2f)

          val axisName = io.oyasai.karakuri.motion.ChildContraptionMotion.getPistonAxisName(facing)
          player.sendMessage(Component.text("🔗 【回転・子構造接続モード開始】", NamedTextColor.GOLD, TextDecoration.BOLD))
          player.sendMessage(Component.text("  ▶ 接続したい第二のからくりを調律師のレンチで【右クリック】してください！", NamedTextColor.YELLOW))
          player.sendMessage(Component.text("  ▶ 粘着ピストン位置: (${hitLoc.blockX}, ${hitLoc.blockY}, ${hitLoc.blockZ}) / 向き: $facing", NamedTextColor.WHITE))
          player.sendMessage(Component.text("  ▶ 子構造の回転軸: $axisName （粘着ピストンの場所が回転の中心軸になります）", NamedTextColor.AQUA))
          player.sendMessage(Component.text("  ▶ 解除するには【Shift + 左クリック】してください", NamedTextColor.GRAY))
          player.sendActionBar(Component.text("接続したい別のからくりをレンチで右クリック (解除: Shift左クリック)", NamedTextColor.YELLOW))
          return
        }

        // (イ) 鉄の鎖・非錆止め銅の鎖検知 -> 鎖・直線子構造接続モード開始
        if (hitLoc != null && isSupportedChainMaterial(blockData?.material ?: Material.AIR)) {
          if (activeTarget.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
              activeTarget.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
            player.sendMessage(Component.text("⚠️ 子構造のからくりにさらに子構造を接続することはできません。", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          if (getChildContraptions(activeTarget).size >= 8) {
            player.sendMessage(Component.text("⚠️ この親構造には既に最大数（8個）の子構造が接続されています！", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          if (isChainAlreadyOccupied(activeTarget, hitLoc)) {
            player.sendMessage(Component.text("⚠️ この鎖には既に別の子構造が接続されています！", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          if (!canAttachChildContraption(activeTarget)) {
            player.sendMessage(Component.text("⚠️ 親構造のからくりが動作中、または初期位置・初期姿勢にありません！", NamedTextColor.RED))
            player.sendMessage(Component.text("  ▶ からくりを停止させ、初期位置・初期姿勢に戻してから接続してください。", NamedTextColor.GRAY))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          val scan = scanConnectedChains(activeTarget, hitLoc)
          if (scan == null) {
            player.sendMessage(Component.text("⚠️ 鎖ブロックの検出に失敗しました。", NamedTextColor.RED))
            return
          }

          // 鉄の鎖がエンティティ統合されている場合は子構造システムに使用不可
          val hasMergedIronChain = scan.allLocations.any { loc ->
            val data = activeTarget.getBlockDataAt(loc)
            val mName = data?.material?.name ?: ""
            (mName == "CHAIN" || mName == "IRON_CHAIN") && activeTarget.isBlockMerged(loc)
          }
          if (hasMergedIronChain) {
            player.sendMessage(Component.text("⚠️ 選択した鎖（または直線上の鎖）にエンティティ統合された鉄の鎖が含まれているため、子構造システムには使用できません。", NamedTextColor.RED))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            return
          }

          clearAllSelectingSessions(player.uniqueId)

          selectingChainChildKarakuri[player.uniqueId] = ChainChildSession(
            parentInstance = activeTarget,
            chainAxis = scan.axis,
            clickedLoc = scan.clickedLoc,
            minChainLoc = scan.minLoc,
            maxChainLoc = scan.maxLoc
          )

          val bCenter = Location(hitLoc.world, hitLoc.blockX + 0.5, hitLoc.blockY + 0.5, hitLoc.blockZ + 0.5)
          player.spawnParticle(Particle.PORTAL, bCenter, 30, 0.3, 0.3, 0.3, 0.1)
          player.playSound(bCenter, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)
          player.playSound(bCenter, Sound.BLOCK_CHAIN_PLACE, 1.0f, 1.2f)

          player.sendMessage(Component.text("⛓️ 【鎖・直線子構造接続モード開始】", NamedTextColor.GOLD, TextDecoration.BOLD))
          player.sendMessage(Component.text("  ▶ 接続したい第二のからくりを調律師のレンチで【右クリック】してください！", NamedTextColor.YELLOW))
          player.sendMessage(Component.text("  ▶ 選択された鎖の長さ: ${scan.totalChains} ブロック (軸: ${scan.axis} 軸)", NamedTextColor.WHITE))
          player.sendMessage(Component.text("  ▶ 解除するには【Shift + 左クリック】してください", NamedTextColor.GRAY))
          player.sendActionBar(Component.text("接続したい別のからくりをレンチで右クリック (解除: Shift左クリック)", NamedTextColor.YELLOW))
          return
        }

        // 粘着ピストンや鎖でない場合は、トロッコGUIまたは通常GUIを開く
        val cartMotion = targetKarakuri.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
        if (cartMotion != null) {
          io.oyasai.karakuri.gui.KarakuriMinecartGUI(targetKarakuri, cartMotion).open(player)
        } else {
          KarakuriGUI(targetKarakuri).open(player)
        }
        return
      }

      // (5) 通常右クリック時（レンチ所持）: 各種設定GUIを開く
      val childMotion = targetKarakuri.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion
      if (childMotion != null) {
        io.oyasai.karakuri.gui.KarakuriChildGUI(targetKarakuri, childMotion).open(player)
        return
      }

      val chainMotion = targetKarakuri.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion
      if (chainMotion != null) {
        io.oyasai.karakuri.gui.KarakuriChainChildGUI(targetKarakuri, chainMotion).open(player)
        return
      }

      val cartMotion = targetKarakuri.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
      if (cartMotion != null) {
        io.oyasai.karakuri.gui.KarakuriMinecartGUI(targetKarakuri, cartMotion).open(player)
      } else {
        KarakuriGUI(targetKarakuri).open(player)
      }
      return
    }

    if (KarakuriPistonRod.isRod(item) || KarakuriGlue.isGlue(item) || KarakuriToolbox.isToolbox(item)) {
      return
    }

    // 素手での作動は Shift + 右クリック のみ！
    if (player.isSneaking) {
      if (targetKarakuri.isImmovable) {
        player.sendActionBar(Component.text("🗿 このからくりは「不動」に設定されているため作動しません", NamedTextColor.RED))
        player.playSound(player.location, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.8f, 0.8f)
        return
      }
      triggerManual(player, targetKarakuri)
    }
  }



  @EventHandler(priority = EventPriority.HIGHEST)
  fun onVehicleEnter(event: VehicleEnterEvent) {
    val vehicle = event.vehicle
    if (manager.isKarakuriMinecart(vehicle)) {
      event.isCancelled = true
      if (event.entered is Player) {
        (event.entered as Player).sendActionBar(Component.text("🚫 からくりが乗っているトロッコには乗車できません", NamedTextColor.RED))
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  fun onSpawnerSpawn(event: org.bukkit.event.entity.SpawnerSpawnEvent) {
    if (event.spawner is org.bukkit.entity.minecart.SpawnerMinecart) {
      event.isCancelled = true
    }
  }

  /**
   * レンチ左クリックによる先頭車両指定・切り替え共通処理（トロッコ停止時のみ実行可能）
   */
  private fun handleSetLeadCart(player: Player, cart: Minecart): Boolean {
    val couplingManager = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager
    val train = couplingManager.getTrainForCart(cart) ?: return false
    if (train.carts.size <= 1) return false

    // トロッコが走行していないときのみ変更可能！
    val leadCart = train.carts.first()
    if (leadCart.velocity.length() > 0.05) {
      player.sendMessage(Component.text("⚠️ トロッコが走行中は先頭車両を変更できません。", NamedTextColor.RED))
      return true
    }

    val first = train.carts.first()
    val last = train.carts.last()
    if (cart == first) {
      player.sendMessage(Component.text("🚂 このトロッコは既に先頭車両です。", NamedTextColor.GREEN, TextDecoration.BOLD))
      return true
    }
    if (cart == last) {
      couplingManager.reverseTrain(train)
      player.sendMessage(Component.text("🔄 先頭車両を切り替えました！ (このトロッコが先頭になりました)", NamedTextColor.AQUA, TextDecoration.BOLD))
      return true
    }
    player.sendMessage(Component.text("⚠️ 先頭車両に指定できるのは編成の両端のトロッコのみです。", NamedTextColor.RED))
    return true
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  fun onVehicleDamage(event: VehicleDamageEvent) {
    val vehicle = event.vehicle
    if (manager.isKarakuriMinecart(vehicle)) {
      event.isCancelled = true
      return
    }
    // トロッコに対する攻撃処理
    if (vehicle is Minecart) {
      val couplingManager = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager
      val attacker = event.attacker as? Player
      if (attacker != null) {
        val handItem = attacker.inventory.itemInMainHand
        val isBareHand = handItem.isEmpty || handItem.type == Material.AIR
        val isPushActive = attacker.isSneaking && isBareHand && couplingManager.isPushModeEnabled(attacker)
        if (isPushActive) {
          event.isCancelled = true
          couplingManager.pushCartByPlayer(attacker, vehicle)
          return
        }

        val train = couplingManager.getTrainForCart(vehicle)
        if (train != null) {
          event.isCancelled = true
          if (KarakuriWrench.isWrench(handItem)) {
            // レンチなら先頭車両変更
            handleSetLeadCart(attacker, vehicle)
          }
          return
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  fun onVehicleDestroy(event: VehicleDestroyEvent) {
    val vehicle = event.vehicle
    if (manager.isKarakuriMinecart(vehicle)) {
      event.isCancelled = true
      return
    }
    // トロッコの破壊防止（手押し時、および連結トロッコのレンチ破壊）
    if (vehicle is Minecart) {
      val couplingManager = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager
      val attacker = event.attacker as? Player
      if (attacker != null) {
        val handItem = attacker.inventory.itemInMainHand
        val isBareHand = handItem.isEmpty || handItem.type == Material.AIR
        val isPushActive = attacker.isSneaking && isBareHand && couplingManager.isPushModeEnabled(attacker)
        if (isPushActive) {
          event.isCancelled = true
          return
        }
        if (KarakuriWrench.isWrench(handItem)) {
          if (couplingManager.getTrainForCart(vehicle) != null) {
            event.isCancelled = true
            return
          }
        }
      }
      couplingManager.unlinkCart(vehicle)
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  fun onEntityDamage(event: EntityDamageByEntityEvent) {
    val entity = event.entity
    val damager = event.damager as? Player ?: return
    val couplingManager = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager

    // ★ どのエンティティであっても、Shift + 左クリックで各種選択・連結モードを確実に全解除！
    if (damager.isSneaking) {
      if (couplingManager.selectingPlayers.containsKey(damager.uniqueId) ||
          selectingMinecartForInstance.containsKey(damager.uniqueId) ||
          selectingMinecarts.containsKey(damager.uniqueId) ||
          selectingChildKarakuri.containsKey(damager.uniqueId) ||
          selectingChainChildKarakuri.containsKey(damager.uniqueId)
      ) {
        event.isCancelled = true
        clearAllSelectingSessions(damager.uniqueId)
        damager.sendMessage(Component.text("🚫 各種接続・合体モードを解除しました。", NamedTextColor.YELLOW))
        return
      }
    }

    // ★ 連結鎖 (Interaction) に対する攻撃: レンチで左クリックした時のみ解除！素手・他アイテムでは破壊不可
    if (entity is Interaction && entity.scoreboardTags.contains(io.oyasai.karakuri.engine.MinecartCouplingManager.TAG_CHAIN_LINK)) {
      event.isCancelled = true
      val handItem = damager.inventory.itemInMainHand
      if (KarakuriWrench.isWrench(handItem)) {
        val linkTag = entity.scoreboardTags.firstOrNull { it.startsWith(io.oyasai.karakuri.engine.MinecartCouplingManager.TAG_LINK_ID_PREFIX) }
        val linkId = linkTag?.removePrefix(io.oyasai.karakuri.engine.MinecartCouplingManager.TAG_LINK_ID_PREFIX)
        if (linkId != null && couplingManager.unlink(linkId)) {
          damager.sendMessage(Component.text("⛓️ トロッコの連結を解除しました！", NamedTextColor.YELLOW, TextDecoration.BOLD))
        }
      } else {
        damager.sendActionBar(Component.text("🔧 レンチで左クリックすると連結を解除できます", NamedTextColor.GRAY))
      }
      return
    }

    // トロッコ対象判定 (直接トロッコをクリックした場合、またはからくり台車の場合、直上にトロッコがある場合)
    val targetCartForAction: Minecart? = when {
      entity is Minecart -> entity
      else -> {
        val k = manager.getByEntity(entity)
        (k?.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion)?.minecart
          ?: if (entity is Interaction || entity is Display) findCartAboveEntity(entity) else null
      }
    }

    val handItem = damager.inventory.itemInMainHand
    val isBareHand = handItem.isEmpty || handItem.type == Material.AIR
    val isPushActive = damager.isSneaking && isBareHand && couplingManager.isPushModeEnabled(damager)

    // ★ トロッコに対する左クリック
    if (targetCartForAction != null) {
      val isWrench = KarakuriWrench.isWrench(handItem)
      val train = couplingManager.getTrainForCart(targetCartForAction)

      // 手押しモード有効かつ素手+Shiftの場合: トロッコを視線方向へ手押し！
      if (isPushActive) {
        event.isCancelled = true
        couplingManager.pushCartByPlayer(damager, targetCartForAction)
        return
      }

      if (train != null && train.carts.size > 1) {
        event.isCancelled = true
        if (isWrench) {
          handleSetLeadCart(damager, targetCartForAction)
        }
        return
      } else if (entity !is Minecart) {
        // からくりレール等の直上にあるトロッコへの誤破壊防止
        event.isCancelled = true
        return
      }
    }

    // トロッコに対するShift左クリック（連結モードの解除のみ）
    if (entity is Minecart) {
      if (damager.isSneaking) {
        if (couplingManager.selectingPlayers.containsKey(damager.uniqueId)) {
          event.isCancelled = true
          couplingManager.selectingPlayers.remove(damager.uniqueId)
          damager.sendMessage(Component.text("🚫 トロッコ連結モードを解除しました。", NamedTextColor.YELLOW))
          return
        }
      }
    }

    // からくりトロッコへの直接攻撃: 破壊は防止
    if (manager.isKarakuriMinecart(entity)) {
      event.isCancelled = true
      if (isPushActive) {
        couplingManager.pushCartByPlayer(damager, entity as Minecart)
      }
      return
    }

    val targetKarakuri = manager.getByEntity(entity) ?: return

    event.isCancelled = true

    // もしトロッコに乗っているからくりで、手押しモード有効時ならトロッコを手押し
    val cartMotion = targetKarakuri.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
    if (cartMotion != null && isPushActive) {
      couplingManager.pushCartByPlayer(damager, cartMotion.minecart)
      return
    }

    val item = damager.inventory.itemInMainHand
    if (KarakuriWrench.isWrench(item)) {
      if (damager.isSneaking) {
        if (selectingMinecartForInstance.containsKey(damager.uniqueId) ||
            selectingMinecarts.containsKey(damager.uniqueId) ||
            selectingChildKarakuri.containsKey(damager.uniqueId) ||
            selectingChainChildKarakuri.containsKey(damager.uniqueId)) {
          clearAllSelectingSessions(damager.uniqueId)
          damager.sendMessage(Component.text("🚫 各種接続・合体モードを解除しました。", NamedTextColor.YELLOW))
          return
        }
      }

      // レンチで左クリック -> 中心軸を設定（トロッコ合体モード中であっても有効！）
      val hit = manager.rayTraceDetailed(damager.eyeLocation, damager.location.direction, 5.0)
      val pivotLoc = hit?.hitBlockLocation ?: entity.location
      targetKarakuri.setPivotToBlock(pivotLoc)
      val center = Location(pivotLoc.world, pivotLoc.blockX + 0.5, pivotLoc.blockY + 0.5, pivotLoc.blockZ + 0.5)
      damager.spawnParticle(Particle.END_ROD, center, 20, 0.2, 0.2, 0.2, 0.05)
      damager.playSound(center, Sound.BLOCK_ANVIL_USE, 0.8f, 1.6f)
      damager.sendMessage(Component.text("🎯 【中心軸(回転・トロッコ)を設定しました！】 (${pivotLoc.blockX}, ${pivotLoc.blockY}, ${pivotLoc.blockZ})", NamedTextColor.GOLD))
      return
    }

    if (KarakuriPistonRod.isRod(item)) {
      if (damager.isSneaking) {
        applyPistonRodLine(damager, targetKarakuri)
      }
      return
    }

    if (KarakuriGlue.isGlue(item) || KarakuriToolbox.isToolbox(item)) {
      return
    }
  }

  private fun applyPistonRodLine(player: Player, targetKarakuri: io.oyasai.karakuri.engine.KarakuriInstance) {
    val rodLine = KarakuriPistonRod.getLine(player.uniqueId)
    if (!rodLine.isComplete) {
      player.sendMessage(Component.text("先に始点(左クリック)と終点(右クリック)を設定してください。", NamedTextColor.RED))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
      return
    }

    // ★重要: もし対象からくりが「7ステッププログラム (ProgramMotion)」を実行中なら、
    // 単体直線運動で勝手に上書きせず、適用先のステップを選択する画面を開く！
    val curMotion = targetKarakuri.activeMotion
    if (curMotion is io.oyasai.karakuri.motion.ProgramMotion) {
      io.oyasai.karakuri.gui.KarakuriProgramSelectStepGUI(targetKarakuri).open(player)
      return
    }

    val startLoc = rodLine.startPoint!!.clone()
    val endLoc = rodLine.endPoint!!.clone()
    val sLoc = startLoc.clone()
    val eLoc = endLoc.clone()

    targetKarakuri.updateOrigin(sLoc)
    targetKarakuri.resetToBase(stopMotion = false)

    val duration = (rodLine.distance * 10).toInt().coerceIn(15, 120)
    val linear = IntegratedLinearMotion(
      startLocation = sLoc,
      endLocation = eLoc,
      durationTicks = duration,
      loopMode = io.oyasai.karakuri.motion.LinearLoopMode.AUTO_PING_PONG
    )
    targetKarakuri.setMotion(linear)

    val loc = startLoc.clone().add(0.5, 0.5, 0.5)
    player.spawnParticle(Particle.HAPPY_VILLAGER, loc, 30, 0.4, 0.4, 0.4, 0.1)
    player.playSound(loc, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
    player.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.8f)

    player.sendMessage(Component.text("✔ 【からくりを設定ライン (始点A) へ配置しました！】", NamedTextColor.GREEN))
    player.sendMessage(Component.text("  ▶ Shift+素手右クリックで直線運動を開始できます！ (移動量: ${rodLine.dx.toInt()}X, ${rodLine.dy.toInt()}Y, ${rodLine.dz.toInt()}Z / 計 ${String.format("%.1f", rodLine.distance)}m)", NamedTextColor.YELLOW))
  }

  private fun triggerManual(player: Player, targetKarakuri: io.oyasai.karakuri.engine.KarakuriInstance) {
    manager.triggerManual(player, targetKarakuri)
  }

  private fun showLineSummary(player: Player, rodLine: KarakuriPistonRod.RodLine) {
    player.sendMessage(Component.text("📐 【移動ライン確定！】 X: ${rodLine.dx.toInt()}, Y: ${rodLine.dy.toInt()}, Z: ${rodLine.dz.toInt()} (直線距離: ${String.format("%.1f", rodLine.distance)}m)", NamedTextColor.GREEN))
    player.sendMessage(Component.text("  ▶ 【Shift + 左クリック】でからくりに適用！（プログラム設定時はステップ選択画面が開きます）", NamedTextColor.YELLOW))

    val start = rodLine.startPoint?.clone()?.add(0.5, 0.5, 0.5) ?: return
    val end = rodLine.endPoint?.clone()?.add(0.5, 0.5, 0.5) ?: return
    val count = (rodLine.distance * 2).toInt().coerceIn(5, 50)
    for (i in 0..count) {
      val t = i.toDouble() / count
      val p = start.clone().add(
        (end.x - start.x) * t,
        (end.y - start.y) * t,
        (end.z - start.z) * t
      )
      player.spawnParticle(Particle.END_ROD, p, 1, 0.0, 0.0, 0.0, 0.0)
    }
  }

  /**
   * 親からくりが静止しており、子構造を接続可能かを判定する
   */
  private fun canAttachChildContraption(parent: io.oyasai.karakuri.engine.KarakuriInstance): Boolean {
    // 1. 親自身が子構造なら不可（子構造のネスト禁止）
    if (parent.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion) return false
    if (parent.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) return false

    // 2. 子構造の最大数チェック（最大8つまで）
    if (getChildContraptions(parent).size >= 8) {
      return false
    }

    // 3. 親がトロッコ乗車中の場合
    val cartMotion = parent.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
    if (cartMotion != null) {
      // トロッコが有効で、かつ停止していること（走行中の追加は不可）
      if (!cartMotion.minecart.isValid || cartMotion.isCartMoving) {
        return false
      }
      return true
    }

    // 4. 通常の親の場合: 動作中チェック
    when (val m = parent.activeMotion) {
      is io.oyasai.karakuri.motion.ContinuousSpinMotion -> {
        if (m.isRunning) return false
      }
      is io.oyasai.karakuri.motion.AngleRotationMotion -> return false
      is io.oyasai.karakuri.motion.DoorMotion -> {
        if (m.isOpened) return false
      }
      is io.oyasai.karakuri.motion.IntegratedLinearMotion -> {
        if (m.isRunning || m.isMoving) return false
      }
      is io.oyasai.karakuri.motion.ProgramMotion -> {
        if (m.isPlaying) return false
      }
      null -> {}
    }

    // 5. 通常の親の場合: 初期位置チェック (baseLocation == originLocation)
    if (parent.baseLocation.distanceSquared(parent.originLocation) > 0.001) {
      return false
    }

    // 6. 通常の親の場合: 初期姿勢チェック (currentRotation が単位四元数か)
    val q = parent.currentRotation
    val isIdentity = kotlin.math.abs(kotlin.math.abs(q.w) - 1.0f) < 0.01f &&
      kotlin.math.abs(q.x) < 0.01f &&
      kotlin.math.abs(q.y) < 0.01f &&
      kotlin.math.abs(q.z) < 0.01f
    if (!isIdentity) {
      return false
    }

    return true
  }

  /**
   * 親からくりに接続されている子構造の一覧を取得
   */
  private fun getChildContraptions(parent: io.oyasai.karakuri.engine.KarakuriInstance): List<io.oyasai.karakuri.engine.KarakuriInstance> {
    return manager.getAll().filter {
      (it.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion)?.parentId == parent.id ||
      (it.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion)?.parentId == parent.id
    }
  }

  /**
   * 指定の粘着ピストン位置に既に別の子構造が接続されているかを判定
   */
  private fun isPistonAlreadyOccupied(parent: io.oyasai.karakuri.engine.KarakuriInstance, pistonLoc: Location): Boolean {
    return manager.getAll().any {
      val cm = it.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion
      cm != null && cm.parentId == parent.id &&
        cm.pistonOriginLoc.blockX == pistonLoc.blockX &&
        cm.pistonOriginLoc.blockY == pistonLoc.blockY &&
        cm.pistonOriginLoc.blockZ == pistonLoc.blockZ
    }
  }

  /**
   * 指定の鎖位置に既に別の子構造が接続されているかを判定
   */
  private fun isChainAlreadyOccupied(parent: io.oyasai.karakuri.engine.KarakuriInstance, checkLoc: Location): Boolean {
    return manager.getAll().any {
      val ccm = it.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion
      if (ccm != null && ccm.parentId == parent.id) {
        val min = ccm.minChainLoc
        val max = ccm.maxChainLoc
        val minX = minOf(min.blockX, max.blockX)
        val maxX = maxOf(min.blockX, max.blockX)
        val minY = minOf(min.blockY, max.blockY)
        val maxY = maxOf(min.blockY, max.blockY)
        val minZ = minOf(min.blockZ, max.blockZ)
        val maxZ = maxOf(min.blockZ, max.blockZ)
        checkLoc.blockX in minX..maxX && checkLoc.blockY in minY..maxY && checkLoc.blockZ in minZ..maxZ
      } else false
    }
  }

  /**
   * 親からくりに既に子構造が接続されているかを判定する
   */
  private fun hasChildContraption(parent: io.oyasai.karakuri.engine.KarakuriInstance): Boolean {
    return getChildContraptions(parent).isNotEmpty()
  }

  /**
   * 鉄の鎖、または錆止めされていない銅系の鎖かを判定
   */
  private fun isSupportedChainMaterial(material: Material): Boolean {
    val name = material.name
    if (name == "CHAIN" || name == "IRON_CHAIN") return true
    if (name.endsWith("_CHAIN") && !name.startsWith("WAXED_")) return true
    return false
  }

  /**
   * 同系統の鎖マテリアルかを判定（鉄の鎖、銅の鎖、風化銅の鎖、錆び銅の鎖、酸化銅の鎖を厳密に区別）
   */
  private fun isSameChainType(mat1: Material, mat2: Material): Boolean {
    if (mat1 == mat2) return true
    val n1 = mat1.name
    val n2 = mat2.name
    if ((n1 == "CHAIN" || n1 == "IRON_CHAIN") && (n2 == "CHAIN" || n2 == "IRON_CHAIN")) return true
    return false
  }

  data class ChainScanResult(
    val axis: org.bukkit.Axis,
    val clickedLoc: Location,
    val minLoc: Location,
    val maxLoc: Location,
    val totalChains: Int,
    val allLocations: List<Location>,
  )

  /**
   * 親からくり内の同一軸で連続する鎖ブロック群をスキャンし、直線の端から端を検出
   */
  private fun scanConnectedChains(parent: io.oyasai.karakuri.engine.KarakuriInstance, clickedLoc: Location): ChainScanResult? {
    val clickedData = parent.getBlockDataAt(clickedLoc) ?: return null
    if (!isSupportedChainMaterial(clickedData.material)) return null
    val axis = (clickedData as? org.bukkit.block.data.Orientable)?.axis ?: org.bukkit.Axis.Y
    val clickedMaterial = clickedData.material

    val stepX = if (axis == org.bukkit.Axis.X) 1 else 0
    val stepY = if (axis == org.bukkit.Axis.Y) 1 else 0
    val stepZ = if (axis == org.bukkit.Axis.Z) 1 else 0

    val connected = mutableListOf<Location>()
    connected.add(clickedLoc)

    // 正方向をスキャン (最大256ブロックのリミット)
    var step = 1
    while (step <= 256) {
      val checkLoc = Location(clickedLoc.world, clickedLoc.blockX + (stepX * step).toDouble(), clickedLoc.blockY + (stepY * step).toDouble(), clickedLoc.blockZ + (stepZ * step).toDouble())
      val data = parent.getBlockDataAt(checkLoc) ?: break
      if (!isSupportedChainMaterial(data.material) || !isSameChainType(data.material, clickedMaterial)) break
      val curAxis = (data as? org.bukkit.block.data.Orientable)?.axis ?: org.bukkit.Axis.Y
      if (curAxis != axis) break
      connected.add(checkLoc)
      step++
    }

    // 負方向をスキャン (最大256ブロックのリミット)
    step = 1
    while (step <= 256) {
      val checkLoc = Location(clickedLoc.world, clickedLoc.blockX - (stepX * step).toDouble(), clickedLoc.blockY - (stepY * step).toDouble(), clickedLoc.blockZ - (stepZ * step).toDouble())
      val data = parent.getBlockDataAt(checkLoc) ?: break
      if (!isSupportedChainMaterial(data.material) || !isSameChainType(data.material, clickedMaterial)) break
      val curAxis = (data as? org.bukkit.block.data.Orientable)?.axis ?: org.bukkit.Axis.Y
      if (curAxis != axis) break
      connected.add(checkLoc)
      step++
    }

    // 軸に応じて昇順ソート
    when (axis) {
      org.bukkit.Axis.X -> connected.sortBy { it.blockX }
      org.bukkit.Axis.Y -> connected.sortBy { it.blockY }
      org.bukkit.Axis.Z -> connected.sortBy { it.blockZ }
    }

    val minLoc = connected.first()
    val maxLoc = connected.last()

    return ChainScanResult(
      axis = axis,
      clickedLoc = clickedLoc,
      minLoc = minLoc,
      maxLoc = maxLoc,
      totalChains = connected.size,
      allLocations = connected
    )
  }

  /**
   * 子からくりの回転中心軸 (worldPivotLocation) が、選択した鎖のブロック位置に重なるようにワープ配置する
   */
  private fun warpChildToChainLocation(
    child: io.oyasai.karakuri.engine.KarakuriInstance,
    targetChainCenter: Location,
  ) {
    val curPivot = child.worldPivotLocation
    val deltaX = targetChainCenter.x - curPivot.x
    val deltaY = targetChainCenter.y - curPivot.y
    val deltaZ = targetChainCenter.z - curPivot.z

    // 原点更新
    child.updateOrigin(child.originLocation.clone().add(deltaX, deltaY, deltaZ))
    child.worldPivotLocation = targetChainCenter.clone()

    // constituentBlocks の座標も更新
    val updatedBlocks = mutableMapOf<Location, org.bukkit.block.data.BlockData>()
    for ((loc, data) in child.constituentBlocks) {
      updatedBlocks[loc.clone().add(deltaX, deltaY, deltaZ)] = data
    }
    child.constituentBlocks.clear()
    child.constituentBlocks.putAll(updatedBlocks)

    // constituentSkullProfiles も更新
    val updatedSkulls = mutableMapOf<Location, com.destroystokyo.paper.profile.PlayerProfile>()
    for ((loc, prof) in child.constituentSkullProfiles) {
      updatedSkulls[loc.clone().add(deltaX, deltaY, deltaZ)] = prof
    }
    child.constituentSkullProfiles.clear()
    child.constituentSkullProfiles.putAll(updatedSkulls)

    // ルートエンティティのテレポート
    val rootOffset = child.getBaseEntityOffset(child.displayEntity, child.originalBlockData)
    val rootTarget = child.originLocation.clone().add(rootOffset.x.toDouble(), rootOffset.y.toDouble(), rootOffset.z.toDouble())
    child.displayEntity.teleportDuration = 0
    child.displayEntity.teleport(rootTarget)
    child.interactionEntity?.let { inter ->
      if (inter.isValid) {
        inter.teleport(rootTarget)
      }
    }

    // 追加パーツのテレポート
    for (part in child.additionalParts) {
      if (!part.displayEntity.isValid) continue
      val partOffset = child.getBaseEntityOffset(part.displayEntity, part.originalBlockData)
      val partTarget = part.originLocation.clone().add(partOffset.x.toDouble(), partOffset.y.toDouble(), partOffset.z.toDouble())
      part.displayEntity.teleportDuration = 0
      part.displayEntity.teleport(partTarget)
      part.interactionEntity?.let { pInter ->
        if (pInter.isValid) {
          pInter.teleport(partTarget)
        }
      }
    }

    child.setPivotToBlock(targetChainCenter)

    // ワープ後の正確な原点・中心軸・構成ブロック座標をDBに即時同期・永続化！
    manager.syncInstanceGeometry(child)
  }

  /**
   * 子構造のからくりを、親の粘着ピストンの前方にスマートにワープ配置する。
   *
   * - 子構造の元々の回転中心軸 (worldPivotLocation) を基準とし、
   *   ピストンの向きの軸 (facing) の直線上で、ピストン側 (-facing) の最も外側にあるブロックを
   *   粘着ピストンの前方 1 ブロックに配置する。
   * - これにより、中心軸がブロックに挟まれていても、粘着ピストンと子構造のブロックが絶対に重ならない！
   * - また、ピストンの軸と直交する2軸については、子構造の回転中心軸がピストンの中心軸と完全に一直線に揃う！
   */
  private fun warpChildToStickyPistonFront(
    child: io.oyasai.karakuri.engine.KarakuriInstance,
    pistonLoc: Location,
    facing: org.bukkit.block.BlockFace,
  ) {
    // 1. 子構造の元々の回転中心軸のブロック座標
    val pivotBlockX = child.worldPivotLocation.blockX
    val pivotBlockY = child.worldPivotLocation.blockY
    val pivotBlockZ = child.worldPivotLocation.blockZ

    // 2. 子構造を構成する全ブロック座標の取得
    val childBlocks = if (child.constituentBlocks.isNotEmpty()) {
      child.constituentBlocks.keys.toList()
    } else {
      val list = mutableListOf(child.originLocation)
      for (part in child.additionalParts) {
        if (part.displayEntity.isValid) list.add(part.originLocation)
      }
      list
    }

    // 3. ピストンの向きのベクトル
    val modX = facing.modX
    val modY = facing.modY
    val modZ = facing.modZ

    // 4. 回転中心軸を起点に、ピストンの向きの直線上で「ピストン側 (-facing)」の最も外側にあるブロックのオフセットを算出
    // (例: ピストンが EAST(+X) の場合、中心軸より西側 (-X) に 1 マスあれば minProj = -1)
    var minProj = 0
    for (b in childBlocks) {
      // ユーザー要件: 「回転の中心軸を起点に、粘着ピストンの向きの軸の直線上に存在している」ブロックのみを対象にする
      val isOnAxisLine = when (facing) {
        org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST -> (b.blockY == pivotBlockY && b.blockZ == pivotBlockZ)
        org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN -> (b.blockX == pivotBlockX && b.blockZ == pivotBlockZ)
        org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.NORTH -> (b.blockX == pivotBlockX && b.blockY == pivotBlockY)
        else -> false
      }
      if (!isOnAxisLine) continue

      val dx = b.blockX - pivotBlockX
      val dy = b.blockY - pivotBlockY
      val dz = b.blockZ - pivotBlockZ
      val proj = dx * modX + dy * modY + dz * modZ
      if (proj < minProj) {
        minProj = proj
      }
    }

    // 5. 粘着ピストンの前方 1 ブロックの位置
    val frontX = pistonLoc.blockX + modX
    val frontY = pistonLoc.blockY + modY
    val frontZ = pistonLoc.blockZ + modZ

    // 6. 子構造の中心軸 (Pivot) が来るべき目標ブロック位置
    // 一番外側のブロック (Pivot + minProj * facing) が front に来るため:
    // targetPivot = front - minProj * facing
    val targetPivotX = frontX - minProj * modX
    val targetPivotY = frontY - minProj * modY
    val targetPivotZ = frontZ - minProj * modZ

    // 7. ワープ移動量 (delta)
    val deltaX = targetPivotX - pivotBlockX
    val deltaY = targetPivotY - pivotBlockY
    val deltaZ = targetPivotZ - pivotBlockZ

    val world = child.originLocation.world
    val newPivotLoc = Location(world, targetPivotX.toDouble() + 0.5, targetPivotY.toDouble() + 0.5, targetPivotZ.toDouble() + 0.5)

    if (deltaX != 0 || deltaY != 0 || deltaZ != 0) {
      val shiftVec = Location(world, deltaX.toDouble(), deltaY.toDouble(), deltaZ.toDouble())

      // (A) constituentBlocks のキーをシフト更新
      if (child.constituentBlocks.isNotEmpty()) {
        val updatedMap = mutableMapOf<Location, org.bukkit.block.data.BlockData>()
        for ((oldLoc, data) in child.constituentBlocks) {
          updatedMap[oldLoc.clone().add(shiftVec)] = data
        }
        child.constituentBlocks.clear()
        child.constituentBlocks.putAll(updatedMap)
      }

      // constituentSkullProfiles もシフト更新
      if (child.constituentSkullProfiles.isNotEmpty()) {
        val updatedSkullMap = mutableMapOf<Location, com.destroystokyo.paper.profile.PlayerProfile>()
        for ((oldLoc, profile) in child.constituentSkullProfiles) {
          updatedSkullMap[oldLoc.clone().add(shiftVec)] = profile
        }
        child.constituentSkullProfiles.clear()
        child.constituentSkullProfiles.putAll(updatedSkullMap)
      }

      // (B) 原点と各パーツの位置を更新
      val newOrigin = child.originLocation.clone().add(shiftVec)
      child.updateOrigin(newOrigin)

      // (C) 中心軸を目標位置に設定
      child.setPivotToBlock(newPivotLoc)

      // (D) エンティティを即座に新位置へテレポート
      child.resetToBase(stopMotion = true)
    } else {
      child.setPivotToBlock(newPivotLoc)
      child.resetToBase(stopMotion = true)
    }

    // ワープ後の正確な原点・中心軸・構成ブロック座標をDBに即時同期・永続化！
    manager.syncInstanceGeometry(child)
  }

  @EventHandler
  fun onInventoryClick(event: InventoryClickEvent) {
    val title = event.view.title()
    val rawTitle = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
    val player = event.whoClicked as? Player ?: return
    val slot = event.rawSlot

    // 工具箱GUI
    if (rawTitle.contains(KarakuriToolboxGUI.TITLE)) {
      if (slot < 27) {
        event.isCancelled = true
        KarakuriToolboxGUI.handleClick(player, slot, event.currentItem, event.cursor)
      } else {
        // 下半分（プレイヤー自身のインベントリ）:
        if (event.isShiftClick) {
          // Shift+クリックで即座にゴミ箱消去！
          val clickedItem = event.currentItem
          if (clickedItem != null && !clickedItem.type.isAir) {
            event.isCancelled = true
            event.currentItem = null
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 0.8f, 1.2f)
            player.sendActionBar(Component.text("🗑️ アイテムを破棄しました", NamedTextColor.RED))
          }
        }
        // 通常クリックは event.isCancelled = false のままアイテムを持ち上げ可能！
      }
      return
    }

    // 便利ツール盤GUI (工具箱左クリック)
    if (rawTitle.contains(KarakuriCommandGUI.TITLE)) {
      event.isCancelled = true
      if (slot in 0 until 27) {
        KarakuriCommandGUI.handleClick(player, slot)
      }
      return
    }

    // リモコン専用収納GUI (工具箱Shift右クリック)
    if (rawTitle.contains(io.oyasai.karakuri.gui.KarakuriRemoteStorageGUI.TITLE)) {
      if (slot < 27) {
        val cursor = event.cursor
        if (cursor != null && !cursor.type.isAir) {
          if (!io.oyasai.karakuri.gui.KarakuriRemoteStorageGUI.handleItemMove(player, cursor)) {
            event.isCancelled = true
            return
          }
        }
      } else {
        if (event.isShiftClick) {
          val current = event.currentItem
          if (current != null && !current.type.isAir) {
            if (!io.oyasai.karakuri.gui.KarakuriRemoteStorageGUI.handleItemMove(player, current)) {
              event.isCancelled = true
              return
            }
          }
        }
      }
      return
    }

    val idMatch = Regex("\\[(.*?)\\]").find(rawTitle)
    val id = idMatch?.groupValues?.get(1) ?: return
    val instance = manager.get(id) ?: return

    when {
      rawTitle.contains(KarakuriStepEditGUI.TITLE_PREFIX) -> {
        event.isCancelled = true
        val stepMatch = Regex("Step (\\d+)").find(rawTitle)
        val stepNumber = stepMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        KarakuriStepEditGUI(instance, stepNumber - 1).handleClick(player, slot, event.isRightClick, event.isShiftClick)
      }
      rawTitle.contains(KarakuriGUI.GUI_TITLE_PREFIX) -> {
        event.isCancelled = true
        KarakuriGUI(instance).handleClick(player, slot, event.isRightClick)
      }
      rawTitle.contains(KarakuriLinearGUI.TITLE_PREFIX) -> {
        event.isCancelled = true
        KarakuriLinearGUI(instance).handleClick(player, slot, event.isRightClick, event.isShiftClick)
      }
      rawTitle.contains(io.oyasai.karakuri.gui.KarakuriProgramSelectStepGUI.TITLE_PREFIX) -> {
        event.isCancelled = true
        io.oyasai.karakuri.gui.KarakuriProgramSelectStepGUI(instance).handleClick(player, slot)
      }
      rawTitle.contains(KarakuriProgramGUI.TITLE_PREFIX) -> {
        event.isCancelled = true
        KarakuriProgramGUI(instance).handleClick(player, slot, event.isShiftClick)
      }
      rawTitle.contains(KarakuriMinecartGUI.TITLE_PREFIX) -> {
        event.isCancelled = true
        val cartMotion = instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
        if (cartMotion != null) {
          KarakuriMinecartGUI(instance, cartMotion).handleClick(player, slot)
        }
      }
      rawTitle.contains(io.oyasai.karakuri.gui.KarakuriChainChildGUI.GUI_TITLE_PREFIX) -> {
        event.isCancelled = true
        val chainMotion = instance.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion
        if (chainMotion != null) {
          io.oyasai.karakuri.gui.KarakuriChainChildGUI(instance, chainMotion).handleClick(slot, player, event.isRightClick)
        }
      }
      rawTitle.contains(io.oyasai.karakuri.gui.KarakuriChildGUI.GUI_TITLE_PREFIX) -> {
        event.isCancelled = true
        val childMotion = instance.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion
        if (childMotion != null) {
          io.oyasai.karakuri.gui.KarakuriChildGUI(instance, childMotion).handleClick(slot, player)
        }
      }
    }
  }

  // ==========================================
  // チャンクロード / アンロード / エンティティロード連動
  // ==========================================
  @EventHandler
  fun onChunkLoad(event: ChunkLoadEvent) {
    manager.handleChunkLoad(event.chunk)
  }

  @EventHandler
  fun onChunkUnload(event: ChunkUnloadEvent) {
    manager.handleChunkUnload(event.chunk)
  }

  @EventHandler
  fun onEntitiesLoad(event: EntitiesLoadEvent) {
    manager.handleEntitiesLoad(event.entities)
  }

  // ==========================================
  // からくり座席連動（降車・切断・死亡）
  // ==========================================
  @EventHandler(priority = EventPriority.HIGHEST)
  fun onEntityDismount(event: org.bukkit.event.entity.EntityDismountEvent) {
    val passenger = event.entity
    if (passenger is Player) {
      val seatManager = io.oyasai.karakuri.KarakuriPlugin.instance.seatManager
      if (seatManager.isTransitioning(passenger)) return
      if (seatManager.isSeated(passenger)) {
        seatManager.dismount(passenger)
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  fun onPlayerToggleSneak(event: org.bukkit.event.player.PlayerToggleSneakEvent) {
    if (event.isSneaking) {
      val player = event.player
      val seatManager = io.oyasai.karakuri.KarakuriPlugin.instance.seatManager
      if (seatManager.isTransitioning(player)) return
      if (seatManager.isSeated(player)) {
        seatManager.dismount(player)
      }
    }
  }

  @EventHandler
  fun onPlayerQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
    io.oyasai.karakuri.KarakuriPlugin.instance.seatManager.dismount(event.player)
  }

  @EventHandler
  fun onPlayerDeath(event: org.bukkit.event.entity.PlayerDeathEvent) {
    io.oyasai.karakuri.KarakuriPlugin.instance.seatManager.dismount(event.entity)
  }

  /**
   * からくりと紐付け登録されたワールド側のオブザーバーが、
   * 周囲のブロック設置・破壊・状態変化などのバニラブロック物理更新に自発的に反応しないように抑制
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  fun onBlockPhysics(event: org.bukkit.event.block.BlockPhysicsEvent) {
    val block = event.block
    if (block.type != Material.OBSERVER) return
    val world = block.world
    if (io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager.isLinkedObserver(world.name, block.x, block.y, block.z)) {
      event.isCancelled = true
    }
  }

  /**
   * からくり接触以外によるバニラオブザーバーのレッドストーン信号出力を 0 に抑制
   */
  @EventHandler(priority = EventPriority.HIGHEST)
  fun onBlockRedstone(event: org.bukkit.event.block.BlockRedstoneEvent) {
    val block = event.block
    if (block.type != Material.OBSERVER) return
    val world = block.world
    val rlm = io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager
    if (rlm.isLinkedObserver(world.name, block.x, block.y, block.z)) {
      if (!rlm.isObserverColliding(world.name, block.x, block.y, block.z)) {
        event.newCurrent = 0
      }
    }
  }

  @EventHandler
  fun onInventoryClose(event: org.bukkit.event.inventory.InventoryCloseEvent) {
    val title = event.view.title()
    val rawTitle = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title)
    val player = event.player as? Player ?: return
    if (rawTitle.contains(io.oyasai.karakuri.gui.KarakuriRemoteStorageGUI.TITLE)) {
      io.oyasai.karakuri.gui.KarakuriRemoteStorageGUI.save(player, event.inventory)
    }
  }

  /**
   * RS連動ブロックおよびワイヤレス機器の通常破壊を保護
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  fun onBlockBreak(event: org.bukkit.event.block.BlockBreakEvent) {
    val block = event.block
    val player = event.player

    // 1. ワイヤレスRS機器（送信機 / 受信機）の保護
    val wirelessManager = io.oyasai.karakuri.KarakuriPlugin.instance.wirelessRedstoneManager
    val wirelessNode = wirelessManager.getNodeAt(block)
    if (wirelessNode != null) {
      event.isCancelled = true
      val typeName = if (wirelessNode.type == io.oyasai.karakuri.engine.WirelessNodeType.TRANSMITTER) "ワイヤレス送信機" else "ワイヤレス受信機"
      player.sendMessage(Component.text("🔒 このブロックは【${typeName}】として登録されているため破壊できません！", NamedTextColor.RED))
      player.sendMessage(Component.text("  ▶ 調律師のレンチを持って【左クリック】すると登録解除できます。", NamedTextColor.YELLOW))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
      return
    }

    // 2. からくりのRS連動ブロック（ランプ、銅の電球、オブザーバー）の保護
    for (inst in manager.getAll()) {
      val link = inst.redstoneLinks.find {
        it.blockLocation.world == block.world &&
        it.blockLocation.blockX == block.x &&
        it.blockLocation.blockY == block.y &&
        it.blockLocation.blockZ == block.z
      }
      if (link != null) {
        event.isCancelled = true
        val typeName = io.oyasai.karakuri.engine.RedstoneLinkManager.getTypeName(link.type)
        player.sendMessage(Component.text("🔒 このブロックはからくり [${inst.id}] の【${typeName}】として登録されているため破壊できません！", NamedTextColor.RED))
        player.sendMessage(Component.text("  ▶ 調律師のレンチを持って【左クリック】すると登録解除できます。", NamedTextColor.YELLOW))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
        return
      }
    }
  }
}
