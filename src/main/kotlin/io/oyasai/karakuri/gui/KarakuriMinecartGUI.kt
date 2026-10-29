package io.oyasai.karakuri.gui

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.motion.Axis
import io.oyasai.karakuri.motion.MinecartMotion
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * トロッコ合体からくり専用の設定盤 (GUI)
 */
class KarakuriMinecartGUI(
  private val instance: KarakuriInstance,
  private val motion: MinecartMotion,
) {

  companion object {
    const val TITLE_PREFIX = "🚂 トロッコ連動設定盤"
  }

  fun open(player: Player) {
    val inv: Inventory = Bukkit.createInventory(
      null,
      27,
      Component.text("[${instance.id}] $TITLE_PREFIX", NamedTextColor.DARK_GRAY)
    )

    // ==========================================
    // 上段 (スロット 0〜8): 自転回転速度 (RPM)
    // ==========================================
    inv.setItem(0, createItem(Material.REDSTONE, Component.text("⏪ RPM -10", NamedTextColor.RED, TextDecoration.BOLD)))
    inv.setItem(1, createItem(Material.REDSTONE_TORCH, Component.text("◀ RPM -1", NamedTextColor.RED)))
    inv.setItem(
      2,
      createItem(
        Material.CLOCK,
        Component.text("⏱ 自転速度: ${String.format("%.1f", motion.rpm)} RPM", NamedTextColor.YELLOW, TextDecoration.BOLD),
        listOf(
          Component.text("トロッコ走行中の自転回転速度を設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("1分間に約 ${String.format("%.1f", kotlin.math.abs(motion.rpm))} 回転", NamedTextColor.WHITE)
        )
      )
    )
    inv.setItem(3, createItem(Material.GLOWSTONE_DUST, Component.text("▶ RPM +1", NamedTextColor.GREEN)))
    inv.setItem(4, createItem(Material.GLOWSTONE, Component.text("⏩ RPM +10", NamedTextColor.GREEN, TextDecoration.BOLD)))

    inv.setItem(
      6,
      createItem(
        Material.ECHO_SHARD,
        Component.text("🔁 自転方向反転", NamedTextColor.AQUA, TextDecoration.BOLD),
        listOf(
          Component.text("自転の回転方向を瞬時に反転します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("▶ [クリックして反転]", NamedTextColor.GOLD)
        )
      )
    )

    // ==========================================
    // 中段 (スロット 9〜17): 核心設定
    // ==========================================

    // 10: 進行方向連動 (ON / OFF)
    val isFollow = motion.followRotation
    inv.setItem(
      10,
      createItem(
        Material.MINECART,
        Component.text(
          "🚂 進行方向連動: " + if (isFollow) "ON (追従回転)" else "OFF (向き固定)",
          if (isFollow) NamedTextColor.GREEN else NamedTextColor.AQUA,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("トロッコの進行方向（カーブ・進路）に合わせて", NamedTextColor.GRAY),
          Component.text("からくりの向きを回転追従させるかを切り替えます。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在の設定: " + if (isFollow) "進行方向に連動して曲がる (ON)" else "向きを固定して移動 (OFF)", NamedTextColor.YELLOW),
          Component.text(""),
          Component.text("▶ [クリックして切り替え]", NamedTextColor.GREEN)
        ),
        isGlowing = isFollow
      )
    )

    // 11: 坂レール傾斜連動 (ON / OFF)
    val isSlopeFollow = motion.followSlope
    inv.setItem(
      11,
      createItem(
        Material.RAIL,
        Component.text(
          "📐 坂レール傾斜連動: " + if (isSlopeFollow) "ON (傾斜追従)" else "OFF (水平維持)",
          if (isSlopeFollow) NamedTextColor.GREEN else NamedTextColor.AQUA,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("坂道（斜め）レールを走行する際に、", NamedTextColor.GRAY),
          Component.text("トロッコの傾斜に合わせてからくりを傾けるかを設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在の設定: " + if (isSlopeFollow) "レールの傾きに追従する (ON)" else "常に水平を維持する (OFF)", NamedTextColor.YELLOW),
          Component.text("※自転回転機能とも完全に両立・調和します。", NamedTextColor.DARK_GRAY),
          Component.text(""),
          Component.text("▶ [クリックして切り替え]", NamedTextColor.GREEN)
        ),
        isGlowing = isSlopeFollow
      )
    )

    // 12: からくりの前側（正面）
    val faceName = when (motion.forwardFacing) {
      BlockFace.NORTH -> "北 (North [-Z])"
      BlockFace.EAST -> "東 (East [+X])"
      BlockFace.SOUTH -> "南 (South [+Z])"
      BlockFace.WEST -> "西 (West [-X])"
      else -> motion.forwardFacing.name
    }
    inv.setItem(
      12,
      createItem(
        Material.COMPASS,
        Component.text("🧭 からくりの前側(正面): $faceName", NamedTextColor.GOLD, TextDecoration.BOLD),
        listOf(
          Component.text("トロッコが前進する際に、からくりのどの面を", NamedTextColor.GRAY),
          Component.text("『正面（進行方向）』として扱うかを指定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在の正面: $faceName", NamedTextColor.AQUA),
          Component.text(""),
          Component.text("▶ [クリックして次の向きへ変更 (南➔西➔北➔東)]", NamedTextColor.YELLOW)
        ),
        isGlowing = true
      )
    )

    // 13: レール端での先頭入替 (ON / OFF)
    val isAutoRev = motion.autoReverseAtRailEnd
    inv.setItem(
      13,
      createItem(
        Material.LEVER,
        Component.text(
          "🔄 レール端での先頭入替: " + if (isAutoRev) "ON (自動折り返し)" else "OFF (そのまま停止)",
          if (isAutoRev) NamedTextColor.GREEN else NamedTextColor.AQUA,
          TextDecoration.BOLD
        ),
        listOf(
          Component.text("連結されたトロッコがレールの終端に到達した際、", NamedTextColor.GRAY),
          Component.text("先頭車両と一番後ろの車両を入れ替えて折り返し走行させるかを設定します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在の設定: " + if (isAutoRev) "終端で先頭を入れ替えて反転 (ON)" else "終端で停止 (OFF)", NamedTextColor.YELLOW),
          Component.text(""),
          Component.text("▶ [クリックして切り替え]", NamedTextColor.GREEN)
        ),
        isGlowing = isAutoRev
      )
    )

    // 14: 連続回転（自転軸）
    val axisName = when (motion.spinAxis) {
      null -> "回転なし (停止)"
      Axis.Y -> "Y軸 (水平回転台・メリーゴーランド)"
      Axis.X -> "X軸 (水車・縦回転)"
      Axis.Z -> "Z軸 (風車・プロペラ回転)"
    }
    inv.setItem(
      14,
      createItem(
        Material.AMETHYST_SHARD,
        Component.text("🔄 連続自転軸: $axisName", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
        listOf(
          Component.text("トロッコで移動しながら、からくり本体を", NamedTextColor.GRAY),
          Component.text("設定した軸を中心にくるくる自転回転させます。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("現在の自転軸: $axisName", NamedTextColor.YELLOW),
          Component.text(""),
          Component.text("▶ [クリックして切り替え (なし➔Y軸➔X軸➔Z軸)]", NamedTextColor.GREEN)
        ),
        isGlowing = motion.spinAxis != null
      )
    )

    // 16: トロッコから降ろす (分離)
    inv.setItem(
      16,
      createItem(
        Material.SHEARS,
        Component.text("⛓️ トロッコから降ろす (分離)", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(
          Component.text("からくりをトロッコから切り離し、", NamedTextColor.GRAY),
          Component.text("現在の位置に独立したからくりとして設置します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("▶ [クリックして分離]", NamedTextColor.RED)
        )
      )
    )

    // ==========================================
    // 下段 (スロット 18〜26): ナビゲーション・管理
    // ==========================================
    inv.setItem(
      18,
      createItem(
        Material.ARROW,
        Component.text("⬅️ メイン設定盤に戻る", NamedTextColor.WHITE, TextDecoration.BOLD),
        listOf(
          Component.text("からくりの総合設定盤を開きます。", NamedTextColor.GRAY),
          Component.text("※トロッコ合体中は他動作モードの選択は制限されます。", NamedTextColor.DARK_GRAY)
        )
      )
    )

    inv.setItem(
      22,
      createItem(
        Material.LAVA_BUCKET,
        Component.text("🗑️ 撤去してブロックに戻す", NamedTextColor.DARK_RED, TextDecoration.BOLD),
        listOf(
          Component.text("トロッコが乗っている現在の場所に新しく実体ブロックを設置し、", NamedTextColor.YELLOW),
          Component.text("からくりエンティティを撤去・ブロック化します。", NamedTextColor.GRAY),
          Component.text(""),
          Component.text("▶ [クリックしてブロック化]", NamedTextColor.RED)
        )
      )
    )

    inv.setItem(
      26,
      createItem(
        Material.BARRIER,
        Component.text("❌ 閉じる", NamedTextColor.RED, TextDecoration.BOLD),
        listOf(Component.text("設定盤を閉じます。", NamedTextColor.GRAY))
      )
    )

    player.openInventory(inv)
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.2f)
  }

  fun handleClick(player: Player, slot: Int): Boolean {
    val loc = instance.displayEntity.location

    when (slot) {
      0 -> modifyRpm(player, -10f)
      1 -> modifyRpm(player, -1f)
      3 -> modifyRpm(player, 1f)
      4 -> modifyRpm(player, 10f)
      6 -> { // 自転方向反転
        motion.rpm = -motion.rpm
        motion.forceUpdateNextTick = true
        motion.updatePositionAndRotation(instance, durationTicks = 2)
        player.sendMessage(Component.text("自転方向を反転しました！", NamedTextColor.YELLOW))
        player.playSound(loc, Sound.BLOCK_LEVER_CLICK, 1.0f, 1.2f)
        open(player)
      }
      10 -> { // 進行方向連動 切替
        motion.followRotation = !motion.followRotation
        motion.forceUpdateNextTick = true
        motion.updatePositionAndRotation(instance, durationTicks = 2)
        if (motion.followRotation) {
          player.sendMessage(Component.text("🚂 [${instance.id}] トロッコの進行方向に連動して回転するモードに設定しました！", NamedTextColor.GREEN))
          player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f)
        } else {
          player.sendMessage(Component.text("🔒 [${instance.id}] からくりの向きを固定するモードに設定しました（回転連動なし）。", NamedTextColor.AQUA))
          player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f)
        }
        open(player)
      }
      11 -> { // 坂レール傾斜連動 切替
        motion.followSlope = !motion.followSlope
        motion.forceUpdateNextTick = true
        motion.updatePositionAndRotation(instance, durationTicks = 2)
        if (motion.followSlope) {
          player.sendMessage(Component.text("📐 [${instance.id}] 坂レールの傾きに合わせて傾斜するモードに設定しました！", NamedTextColor.GREEN))
          player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f)
        } else {
          player.sendMessage(Component.text("🔒 [${instance.id}] 坂レールでも常に水平を維持するモードに設定しました。", NamedTextColor.AQUA))
          player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f)
        }
        open(player)
      }
      12 -> { // 正面（前側）切替 (南 ➔ 西 ➔ 北 ➔ 東 ➔ 南)
        motion.forwardFacing = when (motion.forwardFacing) {
          BlockFace.SOUTH -> BlockFace.WEST
          BlockFace.WEST -> BlockFace.NORTH
          BlockFace.NORTH -> BlockFace.EAST
          BlockFace.EAST -> BlockFace.SOUTH
          else -> BlockFace.SOUTH
        }
        motion.forceUpdateNextTick = true
        motion.updatePositionAndRotation(instance, durationTicks = 2)
        val name = when (motion.forwardFacing) {
          BlockFace.NORTH -> "北 (North [-Z])"
          BlockFace.EAST -> "東 (East [+X])"
          BlockFace.SOUTH -> "南 (South [+Z])"
          BlockFace.WEST -> "西 (West [-X])"
          else -> motion.forwardFacing.name
        }
        player.sendMessage(Component.text("🧭 [${instance.id}] からくりの前側(正面)を 【$name】 に設定しました！", NamedTextColor.GOLD))
        player.playSound(loc, Sound.ITEM_ARMOR_EQUIP_GENERIC, 1.0f, 1.5f)
        open(player)
      }
      13 -> { // レール端での先頭入替 切替
        motion.autoReverseAtRailEnd = !motion.autoReverseAtRailEnd
        if (motion.autoReverseAtRailEnd) {
          player.sendMessage(Component.text("🔄 [${instance.id}] レール端到達時に先頭と最後尾を自動で入れ替えるモードに設定しました（自動折り返しON）！", NamedTextColor.GREEN))
          player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f)
        } else {
          player.sendMessage(Component.text("🔒 [${instance.id}] レール端到達時の先頭入れ替えを無効化しました（自動折り返しOFF）。", NamedTextColor.AQUA))
          player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f)
        }
        open(player)
      }
      14 -> { // 自転軸切替 (なし ➔ Y軸 ➔ X軸 ➔ Z軸 ➔ なし)
        motion.spinAxis = when (motion.spinAxis) {
          null -> Axis.Y
          Axis.Y -> Axis.X
          Axis.X -> Axis.Z
          Axis.Z -> null
        }
        motion.forceUpdateNextTick = true
        motion.updatePositionAndRotation(instance, durationTicks = 2)
        val name = when (motion.spinAxis) {
          null -> "回転なし (停止)"
          Axis.Y -> "Y軸 (水平回転台)"
          Axis.X -> "X軸 (水車・縦回転)"
          Axis.Z -> "Z軸 (風車・プロペラ)"
        }
        player.sendMessage(Component.text("🔄 [${instance.id}] 連続自転軸を 【$name】 に設定しました！", NamedTextColor.LIGHT_PURPLE))
        player.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f)
        open(player)
      }
      16 -> { // トロッコから降ろす (分離)
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.dismountFromMinecart(instance)
        player.sendMessage(Component.text("⛓️ トロッコから切り離し、現在の場所に独立設置しました！", NamedTextColor.LIGHT_PURPLE))
        player.playSound(player.location, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.2f)
        KarakuriGUI(instance).open(player)
      }
      18 -> { // メイン設定盤に戻る
        KarakuriGUI(instance).open(player)
      }
      22 -> { // 撤去
        motion.restoreAtCurrentMinecartPosition(instance)
        player.sendMessage(Component.text("✦ トロッコの現在位置にブロックを設置し、からくりを撤去しました！", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 1.0f, 1.2f)
        player.closeInventory()
      }
      26 -> { // 閉じる
        player.closeInventory()
      }
    }
    return true
  }

  private fun modifyRpm(player: Player, delta: Float) {
    val newRpm = (motion.rpm + delta).coerceIn(-256f, 256f)
    motion.rpm = newRpm
    motion.forceUpdateNextTick = true
    motion.updatePositionAndRotation(instance, durationTicks = 2)
    player.sendMessage(Component.text("自転速度を ${String.format("%.1f", newRpm)} RPM に変更しました。", NamedTextColor.GREEN))
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.8f, 1.5f)
    open(player)
  }

  private fun createItem(
    material: Material,
    name: Component,
    lore: List<Component> = emptyList(),
    isGlowing: Boolean = false,
  ): ItemStack {
    val item = ItemStack(material)
    val meta = item.itemMeta ?: return item
    meta.displayName(name.decoration(TextDecoration.ITALIC, false))
    meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
    if (isGlowing) {
      meta.setEnchantmentGlintOverride(true)
    }
    item.itemMeta = meta
    return item
  }
}
