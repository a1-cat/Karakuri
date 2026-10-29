package io.oyasai.karakuri.tool

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * エレベーター・スライドドア等の直線運動専用ツール『からくりのピストンロッド』
 * アイテム実体は STICK で設置を防止し、item_model="minecraft:lightning_rod" で避雷針の見た目を再現。
 * 左クリックで始点A、右クリックで終点B、Shift+左クリックでからくりにライン適用。
 */
object KarakuriPistonRod {

  private lateinit var rodKey: NamespacedKey
  private val playerLines = ConcurrentHashMap<UUID, RodLine>()

  data class RodLine(
    var startPoint: Location? = null,
    var endPoint: Location? = null,
  ) {
    val isComplete: Boolean get() = startPoint != null && endPoint != null
    val dx: Double get() = if (isComplete) (endPoint!!.blockX - startPoint!!.blockX).toDouble() else 0.0
    val dy: Double get() = if (isComplete) (endPoint!!.blockY - startPoint!!.blockY).toDouble() else 0.0
    val dz: Double get() = if (isComplete) (endPoint!!.blockZ - startPoint!!.blockZ).toDouble() else 0.0
    val distance: Double get() = if (isComplete) kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) else 0.0
  }

  fun init(plugin: Plugin) {
    rodKey = NamespacedKey(plugin, "piston_rod")
  }

  fun getLine(uuid: UUID): RodLine {
    return playerLines.computeIfAbsent(uuid) { RodLine() }
  }

  fun create(): ItemStack {
    val item = ItemStack(Material.STICK)
    val meta = item.itemMeta ?: return item

    meta.displayName(
      Component.text("調律師のピストンロッド", NamedTextColor.AQUA, TextDecoration.BOLD)
        .append(Component.text(" [Tuning Piston Rod]", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
    )

    meta.lore(
      listOf(
        Component.text("【直感直線運動ツール】", NamedTextColor.YELLOW),
        Component.text("▶ [ブロック左クリック] ", NamedTextColor.GOLD).append(Component.text("始点 (Point A) を指定", NamedTextColor.WHITE)),
        Component.text("▶ [ブロック右クリック] ", NamedTextColor.GOLD).append(Component.text("終点 (Point B) を指定", NamedTextColor.WHITE)),
        Component.text("▶ [Shift+左クリック] ", NamedTextColor.GOLD).append(Component.text("からくりに測定ラインを適用！ (プログラム時はステップ選択)", NamedTextColor.AQUA)),
        Component.text("▶ [Shift+右クリック] ", NamedTextColor.GOLD).append(Component.text("直線運動設定盤 (GUI) を開く", NamedTextColor.WHITE)),
        Component.text(""),
        Component.text("始点と終点をクリックするだけで移動ラインを自在に設計", NamedTextColor.DARK_GRAY)
      )
    )

    // 見た目を避雷針に変更 (1.20.5+ / 1.21+ item_model)
    meta.setItemModel(NamespacedKey.minecraft("lightning_rod"))
    // エンチャントの光を付与
    meta.setEnchantmentGlintOverride(true)
    meta.setMaxStackSize(1)

    meta.persistentDataContainer.set(rodKey, PersistentDataType.BYTE, 1)
    item.itemMeta = meta
    return item
  }

  fun isRod(item: ItemStack?): Boolean {
    if (item == null || item.type != Material.STICK) return false
    val meta = item.itemMeta ?: return false
    return meta.persistentDataContainer.has(rodKey, PersistentDataType.BYTE)
  }
}
