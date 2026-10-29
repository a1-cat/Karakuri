package io.oyasai.karakuri.gui

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.motion.Axis
import io.oyasai.karakuri.motion.ProgramLoopMode
import io.oyasai.karakuri.motion.ProgramMotion
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap

/**
 * プレイヤーが直感的に7ステップの動作を自由に設計できるプログラム編集盤（メイン）。
 * 各ステップをクリックすると詳細設定画面（KarakuriStepEditGUI）が開き、
 * 移動・回転・待機・ピストンロッド取り込み等を自在にカスタマイズ可能。
 */
class KarakuriProgramGUI(val instance: KarakuriInstance) {

  companion object {
    const val TITLE_PREFIX = "プログラム編集盤 ["
    val STEP_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16) // 横1列 7スロット

    val programConfigs = ConcurrentHashMap<String, ProgramConfig>()

    fun getConfig(id: String): ProgramConfig {
      return programConfigs.computeIfAbsent(id) {
        val defaultSteps = mutableListOf(
          StepConfig(actionType = ActionType.MOVE, moveDy = 3.0, moveDurationTicks = 30),
          StepConfig(actionType = ActionType.WAIT, waitTicks = 20),
          StepConfig(actionType = ActionType.ROTATE, rotateAxis = Axis.Y, rotateAngleDeg = 90f, rotateDurationTicks = 20),
          StepConfig(actionType = ActionType.WAIT, waitTicks = 20),
          StepConfig(actionType = ActionType.NONE),
          StepConfig(actionType = ActionType.NONE),
          StepConfig(actionType = ActionType.NONE)
        )
        ProgramConfig(steps = defaultSteps, loopMode = ProgramLoopMode.AUTO_REVERSE)
      }
    }
  }

  data class ProgramConfig(
    val steps: MutableList<StepConfig>,
    var loopMode: ProgramLoopMode,
  )

  fun open(player: Player) {
    val title = Component.text("$TITLE_PREFIX${instance.id}]", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
    val inv = Bukkit.createInventory(player, 27, title)

    val grayGlass = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
    for (i in 0 until 27) inv.setItem(i, grayGlass)

    val config = getConfig(instance.id)

    // ステップ1〜7の配置 (スロット 10〜16)
    for (i in 0 until 7) {
      val slot = STEP_SLOTS[i]
      val step = config.steps[i]

      val icon = step.actionType.icon
      val nameColor = if (step.actionType != ActionType.NONE) NamedTextColor.GOLD else NamedTextColor.GRAY

      val rodLine = io.oyasai.karakuri.tool.KarakuriPistonRod.getLine(player.uniqueId)
      val stepLore = mutableListOf(
        Component.text("設定内容: ${step.getSummary()}", NamedTextColor.WHITE),
        Component.text(""),
        Component.text("▶ [クリック] このステップを詳細編集", NamedTextColor.GREEN)
      )
      if (rodLine.isComplete) {
        stepLore.add(Component.text("▶ [Shift+クリック] ピストンロッドの測定ラインを取り込む", NamedTextColor.AQUA))
      }

      inv.setItem(
        slot,
        createItem(
          icon,
          Component.text("【ステップ ${i + 1}】", nameColor, TextDecoration.BOLD)
            .append(Component.text(" ${step.actionType.displayName}", NamedTextColor.YELLOW)),
          stepLore,
          isGlowing = step.actionType != ActionType.NONE
        )
      )
    }

    // 19: 再生・復帰方式切替 (リバース往復、手動トグル、ループなど)
    inv.setItem(
      19,
      createItem(
        Material.REPEATER,
        Component.text("再生方式: ${config.loopMode.displayName}", NamedTextColor.YELLOW, TextDecoration.BOLD),
        listOf(
          Component.text(config.loopMode.description, NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックして切替]", NamedTextColor.GOLD)
        )
      )
    )

    // 21: 初期位置リセット
    inv.setItem(
      21,
      createItem(
        Material.RECOVERY_COMPASS,
        Component.text("⏮️ 初期位置にリセット", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
        listOf(
          Component.text("からくりを初期の設置位置・姿勢に即座に戻します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックしてリセット]", NamedTextColor.YELLOW)
        )
      )
    )

    // 22: 適用＆実行
    inv.setItem(
      22,
      createItem(
        Material.LIME_DYE,
        Component.text("▶ プログラムを適用して実行！", NamedTextColor.GREEN, TextDecoration.BOLD),
        listOf(Component.text("設定した7ステップのプログラムをロードして再生します。", NamedTextColor.GRAY))
      )
    )

    // 24: 全ステップクリア
    inv.setItem(
      24,
      createItem(
        Material.TNT,
        Component.text("🗑 全ステップをクリア", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(Component.text("全ステップを『なし』にリセットします。", NamedTextColor.GRAY))
      )
    )

    // 26: 完了 (閉じる)
    inv.setItem(
      26,
      createItem(
        Material.IRON_DOOR,
        Component.text("✔ 完了 (閉じる)", NamedTextColor.WHITE, TextDecoration.BOLD)
      )
    )

    player.openInventory(inv)
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
  }

  fun handleClick(player: Player, slot: Int, isShiftClick: Boolean = false): Boolean {
    val config = getConfig(instance.id)

    val stepIndex = STEP_SLOTS.indexOf(slot)
    if (stepIndex != -1) {
      if (isShiftClick) {
        val rodLine = io.oyasai.karakuri.tool.KarakuriPistonRod.getLine(player.uniqueId)
        if (rodLine.isComplete) {
          val step = config.steps[stepIndex]
          step.actionType = ActionType.MOVE
          step.moveDx = rodLine.dx
          step.moveDy = rodLine.dy
          step.moveDz = rodLine.dz
          val dist = rodLine.distance
          step.moveDurationTicks = (dist * 10).toInt().coerceIn(10, 120)

          val actions = config.steps.mapNotNull { it.toAction() }
          if (actions.isNotEmpty()) {
            val motion = ProgramMotion(actions, config.loopMode)
            instance.setMotion(motion)
          }
          io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)

          player.sendMessage(Component.text("✔ 【ステップ ${stepIndex + 1}】にピストンロッドの直線軌道を適用しました！", NamedTextColor.GREEN, TextDecoration.BOLD))
          player.sendMessage(Component.text("  ▶ 移動量: X: ${step.moveDx.toInt()}, Y: ${step.moveDy.toInt()}, Z: ${step.moveDz.toInt()} (${String.format("%.1f", dist)}m)", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.8f)
          open(player)
          return true
        }
      }
      // ステップ編集画面を開く！
      KarakuriStepEditGUI(instance, stepIndex).open(player)
      return true
    }

    when (slot) {
      19 -> { // ループ・リバース方式切替
        val modes = ProgramLoopMode.values()
        val curIdx = modes.indexOf(config.loopMode)
        val nextIdx = (curIdx + 1) % modes.size
        config.loopMode = modes[nextIdx]
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
        player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1f, 1.2f)
        open(player)
      }
      21 -> { // 初期位置リセット
        instance.resetToBase()
        player.sendMessage(Component.text("⏮️ からくりを初期位置・初期姿勢にリセットしました！", NamedTextColor.LIGHT_PURPLE))
        player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f)
        open(player)
      }
      22 -> { // 適用＆実行
        val actions = config.steps.mapNotNull { it.toAction() }
        if (actions.isEmpty()) {
          player.sendMessage(Component.text("実行できる有効なステップがありません。", NamedTextColor.RED))
          return true
        }
        val motion = ProgramMotion(actions, config.loopMode)
        instance.setMotion(motion)
        motion.isWaitingTrigger = false // 「適用＆実行」ボタン押下時は直ちに再生開始！
        player.sendMessage(Component.text("✔ 7ステッププログラムをロードしました！ (${actions.size}個のアクション / ${config.loopMode.displayName})", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f)
        player.closeInventory()
      }
      24 -> { // 全クリア
        for (step in config.steps) {
          step.actionType = ActionType.NONE
        }
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
        player.sendMessage(Component.text("全ステップをリセットしました。", NamedTextColor.YELLOW))
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.8f, 1.0f)
        open(player)
      }
      26 -> {
        player.closeInventory()
      }
      else -> return false
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
