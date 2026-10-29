package io.oyasai.karakuri.motion

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.engine.KarakuriSounds
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI

/**
 * ドア（扉・門）専用のスマートトグルモーション。
 * 回転速度(RPM)が開閉速度に完全連動し、時計回り・反時計回り、自動クローズもサポート。
 */
class DoorMotion(
  var axis: Axis = Axis.Y,
  var openAngleDeg: Float = 90f,
  var rpm: Float = 16f,
  var autoCloseTicks: Int = 60,
  var clockwise: Boolean = true,
) : MotionController {

  var isOpened: Boolean = false
    private set

  private var isAnimating: Boolean = false
  private var animTicks: Int = 0
  private var targetAnimTicks: Int = 19
  private var startAngleDeg: Float = 0f
  private var targetAngleDeg: Float = 0f
  private var currentAngleDeg: Float = 0f
  private var openedStayTicks: Int = 0

  val durationTicks: Int
    get() {
      val dps = (rpm * 6.0f).coerceAtLeast(1.0f)
      return (openAngleDeg / dps * 20.0f).toInt().coerceIn(2, 200)
    }

  private var isRestored: Boolean = false

  fun initRestoredState(opened: Boolean) {
    this.isOpened = opened
    this.isRestored = true
    val dirMultiplier = if (clockwise) 1.0f else -1.0f
    this.currentAngleDeg = if (opened) openAngleDeg * dirMultiplier else 0.0f
    this.targetAngleDeg = this.currentAngleDeg
  }

  override fun onStart(instance: KarakuriInstance) {
    if (!isRestored) {
      currentAngleDeg = 0f
      isOpened = false
      applyAngle(instance, 0f, 0)
    }
    isAnimating = false
  }

  fun toggle(instance: KarakuriInstance, isManual: Boolean = true) {
    if (isAnimating) return

    isOpened = !isOpened
    isAnimating = true
    animTicks = 0
    openedStayTicks = 0

    targetAnimTicks = durationTicks
    startAngleDeg = currentAngleDeg

    val dirMultiplier = if (clockwise) 1.0f else -1.0f
    targetAngleDeg = if (isOpened) openAngleDeg * dirMultiplier else 0.0f

    val loc = instance.displayEntity.location
    if (isManual) {
      KarakuriSounds.playManual(loc)
    } else {
      KarakuriSounds.playAuto(loc)
    }
  }

  override fun tick(instance: KarakuriInstance, currentTick: Long): Boolean {
    if (isAnimating) {
      animTicks++
      val progress = (animTicks.toFloat() / targetAnimTicks.toFloat()).coerceIn(0.0f, 1.0f)
      val t = progress * progress * (3.0f - 2.0f * progress)
      currentAngleDeg = startAngleDeg + (targetAngleDeg - startAngleDeg) * t

      applyAngle(instance, currentAngleDeg, 1)

      if (animTicks >= targetAnimTicks) {
        isAnimating = false
        currentAngleDeg = targetAngleDeg
        applyAngle(instance, currentAngleDeg, 1)
      }
    } else if (isOpened && autoCloseTicks > 0) {
      openedStayTicks++
      if (openedStayTicks >= autoCloseTicks) {
        toggle(instance, isManual = false)
      }
    }
    return true
  }

  private fun applyAngle(instance: KarakuriInstance, angleDeg: Float, duration: Int) {
    val rad = (angleDeg * PI / 180.0).toFloat()
    val rot = Quaternionf().rotateAxis(rad, axis.vector.x, axis.vector.y, axis.vector.z)
    instance.applyRotation(rot, duration, 0, null)
  }
}
