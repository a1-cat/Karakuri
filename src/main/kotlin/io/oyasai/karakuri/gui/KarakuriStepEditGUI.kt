package io.oyasai.karakuri.gui

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.motion.Axis
import io.oyasai.karakuri.motion.KarakuriAction
import io.oyasai.karakuri.tool.KarakuriPistonRod
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * プログラムの1ステップ専用の詳細編集画面。
 * アクション種別一覧からの選択、逆回転切替、次のステップとの同時並行実行、
 * 回転速度・移動速度・待機時間・ピストンロッド取り込みに対応。
 */
class KarakuriStepEditGUI(
  val instance: KarakuriInstance,
  val stepIndex: Int, // 0..6 (ステップ1〜7)
) {

  companion object {
    const val TITLE_PREFIX = "ステップ編集 ["
  }

  fun open(player: Player) {
    val title = Component.text("$TITLE_PREFIX${instance.id}] Step ${stepIndex + 1}", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
    val inv = Bukkit.createInventory(player, 45, title)

    val grayGlass = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
    for (i in 0 until 45) inv.setItem(i, grayGlass)

    val config = KarakuriProgramGUI.getConfig(instance.id)
    val step = config.steps[stepIndex]

    // === 行1: アクション種別一覧 (スロット 10〜15) ===
    val types = ActionType.values()
    val slotStart = 10
    for (i in types.indices) {
      val type = types[i]
      val isSelected = step.actionType == type
      val nameColor = if (isSelected) NamedTextColor.GREEN else NamedTextColor.WHITE
      val dec = if (isSelected) TextDecoration.BOLD else TextDecoration.ITALIC
      inv.setItem(
        slotStart + i,
        createItem(
          type.icon,
          Component.text(type.displayName, nameColor, dec),
          listOf(
            Component.text(if (isSelected) "● 現在選択中" else "○ クリックして選択", if (isSelected) NamedTextColor.GOLD else NamedTextColor.GRAY)
          ),
          isGlowing = isSelected
        )
      )
    }

    // === 行3: 種別に応じた詳細設定コントロール (スロット 19〜25) ===
    when (step.actionType) {
      ActionType.MOVE -> {
        // 19: ピストンロッドからインポート
        val rodLine = KarakuriPistonRod.getLine(player.uniqueId)
        val hasLine = rodLine.isComplete
        val rodLore = if (hasLine) {
          listOf(
            Component.text("計測値: X: ${rodLine.dx.toInt()}, Y: ${rodLine.dy.toInt()}, Z: ${rodLine.dz.toInt()} (${rodLine.distance.toInt()}m)", NamedTextColor.AQUA),
            Component.text(""),
            Component.text("[クリックしてこの移動量を適用]", NamedTextColor.GREEN)
          )
        } else {
          listOf(
            Component.text("ピストンロッドで左クリック(始点)と", NamedTextColor.GRAY),
            Component.text("右クリック(終点)を指定すると取り込めます。", NamedTextColor.GRAY)
          )
        }
        inv.setItem(
          19,
          createItem(
            Material.LIGHTNING_ROD,
            Component.text("📐 ピストンロッドの測定ラインを取り込む", if (hasLine) NamedTextColor.GOLD else NamedTextColor.GRAY, TextDecoration.BOLD),
            rodLore,
            isGlowing = hasLine
          )
        )

        // 20: X軸移動
        inv.setItem(
          20,
          createItem(
            Material.RED_TERRACOTTA,
            Component.text("X軸移動: ${step.moveDx.toInt()} ブロック", NamedTextColor.RED, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +1 ブロック", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -1 ブロック", NamedTextColor.AQUA)
            )
          )
        )

        // 21: Y軸移動 (昇降)
        inv.setItem(
          21,
          createItem(
            Material.GREEN_TERRACOTTA,
            Component.text("Y軸昇降: ${step.moveDy.toInt()} ブロック", NamedTextColor.GREEN, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +1 ブロック (上昇)", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -1 ブロック (下降)", NamedTextColor.AQUA)
            )
          )
        )

        // 22: Z軸移動
        inv.setItem(
          22,
          createItem(
            Material.BLUE_TERRACOTTA,
            Component.text("Z軸移動: ${step.moveDz.toInt()} ブロック", NamedTextColor.BLUE, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +1 ブロック", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -1 ブロック", NamedTextColor.AQUA)
            )
          )
        )

        // 24: 移動速度 (所要時間)
        val sec = step.moveDurationTicks / 20.0f
        inv.setItem(
          24,
          createItem(
            Material.CLOCK,
            Component.text("⏱️ 移動時間: ${String.format("%.1f", sec)} 秒 (${step.moveDurationTicks}t)", NamedTextColor.YELLOW, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +0.5秒 (ゆっくり)", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -0.5秒 (素早く)", NamedTextColor.AQUA)
            )
          )
        )
      }

      ActionType.ROTATE -> {
        // 20: 回転軸選択 (X / Y / Z)
        inv.setItem(
          20,
          createItem(
            Material.COMPASS,
            Component.text("🧭 回転軸: ${step.rotateAxis.name} 軸", NamedTextColor.AQUA, TextDecoration.BOLD),
            listOf(
              Component.text("X軸: 縦回転 / Y軸: 水平回転 / Z軸: 正面回転", NamedTextColor.GRAY),
              Component.text("[クリックして切替]", NamedTextColor.YELLOW)
            )
          )
        )

        // 22: 回転角度 (+90, +180, -90, +45等)
        inv.setItem(
          22,
          createItem(
            Material.ENDER_PEARL,
            Component.text("📐 回転角度: ${step.rotateAngleDeg.toInt()}°", NamedTextColor.GOLD, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +45° 加算", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -45° 減算", NamedTextColor.AQUA)
            )
          )
        )

        // 24: 回転時間 (速度)
        val sec = step.rotateDurationTicks / 20.0f
        inv.setItem(
          24,
          createItem(
            Material.CLOCK,
            Component.text("⏱️ 所要時間: ${String.format("%.1f", sec)} 秒 (${step.rotateDurationTicks}t)", NamedTextColor.YELLOW, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +0.5秒", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -0.5秒", NamedTextColor.AQUA)
            )
          )
        )

        // 25: 逆回転切替 (正回転 / 逆回転)
        inv.setItem(
          25,
          createItem(
            Material.ECHO_SHARD,
            Component.text("🔁 回転方向反転 (正 / 逆回転)", NamedTextColor.AQUA, TextDecoration.BOLD),
            listOf(
              Component.text("現在の角度: ${step.rotateAngleDeg.toInt()}°", NamedTextColor.WHITE),
              Component.text(""),
              Component.text("[クリックしてプラス/マイナス反転]", NamedTextColor.GOLD)
            )
          )
        )
      }

      ActionType.SPIN -> {
        // 20: 軸選択
        inv.setItem(
          20,
          createItem(
            Material.COMPASS,
            Component.text("🧭 回転軸: ${step.spinAxis.name} 軸", NamedTextColor.AQUA, TextDecoration.BOLD),
            listOf(Component.text("[クリックして切替]", NamedTextColor.YELLOW))
          )
        )

        // 22: 回転速度 (RPM)
        inv.setItem(
          22,
          createItem(
            Material.BLAZE_POWDER,
            Component.text("⚡ 回転速度: ${step.spinRpm.toInt()} RPM", NamedTextColor.GOLD, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +8 RPM / [Shift+左] +1 RPM", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -8 RPM / [Shift+右] -1 RPM", NamedTextColor.AQUA)
            )
          )
        )

        // 24: 持続時間
        val sec = step.spinDurationTicks / 20.0f
        inv.setItem(
          24,
          createItem(
            Material.CLOCK,
            Component.text("⏱️ 持続時間: ${String.format("%.1f", sec)} 秒 (${step.spinDurationTicks}t)", NamedTextColor.YELLOW, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +1.0秒", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -1.0秒", NamedTextColor.AQUA)
            )
          )
        )

        // 25: 逆回転切替
        inv.setItem(
          25,
          createItem(
            Material.ECHO_SHARD,
            Component.text("🔁 回転方向反転 (正 / 逆回転)", NamedTextColor.AQUA, TextDecoration.BOLD),
            listOf(
              Component.text("現在の速度: ${step.spinRpm.toInt()} RPM", NamedTextColor.WHITE),
              Component.text(""),
              Component.text("[クリックして逆回転に反転]", NamedTextColor.GOLD)
            )
          )
        )
      }

      ActionType.SPEED_TRANSITION -> {
        // 20: 回転軸選択 (X / Y / Z)
        inv.setItem(
          20,
          createItem(
            Material.COMPASS,
            Component.text("🧭 回転軸: ${step.speedAxis.name} 軸", NamedTextColor.AQUA, TextDecoration.BOLD),
            listOf(
              Component.text("X軸: 縦回転 / Y軸: 水平回転 / Z軸: 正面回転", NamedTextColor.GRAY),
              Component.text("[クリックして切替]", NamedTextColor.YELLOW)
            )
          )
        )

        // 21: 開始速度 (RPM)
        inv.setItem(
          21,
          createItem(
            Material.BLAZE_POWDER,
            Component.text("⚡ 開始速度: ${step.speedStartRpm.toInt()} RPM", NamedTextColor.GOLD, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +8 RPM / [Shift+左] +1 RPM", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -8 RPM / [Shift+右] -1 RPM", NamedTextColor.AQUA)
            )
          )
        )

        // 22: 終了速度 (RPM)
        inv.setItem(
          22,
          createItem(
            Material.BLAZE_ROD,
            Component.text("🏁 終了速度: ${step.speedEndRpm.toInt()} RPM", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +8 RPM / [Shift+左] +1 RPM", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -8 RPM / [Shift+右] -1 RPM", NamedTextColor.AQUA)
            )
          )
        )

        // 24: 変化時間
        val sec = step.speedDurationTicks / 20.0f
        inv.setItem(
          24,
          createItem(
            Material.CLOCK,
            Component.text("⏱️ 変化時間: ${String.format("%.1f", sec)} 秒 (${step.speedDurationTicks}t)", NamedTextColor.YELLOW, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +0.5秒", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -0.5秒", NamedTextColor.AQUA)
            )
          )
        )

        // 25: 回転方向
        val dirStr = if (step.speedIsClockwise) "時計回り ↻" else "反時計回り ↺"
        inv.setItem(
          25,
          createItem(
            Material.ECHO_SHARD,
            Component.text("🔁 回転方向: $dirStr", NamedTextColor.AQUA, TextDecoration.BOLD),
            listOf(
              Component.text("現在の設定: $dirStr", NamedTextColor.WHITE),
              Component.text(""),
              Component.text("[クリックして回転方向を切替]", NamedTextColor.GOLD)
            )
          )
        )
      }

      ActionType.WAIT -> {
        // 22: 待機時間
        val sec = step.waitTicks / 20.0f
        inv.setItem(
          22,
          createItem(
            Material.CLOCK,
            Component.text("⏳ 待機時間: ${String.format("%.1f", sec)} 秒 (${step.waitTicks}t)", NamedTextColor.YELLOW, TextDecoration.BOLD),
            listOf(
              Component.text("▶ [左クリック] +0.5秒", NamedTextColor.GREEN),
              Component.text("◀ [右クリック] -0.5秒", NamedTextColor.AQUA)
            )
          )
        )
      }

      ActionType.NONE -> {
        inv.setItem(
          22,
          createItem(
            Material.BARRIER,
            Component.text("❌ このステップはスキップされます", NamedTextColor.RED, TextDecoration.BOLD)
          )
        )
      }
    }

    // === 行4: 次のステップと同時に並行実行 (スロット 31) ===
    if (step.actionType != ActionType.NONE && stepIndex < 6) {
      inv.setItem(
        31,
        createItem(
          if (step.isSimultaneous) Material.FIREWORK_ROCKET else Material.LEVER,
          Component.text("⏩ 次のステップと同時に実行: " + if (step.isSimultaneous) "ON (並行実行)" else "OFF (順次実行)", if (step.isSimultaneous) NamedTextColor.GREEN else NamedTextColor.GRAY, TextDecoration.BOLD),
          listOf(
            Component.text("• ON: 次のステップの動作を、このステップと同時に動かします！", NamedTextColor.YELLOW),
            Component.text("  （例: 上昇しながら回転するスクリューや、X軸+Y軸の同時複合回転）", NamedTextColor.AQUA),
            Component.text(""),
            Component.text("[クリックして切替]", NamedTextColor.GOLD)
          ),
          isGlowing = step.isSimultaneous
        )
      )
    }

    // === 行5: 戻るボタン (スロット 40) ===
    inv.setItem(
      40,
      createItem(
        Material.ARROW,
        Component.text("⬅ プログラム一覧へ戻る", NamedTextColor.WHITE, TextDecoration.BOLD),
        listOf(Component.text("変更を保持して一覧画面に戻ります。", NamedTextColor.GRAY))
      )
    )

    player.openInventory(inv)
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
  }

  fun handleClick(player: Player, slot: Int, isRightClick: Boolean, isShiftClick: Boolean = false): Boolean {
    val config = KarakuriProgramGUI.getConfig(instance.id)
    val step = config.steps[stepIndex]

    // 行1: アクション種別切り替え (スロット 10〜15)
    if (slot in 10..15) {
      val typeIdx = slot - 10
      val types = ActionType.values()
      if (typeIdx in types.indices) {
        step.actionType = types[typeIdx]
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.5f)
        open(player)
        return true
      }
    }

    // スロット 31: 同時実行切替
    if (slot == 31 && step.actionType != ActionType.NONE && stepIndex < 6) {
      step.isSimultaneous = !step.isSimultaneous
      player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, if (step.isSimultaneous) 1.5f else 0.8f)
      open(player)
      return true
    }

    // 行3: 詳細設定
    when (step.actionType) {
      ActionType.MOVE -> {
        when (slot) {
          19 -> { // ピストンロッド取り込み
            val rodLine = KarakuriPistonRod.getLine(player.uniqueId)
            if (rodLine.isComplete) {
              step.moveDx = rodLine.dx
              step.moveDy = rodLine.dy
              step.moveDz = rodLine.dz
              val dist = rodLine.distance
              step.moveDurationTicks = (dist * 10).toInt().coerceIn(10, 120)

              val actions = config.steps.mapNotNull { it.toAction() }
              if (actions.isNotEmpty()) {
                val motion = io.oyasai.karakuri.motion.ProgramMotion(actions, config.loopMode)
                instance.setMotion(motion)
              }
              io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)

              player.sendMessage(Component.text("✔ ピストンロッドの計測ラインを適用しました！ (X: ${step.moveDx.toInt()}, Y: ${step.moveDy.toInt()}, Z: ${step.moveDz.toInt()})", NamedTextColor.GREEN))
              player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.8f)
            } else {
              player.sendMessage(Component.text("ピストンロッドで始点と終点を両方指定してください。", NamedTextColor.RED))
              player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
            }
            open(player)
          }
          20 -> { // X
            step.moveDx += if (isRightClick) -1.0 else 1.0
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f)
            open(player)
          }
          21 -> { // Y
            step.moveDy += if (isRightClick) -1.0 else 1.0
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f)
            open(player)
          }
          22 -> { // Z
            step.moveDz += if (isRightClick) -1.0 else 1.0
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f)
            open(player)
          }
          24 -> { // Duration
            val delta = if (isRightClick) -10 else 10
            step.moveDurationTicks = (step.moveDurationTicks + delta).coerceIn(5, 200)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f)
            open(player)
          }
        }
      }
      ActionType.ROTATE -> {
        when (slot) {
          20 -> { // 軸
            step.rotateAxis = when (step.rotateAxis) {
              Axis.X -> Axis.Y
              Axis.Y -> Axis.Z
              Axis.Z -> Axis.X
            }
            player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
            open(player)
          }
          22 -> { // 角度
            val delta = if (isRightClick) -45f else 45f
            step.rotateAngleDeg = (step.rotateAngleDeg + delta).coerceIn(-360f, 360f)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f)
            open(player)
          }
          24 -> { // Duration
            val delta = if (isRightClick) -10 else 10
            step.rotateDurationTicks = (step.rotateDurationTicks + delta).coerceIn(5, 200)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f)
            open(player)
          }
          25 -> { // 逆回転切替
            step.rotateAngleDeg = -step.rotateAngleDeg
            player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.4f)
            open(player)
          }
        }
      }
      ActionType.SPIN -> {
        when (slot) {
          20 -> { // 軸
            step.spinAxis = when (step.spinAxis) {
              Axis.X -> Axis.Y
              Axis.Y -> Axis.Z
              Axis.Z -> Axis.X
            }
            player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
            open(player)
          }
          22 -> { // RPM
            val stepVal = if (isShiftClick) 1f else 8f
            val delta = if (isRightClick) -stepVal else stepVal
            step.spinRpm = (step.spinRpm + delta).coerceIn(-256f, 256f)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f)
            open(player)
          }
          24 -> { // Duration
            val delta = if (isRightClick) -20 else 20
            step.spinDurationTicks = (step.spinDurationTicks + delta).coerceIn(10, 400)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f)
            open(player)
          }
          25 -> { // 逆回転切替
            step.spinRpm = -step.spinRpm
            player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.4f)
            open(player)
          }
        }
      }
      ActionType.SPEED_TRANSITION -> {
        when (slot) {
          20 -> { // 回転軸
            step.speedAxis = when (step.speedAxis) {
              Axis.X -> Axis.Y
              Axis.Y -> Axis.Z
              Axis.Z -> Axis.X
            }
            player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
            open(player)
          }
          21 -> { // 開始RPM
            val stepVal = if (isShiftClick) 1f else 8f
            val delta = if (isRightClick) -stepVal else stepVal
            step.speedStartRpm = (step.speedStartRpm + delta).coerceIn(0f, 256f)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f)
            open(player)
          }
          22 -> { // 終了RPM
            val stepVal = if (isShiftClick) 1f else 8f
            val delta = if (isRightClick) -stepVal else stepVal
            step.speedEndRpm = (step.speedEndRpm + delta).coerceIn(0f, 256f)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f)
            open(player)
          }
          24 -> { // 変化時間
            val delta = if (isRightClick) -10 else 10
            step.speedDurationTicks = (step.speedDurationTicks + delta).coerceIn(10, 600)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f)
            open(player)
          }
          25 -> { // 回転方向切替
            step.speedIsClockwise = !step.speedIsClockwise
            player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.4f)
            open(player)
          }
        }
      }
      ActionType.WAIT -> {
        if (slot == 22) {
          val delta = if (isRightClick) -10 else 10
          step.waitTicks = (step.waitTicks + delta).coerceIn(5, 200)
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f)
          open(player)
        }
      }
      ActionType.NONE -> {}
    }

    // 戻るボタン (スロット 40)
    if (slot == 40) {
      KarakuriProgramGUI(instance).open(player)
      return true
    }

    return true
  }

  private fun createItem(
    mat: Material,
    name: Component,
    lore: List<Component> = emptyList(),
    isGlowing: Boolean = false,
  ): ItemStack {
    val item = ItemStack(mat)
    val meta = item.itemMeta ?: return item
    meta.displayName(name)
    if (lore.isNotEmpty()) meta.lore(lore)
    if (isGlowing) {
      meta.setEnchantmentGlintOverride(true)
    }
    item.itemMeta = meta
    return item
  }
}

enum class ActionType(val displayName: String, val icon: Material) {
  MOVE("🛗 直線移動 (Move)", Material.PISTON),
  ROTATE("🔄 角度回転 (Rotate)", Material.COMPASS),
  SPIN("🌪️ 連続回転 (Spin)", Material.WHITE_WOOL),
  SPEED_TRANSITION("⚡ 加減速回転 (Accel)", Material.COPPER_BULB),
  WAIT("⏳ 一時待機 (Wait)", Material.CLOCK),
  NONE("❌ なし (Skip)", Material.BARRIER)
}

data class StepConfig(
  var actionType: ActionType = ActionType.MOVE,
  var moveDx: Double = 0.0,
  var moveDy: Double = 3.0,
  var moveDz: Double = 0.0,
  var moveDurationTicks: Int = 30,
  var rotateAxis: Axis = Axis.Y,
  var rotateAngleDeg: Float = 90f,
  var rotateDurationTicks: Int = 20,
  var spinAxis: Axis = Axis.Z,
  var spinRpm: Float = 16f,
  var spinDurationTicks: Int = 60,
  var waitTicks: Int = 20,
  var isSimultaneous: Boolean = false,
  var speedAxis: Axis = Axis.Y,
  var speedStartRpm: Float = 32f,
  var speedEndRpm: Float = 16f,
  var speedDurationTicks: Int = 120,
  var speedIsClockwise: Boolean = true,
) {
  fun toAction(): KarakuriAction? = when (actionType) {
    ActionType.MOVE -> KarakuriAction.Move(moveDx, moveDy, moveDz, moveDurationTicks, isSimultaneous)
    ActionType.ROTATE -> KarakuriAction.Rotate(rotateAxis, rotateAngleDeg, rotateDurationTicks, null, isSimultaneous)
    ActionType.SPIN -> KarakuriAction.Spin(spinAxis, spinRpm * 6.0f, spinDurationTicks, null, isSimultaneous)
    ActionType.SPEED_TRANSITION -> KarakuriAction.SpeedTransition(speedAxis, speedStartRpm, speedEndRpm, speedDurationTicks, speedIsClockwise, null, isSimultaneous)
    ActionType.WAIT -> KarakuriAction.Wait(waitTicks, isSimultaneous)
    ActionType.NONE -> null
  }

  fun getSummary(): String {
    val simTag = if (isSimultaneous) " ⚡[並行実行]" else ""
    return when (actionType) {
      ActionType.MOVE -> "移動 (X:${moveDx.toInt()}, Y:${moveDy.toInt()}, Z:${moveDz.toInt()} / ${String.format("%.1f", moveDurationTicks / 20f)}s)$simTag"
      ActionType.ROTATE -> "回転 (${rotateAxis.name}軸 ${rotateAngleDeg.toInt()}° / ${String.format("%.1f", rotateDurationTicks / 20f)}s)$simTag"
      ActionType.SPIN -> "スピン (${spinAxis.name}軸 ${spinRpm.toInt()}RPM / ${String.format("%.1f", spinDurationTicks / 20f)}s)$simTag"
      ActionType.SPEED_TRANSITION -> {
        val dir = if (speedIsClockwise) "時計回り" else "反時計回り"
        "加減速 (${speedAxis.name}軸 ${speedStartRpm.toInt()}→${speedEndRpm.toInt()}RPM $dir / ${String.format("%.1f", speedDurationTicks / 20f)}s)$simTag"
      }
      ActionType.WAIT -> "待機 (${String.format("%.1f", waitTicks / 20f)}秒)$simTag"
      ActionType.NONE -> "スキップ"
    }
  }
}
