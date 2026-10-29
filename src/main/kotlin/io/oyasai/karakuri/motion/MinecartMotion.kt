package io.oyasai.karakuri.motion

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.engine.KarakuriManager
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Rotatable
import org.bukkit.entity.Minecart
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * からくりを通常トロッコ (RideableMinecart) に合体・連動追従させる高精度モーションコントローラー。
 *
 * - からくりの「回転の中心軸（worldPivotLocation）」がトロッコの中央に乗るように配置
 * - followRotation: トロッコの進行方向に合わせてからくりの向きを連動回転
 * - forwardFacing: からくりの「前側（正面）」の方角（NORTH, EAST, SOUTH, WEST）
 * - spinAxis / rpm: トロッコ走行中にからくり本体を自転させる連続回転設定
 * - クォータニオン最短経路保証、静止時パケット送信抑制、ブレゼロ対称ピボット数式により、荒ぶりを完全に根絶
 */
class MinecartMotion(
  val minecart: Minecart,
  var followRotation: Boolean = true,
  var followSlope: Boolean = true,
  var forwardFacing: BlockFace = BlockFace.SOUTH,
  var spinAxis: Axis? = null,
  var rpm: Float = 16f,
  var autoReverseAtRailEnd: Boolean = true,
) : MotionController {

  var isBulbInverted: Boolean = false
  var isBulbReversing: Boolean = false

  /**
   * 銅の電球 (錆び・酸化) による逆再生の切り替え
   * トロッコの走行速度を反転し、自転方向も反転する
   */
  fun setBulbReversing(reversing: Boolean, instance: KarakuriInstance) {
    if (isBulbReversing == reversing) return
    isBulbReversing = reversing
    isBulbInverted = reversing

    // 連結編成に属している場合は編成全体を反転（先頭と最後尾の入れ替え）
    val couplingMgr = try { io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager } catch (_: Exception) { null }
    val train = couplingMgr?.getTrainForCart(minecart)
    if (train != null) {
      couplingMgr.reverseTrain(train)
    } else {
      if (minecart.isValid && !minecart.isDead) {
        val vel = minecart.velocity
        if (vel.lengthSquared() > 0.0001) {
          minecart.velocity = vel.multiply(-1.0)
        } else if (lastMoveDx != 0.0 || lastMoveDz != 0.0) {
          val speed = kotlin.math.sqrt(lastMoveDx * lastMoveDx + lastMoveDz * lastMoveDz).coerceAtLeast(0.1)
          val norm = org.bukkit.util.Vector(-lastMoveDx, 0.0, -lastMoveDz).normalize()
          minecart.velocity = norm.multiply(speed)
        }
      }
    }
    requestUpdate()
  }

  var smoothYaw: Float = minecart.location.yaw
  private var smoothPitch: Float = 0f
  private var lastCartX: Double = minecart.location.x
  private var lastCartY: Double = minecart.location.y
  private var lastCartZ: Double = minecart.location.z
  private var lastMoveDx: Double = 0.0
  private var lastMoveDy: Double = 0.0
  private var lastMoveDz: Double = 0.0
  var isCartMoving: Boolean = false
    private set
  var lastDeltaYaw: Float = 0f
    private set
  private var currentSpinAngleDeg: Float = 0f

  // 最短経路保証用クォータニオン
  private var lastSentQuat: Quaternionf = Quaternionf()
  private var hasInitialTransformationSent: Boolean = false

  // 設定変更直後などに即座に更新を反映するためのフラグ
  var forceUpdateNextTick: Boolean = true

  fun requestUpdate() {
    forceUpdateNextTick = true
  }

  fun addRotationDelta(deltaYaw: Float) {
    smoothYaw += deltaYaw
    forceUpdateNextTick = true
  }

  override fun onStart(instance: KarakuriInstance) {
    smoothYaw = minecart.location.yaw
    smoothPitch = 0f
    lastCartX = minecart.location.x
    lastCartY = minecart.location.y
    lastCartZ = minecart.location.z
    currentSpinAngleDeg = 0f
    lastSentQuat = Quaternionf()
    hasInitialTransformationSent = false
    forceUpdateNextTick = true

    minecart.addScoreboardTag(KarakuriManager.TAG_MINECART_CONTRAPTION)
    minecart.addScoreboardTag("${KarakuriManager.TAG_MINECART_INSTANCE_PREFIX}${instance.id}")

    // 即座に初期同期
    updatePositionAndRotation(instance, durationTicks = 0, forceTransform = true)
  }

  override fun tick(instance: KarakuriInstance, currentTick: Long): Boolean {
    // 1. トロッコが有効かチェック
    if (!minecart.isValid || minecart.isDead) {
      io.oyasai.karakuri.KarakuriPlugin.instance.manager.remove(instance.id)
      return false
    }

    // 2. 奈落落下チェック (Y < -64)
    if (minecart.location.y < -64.0) {
      minecart.remove()
      io.oyasai.karakuri.KarakuriPlugin.instance.manager.remove(instance.id)
      return false
    }

    val cartLoc = minecart.location
    val dx = cartLoc.x - lastCartX
    val dy = cartLoc.y - lastCartY
    val dz = cartLoc.z - lastCartZ
    val moveDistSq = dx * dx + dz * dz + dy * dy
    isCartMoving = moveDistSq > 0.0001 // 実際に走行中 (速度 > 0.2 ブロック/秒)
    if (isCartMoving) {
      lastMoveDx = dx
      lastMoveDy = dy
      lastMoveDz = dz
    } else {
      lastMoveDx = 0.0
      lastMoveDy = 0.0
      lastMoveDz = 0.0
    }

    // 3. 実際の移動ベクトルから進行方向(Yaw)を滑らかに追従
    val prevSmoothYaw = smoothYaw
    if (isCartMoving && followRotation) {
      val rawMoveYaw = (-Math.toDegrees(atan2(dx, dz))).toFloat()
      var diff = (rawMoveYaw - smoothYaw) % 360f
      if (diff > 180f) diff -= 360f
      if (diff < -180f) diff += 360f
      smoothYaw += diff * 0.35f
    }

    // 3.5 斜めレール傾斜 (Pitch) の追従計算
    val prevSmoothPitch = smoothPitch
    if (followSlope) {
      val railPitch = detectRailSlopePitch(minecart)
      val targetPitch = railPitch ?: if (isCartMoving) {
        val horizDist = kotlin.math.sqrt(dx * dx + dz * dz)
        if (horizDist > 0.005) {
          val moveAngle = (-Math.toDegrees(atan2(dx, dz))).toFloat()
          var angleDiff = (moveAngle - smoothYaw) % 360f
          if (angleDiff > 180f) angleDiff -= 360f
          if (angleDiff < -180f) angleDiff += 360f
          val isForward = kotlin.math.abs(angleDiff) < 90f
          val rawPitch = -kotlin.math.atan2(dy, horizDist).toFloat()
          if (isForward) rawPitch else -rawPitch
        } else {
          smoothPitch
        }
      } else {
        smoothPitch
      }
      smoothPitch += (targetPitch - smoothPitch) * 0.35f
    } else {
      smoothPitch += (0f - smoothPitch) * 0.35f
    }

    lastCartX = cartLoc.x
    lastCartY = cartLoc.y
    lastCartZ = cartLoc.z

    // 4. 自転角度の更新
    val isSpinning = spinAxis != null && rpm != 0f
    var spinDeltaYaw = 0f
    if (isSpinning) {
      val effectiveRpm = if (isBulbInverted) -rpm else rpm
      val degPerTick = (effectiveRpm * 6.0f) / 20.0f
      currentSpinAngleDeg = (currentSpinAngleDeg + degPerTick) % 360f
      if (spinAxis == Axis.Y) {
        spinDeltaYaw = -degPerTick
      }
    }

    var cartYawDelta = (smoothYaw - prevSmoothYaw) % 360f
    while (cartYawDelta > 180f) cartYawDelta -= 360f
    while (cartYawDelta < -180f) cartYawDelta += 360f
    lastDeltaYaw = cartYawDelta + spinDeltaYaw

    val isPitchChanging = kotlin.math.abs(smoothPitch - prevSmoothPitch) > 0.001f

    // ★重要: 静止中で自転・傾斜変化もないときは、毎Tickの不要なテレポート送信を完全に抑制！
    val needsUpdate = isCartMoving || isSpinning || isPitchChanging || forceUpdateNextTick
    if (needsUpdate) {
      val duration = if (forceUpdateNextTick) 3 else (if (isCartMoving) 2 else 1)
      updatePositionAndRotation(instance, durationTicks = duration, forceTransform = forceUpdateNextTick)
      forceUpdateNextTick = false
    }

    return true
  }

  override fun onStop(instance: KarakuriInstance) {
    if (minecart.isValid) {
      minecart.removeScoreboardTag(KarakuriManager.TAG_MINECART_CONTRAPTION)
      minecart.removeScoreboardTag("${KarakuriManager.TAG_MINECART_INSTANCE_PREFIX}${instance.id}")
    }
  }

  /**
   * からくりの全パーツのテレポート・トランスフォーメーションをトロッコに合わせて更新
   * KarakuriInstance の偏心ブレゼロ対称ピボット数式を採用
   */
  fun updatePositionAndRotation(instance: KarakuriInstance, durationTicks: Int, forceTransform: Boolean = false) {
    if (!minecart.isValid) return
    val cartLoc = minecart.location

    // 1. トロッコ上の目標ピボット位置 (Minecartの上面 +0.475 = 約2ピクセル浮き上がらせて地面の埋まり・黒い陰影を解消)
    // クライアント側トロッコ補間遅延（約1tick）に合わせて走行中の前方ズレを相殺
    val lagCompensation = if (isCartMoving) 0.8 else 0.0
    val targetPivotX = cartLoc.x - lastMoveDx * lagCompensation
    val targetPivotY = cartLoc.y + 0.475 - lastMoveDy * lagCompensation
    val targetPivotZ = cartLoc.z - lastMoveDz * lagCompensation

    val origPivot = instance.worldPivotLocation

    // ピボットの平行移動オフセット（初期ピボットからトロッコピボットへの差分）
    val deltaX = targetPivotX - origPivot.x
    val deltaY = targetPivotY - origPivot.y
    val deltaZ = targetPivotZ - origPivot.z

    // 2. からくりの正面オフセット計算
    val facingBaseYaw = when (forwardFacing) {
      BlockFace.SOUTH -> 0f
      BlockFace.WEST -> 90f
      BlockFace.NORTH -> 180f
      BlockFace.EAST -> 270f
      else -> 0f
    }

    val facingBaseRad = (-Math.toRadians(facingBaseYaw.toDouble())).toFloat()
    val cartYawRad = if (followRotation) (-Math.toRadians(smoothYaw.toDouble())).toFloat() else 0f

    val cartQuat = Quaternionf().rotateY(cartYawRad)
    if (followSlope && kotlin.math.abs(smoothPitch) > 0.001f) {
      cartQuat.rotateX(smoothPitch)
    }
    cartQuat.rotateY(facingBaseRad)

    // 3. 連続自転クォータニオンとの合成
    val totalQuat = if (spinAxis != null && rpm != 0f) {
      val spinRad = Math.toRadians(currentSpinAngleDeg.toDouble()).toFloat()
      val spinQuat = Quaternionf().rotateAxis(spinRad, spinAxis!!.vector.x, spinAxis!!.vector.y, spinAxis!!.vector.z)
      Quaternionf(cartQuat).mul(spinQuat)
    } else {
      Quaternionf(cartQuat)
    }

    // 最短経路保証（内積が負なら符号反転して360度大回転バグを完全防止）
    if (hasInitialTransformationSent && lastSentQuat.dot(totalQuat) < 0f) {
      totalQuat.set(-totalQuat.x, -totalQuat.y, -totalQuat.z, -totalQuat.w)
    }

    // ★重要: クォータニオンが実際に変化したかチェック（連動OFF時は毎Tick同じ値になる）
    // 変化していない場合、displayEntity.transformation を再送しないことで、
    // クライアント側での補間リセット・マイクロジッター（荒ぶり）を完全根絶！
    val quatDiff = if (hasInitialTransformationSent) {
      1.0f - Math.abs(lastSentQuat.dot(totalQuat))
    } else {
      1.0f
    }
    val needsTransform = forceTransform || !hasInitialTransformationSent || quatDiff > 0.0001f

    if (needsTransform) {
      lastSentQuat = Quaternionf(totalQuat)
      hasInitialTransformationSent = true
    }
    instance.updateCurrentRotation(totalQuat)

    // 4. (A) ルートエンティティの更新
    val rootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
    val rootTarget = instance.originLocation.clone().add(
      rootOffset.x.toDouble() + deltaX,
      rootOffset.y.toDouble() + deltaY,
      rootOffset.z.toDouble() + deltaZ
    )
    instance.displayEntity.teleportDuration = durationTicks
    instance.displayEntity.teleport(rootTarget)

    if (needsTransform) {
      // ルート原点から初期ピボットへのベクトル
      val rootPivotVec = Vector3f(
        (origPivot.x - (instance.originLocation.x + rootOffset.x)).toFloat(),
        (origPivot.y - (instance.originLocation.y + rootOffset.y)).toFloat(),
        (origPivot.z - (instance.originLocation.z + rootOffset.z)).toFloat()
      )
      val rootRotatedPivot = Vector3f(rootPivotVec).rotate(totalQuat)
      val rootTranslation = Vector3f(rootPivotVec).sub(rootRotatedPivot)

      instance.displayEntity.interpolationDuration = durationTicks
      instance.displayEntity.interpolationDelay = 0
      instance.displayEntity.transformation = org.bukkit.util.Transformation(
        rootTranslation,
        totalQuat,
        instance.scale,
        instance.initialRightRotation
      )
    }

    // からくりの合成正面ベクトルから Yaw / Pitch を算出
    val forwardVec = Vector3f(0f, 0f, 1f).rotate(totalQuat)
    val interYaw = (-Math.toDegrees(atan2(forwardVec.x.toDouble(), forwardVec.z.toDouble()))).toFloat()
    val interPitch = (-Math.toDegrees(kotlin.math.asin(forwardVec.y.coerceIn(-1f, 1f).toDouble()))).toFloat()

    // ルートInteraction (回転後のブロック中心 - 0.5Y)
    instance.interactionEntity?.let { inter ->
      if (inter.isValid) {
        val rootCenter = Vector3f(
          (instance.originLocation.x + 0.5 - origPivot.x).toFloat(),
          (instance.originLocation.y + 0.5 - origPivot.y).toFloat(),
          (instance.originLocation.z + 0.5 - origPivot.z).toFloat()
        )
        val rotatedRootCenter = Vector3f(rootCenter).rotate(totalQuat)
        val targetCenterX = targetPivotX + rotatedRootCenter.x
        val targetCenterY = targetPivotY + rotatedRootCenter.y
        val targetCenterZ = targetPivotZ + rotatedRootCenter.z
        val interTarget = Location(cartLoc.world, targetCenterX, targetCenterY - 0.5, targetCenterZ, interYaw, interPitch)
        inter.teleport(interTarget)
      }
    }

    // 4. (B) 追加パーツ群の更新
    for (part in instance.additionalParts) {
      if (!part.displayEntity.isValid) continue
      val partOffset = instance.getBaseEntityOffset(part.displayEntity, part.originalBlockData)
      val partTarget = part.originLocation.clone().add(
        partOffset.x.toDouble() + deltaX,
        partOffset.y.toDouble() + deltaY,
        partOffset.z.toDouble() + deltaZ
      )
      part.displayEntity.teleportDuration = durationTicks
      part.displayEntity.teleport(partTarget)

      if (needsTransform) {
        val partPivotVec = Vector3f(
          (origPivot.x - (part.originLocation.x + partOffset.x)).toFloat(),
          (origPivot.y - (part.originLocation.y + partOffset.y)).toFloat(),
          (origPivot.z - (part.originLocation.z + partOffset.z)).toFloat()
        )
        val partRotatedPivot = Vector3f(partPivotVec).rotate(totalQuat)
        val partTranslation = Vector3f(partPivotVec).sub(partRotatedPivot)

        part.displayEntity.interpolationDuration = durationTicks
        part.displayEntity.interpolationDelay = 0
        part.displayEntity.transformation = org.bukkit.util.Transformation(
          partTranslation,
          totalQuat,
          part.partScale,
          part.initialRightRotation
        )
      }

      part.interactionEntity?.let { pInter ->
        if (pInter.isValid) {
          val partCenter = Vector3f(
            (part.originLocation.x + 0.5 - origPivot.x).toFloat(),
            (part.originLocation.y + 0.5 - origPivot.y).toFloat(),
            (part.originLocation.z + 0.5 - origPivot.z).toFloat()
          )
          val rotatedPartCenter = Vector3f(partCenter).rotate(totalQuat)
          val pTargetCenterX = targetPivotX + rotatedPartCenter.x
          val pTargetCenterY = targetPivotY + rotatedPartCenter.y
          val pTargetCenterZ = targetPivotZ + rotatedPartCenter.z
          val pInterTarget = Location(cartLoc.world, pTargetCenterX, pTargetCenterY - 0.5, pTargetCenterZ, interYaw, interPitch)
          pInter.teleport(pInterTarget)
        }
      }
    }
  }

  /**
   * トロッコモードを終了し、現在のトロッコの位置を新しい初期位置・初期姿勢として
   * ワールド上にブロックとして復元・設置する。
   */
  fun restoreAtCurrentMinecartPosition(instance: KarakuriInstance) {
    val world = instance.originLocation.world ?: return
    val cartLoc = minecart.location

    val pivotSnapX = floor(cartLoc.x).toInt()
    val pivotSnapY = (cartLoc.y + 0.5).roundToInt()
    val pivotSnapZ = floor(cartLoc.z).toInt()

    val origPivot = instance.worldPivotLocation
    val origPivotBlockX = origPivot.blockX
    val origPivotBlockY = origPivot.blockY
    val origPivotBlockZ = origPivot.blockZ

    val facingBaseYaw = when (forwardFacing) {
      BlockFace.SOUTH -> 0f
      BlockFace.WEST -> 90f
      BlockFace.NORTH -> 180f
      BlockFace.EAST -> 270f
      else -> 0f
    }
    val cartYawDiff = if (followRotation) smoothYaw - facingBaseYaw else -facingBaseYaw
    val rotationSteps = Math.floorMod((cartYawDiff / 90.0).roundToInt(), 4)

    val blocksToRestore = if (instance.constituentBlocks.isNotEmpty()) {
      instance.constituentBlocks
    } else {
      val map = mutableMapOf<Location, BlockData>()
      instance.originalBlockData?.let { map[instance.originLocation] = it }
      for (part in instance.additionalParts) {
        part.originalBlockData?.let { map[part.originLocation] = it }
      }
      map
    }

    for ((origLoc, blockData) in blocksToRestore) {
      val dx = origLoc.blockX - origPivotBlockX
      val dy = origLoc.blockY - origPivotBlockY
      val dz = origLoc.blockZ - origPivotBlockZ

      // 90度単位の座標回転 (時計回り)
      val (newDx, newDz) = when (rotationSteps) {
        1 -> -dz to dx
        2 -> -dx to -dz
        3 -> dz to -dx
        else -> dx to dz
      }

      val targetX = pivotSnapX + newDx
      val targetY = pivotSnapY + dy
      val targetZ = pivotSnapZ + newDz

      val targetBlockLoc = Location(world, targetX.toDouble(), targetY.toDouble(), targetZ.toDouble())
      val block = world.getBlockAt(targetBlockLoc)

      val rotatedData = rotateBlockData(blockData.clone(), rotationSteps)
      block.setBlockData(rotatedData, false)

      val profile = instance.constituentSkullProfiles[origLoc] ?: instance.skullProfile
      if (profile != null && block.state is org.bukkit.block.Skull) {
        val skull = block.state as org.bukkit.block.Skull
        skull.setPlayerProfile(profile)
        skull.update(true, false)
      }
    }

    if (minecart.isValid) {
      minecart.removeScoreboardTag(KarakuriManager.TAG_MINECART_CONTRAPTION)
      minecart.removeScoreboardTag("${KarakuriManager.TAG_MINECART_INSTANCE_PREFIX}${instance.id}")
    }

    instance.remove()
    io.oyasai.karakuri.KarakuriPlugin.instance.manager.remove(instance.id)
  }

  private fun rotateBlockData(data: BlockData, steps: Int): BlockData {
    if (steps == 0) return data
    if (data is Directional) {
      var currentFace = data.facing
      repeat(steps) {
        currentFace = rotateFaceClockwise(currentFace)
      }
      if (data.faces.contains(currentFace)) {
        data.facing = currentFace
      }
    }
    if (data is Rotatable) {
      var currentFace = data.rotation
      repeat(steps) {
        currentFace = rotateFaceClockwise(currentFace)
      }
      data.rotation = currentFace
    }
    return data
  }

  private fun rotateFaceClockwise(face: BlockFace): BlockFace {
    return when (face) {
      BlockFace.NORTH -> BlockFace.EAST
      BlockFace.EAST -> BlockFace.SOUTH
      BlockFace.SOUTH -> BlockFace.WEST
      BlockFace.WEST -> BlockFace.NORTH
      BlockFace.NORTH_EAST -> BlockFace.SOUTH_EAST
      BlockFace.SOUTH_EAST -> BlockFace.SOUTH_WEST
      BlockFace.SOUTH_WEST -> BlockFace.NORTH_WEST
      BlockFace.NORTH_WEST -> BlockFace.NORTH_EAST
      else -> face
    }
  }

  private fun detectRailSlopePitch(cart: Minecart): Float? {
    val loc = cart.location
    for (yOff in listOf(0.0, -0.2, -0.5, 0.5)) {
      val block = loc.clone().add(0.0, yOff, 0.0).block
      val data = block.blockData
      if (data is org.bukkit.block.data.Rail) {
        val shape = data.shape
        val pitchRad = Math.toRadians(45.0).toFloat()
        val yawRad = Math.toRadians(smoothYaw.toDouble())
        return when (shape) {
          org.bukkit.block.data.Rail.Shape.ASCENDING_SOUTH -> {
            (-pitchRad * kotlin.math.cos(yawRad)).toFloat()
          }
          org.bukkit.block.data.Rail.Shape.ASCENDING_NORTH -> {
            (pitchRad * kotlin.math.cos(yawRad)).toFloat()
          }
          org.bukkit.block.data.Rail.Shape.ASCENDING_EAST -> {
            (pitchRad * kotlin.math.sin(yawRad)).toFloat()
          }
          org.bukkit.block.data.Rail.Shape.ASCENDING_WEST -> {
            (-pitchRad * kotlin.math.sin(yawRad)).toFloat()
          }
          else -> 0f
        }
      }
    }
    return null
  }
}
