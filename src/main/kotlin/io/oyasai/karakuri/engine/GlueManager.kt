package io.oyasai.karakuri.engine

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * 建築物の接着範囲を管理するマネージャー。
 * Create Mod の Super Glue に準拠し、複数エリアの作成・重複合体・枠線パーティクル表示を担う。
 */
class GlueManager(private val plugin: Plugin) {

  data class GlueArea(
    val id: UUID = UUID.randomUUID(),
    val worldName: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
  ) {
    fun toBoundingBox(): BoundingBox {
      return BoundingBox(
        minX.toDouble(),
        minY.toDouble(),
        minZ.toDouble(),
        (maxX + 1).toDouble(),
        (maxY + 1).toDouble(),
        (maxZ + 1).toDouble()
      )
    }

    fun contains(loc: Location): Boolean {
      if (loc.world.name != worldName) return false
      return loc.blockX in minX..maxX && loc.blockY in minY..maxY && loc.blockZ in minZ..maxZ
    }

    /**
     * 他の接着エリアと交差または隣接しているか判定（合体用）
     */
    fun intersectsOrTouches(other: GlueArea): Boolean {
      if (worldName != other.worldName) return false
      // 1ブロック以内の隣接または重なり
      return minX <= other.maxX + 1 && maxX + 1 >= other.minX &&
        minY <= other.maxY + 1 && maxY + 1 >= other.minY &&
        minZ <= other.maxZ + 1 && maxZ + 1 >= other.minZ
    }
  }

  private val areas = ConcurrentHashMap<UUID, GlueArea>()
  private var particleTask: BukkitTask? = null

  fun start() {
    particleTask?.cancel()
    // 5 tick (0.25秒) ごとに枠線パーティクルを描画
    particleTask = object : BukkitRunnable() {
      override fun run() {
        renderParticles()
      }
    }.runTaskTimer(plugin, 10L, 5L)
  }

  fun stop() {
    particleTask?.cancel()
    particleTask = null
  }

  /**
   * 接着エリアを新規追加
   */
  fun addArea(p1: Location, p2: Location): GlueArea {
    val world = p1.world
    val minX = min(p1.blockX, p2.blockX)
    val maxX = max(p1.blockX, p2.blockX)
    val minY = min(p1.blockY, p2.blockY)
    val maxY = max(p1.blockY, p2.blockY)
    val minZ = min(p1.blockZ, p2.blockZ)
    val maxZ = max(p1.blockZ, p2.blockZ)

    val area = GlueArea(
      worldName = world.name,
      minX = minX,
      minY = minY,
      minZ = minZ,
      maxX = maxX,
      maxY = maxY,
      maxZ = maxZ
    )
    areas[area.id] = area
    return area
  }

  fun removeArea(id: UUID): GlueArea? = areas.remove(id)

  /**
   * 未変換の接着エリアをすべて消去する
   */
  fun clearAll(): Int {
    val count = areas.size
    areas.clear()
    return count
  }

  fun getAllAreas(): List<GlueArea> = areas.values.toList()

  /**
   * プレイヤーの視線先にある接着エリアを探す（消去用）
   */
  fun rayTraceArea(player: Player, maxDistance: Double = 6.0): GlueArea? {
    val eyeLoc = player.eyeLocation
    val dir = eyeLoc.direction
    val startVec = eyeLoc.toVector()

    var closestArea: GlueArea? = null
    var closestDist = Double.MAX_VALUE

    for (area in areas.values) {
      if (area.worldName != eyeLoc.world.name) continue
      val box = area.toBoundingBox()
      val rayResult = box.rayTrace(startVec, dir, maxDistance)
      if (rayResult != null) {
        val dist = rayResult.hitPosition.distance(startVec)
        if (dist < closestDist) {
          closestDist = dist
          closestArea = area
        }
      }
    }
    return closestArea
  }

  /**
   * 指定したブロックが含まれる接着エリアと、それに重なり合っている全ての接着エリアを合体収集する
   */
  fun getConnectedAreasForBlock(blockLoc: Location): List<GlueArea> {
    val initialArea = areas.values.firstOrNull { it.contains(blockLoc) } ?: return emptyList()

    val connected = mutableSetOf<GlueArea>()
    val queue = ArrayDeque<GlueArea>()
    queue.add(initialArea)
    connected.add(initialArea)

    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      for (other in areas.values) {
        if (other !in connected && current.intersectsOrTouches(other)) {
          connected.add(other)
          queue.add(other)
        }
      }
    }
    return connected.toList()
  }

  /**
   * 指定された接着エリア群に含まれる、空気以外のすべての通常ブロックを取得する
   */
  fun getSolidBlocksInAreas(connectedAreas: List<GlueArea>): List<Block> {
    if (connectedAreas.isEmpty()) return emptyList()
    val world = plugin.server.getWorld(connectedAreas.first().worldName) ?: return emptyList()
    val blocks = mutableListOf<Block>()

    for (area in connectedAreas) {
      for (x in area.minX..area.maxX) {
        for (y in area.minY..area.maxY) {
          for (z in area.minZ..area.maxZ) {
            val b = world.getBlockAt(x, y, z)
            if (!b.type.isAir && b.type != org.bukkit.Material.BEDROCK) {
              if (!blocks.contains(b)) {
                blocks.add(b)
              }
            }
          }
        }
      }
    }
    return blocks
  }

  /**
   * 接着エリア枠線のパーティクル描画
   */
  private fun renderParticles() {
    if (areas.isEmpty()) return

    // 鮮やかなサルファーイエロー (R: 255, G: 215, B: 20)
    val dustOption = Particle.DustOptions(Color.fromRGB(255, 215, 20), 1.0f)

    for (area in areas.values) {
      val world = plugin.server.getWorld(area.worldName) ?: continue

      val minX = area.minX.toDouble()
      val minY = area.minY.toDouble()
      val minZ = area.minZ.toDouble()
      val maxX = (area.maxX + 1).toDouble()
      val maxY = (area.maxY + 1).toDouble()
      val maxZ = (area.maxZ + 1).toDouble()

      // 8つの頂点
      val c000 = Vector(minX, minY, minZ)
      val c100 = Vector(maxX, minY, minZ)
      val c010 = Vector(minX, maxY, minZ)
      val c110 = Vector(maxX, maxY, minZ)
      val c001 = Vector(minX, minY, maxZ)
      val c101 = Vector(maxX, minY, maxZ)
      val c011 = Vector(minX, maxY, maxZ)
      val c111 = Vector(maxX, maxY, maxZ)

      // 12本の辺
      drawEdge(world, c000, c100, dustOption)
      drawEdge(world, c100, c110, dustOption)
      drawEdge(world, c110, c010, dustOption)
      drawEdge(world, c010, c000, dustOption)

      drawEdge(world, c001, c101, dustOption)
      drawEdge(world, c101, c111, dustOption)
      drawEdge(world, c111, c011, dustOption)
      drawEdge(world, c011, c001, dustOption)

      drawEdge(world, c000, c001, dustOption)
      drawEdge(world, c100, c101, dustOption)
      drawEdge(world, c110, c111, dustOption)
      drawEdge(world, c010, c011, dustOption)
    }
  }

  private fun drawEdge(world: World, from: Vector, to: Vector, dust: Particle.DustOptions) {
    val dist = from.distance(to)
    val steps = (dist * 2).toInt().coerceAtLeast(1)
    val diff = to.clone().subtract(from).multiply(1.0 / steps)

    val current = from.clone()
    for (i in 0..steps) {
      world.spawnParticle(Particle.DUST, current.x, current.y, current.z, 1, 0.0, 0.0, 0.0, 0.0, dust)
      current.add(diff)
    }
  }
}
