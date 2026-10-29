package io.oyasai.karakuri.motion

import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.engine.KarakuriSounds
import org.bukkit.Location

/**
 * 直線運動の動作モード
 */
enum class LinearLoopMode(val displayName: String, val description: String) {
  AUTO_PING_PONG("🔁 自動往復 (Ping-Pong)", "始点と終点を自動で往復し続けます。"),
  MANUAL_TOGGLE("🕹️ 手動往復ループ", "端に到達すると停止し、次回作動時に逆方向へ移動します。"),
  ONE_WAY_LOOP("🔄 自動一方通行ループ", "終点に到達後、始点に自動で瞬間復帰して同じ方向へ繰り返します。"),
  MANUAL_ONE_WAY("🔂 手動一方通行ループ", "終点に到達後停止し、次回作動時に始点から同じ方向へ移動します。");
}

/**
 * 任意の2地点間（斜め移動を含む完全な3D移動）を滑らかに移動する直線運動コントローラー。
 * 毎tickの精密補間により、MinecraftのteleportDuration不具合を回避し、
 * 「ゆっくり」な速度でも絶対に停止・フリーズせず極めて滑らかに動く。
 * 自動往復 (Ping-Pong)、一方通行ループ、1回実行、手動トグル、一時停止・再開に対応。
 */
class IntegratedLinearMotion(
  var startLocation: Location? = null,
  var endLocation: Location? = null,
  var durationTicks: Int = 40, // 移動速度 (ticks)
  var loopMode: LinearLoopMode = LinearLoopMode.AUTO_PING_PONG,
  var pauseTicks: Int = 20, // 端点での停止時間
  var blocksPerSecond: Double = 2.0, // 秒速 (m/s)
) : MotionController {

  constructor(
    startLocation: Location?,
    endLocation: Location?,
    durationTicks: Int = 40,
    isAutoPingPong: Boolean = true,
    pauseTicks: Int = 20
  ) : this(
    startLocation,
    endLocation,
    durationTicks,
    if (isAutoPingPong) LinearLoopMode.AUTO_PING_PONG else LinearLoopMode.MANUAL_TOGGLE,
    pauseTicks,
    2.0
  )

  /**
   * 現在の移動距離と秒速 (blocksPerSecond) から所要時間 ticks を再計算
   */
  fun updateDurationTicks() {
    val dist = getDistance()
    val bps = blocksPerSecond.coerceAtLeast(0.1)
    durationTicks = maxOf(5, ((dist / bps) * 20.0).toInt().coerceAtLeast(5))
  }

  // 下位互換プロパティ
  var isAutoPingPong: Boolean
    get() = loopMode == LinearLoopMode.AUTO_PING_PONG
    set(value) {
      loopMode = if (value) LinearLoopMode.AUTO_PING_PONG else LinearLoopMode.MANUAL_TOGGLE
    }

  enum class ToggleResult {
    STARTED,
    PAUSED,
    RESUMED,
    TOGGLED,
  }

  var isRunning: Boolean = false
  var isPaused: Boolean = false
  var isMoving: Boolean = false
    private set
  var isAtEnd: Boolean = false
    private set

  var isBulbReversing: Boolean = false

  private var moveTicks: Int = 0
  private var waitTicks: Int = 0
  private var fromLoc: Location? = null
  private var toLoc: Location? = null
  private var legDurationTicks: Int = 40

  /**
   * 銅の電球 (錆び・酸化) による逆再生の切り替え
   */
  fun setBulbReversing(reversing: Boolean, instance: KarakuriInstance) {
    if (isBulbReversing == reversing) return
    isBulbReversing = reversing

    val s = startLocation ?: instance.originLocation.clone()
    val e = endLocation ?: s.clone().add(0.0, 3.0, 0.0)
    val target = if (isBulbReversing) s else e

    val curLoc = instance.displayEntity.location

    if (isMoving) {
      // 移動中に切り替え: 現在の進行方向とは反対の端点へ滑らかに折り返す！
      val currentTargetIsEnd = (toLoc != null && toLoc!!.distanceSquared(e) < toLoc!!.distanceSquared(s))
      val target = if (currentTargetIsEnd) s else e

      fromLoc = curLoc.clone()
      toLoc = target.clone()
      val dist = curLoc.distance(target)
      val totalDist = s.distance(e).coerceAtLeast(0.01)
      val neededTicks = ((dist / totalDist) * durationTicks).toInt().coerceIn(2, durationTicks)
      legDurationTicks = neededTicks
      moveTicks = 0
      waitTicks = 0
      KarakuriSounds.playMotion(curLoc)
    } else {
      // 停止中・端点待機中
      isRunning = true
      isPaused = false
      waitTicks = 0
      if (loopMode == LinearLoopMode.ONE_WAY_LOOP) {
        if (isBulbReversing) {
          instance.applySmoothTeleport(e, 1)
          isAtEnd = true
          startMoveTo(instance, s)
        } else {
          instance.applySmoothTeleport(s, 1)
          isAtEnd = false
          startMoveTo(instance, e)
        }
      } else {
        val target = if (isAtEnd) s else e
        startMoveTo(instance, target)
      }
    }
  }

  override fun onStart(instance: KarakuriInstance) {
    // 直線運動モード開始時、前の回転姿勢や変位をゼロクリアして正規化（見た目とクリック判定のズレを根絶）
    instance.updateCurrentRotation(org.joml.Quaternionf())
    instance.applyRotation(org.joml.Quaternionf(), 0, 0, null)

    if (startLocation == null) {
      startLocation = instance.originLocation.clone()
    }
    // ブロック角座標に正規化して +0.5 南東ズレを根絶
    startLocation = startLocation?.let {
      Location(it.world, it.blockX.toDouble(), it.blockY.toDouble(), it.blockZ.toDouble(), it.yaw, it.pitch)
    }
    if (endLocation == null) {
      endLocation = startLocation!!.clone().add(0.0, 3.0, 0.0) // デフォルトY+3
    }
    endLocation = endLocation?.let {
      Location(it.world, it.blockX.toDouble(), it.blockY.toDouble(), it.blockZ.toDouble(), it.yaw, it.pitch)
    }

    updateDurationTicks()

    // 初回は勝手に動かず停止状態を維持（Shift素手右クリックで開始）
    isRunning = false
    isPaused = false
    isMoving = false
    isAtEnd = false
    isBulbReversing = false
    moveTicks = 0
    waitTicks = 0
  }

  fun setEndpoints(start: Location, end: Location) {
    startLocation = Location(start.world, start.blockX.toDouble(), start.blockY.toDouble(), start.blockZ.toDouble(), start.yaw, start.pitch)
    endLocation = Location(end.world, end.blockX.toDouble(), end.blockY.toDouble(), end.blockZ.toDouble(), end.yaw, end.pitch)
    updateDurationTicks()
    isRunning = false
    isPaused = false
    isMoving = false
    isAtEnd = false
    isBulbReversing = false
    moveTicks = 0
    waitTicks = 0
  }

  /**
   * 手動トグル / 開始 / 一時停止 / 再開の切り替え
   */
  fun toggle(instance: KarakuriInstance): ToggleResult {
    val s = startLocation ?: instance.originLocation.clone()
    val e = endLocation ?: s.clone().add(0.0, 3.0, 0.0)

    if (!isRunning) {
      // 1. 初回動作開始 / 停止後の再開
      isRunning = true
      isPaused = false
      if (loopMode == LinearLoopMode.MANUAL_ONE_WAY) {
        // 手動一方通行: 始点へ瞬時にスナップ復帰してから終点へ移動！
        instance.applySmoothTeleport(s, 1)
        isAtEnd = false
        startMoveTo(instance, e)
        return ToggleResult.STARTED
      }
      val target = if (isAtEnd) s else e
      startMoveTo(instance, target)
      return ToggleResult.STARTED
    }

    // 2. 実行中の挙動
    return if (loopMode == LinearLoopMode.MANUAL_TOGGLE) {
      // 手動トグルモード: 移動中なら反転、停止中なら逆方向へ移動
      if (isMoving) {
        val currentTarget = toLoc
        if (currentTarget == e) {
          startMoveTo(instance, s)
          isAtEnd = false
        } else {
          startMoveTo(instance, e)
          isAtEnd = true
        }
      } else {
        val target = if (isAtEnd) s else e
        startMoveTo(instance, target)
      }
      ToggleResult.TOGGLED
    } else {
      // 自動往復 / 一方通行 / 1回実行: 一時停止 / 再開 の切り替え
      isPaused = !isPaused
      if (!isPaused) {
        KarakuriSounds.playMotion(instance.displayEntity.location)
        ToggleResult.RESUMED
      } else {
        ToggleResult.PAUSED
      }
    }
  }

  /**
   * 初期位置（始点）に戻して停止する
   */
  fun reset(instance: KarakuriInstance) {
    isRunning = false
    isPaused = false
    isMoving = false
    isAtEnd = false
    isBulbReversing = false
    moveTicks = 0
    waitTicks = 0
    fromLoc = null
    toLoc = null
    instance.resetToBase()
  }

  private fun startMoveTo(instance: KarakuriInstance, target: Location) {
    val s = startLocation ?: instance.originLocation.clone()
    val e = endLocation ?: s.clone().add(0.0, 3.0, 0.0)
    fromLoc = if (target == e) s.clone() else e.clone()
    toLoc = target.clone()
    isMoving = true
    moveTicks = 0
    waitTicks = 0
    legDurationTicks = durationTicks

    KarakuriSounds.playMotion(target)
  }

  override fun tick(instance: KarakuriInstance, currentTick: Long): Boolean {
    val s = startLocation ?: return true
    val e = endLocation ?: return true

    if (!isRunning || isPaused) return true

    if (isMoving) {
      val from = fromLoc ?: return true
      val to = toLoc ?: return true

      moveTicks++
      val currentLeg = maxOf(1, legDurationTicks)
      val progress = (moveTicks.toFloat() / currentLeg.toFloat()).coerceIn(0.0f, 1.0f)
      // Smoothstep イージング (3p^2 - 2p^3) による自然な加減速
      val t = progress * progress * (3.0f - 2.0f * progress)

      val curX = from.x + (to.x - from.x) * t
      val curY = from.y + (to.y - from.y) * t
      val curZ = from.z + (to.z - from.z) * t

      val curLoc = Location(from.world, curX, curY, curZ, from.yaw, from.pitch)

      // 毎tick 2-tick補間で確実にテレポート追従
      instance.applySmoothTeleport(curLoc, 2)

      if (moveTicks >= currentLeg) {
        isMoving = false
        isAtEnd = (to.distanceSquared(e) < 0.01)
        moveTicks = 0
        waitTicks = 0
        instance.applySmoothTeleport(to, 1)

        if (loopMode == LinearLoopMode.MANUAL_ONE_WAY) {
          // 手動一方通行: 到着したら停止
          isRunning = false
        }
      }
    } else {
      // 停止中 / 端点での一時待機中
      when (loopMode) {
        LinearLoopMode.AUTO_PING_PONG -> {
          waitTicks++
          if (waitTicks >= pauseTicks) {
            waitTicks = 0
            val nextTarget = if (isAtEnd) s else e
            startMoveTo(instance, nextTarget)
          }
        }
        LinearLoopMode.ONE_WAY_LOOP -> {
          waitTicks++
          if (waitTicks >= pauseTicks) {
            waitTicks = 0
            if (isBulbReversing) {
              // 逆再生時の一方通行: 始点到達後、終点に瞬間復帰して始点へ繰り返す
              instance.applySmoothTeleport(e, 1)
              isAtEnd = true
              startMoveTo(instance, s)
            } else {
              // 通常の一方通行: 終点到達後、始点に瞬間復帰して終点へ繰り返す
              instance.applySmoothTeleport(s, 1)
              isAtEnd = false
              startMoveTo(instance, e)
            }
          }
        }
        LinearLoopMode.MANUAL_TOGGLE, LinearLoopMode.MANUAL_ONE_WAY -> {
          // 到着後は次の操作まで待機
        }
      }
    }

    return true
  }

  /**
   * 斜め移動を含めた総直線距離を計算
   */
  fun getDistance(): Double {
    val s = startLocation ?: return 0.0
    val e = endLocation ?: return 0.0
    val dx = e.x - s.x
    val dy = e.y - s.y
    val dz = e.z - s.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
  }
}
