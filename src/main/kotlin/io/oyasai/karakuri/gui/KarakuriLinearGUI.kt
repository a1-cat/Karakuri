package io.oyasai.karakuri.gui

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.motion.IntegratedLinearMotion
import io.oyasai.karakuri.motion.LinearLoopMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * 直線運動（エレベーター・スライドドア・斜め移動）設定 GUI
 */
class KarakuriLinearGUI(val instance: KarakuriInstance) {

  companion object {
    const val TITLE_PREFIX = "直線運動設定盤 ["

    val SPEED_PRESETS = listOf(
      SpeedPreset("⚡ 超高速", 15),
      SpeedPreset("🚀 高速", 25),
      SpeedPreset("🚶 標準", 40),
      SpeedPreset("🐢 ゆっくり", 60)
    )
  }

  data class SpeedPreset(val name: String, val ticks: Int)

  private val motion: IntegratedLinearMotion
    get() {
      val cur = instance.activeMotion
      if (cur is IntegratedLinearMotion) return cur
      val loc = instance.originLocation.clone()
      val newMotion = IntegratedLinearMotion(
        startLocation = loc,
        endLocation = loc.clone().add(0.0, 3.0, 0.0),
        durationTicks = 40,
        loopMode = LinearLoopMode.AUTO_PING_PONG
      )
      instance.setMotion(newMotion)
      return newMotion
    }

  fun open(player: Player) {
    val title = Component.text("$TITLE_PREFIX${instance.id}]", NamedTextColor.BLUE, TextDecoration.BOLD)
    val inv = Bukkit.createInventory(player, 27, title)

    val grayGlass = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
    for (i in 0 until 27) inv.setItem(i, grayGlass)

    val curMotion = motion
    val dist = curMotion.getDistance()

    // 10: X軸プリセット
    inv.setItem(
      10,
      createItem(
        Material.RED_GLAZED_TERRACOTTA,
        Component.text("↔ X軸 (東西スライド)", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(
          Component.text("東西方向にまっすぐ移動します。", NamedTextColor.WHITE),
          Component.text("[クリックして適用]", NamedTextColor.YELLOW)
        )
      )
    )

    // 11: Y軸プリセット
    inv.setItem(
      11,
      createItem(
        Material.LIME_GLAZED_TERRACOTTA,
        Component.text("↕ Y軸 (上下エレベーター)", NamedTextColor.GREEN, TextDecoration.BOLD),
        listOf(
          Component.text("上下方向に昇降します。", NamedTextColor.WHITE),
          Component.text("[クリックして適用]", NamedTextColor.YELLOW)
        )
      )
    )

    // 12: Z軸プリセット
    inv.setItem(
      12,
      createItem(
        Material.BLUE_GLAZED_TERRACOTTA,
        Component.text("↔ Z軸 (南北スライドドア)", NamedTextColor.AQUA, TextDecoration.BOLD),
        listOf(
          Component.text("南北方向にまっすぐ移動します。", NamedTextColor.WHITE),
          Component.text("[クリックして適用]", NamedTextColor.YELLOW)
        )
      )
    )

    // 13: 距離 -1
    inv.setItem(
      13,
      createItem(
        Material.RED_CONCRETE,
        Component.text("➖ 距離 -1", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(Component.text("移動距離を1ブロック減らします。", NamedTextColor.GRAY))
      )
    )

    // 14: 現在の距離・状態表示
    val s = curMotion.startLocation
    val e = curMotion.endLocation
    val diffX = if (s != null && e != null) (e.x - s.x).toInt() else 0
    val diffY = if (s != null && e != null) (e.y - s.y).toInt() else 0
    val diffZ = if (s != null && e != null) (e.z - s.z).toInt() else 0
    inv.setItem(
      14,
      createItem(
        Material.OAK_SIGN,
        Component.text("📐 移動距離: ${String.format("%.1f", dist)}m", NamedTextColor.GOLD, TextDecoration.BOLD),
        listOf(
          Component.text("ベクトル: X: $diffX, Y: $diffY, Z: $diffZ", NamedTextColor.YELLOW),
          Component.text("状態: ${if (!curMotion.isRunning) "⏹ 停止中" else if (curMotion.isPaused) "⏸ 一時停止中" else "▶ 動作中"}", NamedTextColor.WHITE),
          Component.text("※Shift素手右クリックで動作の開始/一時停止が可能！", NamedTextColor.AQUA)
        )
      )
    )

    // 15: 距離 +1
    inv.setItem(
      15,
      createItem(
        Material.LIME_CONCRETE,
        Component.text("➕ 距離 +1", NamedTextColor.GREEN, TextDecoration.BOLD),
        listOf(Component.text("移動距離を1ブロック増やします。", NamedTextColor.GRAY))
      )
    )

    // 16: 移動速度設定 (秒速 BPS)
    val bps = curMotion.blocksPerSecond
    val sec = curMotion.durationTicks / 20.0
    inv.setItem(
      16,
      createItem(
        Material.SUGAR,
        Component.text("⚡ 移動速度: 秒速 ${String.format("%.1f", bps)} m/s", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
        listOf(
          Component.text("所要時間: 約 ${String.format("%.1f", sec)} 秒 (${curMotion.durationTicks}t)", NamedTextColor.WHITE),
          Component.text("移動距離: 約 ${String.format("%.1f", dist)}m", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("• [左クリック]: +0.5 m/s", NamedTextColor.YELLOW),
          Component.text("• [右クリック]: -0.5 m/s", NamedTextColor.YELLOW),
          Component.text("• [Shift+左クリック]: +2.0 m/s", NamedTextColor.GOLD),
          Component.text("• [Shift+右クリック]: -2.0 m/s", NamedTextColor.GOLD)
        )
      )
    )

    // 18: 初期位置に戻す
    inv.setItem(
      18,
      createItem(
        Material.ENDER_PEARL,
        Component.text("⏮️ 初期位置に戻す", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
        listOf(
          Component.text("からくりを初期始点・初期姿勢へ即座にリセットします。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックしてリセット]", NamedTextColor.RED)
        )
      )
    )

    // 19: 動作方式（ループモード）切替
    val modeIcon = when (curMotion.loopMode) {
      LinearLoopMode.AUTO_PING_PONG -> Material.REPEATER
      LinearLoopMode.ONE_WAY_LOOP -> Material.COMPARATOR
      LinearLoopMode.MANUAL_TOGGLE -> Material.LEVER
      LinearLoopMode.MANUAL_ONE_WAY -> Material.TRIPWIRE_HOOK
    }
    inv.setItem(
      19,
      createItem(
        modeIcon,
        Component.text("動作方式: ${curMotion.loopMode.displayName}", NamedTextColor.YELLOW, TextDecoration.BOLD),
        listOf(
          Component.text("説明: ${curMotion.loopMode.description}", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("• 🔁 自動往復 (Ping-Pong): 終点と始点を往復", NamedTextColor.DARK_GRAY),
          Component.text("• 🔄 一方通行ループ: 終点から始点へ戻って繰り返し", NamedTextColor.DARK_GRAY),
          Component.text("• 🕹️ 手動トグル: Shift右クリックで前進/後退", NamedTextColor.DARK_GRAY),
          Component.text("• 🔂 手動一方通行ループ: 終点到達で停止、次回始点から再開", NamedTextColor.DARK_GRAY),
          Component.text(""),
          Component.text("[クリックして動作方式を切替]", NamedTextColor.GOLD)
        )
      )
    )

    // 22: テスト作動 / 一時停止 / 再開
    val (actionMat, actionName, actionColor) = when {
      !curMotion.isRunning -> Triple(Material.LIME_DYE, "▶ 動作開始", NamedTextColor.GREEN)
      curMotion.isPaused -> Triple(Material.CYAN_DYE, "▶ 再開", NamedTextColor.AQUA)
      else -> Triple(Material.YELLOW_DYE, "⏸ 一時停止", NamedTextColor.YELLOW)
    }
    inv.setItem(
      22,
      createItem(
        actionMat,
        Component.text(actionName, actionColor, TextDecoration.BOLD),
        listOf(
          Component.text("現在の設定で作動・一時停止をテストします。", NamedTextColor.GRAY),
          Component.text("※ブロックにShift素手右クリックでも同様に操作可能！", NamedTextColor.YELLOW)
        )
      )
    )

    // 26: 完了
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

  fun handleClick(player: Player, slot: Int, isRightClick: Boolean = false, isShiftClick: Boolean = false): Boolean {
    val m = motion
    val base = instance.originLocation.clone() // 0.5マスずれゼロの基準点！

    when (slot) {
      10 -> { // X軸
        instance.resetToBase(stopMotion = false)
        m.setEndpoints(base, base.clone().add(3.0, 0.0, 0.0))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f)
        open(player)
      }
      11 -> { // Y軸
        instance.resetToBase(stopMotion = false)
        m.setEndpoints(base, base.clone().add(0.0, 3.0, 0.0))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f)
        open(player)
      }
      12 -> { // Z軸
        instance.resetToBase(stopMotion = false)
        m.setEndpoints(base, base.clone().add(0.0, 0.0, 3.0))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f)
        open(player)
      }
      13 -> { // 距離 -1
        val s = m.startLocation ?: base
        val e = m.endLocation ?: base.clone().add(0.0, 3.0, 0.0)
        val curDist = m.getDistance()
        if (curDist > 1.5) {
          val ratio = (curDist - 1.0) / curDist
          val newEnd = Location(
            s.world,
            s.x + (e.x - s.x) * ratio,
            s.y + (e.y - s.y) * ratio,
            s.z + (e.z - s.z) * ratio
          )
          m.setEndpoints(s, newEnd)
        }
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f)
        open(player)
      }
      15 -> { // 距離 +1
        val s = m.startLocation ?: base
        val e = m.endLocation ?: base.clone().add(0.0, 3.0, 0.0)
        val curDist = m.getDistance().coerceAtLeast(0.1)
        val ratio = (curDist + 1.0) / curDist
        val newEnd = Location(
            s.world,
          s.x + (e.x - s.x) * ratio,
          s.y + (e.y - s.y) * ratio,
          s.z + (e.z - s.z) * ratio
        )
        m.setEndpoints(s, newEnd)
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f)
        open(player)
      }
      16 -> { // 速度変更 (秒速 BPS)
        val step = if (isShiftClick) 2.0 else 0.5
        val delta = if (isRightClick) -step else step
        var newBps = (m.blocksPerSecond + delta).coerceIn(0.2, 20.0)
        newBps = (kotlin.math.round(newBps * 10.0) / 10.0)
        m.blocksPerSecond = newBps
        m.updateDurationTicks()
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, if (delta > 0) 1.8f else 1.2f)
        open(player)
      }
      18 -> { // 初期位置に戻す
        m.reset(instance)
        player.sendMessage(Component.text("⏮️ からくりを初期位置（始点）にリセットしました！", NamedTextColor.LIGHT_PURPLE))
        player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f)
        open(player)
      }
      19 -> { // モード切替
        val modes = LinearLoopMode.values()
        val curIdx = modes.indexOf(m.loopMode)
        val nextMode = modes[(curIdx + 1) % modes.size]
        m.loopMode = nextMode
        player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1f, 1.2f)
        player.sendMessage(Component.text("✔ 動作方式を 【${nextMode.displayName}】 に設定しました！", NamedTextColor.YELLOW))
        open(player)
      }
      22 -> { // テスト作動 / 一時停止 / 再開
        val res = m.toggle(instance)
        when (res) {
          IntegratedLinearMotion.ToggleResult.STARTED -> {
            player.sendMessage(Component.text("▶ 直線運動を開始しました！(距離: ${String.format("%.1f", m.getDistance())}m)", NamedTextColor.GREEN))
          }
          IntegratedLinearMotion.ToggleResult.PAUSED -> {
            player.sendMessage(Component.text("⏸️ 直線運動を一時停止しました。", NamedTextColor.YELLOW))
          }
          IntegratedLinearMotion.ToggleResult.RESUMED -> {
            player.sendMessage(Component.text("▶ 直線運動を再開しました！", NamedTextColor.AQUA))
          }
          IntegratedLinearMotion.ToggleResult.TOGGLED -> {
            player.sendMessage(Component.text("↔ 直線運動を作動しました！", NamedTextColor.AQUA))
          }
        }
        open(player)
      }
      26 -> {
        player.closeInventory()
      }
      else -> return false
    }
    io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
    return true
  }

  private fun createItem(mat: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
    val item = ItemStack(mat)
    val meta = item.itemMeta ?: return item
    meta.displayName(name)
    if (lore.isNotEmpty()) meta.lore(lore)
    item.itemMeta = meta
    return item
  }
}
