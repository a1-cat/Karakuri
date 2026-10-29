package io.oyasai.karakuri.tool

import io.oyasai.karakuri.KarakuriPlugin
import io.oyasai.karakuri.engine.WirelessFrequency
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * 信号送信モード
 */
enum class RemoteSignalMode {
  PULSE,  // パルス式 (1秒/20ticks 給電)
  TOGGLE, // 切り替え式 (再送信または設置型送信機が送信するまで常時ON)
}

/**
 * 手持ちの携帯型ワイヤレスRS信号送信機「レッドストーンリモコン」
 *
 * 見た目: エンチャントのオーラを纏った銅の馬鎧の見た目をした銅インゴット (COPPER_INGOT + item_model)
 * - 左クリック / 右クリックで別々の周波数を登録可能
 * - 送信機または受信機をクリックすることで周波数を書き込み
 *   - 通常クリック: パルス式
 *   - Shift + クリック: 切り替え式 (トグル)
 * - クリック時に半径64ブロック以内の同周波数受信機へ信号をワイヤレス送信
 */
object KarakuriRemote {

  private lateinit var remoteKey: NamespacedKey
  private lateinit var rightFreqKey: NamespacedKey
  private lateinit var rightModeKey: NamespacedKey
  private lateinit var leftFreqKey: NamespacedKey
  private lateinit var leftModeKey: NamespacedKey

  fun init(plugin: KarakuriPlugin) {
    remoteKey = NamespacedKey(plugin, "rs_remote")
    rightFreqKey = NamespacedKey(plugin, "remote_r_freq")
    rightModeKey = NamespacedKey(plugin, "remote_r_mode")
    leftFreqKey = NamespacedKey(plugin, "remote_l_freq")
    leftModeKey = NamespacedKey(plugin, "remote_l_mode")
  }

  fun create(
    rFreq: WirelessFrequency? = null,
    rMode: RemoteSignalMode = RemoteSignalMode.PULSE,
    lFreq: WirelessFrequency? = null,
    lMode: RemoteSignalMode = RemoteSignalMode.PULSE,
  ): ItemStack {
    val item = ItemStack(Material.COPPER_INGOT)
    val meta = item.itemMeta ?: return item

    // 見た目を銅の馬鎧に変更 & エンチャントオーラ付与
    meta.setItemModel(NamespacedKey.minecraft("copper_horse_armor"))
    meta.setEnchantmentGlintOverride(true)
    meta.setMaxStackSize(1)

    meta.persistentDataContainer.set(remoteKey, PersistentDataType.BYTE, 1)
    if (rFreq != null) {
      meta.persistentDataContainer.set(rightFreqKey, PersistentDataType.STRING, rFreq.serialize())
      meta.persistentDataContainer.set(rightModeKey, PersistentDataType.STRING, rMode.name)
    }
    if (lFreq != null) {
      meta.persistentDataContainer.set(leftFreqKey, PersistentDataType.STRING, lFreq.serialize())
      meta.persistentDataContainer.set(leftModeKey, PersistentDataType.STRING, lMode.name)
    }
    item.itemMeta = meta
    updateLore(item)
    return item
  }

  fun isRemote(item: ItemStack?): Boolean {
    if (item == null || item.type.isAir) return false
    if (item.type != Material.COPPER_INGOT && item.type != Material.RECOVERY_COMPASS) return false
    val meta = item.itemMeta ?: return false
    return meta.persistentDataContainer.has(remoteKey, PersistentDataType.BYTE)
  }

  fun getRightFrequency(item: ItemStack?): WirelessFrequency? {
    if (!isRemote(item)) return null
    val meta = item?.itemMeta ?: return null
    val str = meta.persistentDataContainer.get(rightFreqKey, PersistentDataType.STRING) ?: return null
    return WirelessFrequency.deserialize(str)
  }

  fun getRightMode(item: ItemStack?): RemoteSignalMode {
    if (!isRemote(item)) return RemoteSignalMode.PULSE
    val meta = item?.itemMeta ?: return RemoteSignalMode.PULSE
    val modeStr = meta.persistentDataContainer.get(rightModeKey, PersistentDataType.STRING) ?: return RemoteSignalMode.PULSE
    return try {
      RemoteSignalMode.valueOf(modeStr)
    } catch (_: Exception) {
      RemoteSignalMode.PULSE
    }
  }

  fun setRightFrequency(item: ItemStack, freq: WirelessFrequency, mode: RemoteSignalMode = RemoteSignalMode.PULSE) {
    val meta = item.itemMeta ?: return
    meta.persistentDataContainer.set(rightFreqKey, PersistentDataType.STRING, freq.serialize())
    meta.persistentDataContainer.set(rightModeKey, PersistentDataType.STRING, mode.name)
    item.itemMeta = meta
    updateLore(item)
  }

  fun getLeftFrequency(item: ItemStack?): WirelessFrequency? {
    if (!isRemote(item)) return null
    val meta = item?.itemMeta ?: return null
    val str = meta.persistentDataContainer.get(leftFreqKey, PersistentDataType.STRING) ?: return null
    return WirelessFrequency.deserialize(str)
  }

  fun getLeftMode(item: ItemStack?): RemoteSignalMode {
    if (!isRemote(item)) return RemoteSignalMode.PULSE
    val meta = item?.itemMeta ?: return RemoteSignalMode.PULSE
    val modeStr = meta.persistentDataContainer.get(leftModeKey, PersistentDataType.STRING) ?: return RemoteSignalMode.PULSE
    return try {
      RemoteSignalMode.valueOf(modeStr)
    } catch (_: Exception) {
      RemoteSignalMode.PULSE
    }
  }

  fun setLeftFrequency(item: ItemStack, freq: WirelessFrequency, mode: RemoteSignalMode = RemoteSignalMode.PULSE) {
    val meta = item.itemMeta ?: return
    meta.persistentDataContainer.set(leftFreqKey, PersistentDataType.STRING, freq.serialize())
    meta.persistentDataContainer.set(leftModeKey, PersistentDataType.STRING, mode.name)
    item.itemMeta = meta
    updateLore(item)
  }

  fun updateLore(item: ItemStack) {
    val meta = item.itemMeta ?: return
    meta.displayName(
      Component.text("⚡ レッドストーンリモコン", NamedTextColor.RED, TextDecoration.BOLD)
    )

    // 見た目とエンチャントオーラの最新化
    meta.setItemModel(NamespacedKey.minecraft("copper_horse_armor"))
    meta.setEnchantmentGlintOverride(true)
    meta.setMaxStackSize(1)

    val rFreq = getRightFrequency(item)
    val rMode = getRightMode(item)
    val lFreq = getLeftFrequency(item)
    val lMode = getLeftMode(item)

    val lore = mutableListOf<Component>()
    lore.add(Component.text("ポータブルなワイヤレスRS信号送信機", NamedTextColor.GRAY))
    lore.add(Component.text(""))

    val rModeText = if (rMode == RemoteSignalMode.TOGGLE) " (切替式)" else " (パルス式)"
    lore.add(
      Component.text("▶ 右クリック周波数: ", NamedTextColor.GOLD)
        .append(
          if (rFreq != null) {
            Component.text(rFreq.displayName, NamedTextColor.YELLOW, TextDecoration.BOLD)
              .append(Component.text(rModeText, if (rMode == RemoteSignalMode.TOGGLE) NamedTextColor.LIGHT_PURPLE else NamedTextColor.GOLD))
          } else {
            Component.text("【未登録】", NamedTextColor.DARK_GRAY)
          }
        )
    )

    val lModeText = if (lMode == RemoteSignalMode.TOGGLE) " (切替式)" else " (パルス式)"
    lore.add(
      Component.text("▶ 左クリック周波数: ", NamedTextColor.AQUA)
        .append(
          if (lFreq != null) {
            Component.text(lFreq.displayName, NamedTextColor.AQUA, TextDecoration.BOLD)
              .append(Component.text(lModeText, if (lMode == RemoteSignalMode.TOGGLE) NamedTextColor.LIGHT_PURPLE else NamedTextColor.AQUA))
          } else {
            Component.text("【未登録】", NamedTextColor.DARK_GRAY)
          }
        )
    )
    lore.add(Component.text(""))
    lore.add(Component.text("【周波数の登録方法】", NamedTextColor.YELLOW))
    lore.add(Component.text("  送信機/受信機を右クリック → 右送信 [パルス式: 1秒]", NamedTextColor.DARK_GRAY))
    lore.add(Component.text("  Shift + 右クリック → 右送信 [切替式: トグル]", NamedTextColor.DARK_GRAY))
    lore.add(Component.text("  送信機/受信機を左クリック → 左送信 [パルス式: 1秒]", NamedTextColor.DARK_GRAY))
    lore.add(Component.text("  Shift + 左クリック → 左送信 [切替式: トグル]", NamedTextColor.DARK_GRAY))
    lore.add(Component.text("【信号送信】", NamedTextColor.YELLOW))
    lore.add(Component.text("  右/左クリックで半径64m内の同周波数受信機へ送信", NamedTextColor.DARK_GRAY))

    meta.lore(lore)
    item.itemMeta = meta
  }
}
