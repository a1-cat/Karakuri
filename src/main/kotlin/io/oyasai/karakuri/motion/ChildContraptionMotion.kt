package io.oyasai.karakuri.motion

import io.oyasai.karakuri.KarakuriPlugin
import io.oyasai.karakuri.engine.KarakuriInstance
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI

/**
 * 粘着ピストンで親からくりに接続された「子構造（第二のからくり）」専用のモーションコントローラー。
 *
 * - 親からくりの中心軸公転に完全追従（座標は親構造と一緒に公転）
 * - 子構造自体の向き（回転角）は親に追従するか（followParentRotation = true: 剛体固定）、
 *   追従せず向きを維持するか（followParentRotation = false: ゴンドラモード）を選択可能
 * - 粘着ピストンの向き（UP/DOWN -> Y軸, EAST/WEST -> X軸, NORTH/SOUTH -> Z軸）に連動した独自回転
 * - 子構造の回転の中心軸（ピボット）は、粘着ピストンがある場所となる！
 */
class ChildContraptionMotion(
  val parentId: String,
  val pistonOriginLoc: Location, // 親の初期原点基準のピストンブロック座標
  val pistonFacing: BlockFace,   // ピストンの向き (UP, DOWN, NORTH, SOUTH, EAST, WEST)
  var followParentRotation: Boolean = true, // 親の回転に向きも追従するか
  var rpm: Float = 16.0f,
  var isClockwise: Boolean = true,
  var isSpinActive: Boolean = false,
) : MotionController {

  var isBulbInverted: Boolean = false

  val effectiveIsClockwise: Boolean
    get() = if (isBulbInverted) !isClockwise else isClockwise

  var currentSpinAngleRad: Double = 0.0
  private var previousQuat: Quaternionf = Quaternionf()
  private var prevIsSpinActive: Boolean = isSpinActive
  private var prevFollowParentRotation: Boolean = followParentRotation
  var needsSnap: Boolean = true // 初期開始時やリセット時は即座にスナップ
  private var isStationary: Boolean = false

  fun requestSnap() {
    needsSnap = true
    isStationary = false
  }

  companion object {
    /**
     * 粘着ピストンの向きに応じた単位回転軸ベクトルを取得
     */
    fun getPistonAxisVector(facing: BlockFace): Vector3f {
      return when (facing) {
        BlockFace.UP -> Vector3f(0f, 1f, 0f)
        BlockFace.DOWN -> Vector3f(0f, -1f, 0f)
        BlockFace.EAST -> Vector3f(1f, 0f, 0f)
        BlockFace.WEST -> Vector3f(-1f, 0f, 0f)
        BlockFace.SOUTH -> Vector3f(0f, 0f, 1f)
        BlockFace.NORTH -> Vector3f(0f, 0f, -1f)
        else -> Vector3f(0f, 1f, 0f)
      }
    }

    /**
     * 向きに対応する軸名称（表示用）
     */
    fun getPistonAxisName(facing: BlockFace): String {
      return when (facing) {
        BlockFace.UP, BlockFace.DOWN -> "Y軸 (垂直回転)"
        BlockFace.EAST, BlockFace.WEST -> "X軸 (ピッチ回転)"
        BlockFace.NORTH, BlockFace.SOUTH -> "Z軸 (ロール回転)"
        else -> "Y軸"
      }
    }
  }

  override fun onStart(instance: KarakuriInstance) {
    previousQuat = Quaternionf(instance.currentRotation)
    prevIsSpinActive = isSpinActive
    prevFollowParentRotation = followParentRotation
    needsSnap = true
  }

  override fun onStop(instance: KarakuriInstance) {
    // 停止時は何もしない
  }

  override fun tick(instance: KarakuriInstance, currentTick: Long): Boolean {
    val parent = KarakuriPlugin.instance.manager.get(parentId)
    if (parent == null || !parent.isValid()) {
      return true // 親のロード・復帰を待機（子構造を破棄しない！）
    }

    // 自転がONからOFFに切り替わった瞬間、スナップモードを発動して角度を0にリセット
    if (prevIsSpinActive && !isSpinActive) {
      currentSpinAngleRad = 0.0
      needsSnap = true
    }
    prevIsSpinActive = isSpinActive

    // 親の向き公転連動(followParentRotation)が切り替わった瞬間もスナップモードを発動（荒ぶり防止）
    if (prevFollowParentRotation != followParentRotation) {
      needsSnap = true
    }
    prevFollowParentRotation = followParentRotation

    // 1. 独自回転（自転）の微小角変位を加算
    if (isSpinActive && rpm > 0.01f) {
      val degreesPerSec = rpm * 6.0f
      val deltaRad = (degreesPerSec.toDouble() * (PI / 180.0) / 20.0) * (if (effectiveIsClockwise) -1.0 else 1.0)
      val fourPi = 4.0 * PI
      currentSpinAngleRad = (currentSpinAngleRad + deltaRad) % fourPi
      if (currentSpinAngleRad < 0.0) currentSpinAngleRad += fourPi
    } else {
      currentSpinAngleRad = 0.0
    }

    // 2. 粘着ピストンの向きに応じた軸での独自回転クォータニオン
    val axis = getPistonAxisVector(pistonFacing)
    val spinQuat = Quaternionf().rotateAxis(currentSpinAngleRad.toFloat(), axis.x, axis.y, axis.z)

    // 3. 親の現在の回転クォータニオン
    val parentRot = Quaternionf(parent.currentRotation)

    // 4. 全体の回転クォータニオン (Q_total)
    val totalRot = if (followParentRotation) {
      Quaternionf(parentRot).mul(spinQuat)
    } else {
      Quaternionf(spinQuat)
    }

    if (needsSnap) {
      previousQuat = Quaternionf(totalRot)
    } else {
      // 連続回転時の最短弧保証
      if (previousQuat.dot(totalRot) < 0.0f) {
        totalRot.set(-totalRot.x, -totalRot.y, -totalRot.z, -totalRot.w)
      }
      previousQuat = Quaternionf(totalRot)
    }

    instance.updateCurrentRotation(totalRot)

    // 5. 親からくりにおける現在の粘着ピストンのワールド位置を計算
    val relPiston = Vector3f(
      (pistonOriginLoc.blockX + 0.5 - parent.worldPivotLocation.x).toFloat(),
      (pistonOriginLoc.blockY + 0.5 - parent.worldPivotLocation.y).toFloat(),
      (pistonOriginLoc.blockZ + 0.5 - parent.worldPivotLocation.z).toFloat()
    )
    val rotatedRelPiston = Vector3f(relPiston).rotate(parentRot)

    // 親の直線移動等の変位量
    val parentRootOffset = parent.getBaseEntityOffset(parent.displayEntity, parent.originalBlockData)
    val parentDeltaX = parent.displayEntity.location.x - (parent.originLocation.x + parentRootOffset.x)
    val parentDeltaY = parent.displayEntity.location.y - (parent.originLocation.y + parentRootOffset.y)
    val parentDeltaZ = parent.displayEntity.location.z - (parent.originLocation.z + parentRootOffset.z)

    val curPistonX = parent.worldPivotLocation.x + parentDeltaX + rotatedRelPiston.x
    val curPistonY = parent.worldPivotLocation.y + parentDeltaY + rotatedRelPiston.y
    val curPistonZ = parent.worldPivotLocation.z + parentDeltaZ + rotatedRelPiston.z

    val curPistonWorldLoc = Location(parent.worldPivotLocation.world, curPistonX, curPistonY, curPistonZ)
    instance.worldPivotLocation = curPistonWorldLoc

    val parentMotion = parent.activeMotion
    val isParentMoving = when (parentMotion) {
      is io.oyasai.karakuri.motion.MinecartMotion -> parentMotion.isCartMoving
      is io.oyasai.karakuri.motion.ContinuousSpinMotion -> parentMotion.isRunning
      is io.oyasai.karakuri.motion.IntegratedLinearMotion -> parentMotion.isRunning || parentMotion.isMoving
      is io.oyasai.karakuri.motion.ProgramMotion -> parentMotion.isPlaying
      null -> false
      else -> parent.lastDeltaYaw != 0f
    }
    val isChildMoving = (isSpinActive && rpm > 0.01f)

    val durationTicks: Int
    if (!needsSnap && !isParentMoving && !isChildMoving) {
      if (isStationary) {
        // 完全静止中: 毎tickの不要なテレポート送信をスキップ（補間ジッター・荒ぶりを完全防止）
        return true
      }
      durationTicks = 0
      isStationary = true
    } else {
      isStationary = false
      durationTicks = if (needsSnap) {
        0
      } else if (parentMotion is io.oyasai.karakuri.motion.MinecartMotion) {
        if (parentMotion.isCartMoving) 2 else 1
      } else {
        1
      }
    }
    needsSnap = false

    // 6. 子からくりの各パーツの位置と姿勢を更新
    val initPistonCenter = Vector3f(
      (pistonOriginLoc.blockX + 0.5).toFloat(),
      (pistonOriginLoc.blockY + 0.5).toFloat(),
      (pistonOriginLoc.blockZ + 0.5).toFloat()
    )

    // (A) ルートパーツの配置
    val rootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
    val rootInitLoc = Vector3f(
      (instance.originLocation.x + rootOffset.x).toFloat(),
      (instance.originLocation.y + rootOffset.y).toFloat(),
      (instance.originLocation.z + rootOffset.z).toFloat()
    )
    val rootRel = Vector3f(rootInitLoc).sub(initPistonCenter)
    val rootRotRel = Vector3f(rootRel).rotate(totalRot)
    val rootTargetX = curPistonX + rootRotRel.x
    val rootTargetY = curPistonY + rootRotRel.y
    val rootTargetZ = curPistonZ + rootRotRel.z

    val rootTargetLoc = Location(instance.originLocation.world, rootTargetX, rootTargetY, rootTargetZ)
    instance.displayEntity.teleportDuration = durationTicks
    instance.displayEntity.teleport(rootTargetLoc)
    instance.displayEntity.interpolationDuration = durationTicks
    instance.displayEntity.interpolationDelay = 0
    instance.displayEntity.transformation = Transformation(Vector3f(0f, 0f, 0f), totalRot, instance.scale, instance.initialRightRotation)

    // ルートの InteractionEntity も公転・回転追従
    instance.interactionEntity?.let { inter ->
      if (inter.isValid) {
        val rootCenterRel = Vector3f(
          (instance.originLocation.x + 0.5 - initPistonCenter.x).toFloat(),
          (instance.originLocation.y + 0.5 - initPistonCenter.y).toFloat(),
          (instance.originLocation.z + 0.5 - initPistonCenter.z).toFloat()
        )
        val rotCenterRel = Vector3f(rootCenterRel).rotate(totalRot)
        val interLoc = Location(instance.originLocation.world, curPistonX + rotCenterRel.x, curPistonY + rotCenterRel.y - 0.5, curPistonZ + rotCenterRel.z)
        inter.teleport(interLoc)
      }
    }

    // (B) 追加パーツの配置
    for (part in instance.additionalParts) {
      if (!part.displayEntity.isValid) continue
      val partOffset = instance.getBaseEntityOffset(part.displayEntity, part.originalBlockData)
      val partInitLoc = Vector3f(
        (part.originLocation.x + partOffset.x).toFloat(),
        (part.originLocation.y + partOffset.y).toFloat(),
        (part.originLocation.z + partOffset.z).toFloat()
      )
      val partRel = Vector3f(partInitLoc).sub(initPistonCenter)
      val partRotRel = Vector3f(partRel).rotate(totalRot)
      val partTargetX = curPistonX + partRotRel.x
      val partTargetY = curPistonY + partRotRel.y
      val partTargetZ = curPistonZ + partRotRel.z

      val partTargetLoc = Location(part.originLocation.world, partTargetX, partTargetY, partTargetZ)
      part.displayEntity.teleportDuration = durationTicks
      part.displayEntity.teleport(partTargetLoc)
      part.displayEntity.interpolationDuration = durationTicks
      part.displayEntity.interpolationDelay = 0
      part.displayEntity.transformation = Transformation(Vector3f(0f, 0f, 0f), totalRot, part.partScale, part.initialRightRotation)

      // 追加パーツの InteractionEntity も公転・回転追従
      part.interactionEntity?.let { inter ->
        if (inter.isValid) {
          val partCenterRel = Vector3f(
            (part.originLocation.x + 0.5 - initPistonCenter.x).toFloat(),
            (part.originLocation.y + 0.5 - initPistonCenter.y).toFloat(),
            (part.originLocation.z + 0.5 - initPistonCenter.z).toFloat()
          )
          val rotCenterRel = Vector3f(partCenterRel).rotate(totalRot)
          val interLoc = Location(part.originLocation.world, curPistonX + rotCenterRel.x, curPistonY + rotCenterRel.y - 0.5, curPistonZ + rotCenterRel.z)
          inter.teleport(interLoc)
        }
      }
    }

    return true
  }
}
