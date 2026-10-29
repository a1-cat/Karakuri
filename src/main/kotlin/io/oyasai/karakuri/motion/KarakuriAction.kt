package io.oyasai.karakuri.motion

import org.joml.Vector3f

/**
 * からくりプログラムの1ステップを表すアクション
 */
sealed class KarakuriAction {
  /**
   * 次のアクションと同時に並行して実行するかどうか（複合回転・スクリュー移動など）
   */
  abstract var isSimultaneous: Boolean

  /**
   * 真逆の動作（リバース）を返す
   */
  abstract fun reversed(): KarakuriAction

  /**
   * 指定軸での回転
   */
  data class Rotate(
    val axis: Axis = Axis.Y,
    val angleDeg: Float = 90f,
    val durationTicks: Int = 20,
    val pivot: Vector3f? = null,
    override var isSimultaneous: Boolean = false,
  ) : KarakuriAction() {
    override fun reversed(): KarakuriAction =
      Rotate(axis, -angleDeg, durationTicks, pivot, isSimultaneous)
  }

  /**
   * 直線移動
   */
  data class Move(
    val dx: Double = 0.0,
    val dy: Double = 3.0,
    val dz: Double = 0.0,
    val durationTicks: Int = 30,
    override var isSimultaneous: Boolean = false,
  ) : KarakuriAction() {
    override fun reversed(): KarakuriAction =
      Move(-dx, -dy, -dz, durationTicks, isSimultaneous)
  }

  /**
   * 一時待機
   */
  data class Wait(
    val waitTicks: Int = 20,
    override var isSimultaneous: Boolean = false,
  ) : KarakuriAction() {
    override fun reversed(): KarakuriAction =
      Wait(waitTicks, isSimultaneous)
  }

  /**
   * 連続回転（一定時間回ったら次へ）
   */
  data class Spin(
    val axis: Axis = Axis.Y,
    val degreesPerSecond: Float = 96f, // 16 RPM
    val durationTicks: Int = 60, // 指定ticks回ったら次へ
    val pivot: Vector3f? = null,
    override var isSimultaneous: Boolean = false,
  ) : KarakuriAction() {
    override fun reversed(): KarakuriAction =
      Spin(axis, -degreesPerSecond, durationTicks, pivot, isSimultaneous)
  }

  /**
   * 回転速度の滑らかな変化（加減速回転）
   * 開始RPMから終了RPMへ、指定時間をかけて滑らかに速度を推移させる
   */
  data class SpeedTransition(
    val axis: Axis = Axis.Y,
    val startRpm: Float = 32f,
    val endRpm: Float = 16f,
    val durationTicks: Int = 120, // 6秒 (120ticks)
    val isClockwise: Boolean = true,
    val pivot: Vector3f? = null,
    override var isSimultaneous: Boolean = false,
  ) : KarakuriAction() {
    override fun reversed(): KarakuriAction =
      SpeedTransition(
        axis = axis,
        startRpm = endRpm,
        endRpm = startRpm,
        durationTicks = durationTicks,
        isClockwise = !isClockwise,
        pivot = pivot,
        isSimultaneous = isSimultaneous,
      )
  }
}

/**
 * プログラム全体の再生モード
 */
enum class ProgramLoopMode(val displayName: String, val description: String) {
  AUTO_REVERSE("🔁 自動往復リバース", "最後まで進んだら真逆の動作で初期位置に戻り、往復を繰り返します"),
  TOGGLE_REVERSE("🕹️ 手動トグル往復", "クリックで往路を実行、もう一度クリックで真逆の動作で初期位置に戻ります"),
  LOOP("🔄 一方通行ループ", "ステップ1〜7を順方向に無限に繰り返します"),
  ONCE_THEN_RESET("⏹️ 1回実行（クリックでリセット）", "最後まで実行して停止。クリックで初期位置に戻り、再度クリックで再生します")
}
