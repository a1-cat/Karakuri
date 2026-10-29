package io.oyasai.karakuri.collision

import org.bukkit.Axis
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Gate
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.util.BoundingBox
import org.joml.Vector3f

/**
 * ブロックの種類とスケール（拡大率）に応じたローカル直方体（BoundingBox）リストを生成するユーティリティ。
 *
 * - フルブロック: 0..scaleX, 0..scaleY, 0..scaleZ の1直方体
 * - ハーフブロック (Slab): 上半分または下半分の直方体
 * - 階段 (Stairs):
 *     土台直方体 + 向きに応じたステップ直方体
 *     ★2x2x2などに統合された場合、拡大された見た目通りの段差（幅1m・高さ1m）を完全再現！
 * - トラップドア、カーペット等も見た目の厚みに応じて分割直方体を生成
 */
object VoxelShape {

  /**
   * 指定した BlockData と scale から、ローカル直方体 (BoundingBox) のリストを計算
   * 各 BoundingBox は [0, 0, 0] から [scale.x, scale.y, scale.z] の範囲内で定義される。
   */
  fun getLocalBoxes(blockData: BlockData?, scale: Vector3f): List<BoundingBox> {
    val sx = scale.x.toDouble()
    val sy = scale.y.toDouble()
    val sz = scale.z.toDouble()

    if (blockData == null) {
      return listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz))
    }

    val mat = blockData.material

    // 1. ハーフブロック (Slab)
    if (blockData is Slab) {
      return when (blockData.type) {
        Slab.Type.BOTTOM -> listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy * 0.5, sz))
        Slab.Type.TOP -> listOf(BoundingBox(0.0, sy * 0.5, 0.0, sx, sy, sz))
        Slab.Type.DOUBLE -> listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz))
      }
    }

    // 2. 階段 (Stairs)
    // 2x2x2 などに統合された場合、土台とステップがそれぞれ scale に比例して拡大され、
    // まさに「フルブロックを階段状に置いた段差」を正確に再現する！
    if (blockData is Stairs) {
      val isTop = (blockData.half == Bisected.Half.TOP)
      val facing = blockData.facing
      val shape = blockData.shape

      // 土台 (Base): 高さ半分
      val baseBox = if (isTop) {
        BoundingBox(0.0, sy * 0.5, 0.0, sx, sy, sz)
      } else {
        BoundingBox(0.0, 0.0, 0.0, sx, sy * 0.5, sz)
      }

      // ステップの Y 範囲
      val stepMinY = if (isTop) 0.0 else sy * 0.5
      val stepMaxY = if (isTop) sy * 0.5 else sy

      val stepBoxes = mutableListOf<BoundingBox>()

      // 階段の踏み面・背もたれ部分の直方体分割
      // ★DisplayEntity (BlockData) のローカル座標系と完全一致するステップ形状
      when (shape) {
        Stairs.Shape.STRAIGHT -> {
          when (facing) {
            BlockFace.NORTH -> stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx, stepMaxY, sz * 0.5))
            BlockFace.SOUTH -> stepBoxes.add(BoundingBox(0.0, stepMinY, sz * 0.5, sx, stepMaxY, sz))
            BlockFace.WEST -> stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx * 0.5, stepMaxY, sz))
            BlockFace.EAST -> stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, 0.0, sx, stepMaxY, sz))
            else -> stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx, stepMaxY, sz * 0.5))
          }
        }
        Stairs.Shape.INNER_LEFT, Stairs.Shape.INNER_RIGHT -> {
          // 内角階段 (L字型に3/4埋まっている)
          when (facing) {
            BlockFace.NORTH -> {
              stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx, stepMaxY, sz * 0.5))
              if (shape == Stairs.Shape.INNER_LEFT) {
                stepBoxes.add(BoundingBox(0.0, stepMinY, sz * 0.5, sx * 0.5, stepMaxY, sz))
              } else {
                stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, sz * 0.5, sx, stepMaxY, sz))
              }
            }
            BlockFace.SOUTH -> {
              stepBoxes.add(BoundingBox(0.0, stepMinY, sz * 0.5, sx, stepMaxY, sz))
              if (shape == Stairs.Shape.INNER_LEFT) {
                stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, 0.0, sx, stepMaxY, sz * 0.5))
              } else {
                stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx * 0.5, stepMaxY, sz * 0.5))
              }
            }
            BlockFace.WEST -> {
              stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx * 0.5, stepMaxY, sz))
              if (shape == Stairs.Shape.INNER_LEFT) {
                stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, 0.0, sx, stepMaxY, sz * 0.5))
              } else {
                stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, sz * 0.5, sx, stepMaxY, sz))
              }
            }
            BlockFace.EAST -> {
              stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, 0.0, sx, stepMaxY, sz))
              if (shape == Stairs.Shape.INNER_LEFT) {
                stepBoxes.add(BoundingBox(0.0, stepMinY, sz * 0.5, sx * 0.5, stepMaxY, sz))
              } else {
                stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx * 0.5, stepMaxY, sz * 0.5))
              }
            }
            else -> {}
          }
        }
        Stairs.Shape.OUTER_LEFT, Stairs.Shape.OUTER_RIGHT -> {
          // 外角階段 (1/4の角のみ)
          when (facing) {
            BlockFace.NORTH -> {
              if (shape == Stairs.Shape.OUTER_LEFT) {
                stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx * 0.5, stepMaxY, sz * 0.5))
              } else {
                stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, 0.0, sx, stepMaxY, sz * 0.5))
              }
            }
            BlockFace.SOUTH -> {
              if (shape == Stairs.Shape.OUTER_LEFT) {
                stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, sz * 0.5, sx, stepMaxY, sz))
              } else {
                stepBoxes.add(BoundingBox(0.0, stepMinY, sz * 0.5, sx * 0.5, stepMaxY, sz))
              }
            }
            BlockFace.WEST -> {
              if (shape == Stairs.Shape.OUTER_LEFT) {
                stepBoxes.add(BoundingBox(0.0, stepMinY, 0.0, sx * 0.5, stepMaxY, sz * 0.5))
              } else {
                stepBoxes.add(BoundingBox(0.0, stepMinY, sz * 0.5, sx * 0.5, stepMaxY, sz))
              }
            }
            BlockFace.EAST -> {
              if (shape == Stairs.Shape.OUTER_LEFT) {
                stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, sz * 0.5, sx, stepMaxY, sz))
              } else {
                stepBoxes.add(BoundingBox(sx * 0.5, stepMinY, 0.0, sx, stepMaxY, sz * 0.5))
              }
            }
            else -> {}
          }
        }
      }

      return listOf(baseBox) + stepBoxes
    }

    // 3. トラップドア (TrapDoor)
    if (blockData is TrapDoor) {
      val thickness = sy * (3.0 / 16.0)
      if (!blockData.isOpen) {
        // 水平
        return if (blockData.half == Bisected.Half.TOP) {
          listOf(BoundingBox(0.0, sy - thickness, 0.0, sx, sy, sz))
        } else {
          listOf(BoundingBox(0.0, 0.0, 0.0, sx, thickness, sz))
        }
      } else {
        // 垂直（開いた状態）
        return when (blockData.facing) {
          BlockFace.NORTH -> listOf(BoundingBox(0.0, 0.0, sz - thickness, sx, sy, sz))
          BlockFace.SOUTH -> listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, thickness))
          BlockFace.WEST -> listOf(BoundingBox(sx - thickness, 0.0, 0.0, sx, sy, sz))
          BlockFace.EAST -> listOf(BoundingBox(0.0, 0.0, 0.0, thickness, sy, sz))
          else -> listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz))
        }
      }
    }

    // 4. ドア (Door)
    if (blockData is Door) {
      val t = 3.0 / 16.0
      val facing = blockData.facing
      val isOpen = blockData.isOpen
      val isLeft = (blockData.hinge == Door.Hinge.LEFT)

      if (!isOpen) {
        // 閉じている状態: facing の奥側に立つ
        return when (facing) {
          BlockFace.NORTH -> listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz * t))
          BlockFace.SOUTH -> listOf(BoundingBox(0.0, 0.0, sz * (1.0 - t), sx, sy, sz))
          BlockFace.WEST -> listOf(BoundingBox(0.0, 0.0, 0.0, sx * t, sy, sz))
          BlockFace.EAST -> listOf(BoundingBox(sx * (1.0 - t), 0.0, 0.0, sx, sy, sz))
          else -> listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz * t))
        }
      } else {
        // 開いている状態: ヒンジを軸に90度回転して壁沿いに立つ
        return when (facing) {
          BlockFace.NORTH -> if (isLeft) listOf(BoundingBox(0.0, 0.0, 0.0, sx * t, sy, sz)) else listOf(BoundingBox(sx * (1.0 - t), 0.0, 0.0, sx, sy, sz))
          BlockFace.SOUTH -> if (isLeft) listOf(BoundingBox(sx * (1.0 - t), 0.0, 0.0, sx, sy, sz)) else listOf(BoundingBox(0.0, 0.0, 0.0, sx * t, sy, sz))
          BlockFace.WEST -> if (isLeft) listOf(BoundingBox(0.0, 0.0, sz * (1.0 - t), sx, sy, sz)) else listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz * t))
          BlockFace.EAST -> if (isLeft) listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz * t)) else listOf(BoundingBox(0.0, 0.0, sz * (1.0 - t), sx, sy, sz))
          else -> emptyList()
        }
      }
    }

    // 5. フェンスゲート (Gate)
    if (blockData is Gate) {
      if (blockData.isOpen) {
        // 開いているフェンスゲートは通り抜け可能
        return emptyList()
      } else {
        // 閉じているフェンスゲート: 厚み 3/16 (0.375 .. 0.625)
        return when (blockData.facing) {
          BlockFace.NORTH, BlockFace.SOUTH -> listOf(BoundingBox(0.0, 0.0, sz * 0.375, sx, sy, sz * 0.625))
          BlockFace.EAST, BlockFace.WEST -> listOf(BoundingBox(sx * 0.375, 0.0, 0.0, sx * 0.625, sy, sz))
          else -> listOf(BoundingBox(0.0, 0.0, sz * 0.375, sx, sy, sz * 0.625))
        }
      }
    }

    // 6. 塀 (Wall) / フェンス (Fence)
    if (mat.name.endsWith("_FENCE") || mat.name.endsWith("_WALL")) {
      return listOf(BoundingBox(sx * 0.25, 0.0, sz * 0.25, sx * 0.75, sy, sz * 0.75))
    }

    // 7. 鉄格子 (Iron Bars) / 銅の格子 (Copper Bars 全種) / ガラス板 (Glass Pane) / 銅格子 (Copper Grate)
    // ★ユーザー指定: 鉄格子だけでなく銅系の格子（COPPER_BARS 等）も薄い当たり判定に完全対応！
    if (mat.name.endsWith("_BARS") || mat.name.endsWith("_PANE") || mat.name.contains("COPPER_GRATE")) {
      val boxes = mutableListOf<BoundingBox>()
      // 中央の薄い柱/板 (厚み 2ピクセル = 0.125m, 高さ 1.0m)
      boxes.add(BoundingBox(sx * 0.4375, 0.0, sz * 0.4375, sx * 0.5625, sy, sz * 0.5625))
      if (blockData is org.bukkit.block.data.MultipleFacing) {
        if (blockData.hasFace(BlockFace.NORTH)) boxes.add(BoundingBox(sx * 0.4375, 0.0, 0.0, sx * 0.5625, sy, sz * 0.5))
        if (blockData.hasFace(BlockFace.SOUTH)) boxes.add(BoundingBox(sx * 0.4375, 0.0, sz * 0.5, sx * 0.5625, sy, sz))
        if (blockData.hasFace(BlockFace.WEST)) boxes.add(BoundingBox(0.0, 0.0, sz * 0.4375, sx * 0.5, sy, sz * 0.5625))
        if (blockData.hasFace(BlockFace.EAST)) boxes.add(BoundingBox(sx * 0.5, 0.0, sz * 0.4375, sx, sy, sz * 0.5625))
      }
      return boxes
    }

    // 8. 棒状ブロック: 鎖 (Chain / 銅の鎖), エンドロッド, 避雷針, 鍾乳石 (Dripstone), 尖った硫黄 (Sulfur)
    // ★ユーザー指定: ブロック統合時に太さだけが変わった一本の棒としてスケール連動！
    val isRod = (mat == Material.END_ROD || mat == Material.LIGHTNING_ROD ||
      mat.name.contains("CHAIN") || mat.name.contains("DRIPSTONE") || mat.name.contains("SULFUR"))
    if (isRod) {
      val axis = when {
        blockData is Orientable -> blockData.axis
        blockData is Directional -> when (blockData.facing) {
          BlockFace.EAST, BlockFace.WEST -> Axis.X
          BlockFace.NORTH, BlockFace.SOUTH -> Axis.Z
          else -> Axis.Y
        }
        else -> Axis.Y
      }
      return when (axis) {
        Axis.X -> listOf(BoundingBox(0.0, sy * 0.375, sz * 0.375, sx, sy * 0.625, sz * 0.625))
        Axis.Z -> listOf(BoundingBox(sx * 0.375, sy * 0.375, 0.0, sx * 0.625, sy * 0.625, sz))
        Axis.Y -> listOf(BoundingBox(sx * 0.375, 0.0, sz * 0.375, sx * 0.625, sy, sz * 0.625))
      }
    }

    // 9. 石切台 (Stonecutter: 刃を除いた土台部分、高さ約 0.56m)
    if (mat == Material.STONECUTTER) {
      return listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy * 0.5625, sz))
    }

    // 10. 日照センサー (Daylight Detector: 高さ約 0.375m)
    if (mat == Material.DAYLIGHT_DETECTOR) {
      return listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy * 0.375, sz))
    }

    // 11. ベッド (Bed: 高さ約 0.56m)
    if (mat.name.endsWith("_BED")) {
      return listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy * 0.5625, sz))
    }

    // 12. 棚系ブロック (最新の木材系棚ブロック: バニラ公式 VoxelShape 完全準拠！)
    // 壁際の背板＋上下棚板（厚み 5/16 = 0.3125m、高さ 1.0m）。手前 11/16 は完全空間！
    if (mat.name.contains("SHELF") && mat != Material.BOOKSHELF && mat != Material.CHISELED_BOOKSHELF) {
      val facing = if (blockData is Directional) blockData.facing else BlockFace.NORTH
      val t = 5.0 / 16.0
      return when (facing) {
        BlockFace.NORTH -> listOf(BoundingBox(0.0, 0.0, sz * (1.0 - t), sx, sy, sz)) // 手前北、奥壁南
        BlockFace.SOUTH -> listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz * t))         // 手前南、奥壁北
        BlockFace.WEST -> listOf(BoundingBox(sx * (1.0 - t), 0.0, 0.0, sx, sy, sz))  // 手前西、奥壁東
        BlockFace.EAST -> listOf(BoundingBox(0.0, 0.0, 0.0, sx * t, sy, sz))          // 手前東、奥壁西
        else -> listOf(BoundingBox(0.0, 0.0, sz * (1.0 - t), sx, sy, sz))
      }
    }

    // 13. 雪 (Snow: 重ねられる層数 1〜8 に完全対応！)
    if (blockData is org.bukkit.block.data.type.Snow) {
      val h = (blockData.layers / 8.0) * sy
      return listOf(BoundingBox(0.0, 0.0, 0.0, sx, h, sz))
    }

    // 14. 焚き火 (Campfire / Soul Campfire: 高さ 7/16 = 0.4375m)
    if (mat.name.contains("CAMPFIRE")) {
      return listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy * 0.4375, sz))
    }

    // 15. スカルクセンサー / 調律されたスカルクセンサー / スカルクシュリーカー (高さ約 0.5m)
    if (mat.name.contains("SCULK_SENSOR") || mat.name.contains("SCULK_SHRIEKER")) {
      return listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy * 0.5, sz))
    }

    // 16. ケーキ (Cake: 高さ 7/16 = 0.4375m)
    if (mat.name.contains("CAKE")) {
      return listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy * 0.4375, sz))
    }

    // 17. 当たり判定を完全に無し (すり抜け可能) にするブロック群
    // - フルブロックを除くサンゴ系 (ウミウチワ、サンゴ、死んだサンゴ等)
    val isNonBlockCoral = (mat.name.contains("CORAL") && !mat.name.endsWith("_CORAL_BLOCK"))
    // - 感圧板 (全木材・石・鉄・金等すべて)
    val isPressurePlate = mat.name.endsWith("_PRESSURE_PLATE")
    // - カーペット全種
    if (mat.name.contains("CARPET")) {
      return if (sy > 1.05) {
        listOf(BoundingBox(0.0, 0.0, 0.0, sx, 0.0625 * sy, sz))
      } else {
        emptyList()
      }
    }

    // - ろうそく全種 (上面に乗った際にハーフブロックの巨大足場が発生するのを完全防止！)
    val isCandle = mat.name.contains("CANDLE")
    // - その他松明、花、草、作物、レール、ボタン、レバー等の非固体ブロック
    if (isNonBlockCoral || isPressurePlate || isCandle || !mat.isSolid) {
      return emptyList()
    }

    // デフォルト (フルブロック):
    return listOf(BoundingBox(0.0, 0.0, 0.0, sx, sy, sz))
  }
}
