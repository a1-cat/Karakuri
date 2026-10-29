package io.oyasai.karakuri.motion

import io.oyasai.karakuri.engine.KarakuriInstance
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs

/**
 * 指定角度まで滑らかに回転するモーション（跳ね橋、プログラムのステップ回転など）
 * 初期姿勢(initialOrientation)を引き継ぎ、Slerp球面線形補間によりシームレスに多段回転。
 */
class AngleRotationMotion(
  val axis: Axis,
  val targetAngleDeg: Float,
  val durationTicks: Int,
  val initialOrientation: Quaternionf = Quaternionf(),
) : MotionController {

  private var elapsedTicks = 0
  private var startOrientation: Quaternionf = Quaternionf()
  var finalOrientation: Quaternionf = Quaternionf()
    private set

  override fun onStart(instance: KarakuriInstance) {
    elapsedTicks = 0
    startOrientation = if (initialOrientation.lengthSquared() > 0.001f && initialOrientation != Quaternionf()) {
      Quaternionf(initialOrientation)
    } else {
      Quaternionf(instance.currentRotation)
    }
    val deltaRad = (targetAngleDeg * PI / 180.0).toFloat()
    // startOrientation から指定軸で回転した目標姿勢を算出
    finalOrientation = Quaternionf(startOrientation).rotateAxis(deltaRad, axis.vector.x, axis.vector.y, axis.vector.z)
    // 最短経路保証
    if (startOrientation.dot(finalOrientation) < 0.0f) {
      finalOrientation.set(-finalOrientation.x, -finalOrientation.y, -finalOrientation.z, -finalOrientation.w)
    }
  }

  override fun tick(instance: KarakuriInstance, currentTick: Long): Boolean {
    elapsedTicks++
    val progress = (elapsedTicks.toFloat() / durationTicks.toFloat()).coerceIn(0.0f, 1.0f)
    // Smoothstep イージング
    val t = progress * progress * (3.0f - 2.0f * progress)

    // startOrientation から finalOrientation へ直接 Slerp 補間（前の姿勢から滑らかに回転！）
    val curQuat = Quaternionf(startOrientation).slerp(finalOrientation, t)
    instance.applyRotation(curQuat, 1, 0, null)

    return elapsedTicks < durationTicks
  }
}

/**
 * 連続して回転し続けるモーション（水車、風車、回転台など）
 * 固定グローバル軸ベクトルによる直接クォータニオン生成と 1-tick 完全同期補間により、
 * すりこぎ運動（軸の傾き）および高速回転時の中心軸の弦誤差・ブレを物理的・数学的に完全に排除。
 */
class ContinuousSpinMotion(
  val axis: Axis,
  var degreesPerSecond: Float = 96f, // デフォルト 16 RPM
  var isRunning: Boolean = true,
) : MotionController {

  // ベースの回転方向（手動・GUI設定準拠）
  var baseIsClockwise: Boolean = degreesPerSecond >= 0
  // 銅の電球による回転反転フラグ (光っている際に反転)
  var isBulbInverted: Boolean = false

  val effectiveDegreesPerSecond: Float
    get() {
      val speed = abs(degreesPerSecond)
      val isCw = if (isBulbInverted) !baseIsClockwise else baseIsClockwise
      return if (isCw) speed else -speed
    }

  var rpm: Float
    get() = abs(degreesPerSecond) / 6.0f
    set(value) {
      baseIsClockwise = degreesPerSecond >= 0
      val sign = if (baseIsClockwise) 1.0f else -1.0f
      degreesPerSecond = value.coerceIn(0.1f, 256.0f) * 6.0f * sign
    }

  fun toggleDirection() {
    baseIsClockwise = !baseIsClockwise
    degreesPerSecond = -degreesPerSecond
  }

  private var currentOrientation: Quaternionf = Quaternionf()

  fun resetOrientation() {
    currentOrientation = Quaternionf()
    stepCount = 0L
  }

  fun toggle(instance: KarakuriInstance? = null): Boolean {
    isRunning = !isRunning
    if (!isRunning && instance != null) {
      org.bukkit.Bukkit.getLogger().info("[Karakuri-Test] ${instance.id} 手動停止: durationTicks=0 即時反映 (opt=${instance.isOptimized}, comp=${instance.dynamicCompMode.name})")
      instance.applyRotation(currentOrientation, durationTicks = 0)
    }
    return isRunning
  }

  override fun onStart(instance: KarakuriInstance) {
    instance.restoreFromYawMode()
    instance.cleanupDualEntities()
    baseIsClockwise = degreesPerSecond >= 0
    currentOrientation = if (instance.currentRotation.lengthSquared() > 0.001f) {
      Quaternionf(instance.currentRotation).normalize()
    } else {
      Quaternionf()
    }
    // 回転開始時の安全初期化: 過去のQuaternion履歴を初期姿勢で揃える
    instance.resetRotationHistory(currentOrientation)
    org.bukkit.Bukkit.getLogger().info("[Karakuri-Test] ${instance.id} 回転開始: axis=${axis.name}, rpm=$rpm, opt=${instance.isOptimized}, comp=${instance.dynamicCompMode.name}")
    if (isRunning) {
      applyStep(instance)
    }
  }

  override fun onStop(instance: KarakuriInstance) {
    instance.restoreFromYawMode()
    instance.cleanupDualEntities()
    org.bukkit.Bukkit.getLogger().info("[Karakuri-Test] ${instance.id} 回転停止: durationTicks=0 即時反映 (opt=${instance.isOptimized}, comp=${instance.dynamicCompMode.name})")
    instance.applyRotation(currentOrientation, durationTicks = 0)
  }

  override fun tick(instance: KarakuriInstance, currentTick: Long): Boolean {
    if (isRunning) {
      applyStep(instance)
    }
    return true
  }

  private var stepCount: Long = 0L

  private fun applyStep(instance: KarakuriInstance) {
    stepCount++
    val deltaDeg = (effectiveDegreesPerSecond / 20.0f)
    val deltaRad = deltaDeg.toDouble() * (PI / 180.0)

    val dq = Quaternionf().rotateAxis(deltaRad.toFloat(), axis.vector.x, axis.vector.y, axis.vector.z)
    val nextOrientation = Quaternionf(dq).mul(currentOrientation).normalize()

    val dot = currentOrientation.dot(nextOrientation)
    if (dot < 0.0f) {
      org.bukkit.Bukkit.getLogger().warning("[Karakuri-Debug] ${instance.id} step=$stepCount NEGATIVE DOT DETECTED: dot=$dot, axis=${axis.name}, rpm=$rpm")
    }
    // 検証用: メインスレッド負荷低減のため 20 tick 周期の定常同期ログを停止
    // else if (stepCount % 20L == 0L) { ... }

    currentOrientation = nextOrientation

    // 基準設定: durationTicks = 2, delayTicks = 0 (V2_ORTHO_T1 および RIGID_SYNC 検証時のみ 1-tick 補間)
    val targetDuration = if (
      instance.dynamicCompMode == KarakuriInstance.DynamicCompMode.V2_ORTHO_T1 ||
      instance.dynamicCompMode == KarakuriInstance.DynamicCompMode.RIGID_SYNC
    ) 1 else 2
    instance.applyRotation(currentOrientation, durationTicks = targetDuration, delayTicks = 0)
  }
}
