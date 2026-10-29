package io.oyasai.karakuri.engine

import io.oyasai.karakuri.KarakuriPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Instrument
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.LightningRod
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ワイヤレスレッドストーン信号の周波数定義 (音階 0..24 + 音色/楽器)
 */
data class WirelessFrequency(
  val instrument: Instrument,
  val note: Int,
) {
  val displayName: String
    get() {
      val instName = when (instrument) {
        Instrument.PIANO -> "ピアノ"
        Instrument.BASS_DRUM -> "バスドラム"
        Instrument.SNARE_DRUM -> "スネアドラム"
        Instrument.STICKS -> "クリック"
        Instrument.BASS_GUITAR -> "ベース"
        Instrument.FLUTE -> "フルート"
        Instrument.BELL -> "鐘"
        Instrument.GUITAR -> "ギター"
        Instrument.CHIME -> "チャイム"
        Instrument.XYLOPHONE -> "木琴"
        Instrument.IRON_XYLOPHONE -> "鉄琴"
        Instrument.COW_BELL -> "カウベル"
        Instrument.DIDGERIDOO -> "ディジュリドゥ"
        Instrument.BIT -> "ビット"
        Instrument.BANJO -> "バンジョー"
        Instrument.PLING -> "プレング"
        Instrument.ZOMBIE -> "ゾンビの頭"
        Instrument.SKELETON -> "スケルトンの頭"
        Instrument.CREEPER -> "クリーパーの頭"
        Instrument.DRAGON -> "ドラゴンの頭"
        Instrument.WITHER_SKELETON -> "ウィザースケルトンの頭"
        Instrument.PIGLIN -> "ピグリンの頭"
        Instrument.CUSTOM_HEAD -> "プレイヤーの頭"
        else -> instrument.name
      }
      return "音の高さ${note}の${instName}"
    }

  fun serialize(): String = "${instrument.name}:$note"

  companion object {
    fun deserialize(str: String): WirelessFrequency? {
      val parts = str.split(":")
      if (parts.size != 2) return null
      val inst = try { Instrument.valueOf(parts[0]) } catch (_: Exception) { return null }
      val note = parts[1].toIntOrNull() ?: return null
      return WirelessFrequency(inst, note)
    }
  }
}

enum class WirelessNodeType {
  TRANSMITTER, // 送信機 (通常/風化 避雷針)
  RECEIVER,    // 受信機 (錆び/酸化 避雷針)
}

/**
 * ワイヤレスRSノード (送信機 / 受信機)
 */
data class WirelessNode(
  val id: String = UUID.randomUUID().toString(),
  val type: WirelessNodeType,
  val rodLocation: Location,
  val noteBlockLocation: Location,
  val baseBlockLocation: Location,
  val frequency: WirelessFrequency,
  var isPowered: Boolean = false,
  var pulseTicksRemaining: Int = 0,
  var isToggledOn: Boolean = false,
)

/**
 * ワイヤレスレッドストーン信号管理マネージャー
 */
class WirelessRedstoneManager(private val plugin: KarakuriPlugin) {

  companion object {
    const val MAX_DISTANCE = 64.0
    const val MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE

    /**
     * 送信機用の避雷針（通常・風化）判定
     */
    fun isTransmitterRod(material: Material): Boolean {
      val name = material.name
      return name == "LIGHTNING_ROD" ||
             name == "WAXED_LIGHTNING_ROD" ||
             name == "EXPOSED_LIGHTNING_ROD" ||
             name == "WAXED_EXPOSED_LIGHTNING_ROD"
    }

    /**
     * 受信機用の避雷針（錆び・酸化）判定
     */
    fun isReceiverRod(material: Material): Boolean {
      val name = material.name
      return name == "WEATHERED_LIGHTNING_ROD" ||
             name == "WAXED_WEATHERED_LIGHTNING_ROD" ||
             name == "OXIDIZED_LIGHTNING_ROD" ||
             name == "WAXED_OXIDIZED_LIGHTNING_ROD"
    }

    fun isAnyLightningRod(material: Material): Boolean =
      isTransmitterRod(material) || isReceiverRod(material)
  }

  // ノードID -> ノード
  val nodes = ConcurrentHashMap<String, WirelessNode>()

  // ブロック位置 (Longパック) -> ノード
  private val blockPosIndex = ConcurrentHashMap<String, MutableMap<Long, WirelessNode>>()

  private fun packBlockPos(x: Int, y: Int, z: Int): Long {
    return ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
  }

  fun registerNode(node: WirelessNode) {
    nodes[node.id] = node
    val worldName = node.rodLocation.world?.name ?: return
    val map = blockPosIndex.computeIfAbsent(worldName) { ConcurrentHashMap() }
    map[packBlockPos(node.rodLocation.blockX, node.rodLocation.blockY, node.rodLocation.blockZ)] = node
    map[packBlockPos(node.noteBlockLocation.blockX, node.noteBlockLocation.blockY, node.noteBlockLocation.blockZ)] = node
    map[packBlockPos(node.baseBlockLocation.blockX, node.baseBlockLocation.blockY, node.baseBlockLocation.blockZ)] = node
  }

  fun unregisterNode(nodeId: String): WirelessNode? {
    val node = nodes.remove(nodeId) ?: return null
    val worldName = node.rodLocation.world?.name
    if (worldName != null) {
      val map = blockPosIndex[worldName]
      if (map != null) {
        map.remove(packBlockPos(node.rodLocation.blockX, node.rodLocation.blockY, node.rodLocation.blockZ))
        map.remove(packBlockPos(node.noteBlockLocation.blockX, node.noteBlockLocation.blockY, node.noteBlockLocation.blockZ))
        map.remove(packBlockPos(node.baseBlockLocation.blockX, node.baseBlockLocation.blockY, node.baseBlockLocation.blockZ))
      }
    }
    // 受信機が給電中だった場合は給電を解除
    if (node.type == WirelessNodeType.RECEIVER) {
      setRodPower(node.rodLocation.block, false)
    }
    return node
  }

  fun getNodeAt(block: Block): WirelessNode? {
    val worldName = block.world.name
    val map = blockPosIndex[worldName] ?: return null
    return map[packBlockPos(block.x, block.y, block.z)]
  }

  fun isProtectedBlock(block: Block): Boolean = getNodeAt(block) != null

  data class StructureDetectResult(
    val type: WirelessNodeType,
    val rodBlock: Block,
    val noteBlock: Block,
    val baseBlock: Block,
    val frequency: WirelessFrequency,
  )

  /**
   * クリックされたブロックから「避雷針(上向き) + 音符ブロック + 音色ブロック」の3段構造を検出
   */
  fun detectStructure(clickedBlock: Block): StructureDetectResult? {
    val (rodBlock, noteBlock, baseBlock) = when {
      isAnyLightningRod(clickedBlock.type) -> {
        val rod = clickedBlock
        val note = clickedBlock.getRelative(BlockFace.DOWN)
        val base = note.getRelative(BlockFace.DOWN)
        Triple(rod, note, base)
      }
      clickedBlock.type == Material.NOTE_BLOCK -> {
        val rod = clickedBlock.getRelative(BlockFace.UP)
        val note = clickedBlock
        val base = clickedBlock.getRelative(BlockFace.DOWN)
        Triple(rod, note, base)
      }
      else -> return null
    }

    if (!isAnyLightningRod(rodBlock.type)) return null
    if (noteBlock.type != Material.NOTE_BLOCK) return null
    if (baseBlock.type.isAir) return null

    // 避雷針は上向き (facing == UP) であること
    val rodData = rodBlock.blockData as? LightningRod ?: return null
    if (rodData.facing != BlockFace.UP) return null

    val type = when {
      isTransmitterRod(rodBlock.type) -> WirelessNodeType.TRANSMITTER
      isReceiverRod(rodBlock.type) -> WirelessNodeType.RECEIVER
      else -> return null
    }

    val noteData = noteBlock.blockData as? NoteBlock ?: return null
    val freq = WirelessFrequency(noteData.instrument, noteData.note.id.toInt())

    return StructureDetectResult(type, rodBlock, noteBlock, baseBlock, freq)
  }

  /**
   * 毎Tick実行: 送信機の通電検知と受信機への信号送出
   */
  fun tick() {
    if (nodes.isEmpty()) return

    // 1. 各周波数ごとに、通電中の送信機リストを収集
    val activeTransmittersByFreq = mutableMapOf<WirelessFrequency, MutableList<WirelessNode>>()

    for (node in nodes.values) {
      if (node.type != WirelessNodeType.TRANSMITTER) continue
      val noteBlock = node.noteBlockLocation.block
      if (noteBlock.type != Material.NOTE_BLOCK) continue

      val powered = noteBlock.isBlockPowered || noteBlock.isBlockIndirectlyPowered
      node.isPowered = powered
      if (powered) {
        activeTransmittersByFreq.computeIfAbsent(node.frequency) { mutableListOf() }.add(node)
      }
    }

    // 2. 受信機の給電状態を判定・適用
    for (node in nodes.values) {
      if (node.type != WirelessNodeType.RECEIVER) continue
      val rodBlock = node.rodLocation.block
      if (!isAnyLightningRod(rodBlock.type)) continue

      val activeTransmitters = activeTransmittersByFreq[node.frequency]
      var isTransmitterInRange = false

      if (activeTransmitters != null) {
        for (tx in activeTransmitters) {
          if (tx.rodLocation.world == node.rodLocation.world &&
              tx.rodLocation.distanceSquared(node.rodLocation) <= MAX_DISTANCE_SQ) {
            isTransmitterInRange = true
            break
          }
        }
      }

      // 設置型の送信機が信号を送信してきた場合、リモコンによるトグルON状態を解除
      if (isTransmitterInRange && node.isToggledOn) {
        node.isToggledOn = false
      }

      // リモコンパルスタイマーのカウントダウン
      if (node.pulseTicksRemaining > 0) {
        node.pulseTicksRemaining--
      }

      val shouldPower = isTransmitterInRange || (node.pulseTicksRemaining > 0) || node.isToggledOn
      if (node.isPowered != shouldPower) {
        node.isPowered = shouldPower
        setRodPower(rodBlock, shouldPower)
      } else if (shouldPower) {
        // 通電継続中: バニラによるリセットを防ぐため確実に維持
        val data = rodBlock.blockData as? LightningRod
        if (data != null && !data.isPowered) {
          setRodPower(rodBlock, true)
        }
      }
    }
  }

  private fun setRodPower(rodBlock: Block, powered: Boolean) {
    val data = rodBlock.blockData as? LightningRod ?: return
    if (data.isPowered != powered) {
      data.isPowered = powered
      rodBlock.setBlockData(data, true)
      if (powered) {
        rodBlock.world.spawnParticle(Particle.ELECTRIC_SPARK, rodBlock.location.add(0.5, 0.5, 0.5), 10, 0.2, 0.2, 0.2, 0.05)
      }
    }
  }

  data class RemoteSignalResult(
    val totalReceivers: Int,
    val mode: io.oyasai.karakuri.tool.RemoteSignalMode,
    val toggledOnCount: Int = 0,
    val toggledOffCount: Int = 0,
  )

  /**
   * リモコンからの信号送信: プレイヤーの半径64ブロック以内の同周波数受信機を作動させる
   * @param mode PULSE: 1秒間パルス, TOGGLE: オン/オフ切り替え
   */
  fun triggerRemoteSignal(
    player: Player,
    frequency: WirelessFrequency,
    mode: io.oyasai.karakuri.tool.RemoteSignalMode = io.oyasai.karakuri.tool.RemoteSignalMode.PULSE,
  ): RemoteSignalResult {
    val pLoc = player.location
    var count = 0
    var onCount = 0
    var offCount = 0

    for (node in nodes.values) {
      if (node.type != WirelessNodeType.RECEIVER) continue
      if (node.frequency != frequency) continue
      if (node.rodLocation.world != pLoc.world) continue
      if (node.rodLocation.distanceSquared(pLoc) > MAX_DISTANCE_SQ) continue

      count++
      val rLoc = node.rodLocation.clone().add(0.5, 0.5, 0.5)

      if (mode == io.oyasai.karakuri.tool.RemoteSignalMode.PULSE) {
        node.pulseTicksRemaining = 20 // 1秒間 (20 ticks) 給電
        node.isPowered = true
        setRodPower(node.rodLocation.block, true)

        rLoc.world?.spawnParticle(Particle.ELECTRIC_SPARK, rLoc, 25, 0.3, 0.3, 0.3, 0.1)
        rLoc.world?.playSound(rLoc, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.6f)
      } else {
        // TOGGLE モード: 状態を反転
        node.isToggledOn = !node.isToggledOn
        node.pulseTicksRemaining = 0

        if (node.isToggledOn) {
          onCount++
          node.isPowered = true
          setRodPower(node.rodLocation.block, true)
          rLoc.world?.spawnParticle(Particle.ELECTRIC_SPARK, rLoc, 30, 0.3, 0.3, 0.3, 0.1)
          rLoc.world?.playSound(rLoc, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.8f)
        } else {
          offCount++
          node.isPowered = false
          setRodPower(node.rodLocation.block, false)
          rLoc.world?.spawnParticle(Particle.SMOKE, rLoc, 15, 0.2, 0.2, 0.2, 0.05)
          rLoc.world?.playSound(rLoc, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
        }
      }
    }

    if (count > 0) {
      if (mode == io.oyasai.karakuri.tool.RemoteSignalMode.PULSE) {
        player.playSound(pLoc, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.8f)
        player.spawnParticle(Particle.ELECTRIC_SPARK, pLoc.clone().add(0.0, 1.0, 0.0), 20, 0.3, 0.3, 0.3, 0.1)
      } else {
        if (onCount > 0) {
          player.playSound(pLoc, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 2.0f)
          player.spawnParticle(Particle.ELECTRIC_SPARK, pLoc.clone().add(0.0, 1.0, 0.0), 25, 0.3, 0.3, 0.3, 0.1)
        } else {
          player.playSound(pLoc, Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.8f)
          player.spawnParticle(Particle.SMOKE, pLoc.clone().add(0.0, 1.0, 0.0), 15, 0.2, 0.2, 0.2, 0.05)
        }
      }
    }

    return RemoteSignalResult(count, mode, onCount, offCount)
  }
}
