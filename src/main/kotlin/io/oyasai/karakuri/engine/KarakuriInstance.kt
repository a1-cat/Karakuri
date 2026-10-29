package io.oyasai.karakuri.engine

import io.oyasai.karakuri.motion.Axis
import io.oyasai.karakuri.motion.MotionController
import org.bukkit.Location
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI

/**
 * 1つの「からくり」（動くブロック機構）を表すクラス。
 * 単一ブロックおよび Create Mod スタイルの複数ブロック建築物（マルチブロック）を完全サポート。
 * BlockDisplay を採用し、西向きのエンドロッドや階段、デバッグ棒等の BlockData を100%完全保持。
 * 共通ピボット（worldPivotLocation）を中心に、X/Y/Z全軸で偏心ブレゼロの真円回転を実現。
 */
class KarakuriInstance(
  val id: String,
  var displayEntity: Display,
  var baseLocation: Location,
  val additionalParts: MutableList<BlockPart> = mutableListOf(),
  var interactionEntity: org.bukkit.entity.Interaction? = null,
  var originalBlockData: org.bukkit.block.data.BlockData? = null,
  var initialRightRotation: Quaternionf = Quaternionf(),
  var skullProfile: com.destroystokyo.paper.profile.PlayerProfile? = null,
) {
  /**
   * マルチブロックを構成する各ブロックパーツ
   */
  data class BlockPart(
    val displayEntity: Display,
    val originLocation: Location,
    val interactionEntity: org.bukkit.entity.Interaction? = null,
    val originalBlockData: org.bukkit.block.data.BlockData? = null,
    var initialRightRotation: Quaternionf = Quaternionf(),
    val skullProfile: com.destroystokyo.paper.profile.PlayerProfile? = null,
    val partScale: Vector3f = Vector3f(1.0f, 1.0f, 1.0f),
  )

  /**
   * からくりを構成する元の全ブロック情報 (1x1x1 単位)
   * 撤去時の完全復元や、レンチクリック時の個別ブロック特定に使用
   */
  val constituentBlocks: MutableMap<Location, org.bukkit.block.data.BlockData> = mutableMapOf()
  val constituentSkullProfiles: MutableMap<Location, com.destroystokyo.paper.profile.PlayerProfile> = mutableMapOf()

  /**
   * エンティティ統合（軽量化）が有効かどうか
   */
  var isOptimized: Boolean = false

  /**
   * 非フルブロック（ガラス、ハーフブロック、階段等）も統合対象に含めるかどうか
   */
  var optimizeNonFullBlocks: Boolean = false

  /**
   * 不動（手動作動ロック）: Shift+右クリックでの動作トリガーを無効化するかどうか
   */
  var isImmovable: Boolean = false

  /**
   * RS連動ブロック (ランプ、銅の電球、オブザーバー) のリスト
   */
  val redstoneLinks: MutableList<RedstoneLinkManager.RedstoneLink> = mutableListOf()

  /**
   * チャンク未ロード等による休眠状態フラグ（休眠中はエンティティが一時撤去されtick停止）
   */
  var isDormant: Boolean = false

  var lastKnownLocation: Location? = null

  /**
   * からくりが占有する全チャンクのキー集合 (Chunk.getChunkKey() 形式)
   */
  fun getOccupiedChunkKeys(): Set<Long> {
    val set = mutableSetOf<Long>()
    fun addLoc(loc: Location) {
      val cx = loc.blockX shr 4
      val cz = loc.blockZ shr 4
      val key = (cx.toLong() and 0xFFFFFFFFL) or ((cz.toLong() and 0xFFFFFFFFL) shl 32)
      set.add(key)
    }
    addLoc(originLocation)
    for (loc in constituentBlocks.keys) {
      addLoc(loc)
    }
    addLoc(worldPivotLocation)
    if (displayEntity.isValid) {
      addLoc(displayEntity.location)
    }
    for (part in additionalParts) {
      if (part.displayEntity.isValid) {
        addLoc(part.displayEntity.location)
      }
    }
    return set
  }

  /**
   * スポーン時またはピストンロッドで設定された不変の初期原点座標（ブロック角）
   */
  var originLocation: Location = baseLocation.clone()
    private set

  fun updateOrigin(newOrigin: Location) {
    val deltaX = newOrigin.x - originLocation.x
    val deltaY = newOrigin.y - originLocation.y
    val deltaZ = newOrigin.z - originLocation.z

    originLocation = newOrigin.clone()
    baseLocation = newOrigin.clone()

    if (additionalParts.isNotEmpty() && (deltaX != 0.0 || deltaY != 0.0 || deltaZ != 0.0)) {
      val newParts = additionalParts.map { part ->
        part.copy(originLocation = part.originLocation.clone().add(deltaX, deltaY, deltaZ))
      }
      additionalParts.clear()
      additionalParts.addAll(newParts)
    }
  }

  var activeMotion: MotionController? = null
    private set

  /**
   * 単一ブロック時のデフォルトローカルピボット。
   * ItemDisplay (NONE) は幾何中心が原点のため (0, 0, 0)。
   * BlockDisplay は原点が角のため幾何中心は (0.5, 0.5, 0.5)。
   */
  var pivotOffset: Vector3f = if (displayEntity is org.bukkit.entity.ItemDisplay) Vector3f(0f, 0f, 0f) else Vector3f(0.5f, 0.5f, 0.5f)

  /**
   * 基本拡大縮小 (1.0 で 1x1x1 フルブロックサイズ)
   */
  var scale: Vector3f = Vector3f(1.0f, 1.0f, 1.0f)

  /**
   * 回転軸の中心となるワールド絶対座標（原点静止時）。
   * デフォルトはルートブロックの中心。
   */
  var worldPivotLocation: Location = Location(
    originLocation.world,
    originLocation.blockX.toDouble() + 0.5,
    originLocation.blockY.toDouble() + 0.5,
    originLocation.blockZ.toDouble() + 0.5
  )

  init {
    displayEntity.teleportDuration = 0
    displayEntity.interpolationDuration = 0
    displayEntity.interpolationDelay = 0
  }

  /**
   * 指定したブロック座標を中心軸（ピボット）に設定する
   */
  fun setPivotToBlock(blockLoc: Location, syncToDb: Boolean = true) {
    worldPivotLocation = Location(
      blockLoc.world,
      blockLoc.blockX.toDouble() + 0.5,
      blockLoc.blockY.toDouble() + 0.5,
      blockLoc.blockZ.toDouble() + 0.5
    )
    val rootOffset = getBaseEntityOffset(displayEntity, originalBlockData)
    val dx = (worldPivotLocation.x - (originLocation.x + rootOffset.x.toDouble())).toFloat()
    val dy = (worldPivotLocation.y - (originLocation.y + rootOffset.y.toDouble())).toFloat()
    val dz = (worldPivotLocation.z - (originLocation.z + rootOffset.z.toDouble())).toFloat()
    pivotOffset = Vector3f(dx, dy, dz)
    if (syncToDb) {
      try {
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.syncInstanceGeometry(this)
      } catch (_: Exception) {}
    }
  }

  /**
   * 指定したブロック座標に対応する BlockData を取得する
   */
  fun getBlockDataAt(loc: Location): org.bukkit.block.data.BlockData? {
    constituentBlocks[loc]?.let { return it }
    for ((bLoc, data) in constituentBlocks) {
      if (bLoc.blockX == loc.blockX && bLoc.blockY == loc.blockY && bLoc.blockZ == loc.blockZ) {
        return data
      }
    }
    if (originLocation.blockX == loc.blockX && originLocation.blockY == loc.blockY && originLocation.blockZ == loc.blockZ) {
      return originalBlockData
    }
    for (part in additionalParts) {
      if (part.originLocation.blockX == loc.blockX && part.originLocation.blockY == loc.blockY && part.originLocation.blockZ == loc.blockZ) {
        return part.originalBlockData
      }
    }
    return null
  }

  /**
   * 指定した座標のブロックがエンティティ統合（複数ブロックとしてまとめられている）されているか判定
   */
  fun isBlockMerged(loc: Location): Boolean {
    if (!isOptimized) return false
    val bx = loc.blockX
    val by = loc.blockY
    val bz = loc.blockZ

    // ルートエンティティの直方体判定
    val rScaleX = kotlin.math.round(scale.x).toInt()
    val rScaleY = kotlin.math.round(scale.y).toInt()
    val rScaleZ = kotlin.math.round(scale.z).toInt()
    if (rScaleX > 1 || rScaleY > 1 || rScaleZ > 1) {
      val ox = originLocation.blockX
      val oy = originLocation.blockY
      val oz = originLocation.blockZ
      if (bx in ox until (ox + rScaleX) && by in oy until (oy + rScaleY) && bz in oz until (oz + rScaleZ)) {
        return true
      }
    }

    // パーツエンティティの直方体判定
    for (part in additionalParts) {
      val pScaleX = kotlin.math.round(part.partScale.x).toInt()
      val pScaleY = kotlin.math.round(part.partScale.y).toInt()
      val pScaleZ = kotlin.math.round(part.partScale.z).toInt()
      if (pScaleX > 1 || pScaleY > 1 || pScaleZ > 1) {
        val px = part.originLocation.blockX
        val py = part.originLocation.blockY
        val pz = part.originLocation.blockZ
        if (bx in px until (px + pScaleX) && by in py until (py + pScaleY) && bz in pz until (pz + pScaleZ)) {
          return true
        }
      }
    }
    return false
  }

  /**
   * 現在実行中のモーションが持つ回転軸を取得する（回転モーションでない場合は null）
   */
  fun getActiveRotationAxis(): io.oyasai.karakuri.motion.Axis? {
    return when (val motion = activeMotion) {
      is io.oyasai.karakuri.motion.ContinuousSpinMotion -> motion.axis
      is io.oyasai.karakuri.motion.AngleRotationMotion -> motion.axis
      is io.oyasai.karakuri.motion.DoorMotion -> motion.axis
      else -> null
    }
  }

  fun setMotion(motion: MotionController?, saveToDb: Boolean = true) {
    activeMotion?.onStop(this)
    activeMotion = motion
    if (motion != null) {
      // 前の動作モードでの最後の回転角度 (currentRotation) をそのまま維持して開始！
      motion.onStart(this)
    }
    if (saveToDb) {
      try {
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(this)
      } catch (_: Exception) {}
    }
  }

  fun stopMotion() {
    setMotion(null)
  }

  fun tick(currentTick: Long) {
    if (isDormant || !displayEntity.isValid) {
      return
    }
    val motion = activeMotion ?: return
    val continueRunning = motion.tick(this, currentTick)
    if (!continueRunning) {
      stopMotion()
    }
  }

  /**
   * 現在の回転姿勢クォータニオン
   */
  var currentRotation: Quaternionf = Quaternionf()
    private set

  var prevRotation: Quaternionf = Quaternionf()
    private set

  var prev2Rotation: Quaternionf = Quaternionf()
    private set

  enum class DynamicCompMode {
    OFF,
    V1_ENDPOINT,   // 現行逆フィルタ (端点完全一致、サジッタ残存15.3cm)
    V2_MINIMAX,    // 現行ミニマックス (等方ブースト、理論最大偏心8.58cm、軸リークあり)
    V2_ORTHOGONAL, // 回転軸直交分解ミニマックス (回転面のみブースト、軸変位0mm)
    V2_ORTHO_T1,   // 1-tick独立検証モード (durationTicks=1, 回転面直交射影即時補正)
    RIGID_SYNC     // 1-tick純粋剛体同期モード (durationTicks=1, g=1.0, 動径ブーストなし剛体並進)
  }

  /**
   * Entity Integration ON 時の高速回転動的サジッタ逆算補正モード（検証用トグル、デフォルト OFF）
   */
  var dynamicCompMode: DynamicCompMode = DynamicCompMode.OFF

  // 後方互換用プロパティ
  var enableDynamicCompensation: Boolean
    get() = dynamicCompMode != DynamicCompMode.OFF
    set(value) {
      dynamicCompMode = if (value) DynamicCompMode.V1_ENDPOINT else DynamicCompMode.OFF
    }

  fun resetRotationHistory(initRot: Quaternionf = currentRotation) {
    currentRotation = Quaternionf(initRot)
    prevRotation = Quaternionf(initRot)
    prev2Rotation = Quaternionf(initRot)
  }

  /**
   * 前TickからのY軸周り回転差分（度数法、MinecraftのYaw増分基準）
   */
  var lastDeltaYaw: Float = 0f
    private set

  /**
   * 前Tickからの3次元回転角度の大きさ（度数法、X/Y/Z全軸対応）
   */
  var lastDeltaAngleDeg: Float = 0f
    private set

  fun updateCurrentRotation(rot: Quaternionf) {
    recordRotation(rot)
  }

  private fun recordRotation(newRot: Quaternionf) {
    if (currentRotation.lengthSquared() > 0.001f) {
      val deltaQ = Quaternionf(newRot).mul(Quaternionf(currentRotation).conjugate())
      // 3次元全軸での真の回転角の大きさ [deg]
      val clampedW = kotlin.math.abs(deltaQ.w).coerceIn(0.0f, 1.0f)
      lastDeltaAngleDeg = (2.0 * kotlin.math.acos(clampedW.toDouble()) * (180.0 / PI)).toFloat()

      val fVec = Vector3f(0f, 0f, 1f).rotate(deltaQ)
      var dYaw = (-Math.toDegrees(kotlin.math.atan2(fVec.x.toDouble(), fVec.z.toDouble()))).toFloat()
      while (dYaw > 180f) dYaw -= 360f
      while (dYaw < -180f) dYaw += 360f
      lastDeltaYaw = dYaw
    } else {
      lastDeltaAngleDeg = 0f
      lastDeltaYaw = 0f
    }
    prev2Rotation = Quaternionf(prevRotation)
    prevRotation = Quaternionf(currentRotation)
    currentRotation = Quaternionf(newRot)
  }

  /**
   * ピボット(worldPivotLocation)から全パーツエンティティ原点までの最大距離を算出
   * クライアント側 Translation-LERP による幾何学的弦誤差（サジッタ）の判定に使用
   */
  fun getMaxPivotDistance(): Float {
    var maxDistSq = 0f
    val rootOffset = getBaseEntityOffset(displayEntity, originalBlockData)
    val rdx = (worldPivotLocation.x - (originLocation.x + rootOffset.x.toDouble())).toFloat()
    val rdy = (worldPivotLocation.y - (originLocation.y + rootOffset.y.toDouble())).toFloat()
    val rdz = (worldPivotLocation.z - (originLocation.z + rootOffset.z.toDouble())).toFloat()
    maxDistSq = maxOf(maxDistSq, rdx * rdx + rdy * rdy + rdz * rdz)

    for (part in additionalParts) {
      if (!part.displayEntity.isValid) continue
      val pOffset = getBaseEntityOffset(part.displayEntity, part.originalBlockData)
      val pdx = (worldPivotLocation.x - (part.originLocation.x + pOffset.x.toDouble())).toFloat()
      val pdy = (worldPivotLocation.y - (part.originLocation.y + pOffset.y.toDouble())).toFloat()
      val pdz = (worldPivotLocation.z - (part.originLocation.z + pOffset.z.toDouble())).toFloat()
      maxDistSq = maxOf(maxDistSq, pdx * pdx + pdy * pdy + pdz * pdz)
    }
    return kotlin.math.sqrt(maxDistSq)
  }

  /**
   * 水平回転（Y軸回転）専用の Yaw 首振り方式が有効かどうか
   */
  var isYawModeActive: Boolean = false
    private set

  /**
   * 水平回転（Y軸）専用：中心軸(worldPivotLocation)をエンティティの足元とし、
   * Yaw(首振り)を回すことで、クライアント側Translation-LERP(定規直線ショートカット)を完全排除し、
   * 弦誤差0.000mmかつ超滑らか補間(teleportDuration=1)で回転させる。
   */
  fun applyYawRotation(yawDeg: Float, rotation: Quaternionf) {
    recordRotation(rotation)
    val isFirstActivation = !isYawModeActive

    // 初回アクティブ化時、またはYawモード未適用の場合、固定オフセットTransformationを設定
    if (isFirstActivation) {
      isYawModeActive = true

      val priLeftRot = getPrimaryLeftRotation(Axis.Y)
      val rootTranslation = getPrimaryTranslation(Axis.Y, displayEntity, originalBlockData, originLocation)
      displayEntity.interpolationDuration = 0
      displayEntity.transformation = Transformation(rootTranslation, priLeftRot, scale, initialRightRotation)

      // 追加パーツ
      for (part in additionalParts) {
        if (!part.displayEntity.isValid) continue
        val partTranslation = getPrimaryTranslation(Axis.Y, part.displayEntity, part.originalBlockData, part.originLocation)
        part.displayEntity.interpolationDuration = 0
        part.displayEntity.transformation = Transformation(partTranslation, priLeftRot, part.partScale, part.initialRightRotation)
      }
    }

    // 毎tick: 中心軸に配置したまま Yaw だけを更新（初回のみ 0 でスナップ、以降は teleportDuration = 1 で滑らか真円補間！）
    val duration = if (isFirstActivation) 0 else 1
    val targetLoc = Location(worldPivotLocation.world, worldPivotLocation.x, worldPivotLocation.y, worldPivotLocation.z, yawDeg, 0.0f)

    displayEntity.teleportDuration = duration
    displayEntity.teleport(targetLoc)

    for (part in additionalParts) {
      if (part.displayEntity.isValid) {
        part.displayEntity.teleportDuration = duration
        part.displayEntity.teleport(targetLoc)
      }
    }
    syncInteractionEntities(rotation)
  }

  fun getPrimaryLeftRotation(axis: Axis): Quaternionf {
    return if (axis == Axis.Z) {
      Quaternionf().rotateY(PI.toFloat() / 2f)
    } else {
      Quaternionf()
    }
  }

  fun getSecondaryLeftRotation(axis: Axis): Quaternionf {
    return if (axis == Axis.Z) {
      Quaternionf().rotateX(PI.toFloat()).rotateY(PI.toFloat() / 2f)
    } else {
      Quaternionf().rotateX(PI.toFloat())
    }
  }

  fun getPrimaryTranslation(axis: Axis, entity: Display, blockData: org.bukkit.block.data.BlockData?, partOrigin: Location): Vector3f {
    val offset = getBaseEntityOffset(entity, blockData)
    val dx = (worldPivotLocation.x - (partOrigin.x + offset.x.toDouble())).toFloat()
    val dy = (worldPivotLocation.y - (partOrigin.y + offset.y.toDouble())).toFloat()
    val dz = (worldPivotLocation.z - (partOrigin.z + offset.z.toDouble())).toFloat()
    return if (axis == Axis.Z) {
      Vector3f(-dz, -dy, dx)
    } else {
      Vector3f(-dx, -dy, -dz)
    }
  }

  fun getSecondaryTranslation(axis: Axis, entity: Display, blockData: org.bukkit.block.data.BlockData?, partOrigin: Location): Vector3f {
    val offset = getBaseEntityOffset(entity, blockData)
    val dx = (worldPivotLocation.x - (partOrigin.x + offset.x.toDouble())).toFloat()
    val dy = (worldPivotLocation.y - (partOrigin.y + offset.y.toDouble())).toFloat()
    val dz = (worldPivotLocation.z - (partOrigin.z + offset.z.toDouble())).toFloat()
    return if (axis == Axis.Z) {
      Vector3f(-dz, dy, -dx)
    } else {
      Vector3f(-dx, dy, dz)
    }
  }

  data class SecondaryPart(val entity: Display, val originalScale: Vector3f)
  val secondaryParts: MutableList<SecondaryPart> = mutableListOf()
  var isPrimaryVisible: Boolean = true
    private set

  private fun cloneDisplayEntity(source: Display, location: Location): Display {
    val world = location.world ?: throw IllegalStateException("World cannot be null")
    val clone = if (source is BlockDisplay) {
      val bd = world.spawnEntity(location, org.bukkit.entity.EntityType.BLOCK_DISPLAY) as BlockDisplay
      bd.block = source.block
      bd
    } else if (source is org.bukkit.entity.ItemDisplay) {
      val itemDisp = world.spawnEntity(location, org.bukkit.entity.EntityType.ITEM_DISPLAY) as org.bukkit.entity.ItemDisplay
      source.itemStack?.let { itemDisp.setItemStack(it.clone()) }
      itemDisp.itemDisplayTransform = source.itemDisplayTransform
      itemDisp
    } else {
      world.spawnEntity(location, org.bukkit.entity.EntityType.BLOCK_DISPLAY) as BlockDisplay
    }
    clone.isPersistent = false
    clone.teleportDuration = 1
    clone.interpolationDuration = 0
    clone.interpolationDelay = 0
    clone.viewRange = source.viewRange
    clone.shadowRadius = source.shadowRadius
    clone.shadowStrength = source.shadowStrength
    clone.displayWidth = source.displayWidth
    clone.displayHeight = source.displayHeight
    source.brightness?.let { clone.brightness = it }
    clone.addScoreboardTag(KarakuriManager.TAG_ENTITY)
    clone.addScoreboardTag("${KarakuriManager.TAG_ID_PREFIX}$id")
    return clone
  }

  fun setupDualEntities(axis: Axis) {
    cleanupDualEntities()
    val world = worldPivotLocation.world ?: return
    val yawDeg = if (axis == Axis.Z) 90.0f else 0.0f
    val spawnLoc = Location(world, worldPivotLocation.x, worldPivotLocation.y, worldPivotLocation.z, yawDeg, 0.0f)
    val secLeftRot = getSecondaryLeftRotation(axis)

    // ルートエンティティの厳密な反転オフセット
    val rootTranslation = getSecondaryTranslation(axis, displayEntity, originalBlockData, originLocation)
    val rootSec = cloneDisplayEntity(displayEntity, spawnLoc)
    rootSec.transformation = Transformation(rootTranslation, secLeftRot, Vector3f(0f, 0f, 0f), initialRightRotation)
    secondaryParts.add(SecondaryPart(rootSec, Vector3f(scale)))

    // 追加パーツの厳密な反転オフセット
    for (part in additionalParts) {
      if (!part.displayEntity.isValid) continue
      val partTranslation = getSecondaryTranslation(axis, part.displayEntity, part.originalBlockData, part.originLocation)
      val partSec = cloneDisplayEntity(part.displayEntity, spawnLoc)
      partSec.transformation = Transformation(partTranslation, secLeftRot, Vector3f(0f, 0f, 0f), part.initialRightRotation)
      secondaryParts.add(SecondaryPart(partSec, Vector3f(part.partScale)))
    }

    isPrimaryVisible = true
  }

  fun cleanupDualEntities() {
    for (sec in secondaryParts) {
      if (sec.entity.isValid) {
        sec.entity.remove()
      }
    }
    secondaryParts.clear()
  }

  /**
   * 垂直回転（デュアルエンティティ方式）：
   * Primary (表側) と Secondary (裏側) の2組を使い、
   * どちらも急激な180度反転補間を行わず、teleportDuration = 1 の完全真円角度補間で回転させる。
   * これにより天頂・最下点の通過時のカクつき・フリップが 100% ゼロになり、中心軸ズレも完全解消！
   */
  fun applyDualPitchRotation(
    axis: Axis,
    primaryPitchDeg: Float,
    secondaryPitchDeg: Float,
    showPrimary: Boolean,
    rotation: Quaternionf
  ) {
    recordRotation(rotation)
    val yawDeg = if (axis == Axis.Z) 90.0f else 0.0f
    val isFirstActivation = !isYawModeActive
    val priLeftRot = getPrimaryLeftRotation(axis)
    val secLeftRot = getSecondaryLeftRotation(axis)

    // 初回初期化: Primary エンティティを中心軸・通常オフセットに設定
    if (isFirstActivation) {
      isYawModeActive = true

      val rootTranslation = getPrimaryTranslation(axis, displayEntity, originalBlockData, originLocation)
      displayEntity.interpolationDuration = 0
      displayEntity.transformation = Transformation(rootTranslation, priLeftRot, scale, initialRightRotation)

      // 追加パーツ
      for (part in additionalParts) {
        if (!part.displayEntity.isValid) continue
        val partTranslation = getPrimaryTranslation(axis, part.displayEntity, part.originalBlockData, part.originLocation)
        part.displayEntity.interpolationDuration = 0
        part.displayEntity.transformation = Transformation(partTranslation, priLeftRot, part.partScale, part.initialRightRotation)
      }
    }

    // 表示状態の切り替え (表/裏が交代した瞬間のみ scale を更新)
    if (isPrimaryVisible != showPrimary) {
      isPrimaryVisible = showPrimary

      // Primary の表示切替
      val primaryScale = if (showPrimary) scale else Vector3f(0f, 0f, 0f)
      val rootTranslation = getPrimaryTranslation(axis, displayEntity, originalBlockData, originLocation)
      displayEntity.interpolationDuration = 0
      displayEntity.transformation = Transformation(rootTranslation, priLeftRot, primaryScale, initialRightRotation)

      for (part in additionalParts) {
        if (!part.displayEntity.isValid) continue
        val pScale = if (showPrimary) part.partScale else Vector3f(0f, 0f, 0f)
        val partTranslation = getPrimaryTranslation(axis, part.displayEntity, part.originalBlockData, part.originLocation)
        part.displayEntity.interpolationDuration = 0
        part.displayEntity.transformation = Transformation(partTranslation, priLeftRot, pScale, part.initialRightRotation)
      }

      // Secondary の表示切替
      for (sec in secondaryParts) {
        if (!sec.entity.isValid) continue
        val sScale = if (!showPrimary) sec.originalScale else Vector3f(0f, 0f, 0f)
        sec.entity.interpolationDuration = 0
        val currentT = sec.entity.transformation
        sec.entity.transformation = Transformation(currentT.translation, secLeftRot, sScale, currentT.rightRotation)
      }
    }

    // テレポート更新: 初回のみ 0 (原点からの移動補間スキップ)、以降は常に 1 を維持して極上の滑らか補間！
    val duration = if (isFirstActivation) 0 else 1
    val primaryTarget = Location(worldPivotLocation.world, worldPivotLocation.x, worldPivotLocation.y, worldPivotLocation.z, yawDeg, primaryPitchDeg)
    val secondaryTarget = Location(worldPivotLocation.world, worldPivotLocation.x, worldPivotLocation.y, worldPivotLocation.z, yawDeg, secondaryPitchDeg)

    displayEntity.teleportDuration = duration
    displayEntity.teleport(primaryTarget)
    for (part in additionalParts) {
      if (part.displayEntity.isValid) {
        part.displayEntity.teleportDuration = duration
        part.displayEntity.teleport(primaryTarget)
      }
    }

    for (sec in secondaryParts) {
      if (sec.entity.isValid) {
        sec.entity.teleportDuration = duration
        sec.entity.teleport(secondaryTarget)
      }
    }
    syncInteractionEntities(rotation)
  }

  fun syncInteractionEntities(rotation: Quaternionf) {
    val world = worldPivotLocation.world ?: return
    val inter = interactionEntity
    if (inter != null && inter.isValid) {
      val isFlippedY = kotlin.math.abs(initialRightRotation.y) > 0.001f
      val isFlippedX = kotlin.math.abs(initialRightRotation.x) > 0.001f
      val cx = if (isFlippedY) originLocation.x - scale.x * 0.5 else originLocation.x + scale.x * 0.5
      val cy = if (isFlippedX) originLocation.y - scale.y else originLocation.y
      val cz = if (isFlippedY || isFlippedX) originLocation.z - scale.z * 0.5 else originLocation.z + scale.z * 0.5

      val initFoot = Vector3f(
        (cx - worldPivotLocation.x).toFloat(),
        (cy - worldPivotLocation.y).toFloat(),
        (cz - worldPivotLocation.z).toFloat()
      )
      val rotFoot = Vector3f(initFoot).rotate(rotation)
      inter.teleport(Location(world, worldPivotLocation.x + rotFoot.x.toDouble(), worldPivotLocation.y + rotFoot.y.toDouble(), worldPivotLocation.z + rotFoot.z.toDouble()))
    }

    for (part in additionalParts) {
      val pInter = part.interactionEntity
      if (pInter != null && pInter.isValid) {
        val isFlippedY = kotlin.math.abs(part.initialRightRotation.y) > 0.001f
        val isFlippedX = kotlin.math.abs(part.initialRightRotation.x) > 0.001f
        val cx = if (isFlippedY) part.originLocation.x - part.partScale.x * 0.5 else part.originLocation.x + part.partScale.x * 0.5
        val cy = if (isFlippedX) part.originLocation.y - part.partScale.y else part.originLocation.y
        val cz = if (isFlippedY || isFlippedX) part.originLocation.z - part.partScale.z * 0.5 else part.originLocation.z + part.partScale.z * 0.5

        val pInitFoot = Vector3f(
          (cx - worldPivotLocation.x).toFloat(),
          (cy - worldPivotLocation.y).toFloat(),
          (cz - worldPivotLocation.z).toFloat()
        )
        val pRotFoot = Vector3f(pInitFoot).rotate(rotation)
        pInter.teleport(Location(world, worldPivotLocation.x + pRotFoot.x.toDouble(), worldPivotLocation.y + pRotFoot.y.toDouble(), worldPivotLocation.z + pRotFoot.z.toDouble()))
      }
    }
  }

  /**
   * Yaw/Pitchモードを解除し、各エンティティの位置とTransformationを標準のクォータニオン座標系に戻す
   */
  fun restoreFromYawMode() {
    if (!isYawModeActive) return
    isYawModeActive = false
    cleanupDualEntities()

    // ルートエンティティを元の originLocation に戻す
    val rootOffset = getBaseEntityOffset(displayEntity, originalBlockData)
    val rootTarget = originLocation.clone().add(rootOffset.x.toDouble(), rootOffset.y.toDouble(), rootOffset.z.toDouble())
    rootTarget.yaw = 0f
    rootTarget.pitch = 0f
    displayEntity.teleportDuration = 0
    displayEntity.teleport(rootTarget)

    // 追加パーツを元の各 originLocation に戻す
    for (part in additionalParts) {
      if (!part.displayEntity.isValid) continue
      val partOffset = getBaseEntityOffset(part.displayEntity, part.originalBlockData)
      val partTarget = part.originLocation.clone().add(partOffset.x.toDouble(), partOffset.y.toDouble(), partOffset.z.toDouble())
      partTarget.yaw = 0f
      partTarget.pitch = 0f
      part.displayEntity.teleportDuration = 0
      part.displayEntity.teleport(partTarget)
    }

    // 現在の回転姿勢を標準方式で反映
    applyRotation(currentRotation, 0, 0, null)
  }

  /**
   * 指定したピボットオフセットと回転クォータニオンから Transformation を計算して適用する。
   * 単体ブロック・マルチブロック問わず、指定された回転軸中心を中心に完全に対称回転する。
   */
  fun applyRotation(
    rotation: Quaternionf,
    durationTicks: Int,
    delayTicks: Int = 0,
    customPivot: Vector3f? = null,
  ) {
    if (isYawModeActive) {
      restoreFromYawMode()
    }

    // 連続回転の位相を維持し、周期的フリップ（反転飛び）を完全に排除
    recordRotation(rotation)

    // 1. ルートエンティティへの適用
    applyPartRotation(
      displayEntity,
      originLocation,
      rotation,
      durationTicks,
      delayTicks,
      customPivot,
      initialRightRotation,
      scale,
      originalBlockData
    )

    // 2. 追加パーツ（複数ブロック）への適用
    for (part in additionalParts) {
      if (part.displayEntity.isValid) {
        applyPartRotation(
          part.displayEntity,
          part.originLocation,
          rotation,
          durationTicks,
          delayTicks,
          null,
          part.initialRightRotation,
          part.partScale,
          part.originalBlockData
        )
      }
    }

    // 3. Interaction エンティティの同期（クリック判定・右クリック操作）
    syncInteractionEntities(rotation)
  }

  fun getBaseEntityOffset(entity: Display, blockData: org.bukkit.block.data.BlockData?): Vector3f {
    if (entity is org.bukkit.entity.ItemDisplay) {
      val wallOffset = KarakuriManager.getPlayerWallHeadOffset(blockData)
      return Vector3f(0.5f + wallOffset.x, 0.5f + wallOffset.y, 0.5f + wallOffset.z)
    }
    return Vector3f(0f, 0f, 0f)
  }

  private fun applyPartRotation(
    entity: Display,
    partOrigin: Location,
    rotation: Quaternionf,
    durationTicks: Int,
    delayTicks: Int,
    overridePivot: Vector3f?,
    rightRot: Quaternionf,
    currentScale: Vector3f,
    partBlockData: org.bukkit.block.data.BlockData? = null,
  ) {
    // ピボットベクトル（X/Y/Z対称計算）:
    // overridePivot (明示的カスタムピボット) があればそれを最優先。
    // なければ worldPivotLocation と各エンティティの offset から厳密にワールド中心相対ベクトルを算出！
    // （これにより ItemDisplay / BlockDisplay の切り替えやマルチブロック統合時でも 0.5マスの南東ズレを100%防止）
    val pivot = if (overridePivot != null) {
      overridePivot
    } else {
      val offset = getBaseEntityOffset(entity, partBlockData)
      val dx = (worldPivotLocation.x - (partOrigin.x + offset.x.toDouble())).toFloat()
      val dy = (worldPivotLocation.y - (partOrigin.y + offset.y.toDouble())).toFloat()
      val dz = (worldPivotLocation.z - (partOrigin.z + offset.z.toDouble())).toFloat()
      Vector3f(dx, dy, dz)
    }

    val isMergedBlockDisplay = isOptimized && entity is BlockDisplay && (currentScale.x > 1.05f || currentScale.y > 1.05f || currentScale.z > 1.05f)

    val translation = if (pivot.lengthSquared() < 0.0001f) {
      Vector3f(0f, 0f, 0f)
    } else if (dynamicCompMode == DynamicCompMode.RIGID_SYNC && durationTicks == 1) {
      // RIGID_SYNC: 1-tick 純粋剛体同期モード (durationTicks=1, g=1.0, 動径ブーストなし剛体並進)
      // 全パーツ同一の剛体並進 T = p - R*p を適用し、更新時点の接合部誤差を数学的に 0.0mm に固定
      val rotatedPivot = Vector3f(pivot).rotate(rotation)
      Vector3f(pivot).sub(rotatedPivot)
    } else if (dynamicCompMode == DynamicCompMode.V2_ORTHO_T1 && durationTicks == 1) {
      // v2-Ortho-T1: 1-tick 独立検証モード (durationTicks=1, 回転面直交射影即時補正)
      // 統合ON/OFF問わず、ピボットから離れた全パーツの弦誤差を相殺して五角形公転を完全排除
      // 回転軸ベクトル a を取得し、pivot を軸平行成分と回転平面直交成分に分解
      val axisVec = getActiveRotationAxis()?.vector ?: Vector3f(0f, 0f, 1f)
      val dot = pivot.dot(axisVec)
      val pParallel = Vector3f(axisVec).mul(dot)
      val pPerp = Vector3f(pivot).sub(pParallel)

      // 軸平行成分の変位量を評価 (ワールド固定座標系)
      // 単一軸回転時は R(pParallel) == pParallel (変位 0)。多軸合成時は R(pParallel) != pParallel となる。
      val rotatedParallelCurr = Vector3f(pParallel).rotate(rotation)
      val deltaParallel = Vector3f(rotatedParallelCurr).sub(pParallel).length()

      if (deltaParallel < 0.005f) {
        // 単一軸回転時: 従来の直交射影ミニマックス補正 (統合ON g=1.121269, 統合OFF g=1.0) を完全維持
        val rotatedPerpCurr = Vector3f(pPerp).rotate(rotation)
        val g = if (isMergedBlockDisplay) 1.121269f else 1.0f
        Vector3f(pPerp).sub(Vector3f(rotatedPerpCurr).mul(g))
      } else {
        // 多軸合成回転時: 一般剛体式へ安全フォールバック (数mの位置飛び・姿勢破壊を防止)
        val rotatedPivot = Vector3f(pivot).rotate(rotation)
        Vector3f(pivot).sub(rotatedPivot)
      }
    } else if (dynamicCompMode != DynamicCompMode.OFF && isMergedBlockDisplay && durationTicks == 2 && prev2Rotation.lengthSquared() > 0.001f) {
      val isTransient = prevRotation == prev2Rotation

      if (dynamicCompMode == DynamicCompMode.V2_ORTHOGONAL) {
        // v2-Orthogonal: 回転軸直交分解ミニマックス方式
        // 回転軸ベクトル a を取得し、pivot を軸平行成分と回転平面直交成分に分解
        val axisVec = getActiveRotationAxis()?.vector ?: Vector3f(0f, 0f, 1f)
        val dot = pivot.dot(axisVec)
        val pParallel = Vector3f(axisVec).mul(dot)
        val pPerp = Vector3f(pivot).sub(pParallel)

        val rotatedPerp1 = Vector3f(pPerp).rotate(prevRotation)
        val rotatedPerp2 = Vector3f(pPerp).rotate(prev2Rotation)

        val g = if (!isTransient) 1.121269f else 1.0f

        val c1 = Vector3f(pPerp).sub(Vector3f(rotatedPerp1).mul(g))
        val c2 = Vector3f(pPerp).sub(Vector3f(rotatedPerp2).mul(g))
        Vector3f(c1).mul(2.0f).sub(c2)
      } else {
        val rotated1 = Vector3f(pivot).rotate(prevRotation)
        val rotated2 = Vector3f(pivot).rotate(prev2Rotation)

        // v1 (Endpoint Matching): g = 1.0f (端点完全一致、サジッタ残存 15.3cm)
        // v2 (Minimax Approximation): g = 1.121269f (動径ブースト、最大偏心 8.58cm)
        // 過渡応答の安全性: 回転開始直後 (prevRotation == prev2Rotation) は g = 1.0f で初期化
        val g = if (dynamicCompMode == DynamicCompMode.V2_MINIMAX && !isTransient) 1.121269f else 1.0f

        val c1 = Vector3f(pivot).sub(Vector3f(rotated1).mul(g))
        val c2 = Vector3f(pivot).sub(Vector3f(rotated2).mul(g))
        Vector3f(c1).mul(2.0f).sub(c2)
      }
    } else {
      val rotatedPivot = Vector3f(pivot).rotate(rotation)
      Vector3f(pivot).sub(rotatedPivot)
    }

    entity.interpolationDuration = durationTicks
    entity.interpolationDelay = delayTicks
    entity.transformation = Transformation(translation, rotation, currentScale, rightRot)
  }

  /**
   * 直線移動と回転を同一 Tick で完全位相同期して適用する（全パーツ完全同期）
   * targetLocation は原点 (originLocation) に対する目標地点
   */
  fun applySynchronizedMotion(targetLocation: Location, rotation: Quaternionf, durationTicks: Int = 1) {
    applyRotation(rotation, durationTicks, 0, null)

    val deltaX = targetLocation.x - originLocation.x
    val deltaY = targetLocation.y - originLocation.y
    val deltaZ = targetLocation.z - originLocation.z

    val rootOffset = getBaseEntityOffset(displayEntity, originalBlockData)
    val rootTarget = originLocation.clone().add(rootOffset.x.toDouble() + deltaX, rootOffset.y.toDouble() + deltaY, rootOffset.z.toDouble() + deltaZ)
    displayEntity.teleportDuration = durationTicks
    displayEntity.teleport(rootTarget)

    val inter = interactionEntity
    if (inter != null && inter.isValid) {
      val interTarget = originLocation.clone().add((scale.x * 0.5) + deltaX, deltaY, (scale.z * 0.5) + deltaZ)
      inter.teleport(interTarget)
    }

    for (part in additionalParts) {
      if (part.displayEntity.isValid) {
        val partOffset = getBaseEntityOffset(part.displayEntity, part.originalBlockData)
        val partTarget = part.originLocation.clone().add(partOffset.x.toDouble() + deltaX, partOffset.y.toDouble() + deltaY, partOffset.z.toDouble() + deltaZ)
        part.displayEntity.teleportDuration = durationTicks
        part.displayEntity.teleport(partTarget)

        if (part.interactionEntity != null && part.interactionEntity.isValid) {
          val partInterTarget = part.originLocation.clone().add((part.partScale.x * 0.5) + deltaX, deltaY, (part.partScale.z * 0.5) + deltaZ)
          part.interactionEntity.teleport(partInterTarget)
        }
      }
    }
  }

  /**
   * スムーズな直線テレポート移動を適用する（全パーツ同期）
   * targetLocation は原点 (originLocation) に対する目標地点
   */
  fun applySmoothTeleport(targetLocation: Location, durationTicks: Int) {
    val deltaX = targetLocation.x - originLocation.x
    val deltaY = targetLocation.y - originLocation.y
    val deltaZ = targetLocation.z - originLocation.z

    val rootOffset = getBaseEntityOffset(displayEntity, originalBlockData)
    val rootTarget = originLocation.clone().add(rootOffset.x.toDouble() + deltaX, rootOffset.y.toDouble() + deltaY, rootOffset.z.toDouble() + deltaZ)
    displayEntity.teleportDuration = durationTicks
    displayEntity.teleport(rootTarget)

    val inter = interactionEntity
    if (inter != null && inter.isValid) {
      val interTarget = originLocation.clone().add((scale.x * 0.5) + deltaX, deltaY, (scale.z * 0.5) + deltaZ)
      inter.teleport(interTarget)
    }

    for (part in additionalParts) {
      if (part.displayEntity.isValid) {
        val partOffset = getBaseEntityOffset(part.displayEntity, part.originalBlockData)
        val partTarget = part.originLocation.clone().add(partOffset.x.toDouble() + deltaX, partOffset.y.toDouble() + deltaY, partOffset.z.toDouble() + deltaZ)
        part.displayEntity.teleportDuration = durationTicks
        part.displayEntity.teleport(partTarget)

        if (part.interactionEntity != null && part.interactionEntity.isValid) {
          val partInterTarget = part.originLocation.clone().add((part.partScale.x * 0.5) + deltaX, deltaY, (part.partScale.z * 0.5) + deltaZ)
          part.interactionEntity.teleport(partInterTarget)
        }
      }
    }
  }

  /**
   * からくりエンティティをワールドから削除する（表示の撤去のみ。DBのモーション設定は破棄しない）
   */
  fun remove() {
    activeMotion?.onStop(this)
    if (displayEntity.isValid) {
      displayEntity.remove()
    }
    interactionEntity?.remove()
    for (part in additionalParts) {
      if (part.displayEntity.isValid) {
        part.displayEntity.remove()
      }
      part.interactionEntity?.remove()
    }
    additionalParts.clear()
  }

  /**
   * からくりを初期位置・初期姿勢に即座にリセットする
   */
  fun resetToBase(stopMotion: Boolean = true) {
    if (stopMotion) {
      activeMotion?.onStop(this)
      when (val m = activeMotion) {
        is io.oyasai.karakuri.motion.ContinuousSpinMotion -> {
          m.isRunning = false
          m.resetOrientation()
        }
        is io.oyasai.karakuri.motion.IntegratedLinearMotion -> m.isRunning = false
        is io.oyasai.karakuri.motion.ProgramMotion -> m.pause()
        is io.oyasai.karakuri.motion.ChildContraptionMotion -> m.isSpinActive = false
        is io.oyasai.karakuri.motion.ChainChildContraptionMotion -> {
          m.isRunning = false
          m.isSpinActive = false
        }
        else -> {}
      }
      try {
        io.oyasai.karakuri.KarakuriPlugin.instance.manager.saveInstanceMotion(this)
      } catch (_: Exception) {}
    }
    currentRotation = Quaternionf()
    resetRotationHistory(Quaternionf())
    baseLocation = originLocation.clone()
    val rootOffset = getBaseEntityOffset(displayEntity, originalBlockData)
    val targetLoc = originLocation.clone().add(rootOffset.x.toDouble(), rootOffset.y.toDouble(), rootOffset.z.toDouble())
    displayEntity.teleportDuration = 0
    displayEntity.teleport(targetLoc)
    interactionEntity?.teleport(originLocation.clone().add(scale.x * 0.5, 0.0, scale.z * 0.5))

    for (part in additionalParts) {
      if (part.displayEntity.isValid) {
        val partOffset = getBaseEntityOffset(part.displayEntity, part.originalBlockData)
        val partTarget = part.originLocation.clone().add(partOffset.x.toDouble(), partOffset.y.toDouble(), partOffset.z.toDouble())
        part.displayEntity.teleportDuration = 0
        part.displayEntity.teleport(partTarget)
        part.interactionEntity?.teleport(part.originLocation.clone().add(part.partScale.x * 0.5, 0.0, part.partScale.z * 0.5))
      }
    }
    applyRotation(Quaternionf(), 0, 0, null)

    // 自身を親とする子構造が存在する場合、子構造も即座にスナップリセット
    try {
      for (other in io.oyasai.karakuri.KarakuriPlugin.instance.manager.getAll()) {
        val childMotion = other.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion
        if (childMotion != null && childMotion.parentId == id) {
          childMotion.requestSnap()
          childMotion.tick(other, 0)
        }
        val chainChildMotion = other.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion
        if (chainChildMotion != null && chainChildMotion.parentId == id) {
          chainChildMotion.requestSnap()
          chainChildMotion.tick(other, 0)
        }
      }
    } catch (_: Exception) {}
  }

  fun isValid(): Boolean = displayEntity.isValid
}
