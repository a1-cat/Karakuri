package io.oyasai.karakuri.motion

import io.oyasai.karakuri.KarakuriPlugin
import io.oyasai.karakuri.engine.KarakuriInstance
import org.bukkit.Location
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI

/**
 * 鉄の鎖・非錆止め銅の鎖に接続された「直線運動版 子構造」のモーションコントローラー。
 *
 * - 親からくりの回転・移動に完全追従（鎖自体が親と一緒に公転・移動）
 * - 鎖の直線（端〜端）に沿った直線スライド移動（速度・往復モード設定可能）
 * - 移動と同時に、任意軸（X/Y/Z）での自転回転（連続回転）も同時実行可能！
 * - 親の回転に向きを追従させるか（followParentRotation = true / false）も選択可能
 */
class ChainChildContraptionMotion(
  val parentId: String,
  val chainAxis: org.bukkit.Axis,
  val minChainLoc: Location, // 最もマイナス側の鎖ブロック座標
  val maxChainLoc: Location, // 最もプラス側の鎖ブロック座標
  val initialConnectLoc: Location, // 接続時にクリックした鎖ブロック座標
  var speed: Double = 2.0, // 直線運動速度 (m/s)
  var linearMode: LinearLoopMode = LinearLoopMode.AUTO_PING_PONG, // 往復モード
  var isRunning: Boolean = false, // 直線運動が作動中か
  var followParentRotation: Boolean = true, // 親の向きに追従するか
  var spinAxis: Axis? = null, // 自転軸 (X, Y, Z, または なし)
  var rpm: Float = 16.0f, // 自転速度 (RPM)
  var isClockwise: Boolean = true, // 自転方向
  var isSpinActive: Boolean = false, // 自転が作動中か
) : MotionController {

  var isBulbInverted: Boolean = false
  var isBulbReversing: Boolean = false

  fun setBulbReversing(reversing: Boolean, instance: KarakuriInstance) {
    if (isBulbReversing != reversing) {
      isBulbReversing = reversing
      isBulbInverted = reversing
      direction = if (reversing) -1.0 else 1.0
    }
  }

  val effectiveIsClockwise: Boolean
    get() = if (isBulbInverted) !isClockwise else isClockwise

  // 親からくりの初期原点基準の鎖ローカルベクトル（親ピボット基準）
  var minChainRel: Vector3f = Vector3f()
  var maxChainRel: Vector3f = Vector3f()
  var initialConnectRel: Vector3f = Vector3f()
  var totalDistance: Double = 0.0

  // 直線運動の進捗 (0.0: min端, 1.0: max端)
  var progress: Double = 0.0
  var direction: Double = 1.0 // +1.0: max方向, -1.0: min方向

  // 自転角度 (ラジアン)
  var currentSpinAngleRad: Double = 0.0

  private var previousQuat: Quaternionf = Quaternionf()
  private var prevIsSpinActive: Boolean = isSpinActive
  private var prevFollowParentRotation: Boolean = followParentRotation
  var needsSnap: Boolean = true
  private var isStationary: Boolean = false

  fun requestSnap() {
    needsSnap = true
    isStationary = false
  }

  /**
   * 最初に接続した親の鎖ブロック位置、および初期姿勢（自転0度）にリセット
   */
  fun resetToInitial() {
    isRunning = false
    isSpinActive = false
    currentSpinAngleRad = 0.0
    progress = if (totalDistance > 0.001) {
      val distFromMin = minChainLoc.distance(initialConnectLoc)
      (distFromMin / totalDistance).coerceIn(0.0, 1.0)
    } else {
      0.0
    }
    direction = 1.0
    needsSnap = true
    isStationary = false
  }

  override fun onStart(instance: KarakuriInstance) {
    val parent = KarakuriPlugin.instance.manager.get(parentId)
    if (parent != null) {
      val pPivot = parent.worldPivotLocation
      minChainRel = Vector3f(
        (minChainLoc.blockX + 0.5 - pPivot.x).toFloat(),
        (minChainLoc.blockY + 0.5 - pPivot.y).toFloat(),
        (minChainLoc.blockZ + 0.5 - pPivot.z).toFloat()
      )
      maxChainRel = Vector3f(
        (maxChainLoc.blockX + 0.5 - pPivot.x).toFloat(),
        (maxChainLoc.blockY + 0.5 - pPivot.y).toFloat(),
        (maxChainLoc.blockZ + 0.5 - pPivot.z).toFloat()
      )
      initialConnectRel = Vector3f(
        (initialConnectLoc.blockX + 0.5 - pPivot.x).toFloat(),
        (initialConnectLoc.blockY + 0.5 - pPivot.y).toFloat(),
        (initialConnectLoc.blockZ + 0.5 - pPivot.z).toFloat()
      )

      totalDistance = minChainLoc.distance(maxChainLoc)
      progress = if (totalDistance > 0.001) {
        val distFromMin = minChainLoc.distance(initialConnectLoc)
        (distFromMin / totalDistance).coerceIn(0.0, 1.0)
      } else {
        0.0
      }
    }

    previousQuat = Quaternionf(instance.currentRotation)
    prevIsSpinActive = isSpinActive
    prevFollowParentRotation = followParentRotation
    needsSnap = true
  }

  override fun onStop(instance: KarakuriInstance) {
    // 停止時
  }

  override fun tick(instance: KarakuriInstance, currentTick: Long): Boolean {
    val parent = KarakuriPlugin.instance.manager.get(parentId)
    if (parent == null || !parent.isValid()) {
      return true // 親のロード・復帰を待機（子構造を破棄しない！）
    }

    // 自転がONからOFFに切り替わった瞬間にリセットスナップ
    if (prevIsSpinActive && !isSpinActive) {
      currentSpinAngleRad = 0.0
      needsSnap = true
    }
    prevIsSpinActive = isSpinActive

    if (prevFollowParentRotation != followParentRotation) {
      needsSnap = true
    }
    prevFollowParentRotation = followParentRotation

    // 1. 直線スライド運動の更新
    if (isRunning && totalDistance > 0.001 && speed > 0.001) {
      val distPerTick = (speed / 20.0) * direction
      val progressDelta = distPerTick / totalDistance
      progress += progressDelta

      when (linearMode) {
        LinearLoopMode.AUTO_PING_PONG -> {
          if (progress >= 1.0) {
            progress = 1.0
            direction = -1.0
          } else if (progress <= 0.0) {
            progress = 0.0
            direction = 1.0
          }
        }
        LinearLoopMode.ONE_WAY_LOOP -> {
          if (direction > 0 && progress >= 1.0) {
            progress = 0.0
          } else if (direction < 0 && progress <= 0.0) {
            progress = 1.0
          }
        }
        LinearLoopMode.MANUAL_TOGGLE -> {
          if (progress >= 1.0) {
            progress = 1.0
            direction = -1.0
            isRunning = false
          } else if (progress <= 0.0) {
            progress = 0.0
            direction = 1.0
            isRunning = false
          }
        }
        LinearLoopMode.MANUAL_ONE_WAY -> {
          if (direction > 0 && progress >= 1.0) {
            progress = 0.0
            isRunning = false
          } else if (direction < 0 && progress <= 0.0) {
            progress = 1.0
            isRunning = false
          }
        }
      }
    }

    // 2. 独自自転の更新
    if (isSpinActive && spinAxis != null && rpm > 0.01f) {
      val degreesPerSec = rpm * 6.0f
      val deltaRad = (degreesPerSec.toDouble() * (PI / 180.0) / 20.0) * (if (effectiveIsClockwise) -1.0 else 1.0)
      val fourPi = 4.0 * PI
      currentSpinAngleRad = (currentSpinAngleRad + deltaRad) % fourPi
      if (currentSpinAngleRad < 0.0) currentSpinAngleRad += fourPi
    } else if (!isSpinActive) {
      currentSpinAngleRad = 0.0
    }

    // 3. 自転クォータニオン
    val spinQuat = if (isSpinActive && spinAxis != null) {
      val v = spinAxis!!.vector
      Quaternionf().rotateAxis(currentSpinAngleRad.toFloat(), v.x, v.y, v.z)
    } else {
      Quaternionf()
    }

    // 4. 親の現在回転
    val parentRot = Quaternionf(parent.currentRotation)

    // 5. 合成回転クォータニオン
    val totalRot = if (followParentRotation) {
      Quaternionf(parentRot).mul(spinQuat)
    } else {
      Quaternionf(spinQuat)
    }

    if (needsSnap) {
      previousQuat = Quaternionf(totalRot)
    } else {
      if (previousQuat.dot(totalRot) < 0.0f) {
        totalRot.set(-totalRot.x, -totalRot.y, -totalRot.z, -totalRot.w)
      }
      previousQuat = Quaternionf(totalRot)
    }

    instance.updateCurrentRotation(totalRot)

    // 6. 親からくりにおける現在の鎖直線上ピボット位置の計算
    val curRelPos = if (totalDistance > 0.001) {
      Vector3f(minChainRel).lerp(maxChainRel, progress.toFloat())
    } else {
      Vector3f(minChainRel)
    }
    val rotatedRelPos = Vector3f(curRelPos).rotate(parentRot)

    val parentRootOffset = parent.getBaseEntityOffset(parent.displayEntity, parent.originalBlockData)
    val parentDeltaX = parent.displayEntity.location.x - (parent.originLocation.x + parentRootOffset.x)
    val parentDeltaY = parent.displayEntity.location.y - (parent.originLocation.y + parentRootOffset.y)
    val parentDeltaZ = parent.displayEntity.location.z - (parent.originLocation.z + parentRootOffset.z)

    val curPivotX = parent.worldPivotLocation.x + parentDeltaX + rotatedRelPos.x
    val curPivotY = parent.worldPivotLocation.y + parentDeltaY + rotatedRelPos.y
    val curPivotZ = parent.worldPivotLocation.z + parentDeltaZ + rotatedRelPos.z

    val curPivotWorldLoc = Location(parent.worldPivotLocation.world, curPivotX, curPivotY, curPivotZ)
    instance.worldPivotLocation = curPivotWorldLoc

    val parentMotion = parent.activeMotion
    val isParentMoving = when (parentMotion) {
      is io.oyasai.karakuri.motion.MinecartMotion -> parentMotion.isCartMoving
      is io.oyasai.karakuri.motion.ContinuousSpinMotion -> parentMotion.isRunning
      is io.oyasai.karakuri.motion.IntegratedLinearMotion -> parentMotion.isRunning || parentMotion.isMoving
      is io.oyasai.karakuri.motion.ProgramMotion -> parentMotion.isPlaying
      null -> false
      else -> parent.lastDeltaYaw != 0f
    }
    val isChildMoving = (isRunning && totalDistance > 0.001 && speed > 0.001) || (isSpinActive && spinAxis != null && rpm > 0.01f)

    val durationTicks: Int
    if (!needsSnap && !isParentMoving && !isChildMoving) {
      if (isStationary) {
        // 完全静止中: 位置・姿勢ともに変化がないため毎tickの不要なテレポート送信をスキップ（補間ジッター・荒ぶりを完全根絶）
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

    // 7. 子からくりの各パーツの位置と姿勢を更新
    // 子からくりの初期接続ピボット中心（接続時に鎖に重ねた位置）
    val initConnectCenter = Vector3f(
      (initialConnectLoc.blockX + 0.5).toFloat(),
      (initialConnectLoc.blockY + 0.5).toFloat(),
      (initialConnectLoc.blockZ + 0.5).toFloat()
    )

    // (A) ルートパーツの配置
    val rootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
    val rootInitLoc = Vector3f(
      (instance.originLocation.x + rootOffset.x).toFloat(),
      (instance.originLocation.y + rootOffset.y).toFloat(),
      (instance.originLocation.z + rootOffset.z).toFloat()
    )
    val rootRel = Vector3f(rootInitLoc).sub(initConnectCenter)
    val rootRotRel = Vector3f(rootRel).rotate(totalRot)
    val rootTargetX = curPivotX + rootRotRel.x
    val rootTargetY = curPivotY + rootRotRel.y
    val rootTargetZ = curPivotZ + rootRotRel.z

    val rootTargetLoc = Location(instance.originLocation.world, rootTargetX, rootTargetY, rootTargetZ)
    instance.displayEntity.teleportDuration = durationTicks
    instance.displayEntity.teleport(rootTargetLoc)
    instance.displayEntity.interpolationDuration = durationTicks
    instance.displayEntity.interpolationDelay = 0
    instance.displayEntity.transformation = Transformation(Vector3f(0f, 0f, 0f), totalRot, instance.scale, instance.initialRightRotation)

    // ルート InteractionEntity
    instance.interactionEntity?.let { inter ->
      if (inter.isValid) {
        val rootCenterRel = Vector3f(
          (instance.originLocation.x + 0.5 - initConnectCenter.x).toFloat(),
          (instance.originLocation.y + 0.5 - initConnectCenter.y).toFloat(),
          (instance.originLocation.z + 0.5 - initConnectCenter.z).toFloat()
        )
        val rotCenterRel = Vector3f(rootCenterRel).rotate(totalRot)
        val interLoc = Location(instance.originLocation.world, curPivotX + rotCenterRel.x, curPivotY + rotCenterRel.y - 0.5, curPivotZ + rotCenterRel.z)
        inter.teleport(interLoc)
      }
    }

    // (B) 追加パーツ群の配置
    for (part in instance.additionalParts) {
      if (!part.displayEntity.isValid) continue
      val partOffset = instance.getBaseEntityOffset(part.displayEntity, part.originalBlockData)
      val partInitLoc = Vector3f(
        (part.originLocation.x + partOffset.x).toFloat(),
        (part.originLocation.y + partOffset.y).toFloat(),
        (part.originLocation.z + partOffset.z).toFloat()
      )
      val partRel = Vector3f(partInitLoc).sub(initConnectCenter)
      val partRotRel = Vector3f(partRel).rotate(totalRot)
      val partTargetX = curPivotX + partRotRel.x
      val partTargetY = curPivotY + partRotRel.y
      val partTargetZ = curPivotZ + partRotRel.z

      val partTargetLoc = Location(part.originLocation.world, partTargetX, partTargetY, partTargetZ)
      part.displayEntity.teleportDuration = durationTicks
      part.displayEntity.teleport(partTargetLoc)
      part.displayEntity.interpolationDuration = durationTicks
      part.displayEntity.interpolationDelay = 0
      part.displayEntity.transformation = Transformation(Vector3f(0f, 0f, 0f), totalRot, part.partScale, part.initialRightRotation)

      part.interactionEntity?.let { inter ->
        if (inter.isValid) {
          val partCenterRel = Vector3f(
            (part.originLocation.x + 0.5 - initConnectCenter.x).toFloat(),
            (part.originLocation.y + 0.5 - initConnectCenter.y).toFloat(),
            (part.originLocation.z + 0.5 - initConnectCenter.z).toFloat()
          )
          val rotCenterRel = Vector3f(partCenterRel).rotate(totalRot)
          val interLoc = Location(part.originLocation.world, curPivotX + rotCenterRel.x, curPivotY + rotCenterRel.y - 0.5, curPivotZ + rotCenterRel.z)
          inter.teleport(interLoc)
        }
      }
    }

    return true
  }
}
