package io.oyasai.karakuri

import io.oyasai.karakuri.command.KarakuriCommand
import io.oyasai.karakuri.engine.GlueManager
import io.oyasai.karakuri.engine.KarakuriManager
import io.oyasai.karakuri.listener.KarakuriListener
import io.oyasai.karakuri.tool.KarakuriGlue
import io.oyasai.karakuri.tool.KarakuriPistonRod
import io.oyasai.karakuri.tool.KarakuriToolbox
import io.oyasai.karakuri.tool.KarakuriWrench
import org.bukkit.plugin.java.JavaPlugin

class KarakuriPlugin : JavaPlugin() {

  companion object {
    lateinit var instance: KarakuriPlugin
      private set
  }

  lateinit var manager: KarakuriManager
    private set

  lateinit var glueManager: GlueManager
    private set

  lateinit var collisionEngine: io.oyasai.karakuri.collision.CollisionEngine
    private set

  lateinit var seatManager: io.oyasai.karakuri.engine.KarakuriSeatManager
    private set

  lateinit var redstoneLinkManager: io.oyasai.karakuri.engine.RedstoneLinkManager
    private set

  lateinit var wirelessRedstoneManager: io.oyasai.karakuri.engine.WirelessRedstoneManager
    private set

  lateinit var minecartCouplingManager: io.oyasai.karakuri.engine.MinecartCouplingManager
    private set

  lateinit var database: io.oyasai.karakuri.persistence.KarakuriDatabase
    private set

  override fun onEnable() {
    instance = this
    logger.info("==========================================")
    logger.info("  Karakuri (動く建築) プラグイン 起動中...")
    logger.info("  Display Entity Engine (STEP 3: 当たり判定・乗り物理) Loaded")
    logger.info("==========================================")

    manager = KarakuriManager()
    seatManager = io.oyasai.karakuri.engine.KarakuriSeatManager(manager)
    redstoneLinkManager = io.oyasai.karakuri.engine.RedstoneLinkManager(manager)
    wirelessRedstoneManager = io.oyasai.karakuri.engine.WirelessRedstoneManager(this)
    minecartCouplingManager = io.oyasai.karakuri.engine.MinecartCouplingManager(this)
    glueManager = GlueManager(this)
    glueManager.start()

    collisionEngine = io.oyasai.karakuri.collision.CollisionEngine(this)
    collisionEngine.start()

    // 永続化データベースの初期化と連携
    database = io.oyasai.karakuri.persistence.KarakuriDatabase(dataFolder, logger)
    manager.initDatabase(database, collisionEngine)

    // 旧残骸エンティティのクリーンアップ＆永続化データの自己修復・復元
    manager.cleanOldLeftoverEntities()
    manager.loadAndReconcileAll()

    // ワイヤレスRSノードの復元
    try {
      val savedNodes = database.loadAllWirelessNodes()
      for (node in savedNodes) {
        wirelessRedstoneManager.registerNode(node)
      }
      logger.info("[Karakuri] ワイヤレスRSノード (${savedNodes.size}件) を復元しました。")
    } catch (e: Exception) {
      logger.warning("[Karakuri] ワイヤレスRSノード復元失敗: ${e.message}")
    }

    // ツール初期化
    KarakuriWrench.init(this)
    KarakuriPistonRod.init(this)
    KarakuriGlue.init(this)
    KarakuriToolbox.init(this)
    io.oyasai.karakuri.tool.KarakuriRemote.init(this)

    // リスナー登録
    server.pluginManager.registerEvents(KarakuriListener(manager, glueManager), this)

    // コマンド登録
    val cmd = KarakuriCommand(this, manager, glueManager)
    val pluginCommand = getCommand("karakuri")
    if (pluginCommand != null) {
      pluginCommand.setExecutor(cmd)
      pluginCommand.tabCompleter = cmd
    } else {
      logger.warning("コマンド /karakuri の登録に失敗しました。plugin.ymlを確認してください。")
    }

    // 毎tickのモーション更新＆コリジョン追従＆座席同期タスク（同一Tick内で完全同期！）
    server.scheduler.runTaskTimer(
      this,
      Runnable {
        val currentTick = server.currentTick.toLong()
        manager.tick(currentTick)
        collisionEngine.tick()
        seatManager.tick(currentTick)
        redstoneLinkManager.tick(currentTick)
        wirelessRedstoneManager.tick()
        minecartCouplingManager.tick()
      },
      1L,
      1L
    )

    // 姿勢データの定期非同期フラッシュ (3秒 = 60ticks 間隔)
    server.scheduler.runTaskTimerAsynchronously(
      this,
      Runnable {
        database.flushDirtyPoses()
      },
      60L,
      60L
    )

    logger.info("Karakuri プラグインが正常に有効化されました。")
  }

  override fun onDisable() {
    logger.info("Karakuri プラグインを停止中... 残存からくりをクリーンアップします。")
    if (::seatManager.isInitialized) {
      seatManager.cleanupAll()
    }
    if (::collisionEngine.isInitialized) {
      collisionEngine.stop()
    }
    if (::glueManager.isInitialized) {
      glueManager.stop()
    }
    if (::minecartCouplingManager.isInitialized) {
      minecartCouplingManager.cleanupAll()
    }
    if (::manager.isInitialized) {
      for (instance in manager.getAll()) {
        try {
          if (::database.isInitialized) {
            database.markPoseDirty(instance)
          }
          manager.saveInstanceMotion(instance)
        } catch (_: Exception) {}
      }
      if (::database.isInitialized) {
        database.flushDirtyPosesSync()
      }
      manager.clearAll()
    }
    if (::database.isInitialized) {
      database.close()
    }
    logger.info("Karakuri プラグインを停止しました。")
  }
}
