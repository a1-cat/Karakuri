package io.oyasai.karakuri.engine

import org.bukkit.Location
import org.bukkit.Sound

/**
 * からくり全体の統一システム効果音
 * 手動操作時と、自動ループ・ステップ進行時で適切なシステム音を使い分ける。
 */
object KarakuriSounds {
  /** 手動操作時（プレイヤーのクリックによる作動・トグル） */
  val MANUAL_CLICK: Sound = Sound.UI_BUTTON_CLICK
  const val MANUAL_VOLUME: Float = 0.6f
  const val MANUAL_PITCH: Float = 1.2f

  fun playManual(loc: Location) {
    loc.world?.playSound(loc, MANUAL_CLICK, MANUAL_VOLUME, MANUAL_PITCH)
  }

  /** 自動ループ・自律動作時（ステップ切り替え・自動往復・自動ドアクローズ） */
  val AUTO_CYCLE: Sound = Sound.BLOCK_NOTE_BLOCK_HAT
  const val AUTO_VOLUME: Float = 0.35f
  const val AUTO_PITCH: Float = 1.5f

  fun playAuto(loc: Location) {
    loc.world?.playSound(loc, AUTO_CYCLE, AUTO_VOLUME, AUTO_PITCH)
  }

  // 下位互換用
  fun playMotion(loc: Location) {
    playAuto(loc)
  }
}
