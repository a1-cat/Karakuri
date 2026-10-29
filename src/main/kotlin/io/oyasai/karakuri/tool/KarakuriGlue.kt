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
 * 建築物を1つのからくりとして結合するための専用ツール『調律師の接着剤』
 * アイテム実体は SLIME_BALL で設置を防止し、
 * item_model="minecraft:sulfur_cube_spawn_egg" でサルファーキューブの見た目を再現。
 */
object KarakuriGlue {

  private lateinit var glueKey: NamespacedKey
  private val playerSelections = ConcurrentHashMap<UUID, GlueSelection>()

  data class GlueSelection(
    var startPoint: Location? = null,
    var endPoint: Location? = null,
  ) {
    val isComplete: Boolean get() = startPoint != null && endPoint != null
  }

  fun init(plugin: Plugin) {
    glueKey = NamespacedKey(plugin, "glue")
  }

  fun getSelection(uuid: UUID): GlueSelection {
    return playerSelections.computeIfAbsent(uuid) { GlueSelection() }
  }

  fun clearSelection(uuid: UUID) {
    playerSelections.remove(uuid)
  }

  fun create(): ItemStack {
    val item = ItemStack(Material.SLIME_BALL)
    val meta = item.itemMeta ?: return item

    meta.displayName(
      Component.text("調律師の接着剤", NamedTextColor.GREEN, TextDecoration.BOLD)
        .append(Component.text(" [Super Glue]", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
    )

    meta.lore(
      listOf(
        Component.text("【建築物接着ツール】", NamedTextColor.YELLOW),
        Component.text("▶ [左クリック] ", NamedTextColor.GOLD).append(Component.text("接着範囲の始点を選択", NamedTextColor.WHITE)),
        Component.text("▶ [右クリック] ", NamedTextColor.GOLD).append(Component.text("接着範囲の終点を選択", NamedTextColor.WHITE)),
        Component.text("▶ [Shift + 右クリック] ", NamedTextColor.AQUA).append(Component.text("選択した接着範囲を作成", NamedTextColor.WHITE)),
        Component.text("▶ [Shift + 左クリック] ", NamedTextColor.RED).append(Component.text("視線先の接着範囲を消去", NamedTextColor.WHITE)),
        Component.text(""),
        Component.text("※複数の接着範囲が重なると合体して1つの建築物になります", NamedTextColor.GRAY),
        Component.text("※接着後、調律師のレンチで右クリックすると丸ごとからくり化！", NamedTextColor.GREEN)
      )
    )

    // サルファーキューブの見た目 (1.21.2+ item_model)
    meta.setItemModel(NamespacedKey.minecraft("sulfur_cube_spawn_egg"))
    // エンチャントの輝き
    meta.setEnchantmentGlintOverride(true)
    meta.setMaxStackSize(1)
    meta.persistentDataContainer.set(glueKey, PersistentDataType.BYTE, 1)

    item.itemMeta = meta
    return item
  }

  fun isGlue(item: ItemStack?): Boolean {
    if (item == null || item.type != Material.SLIME_BALL) return false
    val meta = item.itemMeta ?: return false
    return meta.persistentDataContainer.has(glueKey, PersistentDataType.BYTE)
  }
}
