package io.oyasai.karakuri.gui

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.motion.Axis
import io.oyasai.karakuri.motion.ChainChildContraptionMotion
import io.oyasai.karakuri.motion.ChildContraptionMotion
import io.oyasai.karakuri.motion.ContinuousSpinMotion
import io.oyasai.karakuri.motion.DoorMotion
import io.oyasai.karakuri.motion.IntegratedLinearMotion
import io.oyasai.karakuri.motion.ProgramMotion
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.Vector3f

/**
 * からくり直感設定 GUI（メイン画面・27スロット）
 * 上段: 回転速度・方向などの細かい設定
 * 中段: からくりの動作モード設定（ドア、風車、水車、回転台、直線、プログラム）
 * 下段: 制御・リセット・停止・撤去
 */
class KarakuriGUI(val instance: KarakuriInstance) {

  companion object {
    const val GUI_TITLE_PREFIX = "からくり設定盤 ["

    val RPM_PRESETS = listOf(1f, 2f, 4f, 8f, 16f, 32f, 64f, 128f, 256f)
  }

  fun open(player: Player) {
    val title = Component.text("$GUI_TITLE_PREFIX${instance.id}]", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
    val inv = Bukkit.createInventory(player, 27, title)

    val grayGlass = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
    for (i in 0 until 27) inv.setItem(i, grayGlass)

    val activeMotion = instance.activeMotion

    // 現在の RPM の取得（Spin または Door から）
    val curRpm = when (activeMotion) {
      is ContinuousSpinMotion -> activeMotion.rpm
      is DoorMotion -> activeMotion.rpm
      else -> 16f
    }

    // ==========================================
    // 上段 (行1: スロット 0〜8): 回転速度・回転設定
    // ==========================================

    // 0: ドア自動クローズ ON/OFF 切替
    val isAutoClose = (activeMotion as? DoorMotion)?.autoCloseTicks ?: 60 > 0
    inv.setItem(
      0,
      createItem(
        if (isAutoClose) Material.CLOCK else Material.GLASS_BOTTLE,
        Component.text("⏱️ 自動クローズ: " + if (isAutoClose) "ON (3秒後)" else "OFF (手動のみ)", NamedTextColor.AQUA, TextDecoration.BOLD),
        listOf(
          Component.text("ドアが開いた後、自動で閉じるかを設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックして切替]", NamedTextColor.YELLOW)
        )
      )
    )

    // 1: -50 RPM
    inv.setItem(
      1,
      createItem(
        Material.RED_TERRACOTTA,
        Component.text("⏪⏪ -50 RPM", NamedTextColor.DARK_RED, TextDecoration.BOLD),
        listOf(Component.text("回転速度を 50 RPM 大幅減速します", NamedTextColor.GRAY))
      )
    )

    // 2: -10 RPM
    inv.setItem(
      2,
      createItem(
        Material.RED_CONCRETE,
        Component.text("⏪ -10 RPM", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(Component.text("回転速度を 10 RPM 減速します", NamedTextColor.GRAY))
      )
    )

    // 3: -1 RPM
    inv.setItem(
      3,
      createItem(
        Material.ORANGE_CONCRETE,
        Component.text("◀ -1 RPM", NamedTextColor.GOLD, TextDecoration.BOLD),
        listOf(Component.text("回転速度を 1 RPM 減速します", NamedTextColor.GRAY))
      )
    )

    // 4: 速度メインアイコン (クリックでプリセット切替)
    inv.setItem(
      4,
      createItem(
        Material.BLAZE_POWDER,
        Component.text("⚡ 回転速度: ${curRpm.toInt()} RPM", NamedTextColor.YELLOW, TextDecoration.BOLD),
        listOf(
          Component.text("秒間回転角: 約 ${(curRpm * 6.0f).toInt()} °/秒", NamedTextColor.WHITE),
          Component.text("ドア開閉速度: 約 ${String.format("%.2f", 90.0f / (curRpm * 6.0f).coerceAtLeast(1.0f))} 秒", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("プリセット: 1 <-> 2 <-> 4 <-> 8 <-> 16 <-> 32 <-> 64 <-> 128 <-> 256", NamedTextColor.GRAY),
          Component.text("• [左クリック]: 速度アップ", NamedTextColor.YELLOW),
          Component.text("• [右クリック]: 速度ダウン", NamedTextColor.GOLD)
        )
      )
    )

    // 5: +1 RPM
    inv.setItem(
      5,
      createItem(
        Material.LIME_CONCRETE,
        Component.text("▶ +1 RPM", NamedTextColor.GREEN, TextDecoration.BOLD),
        listOf(Component.text("回転速度を 1 RPM 加速します", NamedTextColor.GRAY))
      )
    )

    // 6: +10 RPM
    inv.setItem(
      6,
      createItem(
        Material.GREEN_CONCRETE,
        Component.text("⏩ +10 RPM", NamedTextColor.DARK_GREEN, TextDecoration.BOLD),
        listOf(Component.text("回転速度を 10 RPM 加速します", NamedTextColor.GRAY))
      )
    )

    // 7: +50 RPM
    inv.setItem(
      7,
      createItem(
        Material.EMERALD_BLOCK,
        Component.text("⏩⏩ +50 RPM", NamedTextColor.GREEN, TextDecoration.BOLD),
        listOf(Component.text("回転速度を 50 RPM 大幅加速します", NamedTextColor.GRAY))
      )
    )

    // 8: 回転方向反転 (CW / CCW)
    inv.setItem(
      8,
      createItem(
        Material.ECHO_SHARD,
        Component.text("🔁 回転方向切替 (時計回り / 反時計回り)", NamedTextColor.AQUA, TextDecoration.BOLD),
        listOf(
          Component.text("回転方向を瞬時に反転します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックして反転]", NamedTextColor.GOLD)
        )
      )
    )

    // ==========================================
    // 中段 (行2: スロット 9〜17): 動作モード設定（真ん中！）
    // ==========================================

    // 10: ドアモード
    inv.setItem(
      10,
      createItem(
        Material.OAK_DOOR,
        Component.text("🚪 ドアモード", NamedTextColor.GOLD, TextDecoration.BOLD),
        listOf(
          Component.text("中心を軸に90度スムーズ開閉します。", NamedTextColor.GRAY),
          Component.text("開閉速度は上の RPM 設定に完全連動！", NamedTextColor.YELLOW),
          Component.text(""),
          Component.text("[クリックして適用]", NamedTextColor.GREEN)
        ),
        isGlowing = activeMotion is DoorMotion
      )
    )

    // 11: 風車モード (Spin Z)
    inv.setItem(
      11,
      createItem(
        Material.WHITE_WOOL,
        Component.text("🔄 風車モード (Z軸回転)", NamedTextColor.WHITE, TextDecoration.BOLD),
        listOf(
          Component.text("Z軸（南北方向）を中心軸にして縦に回転します。", NamedTextColor.GRAY),
          Component.text("※視点ではなくワールド座標のZ軸基準で回転します。", NamedTextColor.DARK_GRAY),
          Component.text(""),
          Component.text("[クリックして適用]", NamedTextColor.GREEN)
        ),
        isGlowing = (activeMotion as? ContinuousSpinMotion)?.axis == Axis.Z
      )
    )

    // 12: 水車モード (Spin X)
    inv.setItem(
      12,
      createItem(
        Material.WATER_BUCKET,
        Component.text("🎡 水車モード (X軸回転)", NamedTextColor.BLUE, TextDecoration.BOLD),
        listOf(
          Component.text("X軸（東西方向）を中心軸にして縦に回転します。", NamedTextColor.GRAY),
          Component.text("※視点ではなくワールド座標のX軸基準で回転します。", NamedTextColor.DARK_GRAY),
          Component.text(""),
          Component.text("[クリックして適用]", NamedTextColor.GREEN)
        ),
        isGlowing = (activeMotion as? ContinuousSpinMotion)?.axis == Axis.X
      )
    )

    // 13: 水平回転台 (Spin Y)
    inv.setItem(
      13,
      createItem(
        Material.COMPASS,
        Component.text("🎠 水平回転台 (Y軸回転)", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
        listOf(
          Component.text("Y軸（上下方向）を中心軸にして水平に回転します。", NamedTextColor.GRAY),
          Component.text("※ワールド座標のY軸基準で回転します。", NamedTextColor.DARK_GRAY),
          Component.text(""),
          Component.text("[クリックして適用]", NamedTextColor.GREEN)
        ),
        isGlowing = (activeMotion as? ContinuousSpinMotion)?.axis == Axis.Y
      )
    )

    // 14: トロッコ連動設定盤 または トロッコ合体モード
    val minecartMotion = activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
    if (minecartMotion != null) {
      val isFollow = minecartMotion.followRotation
      val faceName = when (minecartMotion.forwardFacing) {
        org.bukkit.block.BlockFace.NORTH -> "北"
        org.bukkit.block.BlockFace.EAST -> "東"
        org.bukkit.block.BlockFace.SOUTH -> "南"
        org.bukkit.block.BlockFace.WEST -> "西"
        else -> minecartMotion.forwardFacing.name
      }
      val spinName = when (minecartMotion.spinAxis) {
        null -> "なし"
        io.oyasai.karakuri.motion.Axis.Y -> "Y軸"
        io.oyasai.karakuri.motion.Axis.X -> "X軸"
        io.oyasai.karakuri.motion.Axis.Z -> "Z軸"
      }
      inv.setItem(
        14,
        createItem(
          Material.MINECART,
          Component.text("🚂 トロッコ連動設定盤を開く", NamedTextColor.GOLD, TextDecoration.BOLD),
          listOf(
            Component.text("トロッコ走行時の連動・向き・自転回転を設定します。", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("進行方向連動: " + if (isFollow) "ON (追従)" else "OFF (固定)", NamedTextColor.WHITE),
            Component.text("からくりの前側(正面): $faceName", NamedTextColor.AQUA),
            Component.text("連続自転: $spinName (${String.format("%.1f", minecartMotion.rpm)} RPM)", NamedTextColor.LIGHT_PURPLE),
            Component.text(""),
            Component.text("▶ [クリックしてトロッコ設定盤を開く]", NamedTextColor.GREEN)
          ),
          isGlowing = true
        )
      )
    } else {
      inv.setItem(
        14,
        createItem(
          Material.MINECART,
          Component.text("🚂 トロッコ合体モード (乗せる)", NamedTextColor.GOLD, TextDecoration.BOLD),
          listOf(
            Component.text("このからくりをトロッコに乗せるモードを開始します。", NamedTextColor.GRAY),
            Component.text("からくりの中心軸（回転軸）がトロッコの中央に乗ります。", NamedTextColor.WHITE),
            Component.text(""),
            Component.text("▶ [クリックしてモード開始]", NamedTextColor.YELLOW),
            Component.text("（クリック後、乗せたいトロッコを右クリック）", NamedTextColor.DARK_GRAY)
          )
        )
      )
    }

    // 15: 直線運動設定盤を開く
    inv.setItem(
      15,
      createItem(
        Material.LIGHTNING_ROD,
        Component.text("↕️ 直線運動 (エレベーター/スライドドア)", NamedTextColor.AQUA, TextDecoration.BOLD),
        listOf(
          Component.text("2地点間をスムーズに直線移動します。（斜め移動対応）", NamedTextColor.GRAY),
          Component.text("ピストンロッドで始点・終点を指定することも可能です。", NamedTextColor.YELLOW),
          Component.text(""),
          Component.text("[クリックして直線専用設定盤を開く]", NamedTextColor.GREEN)
        ),
        isGlowing = activeMotion is IntegratedLinearMotion
      )
    )

    // 16: プログラム編集盤を開く
    inv.setItem(
      16,
      createItem(
        Material.REPEATER,
        Component.text("🧩 7ステッププログラム編集盤", NamedTextColor.GOLD, TextDecoration.BOLD),
        listOf(
          Component.text("7ステップの動作を自由に設計・リバース往復実行します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックしてプログラム編集盤を開く]", NamedTextColor.YELLOW)
        ),
        isGlowing = activeMotion is ProgramMotion
      )
    )

    // ==========================================
    // 下段 (行3: スロット 18〜26): 制御・管理
    // ==========================================

    // 18: RS連動ブロック管理
    val rsLinks = instance.redstoneLinks
    val rsLore = mutableListOf<Component>()
    rsLore.add(Component.text("中心軸64m以内のレッドストーン連動ブロックを管理します。", NamedTextColor.GRAY))
    rsLore.add(Component.text(""))
    if (rsLinks.isEmpty()) {
      rsLore.add(Component.text("登録中のブロック: なし", NamedTextColor.DARK_GRAY))
      rsLore.add(Component.text("※調律師のレンチを持ってRS連動ブロック(ランプ/銅電球/オブザーバー)を", NamedTextColor.GRAY))
      rsLore.add(Component.text("  Shift+右クリックすると登録モードを開始できます！", NamedTextColor.YELLOW))
    } else {
      rsLore.add(Component.text("登録中のブロック (${rsLinks.size}件):", NamedTextColor.YELLOW))
      rsLinks.take(6).forEach { link ->
        val typeName = io.oyasai.karakuri.engine.RedstoneLinkManager.getTypeName(link.type)
        val pos = "(${link.blockLocation.blockX}, ${link.blockLocation.blockY}, ${link.blockLocation.blockZ})"
        val state = if (io.oyasai.karakuri.engine.RedstoneLinkManager.isCopperBulbType(link.type)) {
          if (link.lastLitState) {
            if (link.type == io.oyasai.karakuri.engine.RedstoneLinkManager.RedstoneLinkType.COPPER_BULB_REVERSE) " [点灯/逆再生中]" else " [点灯/反転中]"
          } else " [消灯]"
        } else ""
        rsLore.add(Component.text(" • $typeName $pos$state", NamedTextColor.AQUA))
      }
      if (rsLinks.size > 6) {
        rsLore.add(Component.text(" ... 他 ${rsLinks.size - 6} 件", NamedTextColor.GRAY))
      }
      rsLore.add(Component.text(""))
      rsLore.add(Component.text("▶ [クリック]: 登録中の全RS連動ブロックを一括解除", NamedTextColor.RED))
    }
    inv.setItem(
      18,
      createItem(
        if (rsLinks.isNotEmpty()) Material.REDSTONE_TORCH else Material.REDSTONE,
        Component.text("⚡ RS連動ブロック管理 (${rsLinks.size}件)", NamedTextColor.RED, TextDecoration.BOLD),
        rsLore,
        isGlowing = rsLinks.isNotEmpty()
      )
    )

    // 19: 初期位置にリセット
    inv.setItem(
      19,
      createItem(
        Material.RECOVERY_COMPASS,
        Component.text("⏮️ 初期位置にリセット", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
        listOf(
          Component.text("からくりを初期の設置位置・姿勢に即座に戻します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("[クリックしてリセット]", NamedTextColor.YELLOW)
        )
      )
    )

    // 20: 不動 (Shift右クリック手動作動のロック切替)
    val isImmovable = instance.isImmovable
    inv.setItem(
      20,
      createItem(
        if (isImmovable) Material.BEDROCK else Material.LEVER,
        Component.text(
          "🗿 不動 (手動作動ロック): " + if (isImmovable) "ON (不動)" else "OFF (可動)",
          if (isImmovable) NamedTextColor.RED else NamedTextColor.GREEN,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("Shift+右クリックで動作させる機能のオンオフを切り替えます。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在の状態: " + if (isImmovable) "ON (手動作動をロック/動作しない)" else "OFF (手動作動を許可/動作する)", if (isImmovable) NamedTextColor.RED else NamedTextColor.GREEN),
          Component.text(""),
          Component.text("[クリックして切替]", NamedTextColor.YELLOW)
        ),
        isGlowing = isImmovable
      )
    )

    val isNonFull = instance.optimizeNonFullBlocks

    // 21: 手動作動 / トグル
    inv.setItem(
      21,
      createItem(
        Material.LIME_DYE,
        Component.text("▶ 今すぐ動かす / トグル", NamedTextColor.GREEN, TextDecoration.BOLD),
        listOf(Component.text("現在の設定で動作を手動実行します。", NamedTextColor.GRAY))
      )
    )

    // 22: トロッコから降ろす (分離)
    if (minecartMotion != null) {
      inv.setItem(
        22,
        createItem(
          Material.SHEARS,
          Component.text("⛓️ トロッコから降ろす (分離)", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
          listOf(
            Component.text("からくりをトロッコから切り離し、現在の場所に独立設置します。", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("▶ [クリックして分離]", NamedTextColor.RED)
          )
        )
      )
    }

    // 23: 停止
    inv.setItem(
      23,
      createItem(
        Material.BARRIER,
        Component.text("⏹ 動作停止", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(Component.text("からくりの動きを一時停止します。", NamedTextColor.GRAY))
      )
    )

    // 24: エンティティ軽量化 (ブロック統合)
    val isOptimized = instance.isOptimized
    val totalOriginalBlocks = if (instance.constituentBlocks.isNotEmpty()) instance.constituentBlocks.size else (1 + instance.additionalParts.size)
    val currentEntityCount = 1 + instance.additionalParts.size
    inv.setItem(
      24,
      createItem(
        if (isOptimized) Material.SLIME_BLOCK else Material.BRICKS,
        Component.text(
          "🧱 エンティティ軽量化 (ブロック統合): " + if (isOptimized) "ON (有効)" else "OFF (無効)",
          if (isOptimized) NamedTextColor.GREEN else NamedTextColor.GOLD,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("隣接する同種ブロックを直方体に統合し、", NamedTextColor.GRAY),
          Component.text("エンティティ数・描画負荷・通信パケットを大幅削減します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在のエンティティ数: ${currentEntityCount} 体 (元: ${totalOriginalBlocks} ブロック)", NamedTextColor.YELLOW),
          Component.text("統合対象: " + if (isNonFull) "すべてのブロック (非フル含む)" else "フルブロックのみ", NamedTextColor.WHITE),
          Component.text(""),
          Component.text(if (isOptimized) "【左クリック】: 個別ブロック(OFF)に展開" else "【左クリック】: 直方体統合(ON)を実行", NamedTextColor.AQUA),
          Component.text("【右クリック】: 非フルブロック統合のON/OFF切替", NamedTextColor.LIGHT_PURPLE)
        ),
        isGlowing = isOptimized
      )
    )

    // 25: 撤去してブロックに戻す
    inv.setItem(
      25,
      createItem(
        Material.LAVA_BUCKET,
        Component.text("🗑 撤去してブロックに戻す", NamedTextColor.DARK_RED, TextDecoration.BOLD),
        if (minecartMotion != null) {
          listOf(
            Component.text("トロッコが乗っている現在位置に新しくブロックを設置し、", NamedTextColor.YELLOW),
            Component.text("からくりエンティティを撤去・ブロック化します。", NamedTextColor.GRAY),
            Component.text(""),
            Component.text("▶ [クリックしてブロック化]", NamedTextColor.RED)
          )
        } else {
          listOf(Component.text("からくりを削除し、通常ブロックに戻します。", NamedTextColor.GRAY))
        }
      )
    )

    // トロッコ搭乗中の場合、関係のない動作モードや設定項目をすべて無効化（赤い板ガラス）
    if (minecartMotion != null) {
      val disabledItem = createItem(
        Material.RED_STAINED_GLASS_PANE,
        Component.text("🚫 トロッコ搭乗中は選択不可", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(
          Component.text("このからくりはトロッコに合体しています。", NamedTextColor.RED),
          Component.text("トロッコ合体中は他の動作モードやリセットは行えません。", NamedTextColor.GRAY),
          Component.text("設定は中央の「🚂 トロッコ連動設定盤」で行うか、", NamedTextColor.YELLOW),
          Component.text("トロッコから降ろしてから再設定してください。", NamedTextColor.GRAY)
        )
      )
      for (s in listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 15, 16, 19, 21, 23)) {
        inv.setItem(s, disabledItem)
      }
    }

    player.openInventory(inv)
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
  }

  fun handleClick(player: Player, slot: Int, isRightClick: Boolean = false): Boolean {
    val loc = instance.displayEntity.location

    val minecartMotion = instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
    if (minecartMotion != null && slot in listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 15, 16, 19, 21, 23)) {
      player.sendMessage(Component.text("🚫 からくりがトロッコに乗っている間は、他の動作モードや関係ない設定は変更できません。", NamedTextColor.RED))
      player.sendMessage(Component.text("  ▶ トロッコ専用の設定は「🚂 トロッコ連動設定盤」を開いて行ってください。", NamedTextColor.YELLOW))
      player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
      return true
    }

    when (slot) {
      // --- 上段: 速度・回転 ---
      0 -> { // ドア自動クローズ切替
        val door = instance.activeMotion as? DoorMotion
        if (door != null) {
          door.autoCloseTicks = if (door.autoCloseTicks > 0) 0 else 60
          io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
          val state = if (door.autoCloseTicks > 0) "ON (3秒後)" else "OFF (手動のみ)"
          player.sendMessage(Component.text("ドア自動クローズを $state に変更しました。", NamedTextColor.YELLOW))
        } else {
          player.sendMessage(Component.text("ドアモードが設定されていません。", NamedTextColor.RED))
        }
        player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
        open(player)
      }
      1 -> modifyRpm(player, -50f)
      2 -> modifyRpm(player, -10f)
      3 -> modifyRpm(player, -1f)
      4 -> cyclePresetRpm(player, isRightClick)
      5 -> modifyRpm(player, 1f)
      6 -> modifyRpm(player, 10f)
      7 -> modifyRpm(player, 50f)
      8 -> { // 回転方向切替
        val curMotion = instance.activeMotion
        if (curMotion is ContinuousSpinMotion) {
          curMotion.toggleDirection()
          io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
          player.sendMessage(Component.text("回転方向を反転しました！", NamedTextColor.YELLOW))
        } else if (curMotion is DoorMotion) {
          curMotion.clockwise = !curMotion.clockwise
          io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
          player.sendMessage(Component.text("ドアの開閉方向を反転しました！", NamedTextColor.YELLOW))
        } else {
          player.sendMessage(Component.text("回転モーションが設定されていません。", NamedTextColor.RED))
        }
        player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
        open(player)
      }

      // --- 中段: 動作モード ---
      10 -> { // ドアモード
        val door = DoorMotion(
          axis = Axis.Y,
          openAngleDeg = 90f,
          rpm = 16f,
          autoCloseTicks = 60,
          clockwise = true
        )
        instance.setMotion(door)
        player.sendMessage(Component.text("✔ ドアモードを設定しました！(素手右クリックで開閉)", NamedTextColor.GREEN))
        player.playSound(loc, Sound.BLOCK_WOODEN_DOOR_OPEN, 1.0f, 1.0f)
        open(player)
      }
      11 -> { // 風車
        instance.setMotion(ContinuousSpinMotion(Axis.Z, 96f))
        player.sendMessage(Component.text("✔ 風車モード (Z軸回転) を設定しました！", NamedTextColor.WHITE))
        player.playSound(loc, Sound.BLOCK_BEACON_AMBIENT, 1.0f, 1.5f)
        open(player)
      }
      12 -> { // 水車
        instance.setMotion(ContinuousSpinMotion(Axis.X, 96f))
        player.sendMessage(Component.text("✔ 水車モード (X軸回転) を設定しました！", NamedTextColor.BLUE))
        player.playSound(loc, Sound.BLOCK_WATER_AMBIENT, 1.0f, 1.0f)
        open(player)
      }
      13 -> { // 回転台
        instance.setMotion(ContinuousSpinMotion(Axis.Y, 96f))
        player.sendMessage(Component.text("✔ 水平回転台 (Y軸回転) を設定しました！", NamedTextColor.LIGHT_PURPLE))
        player.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f)
        open(player)
      }
      14 -> { // トロッコ連動設定盤を開く / トロッコ合体モード開始
        val cartMotion = instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
        if (cartMotion != null) {
          KarakuriMinecartGUI(instance, cartMotion).open(player)
        } else {
          io.oyasai.karakuri.listener.KarakuriListener.startSelectingMinecartForInstance(player, instance)
          player.closeInventory()
        }
      }
      15 -> { // 直線運動
        KarakuriLinearGUI(instance).open(player)
      }
      16 -> { // プログラム
        KarakuriProgramGUI(instance).open(player)
      }

      // --- 下段: 制御 ---
      18 -> { // RS連動ブロック管理（一括解除）
        val rsLinkManager = io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager
        val count = rsLinkManager.removeAllLinks(instance)
        if (count > 0) {
          player.sendMessage(Component.text("⚡ [${instance.id}] 登録されていたRS連動ブロック（${count}件）をすべて解除しました！", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 1.0f, 1.2f)
        } else {
          player.sendMessage(Component.text("登録されているRS連動ブロックはありません。", NamedTextColor.GRAY))
          player.playSound(player.location, Sound.BLOCK_LEVER_CLICK, 0.8f, 1.0f)
        }
        open(player)
      }
      19 -> { // 初期位置リセット
        instance.resetToBase()
        player.sendMessage(Component.text("⏮️ からくりを初期位置・初期姿勢にリセットしました！", NamedTextColor.LIGHT_PURPLE))
        player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.5f)
        open(player)
      }
      20 -> { // 不動切替
        instance.isImmovable = !instance.isImmovable
        io.oyasai.karakuri.KarakuriPlugin.instance.database.updateInstanceFlags(instance)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
        if (instance.isImmovable) {
          player.sendMessage(Component.text("🗿 [${instance.id}] 不動を【ON】に設定しました（Shift+右クリック手動作動をロック）", NamedTextColor.RED))
        } else {
          player.sendMessage(Component.text("🔓 [${instance.id}] 不動を【OFF】に設定しました（Shift+右クリック手動作動を許可）", NamedTextColor.GREEN))
        }
        open(player)
      }
      21 -> { // 開始/トグル
        val motion = instance.activeMotion
        if (motion is DoorMotion) {
          motion.toggle(instance)
        } else if (motion is IntegratedLinearMotion) {
          motion.toggle(instance)
        } else if (motion is ProgramMotion) {
          motion.toggle(instance)
        }
        player.sendMessage(Component.text("動作を作動しました。", NamedTextColor.GREEN))
        player.closeInventory()
      }
      22 -> { // トロッコから降ろす (分離)
        val cartMotion = instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
        if (cartMotion != null) {
          io.oyasai.karakuri.KarakuriPlugin.instance.manager.dismountFromMinecart(instance)
          player.sendMessage(Component.text("⛓️ トロッコから切り離し、現在の場所に独立設置しました！", NamedTextColor.LIGHT_PURPLE))
          player.playSound(player.location, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.2f)
          open(player)
        }
      }
      23 -> { // 停止（モーション設定は保持したまま一時停止）
        val curMotion = instance.activeMotion
        when (curMotion) {
          is ContinuousSpinMotion -> {
            curMotion.isRunning = false
            io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
            player.sendMessage(Component.text("回転を一時停止しました。", NamedTextColor.YELLOW))
          }
          is IntegratedLinearMotion -> {
            curMotion.isRunning = false
            io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
            player.sendMessage(Component.text("直線運動を一時停止しました。", NamedTextColor.YELLOW))
          }
          is ProgramMotion -> {
            curMotion.pause()
            io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
            player.sendMessage(Component.text("プログラムを一時停止しました。", NamedTextColor.YELLOW))
          }
          is ChildContraptionMotion -> {
            curMotion.isSpinActive = false
            io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
            player.sendMessage(Component.text("子構造の自転を一時停止しました。", NamedTextColor.YELLOW))
          }
          is ChainChildContraptionMotion -> {
            curMotion.isRunning = false
            curMotion.isSpinActive = false
            io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)
            player.sendMessage(Component.text("鎖子構造の動作を一時停止しました。", NamedTextColor.YELLOW))
          }
          else -> {
            player.sendMessage(Component.text("動作を一時停止しました。", NamedTextColor.YELLOW))
          }
        }
        player.playSound(loc, Sound.BLOCK_LEVER_CLICK, 1.0f, 0.8f)
        open(player)
      }
      24 -> { // エンティティ軽量化切替
        if (isRightClick) {
          // 右クリック時: 非フルブロック統合のON/OFF切替
          val newNonFull = !instance.optimizeNonFullBlocks
          instance.optimizeNonFullBlocks = newNonFull
          val beforeCount = 1 + instance.additionalParts.size
          if (instance.isOptimized) {
            io.oyasai.karakuri.KarakuriPlugin.instance.manager.rebuildEntities(instance, true, newNonFull)
          }
          val afterCount = 1 + instance.additionalParts.size

          if (newNonFull) {
            val countInfo = if (instance.isOptimized) " (${beforeCount}体 ➔ ${afterCount}体)" else ""
            player.sendMessage(Component.text("📐 [${instance.id}] 非フルブロックの統合を有効にしました！$countInfo", NamedTextColor.AQUA))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f)
          } else {
            val countInfo = if (instance.isOptimized) " (${beforeCount}体 ➔ ${afterCount}体)" else ""
            player.sendMessage(Component.text("📐 [${instance.id}] 非フルブロックの統合を無効にし、フルブロックのみに限定しました。$countInfo", NamedTextColor.YELLOW))
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          }
          open(player)
          return true
        }

        // 左クリック時: 軽量化自体の ON / OFF 切替
        val totalBlocks = if (instance.constituentBlocks.isNotEmpty()) instance.constituentBlocks.size else (1 + instance.additionalParts.size)
        if (totalBlocks <= 1) {
          player.sendMessage(Component.text("単一ブロックのからくりは軽量化できません。", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          return true
        }
        val targetState = !instance.isOptimized
        val beforeCount = 1 + instance.additionalParts.size
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.rebuildEntities(instance, targetState, instance.optimizeNonFullBlocks)
        val afterCount = 1 + instance.additionalParts.size

        if (targetState) {
          val targetDesc = if (instance.optimizeNonFullBlocks) "すべてのブロック(非フル含む)" else "フルブロックのみ"
          player.sendMessage(Component.text("🧱 [${instance.id}] エンティティ軽量化を有効にしました [${targetDesc}] (${beforeCount}体 ➔ ${afterCount}体に削減！)", NamedTextColor.GREEN))
          player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1.0f, 1.8f)
        } else {
          player.sendMessage(Component.text("🧱 [${instance.id}] エンティティ軽量化を無効にし、個別ブロックに展開しました (${beforeCount}体 ➔ ${afterCount}体)", NamedTextColor.YELLOW))
          player.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.4f)
        }
        open(player)
      }
      25 -> { // 撤去してブロックに戻す
        val cartMotion = instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
        if (cartMotion != null) {
          cartMotion.restoreAtCurrentMinecartPosition(instance)
          player.sendMessage(Component.text("✦ トロッコの現在位置にブロックを設置し、からくりを撤去しました！", NamedTextColor.GREEN))
          player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
        } else {
          io.oyasai.karakuri.KarakuriPlugin.instance.manager.revert(instance.id, player)
        }
        player.closeInventory()
      }
      else -> return false
    }
    return true
  }

  private fun modifyRpm(player: Player, delta: Float) {
    val motion = instance.activeMotion
    val currentRpm = when (motion) {
      is ContinuousSpinMotion -> motion.rpm
      is DoorMotion -> motion.rpm
      else -> {
        player.sendMessage(Component.text("回転系モーションが設定されていません。", NamedTextColor.RED))
        return
      }
    }
    val newRpm = (currentRpm + delta).coerceIn(1f, 256f)
    if (motion is ContinuousSpinMotion) motion.rpm = newRpm
    if (motion is DoorMotion) motion.rpm = newRpm

    io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)

    player.sendMessage(Component.text("回転速度: ${newRpm.toInt()} RPM", NamedTextColor.GOLD))
    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f)
    open(player)
  }

  private fun cyclePresetRpm(player: Player, isReverse: Boolean = false) {
    val motion = instance.activeMotion
    val currentRpm = when (motion) {
      is ContinuousSpinMotion -> motion.rpm
      is DoorMotion -> motion.rpm
      else -> {
        player.sendMessage(Component.text("回転系モーションが設定されていません。", NamedTextColor.RED))
        return
      }
    }
    val curIdx = RPM_PRESETS.indexOfFirst { it == currentRpm }
    val nextIdx = if (isReverse) {
      if (curIdx <= 0) RPM_PRESETS.size - 1 else curIdx - 1
    } else {
      (if (curIdx == -1) 0 else curIdx + 1) % RPM_PRESETS.size
    }
    val newRpm = RPM_PRESETS[nextIdx]

    if (motion is ContinuousSpinMotion) motion.rpm = newRpm
    if (motion is DoorMotion) motion.rpm = newRpm

    io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(instance)

    player.sendMessage(Component.text("プリセット速度: ${newRpm.toInt()} RPM", NamedTextColor.GOLD))
    player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, if (isReverse) 1.2f else 1.8f)
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
    meta.displayName(name)
    if (lore.isNotEmpty()) meta.lore(lore)
    if (isGlowing) {
      meta.setEnchantmentGlintOverride(true)
    }
    item.itemMeta = meta
    return item
  }
}
