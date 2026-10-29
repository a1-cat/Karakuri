package io.oyasai.karakuri.gui

import io.oyasai.karakuri.tool.KarakuriGlue
import io.oyasai.karakuri.tool.KarakuriPistonRod
import io.oyasai.karakuri.tool.KarakuriToolbox
import io.oyasai.karakuri.tool.KarakuriWrench
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
 * 調律師の道具（レンチ・ピストンロッド・接着剤・工具箱）を取り出し・破棄できる専用GUI
 */
object KarakuriToolboxGUI {

  const val TITLE = "調律師の工具箱"

  fun open(player: Player) {
    val inv = Bukkit.createInventory(null, 27, Component.text(TITLE, NamedTextColor.GOLD, TextDecoration.BOLD))

    val border = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
      itemMeta = itemMeta?.apply { displayName(Component.text(" ")) }
    }
    for (i in 0 until 27) {
      inv.setItem(i, border)
    }

    // 4: レッドストーンリモコン
    val remote = io.oyasai.karakuri.tool.KarakuriRemote.create()
    val remoteMeta = remote.itemMeta
    remoteMeta?.lore(
      listOf(
        Component.text("クリックしてインベントリに入手", NamedTextColor.YELLOW),
        Component.text("送信機/受信機に周波数を登録して信号を送信できる携帯リモコン", NamedTextColor.GRAY),
        Component.text(""),
        Component.text("※ 工具箱を【Shift + 右クリック】すると「リモコン専用収納」を開けます", NamedTextColor.GOLD)
      )
    )
    remote.itemMeta = remoteMeta
    inv.setItem(4, remote)

    // 10: 調律師の工具箱（中央均等配置: 10, 12, 14, 16）
    val toolbox = KarakuriToolbox.create()
    val toolboxMeta = toolbox.itemMeta
    toolboxMeta.lore(
      listOf(
        Component.text("クリックしてインベントリに入手", NamedTextColor.YELLOW),
        Component.text("いつでもどこでも右クリックで工具箱GUIを開ける専用アイテム", NamedTextColor.GRAY)
      )
    )
    toolbox.itemMeta = toolboxMeta
    inv.setItem(10, toolbox)

    // 12: 調律師のレンチ
    val wrench = KarakuriWrench.create()
    val wrenchMeta = wrench.itemMeta
    wrenchMeta.lore(
      listOf(
        Component.text("クリックしてインベントリに入手", NamedTextColor.YELLOW),
        Component.text("からくりの変換・設定・回転軸の指定を行う万能レンチ", NamedTextColor.GRAY)
      )
    )
    wrench.itemMeta = wrenchMeta
    inv.setItem(12, wrench)

    // 14: 調律師のピストンロッド
    val rod = KarakuriPistonRod.create()
    val rodMeta = rod.itemMeta
    rodMeta.lore(
      listOf(
        Component.text("クリックしてインベントリに入手", NamedTextColor.YELLOW),
        Component.text("直線運動（エレベーター・往復）を設定するロッド", NamedTextColor.GRAY)
      )
    )
    rod.itemMeta = rodMeta
    inv.setItem(14, rod)

    // 16: 調律師の接着剤
    val glue = KarakuriGlue.create()
    val glueMeta = glue.itemMeta
    glueMeta.lore(
      listOf(
        Component.text("クリックしてインベントリに入手", NamedTextColor.YELLOW),
        Component.text("建築物を丸ごと接着して1つのからくりにする超粘着剤", NamedTextColor.GRAY)
      )
    )
    glue.itemMeta = glueMeta
    inv.setItem(16, glue)

    // 19: トロッコ手押しモード切替 (監獄風の装飾の鍛冶型: WARD_ARMOR_TRIM_SMITHING_TEMPLATE)
    val isPush = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager.isPushModeEnabled(player)
    val pushItem = ItemStack(Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE).apply {
      itemMeta = itemMeta?.apply {
        displayName(
          Component.text(
            "🛒 トロッコ手押しモード: " + if (isPush) "【有効】" else "【無効】",
            if (isPush) NamedTextColor.GREEN else NamedTextColor.RED,
            TextDecoration.BOLD
          )
        )
        lore(
          listOf(
            Component.text("有効化すると、素手で【Shift + 左クリック】でトロッコを殴った際に、", NamedTextColor.GRAY),
            Component.text("プレイヤーの視線の方向へトロッコを押し出すことができます。", NamedTextColor.GRAY),
            Component.text("（からくり化レール上のトロッコや連結列車編成にも対応）", NamedTextColor.DARK_GRAY),
            Component.text(""),
            Component.text("現在の設定: " + if (isPush) "ON (手押し有効)" else "OFF (手押し無効)", if (isPush) NamedTextColor.GREEN else NamedTextColor.RED),
            Component.text("コマンド: /k push (/k cp, /karakuri push)", NamedTextColor.DARK_GRAY),
            Component.text(""),
            Component.text("▶ [クリックしてON/OFFを切替]", NamedTextColor.YELLOW)
          )
        )
      }
    }
    inv.setItem(19, pushItem)

    // 21: 一括ゴミ箱（手持ちの道具を全整理）
    val clearItem = ItemStack(Material.LAVA_BUCKET)
    val clearMeta = clearItem.itemMeta
    clearMeta.displayName(Component.text("🗑️ 手持ちの調律師ツールを一括整理・破棄", NamedTextColor.RED, TextDecoration.BOLD))
    clearMeta.lore(
      listOf(
        Component.text("インベントリ内にある調律師のレンチ・ロッド・接着剤を", NamedTextColor.GRAY),
        Component.text("すべて綺麗に消去します。（工具箱自体は残ります）", NamedTextColor.GRAY),
        Component.text(""),
        Component.text("[クリックして実行]", NamedTextColor.RED)
      )
    )
    clearItem.itemMeta = clearMeta
    inv.setItem(21, clearItem)

    // 22: 設定 (コマンドGUI) へ切り替え (ネジ止め風の装飾の鍛冶型: BOLT_ARMOR_TRIM_SMITHING_TEMPLATE)
    val commandGuiItem = ItemStack(Material.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE)
    val commandGuiMeta = commandGuiItem.itemMeta
    commandGuiMeta?.displayName(Component.text("⚙️ 設定", NamedTextColor.AQUA, TextDecoration.BOLD))
    commandGuiMeta?.lore(
      listOf(
        Component.text("脱出・範囲消去・ゴースト・視点公転追従切替・手押しモードなどの", NamedTextColor.GRAY),
        Component.text("からくりに関する各種設定・便利操作を実行できるGUIを開きます。", NamedTextColor.GRAY),
        Component.text("（工具箱を左クリックした時と同じ画面です）", NamedTextColor.DARK_GRAY),
        Component.text(""),
        Component.text("▶ [クリックして設定画面を開く]", NamedTextColor.YELLOW)
      )
    )
    commandGuiItem.itemMeta = commandGuiMeta
    inv.setItem(22, commandGuiItem)

    // 23: 個別ゴミ箱スロット
    val trashSlot = ItemStack(Material.CAULDRON)
    val trashMeta = trashSlot.itemMeta
    trashMeta.displayName(Component.text("🗑️ アイテム個別破棄スロット", NamedTextColor.GOLD, TextDecoration.BOLD))
    trashMeta.lore(
      listOf(
        Component.text("消去したいアイテムを持ってここをクリックすると消滅します。", NamedTextColor.GRAY),
        Component.text("自分の持ち物を【Shift+クリック】しても即座に破棄できます。", NamedTextColor.YELLOW)
      )
    )
    trashSlot.itemMeta = trashMeta
    inv.setItem(23, trashSlot)

    // 26: 閉じる
    val closeItem = ItemStack(Material.BARRIER)
    val closeMeta = closeItem.itemMeta
    closeMeta.displayName(Component.text("✖ 閉じる", NamedTextColor.RED, TextDecoration.BOLD))
    closeItem.itemMeta = closeMeta
    inv.setItem(26, closeItem)

    player.openInventory(inv)
    player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.8f, 1.2f)
  }

  fun handleClick(player: Player, slot: Int, clickedItem: ItemStack?, cursorItem: ItemStack?): Boolean {
    // 23: ゴミ箱スロット（カーソルにアイテムがある場合、破棄）
    if (slot == 23) {
      if (cursorItem != null && !cursorItem.type.isAir) {
        player.setItemOnCursor(null)
        player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 0.8f, 1.2f)
        player.sendActionBar(Component.text("🗑️ アイテムを破棄しました", NamedTextColor.RED))
      }
      return true
    }

    when (slot) {
      4 -> { // リモコン入手
        player.inventory.addItem(io.oyasai.karakuri.tool.KarakuriRemote.create())
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f)
        player.sendActionBar(Component.text("✔ レッドストーンリモコンを入手しました", NamedTextColor.RED))
      }
      10 -> { // 工具箱入手
        player.inventory.addItem(KarakuriToolbox.create())
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f)
        player.sendActionBar(Component.text("✔ 調律師の工具箱を入手しました", NamedTextColor.GOLD))
      }
      12 -> { // レンチ入手
        player.inventory.addItem(KarakuriWrench.create())
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f)
        player.sendActionBar(Component.text("✔ 調律師のレンチを入手しました", NamedTextColor.GOLD))
      }
      14 -> { // ロッド入手
        player.inventory.addItem(KarakuriPistonRod.create())
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f)
        player.sendActionBar(Component.text("✔ 調律師のピストンロッドを入手しました", NamedTextColor.AQUA))
      }
      16 -> { // 接着剤入手
        player.inventory.addItem(KarakuriGlue.create())
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f)
        player.sendActionBar(Component.text("✔ 調律師の接着剤を入手しました", NamedTextColor.GREEN))
      }
      19 -> { // トロッコ手押しモード切替
        val manager = io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager
        val next = manager.togglePushMode(player)
        if (next) {
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f)
          player.sendActionBar(Component.text("🛒 トロッコ手押しモードを【有効】にしました (素手+Shift+左クリックで推進)", NamedTextColor.GREEN))
        } else {
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f)
          player.sendActionBar(Component.text("🛒 トロッコ手押しモードを【無効】にしました", NamedTextColor.RED))
        }
        open(player)
      }
      21 -> { // 一括ツール破棄
        var removedCount = 0
        val contents = player.inventory.contents
        for (i in contents.indices) {
          val item = contents[i] ?: continue
          if (KarakuriWrench.isWrench(item) || KarakuriPistonRod.isRod(item) || KarakuriGlue.isGlue(item)) {
            player.inventory.setItem(i, null)
            removedCount++
          }
        }
        if (removedCount > 0) {
          player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f)
          player.sendActionBar(Component.text("🗑️ 手持ちの調律師ツール (${removedCount}個) を整理・破棄しました", NamedTextColor.YELLOW))
        } else {
          player.sendActionBar(Component.text("インベントリに破棄対象の道具がありませんでした", NamedTextColor.GRAY))
        }
      }
      22 -> { // 便利ツール盤を開く
        KarakuriCommandGUI.open(player)
      }
      26 -> {
        player.closeInventory()
      }
    }
    return true
  }
}
