package io.oyasai.karakuri.motion

import io.oyasai.karakuri.engine.KarakuriInstance
import org.bukkit.Location
import org.bukkit.Sound
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.max

/**
 * プログラムされた一連のアクション（最大7ステップ）を順次または同時並行に実行するコントローラー。
 * 複数の回転・直線移動を同時に並行実行（複合回転・スクリュー上昇など）可能。
 * Waypointによる絶対座標追従とActionGroup事前コンパイルにより、
 * 手動トグル連打時の位置ドリフトおよび復路でのグループ破綻を数学的・物理的に100%根絶。
 */
class ProgramMotion(
  val steps: List<KarakuriAction>,
  var loopMode: ProgramLoopMode = ProgramLoopMode.AUTO_REVERSE,
) : MotionController {

  /**
   * 同時並行実行されるアクションを1つに統合した実行単位グループ
   */
  data class ActionGroup(
    val actions: List<KarakuriAction>,
    val durationTicks: Int,
    val deltaX: Double,
    val deltaY: Double,
    val deltaZ: Double,
    val rotationQuat: Quaternionf,
    val pivot: Vector3f,
    val hasMove: Boolean,
    val hasRotate: Boolean,
    val speedTransition: KarakuriAction.SpeedTransition? = null,
    val spinAction: KarakuriAction.Spin? = null,
  )

  /**
   * 原点からの絶対キーフレーム（位置・姿勢）
   */
  data class Waypoint(
    val location: Location,
    val orientation: Quaternionf,
  )

  /**
   * トグル操作の結果
   */
  enum class ProgramToggleResult {
    STARTED,
    PAUSED,
    RESUMED,
    REVERSED,
    RESET_TO_BASE,
  }

  private var forwardGroups: List<ActionGroup> = emptyList()
  private var waypoints: List<Waypoint> = emptyList()

  private var isReversing = false
  private var currentGroupIndex = 0
  private var groupProgress: Float = 0.0f
  var isWaitingTrigger: Boolean = false
  var isWaitingReset: Boolean = false

  val isPlaying: Boolean
    get() = !isWaitingTrigger && !isWaitingReset

  val isRotating: Boolean
    get() = isPlaying && curHasRotate

  fun pause() {
    isWaitingTrigger = true
  }

  // 現在実行中のグループステート
  private var curStartLoc: Location? = null
  private var curTargetLoc: Location? = null
  private var curStartQuat: Quaternionf = Quaternionf()
  private var curTargetQuat: Quaternionf = Quaternionf()
  private var curDurationTicks: Int = 20
  private var curPivot: Vector3f = Vector3f(0.5f, 0.5f, 0.5f)
  private var curHasMove: Boolean = false
  private var curHasRotate: Boolean = false
  private var curSpeedTransition: KarakuriAction.SpeedTransition? = null
  private var curSpinAction: KarakuriAction.Spin? = null

  var isBulbInverted: Boolean = false
  var isBulbReversing: Boolean = false

  fun setBulbRotationInverted(inverted: Boolean, instance: KarakuriInstance) {
    if (isBulbInverted != inverted) {
      isBulbInverted = inverted
      recompileGroups(instance)
    }
  }

  /**
   * 銅の電球 (錆び・酸化) による逆再生の切り替え
   * 現在位置・現在の進捗度から瞬時に滑らかに反転。不自然な瞬間ワープを100%排除。
   */
  fun setBulbReversing(reversing: Boolean, instance: KarakuriInstance) {
    if (isBulbReversing == reversing) return
    isBulbReversing = reversing
    isReversing = reversing

    if (forwardGroups.isEmpty() || waypoints.size < 2) return

    val maxIdx = forwardGroups.size - 1
    currentGroupIndex = currentGroupIndex.coerceIn(0, maxIdx)

    isWaitingReset = false

    if (reversing) {
      // 【逆再生モード (ON)】: 終点から原点方向へ向かう
      if (currentGroupIndex == 0 && groupProgress <= 0.001f) {
        // 既に原点(開始地点)にいる場合は待機
        isWaitingTrigger = true
      } else {
        // 終点または途中にいる場合: その位置から原点に向かって即座に逆再生を開始！
        isWaitingTrigger = false
        setupCurrentGroup(instance)
      }
    } else {
      // 【正再生モード (OFF)】: 原点から終点方向へ向かう
      if (currentGroupIndex == maxIdx && groupProgress >= 0.999f) {
        // 既に終点(終了地点)にいる場合は待機
        isWaitingTrigger = true
      } else {
        // 原点または途中にいる場合: その位置から終点に向かって即座に正再生を開始！
        isWaitingTrigger = false
        setupCurrentGroup(instance)
      }
    }

    io.oyasai.karakuri.engine.KarakuriSounds.playMotion(instance.displayEntity.location)
  }

  fun recompileGroups(instance: KarakuriInstance) {
    forwardGroups = compileGroups(steps, instance)

    val originLoc = instance.originLocation.clone()
    val wpList = mutableListOf<Waypoint>()
    var curPos = originLoc.clone()
    var curRot = Quaternionf()
    wpList.add(Waypoint(curPos.clone(), Quaternionf(curRot)))

    for (group in forwardGroups) {
      curPos = curPos.clone().add(group.deltaX, group.deltaY, group.deltaZ)
      val nextRot = Quaternionf(curRot).mul(group.rotationQuat)
      if (curRot.dot(nextRot) < 0.0f) {
        nextRot.set(-nextRot.x, -nextRot.y, -nextRot.z, -nextRot.w)
      }
      curRot = nextRot
      wpList.add(Waypoint(curPos.clone(), Quaternionf(curRot)))
    }
    waypoints = wpList

    if (forwardGroups.isNotEmpty() && currentGroupIndex in forwardGroups.indices) {
      val group = forwardGroups[currentGroupIndex]
      curDurationTicks = group.durationTicks
      curPivot = group.pivot
      curHasMove = group.hasMove
      curHasRotate = group.hasRotate
      curSpeedTransition = group.speedTransition
      curSpinAction = group.spinAction

      val fromWp = waypoints[currentGroupIndex]
      val toWp = waypoints[currentGroupIndex + 1]

      curStartLoc = fromWp.location.clone()
      curTargetLoc = toWp.location.clone()
      curStartQuat = Quaternionf(fromWp.orientation)
      curTargetQuat = Quaternionf(toWp.orientation)

      if (curStartQuat.dot(curTargetQuat) < 0.0f) {
        curTargetQuat.set(-curTargetQuat.x, -curTargetQuat.y, -curTargetQuat.z, -curTargetQuat.w)
      }
    }
  }

  private var isRestored = false

  fun initRestoredState(isPlaying: Boolean) {
    isRestored = true
    isWaitingTrigger = !isPlaying
  }

  override fun onStart(instance: KarakuriInstance) {
    if (!isRestored) {
      isReversing = false
      currentGroupIndex = 0
      groupProgress = 0.0f
      isWaitingTrigger = true // 最初は停止状態で待機！クリックまたはRS信号で開始！
      isWaitingReset = false
    }
    isRestored = false

    // 1. 正方向グループを構築（同時実行フラグに基づきグループ化）
    forwardGroups = compileGroups(steps, instance)

    // 2. 原点からの Waypoint (キーフレーム座標・姿勢) を事前確定
    val originLoc = instance.originLocation.clone()
    val wpList = mutableListOf<Waypoint>()
    var curPos = originLoc.clone()
    var curRot = Quaternionf()
    wpList.add(Waypoint(curPos.clone(), Quaternionf(curRot)))

    for (group in forwardGroups) {
      curPos = curPos.clone().add(group.deltaX, group.deltaY, group.deltaZ)
      val nextRot = Quaternionf(curRot).mul(group.rotationQuat)
      // 最短経路保証
      if (curRot.dot(nextRot) < 0.0f) {
        nextRot.set(-nextRot.x, -nextRot.y, -nextRot.z, -nextRot.w)
      }
      curRot = nextRot
      wpList.add(Waypoint(curPos.clone(), Quaternionf(curRot)))
    }
    waypoints = wpList

    // 最初のグループを開始
    setupCurrentGroup(instance)
  }

  private fun compileGroups(rawSteps: List<KarakuriAction>, instance: KarakuriInstance): List<ActionGroup> {
    if (rawSteps.isEmpty()) return emptyList()

    val groups = mutableListOf<ActionGroup>()
    var currentSub = mutableListOf<KarakuriAction>()

    for (action in rawSteps) {
      currentSub.add(action)
      if (!action.isSimultaneous) {
        groups.add(buildGroup(currentSub, instance))
        currentSub = mutableListOf()
      }
    }
    if (currentSub.isNotEmpty()) {
      groups.add(buildGroup(currentSub, instance))
    }
    return groups
  }

  private fun buildGroup(actions: List<KarakuriAction>, instance: KarakuriInstance): ActionGroup {
    var maxDuration = 10
    var dx = 0.0
    var dy = 0.0
    var dz = 0.0
    var compositeQuat = Quaternionf()
    var pivot = instance.pivotOffset
    var hasMove = false
    var hasRotate = false
    var speedTransition: KarakuriAction.SpeedTransition? = null
    var spinAction: KarakuriAction.Spin? = null

    for (action in actions) {
      when (action) {
        is KarakuriAction.Wait -> {
          maxDuration = max(maxDuration, action.waitTicks)
        }
        is KarakuriAction.Move -> {
          hasMove = true
          dx += action.dx
          dy += action.dy
          dz += action.dz
          maxDuration = max(maxDuration, action.durationTicks)
        }
        is KarakuriAction.Rotate -> {
          hasRotate = true
          val angleDeg = if (isBulbInverted) -action.angleDeg else action.angleDeg
          val deltaRad = (angleDeg * PI / 180.0).toFloat()
          val stepQuat = Quaternionf().rotateAxis(deltaRad, action.axis.vector.x, action.axis.vector.y, action.axis.vector.z)
          compositeQuat.mul(stepQuat)
          maxDuration = max(maxDuration, action.durationTicks)
          if (action.pivot != null) pivot = action.pivot
        }
        is KarakuriAction.Spin -> {
          hasRotate = true
          if (spinAction == null) spinAction = action
          val dps = if (isBulbInverted) -action.degreesPerSecond else action.degreesPerSecond
          val totalAngleDeg = dps * (action.durationTicks / 20.0f)
          val deltaRad = (totalAngleDeg * PI / 180.0).toFloat()
          val stepQuat = Quaternionf().rotateAxis(deltaRad, action.axis.vector.x, action.axis.vector.y, action.axis.vector.z)
          compositeQuat.mul(stepQuat)
          maxDuration = max(maxDuration, action.durationTicks)
          if (action.pivot != null) pivot = action.pivot
        }
        is KarakuriAction.SpeedTransition -> {
          hasRotate = true
          if (speedTransition == null) speedTransition = action
          val avgRpm = (action.startRpm + action.endRpm) / 2.0f
          var dirSign = if (action.isClockwise) 1.0f else -1.0f
          if (isBulbInverted) dirSign = -dirSign
          val durationSec = action.durationTicks / 20.0f
          val totalAngleDeg = dirSign * avgRpm * 6.0f * durationSec
          val deltaRad = (totalAngleDeg * PI / 180.0).toFloat()
          val stepQuat = Quaternionf().rotateAxis(deltaRad, action.axis.vector.x, action.axis.vector.y, action.axis.vector.z)
          compositeQuat.mul(stepQuat)
          maxDuration = max(maxDuration, action.durationTicks)
          if (action.pivot != null) pivot = action.pivot
        }
      }
    }

    return ActionGroup(
      actions = actions,
      durationTicks = maxDuration,
      deltaX = dx,
      deltaY = dy,
      deltaZ = dz,
      rotationQuat = compositeQuat,
      pivot = pivot,
      hasMove = hasMove,
      hasRotate = hasRotate,
      speedTransition = speedTransition,
      spinAction = spinAction,
    )
  }

  private fun setupCurrentGroup(instance: KarakuriInstance) {
    if (forwardGroups.isEmpty() || waypoints.size < 2) return
    if (currentGroupIndex !in forwardGroups.indices) return

    val group = forwardGroups[currentGroupIndex]
    curDurationTicks = group.durationTicks
    curPivot = group.pivot
    curHasMove = group.hasMove
    curHasRotate = group.hasRotate
    curSpeedTransition = group.speedTransition
    curSpinAction = group.spinAction

    // 常にグループ index の始点 W[index] と終点 W[index + 1]
    val fromWp = waypoints[currentGroupIndex]
    val toWp = waypoints[currentGroupIndex + 1]

    curStartLoc = fromWp.location.clone()
    curTargetLoc = toWp.location.clone()
    curStartQuat = Quaternionf(fromWp.orientation)
    curTargetQuat = Quaternionf(toWp.orientation)

    // 最短経路保証 (内積が負なら反転)
    if (curStartQuat.dot(curTargetQuat) < 0.0f) {
      curTargetQuat.set(-curTargetQuat.x, -curTargetQuat.y, -curTargetQuat.z, -curTargetQuat.w)
    }

    // 統一システム効果音
    val loc = instance.displayEntity.location
    if (group.hasMove || group.hasRotate) {
      io.oyasai.karakuri.engine.KarakuriSounds.playMotion(loc)
    }
  }

  override fun tick(instance: KarakuriInstance, currentTick: Long): Boolean {
    if (forwardGroups.isEmpty()) return false
    if (isWaitingTrigger) return true

    val delta = if (curDurationTicks > 0) 1.0f / curDurationTicks.toFloat() else 1.0f

    if (!isReversing) {
      groupProgress = (groupProgress + delta).coerceIn(0.0f, 1.0f)
    } else {
      groupProgress = (groupProgress - delta).coerceIn(0.0f, 1.0f)
    }

    val progress = groupProgress.coerceIn(0.0f, 1.0f)
    // Smoothstep イージング
    val t = progress * progress * (3.0f - 2.0f * progress)

    val curLoc = if (curHasMove && curStartLoc != null && curTargetLoc != null) {
      val s = curStartLoc!!
      val e = curTargetLoc!!
      val curX = s.x + (e.x - s.x) * t
      val curY = s.y + (e.y - s.y) * t
      val curZ = s.z + (e.z - s.z) * t
      Location(s.world, curX, curY, curZ, s.yaw, s.pitch)
    } else null

    val curQuat = if (curHasRotate) {
      val st = curSpeedTransition
      val sp = curSpinAction
      if (st != null) {
        val s0 = st.startRpm
        val s1 = st.endRpm
        var dirSign = if (st.isClockwise) 1.0f else -1.0f
        if (isBulbInverted) dirSign = -dirSign
        val durationSec = curDurationTicks / 20.0f
        // 2次式累積回転角: θ(s) = dirSign * (s0 * s + (s1 - s0)/2 * s^2) * 6.0 * T
        val angleDeg = dirSign * (s0 * progress + (s1 - s0) * 0.5f * progress * progress) * 6.0f * durationSec
        val rad = (angleDeg * PI / 180.0).toFloat()
        val deltaQuat = Quaternionf().rotateAxis(rad, st.axis.vector.x, st.axis.vector.y, st.axis.vector.z)
        Quaternionf(deltaQuat).mul(curStartQuat)
      } else if (sp != null) {
        val dps = if (isBulbInverted) -sp.degreesPerSecond else sp.degreesPerSecond
        val durationSec = curDurationTicks / 20.0f
        val angleDeg = dps * durationSec * progress
        val rad = (angleDeg * PI / 180.0).toFloat()
        val deltaQuat = Quaternionf().rotateAxis(rad, sp.axis.vector.x, sp.axis.vector.y, sp.axis.vector.z)
        Quaternionf(deltaQuat).mul(curStartQuat)
      } else {
        // 通常の角度回転: Smoothstep による Slerp
        Quaternionf(curStartQuat).slerp(curTargetQuat, t)
      }
    } else null

    // 回転速度・弦誤差に応じた適応ハイブリッド補間（高速回転時の中心軸荒ぶり・ブレを完全防止）
    val rotDuration = if (curHasRotate) {
      val st = curSpeedTransition
      val sp = curSpinAction
      val currentRpm = if (st != null) {
        st.startRpm + (st.endRpm - st.startRpm) * progress
      } else if (sp != null) {
        kotlin.math.abs(sp.degreesPerSecond) / 6.0f
      } else {
        0f
      }
      val maxDist = instance.getMaxPivotDistance()
      val deltaRadPerTick = (currentRpm * 6.0f * (PI / 180.0) / 20.0).toDouble()
      val sagitta = maxDist * (1.0 - kotlin.math.cos(kotlin.math.abs(deltaRadPerTick) * 0.5))
      if (currentRpm >= 60f || sagitta > 0.05) 0 else 1
    } else 1

    // 1. 直線移動と回転の完全位相同期適用（パーツ間の隙間・離脱を100%防止）
    if (curLoc != null && curQuat != null) {
      instance.applySynchronizedMotion(curLoc, curQuat, rotDuration)
    } else if (curLoc != null) {
      instance.applySmoothTeleport(curLoc, 1)
    } else if (curQuat != null) {
      instance.applyRotation(curQuat, rotDuration, 0, null)
    }

    // 完了・境界判定（ステップ境界での重複送信・不連続上書きを完全防止）
    if (!isReversing) {
      if (groupProgress >= 1.0f) {
        advanceForward(instance)
      }
    } else {
      if (groupProgress <= 0.0f) {
        advanceBackward(instance)
      }
    }

    return true
  }

  private fun advanceForward(instance: KarakuriInstance) {
    currentGroupIndex++
    if (currentGroupIndex < forwardGroups.size) {
      groupProgress = 0.0f
      setupCurrentGroup(instance)
      return
    }

    // 全グループ完了（終点到達）
    when (loopMode) {
      ProgramLoopMode.AUTO_REVERSE -> {
        isReversing = true
        currentGroupIndex = forwardGroups.size - 1
        groupProgress = 1.0f
        setupCurrentGroup(instance)
      }
      ProgramLoopMode.TOGGLE_REVERSE -> {
        isReversing = true
        currentGroupIndex = forwardGroups.size - 1
        groupProgress = 1.0f
        isWaitingTrigger = true // クリック待機
      }
      ProgramLoopMode.LOOP -> {
        // 一方通行ループ: 再び原点から再開（テレポート・スナップリセット）
        instance.applySmoothTeleport(instance.originLocation, 0)
        instance.applyRotation(Quaternionf(), 0, 0, null)
        isReversing = false
        currentGroupIndex = 0
        groupProgress = 0.0f
        setupCurrentGroup(instance)
      }
      ProgramLoopMode.ONCE_THEN_RESET -> {
        currentGroupIndex = forwardGroups.size - 1
        groupProgress = 1.0f
        isWaitingTrigger = true
        isWaitingReset = true
      }
    }
  }

  private fun advanceBackward(instance: KarakuriInstance) {
    currentGroupIndex--
    if (currentGroupIndex >= 0) {
      groupProgress = 1.0f
      setupCurrentGroup(instance)
      return
    }

    currentGroupIndex = 0
    groupProgress = 0.0f

    // 全グループ逆再生完了（原点到達）
    when (loopMode) {
      ProgramLoopMode.AUTO_REVERSE -> {
        if (isBulbReversing) {
          // 電球ONの逆再生で原点に達した場合は、終点にワープせず原点で安全に待機！
          isWaitingTrigger = true
          isReversing = true
          currentGroupIndex = 0
          groupProgress = 0.0f
        } else {
          isReversing = false
          currentGroupIndex = 0
          groupProgress = 0.0f
          instance.resetToBase(stopMotion = false)
          instance.applyRotation(Quaternionf(), 0, 0, null)
          setupCurrentGroup(instance)
        }
      }
      ProgramLoopMode.TOGGLE_REVERSE -> {
        isReversing = isBulbReversing
        currentGroupIndex = 0
        groupProgress = 0.0f
        instance.applySmoothTeleport(instance.originLocation, 0)
        instance.applyRotation(Quaternionf(), 0, 0, null)
        isWaitingTrigger = true // クリック待機
      }
      ProgramLoopMode.LOOP -> {
        if (isBulbReversing) {
          // 逆再生で原点に達した場合は、終点にワープせず原点で待機
          isWaitingTrigger = true
          isReversing = true
          currentGroupIndex = 0
          groupProgress = 0.0f
        } else {
          instance.applySmoothTeleport(instance.originLocation, 0)
          instance.applyRotation(Quaternionf(), 0, 0, null)
          isReversing = false
          currentGroupIndex = 0
          groupProgress = 0.0f
          setupCurrentGroup(instance)
        }
      }
      ProgramLoopMode.ONCE_THEN_RESET -> {
        instance.resetToBase(stopMotion = false)
        instance.applyRotation(Quaternionf(), 0, 0, null)
        isReversing = isBulbReversing
        currentGroupIndex = 0
        groupProgress = 0.0f
        isWaitingTrigger = true
        isWaitingReset = false
      }
    }
  }

  /**
   * 手動トグル（クリック時）の作動
   */
  fun toggle(instance: KarakuriInstance): ProgramToggleResult {
    if (loopMode == ProgramLoopMode.ONCE_THEN_RESET) {
      if (isWaitingReset) {
        // 【実行完了時】: 初期位置・初期姿勢にリセット！
        instance.applySmoothTeleport(instance.originLocation, 0)
        instance.applyRotation(Quaternionf(), 0, 0, null)
        isWaitingReset = false
        isWaitingTrigger = true // 次の再生クリック待ち
        currentGroupIndex = 0
        groupProgress = 0.0f
        isReversing = isBulbReversing
        curStartLoc = null
        return ProgramToggleResult.RESET_TO_BASE
      } else if (isWaitingTrigger) {
        // 再開、または新規再生開始！
        isWaitingTrigger = false
        isWaitingReset = false
        if (curStartLoc == null) {
          isReversing = isBulbReversing
          currentGroupIndex = 0
          groupProgress = 0.0f
          setupCurrentGroup(instance)
          return ProgramToggleResult.STARTED
        } else {
          return ProgramToggleResult.RESUMED
        }
      } else {
        // 動作途中でクリックされた場合: 一時停止！
        isWaitingTrigger = true
        return ProgramToggleResult.PAUSED
      }
    }

    if (isWaitingTrigger) {
      isWaitingTrigger = false
      if (curStartLoc == null) {
        setupCurrentGroup(instance)
        return ProgramToggleResult.STARTED
      } else {
        return ProgramToggleResult.RESUMED
      }
    }

    // 動作の途中でクリックされた場合: 動作を一時停止！
    isWaitingTrigger = true
    return ProgramToggleResult.PAUSED
  }
}
