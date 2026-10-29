package io.oyasai.karakuri.gui

import io.oyasai.karakuri.KarakuriPlugin
import io.oyasai.karakuri.tool.KarakuriRemote
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * 工具箱 Shift+右クリック で開く「レッドストーンリモコン専用収納」
 * 各種周波数を登録した複数のリモコンを安全に保管・整理できる。
 */
object KarakuriRemoteStorageGUI {

  const val TITLE = "⚡ リモコン専用収納"

  fun open(player: Player) {
    val inv = Bukkit.createInventory(
      null,
      27,
      Component.text(TITLE, NamedTextColor.RED, TextDecoration.BOLD)
    )

    // DBからアイテムをロード
    try {
      val savedItems = KarakuriPlugin.instance.database.loadRemoteStorage(player.uniqueId)
      if (savedItems != null) {
        for (i in 0 until savedItems.size) {
          if (i < inv.size) {
            inv.setItem(i, savedItems[i])
          }
        }
      }
    } catch (e: Exception) {
      KarakuriPlugin.instance.logger.warning("[RemoteStorage] ロード失敗 (${player.name}): ${e.message}")
    }

    player.openInventory(inv)
    player.playSound(player.location, Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 1.2f)
  }

  fun save(player: Player, inv: Inventory) {
    try {
      KarakuriPlugin.instance.database.saveRemoteStorage(player.uniqueId, inv.contents)
    } catch (e: Exception) {
      KarakuriPlugin.instance.logger.warning("[RemoteStorage] 保存失敗 (${player.name}): ${e.message}")
    }
  }

  fun handleItemMove(player: Player, cursorItem: ItemStack?): Boolean {
    // リモコン以外のアイテムをこの収納に入れようとした場合は拒否
    if (cursorItem != null && !cursorItem.type.isAir && !KarakuriRemote.isRemote(cursorItem)) {
      player.sendMessage(Component.text("⚠️ この収納にはレッドストーンリモコンのみ保管できます。", NamedTextColor.RED))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
      return false
    }
    return true
  }
}
