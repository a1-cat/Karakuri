package io.oyasai.karakuri.gui

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.motion.ChildContraptionMotion
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 子構造（第二のからくり）専用の設定盤 GUI（27スロット）
 *
 * 上段: 独自回転の速度 (RPM)
 * 中段: 親構造の向き公転連動切替・独自回転の作動/停止・回転方向
 * 下段: 接続情報・親構造からの切り離し
 */
class KarakuriChildGUI(
  val instance: KarakuriInstance,
  val motion: ChildContraptionMotion,
) {

  companion object {
    const val GUI_TITLE_PREFIX = "子構造設定盤 ["
    val RPM_PRESETS = listOf(1f, 2f, 4f, 8f, 16f, 32f, 64f, 128f, 256f)
  }

  fun open(player: Player) {
    val title = Component.text("$GUI_TITLE_PREFIX${instance.id}]", NamedTextColor.DARK_AQUA, TextDecoration.BOLD)
    val inv = Bukkit.createInventory(player, 27, title)

    val grayGlass = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
    for (i in 0 until 27) inv.setItem(i, grayGlass)

    // ==========================================
    // 上段 (行1: スロット 0〜8): 独自回転速度 (RPM)
    // ==========================================
    val curRpm = motion.rpm
    RPM_PRESETS.forEachIndexed { idx, rpmVal ->
      val isCurrent = kotlin.math.abs(curRpm - rpmVal) < 0.1f
      inv.setItem(
        idx,
        createItem(
          if (isCurrent) Material.EMERALD_BLOCK else Material.REDSTONE,
          Component.text(
            "${rpmVal.toInt()} RPM" + if (isCurrent) " (現在)" else "",
            if (isCurrent) NamedTextColor.GREEN else NamedTextColor.YELLOW,
            TextDecoration.BOLD
          ),
          listOf(
            Component.text("子構造独自の回転速度を設定します。", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("[クリックして選択]", NamedTextColor.GOLD)
          ),
          isGlowing = isCurrent
        )
      )
    }

    // ==========================================
    // 中段 (行2: スロット 9〜17): モード・公転設定
    // ==========================================

    // 11: 親構造の向き公転連動切替 (followParentRotation)
    val isFollowRot = motion.followParentRotation
    inv.setItem(
      11,
      createItem(
        if (isFollowRot) Material.ENDER_EYE else Material.COMPASS,
        Component.text(
          "🎡 親の向き公転: " + if (isFollowRot) "ON (剛体固定連動)" else "OFF (ゴンドラ/向き維持)",
          if (isFollowRot) NamedTextColor.AQUA else NamedTextColor.GOLD,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("親からくりの中心軸を公転する際に、", NamedTextColor.GRAY),
          Component.text("子からくり自体の向きを連動回転させるか設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("ON : 親と一緒にからくりの向きも回転します", if (isFollowRot) NamedTextColor.GREEN else NamedTextColor.WHITE),
          Component.text("OFF: 向きは固定のまま座標だけが公転します（観覧車等）", if (!isFollowRot) NamedTextColor.GREEN else NamedTextColor.WHITE),
          Component.text(""),
          Component.text("[クリックして切替]", NamedTextColor.YELLOW)
        ),
        isGlowing = isFollowRot
      )
    )

    // 13: 独自回転の作動 / 一時停止
    val isSpinActive = motion.isSpinActive
    inv.setItem(
      13,
      createItem(
        if (isSpinActive) Material.LIME_DYE else Material.GRAY_DYE,
        Component.text(
          if (isSpinActive) "▶ 独自自転: 動作中" else "⏸️ 独自自転: 停止中",
          if (isSpinActive) NamedTextColor.GREEN else NamedTextColor.GRAY,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("粘着ピストンの向きに沿った独自回転を作動/一時停止します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックして切替]", NamedTextColor.YELLOW)
        ),
        isGlowing = isSpinActive
      )
    )

    // 15: 回転方向切替 (時計回り / 反時計回り)
    val isClockwise = motion.isClockwise
    inv.setItem(
      15,
      createItem(
        Material.ECHO_SHARD,
        Component.text(
          "🔁 自転方向: " + if (isClockwise) "時計回り" else "反時計回り",
          NamedTextColor.AQUA,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("独自回転の回転方向を反転します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックして反転]", NamedTextColor.GOLD)
        )
      )
    )

    // ==========================================
    // 下段 (行3: スロット 18〜26): 接続情報・管理
    // ==========================================

    // 19: 接続情報表示
    val axisName = ChildContraptionMotion.getPistonAxisName(motion.pistonFacing)
    inv.setItem(
      19,
      createItem(
        Material.STICKY_PISTON,
        Component.text("🔗 接続情報 (親からくり連動)", NamedTextColor.GOLD, TextDecoration.BOLD),
        listOf(
          Component.text("親からくり: [${motion.parentId}]", NamedTextColor.YELLOW),
          Component.text("粘着ピストン位置: (${motion.pistonOriginLoc.blockX}, ${motion.pistonOriginLoc.blockY}, ${motion.pistonOriginLoc.blockZ})", NamedTextColor.WHITE),
          Component.text("ピストンの向き: ${motion.pistonFacing.name}", NamedTextColor.AQUA),
          Component.text("回転軸の向き: $axisName", NamedTextColor.GREEN),
          Component.text("回転の中心: 粘着ピストン位置", NamedTextColor.GRAY)
        )
      )
    )



    // 25: 親構造から切り離す (独立化)
    inv.setItem(
      25,
      createItem(
        Material.SHEARS,
        Component.text("⛓️ 親構造から切り離す (独立化)", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(
          Component.text("親からくりとの親子関係を解除し、", NamedTextColor.GRAY),
          Component.text("元の独立したからくりに戻します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックして解除]", NamedTextColor.RED)
        )
      )
    )

    player.openInventory(inv)
  }

  fun handleClick(slot: Int, player: Player) {
    val loc = player.location

    when (slot) {
      // RPM プリセット
      in RPM_PRESETS.indices -> {
        val selectedRpm = RPM_PRESETS[slot]
        motion.rpm = selectedRpm
        player.sendMessage(Component.text("⚡ 独自回転速度を ${selectedRpm.toInt()} RPM に設定しました！", NamedTextColor.GREEN))
        player.playSound(loc, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
        open(player)
      }

      // 11: 親の向き公転連動切替
      11 -> {
        motion.followParentRotation = !motion.followParentRotation
        motion.requestSnap()
        val stateName = if (motion.followParentRotation) "ON (剛体固定連動)" else "OFF (ゴンドラ/向き維持)"
        player.sendMessage(Component.text("🎡 親の向き公転を【$stateName】に設定しました！", NamedTextColor.AQUA))
        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f)
        open(player)
      }

      // 13: 独自回転の作動 / 一時停止
      13 -> {
        motion.isSpinActive = !motion.isSpinActive
        if (!motion.isSpinActive) {
          // ★ユーザー指示: 独自自転をオフにさせた際に、オフにする直前の角度は保持されずリセットされる！
          motion.currentSpinAngleRad = 0.0
        }
        val stateName = if (motion.isSpinActive) "作動" else "一時停止 (角度リセット)"
        player.sendMessage(Component.text("✦ 独自回転を【$stateName】しました！", NamedTextColor.YELLOW))
        player.playSound(loc, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
        open(player)
      }

      // 15: 回転方向切替
      15 -> {
        motion.isClockwise = !motion.isClockwise
        val dirName = if (motion.isClockwise) "時計回り" else "反時計回り"
        player.sendMessage(Component.text("🔁 独自回転の方向を【$dirName】に切り替えました！", NamedTextColor.GOLD))
        player.playSound(loc, Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.4f)
        open(player)
      }

      // 25: 親構造から切り離す (独立化)
      25 -> {
        instance.resetToBase(stopMotion = true)
        instance.stopMotion()
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.syncInstanceGeometry(instance)
        player.sendMessage(Component.text("⛓️ [${instance.id}] を親構造から切り離し、独立したからくりに戻しました！", NamedTextColor.RED))
        player.playSound(loc, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.2f)
        player.closeInventory()
      }
    }

    if (slot in RPM_PRESETS.indices || slot == 11 || slot == 13 || slot == 15) {
      try {
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
      } catch (_: Exception) {}
    }
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
