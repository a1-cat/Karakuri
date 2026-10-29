package io.oyasai.karakuri.persistence

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import io.oyasai.karakuri.engine.KarakuriInstance
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Karakuri 永続化システム（SQLite による耐久用正本管理）
 *
 * - 単一ワーカースレッドによる非同期トランザクション書き込み
 * - 姿勢更新の Dirty Tracking & Coalescing（まとめ書き）
 * - 起動時の整合性チェック・自己修復
 */
class KarakuriDatabase(
  val dataFolder: File,
  val logger: Logger,
) {

  private val dbFile = File(dataFolder, "karakuri.db")
  private val connectionUrl = "jdbc:sqlite:${dbFile.absolutePath}"

  // 単一ワーカーによる非同期トランザクション実行（順序保証・ロック競合ゼロ）
  private val worker = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "Karakuri-DB-Worker").apply { isDaemon = true }
  }

  // 姿勢変更があったインスタンスの Dirty Tracking (ID -> 最新スナップショット)
  private val dirtyPoseMap = ConcurrentHashMap<String, InstancePoseSnapshot>()

  init {
    if (!dataFolder.exists()) {
      dataFolder.mkdirs()
    }
    initDatabase()
  }

  private fun getConnection(): Connection {
    return DriverManager.getConnection(connectionUrl).apply {
      autoCommit = false
    }
  }

  private fun initDatabase() {
    try {
      Class.forName("org.sqlite.JDBC")
    } catch (_: ClassNotFoundException) {
      logger.info("[Karakuri-DB] org.sqlite.JDBC loaded via driver manager.")
    }

    try {
      DriverManager.getConnection(connectionUrl).use { conn ->
        conn.createStatement().use { stmt ->
          // PRAGMA 設定（パフォーマンスと耐久性の最適化）
          stmt.execute("PRAGMA journal_mode = WAL;")
          stmt.execute("PRAGMA synchronous = NORMAL;")
          stmt.execute("PRAGMA foreign_keys = ON;")

          // instances テーブル
          stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS instances (
                id TEXT PRIMARY KEY,
                world TEXT NOT NULL,
                origin_x REAL NOT NULL,
                origin_y REAL NOT NULL,
                origin_z REAL NOT NULL,
                pivot_x REAL NOT NULL,
                pivot_y REAL NOT NULL,
                pivot_z REAL NOT NULL,
                pivot_offset_x REAL NOT NULL,
                pivot_offset_y REAL NOT NULL,
                pivot_offset_z REAL NOT NULL,
                scale_x REAL NOT NULL,
                scale_y REAL NOT NULL,
                scale_z REAL NOT NULL,
                init_right_rot_x REAL NOT NULL,
                init_right_rot_y REAL NOT NULL,
                init_right_rot_z REAL NOT NULL,
                init_right_rot_w REAL NOT NULL,
                current_pos_x REAL NOT NULL,
                current_pos_y REAL NOT NULL,
                current_pos_z REAL NOT NULL,
                current_rot_x REAL NOT NULL,
                current_rot_y REAL NOT NULL,
                current_rot_z REAL NOT NULL,
                current_rot_w REAL NOT NULL,
                is_optimized INTEGER NOT NULL DEFAULT 0,
                optimize_non_full INTEGER NOT NULL DEFAULT 0,
                is_immovable INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'converting',
                motion_type TEXT,
                motion_data TEXT,
                updated_at INTEGER NOT NULL
            );
            """.trimIndent()
          )

          // 既存データベースへのマイグレーション（カラム追加）
          try {
            stmt.execute("ALTER TABLE instances ADD COLUMN motion_type TEXT;")
          } catch (_: Exception) {}
          try {
            stmt.execute("ALTER TABLE instances ADD COLUMN motion_data TEXT;")
          } catch (_: Exception) {}

          // blocks テーブル
          stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS blocks (
                instance_id TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                block_data TEXT NOT NULL,
                skull_uuid TEXT,
                skull_name TEXT,
                skull_texture_value TEXT,
                skull_texture_signature TEXT,
                revert_status TEXT NOT NULL DEFAULT 'pending',
                PRIMARY KEY (instance_id, x, y, z),
                FOREIGN KEY (instance_id) REFERENCES instances (id) ON DELETE CASCADE
            );
            """.trimIndent()
          )

          // instance_redstone_links テーブル
          stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS instance_redstone_links (
                instance_id TEXT NOT NULL,
                type TEXT NOT NULL,
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                is_lit INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (instance_id, world, x, y, z),
                FOREIGN KEY (instance_id) REFERENCES instances (id) ON DELETE CASCADE
            );
            """.trimIndent()
          )

          try {
            stmt.execute("ALTER TABLE instance_redstone_links ADD COLUMN is_lit INTEGER NOT NULL DEFAULT 0")
          } catch (_: Exception) {}

          // wireless_nodes テーブル (ワイヤレス送信機・受信機)
          stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS wireless_nodes (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                world TEXT NOT NULL,
                rod_x INTEGER NOT NULL,
                rod_y INTEGER NOT NULL,
                rod_z INTEGER NOT NULL,
                note_x INTEGER NOT NULL,
                note_y INTEGER NOT NULL,
                note_z INTEGER NOT NULL,
                base_x INTEGER NOT NULL,
                base_y INTEGER NOT NULL,
                base_z INTEGER NOT NULL,
                instrument TEXT NOT NULL,
                note INTEGER NOT NULL
            );
            """.trimIndent()
          )

          // remote_storage テーブル (レッドストーンリモコン専用収納)
          stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS remote_storage (
                player_uuid TEXT PRIMARY KEY,
                items_blob BLOB NOT NULL,
                updated_at INTEGER NOT NULL
            );
            """.trimIndent()
          )
        }
      }
      logger.info("[Karakuri-DB] SQLite データベースが正常に初期化されました: ${dbFile.name}")
    } catch (e: Exception) {
      logger.severe("[Karakuri-DB] データベースの初期化に失敗しました: ${e.message}")
      e.printStackTrace()
    }
  }

  /**
   * DB内の最大ID番号（kk-NのN）を取得し、起動後のID衝突を防止
   */
  fun getMaxInstanceIdNumber(): Int {
    var maxId = 0
    try {
      DriverManager.getConnection(connectionUrl).use { conn ->
        conn.createStatement().use { stmt ->
          val rs = stmt.executeQuery("SELECT id FROM instances")
          while (rs.next()) {
            val idStr = rs.getString("id")
            if (idStr.startsWith("kk-")) {
              val num = idStr.removePrefix("kk-").toIntOrNull()
              if (num != null && num > maxId) {
                maxId = num
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      logger.warning("[Karakuri-DB] 最大IDの取得中にエラーが発生しました: ${e.message}")
    }
    return maxId
  }

  // =========================================================================
  // データモデル
  // =========================================================================

  data class StoredInstanceRecord(
    val id: String,
    val worldName: String,
    val origin: Location,
    val worldPivot: Location,
    val pivotOffset: Vector3f,
    val scale: Vector3f,
    val initialRightRotation: Quaternionf,
    val currentPos: Location,
    val currentRotation: Quaternionf,
    val isOptimized: Boolean,
    val optimizeNonFull: Boolean,
    val isImmovable: Boolean,
    val state: String, // converting, active, reverting
    val motionType: String? = null,
    val motionData: String? = null,
    val blocks: Map<Location, BlockData>,
    val skullProfiles: Map<Location, PlayerProfile>,
  )

  data class InstancePoseSnapshot(
    val id: String,
    val currentPos: Location,
    val currentRotation: Quaternionf,
    val updatedAt: Long = System.currentTimeMillis(),
  )

  // =========================================================================
  // 非同期変換保存 (converting -> DB確定 -> コールバック)
  // =========================================================================

  /**
   * からくり化の初回登録（主スレッドでブロックをAIRにする前に確定する）
   */
  fun saveNewInstanceConvertingRaw(
    id: String,
    worldName: String,
    origin: Location,
    worldPivot: Location,
    pivotOffset: Vector3f,
    scale: Vector3f,
    initialRightRotation: Quaternionf,
    currentPos: Location,
    currentRotation: Quaternionf,
    isOptimized: Boolean,
    optimizeNonFull: Boolean,
    isImmovable: Boolean,
    blocksSnapshot: Map<Location, BlockData>,
    skullsSnapshot: Map<Location, PlayerProfile>,
    motionType: String? = null,
    motionData: String? = null,
    onComplete: (Boolean) -> Unit,
  ) {
    worker.submit {
      var success = false
      try {
        getConnection().use { conn ->
          try {
            val instSql = """
              INSERT OR REPLACE INTO instances (
                id, world, origin_x, origin_y, origin_z,
                pivot_x, pivot_y, pivot_z,
                pivot_offset_x, pivot_offset_y, pivot_offset_z,
                scale_x, scale_y, scale_z,
                init_right_rot_x, init_right_rot_y, init_right_rot_z, init_right_rot_w,
                current_pos_x, current_pos_y, current_pos_z,
                current_rot_x, current_rot_y, current_rot_z, current_rot_w,
                is_optimized, optimize_non_full, is_immovable,
                state, motion_type, motion_data, updated_at
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            conn.prepareStatement(instSql).use { stmt ->
              stmt.setString(1, id)
              stmt.setString(2, worldName)
              stmt.setDouble(3, origin.x)
              stmt.setDouble(4, origin.y)
              stmt.setDouble(5, origin.z)
              stmt.setDouble(6, worldPivot.x)
              stmt.setDouble(7, worldPivot.y)
              stmt.setDouble(8, worldPivot.z)
              stmt.setFloat(9, pivotOffset.x)
              stmt.setFloat(10, pivotOffset.y)
              stmt.setFloat(11, pivotOffset.z)
              stmt.setFloat(12, scale.x)
              stmt.setFloat(13, scale.y)
              stmt.setFloat(14, scale.z)
              stmt.setFloat(15, initialRightRotation.x)
              stmt.setFloat(16, initialRightRotation.y)
              stmt.setFloat(17, initialRightRotation.z)
              stmt.setFloat(18, initialRightRotation.w)
              stmt.setDouble(19, currentPos.x)
              stmt.setDouble(20, currentPos.y)
              stmt.setDouble(21, currentPos.z)
              stmt.setFloat(22, currentRotation.x)
              stmt.setFloat(23, currentRotation.y)
              stmt.setFloat(24, currentRotation.z)
              stmt.setFloat(25, currentRotation.w)
              stmt.setInt(26, if (isOptimized) 1 else 0)
              stmt.setInt(27, if (optimizeNonFull) 1 else 0)
              stmt.setInt(28, if (isImmovable) 1 else 0)
              stmt.setString(29, "converting")
              stmt.setString(30, motionType)
              stmt.setString(31, motionData)
              stmt.setLong(32, System.currentTimeMillis())
              stmt.executeUpdate()
            }

            val blockSql = """
              INSERT OR REPLACE INTO blocks (
                instance_id, x, y, z, block_data,
                skull_uuid, skull_name, skull_texture_value, skull_texture_signature,
                revert_status
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')
            """.trimIndent()

            conn.prepareStatement(blockSql).use { bStmt ->
              for ((loc, bData) in blocksSnapshot) {
                bStmt.setString(1, id)
                bStmt.setInt(2, loc.blockX)
                bStmt.setInt(3, loc.blockY)
                bStmt.setInt(4, loc.blockZ)
                bStmt.setString(5, bData.asString)

                val profile = skullsSnapshot[loc]
                if (profile != null) {
                  bStmt.setString(6, profile.id?.toString())
                  bStmt.setString(7, profile.name)
                  val texturesProp = profile.properties.firstOrNull { it.name == "textures" }
                  bStmt.setString(8, texturesProp?.value)
                  bStmt.setString(9, texturesProp?.signature)
                } else {
                  bStmt.setString(6, null)
                  bStmt.setString(7, null)
                  bStmt.setString(8, null)
                  bStmt.setString(9, null)
                }
                bStmt.addBatch()
              }
              bStmt.executeBatch()
            }

            conn.commit()
            success = true
          } catch (e: Exception) {
            conn.rollback()
            logger.severe("[Karakuri-DB] saveNewInstanceConvertingRaw ロールバック: ${e.message}")
            e.printStackTrace()
          }
        }
      } catch (e: Exception) {
        logger.severe("[Karakuri-DB] DB接続エラー: ${e.message}")
      }

      Bukkit.getScheduler().runTask(io.oyasai.karakuri.KarakuriPlugin.instance, Runnable {
        onComplete(success)
      })
    }
  }

  /**
   * からくり化の初回登録（インスタンス渡しバージョン）
   */
  fun saveNewInstanceConverting(
    instance: KarakuriInstance,
    blocksSnapshot: Map<Location, BlockData>,
    skullsSnapshot: Map<Location, PlayerProfile>,
    optimizeNonFull: Boolean,
    motionType: String? = null,
    motionData: String? = null,
    onComplete: (Boolean) -> Unit,
  ) {
    val curPos = instance.displayEntity.location.clone()
    val curRot = Quaternionf(instance.currentRotation)

    worker.submit {
      var success = false
      try {
        getConnection().use { conn ->
          try {
            // 1. instances レコード挿入
            val instSql = """
              INSERT OR REPLACE INTO instances (
                id, world, origin_x, origin_y, origin_z,
                pivot_x, pivot_y, pivot_z,
                pivot_offset_x, pivot_offset_y, pivot_offset_z,
                scale_x, scale_y, scale_z,
                init_right_rot_x, init_right_rot_y, init_right_rot_z, init_right_rot_w,
                current_pos_x, current_pos_y, current_pos_z,
                current_rot_x, current_rot_y, current_rot_z, current_rot_w,
                is_optimized, optimize_non_full, is_immovable,
                state, motion_type, motion_data, updated_at
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            conn.prepareStatement(instSql).use { stmt ->
              stmt.setString(1, instance.id)
              stmt.setString(2, instance.originLocation.world.name)
              stmt.setDouble(3, instance.originLocation.x)
              stmt.setDouble(4, instance.originLocation.y)
              stmt.setDouble(5, instance.originLocation.z)
              stmt.setDouble(6, instance.worldPivotLocation.x)
              stmt.setDouble(7, instance.worldPivotLocation.y)
              stmt.setDouble(8, instance.worldPivotLocation.z)
              stmt.setFloat(9, instance.pivotOffset.x)
              stmt.setFloat(10, instance.pivotOffset.y)
              stmt.setFloat(11, instance.pivotOffset.z)
              stmt.setFloat(12, instance.scale.x)
              stmt.setFloat(13, instance.scale.y)
              stmt.setFloat(14, instance.scale.z)
              stmt.setFloat(15, instance.initialRightRotation.x)
              stmt.setFloat(16, instance.initialRightRotation.y)
              stmt.setFloat(17, instance.initialRightRotation.z)
              stmt.setFloat(18, instance.initialRightRotation.w)
              stmt.setDouble(19, curPos.x)
              stmt.setDouble(20, curPos.y)
              stmt.setDouble(21, curPos.z)
              stmt.setFloat(22, curRot.x)
              stmt.setFloat(23, curRot.y)
              stmt.setFloat(24, curRot.z)
              stmt.setFloat(25, curRot.w)
              stmt.setInt(26, if (instance.isOptimized) 1 else 0)
              stmt.setInt(27, if (optimizeNonFull) 1 else 0)
              stmt.setInt(28, if (instance.isImmovable) 1 else 0)
              stmt.setString(29, "converting")
              stmt.setString(30, motionType)
              stmt.setString(31, motionData)
              stmt.setLong(32, System.currentTimeMillis())
              stmt.executeUpdate()
            }

            // 2. blocks レコード挿入
            val blockSql = """
              INSERT OR REPLACE INTO blocks (
                instance_id, x, y, z, block_data,
                skull_uuid, skull_name, skull_texture_value, skull_texture_signature,
                revert_status
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')
            """.trimIndent()

            conn.prepareStatement(blockSql).use { bStmt ->
              for ((loc, bData) in blocksSnapshot) {
                bStmt.setString(1, instance.id)
                bStmt.setInt(2, loc.blockX)
                bStmt.setInt(3, loc.blockY)
                bStmt.setInt(4, loc.blockZ)
                bStmt.setString(5, bData.asString)

                val profile = skullsSnapshot[loc]
                if (profile != null) {
                  bStmt.setString(6, profile.id?.toString())
                  bStmt.setString(7, profile.name)
                  val texturesProp = profile.properties.firstOrNull { it.name == "textures" }
                  bStmt.setString(8, texturesProp?.value)
                  bStmt.setString(9, texturesProp?.signature)
                } else {
                  bStmt.setString(6, null)
                  bStmt.setString(7, null)
                  bStmt.setString(8, null)
                  bStmt.setString(9, null)
                }
                bStmt.addBatch()
              }
              bStmt.executeBatch()
            }

            conn.commit()
            success = true
          } catch (e: Exception) {
            conn.rollback()
            logger.severe("[Karakuri-DB] saveNewInstanceConverting ロールバック: ${e.message}")
            e.printStackTrace()
          }
        }
      } catch (e: Exception) {
        logger.severe("[Karakuri-DB] DB接続エラー: ${e.message}")
      }

      Bukkit.getScheduler().runTask(io.oyasai.karakuri.KarakuriPlugin.instance, Runnable {
        onComplete(success)
      })
    }
  }

  /**
   * からくり化完了時に state を active に更新
   */
  fun markInstanceActive(instanceId: String) {
    worker.submit {
      try {
        getConnection().use { conn ->
          conn.prepareStatement("UPDATE instances SET state = 'active', updated_at = ? WHERE id = ?").use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setString(2, instanceId)
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] markInstanceActive 失敗: ${e.message}")
      }
    }
  }

  /**
   * からくりの動作モード・パラメータを非同期保存
   */
  fun updateInstanceMotion(instanceId: String, motionType: String?, motionData: String?) {
    worker.submit {
      try {
        getConnection().use { conn ->
          conn.prepareStatement("UPDATE instances SET motion_type = ?, motion_data = ?, updated_at = ? WHERE id = ?").use { stmt ->
            stmt.setString(1, motionType)
            stmt.setString(2, motionData)
            stmt.setLong(3, System.currentTimeMillis())
            stmt.setString(4, instanceId)
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] updateInstanceMotion 失敗 [$instanceId]: ${e.message}")
      }
    }
  }

  /**
   * からくりの幾何情報（原点、ピボット、構成ブロック座標）をDBに同期更新する
   * （子構造のワープ配置時や位置変更時に使用）
   */
  fun updateInstanceGeometry(instance: KarakuriInstance) {
    val id = instance.id
    val origin = instance.originLocation.clone()
    val pivot = instance.worldPivotLocation.clone()
    val offset = Vector3f(instance.pivotOffset)
    val curPos = instance.displayEntity.location.clone()
    val curRot = Quaternionf(instance.currentRotation)
    val scale = Vector3f(instance.scale)
    val isOpt = instance.isOptimized
    val optNonFull = instance.optimizeNonFullBlocks
    val isImm = instance.isImmovable
    val blocksSnapshot = HashMap(instance.constituentBlocks)
    val skullsSnapshot = HashMap(instance.constituentSkullProfiles)

    // dirtyPoseMap にも最新スナップショットを反映（フラッシュ競合での巻き戻りを防止）
    dirtyPoseMap[id] = InstancePoseSnapshot(
      id = id,
      currentPos = curPos,
      currentRotation = curRot
    )

    worker.submit {
      try {
        getConnection().use { conn ->
          try {
            val instSql = """
              UPDATE instances SET
                origin_x = ?, origin_y = ?, origin_z = ?,
                pivot_x = ?, pivot_y = ?, pivot_z = ?,
                pivot_offset_x = ?, pivot_offset_y = ?, pivot_offset_z = ?,
                scale_x = ?, scale_y = ?, scale_z = ?,
                current_pos_x = ?, current_pos_y = ?, current_pos_z = ?,
                current_rot_x = ?, current_rot_y = ?, current_rot_z = ?, current_rot_w = ?,
                is_optimized = ?, optimize_non_full = ?, is_immovable = ?,
                updated_at = ?
              WHERE id = ?
            """.trimIndent()
            conn.prepareStatement(instSql).use { stmt ->
              stmt.setDouble(1, origin.x)
              stmt.setDouble(2, origin.y)
              stmt.setDouble(3, origin.z)
              stmt.setDouble(4, pivot.x)
              stmt.setDouble(5, pivot.y)
              stmt.setDouble(6, pivot.z)
              stmt.setFloat(7, offset.x)
              stmt.setFloat(8, offset.y)
              stmt.setFloat(9, offset.z)
              stmt.setFloat(10, scale.x)
              stmt.setFloat(11, scale.y)
              stmt.setFloat(12, scale.z)
              stmt.setDouble(13, curPos.x)
              stmt.setDouble(14, curPos.y)
              stmt.setDouble(15, curPos.z)
              stmt.setFloat(16, curRot.x)
              stmt.setFloat(17, curRot.y)
              stmt.setFloat(18, curRot.z)
              stmt.setFloat(19, curRot.w)
              stmt.setInt(20, if (isOpt) 1 else 0)
              stmt.setInt(21, if (optNonFull) 1 else 0)
              stmt.setInt(22, if (isImm) 1 else 0)
              stmt.setLong(23, System.currentTimeMillis())
              stmt.setString(24, id)
              stmt.executeUpdate()
            }

            if (blocksSnapshot.isNotEmpty()) {
              conn.prepareStatement("DELETE FROM blocks WHERE instance_id = ?").use { dStmt ->
                dStmt.setString(1, id)
                dStmt.executeUpdate()
              }

              val bSql = """
                INSERT OR REPLACE INTO blocks (
                  instance_id, x, y, z, block_data,
                  skull_uuid, skull_name, skull_texture_value, skull_texture_signature,
                  revert_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')
              """.trimIndent()
              conn.prepareStatement(bSql).use { bStmt ->
                for ((loc, bData) in blocksSnapshot) {
                  bStmt.setString(1, id)
                  bStmt.setInt(2, loc.blockX)
                  bStmt.setInt(3, loc.blockY)
                  bStmt.setInt(4, loc.blockZ)
                  bStmt.setString(5, bData.asString)

                  val profile = skullsSnapshot[loc]
                  if (profile != null) {
                    bStmt.setString(6, profile.id?.toString())
                    bStmt.setString(7, profile.name)
                    val texturesProp = profile.properties.firstOrNull { it.name == "textures" }
                    bStmt.setString(8, texturesProp?.value)
                    bStmt.setString(9, texturesProp?.signature)
                  } else {
                    bStmt.setString(6, null)
                    bStmt.setString(7, null)
                    bStmt.setString(8, null)
                    bStmt.setString(9, null)
                  }
                  bStmt.addBatch()
                }
                bStmt.executeBatch()
              }
            }

            conn.commit()
          } catch (e: Exception) {
            conn.rollback()
            logger.severe("[Karakuri-DB] updateInstanceGeometry ロールバック [$id]: ${e.message}")
            e.printStackTrace()
          }
        }
      } catch (e: Exception) {
        logger.severe("[Karakuri-DB] updateInstanceGeometry DB接続エラー: ${e.message}")
      }
    }
  }

  /**
   * 変換中断時に DB から削除
   */
  fun deleteInstance(instanceId: String) {
    dirtyPoseMap.remove(instanceId)
    worker.submit {
      try {
        getConnection().use { conn ->
          conn.prepareStatement("DELETE FROM blocks WHERE instance_id = ?").use { stmt ->
            stmt.setString(1, instanceId)
            stmt.executeUpdate()
          }
          conn.prepareStatement("DELETE FROM instances WHERE id = ?").use { stmt ->
            stmt.setString(1, instanceId)
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] deleteInstance 失敗: ${e.message}")
      }
    }
  }

  // =========================================================================
  // 姿勢の Dirty Tracking & Coalescing 保存
  // =========================================================================

  /**
   * 姿勢の変更をマーク（数秒ごとにワーカーへフラッシュされる）
   */
  fun markPoseDirty(instance: KarakuriInstance) {
    dirtyPoseMap[instance.id] = InstancePoseSnapshot(
      id = instance.id,
      currentPos = instance.displayEntity.location.clone(),
      currentRotation = Quaternionf(instance.currentRotation)
    )
  }

  /**
   * 設定フラグ（isOptimized, isImmovable等）の即時更新
   */
  fun updateInstanceFlags(instance: KarakuriInstance) {
    val id = instance.id
    val isOpt = instance.isOptimized
    val optNonFull = instance.optimizeNonFullBlocks
    val isImm = instance.isImmovable
    worker.submit {
      try {
        getConnection().use { conn ->
          val sql = "UPDATE instances SET is_optimized = ?, optimize_non_full = ?, is_immovable = ?, updated_at = ? WHERE id = ?"
          conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, if (isOpt) 1 else 0)
            stmt.setInt(2, if (optNonFull) 1 else 0)
            stmt.setInt(3, if (isImm) 1 else 0)
            stmt.setLong(4, System.currentTimeMillis())
            stmt.setString(5, id)
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] updateInstanceFlags 失敗: ${e.message}")
      }
    }
  }

  /**
   * 周期実行される姿勢保存フラッシュ（数秒ごと）
   */
  fun flushDirtyPoses() {
    if (dirtyPoseMap.isEmpty()) return

    // 現在溜まっている dirty マップを退避してクリア (Coalescing)
    val toSave = ArrayList<InstancePoseSnapshot>()
    for (entry in dirtyPoseMap) {
      if (dirtyPoseMap.remove(entry.key, entry.value)) {
        toSave.add(entry.value)
      }
    }
    if (toSave.isEmpty()) return

    worker.submit {
      try {
        getConnection().use { conn ->
          val sql = """
            UPDATE instances SET
              current_pos_x = ?, current_pos_y = ?, current_pos_z = ?,
              current_rot_x = ?, current_rot_y = ?, current_rot_z = ?, current_rot_w = ?,
              updated_at = ?
            WHERE id = ?
          """.trimIndent()
          conn.prepareStatement(sql).use { stmt ->
            for (snap in toSave) {
              stmt.setDouble(1, snap.currentPos.x)
              stmt.setDouble(2, snap.currentPos.y)
              stmt.setDouble(3, snap.currentPos.z)
              stmt.setFloat(4, snap.currentRotation.x)
              stmt.setFloat(5, snap.currentRotation.y)
              stmt.setFloat(6, snap.currentRotation.z)
              stmt.setFloat(7, snap.currentRotation.w)
              stmt.setLong(8, snap.updatedAt)
              stmt.setString(9, snap.id)
              stmt.addBatch()
            }
            stmt.executeBatch()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] flushDirtyPoses 失敗: ${e.message}")
      }
    }
  }

  /**
   * サーバー停止時に確実に同期書き込みを行う姿勢フラッシュ
   */
  fun flushDirtyPosesSync() {
    if (dirtyPoseMap.isEmpty()) return

    val toSave = ArrayList<InstancePoseSnapshot>()
    for (entry in dirtyPoseMap) {
      if (dirtyPoseMap.remove(entry.key, entry.value)) {
        toSave.add(entry.value)
      }
    }
    if (toSave.isEmpty()) return

    try {
      getConnection().use { conn ->
        val sql = """
          UPDATE instances SET
            current_pos_x = ?, current_pos_y = ?, current_pos_z = ?,
            current_rot_x = ?, current_rot_y = ?, current_rot_z = ?, current_rot_w = ?,
            updated_at = ?
          WHERE id = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
          for (snap in toSave) {
            stmt.setDouble(1, snap.currentPos.x)
            stmt.setDouble(2, snap.currentPos.y)
            stmt.setDouble(3, snap.currentPos.z)
            stmt.setFloat(4, snap.currentRotation.x)
            stmt.setFloat(5, snap.currentRotation.y)
            stmt.setFloat(6, snap.currentRotation.z)
            stmt.setFloat(7, snap.currentRotation.w)
            stmt.setLong(8, snap.updatedAt)
            stmt.setString(9, snap.id)
            stmt.addBatch()
          }
          stmt.executeBatch()
        }
        conn.commit()
      }
    } catch (e: Exception) {
      logger.warning("[Karakuri-DB] flushDirtyPosesSync 失敗: ${e.message}")
    }
  }

  // =========================================================================
  // revert (復元) ライフサイクル
  // =========================================================================

  fun markInstanceReverting(instanceId: String) {
    worker.submit {
      try {
        getConnection().use { conn ->
          conn.prepareStatement("UPDATE instances SET state = 'reverting', updated_at = ? WHERE id = ?").use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setString(2, instanceId)
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] markInstanceReverting 失敗: ${e.message}")
      }
    }
  }

  fun updateBlockRevertStatus(instanceId: String, x: Int, y: Int, z: Int, status: String) {
    worker.submit {
      try {
        getConnection().use { conn ->
          val sql = "UPDATE blocks SET revert_status = ? WHERE instance_id = ? AND x = ? AND y = ? AND z = ?"
          conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, status)
            stmt.setString(2, instanceId)
            stmt.setInt(3, x)
            stmt.setInt(4, y)
            stmt.setInt(5, z)
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] updateBlockRevertStatus 失敗: ${e.message}")
      }
    }
  }

  // =========================================================================
  // 起動時の全件読み出し & 再照合
  // =========================================================================

  /**
   * サーバー起動時に全からくりレコードをDBから読み込む
   */
  fun loadAllInstances(): List<StoredInstanceRecord> {
    val results = mutableListOf<StoredInstanceRecord>()

    try {
      DriverManager.getConnection(connectionUrl).use { conn ->
        val instSql = "SELECT * FROM instances"
        val blockSql = "SELECT * FROM blocks WHERE instance_id = ?"

        conn.prepareStatement(instSql).use { instStmt ->
          val instRs = instStmt.executeQuery()
          while (instRs.next()) {
            val id = instRs.getString("id")
            val worldName = instRs.getString("world")
            val world = Bukkit.getWorld(worldName) ?: continue

            val origin = Location(
              world,
              instRs.getDouble("origin_x"),
              instRs.getDouble("origin_y"),
              instRs.getDouble("origin_z")
            )
            val worldPivot = Location(
              world,
              instRs.getDouble("pivot_x"),
              instRs.getDouble("pivot_y"),
              instRs.getDouble("pivot_z")
            )
            val pivotOffset = Vector3f(
              instRs.getFloat("pivot_offset_x"),
              instRs.getFloat("pivot_offset_y"),
              instRs.getFloat("pivot_offset_z")
            )
            val scale = Vector3f(
              instRs.getFloat("scale_x"),
              instRs.getFloat("scale_y"),
              instRs.getFloat("scale_z")
            )
            val initRightRot = Quaternionf(
              instRs.getFloat("init_right_rot_x"),
              instRs.getFloat("init_right_rot_y"),
              instRs.getFloat("init_right_rot_z"),
              instRs.getFloat("init_right_rot_w")
            )
            val currentPos = Location(
              world,
              instRs.getDouble("current_pos_x"),
              instRs.getDouble("current_pos_y"),
              instRs.getDouble("current_pos_z")
            )
            val currentRot = Quaternionf(
              instRs.getFloat("current_rot_x"),
              instRs.getFloat("current_rot_y"),
              instRs.getFloat("current_rot_z"),
              instRs.getFloat("current_rot_w")
            )
            val isOpt = instRs.getInt("is_optimized") == 1
            val optNonFull = instRs.getInt("optimize_non_full") == 1
            val isImm = instRs.getInt("is_immovable") == 1
            val state = instRs.getString("state")
            val motionType = instRs.getString("motion_type")
            val motionData = instRs.getString("motion_data")

            // ブロック一覧の読み込み
            val blocks = mutableMapOf<Location, BlockData>()
            val skulls = mutableMapOf<Location, PlayerProfile>()

            conn.prepareStatement(blockSql).use { bStmt ->
              bStmt.setString(1, id)
              val bRs = bStmt.executeQuery()
              while (bRs.next()) {
                val bx = bRs.getInt("x")
                val by = bRs.getInt("y")
                val bz = bRs.getInt("z")
                val bDataStr = bRs.getString("block_data")
                val bLoc = Location(world, bx.toDouble(), by.toDouble(), bz.toDouble())

                try {
                  val bData = Bukkit.createBlockData(bDataStr)
                  blocks[bLoc] = bData

                  val skullUuidStr = bRs.getString("skull_uuid")
                  val skullName = bRs.getString("skull_name")
                  val skullTex = bRs.getString("skull_texture_value")
                  val skullSig = bRs.getString("skull_texture_signature")

                  if (skullUuidStr != null || skullName != null || skullTex != null) {
                    val uid = if (skullUuidStr != null) UUID.fromString(skullUuidStr) else UUID.randomUUID()
                    val profile = Bukkit.createProfile(uid, skullName)
                    if (skullTex != null) {
                      profile.setProperty(ProfileProperty("textures", skullTex, skullSig))
                    }
                    skulls[bLoc] = profile
                  }
                } catch (e: Exception) {
                  logger.warning("[Karakuri-DB] ブロックデータ復元失敗 ($bDataStr): ${e.message}")
                }
              }
            }

            results.add(
              StoredInstanceRecord(
                id = id,
                worldName = worldName,
                origin = origin,
                worldPivot = worldPivot,
                pivotOffset = pivotOffset,
                scale = scale,
                initialRightRotation = initRightRot,
                currentPos = currentPos,
                currentRotation = currentRot,
                isOptimized = isOpt,
                optimizeNonFull = optNonFull,
                isImmovable = isImm,
                state = state,
                motionType = motionType,
                motionData = motionData,
                blocks = blocks,
                skullProfiles = skulls
              )
            )
          }
        }
      }
    } catch (e: Exception) {
      logger.severe("[Karakuri-DB] loadAllInstances 失敗: ${e.message}")
      e.printStackTrace()
    }

    return results
  }

  fun loadInstance(id: String): StoredInstanceRecord? {
    return loadAllInstances().firstOrNull { it.id == id }
  }

  fun saveRedstoneLinks(instance: KarakuriInstance) {
    val instanceId = instance.id
    val linksSnapshot = instance.redstoneLinks.map {
      it.copy(blockLocation = it.blockLocation.clone())
    }
    worker.submit {
      try {
        getConnection().use { conn ->
          conn.prepareStatement("DELETE FROM instance_redstone_links WHERE instance_id = ?").use { delStmt ->
            delStmt.setString(1, instanceId)
            delStmt.executeUpdate()
          }

          if (linksSnapshot.isNotEmpty()) {
            conn.prepareStatement(
              "INSERT OR REPLACE INTO instance_redstone_links (instance_id, type, world, x, y, z, is_lit) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { insStmt ->
              for (link in linksSnapshot) {
                val loc = link.blockLocation
                val w = loc.world?.name ?: continue
                insStmt.setString(1, instanceId)
                insStmt.setString(2, link.type.name)
                insStmt.setString(3, w)
                insStmt.setInt(4, loc.blockX)
                insStmt.setInt(5, loc.blockY)
                insStmt.setInt(6, loc.blockZ)
                insStmt.setInt(7, if (link.lastLitState) 1 else 0)
                insStmt.addBatch()
              }
              insStmt.executeBatch()
            }
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] saveRedstoneLinks 失敗 ($instanceId): ${e.message}")
      }
    }
  }

  fun loadRedstoneLinks(instance: KarakuriInstance) {
    try {
      DriverManager.getConnection(connectionUrl).use { conn ->
        conn.prepareStatement("SELECT type, world, x, y, z, is_lit FROM instance_redstone_links WHERE instance_id = ?").use { stmt ->
          stmt.setString(1, instance.id)
          val rs = stmt.executeQuery()
          val loaded = mutableListOf<io.oyasai.karakuri.engine.RedstoneLinkManager.RedstoneLink>()
          while (rs.next()) {
            val typeStr = rs.getString("type")
            val worldName = rs.getString("world")
            val x = rs.getInt("x")
            val y = rs.getInt("y")
            val z = rs.getInt("z")
            val savedIsLit = try { rs.getInt("is_lit") == 1 } catch (_: Exception) { false }

            val world = Bukkit.getWorld(worldName) ?: continue
            val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

            val isChunkLoaded = world.isChunkLoaded(x shr 4, z shr 4)
            val block = if (isChunkLoaded) loc.block else null

            val type = try {
              when (typeStr) {
                "COPPER_BULB" -> {
                  if (block != null && io.oyasai.karakuri.engine.RedstoneLinkManager.isReverseBulb(block.type)) {
                    io.oyasai.karakuri.engine.RedstoneLinkManager.RedstoneLinkType.COPPER_BULB_REVERSE
                  } else {
                    io.oyasai.karakuri.engine.RedstoneLinkManager.RedstoneLinkType.COPPER_BULB_ROTATION
                  }
                }
                else -> io.oyasai.karakuri.engine.RedstoneLinkManager.RedstoneLinkType.valueOf(typeStr)
              }
            } catch (_: Exception) {
              null
            } ?: continue

            val isLit = if (block != null) {
              (block.blockData as? org.bukkit.block.data.Lightable)?.isLit ?: savedIsLit
            } else {
              savedIsLit
            }

            val link = io.oyasai.karakuri.engine.RedstoneLinkManager.RedstoneLink(
              type = type,
              blockLocation = loc,
              lastLitState = isLit,
              lastObserverPulseTick = 0L
            )
            loaded.add(link)

            if (type == io.oyasai.karakuri.engine.RedstoneLinkManager.RedstoneLinkType.OBSERVER) {
              io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager.registerObserverPos(worldName, x, y, z)
            }
          }
          instance.redstoneLinks.clear()
          instance.redstoneLinks.addAll(loaded)

          // ロード直後に点灯・反転状態をからくりへ即時適用
          for (link in loaded) {
            if (io.oyasai.karakuri.engine.RedstoneLinkManager.isCopperBulbType(link.type)) {
              io.oyasai.karakuri.KarakuriPlugin.instance.redstoneLinkManager.applyBulbState(instance, link, link.lastLitState)
            }
          }
        }
      }
    } catch (e: Exception) {
      logger.warning("[Karakuri-DB] loadRedstoneLinks 失敗 (${instance.id}): ${e.message}")
    }
  }

  // =========================================================================
  // ワイヤレスRSノード (送信機 / 受信機)
  // =========================================================================

  fun saveWirelessNode(node: io.oyasai.karakuri.engine.WirelessNode) {
    worker.submit {
      try {
        getConnection().use { conn ->
          val sql = """
            INSERT OR REPLACE INTO wireless_nodes
            (id, type, world, rod_x, rod_y, rod_z, note_x, note_y, note_z, base_x, base_y, base_z, instrument, note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
          """.trimIndent()
          conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, node.id)
            stmt.setString(2, node.type.name)
            stmt.setString(3, node.rodLocation.world?.name ?: "world")
            stmt.setInt(4, node.rodLocation.blockX)
            stmt.setInt(5, node.rodLocation.blockY)
            stmt.setInt(6, node.rodLocation.blockZ)
            stmt.setInt(7, node.noteBlockLocation.blockX)
            stmt.setInt(8, node.noteBlockLocation.blockY)
            stmt.setInt(9, node.noteBlockLocation.blockZ)
            stmt.setInt(10, node.baseBlockLocation.blockX)
            stmt.setInt(11, node.baseBlockLocation.blockY)
            stmt.setInt(12, node.baseBlockLocation.blockZ)
            stmt.setString(13, node.frequency.instrument.name)
            stmt.setInt(14, node.frequency.note)
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] saveWirelessNode 失敗 (${node.id}): ${e.message}")
      }
    }
  }

  fun deleteWirelessNode(nodeId: String) {
    worker.submit {
      try {
        getConnection().use { conn ->
          conn.prepareStatement("DELETE FROM wireless_nodes WHERE id = ?").use { stmt ->
            stmt.setString(1, nodeId)
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] deleteWirelessNode 失敗 ($nodeId): ${e.message}")
      }
    }
  }

  fun loadAllWirelessNodes(): List<io.oyasai.karakuri.engine.WirelessNode> {
    val result = mutableListOf<io.oyasai.karakuri.engine.WirelessNode>()
    try {
      DriverManager.getConnection(connectionUrl).use { conn ->
        conn.createStatement().use { stmt ->
          val rs = stmt.executeQuery("SELECT * FROM wireless_nodes")
          while (rs.next()) {
            val id = rs.getString("id")
            val type = io.oyasai.karakuri.engine.WirelessNodeType.valueOf(rs.getString("type"))
            val worldName = rs.getString("world")
            val world = org.bukkit.Bukkit.getWorld(worldName) ?: continue

            val rodLoc = Location(world, rs.getInt("rod_x").toDouble(), rs.getInt("rod_y").toDouble(), rs.getInt("rod_z").toDouble())
            val noteLoc = Location(world, rs.getInt("note_x").toDouble(), rs.getInt("note_y").toDouble(), rs.getInt("note_z").toDouble())
            val baseLoc = Location(world, rs.getInt("base_x").toDouble(), rs.getInt("base_y").toDouble(), rs.getInt("base_z").toDouble())

            val inst = org.bukkit.Instrument.valueOf(rs.getString("instrument"))
            val note = rs.getInt("note")
            val freq = io.oyasai.karakuri.engine.WirelessFrequency(inst, note)

            result.add(io.oyasai.karakuri.engine.WirelessNode(
              id = id,
              type = type,
              rodLocation = rodLoc,
              noteBlockLocation = noteLoc,
              baseBlockLocation = baseLoc,
              frequency = freq
            ))
          }
        }
      }
    } catch (e: Exception) {
      logger.warning("[Karakuri-DB] loadAllWirelessNodes 失敗: ${e.message}")
    }
    return result
  }

  // =========================================================================
  // リモコン専用収納
  // =========================================================================

  fun saveRemoteStorage(playerUuid: UUID, items: Array<ItemStack?>) {
    val bytes = org.bukkit.inventory.ItemStack.serializeItemsAsBytes(items)
    worker.submit {
      try {
        getConnection().use { conn ->
          val sql = """
            INSERT OR REPLACE INTO remote_storage (player_uuid, items_blob, updated_at)
            VALUES (?, ?, ?);
          """.trimIndent()
          conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, playerUuid.toString())
            stmt.setBytes(2, bytes)
            stmt.setLong(3, System.currentTimeMillis())
            stmt.executeUpdate()
          }
          conn.commit()
        }
      } catch (e: Exception) {
        logger.warning("[Karakuri-DB] saveRemoteStorage 失敗 ($playerUuid): ${e.message}")
      }
    }
  }

  fun loadRemoteStorage(playerUuid: UUID): Array<ItemStack?>? {
    try {
      DriverManager.getConnection(connectionUrl).use { conn ->
        conn.prepareStatement("SELECT items_blob FROM remote_storage WHERE player_uuid = ?").use { stmt ->
          stmt.setString(1, playerUuid.toString())
          val rs = stmt.executeQuery()
          if (rs.next()) {
            val bytes = rs.getBytes("items_blob")
            if (bytes != null && bytes.isNotEmpty()) {
              @Suppress("UNCHECKED_CAST")
              return org.bukkit.inventory.ItemStack.deserializeItemsFromBytes(bytes) as Array<ItemStack?>
            }
          }
        }
      }
    } catch (e: Exception) {
      logger.warning("[Karakuri-DB] loadRemoteStorage 失敗 ($playerUuid): ${e.message}")
    }
    return null
  }

  // =========================================================================
  // サーバー停止時 Flush & シャットダウン
  // =========================================================================

  fun close() {
    logger.info("[Karakuri-DB] データベースワーカーをフラッシュ・終了処理中...")
    flushDirtyPosesSync()
    worker.shutdown()
    try {
      if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.warning("[Karakuri-DB] タイムアウト: 未処理のタスクが残存しています。")
        worker.shutdownNow()
      }
    } catch (_: InterruptedException) {
      worker.shutdownNow()
    }
    logger.info("[Karakuri-DB] データベースワーカーを正常終了しました。")
  }
}
