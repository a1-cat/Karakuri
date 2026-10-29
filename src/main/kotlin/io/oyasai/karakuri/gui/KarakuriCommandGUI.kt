package io.oyasai.karakuri.gui

import io.oyasai.karakuri.KarakuriPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * 調律師の工具箱を左クリックした時に開く、便利系ショートカットコマンド専用GUI
 * - /kk escape (尖塔の装飾の鍛冶型)
 * - /kk clear (大自然風の装飾の鍛冶型)
 * - /kk ghost (静寂の装飾の鍛冶型)
 * - /kk seatcam (旋風の装飾の鍛冶型)
 */
object KarakuriCommandGUI {

  const val TITLE = "調律師の設定"

  fun open(player: Player) {
    val inv = Bukkit.createInventory(null, 27, Component.text(TITLE, NamedTextColor.GOLD, TextDecoration.BOLD))

    val border = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
      itemMeta = itemMeta?.apply { displayName(Component.text(" ")) }
    }
    for (i in 0 until 27) {
      inv.setItem(i, border)
    }

    val plugin = KarakuriPlugin.instance
    val isGhost = plugin.collisionEngine.isGhost(player)
    val isSeatCam = plugin.seatManager.isSpectateSeatEnabled(player)

    // 中央行（スロット11〜15）に5つの設定項目を綺麗に整列配置
    // スロット 11: /kk escape (尖塔の装飾の鍛冶型: SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE)
    val escapeItem = ItemStack(Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE).apply {
      itemMeta = itemMeta?.apply {
        displayName(Component.text("🚀 /kk escape (緊急脱出)", NamedTextColor.GOLD, TextDecoration.BOLD))
        lore(
          listOf(
            Component.text("からくりの挟まり・壁埋まりから最寄りの安全な場所へ脱出します。", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("▶ [クリックして脱出を実行]", NamedTextColor.YELLOW)
          )
        )
      }
    }
    inv.setItem(11, escapeItem)

    // スロット 12: /kk clear (大自然風の装飾の鍛冶型: WILD_ARMOR_TRIM_SMITHING_TEMPLATE)
    val clearItem = ItemStack(Material.WILD_ARMOR_TRIM_SMITHING_TEMPLATE).apply {
      itemMeta = itemMeta?.apply {
        displayName(Component.text("🧹 /kk clear (接着範囲の全消去)", NamedTextColor.GREEN, TextDecoration.BOLD))
        lore(
          listOf(
            Component.text("登録されている未変換の接着範囲や選択をすべて一括消去します。", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("▶ [クリックして接着範囲を全消去]", NamedTextColor.RED)
          )
        )
      }
    }
    inv.setItem(12, clearItem)

    // スロット 13: /kk ghost (静寂の装飾の鍛冶型: SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE)
    val ghostItem = ItemStack(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE).apply {
      itemMeta = itemMeta?.apply {
        displayName(
          Component.text(
            "👻 /kk ghost (ゴーストモード切替): " + if (isGhost) "ON (すり抜け)" else "OFF (通常)",
            if (isGhost) NamedTextColor.AQUA else NamedTextColor.DARK_AQUA,
            TextDecoration.BOLD
          )
        )
        lore(
          listOf(
            Component.text("からくりとの当たり判定を完全にすり抜けるモードを切り替えます。", NamedTextColor.GRAY),
            Component.text("からくり建築中やメンテナンス時の移動に最適です。", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("現在の状態: " + if (isGhost) "ON (からくりをすり抜けます)" else "OFF (からくりと干渉します)", if (isGhost) NamedTextColor.AQUA else NamedTextColor.GRAY),
            Component.text(""),
            Component.text("▶ [クリックしてON/OFFを切替]", NamedTextColor.YELLOW)
          )
        )
      }
    }
    inv.setItem(13, ghostItem)

    // スロット 14: /kk seatcam (旋風の装飾の鍛冶型: FLOW_ARMOR_TRIM_SMITHING_TEMPLATE)
    val seatCamItem = ItemStack(Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE).apply {
      itemMeta = itemMeta?.apply {
        displayName(
          Component.text(
            "🌀 /kk seatcam (視点公転追従切替): " + if (isSeatCam) "ON (公転同期)" else "OFF (自由視線)",
            if (isSeatCam) NamedTextColor.LIGHT_PURPLE else NamedTextColor.YELLOW,
            TextDecoration.BOLD
          )
        )
        lore(
          listOf(
            Component.text("回転しているからくりのカーペットに座った際のカメラ挙動を設定します。", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("• ON : マネキンに憑依し、からくりの回転に合わせて視点も自動公転追従", if (isSeatCam) NamedTextColor.LIGHT_PURPLE else NamedTextColor.GRAY),
            Component.text("• OFF: 視点は固定されず、プレイヤーが自分で自由に周囲を見回せる", if (!isSeatCam) NamedTextColor.YELLOW else NamedTextColor.GRAY),
            Component.text(""),
            Component.text("現在の設定: " + if (isSeatCam) "ON (マネキン憑依・視点同期)" else "OFF (自由視線・手動見回し)", if (isSeatCam) NamedTextColor.LIGHT_PURPLE else NamedTextColor.YELLOW),
            Component.text(""),
            Component.text("▶ [クリックして設定を切替]", NamedTextColor.YELLOW)
          )
        )
      }
    }
    inv.setItem(14, seatCamItem)

    // スロット 15: /kk push (監獄風の装飾の鍛冶型: WARD_ARMOR_TRIM_SMITHING_TEMPLATE)
    val isPush = plugin.minecartCouplingManager.isPushModeEnabled(player)
    val pushItem = ItemStack(Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE).apply {
      itemMeta = itemMeta?.apply {
        displayName(
          Component.text(
            "🛒 /kk push (手押しモード切替): " + if (isPush) "ON (手押し有効)" else "OFF (手押し無効)",
            if (isPush) NamedTextColor.GREEN else NamedTextColor.RED,
            TextDecoration.BOLD
          )
        )
        lore(
          listOf(
            Component.text("有効化すると、素手で【Shift + 左クリック】でトロッコを殴った際に、", NamedTextColor.GRAY),
            Component.text("プレイヤーの視線の方向へトロッコを押し出すことができます。", NamedTextColor.GRAY),
            Component.text("（からくり化レール上のトロッコや連結編成にも対応）", NamedTextColor.DARK_GRAY),
            Component.text(""),
            Component.text("現在の設定: " + if (isPush) "ON (手押し有効)" else "OFF (手押し無効)", if (isPush) NamedTextColor.GREEN else NamedTextColor.RED),
            Component.text("コマンド: /k push (/k cp, /karakuri push)", NamedTextColor.DARK_GRAY),
            Component.text(""),
            Component.text("▶ [クリックしてON/OFFを切替]", NamedTextColor.YELLOW)
          )
        )
      }
    }
    inv.setItem(15, pushItem)

    // スロット 22: 工具箱GUIへ切り替え (道具の取り出し盤)
    val toolboxSwitch = ItemStack(Material.CHEST).apply {
      itemMeta = itemMeta?.apply {
        displayName(Component.text("🧰 『調律師の工具箱』（道具取り出し盤）を開く", NamedTextColor.GOLD, TextDecoration.BOLD))
        lore(
          listOf(
            Component.text("レンチ・ピストンロッド・接着剤を取り出せるGUIを開きます。", NamedTextColor.GRAY),
            Component.text("（工具箱を右クリックした時と同じ画面です）", NamedTextColor.DARK_GRAY),
            Component.text(""),
            Component.text("▶ [クリックして工具箱を開く]", NamedTextColor.GREEN)
          )
        )
      }
    }
    inv.setItem(22, toolboxSwitch)

    player.openInventory(inv)
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
  }

  fun handleClick(player: Player, slot: Int): Boolean {
    val plugin = KarakuriPlugin.instance
    when (slot) {
      11 -> { // /kk escape
        player.performCommand("kk escape")
        player.closeInventory()
        return true
      }
      12 -> { // /kk clear
        player.performCommand("kk clear")
        player.playSound(player.location, Sound.BLOCK_LAVA_EXTINGUISH, 0.8f, 1.2f)
        return true
      }
      13 -> { // /kk ghost
        val newState = plugin.collisionEngine.toggleGhost(player)
        if (newState) {
          player.sendMessage(Component.text("§b[からくり] §aゴーストモードを有効化しました。（からくりをすり抜けます）"))
          player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f)
        } else {
          player.sendMessage(Component.text("§b[からくり] §cゴーストモードを無効化しました。（からくりと干渉します）"))
          player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 0.9f)
        }
        open(player) // 表示更新
        return true
      }
      14 -> { // /kk seatcam
        val newSeatCam = plugin.seatManager.toggleSpectateSeat(player)
        if (newSeatCam) {
          player.sendMessage(Component.text("🌀 [からくり] 回転カーペット騎乗時の【視点公転追従】を有効化しました。（マネキン憑依・視点同期）", NamedTextColor.GREEN))
          player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.5f)
        } else {
          player.sendMessage(Component.text("🌀 [からくり] 回転カーペット騎乗時の【視点公転追従】を無効化しました。（自由視線・手動見回しモード）", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 0.9f)
        }
        open(player) // 表示更新
        return true
      }
      15 -> { // /kk push
        val newPush = plugin.minecartCouplingManager.togglePushMode(player)
        if (newPush) {
          player.sendMessage(Component.text("🛒 [からくり] トロッコ手押しモードを【有効】にしました。（素手 + Shift + 左クリックで視線方向へ推進）", NamedTextColor.GREEN))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f)
        } else {
          player.sendMessage(Component.text("🛒 [からくり] トロッコ手押しモードを【無効】にしました。", NamedTextColor.RED))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f)
        }
        open(player) // 表示更新
        return true
      }
      22 -> { // 工具箱アイテム盤へ移動
        KarakuriToolboxGUI.open(player)
        return true
      }
      else -> return false
    }
  }
}
