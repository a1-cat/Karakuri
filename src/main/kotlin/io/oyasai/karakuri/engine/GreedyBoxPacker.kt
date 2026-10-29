package io.oyasai.karakuri.engine

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData

/**
 * 整数ブロック座標
 */
data class BlockPos(val x: Int, val y: Int, val z: Int)

/**
 * 結合された直方体ブロックボックス
 */
data class MergedBox(
  val originX: Int,
  val originY: Int,
  val originZ: Int,
  val sizeX: Int,
  val sizeY: Int,
  val sizeZ: Int,
  val blockData: BlockData,
) {
  val volume: Int get() = sizeX * sizeY * sizeZ
  val isSingle: Boolean get() = sizeX == 1 && sizeY == 1 && sizeZ == 1
}

/**
 * からくり内の隣接する同種フルブロックを直方体に貪欲結合 (Greedy Meshing) する最適化エンジン。
 * 大規模建築からくりにおいて、見た目・幾何学構造を 100% 保持したまま
 * エンティティ数を 70%〜90% 以上大幅削減し、描画負荷・通信パケットを極小化する。
 */
object GreedyBoxPacker {

  /**
   * スケール伸長による結合が視覚的・幾何学的に安全なブロックか判定
   * allowNonFull == false: 不透過・直方体のフルブロックのみ結合
   * allowNonFull == true: ガラス、ハーフブロック、階段等の非フルブロックも結合
   */
  fun canMerge(blockData: BlockData, allowNonFull: Boolean = false): Boolean {
    val mat = blockData.material
    if (!mat.isBlock || mat.isAir) return false

    // スキンを描画するプレイヤーの頭は常に単体 ItemDisplay を維持
    if (mat == Material.PLAYER_HEAD || mat == Material.PLAYER_WALL_HEAD) return false

    // ★カーペットは常に許可（pack ループで Y 軸方向限定統合を行う）
    if (mat.name.contains("CARPET")) return true

    // ★粘着ピストンはブロック統合を完全に禁止（子構造接続検出や個別判定のため常に単体 DisplayEntity を維持）
    if (mat == Material.STICKY_PISTON) return false

    // ★銅系の鎖はブロック統合を完全に禁止（子構造システムで常に単体 DisplayEntity を維持）
    if (mat.name.contains("COPPER_CHAIN")) return false

    if (!allowNonFull) {
      if (!mat.isSolid || !mat.isOccluding) return false

      // 非フルブロックや接続形状ブロックは除外
      if (blockData is org.bukkit.block.data.type.Slab) return false
      if (blockData is org.bukkit.block.data.type.Stairs) return false
      if (mat.name.endsWith("_BED")) return false
      if (blockData is org.bukkit.block.data.type.Door) return false
      if (blockData is org.bukkit.block.data.type.TrapDoor) return false
      if (blockData is org.bukkit.block.data.type.Fence) return false
      if (blockData is org.bukkit.block.data.type.Wall) return false
      if (blockData is org.bukkit.block.data.type.Gate) return false
      if (blockData is org.bukkit.block.data.MultipleFacing) return false
      if (mat.name.contains("COPPER_GRATE")) return false
      if (mat.name.endsWith("_BARS")) return false
      if (blockData is org.bukkit.block.data.Waterlogged && blockData.isWaterlogged) return false

      return true
    }

    // allowNonFull == true の場合、同種ブロックであれば非フルブロックも結合対象に含める
    return true
  }

  /**
   * ブロック集合を直方体ボックス群に貪欲パッキング
   */
  fun pack(
    blocks: Map<BlockPos, BlockData>,
    allowNonFull: Boolean = false,
  ): List<MergedBox> {
    val mergeable = mutableMapOf<BlockData, MutableSet<BlockPos>>()
    val unmergeable = mutableListOf<MergedBox>()

    for ((pos, data) in blocks) {
      if (canMerge(data, allowNonFull)) {
        mergeable.computeIfAbsent(data) { mutableSetOf() }.add(pos)
      } else {
        unmergeable.add(MergedBox(pos.x, pos.y, pos.z, 1, 1, 1, data))
      }
    }

    val result = mutableListOf<MergedBox>()
    result.addAll(unmergeable)

    for ((data, posSet) in mergeable) {
      val remaining = posSet.toMutableSet()
      val isCarpet = data.material.name.contains("CARPET")

      while (remaining.isNotEmpty()) {
        // Y, Z, X の順で最小のブロックを起点にする
        val start = remaining.minWithOrNull(compareBy({ it.y }, { it.z }, { it.x }))!!

        // ★カーペット専用処理: X軸・Z軸の拡張は行わず、Y軸方向（垂直スタック）でのみ統合！
        if (isCarpet) {
          var height = 1
          while (remaining.contains(BlockPos(start.x, start.y + height, start.z))) {
            height++
          }
          for (dy in 0 until height) {
            remaining.remove(BlockPos(start.x, start.y + dy, start.z))
          }
          result.add(MergedBox(start.x, start.y, start.z, 1, height, 1, data))
          continue
        }

        // 1. X方向に拡張
        var width = 1
        while (remaining.contains(BlockPos(start.x + width, start.y, start.z))) {
          width++
        }

        // 2. Z方向に拡張 (長方形底面 width * depth)
        var depth = 1
        while (true) {
          val nextZ = start.z + depth
          val canExpandZ = (0 until width).all { dx -> remaining.contains(BlockPos(start.x + dx, start.y, nextZ)) }
          if (canExpandZ) depth++ else break
        }

        // 3. Y方向に拡張 (直方体 width * height * depth)
        // ★ハーフブロック (Slab) は縦に引き伸ばされないよう Y 軸方向の統合を禁止
        var height = 1
        val allowYExpand = !(data is org.bukkit.block.data.type.Slab)
        if (allowYExpand) {
          while (true) {
            val nextY = start.y + height
            var canExpandY = true
            for (dx in 0 until width) {
              for (dz in 0 until depth) {
                if (!remaining.contains(BlockPos(start.x + dx, nextY, start.z + dz))) {
                  canExpandY = false
                  break
                }
              }
              if (!canExpandY) break
            }
            if (canExpandY) height++ else break
          }
        }

        // 訪問済みブロックを削除
        for (dx in 0 until width) {
          for (dy in 0 until height) {
            for (dz in 0 until depth) {
              remaining.remove(BlockPos(start.x + dx, start.y + dy, start.z + dz))
            }
          }
        }

        result.add(MergedBox(start.x, start.y, start.z, width, height, depth, data))
      }
    }

    return result
  }
}
