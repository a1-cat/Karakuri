package io.oyasai.karakuri.gui

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.motion.Axis
import io.oyasai.karakuri.motion.ChainChildContraptionMotion
import io.oyasai.karakuri.motion.LinearLoopMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 鉄の鎖・非錆止め銅の鎖に接続された「直線運動版 子構造」専用の設定盤 GUI（27スロット）
 *
 * - 直線移動の速度、進行方向、往復モード、作動/停止
 * - 直線移動と同時に実行可能な独自自転（回転軸、回転速度、作動/停止）
 * - 親からくりの回転に対する向き公転連動 (ON / OFF)
 * - 親構造からの切り離し
 */
class KarakuriChainChildGUI(
  val instance: KarakuriInstance,
  val motion: ChainChildContraptionMotion,
) {

  companion object {
    const val GUI_TITLE_PREFIX = "鎖・直線子構造設定盤 ["
    val RPM_PRESETS = listOf(4f, 8f, 16f, 32f, 64f, 128f, 256f)
  }

  fun open(player: Player) {
    val title = Component.text("$GUI_TITLE_PREFIX${instance.id}]", NamedTextColor.GOLD, TextDecoration.BOLD)
    val inv = Bukkit.createInventory(player, 27, title)

    val grayGlass = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
    for (i in 0 until 27) inv.setItem(i, grayGlass)

    // ==========================================
    // 上段 (行1: スロット 0〜8): 直線移動設定
    // ==========================================
    inv.setItem(0, createItem(Material.REDSTONE, Component.text("⏪ 速度 -1.0 m/s", NamedTextColor.RED, TextDecoration.BOLD)))
    inv.setItem(1, createItem(Material.REDSTONE_TORCH, Component.text("◀ 速度 -0.2 m/s", NamedTextColor.RED)))
    inv.setItem(
      2,
      createItem(
        Material.FEATHER,
        Component.text("🏃 直線速度: ${String.format("%.1f", motion.speed)} m/s", NamedTextColor.YELLOW, TextDecoration.BOLD),
        listOf(
          Component.text("鎖に沿った直線スライド移動の秒速を設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在: ${String.format("%.1f", motion.speed)} ブロック/秒", NamedTextColor.WHITE)
        )
      )
    )
    inv.setItem(3, createItem(Material.GLOWSTONE_DUST, Component.text("▶ 速度 +0.2 m/s", NamedTextColor.GREEN)))
    inv.setItem(4, createItem(Material.GLOWSTONE, Component.text("⏩ 速度 +1.0 m/s", NamedTextColor.GREEN, TextDecoration.BOLD)))

    // 6: 進行方向反転
    val isForward = motion.direction > 0
    inv.setItem(
      6,
      createItem(
        Material.COMPASS,
        Component.text("🧭 進行方向: " + if (isForward) "正方向 ➔" else "◀ 逆方向", NamedTextColor.AQUA, TextDecoration.BOLD),
        listOf(
          Component.text("直線運動の進行方向を反転します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("▶ [クリックして反転]", NamedTextColor.GOLD)
        )
      )
    )

    // 8: 直線移動 作動 / 一時停止
    val isRunning = motion.isRunning
    inv.setItem(
      8,
      createItem(
        if (isRunning) Material.LIME_DYE else Material.GRAY_DYE,
        Component.text(
          if (isRunning) "▶ 直線移動: 動作中" else "⏸️ 直線移動: 停止中",
          if (isRunning) NamedTextColor.GREEN else NamedTextColor.GRAY,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("鎖上の直線スライド移動を作動/一時停止します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("▶ [クリックして切替]", NamedTextColor.YELLOW)
        ),
        isGlowing = isRunning
      )
    )

    // ==========================================
    // 中段 (行2: スロット 9〜17): モード & 自転（回転）設定
    // ==========================================

    // 9: 直線移動モード (往復ループ / 手動往復 / 自動一方通行 / 手動一方通行)
    val modeName = when (motion.linearMode) {
      LinearLoopMode.AUTO_PING_PONG -> "往復ループ (自動往復)"
      LinearLoopMode.MANUAL_TOGGLE -> "手動での往復ループ (端で停止・次回逆動)"
      LinearLoopMode.ONE_WAY_LOOP -> "一方通行の自動ループ (端から自動転移)"
      LinearLoopMode.MANUAL_ONE_WAY -> "手動での一方通行ループ (端で停止・次回始点から)"
      else -> "往復ループ (自動往復)"
    }
    inv.setItem(
      9,
      createItem(
        Material.REPEATER,
        Component.text("⚙️ 動作モード: $modeName", NamedTextColor.YELLOW, TextDecoration.BOLD),
        listOf(
          Component.text("鎖の端に到達したときの動作を設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在のモード: $modeName", NamedTextColor.AQUA),
          Component.text(""),
          Component.text("▶ [クリックして切替]", NamedTextColor.GOLD)
        )
      )
    )

    // 11: 親構造の向き公転連動切替 (followParentRotation)
    val isFollowRot = motion.followParentRotation
    inv.setItem(
      11,
      createItem(
        if (isFollowRot) Material.ENDER_EYE else Material.SPYGLASS,
        Component.text(
          "🎡 親の向き公転: " + if (isFollowRot) "ON (剛体固定連動)" else "OFF (向き維持)",
          if (isFollowRot) NamedTextColor.AQUA else NamedTextColor.GOLD,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("親からくりが回転する際、子からくりの向きを", NamedTextColor.GRAY),
          Component.text("親と一緒に連動回転させるか設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("ON : 親の回転に合わせて向きも回転します", if (isFollowRot) NamedTextColor.GREEN else NamedTextColor.WHITE),
          Component.text("OFF: 向きは固定したまま鎖と一緒に公転します", if (!isFollowRot) NamedTextColor.GREEN else NamedTextColor.WHITE),
          Component.text(""),
          Component.text("▶ [クリックして切替]", NamedTextColor.YELLOW)
        ),
        isGlowing = isFollowRot
      )
    )

    // 13: 自転軸切替 (なし ➔ Y軸 ➔ X軸 ➔ Z軸 ➔ なし)
    val spinAxisName = when (motion.spinAxis) {
      null -> "回転なし (停止)"
      Axis.Y -> "Y軸 (水平回転)"
      Axis.X -> "X軸 (ピッチ回転)"
      Axis.Z -> "Z軸 (ロール回転)"
    }
    inv.setItem(
      13,
      createItem(
        Material.AMETHYST_SHARD,
        Component.text("🔄 自転軸: $spinAxisName", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
        listOf(
          Component.text("鎖の上を移動しながら、からくりを同時に", NamedTextColor.GRAY),
          Component.text("自転回転させる軸を設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在の自転軸: $spinAxisName", NamedTextColor.YELLOW),
          Component.text(""),
          Component.text("▶ [クリックして切替 (なし➔Y軸➔X軸➔Z軸)]", NamedTextColor.GREEN)
        ),
        isGlowing = motion.spinAxis != null
      )
    )

    // 15: 自転速度 (RPM) 切替
    inv.setItem(
      15,
      createItem(
        Material.CLOCK,
        Component.text("⏱ 自転速度: ${motion.rpm.toInt()} RPM", NamedTextColor.GOLD, TextDecoration.BOLD),
        listOf(
          Component.text("自転回転の速度を設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在: ${motion.rpm.toInt()} RPM (${if (motion.isClockwise) "時計回り" else "反時計回り"})", NamedTextColor.WHITE),
          Component.text(""),
          Component.text("▶ [左クリック: 速度変更 (4➔8➔16➔32➔64➔128➔256)]", NamedTextColor.YELLOW),
          Component.text("▶ [右クリック: 回転方向反転]", NamedTextColor.AQUA)
        )
      )
    )

    // 17: 自転作動 / 一時停止
    val isSpinActive = motion.isSpinActive
    inv.setItem(
      17,
      createItem(
        if (isSpinActive) Material.LIME_CANDLE else Material.GRAY_CANDLE,
        Component.text(
          if (isSpinActive) "▶ 自転回転: 動作中" else "⏸️ 自転回転: 停止中",
          if (isSpinActive) NamedTextColor.GREEN else NamedTextColor.GRAY,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("設定した軸での自転回転を作動/一時停止します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("▶ [クリックして切替]", NamedTextColor.YELLOW)
        ),
        isGlowing = isSpinActive
      )
    )

    // ==========================================
    // 下段 (行3: スロット 18〜26): 接続情報・管理
    // ==========================================

    // 19: 鎖接続情報表示
    val axisStr = motion.chainAxis.name
    val distBlocks = (motion.minChainLoc.distance(motion.maxChainLoc)).toInt() + 1
    inv.setItem(
      19,
      createItem(
        Material.IRON_CHAIN,
        Component.text("🔗 鎖・直線接続情報", NamedTextColor.GOLD, TextDecoration.BOLD),
        listOf(
          Component.text("親からくり: [${motion.parentId}]", NamedTextColor.YELLOW),
          Component.text("鎖の軸: $axisStr 軸", NamedTextColor.WHITE),
          Component.text("鎖の全長: 約 $distBlocks ブロック", NamedTextColor.AQUA),
          Component.text("端A: (${motion.minChainLoc.blockX}, ${motion.minChainLoc.blockY}, ${motion.minChainLoc.blockZ})", NamedTextColor.GRAY),
          Component.text("端B: (${motion.maxChainLoc.blockX}, ${motion.maxChainLoc.blockY}, ${motion.maxChainLoc.blockZ})", NamedTextColor.GRAY)
        )
      )
    )

    // 22: 初期位置・初期姿勢に戻す
    inv.setItem(
      22,
      createItem(
        Material.RECOVERY_COMPASS,
        Component.text("⏮️ 初期位置・初期姿勢に戻す", NamedTextColor.AQUA, TextDecoration.BOLD),
        listOf(
          Component.text("接続時に最初に選択した鎖ブロックの位置と、", NamedTextColor.GRAY),
          Component.text("初期の回転姿勢（自転0度）にリセットします。", NamedTextColor.GRAY),
          Component.text("(直線移動・自転回転も停止します)", NamedTextColor.DARK_GRAY),
          Component.text(""),
          Component.text("▶ [クリックしてリセット]", NamedTextColor.YELLOW)
        )
      )
    )

    // 25: 親構造から切り離す (独立化)
    inv.setItem(
      25,
      createItem(
        Material.SHEARS,
        Component.text("⛓️ 鎖から外す (親構造から独立化)", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(
          Component.text("鎖との親子関係を解除し、", NamedTextColor.GRAY),
          Component.text("現在の位置に独立したからくりとして設置します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("▶ [クリックして解除]", NamedTextColor.RED)
        )
      )
    )

    // 26: 閉じる
    inv.setItem(
      26,
      createItem(
        Material.BARRIER,
        Component.text("❌ 閉じる", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(Component.text("設定盤を閉じます。", NamedTextColor.GRAY))
      )
    )

    player.openInventory(inv)
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
  }

  fun handleClick(slot: Int, player: Player, isRightClick: Boolean = false) {
    val loc = player.location

    when (slot) {
      // 0: 速度 -1.0
      0 -> modifySpeed(player, -1.0)
      // 1: 速度 -0.2
      1 -> modifySpeed(player, -0.2)
      // 3: 速度 +0.2
      3 -> modifySpeed(player, 0.2)
      // 4: 速度 +1.0
      4 -> modifySpeed(player, 1.0)

      // 6: 進行方向反転
      6 -> {
        motion.direction = -motion.direction
        val dirName = if (motion.direction > 0) "正方向 ➔" else "◀ 逆方向"
        player.sendMessage(Component.text("🧭 [${instance.id}] 進行方向を 【$dirName】 に反転しました！", NamedTextColor.AQUA))
        player.playSound(loc, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
        open(player)
      }

      // 8: 直線移動 作動 / 一時停止
      8 -> {
        motion.isRunning = !motion.isRunning
        val stateName = if (motion.isRunning) "作動" else "一時停止"
        player.sendMessage(Component.text("✦ [${instance.id}] 直線移動を【$stateName】しました！", NamedTextColor.YELLOW))
        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, if (motion.isRunning) 1.5f else 0.8f)
        open(player)
      }

      // 9: 動作モード切替 (AUTO_PING_PONG ➔ MANUAL_TOGGLE ➔ ONE_WAY_LOOP ➔ MANUAL_ONE_WAY ➔ AUTO_PING_PONG)
      9 -> {
        motion.linearMode = when (motion.linearMode) {
          LinearLoopMode.AUTO_PING_PONG -> LinearLoopMode.MANUAL_TOGGLE
          LinearLoopMode.MANUAL_TOGGLE -> LinearLoopMode.ONE_WAY_LOOP
          LinearLoopMode.ONE_WAY_LOOP -> LinearLoopMode.MANUAL_ONE_WAY
          LinearLoopMode.MANUAL_ONE_WAY -> LinearLoopMode.AUTO_PING_PONG
          else -> LinearLoopMode.AUTO_PING_PONG
        }
        val modeName = when (motion.linearMode) {
          LinearLoopMode.AUTO_PING_PONG -> "往復ループ (自動往復)"
          LinearLoopMode.MANUAL_TOGGLE -> "手動での往復ループ (端で停止・次回逆動)"
          LinearLoopMode.ONE_WAY_LOOP -> "一方通行の自動ループ (端から自動転移)"
          LinearLoopMode.MANUAL_ONE_WAY -> "手動での一方通行ループ (端で停止・次回始点から)"
          else -> "往復ループ (自動往復)"
        }
        player.sendMessage(Component.text("⚙️ [${instance.id}] 動作モードを 【$modeName】 に設定しました！", NamedTextColor.YELLOW))
        player.playSound(loc, Sound.UI_BUTTON_CLICK, 0.8f, 1.4f)
        open(player)
      }

      // 11: 親の向き公転連動切替
      11 -> {
        motion.followParentRotation = !motion.followParentRotation
        motion.requestSnap()
        val stateName = if (motion.followParentRotation) "ON (剛体固定連動)" else "OFF (向き維持)"
        player.sendMessage(Component.text("🎡 [${instance.id}] 親の向き公転を【$stateName】に設定しました！", NamedTextColor.AQUA))
        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f)
        open(player)
      }

      // 13: 自転軸切替 (なし ➔ Y軸 ➔ X軸 ➔ Z軸 ➔ なし)
      13 -> {
        motion.spinAxis = when (motion.spinAxis) {
          null -> Axis.Y
          Axis.Y -> Axis.X
          Axis.X -> Axis.Z
          Axis.Z -> null
        }
        motion.requestSnap()
        val name = when (motion.spinAxis) {
          null -> "回転なし (停止)"
          Axis.Y -> "Y軸 (水平回転)"
          Axis.X -> "X軸 (ピッチ回転)"
          Axis.Z -> "Z軸 (ロール回転)"
        }
        player.sendMessage(Component.text("🔄 [${instance.id}] 自転軸を 【$name】 に設定しました！", NamedTextColor.LIGHT_PURPLE))
        player.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f)
        open(player)
      }

      // 15: 自転速度 / 自転方向
      15 -> {
        if (isRightClick) {
          motion.isClockwise = !motion.isClockwise
          val dirName = if (motion.isClockwise) "時計回り" else "反時計回り"
          player.sendMessage(Component.text("🔁 [${instance.id}] 自転回転方向を 【$dirName】 に反転しました！", NamedTextColor.GOLD))
          player.playSound(loc, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
        } else {
          val curRpm = motion.rpm
          val nextIdx = (RPM_PRESETS.indexOfFirst { kotlin.math.abs(it - curRpm) < 0.1f } + 1) % RPM_PRESETS.size
          motion.rpm = RPM_PRESETS[nextIdx]
          player.sendMessage(Component.text("⏱ [${instance.id}] 自転速度を ${motion.rpm.toInt()} RPM に設定しました！", NamedTextColor.GOLD))
          player.playSound(loc, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
        }
        open(player)
      }

      // 17: 自転作動 / 一時停止
      17 -> {
        motion.isSpinActive = !motion.isSpinActive
        if (!motion.isSpinActive) {
          motion.currentSpinAngleRad = 0.0
        }
        motion.requestSnap()
        val stateName = if (motion.isSpinActive) "作動" else "一時停止 (角度リセット)"
        player.sendMessage(Component.text("✦ [${instance.id}] 自転回転を【$stateName】しました！", NamedTextColor.YELLOW))
        player.playSound(loc, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
        open(player)
      }

      // 22: 初期位置・初期姿勢に戻す
      22 -> {
        motion.resetToInitial()
        player.sendMessage(Component.text("⏮️ [${instance.id}] を初期位置（接続した鎖の位置）および初期姿勢に戻しました！", NamedTextColor.GREEN))
        player.playSound(loc, Sound.BLOCK_ANVIL_USE, 0.8f, 1.4f)
        open(player)
      }

      // 25: 親構造から切り離す
      25 -> {
        instance.resetToBase(stopMotion = true)
        instance.stopMotion()
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.syncInstanceGeometry(instance)
        player.sendMessage(Component.text("⛓️ [${instance.id}] を鎖から外し、独立したからくりに戻しました！", NamedTextColor.RED))
        player.playSound(loc, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.2f)
        player.closeInventory()
      }

      // 26: 閉じる
      26 -> {
        player.closeInventory()
      }
    }

    if (slot != 25 && slot != 26) {
      try {
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
      } catch (_: Exception) {}
    }
  }

  private fun modifySpeed(player: Player, delta: Double) {
    val newSpeed = (motion.speed + delta).coerceIn(0.1, 20.0)
    motion.speed = newSpeed
    try {
      io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
    } catch (_: Exception) {}
    player.sendMessage(Component.text("🏃 [${instance.id}] 直線移動速度を ${String.format("%.1f", newSpeed)} m/s に設定しました。", NamedTextColor.GREEN))
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.5f)
    open(player)
  }

  private fun createItem(
    mat: Material,
    name: Component,
    lore: List<Component> = emptyList(),
    isGlowing: Boolean = false,
  ): ItemStack {
    val item = ItemStack(mat)
    val meta = item.itemMeta ?: return item
    meta.displayName(name.decoration(TextDecoration.ITALIC, false))
    meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
    if (isGlowing) {
      meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true)
      meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS)
    }
    meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES)
    item.itemMeta = meta
    return item
  }
}
