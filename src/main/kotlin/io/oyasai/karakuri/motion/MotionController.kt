package io.oyasai.karakuri.motion

import io.oyasai.karakuri.engine.KarakuriInstance

/**
 * からくりの動作を制御するコントローラーのインターフェース
 */
interface MotionController {
  /**
   * モーションが開始されたときのコールバック
   */
  fun onStart(instance: KarakuriInstance) {}

  /**
   * 毎tick呼び出される更新処理
   * @param instance 対象のからくりインスタンス
   * @param currentTick サーバーの現在tick
   * @return モーションが継続中なら true、完了したなら false
   */
  fun tick(instance: KarakuriInstance, currentTick: Long): Boolean

  /**
   * モーションが停止・キャンセルされたときの処理
   */
  fun onStop(instance: KarakuriInstance) {}
}
