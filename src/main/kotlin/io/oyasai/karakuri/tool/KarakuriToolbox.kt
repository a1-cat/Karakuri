package io.oyasai.karakuri.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * 調律師の道具一式を取り出し・整理するための専用アイテム『調律師の工具箱』
 *
 * 見た目: 銅のオウムガイの鎧の見た目をした銅インゴット (COPPER_INGOT + item_model)
 */
object KarakuriToolbox {

  private lateinit var toolboxKey: NamespacedKey

  fun init(plugin: Plugin) {
    toolboxKey = NamespacedKey(plugin, "toolbox")
  }

  fun create(): ItemStack {
    val item = ItemStack(Material.COPPER_INGOT)
    val meta = item.itemMeta ?: return item

    meta.setItemModel(NamespacedKey.minecraft("copper_nautilus_armor"))
    meta.setEnchantmentGlintOverride(true)
    meta.setMaxStackSize(1)

    meta.displayName(
      Component.text("調律師の工具箱", NamedTextColor.GOLD, TextDecoration.BOLD)
        .append(Component.text(" [Tuner's Toolbox]", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
    )

    meta.lore(
      listOf(
        Component.text("【調律師の万能ツールボックス】", NamedTextColor.YELLOW),
        Component.text("▶ [左クリック] ", NamedTextColor.AQUA).append(Component.text("設定 (GUI) を開く", NamedTextColor.WHITE)),
        Component.text("▶ [右クリック] ", NamedTextColor.GOLD).append(Component.text("工具箱 (GUI) を開く", NamedTextColor.WHITE)),
        Component.text("▶ [Shift + 右クリック] ", NamedTextColor.LIGHT_PURPLE).append(Component.text("リモコン専用収納 (GUI) を開く", NamedTextColor.WHITE)),
        Component.text(""),
        Component.text("左クリック: 脱出・範囲消去・ゴースト・視点追従切替・手押しなどの設定GUI", NamedTextColor.GRAY),
        Component.text("右クリック: レンチ・ロッド・接着剤・リモコンの取り出しと破棄", NamedTextColor.GRAY),
        Component.text("Shift右クリック: レッドストーンリモコンを安全に保管・整理する専用BOX", NamedTextColor.GRAY)
      )
    )

    meta.persistentDataContainer.set(toolboxKey, PersistentDataType.BYTE, 1)

    item.itemMeta = meta
    return item
  }

  fun isToolbox(item: ItemStack?): Boolean {
    if (item == null || item.type.isAir) return false
    if (item.type != Material.COPPER_INGOT && item.type != Material.COPPER_NAUTILUS_ARMOR) return false
    val meta = item.itemMeta ?: return false
    return meta.persistentDataContainer.has(toolboxKey, PersistentDataType.BYTE)
  }
}
