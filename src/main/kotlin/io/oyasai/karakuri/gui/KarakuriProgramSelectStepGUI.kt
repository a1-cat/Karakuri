package io.oyasai.karakuri.gui

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.motion.IntegratedLinearMotion
import io.oyasai.karakuri.motion.LinearLoopMode
import io.oyasai.karakuri.motion.ProgramMotion
import io.oyasai.karakuri.tool.KarakuriPistonRod
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * ピストンロッドで測定した直線軌道を、7ステッププログラムの特定ステップに適用する選択GUI。
 * 他のステップ（以前設定した直線移動など）を勝手に上書きさせず、指定したステップのみ安全に更新する。
 */
class KarakuriProgramSelectStepGUI(val instance: KarakuriInstance) {

  companion object {
    const val TITLE_PREFIX = "ライン適用先ステップ選択 ["
    val STEP_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16)
  }

  fun open(player: Player) {
    val rodLine = KarakuriPistonRod.getLine(player.uniqueId)
    if (!rodLine.isComplete) {
      player.sendMessage(Component.text("先にピストンロッドで始点(左クリック)と終点(右クリック)を設定してください。", NamedTextColor.RED))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
      return
    }

    val title = Component.text("$TITLE_PREFIX${instance.id}]", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
    val inv = Bukkit.createInventory(player, 27, title)

    val grayGlass = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
    for (i in 0 until 27) inv.setItem(i, grayGlass)

    val config = KarakuriProgramGUI.getConfig(instance.id)

    // ステップ1〜7の選択スロット (スロット 10〜16)
    for (i in 0 until 7) {
      val slot = STEP_SLOTS[i]
      val step = config.steps[i]

      val icon = if (step.actionType == ActionType.NONE) Material.LIGHTNING_ROD else step.actionType.icon
      val isMove = step.actionType == ActionType.MOVE

      inv.setItem(
        slot,
        createItem(
          icon,
          Component.text("【ステップ ${i + 1}】に適用", NamedTextColor.GOLD, TextDecoration.BOLD),
          listOf(
            Component.text("現在: ${step.getSummary()}", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("新規測定ライン:", NamedTextColor.AQUA),
            Component.text("  X: ${rodLine.dx.toInt()}, Y: ${rodLine.dy.toInt()}, Z: ${rodLine.dz.toInt()} (${rodLine.distance.toInt()}m)", NamedTextColor.WHITE),
            Component.text(""),
            Component.text("▶ [クリック] このステップに測定ラインを適用！", NamedTextColor.GREEN)
          ),
          isGlowing = isMove
        )
      )
    }

    // 22: 単体直線運動に変更する（プログラム解除）
    inv.setItem(
      22,
      createItem(
        Material.REDSTONE_BLOCK,
        Component.text("⚠️ プログラムを解除して単体直線運動にする", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(
          Component.text("7ステッププログラムを終了し、からくり全体を", NamedTextColor.GRAY),
          Component.text("この測定ライン1本のみの単体直線運動に上書きします。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックして単体直線運動に切替]", NamedTextColor.RED)
        )
      )
    )

    // 26: キャンセル (閉じる)
    inv.setItem(
      26,
      createItem(
        Material.BARRIER,
        Component.text("❌ キャンセル (閉じる)", NamedTextColor.GRAY, TextDecoration.BOLD)
      )
    )

    player.openInventory(inv)
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
  }

  fun handleClick(player: Player, slot: Int): Boolean {
    val rodLine = KarakuriPistonRod.getLine(player.uniqueId)
    if (!rodLine.isComplete) {
      player.sendMessage(Component.text("ピストンロッドの測定ラインが存在しません。", NamedTextColor.RED))
      player.closeInventory()
      return true
    }

    val config = KarakuriProgramGUI.getConfig(instance.id)

    val stepIndex = STEP_SLOTS.indexOf(slot)
    if (stepIndex != -1) {
      // 選択したステップのみを MOVE に設定し、測定ラインを適用！
      // 他のステップ（以前設定した直線移動など）は1ミリも変更されない！
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
      player.sendMessage(Component.text("  ▶ 移動量: X: ${step.moveDx.toInt()}, Y: ${step.moveDy.toInt()}, Z: ${step.moveDz.toInt()} (${String.format("%.1f", dist)}m) (他ステップは保持されました)", NamedTextColor.YELLOW))

      val loc = player.location
      player.spawnParticle(Particle.HAPPY_VILLAGER, loc, 25, 0.5, 0.5, 0.5, 0.1)
      player.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.8f)

      player.closeInventory()
      return true
    }

    if (slot == 22) {
      // 単体直線運動への切り替え
      val startLoc = rodLine.startPoint!!.clone()
      val endLoc = rodLine.endPoint!!.clone()
      instance.updateOrigin(startLoc)
      instance.resetToBase(stopMotion = false)
      val duration = (rodLine.distance * 10).toInt().coerceIn(15, 120)
      val linear = IntegratedLinearMotion(
        startLocation = startLoc,
        endLocation = endLoc,
        durationTicks = duration,
        loopMode = LinearLoopMode.AUTO_PING_PONG
      )
      instance.setMotion(linear)

      player.sendMessage(Component.text("✔ からくりを単体直線運動に切り替えました！", NamedTextColor.GREEN))
      player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
      player.closeInventory()
      return true
    }

    if (slot == 26) {
      player.closeInventory()
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
