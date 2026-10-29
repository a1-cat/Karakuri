package io.oyasai.karakuri.command

import io.oyasai.karakuri.engine.GlueManager
import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.engine.KarakuriManager
import io.oyasai.karakuri.gui.KarakuriToolboxGUI
import io.oyasai.karakuri.motion.AngleRotationMotion
import io.oyasai.karakuri.motion.Axis
import io.oyasai.karakuri.motion.ContinuousSpinMotion
import io.oyasai.karakuri.motion.IntegratedLinearMotion
import io.oyasai.karakuri.tool.KarakuriGlue
import io.oyasai.karakuri.tool.KarakuriPistonRod
import io.oyasai.karakuri.tool.KarakuriToolbox
import io.oyasai.karakuri.tool.KarakuriWrench
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KarakuriCommand(
  private val plugin: Plugin,
  private val manager: KarakuriManager,
  private val glueManager: GlueManager,
) : CommandExecutor, TabCompleter {

  private val selectedInstance = ConcurrentHashMap<UUID, String>()

  override fun onCommand(
    sender: CommandSender,
    command: Command,
    label: String,
    args: Array<out String>,
  ): Boolean {
    if (args.isEmpty()) {
      sendHelp(sender)
      return true
    }

    if (args[0].equals("cleanup", ignoreCase = true)) {
      handleCleanup(sender)
      return true
    }

    if (sender !is Player) {
      sender.sendMessage(Component.text("このコマンドはプレイヤーのみ実行可能です。", NamedTextColor.RED))
      return true
    }

    when (args[0].lowercase()) {
      "tools" -> handleTools(sender)
      "toolbox", "box" -> handleToolbox(sender)
      "wrench", "tool" -> handleWrench(sender)
      "rod", "piston" -> handleRod(sender)
      "glue" -> handleGlue(sender)
      "spawn" -> handleSpawn(sender, args)
      "select" -> handleSelect(sender, args)
      "move" -> handleMove(sender, args)
      "pingpong" -> handlePingPong(sender, args)
      "rotate" -> handleRotate(sender, args)
      "spin" -> handleSpin(sender, args)
      "pivot" -> handlePivot(sender, args)
      "stop" -> handleStop(sender, args)
      "optimize", "compress" -> handleOptimize(sender, args, true)
      "unoptimize", "decompress" -> handleOptimize(sender, args, false)
      "nonfull" -> handleNonFull(sender, args)
      "testcomp", "comp" -> handleTestComp(sender, args)
      "revert", "undo" -> handleRevert(sender, args)
      "escape", "esc", "unstuck" -> handleEscape(sender)
      "ghost", "gh", "g" -> handleGhost(sender)
      "seatcam", "cam", "view", "seat" -> handleSeatCam(sender)
      "push", "cp", "cartpush" -> handleCartPush(sender)
      "remove" -> handleRemove(sender, args)
      "clear" -> handleClear(sender)
      "cleanup" -> handleCleanup(sender)
      "list" -> handleList(sender)
      else -> sendHelp(sender)
    }

    return true
  }

  private fun handleCleanup(sender: CommandSender) {
    val count = manager.cleanOldLeftoverEntities()
    sender.sendMessage(Component.text("🧹 からくりの残骸・重複・孤立DisplayEntityを $count 体クリーンアップしました！", NamedTextColor.GREEN))
  }

  private fun handleTools(player: Player) {
    KarakuriToolboxGUI.open(player)
  }

  private fun handleToolbox(player: Player) {
    val box = KarakuriToolbox.create()
    player.inventory.addItem(box)
    player.sendMessage(Component.text("✦ 『調律師の工具箱』を入手しました！", NamedTextColor.GOLD))
    player.sendMessage(Component.text("  ▶ 右クリックで工具箱GUIを開けます", NamedTextColor.YELLOW))
    player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f)
  }

  private fun handleWrench(player: Player) {
    val wrench = KarakuriWrench.create()
    player.inventory.addItem(wrench)
    player.sendMessage(Component.text("✦ 『調律師のレンチ』を入手しました！", NamedTextColor.GOLD))
    player.sendMessage(Component.text("  ▶ ブロックを右クリック: からくりに変換（接着建築物は丸ごと変換）", NamedTextColor.YELLOW))
    player.sendMessage(Component.text("  ▶ からくりを左クリック: そのブロックを中心軸(回転・トロッコ)に設定", NamedTextColor.GREEN))
    player.sendMessage(Component.text("  ▶ トロッコにShift右クリック: からくり合体モードの開始/解除", NamedTextColor.LIGHT_PURPLE))
    player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f)
  }

  private fun handleRod(player: Player) {
    val rod = KarakuriPistonRod.create()
    player.inventory.addItem(rod)
    player.sendMessage(Component.text("✦ 『調律師のピストンロッド』を入手しました！", NamedTextColor.AQUA))
    player.sendMessage(Component.text("  ▶ 左クリックで始点A、右クリックで終点Bを設定", NamedTextColor.YELLOW))
    player.sendMessage(Component.text("  ▶ Shift+左クリックでからくりにライン適用！", NamedTextColor.AQUA))
    player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f)
  }

  private fun handleGlue(player: Player) {
    val glue = KarakuriGlue.create()
    player.inventory.addItem(glue)
    player.sendMessage(Component.text("✦ 『調律師の接着剤』を入手しました！", NamedTextColor.GREEN))
    player.sendMessage(Component.text("  ▶ 左クリックで始点、右クリックで終点を選択", NamedTextColor.YELLOW))
    player.sendMessage(Component.text("  ▶ Shift+右クリックで接着範囲を作成！", NamedTextColor.AQUA))
    player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f)
  }

  private fun getTargetInstance(player: Player, specifiedId: String? = null): KarakuriInstance? {
    if (specifiedId != null) {
      return manager.get(specifiedId)
    }
    val rayHit = manager.rayTrace(player.eyeLocation, player.location.direction, 5.0)
    if (rayHit != null) {
      return rayHit
    }
    val lastId = selectedInstance[player.uniqueId]
    if (lastId != null) {
      val found = manager.get(lastId)
      if (found != null && found.isValid()) {
        return found
      }
    }
    return manager.getAll().lastOrNull { it.isValid() }
  }

  private fun handleSpawn(player: Player, args: Array<out String>) {
    val materialName = if (args.size > 1) args[1].uppercase() else "DIAMOND_BLOCK"
    val material = Material.matchMaterial(materialName) ?: Material.DIAMOND_BLOCK

    val targetBlock = player.getTargetBlockExact(5)
    val rawLoc = targetBlock?.location?.add(0.0, 1.0, 0.0) ?: player.location.clone().add(player.location.direction.multiply(2.0))

    val profile = if (material == Material.PLAYER_HEAD || material == Material.PLAYER_WALL_HEAD) {
      if (args.size > 2) {
        val targetPlayer = org.bukkit.Bukkit.getOfflinePlayer(args[2])
        targetPlayer.playerProfile
      } else {
        player.playerProfile
      }
    } else {
      null
    }

    val blockData = if (material.isBlock) material.createBlockData() else Material.STONE.createBlockData()
    val instance = manager.spawn(rawLoc, blockData, null, profile)
    selectedInstance[player.uniqueId] = instance.id

    player.sendMessage(
      Component.text("✦ からくり [${instance.id}] (${material.name}) を召喚しました！", NamedTextColor.GREEN)
    )
    player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
  }

  private fun handleSelect(player: Player, args: Array<out String>) {
    if (args.size < 2) {
      val hit = manager.rayTrace(player.eyeLocation, player.location.direction, 5.0)
      if (hit != null) {
        selectedInstance[player.uniqueId] = hit.id
        player.sendMessage(Component.text("✔ からくり [${hit.id}] を選択しました。", NamedTextColor.GREEN))
        return
      }
      player.sendMessage(Component.text("使用方法: /kk select <ID> (またはからくりを視界に入れて実行)", NamedTextColor.YELLOW))
      return
    }

    val id = args[1]
    val inst = manager.get(id)
    if (inst == null) {
      player.sendMessage(Component.text("ID [${id}] のからくりは見つかりませんでした。", NamedTextColor.RED))
      return
    }
    selectedInstance[player.uniqueId] = inst.id
    player.sendMessage(Component.text("✔ からくり [${inst.id}] を選択しました。", NamedTextColor.GREEN))
  }

  private fun handleMove(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。視線を合わせるか /kk select してください。", NamedTextColor.RED))
      return
    }

    val dx = args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    val dy = args.getOrNull(2)?.toDoubleOrNull() ?: 3.0
    val dz = args.getOrNull(3)?.toDoubleOrNull() ?: 0.0
    val duration = args.getOrNull(4)?.toIntOrNull() ?: 40

    val startLoc = inst.originLocation.clone()
    val endLoc = startLoc.clone().add(dx, dy, dz)

    val motion = IntegratedLinearMotion(
      startLocation = startLoc,
      endLocation = endLoc,
      durationTicks = duration,
      isAutoPingPong = false
    )
    inst.setMotion(motion)
    player.sendMessage(
      Component.text("✔ [${inst.id}] 直線移動を開始: Δ(${dx}, ${dy}, ${dz}) / ${duration}ticks", NamedTextColor.AQUA)
    )
  }

  private fun handlePingPong(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }

    val dx = args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    val dy = args.getOrNull(2)?.toDoubleOrNull() ?: 3.0
    val dz = args.getOrNull(3)?.toDoubleOrNull() ?: 0.0
    val duration = args.getOrNull(4)?.toIntOrNull() ?: 40

    val startLoc = inst.originLocation.clone()
    val endLoc = startLoc.clone().add(dx, dy, dz)

    val motion = IntegratedLinearMotion(
      startLocation = startLoc,
      endLocation = endLoc,
      durationTicks = duration,
      isAutoPingPong = true
    )
    inst.setMotion(motion)
    player.sendMessage(
      Component.text("✔ [${inst.id}] 往復移動（エレベーター/ピストン）を開始しました！", NamedTextColor.GREEN)
    )
  }

  private fun handleRotate(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }

    val axis = Axis.fromString(args.getOrNull(1) ?: "y") ?: Axis.Y
    val angle = args.getOrNull(2)?.toFloatOrNull() ?: 90.0f
    val duration = args.getOrNull(3)?.toIntOrNull() ?: 20

    val motion = AngleRotationMotion(
      axis = axis,
      targetAngleDeg = angle,
      durationTicks = duration
    )
    inst.setMotion(motion)
    player.sendMessage(
      Component.text("✔ [${inst.id}] 角度回転を開始: 軸=${axis.name}, 角度=${angle}°, ${duration}ticks", NamedTextColor.GOLD)
    )
  }

  private fun handleSpin(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }

    val axis = Axis.fromString(args.getOrNull(1) ?: "y") ?: Axis.Y
    val dps = args.getOrNull(2)?.toFloatOrNull() ?: 90.0f

    val motion = ContinuousSpinMotion(
      axis = axis,
      degreesPerSecond = dps
    )
    inst.setMotion(motion)
    player.sendMessage(
      Component.text("✔ [${inst.id}] 連続回転（風車/水車）を開始: 軸=${axis.name}, 速度=${dps}°/秒", NamedTextColor.GOLD)
    )
  }

  private fun handlePivot(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }

    val px = args.getOrNull(1)?.toFloatOrNull() ?: 0.5f
    val py = args.getOrNull(2)?.toFloatOrNull() ?: 0.5f
    val pz = args.getOrNull(3)?.toFloatOrNull() ?: 0.5f

    inst.pivotOffset = Vector3f(px, py, pz)
    player.sendMessage(
      Component.text("✔ [${inst.id}] 回転ピボットを (${px}, ${py}, ${pz}) に設定しました！", NamedTextColor.YELLOW)
    )
  }

  private fun handleStop(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player, args.getOrNull(1)) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }
    inst.stopMotion()
    player.sendMessage(Component.text("⏹️ [${inst.id}] の動作を停止しました。", NamedTextColor.GRAY))
  }

  private fun handleOptimize(player: Player, args: Array<out String>, optimize: Boolean) {
    val inst = getTargetInstance(player, args.getOrNull(1)) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }
    val modeArg = args.getOrNull(2)?.lowercase()
    if (modeArg == "all") {
      inst.optimizeNonFullBlocks = true
    } else if (modeArg == "full") {
      inst.optimizeNonFullBlocks = false
    }

    val beforeCount = 1 + inst.additionalParts.size
    manager.rebuildEntities(inst, optimize, inst.optimizeNonFullBlocks)
    val afterCount = 1 + inst.additionalParts.size

    if (optimize) {
      val targetDesc = if (inst.optimizeNonFullBlocks) "すべてのブロック(非フル含む)" else "フルブロックのみ"
      player.sendMessage(Component.text("🧱 [${inst.id}] エンティティ軽量化を有効にしました [${targetDesc}] (${beforeCount}体 ➔ ${afterCount}体に削減！)", NamedTextColor.GREEN))
      player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1.0f, 1.8f)
    } else {
      player.sendMessage(Component.text("🧱 [${inst.id}] エンティティ軽量化を無効にし、個別ブロックに展開しました (${beforeCount}体 ➔ ${afterCount}体)", NamedTextColor.YELLOW))
      player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.4f)
    }
  }

  private fun handleNonFull(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player, args.getOrNull(1)) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }
    val newNonFull = !inst.optimizeNonFullBlocks
    inst.optimizeNonFullBlocks = newNonFull
    val beforeCount = 1 + inst.additionalParts.size
    if (inst.isOptimized) {
      manager.rebuildEntities(inst, true, newNonFull)
    }
    val afterCount = 1 + inst.additionalParts.size

    if (newNonFull) {
      val countInfo = if (inst.isOptimized) " (${beforeCount}体 ➔ ${afterCount}体)" else ""
      player.sendMessage(Component.text("📐 [${inst.id}] 非フルブロックの統合を有効にしました！$countInfo", NamedTextColor.AQUA))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f)
    } else {
      val countInfo = if (inst.isOptimized) " (${beforeCount}体 ➔ ${afterCount}体)" else ""
      player.sendMessage(Component.text("📐 [${inst.id}] 非フルブロックの統合を無効にし、フルブロックのみに限定しました。$countInfo", NamedTextColor.YELLOW))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
    }
  }

  private fun handleTestComp(player: Player, args: Array<out String>) {
    var modeArg: String? = null
    var idArg: String? = null

    val validModes = listOf("off", "on", "v1", "v2", "minimax", "endpoint", "v2ortho", "v2_ortho", "ortho", "v2orthot1", "v2_ortho_t1", "orthot1", "t1", "rigidsync", "rigid_sync", "rigid", "sync")

    if (args.size >= 3) {
      val arg1 = args[1].lowercase()
      val arg2 = args[2].lowercase()
      if (arg1 in validModes) {
        modeArg = arg1
        idArg = args[2]
      } else if (arg2 in validModes) {
        idArg = args[1]
        modeArg = arg2
      } else {
        idArg = args[1]
      }
    } else if (args.size == 2) {
      val arg1 = args[1].lowercase()
      if (arg1 in validModes) {
        modeArg = arg1
        idArg = null
      } else {
        idArg = args[1]
      }
    }

    val inst = (if (idArg != null) {
      manager.get(idArg)
    } else {
      manager.rayTrace(player.eyeLocation, player.location.direction, 5.0)
        ?: selectedInstance[player.uniqueId]?.let { manager.get(it) }
    }) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。視線を合わせるかIDを指定してください (/kk testcomp <mode> <id>)。", NamedTextColor.RED))
      return
    }

    val targetMode = when (modeArg) {
      "off" -> KarakuriInstance.DynamicCompMode.OFF
      "on", "v1", "endpoint" -> KarakuriInstance.DynamicCompMode.V1_ENDPOINT
      "v2", "minimax" -> KarakuriInstance.DynamicCompMode.V2_MINIMAX
      "v2ortho", "v2_ortho", "ortho" -> KarakuriInstance.DynamicCompMode.V2_ORTHOGONAL
      "v2orthot1", "v2_ortho_t1", "orthot1", "t1" -> KarakuriInstance.DynamicCompMode.V2_ORTHO_T1
      "rigidsync", "rigid_sync", "rigid", "sync" -> KarakuriInstance.DynamicCompMode.RIGID_SYNC
      else -> {
        // トグルサイクル: OFF -> V1 -> V2 -> V2_ORTHO -> V2_ORTHO_T1 -> RIGID_SYNC -> OFF
        when (inst.dynamicCompMode) {
          KarakuriInstance.DynamicCompMode.OFF -> KarakuriInstance.DynamicCompMode.V1_ENDPOINT
          KarakuriInstance.DynamicCompMode.V1_ENDPOINT -> KarakuriInstance.DynamicCompMode.V2_MINIMAX
          KarakuriInstance.DynamicCompMode.V2_MINIMAX -> KarakuriInstance.DynamicCompMode.V2_ORTHOGONAL
          KarakuriInstance.DynamicCompMode.V2_ORTHOGONAL -> KarakuriInstance.DynamicCompMode.V2_ORTHO_T1
          KarakuriInstance.DynamicCompMode.V2_ORTHO_T1 -> KarakuriInstance.DynamicCompMode.RIGID_SYNC
          KarakuriInstance.DynamicCompMode.RIGID_SYNC -> KarakuriInstance.DynamicCompMode.OFF
        }
      }
    }

    inst.dynamicCompMode = targetMode
    org.bukkit.Bukkit.getLogger().info("[Karakuri-Test] ${inst.id} の動的補正モードを ${targetMode.name} に変更しました。(実行者: ${player.name})")

    when (targetMode) {
      KarakuriInstance.DynamicCompMode.OFF -> {
        player.sendMessage(Component.text("🔬 [${inst.id}] 動的サジッタ補正を【無効 (OFF)】にしました。（基準動作、従来の約0.5ブロック偏心公転）", NamedTextColor.YELLOW))
        player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.0f)
      }
      KarakuriInstance.DynamicCompMode.V1_ENDPOINT -> {
        player.sendMessage(Component.text("🔬 [${inst.id}] 動的サジッタ補正を【v1 (端点一致逆フィルタ)】に設定しました。（端点誤差0mm、サジッタ残存約15cm）", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f)
      }
      KarakuriInstance.DynamicCompMode.V2_MINIMAX -> {
        player.sendMessage(Component.text("🔬 [${inst.id}] 動的サジッタ補正を【v2 (ミニマックス新方式)】に設定しました。（動径ブーストg=1.1213、理論最大偏心約8.58cm）", NamedTextColor.AQUA))
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.8f)
      }
      KarakuriInstance.DynamicCompMode.V2_ORTHOGONAL -> {
        player.sendMessage(Component.text("🔬 [${inst.id}] 動的サジッタ補正を【v2ortho (回転軸直交分解ミニマックス)】に設定しました。（duration=2, 回転面のみブースト、軸方向変位0mm、理論最大偏心約8.58cm）", NamedTextColor.LIGHT_PURPLE))
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f)
      }
      KarakuriInstance.DynamicCompMode.V2_ORTHO_T1 -> {
        player.sendMessage(Component.text("🔬 [${inst.id}] 動的サジッタ補正を【v2orthot1 (1-tick 独立検証モード)】に設定しました。（duration=1, 直交射影即時補正、軸方向変位0mm）", NamedTextColor.GOLD))
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f)
      }
      KarakuriInstance.DynamicCompMode.RIGID_SYNC -> {
        player.sendMessage(Component.text("🔬 [${inst.id}] 動的サジッタ補正を【rigidsync (純粋剛体同期モード)】に設定しました。（duration=1, g=1.0, ブーストなし純粋剛体並進、更新点接合部誤差0mm）", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f)
      }
    }
  }

  private fun handleRevert(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player, args.getOrNull(1)) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }

    if (!manager.revert(inst.id, player)) {
      player.sendMessage(Component.text("⚠️ からくり [${inst.id}] の復元に失敗しました。", NamedTextColor.RED))
    }
  }

  private fun handleRemove(player: Player, args: Array<out String>) {
    val inst = getTargetInstance(player, args.getOrNull(1)) ?: run {
      player.sendMessage(Component.text("対象のからくりが見つかりません。", NamedTextColor.RED))
      return
    }
    val id = inst.id
    manager.remove(id)
    player.sendMessage(Component.text("🗑️ からくり [${id}] を削除しました。", NamedTextColor.RED))
  }

  /**
   * 未変換ブロックの接着範囲を全消去する
   */
  private fun handleClear(player: Player) {
    val count = glueManager.clearAll()
    player.sendMessage(
      Component.text("✔ 未変換の接着範囲を計 ${count} 箇所消去しました！", NamedTextColor.GREEN)
    )
    player.playSound(player.location, Sound.BLOCK_LAVA_EXTINGUISH, 0.8f, 1.2f)
  }

  private fun handleList(player: Player) {
    val all = manager.getAll()
    if (all.isEmpty()) {
      player.sendMessage(Component.text("現在登録されているからくりはありません。", NamedTextColor.GRAY))
      return
    }
    player.sendMessage(Component.text("--- 登録中のからくり (${all.size}件) ---", NamedTextColor.YELLOW))
    all.forEach { inst ->
      val motionName = inst.activeMotion?.javaClass?.simpleName ?: "静止中"
      val loc = inst.displayEntity.location
      val matName = when (val entity = inst.displayEntity) {
        is org.bukkit.entity.BlockDisplay -> entity.block.material.name
        is org.bukkit.entity.ItemDisplay -> entity.itemStack.type.name
        else -> "UNKNOWN"
      }
      val partCount = inst.additionalParts.size + 1
      player.sendMessage(
        Component.text("• [${inst.id}] ブロック: $matName (${partCount}ブロック構成) | 動作: $motionName | 座標: (${loc.blockX}, ${loc.blockY}, ${loc.blockZ})", NamedTextColor.WHITE)
      )
    }
  }

  private fun handleEscape(player: Player) {
    val pLoc = player.location
    val world = player.world
    val activeInstances = manager.getAll()

    // 0. 座席に着席中の場合は安全に降車、またはスペクテイター孤立時の復旧
    val seatManager = io.oyasai.karakuri.KarakuriPlugin.instance.seatManager
    if (seatManager.isSeated(player)) {
      seatManager.dismount(player)
    } else if (player.gameMode == org.bukkit.GameMode.SPECTATOR) {
      player.spectatorTarget = null
      player.gameMode = org.bukkit.GameMode.SURVIVAL
      // プレイヤーの座席タグが付いた残骸を消去
      try {
        val tag = "karakuri_seat_${player.uniqueId}"
        world.entities.forEach { if (it.scoreboardTags.contains(tag)) it.remove() }
      } catch (_: Exception) {}
    }

    // 1. 乗車判定とガストの即時クリーンアップ
    io.oyasai.karakuri.KarakuriPlugin.instance.collisionEngine.unregisterPlayer(player)

    // 2. からくりのピボット位置を収集
    val karakuriCenters = activeInstances.mapNotNull { it.worldPivotLocation }

    // 3. プレイヤーの周囲 半径 2〜10m の安全な足場ブロックを探索
    // ユーザー指定: 「近くにからくりがない空気ブロックまたはライトブロック」
    var safeLoc: Location? = null

    val px = pLoc.blockX
    val py = pLoc.blockY
    val pz = pLoc.blockZ

    searchLoop@ for (r in 2..10) {
      for (dx in -r..r) {
        for (dz in -r..r) {
          if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) continue // 同心正方形の境界のみスキャン

          val x = px + dx
          val z = pz + dz

          // 全からくりから 2.5m 以上離れていること
          val isNearKarakuri = karakuriCenters.any { c ->
            val distSq = (c.x - (x + 0.5)) * (c.x - (x + 0.5)) + (c.z - (z + 0.5)) * (c.z - (z + 0.5))
            distSq < 2.5 * 2.5
          }
          if (isNearKarakuri) continue

          // Y軸方向をプレイヤーの高さ ±3 で探索
          for (dy in -3..3) {
            val y = py + dy
            val feetBlock = world.getBlockAt(x, y, z)
            val headBlock = world.getBlockAt(x, y + 1, z)
            val groundBlock = world.getBlockAt(x, y - 1, z)

            val isFeetPassable = feetBlock.type.isAir || feetBlock.type == Material.LIGHT
            val isHeadPassable = headBlock.type.isAir || headBlock.type == Material.LIGHT
            val isGroundSolid = groundBlock.type.isSolid && groundBlock.type != Material.BARRIER && !groundBlock.isLiquid

            if (isFeetPassable && isHeadPassable && isGroundSolid) {
              safeLoc = Location(world, x + 0.5, y.toDouble(), z + 0.5, pLoc.yaw, pLoc.pitch)
              break@searchLoop
            }
          }
        }
      }
    }

    // フォールバック: もし見つからなければ、プレイヤーの現在位置からX+4の最高ブロック
    if (safeLoc == null) {
      val targetX = px + 4
      val targetZ = pz
      val highestY = world.getHighestBlockYAt(targetX, targetZ)
      safeLoc = Location(world, targetX + 0.5, highestY.toDouble() + 1.0, targetZ + 0.5, pLoc.yaw, pLoc.pitch)
    }

    player.teleport(safeLoc)
    player.fallDistance = 0f
    player.sendMessage(Component.text("§a[からくり] からくりの外の安全な場所に脱出しました！"))
  }

  private fun handleGhost(player: Player) {
    val isGhost = io.oyasai.karakuri.KarakuriPlugin.instance.collisionEngine.toggleGhost(player)
    if (isGhost) {
      player.sendMessage(Component.text("§b[からくり] §aゴーストモードを有効化しました。（からくりをすり抜けます）"))
    } else {
      player.sendMessage(Component.text("§b[からくり] §cゴーストモードを無効化しました。（からくりと干渉します）"))
    }
  }

  private fun handleSeatCam(player: Player) {
    val enabled = io.oyasai.karakuri.KarakuriPlugin.instance.seatManager.toggleSpectateSeat(player)
    if (enabled) {
      player.sendMessage(Component.text("🌀 [からくり] 回転カーペット騎乗時の【視点公転追従】を有効化しました。（マネキン憑依・視点同期）", NamedTextColor.GREEN))
      player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f)
    } else {
      player.sendMessage(Component.text("🌀 [からくり] 回転カーペット騎乗時の【視点公転追従】を無効化しました。（自由視線・手動見回しモード）", NamedTextColor.YELLOW))
      player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 0.9f)
    }
  }

  private fun handleCartPush(player: Player) {
    val manager = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager
    val enabled = manager.togglePushMode(player)
    if (enabled) {
      player.sendMessage(Component.text("🛒 [からくり] トロッコ手押しモードを【有効】にしました。（素手 + Shift + 左クリックで視線方向へ推進）", NamedTextColor.GREEN))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f)
    } else {
      player.sendMessage(Component.text("🛒 [からくり] トロッコ手押しモードを【無効】にしました。", NamedTextColor.RED))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f)
    }
  }

  private fun sendHelp(sender: CommandSender) {
    sender.sendMessage(Component.text("===== 調律師 (Karakuri) コマンドヘルプ =====", NamedTextColor.GOLD))
    sender.sendMessage(Component.text("/kk tools   - 『調律師の工具箱』（道具入手・破棄GUI）を開く", NamedTextColor.GREEN))
    sender.sendMessage(Component.text("/kk toolbox - 『調律師の工具箱』アイテムを入手", NamedTextColor.GREEN))
    sender.sendMessage(Component.text("/kk wrench  - 『調律師のレンチ』を入手", NamedTextColor.GREEN))
    sender.sendMessage(Component.text("/kk rod     - 『調律師のピストンロッド』を入手", NamedTextColor.GREEN))
    sender.sendMessage(Component.text("/kk glue    - 『調律師の接着剤』を入手", NamedTextColor.GREEN))
    sender.sendMessage(Component.text("/kk escape  - からくりから安全な場所へ脱出する", NamedTextColor.GOLD))
    sender.sendMessage(Component.text("/kk ghost   - からくりのすり抜け（ゴーストモード）を切替 (/kk gh, /kk g)", NamedTextColor.AQUA))
    sender.sendMessage(Component.text("/kk seatcam - 回転カーペット騎乗時の視点公転追従を切替 (/kk cam, /kk seat)", NamedTextColor.LIGHT_PURPLE))
    sender.sendMessage(Component.text("/kk push    - トロッコ手押しモードを切替 (/kk push, /k push, /k cp)", NamedTextColor.AQUA))
    sender.sendMessage(Component.text("/kk clear   - 未変換の接着範囲を全消去", NamedTextColor.RED))
    sender.sendMessage(Component.text("/kk revert  - 視線の先のからくりを通常ブロックに戻す", NamedTextColor.AQUA))
    sender.sendMessage(Component.text("/kk optimize - 同種ブロックを統合してエンティティ軽量化 (/kk optimize [ID] [all|full])", NamedTextColor.GREEN))
    sender.sendMessage(Component.text("/kk unoptimize - 軽量化を解除して個別ブロックに展開", NamedTextColor.YELLOW))
    sender.sendMessage(Component.text("/kk nonfull - 非フルブロック統合のON/OFFを切替", NamedTextColor.AQUA))
    sender.sendMessage(Component.text("/kk testcomp <off|v1|v2|v2ortho|v2orthot1> - 統合ON時の動的補正を切替 (/kk comp, v1:端点一致, v2:ミニマックス, v2ortho:直交分解, v2orthot1:1-tick直交)", NamedTextColor.GREEN))
    sender.sendMessage(Component.text("/kk list    - 登録中のからくり一覧を表示", NamedTextColor.YELLOW))
    sender.sendMessage(Component.text("/kk stop    - 動作を停止", NamedTextColor.YELLOW))
    sender.sendMessage(Component.text("/kk cleanup - ワールド内の残存・重複・孤立ブロックディスプレイを一括消去", NamedTextColor.LIGHT_PURPLE))
  }

  override fun onTabComplete(
    sender: CommandSender,
    command: Command,
    alias: String,
    args: Array<out String>,
  ): List<String> {
    if (args.size == 1) {
      val sub = listOf("tools", "toolbox", "box", "wrench", "rod", "glue", "escape", "ghost", "gh", "g", "seatcam", "cam", "seat", "push", "cp", "cartpush", "clear", "cleanup", "revert", "optimize", "unoptimize", "nonfull", "testcomp", "comp", "list", "stop", "spawn", "select", "move", "pingpong", "rotate", "spin", "pivot", "remove")
      return sub.filter { it.startsWith(args[0].lowercase()) }
    }
    if (args.size == 2) {
      when (args[0].lowercase()) {
        "spawn" -> {
          return Material.entries
            .filter { it.isBlock && !it.isAir }
            .map { it.name.lowercase() }
            .filter { it.startsWith(args[1].lowercase()) }
            .take(30)
        }
        "rotate", "spin" -> {
          return listOf("x", "y", "z").filter { it.startsWith(args[1].lowercase()) }
        }
        "testcomp", "comp" -> {
          return (listOf("off", "v1", "v2", "v2ortho", "v2orthot1", "rigidsync", "on") + manager.getAll().map { it.id }).filter { it.startsWith(args[1].lowercase()) }
        }
        "select", "remove", "stop", "revert", "optimize", "unoptimize", "nonfull" -> {
          return manager.getAll().map { it.id }.filter { it.startsWith(args[1].lowercase()) }
        }
      }
    }
    if (args.size == 3) {
      if (args[0].lowercase() == "optimize" || args[0].lowercase() == "compress") {
        return listOf("all", "full").filter { it.startsWith(args[2].lowercase()) }
      }
      if (args[0].lowercase() == "testcomp" || args[0].lowercase() == "comp") {
        return manager.getAll().map { it.id }.filter { it.startsWith(args[2].lowercase()) }
      }
    }
    return emptyList()
  }
}
