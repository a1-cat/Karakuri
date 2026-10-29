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
 * からくり操作用専用ツール『調律師のレンチ』
 *
 * 見た目: 銅のクワの見た目をした棒 (STICK + item_model)
 */
object KarakuriWrench {

  private lateinit var wrenchKey: NamespacedKey

  fun init(plugin: Plugin) {
    wrenchKey = NamespacedKey(plugin, "wrench")
  }

  fun create(): ItemStack {
    val item = ItemStack(Material.STICK)
    val meta = item.itemMeta ?: return item

    meta.setItemModel(NamespacedKey.minecraft("copper_hoe"))
    meta.setEnchantmentGlintOverride(true)
    meta.setMaxStackSize(1)

    meta.displayName(
      Component.text("調律師のレンチ", NamedTextColor.GOLD, TextDecoration.BOLD)
        .append(Component.text(" [Tuning Wrench]", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
    )

    meta.lore(
      listOf(
        Component.text("【からくりの調律・設定ツール】", NamedTextColor.YELLOW),
        Component.text("▶ [ブロック右クリック] ", NamedTextColor.GOLD).append(Component.text("単一ブロックをからくりに変換", NamedTextColor.WHITE)),
        Component.text("▶ [接着ブロック右クリック] ", NamedTextColor.GOLD).append(Component.text("接着された建築物を丸ごとからくり化", NamedTextColor.WHITE)),
        Component.text("▶ [からくり右クリック] ", NamedTextColor.GOLD).append(Component.text("設定盤 (GUI) を開く", NamedTextColor.WHITE)),
        Component.text("▶ [からくり左クリック] ", NamedTextColor.AQUA).append(Component.text("そのブロックを中心軸(回転・トロッコ)に設定", NamedTextColor.WHITE)),
        Component.text("▶ [トロッコShift右クリック] ", NamedTextColor.LIGHT_PURPLE).append(Component.text("トロッコ合体モード (解除: Shift左クリック)", NamedTextColor.WHITE)),
        Component.text("▶ [ピストン/鎖Shift右クリック] ", NamedTextColor.GREEN).append(Component.text("子構造・直線運動接続モード", NamedTextColor.WHITE)),
        Component.text("▶ [RS連動/避雷針Shift右クリック] ", NamedTextColor.RED).append(Component.text("RS連動ブロック・ワイヤレス送受信機登録", NamedTextColor.WHITE)),
        Component.text("▶ [連動ブロック左クリック] ", NamedTextColor.DARK_AQUA).append(Component.text("RS連動・ワイヤレス登録を解除 (解除後破壊可能)", NamedTextColor.WHITE)),
        Component.text(""),
        Component.text("建築物を命あるからくりへと昇華させる万能レンチ", NamedTextColor.DARK_GRAY)
      )
    )

    meta.persistentDataContainer.set(wrenchKey, PersistentDataType.BYTE, 1)
    item.itemMeta = meta
    return item
  }

  fun isWrench(item: ItemStack?): Boolean {
    if (item == null || item.type.isAir) return false
    if (item.type != Material.STICK && item.type != Material.COPPER_HOE) return false
    val meta = item.itemMeta ?: return false
    return meta.persistentDataContainer.has(wrenchKey, PersistentDataType.BYTE)
  }
}
