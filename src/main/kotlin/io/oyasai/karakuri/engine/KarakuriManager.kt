package io.oyasai.karakuri.engine

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.EntityType
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * サーバー内のすべてのからくり（KarakuriInstance）を集中管理するマネージャー。
 * BlockDisplay を全面的に採用し、西向きのエンドロッド等の BlockData を完全保持。
 */
class KarakuriManager {

  companion object {
    const val TAG_ENTITY = "karakuri:entity"
    const val TAG_ID_PREFIX = "karakuri:id:"
    const val TAG_MINECART_CONTRAPTION = "karakuri:cart"
    const val TAG_MINECART_INSTANCE_PREFIX = "karakuri:cart_id:"

    /**
     * 壁付きプレイヤーヘッド (PLAYER_WALL_HEAD) の壁面吸着・高さオフセットを計算
     * バニラの壁掛け頭は高さ中央 (Y=0.5) かつ壁面に密着 (Z/X が 0.25 壁側へシフト) している。
     * ItemDisplay (NONE) は床置き頭モデル (Y=0.0〜0.5, 水平中央) のため、
     * このオフセットを加算することで壁にピッタリ吸着した壁掛け頭として忠実描画する。
     */
    fun getPlayerWallHeadOffset(blockData: BlockData?): Vector3f {
      if (blockData == null || blockData.material != Material.PLAYER_WALL_HEAD || blockData !is org.bukkit.block.data.Directional) {
        return Vector3f(0f, 0f, 0f)
      }
      return when (blockData.facing) {
        org.bukkit.block.BlockFace.NORTH -> Vector3f(0f, 0.25f, 0.25f)
        org.bukkit.block.BlockFace.SOUTH -> Vector3f(0f, 0.25f, -0.25f)
        org.bukkit.block.BlockFace.WEST -> Vector3f(0.25f, 0.25f, 0f)
        org.bukkit.block.BlockFace.EAST -> Vector3f(-0.25f, 0.25f, 0f)
        else -> Vector3f(0f, 0.25f, 0f)
      }
    }
  }

  private data class BoxDef(
    val loc: Location,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val blockData: BlockData,
    val skullProfile: com.destroystokyo.paper.profile.PlayerProfile?,
  )

  var database: io.oyasai.karakuri.persistence.KarakuriDatabase? = null
  var collisionEngine: io.oyasai.karakuri.collision.CollisionEngine? = null

  private val instances = ConcurrentHashMap<String, KarakuriInstance>()
  private val entityIdToInstanceId = ConcurrentHashMap<UUID, String>()
  val idCounter = AtomicInteger(1)

  fun initDatabase(db: io.oyasai.karakuri.persistence.KarakuriDatabase, engine: io.oyasai.karakuri.collision.CollisionEngine) {
    this.database = db
    this.collisionEngine = engine
    val maxId = db.getMaxInstanceIdNumber()
    idCounter.set(maxId + 1)
  }

  fun saveInstanceMotion(instance: KarakuriInstance) {
    val (type, data) = io.oyasai.karakuri.persistence.KarakuriMotionSerializer.serialize(instance)
    database?.updateInstanceMotion(instance.id, type, data)
  }

  /**
   * インスタンスの幾何情報（原点、ピボット、構成ブロック）をDBへ同期
   */
  fun syncInstanceGeometry(instance: KarakuriInstance) {
    database?.updateInstanceGeometry(instance)
  }

  fun getAll(): Collection<KarakuriInstance> = instances.values

  fun get(id: String): KarakuriInstance? = instances[id]

  fun getByEntity(entity: org.bukkit.entity.Entity): KarakuriInstance? {
    val instanceId = entityIdToInstanceId[entity.uniqueId] ?: return null
    return instances[instanceId]
  }

  fun isKarakuriMinecart(entity: org.bukkit.entity.Entity): Boolean {
    return entity is org.bukkit.entity.Minecart && entity.scoreboardTags.contains(TAG_MINECART_CONTRAPTION)
  }

  fun getKarakuriByMinecart(entity: org.bukkit.entity.Entity): KarakuriInstance? {
    if (entity !is org.bukkit.entity.Minecart) return null
    val tag = entity.scoreboardTags.firstOrNull { it.startsWith(TAG_MINECART_INSTANCE_PREFIX) } ?: return null
    val id = tag.removePrefix(TAG_MINECART_INSTANCE_PREFIX)
    return get(id)
  }

  fun mountToMinecart(
    instance: KarakuriInstance,
    minecart: org.bukkit.entity.Minecart,
    followRotation: Boolean = true,
  ): io.oyasai.karakuri.motion.MinecartMotion {
    // 通常トロッコの場合は、モブ搭乗を完全防止するためスポナー付きトロッコ (SpawnerMinecart) へ変化させる
    val targetCart: org.bukkit.entity.Minecart = if (minecart !is org.bukkit.entity.minecart.SpawnerMinecart) {
      val loc = minecart.location.clone()
      val vel = minecart.velocity.clone()
      val tags = minecart.scoreboardTags.toList()
      minecart.remove()
      val spawnerCart = loc.world.spawn(loc, org.bukkit.entity.minecart.SpawnerMinecart::class.java) { sc ->
        sc.velocity = vel
        tags.forEach { sc.addScoreboardTag(it) }
        // スポナーからのMob自然湧きを完全無効化
        try {
          sc.setSpawnedType(null)
          sc.delay = Int.MAX_VALUE
          sc.requiredPlayerRange = 0
        } catch (_: Exception) {}
      }
      io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager.replaceCartReference(minecart, spawnerCart)
      spawnerCart
    } else {
      minecart
    }

    val motion = io.oyasai.karakuri.motion.MinecartMotion(targetCart, followRotation)
    instance.setMotion(motion)
    return motion
  }

  /**
   * スポナー付きトロッコ（SpawnerMinecart）を通常の乗車可能トロッコ（RideableMinecart）に復元し、
   * 連結データやタグを確実に引き継ぐ
   */
  fun restoreSpawnerCartToRideable(cart: org.bukkit.entity.Minecart): org.bukkit.entity.Minecart {
    if (cart !is org.bukkit.entity.minecart.SpawnerMinecart || !cart.isValid) return cart
    val loc = cart.location.clone()
    val vel = cart.velocity.clone()
    cart.removeScoreboardTag(TAG_MINECART_CONTRAPTION)
    val tags = cart.scoreboardTags.toList()

    cart.remove()
    val rc = loc.world.spawn(loc, org.bukkit.entity.minecart.RideableMinecart::class.java) { newCart ->
      newCart.velocity = vel
      tags.forEach { tag ->
        if (tag != TAG_MINECART_CONTRAPTION && !tag.startsWith(TAG_MINECART_INSTANCE_PREFIX)) {
          newCart.addScoreboardTag(tag)
        }
      }
    }

    io.oyasai.karakuri.KarakuriPlugin.instance.minecartCouplingManager.replaceCartReference(cart, rc)
    return rc
  }

  fun dismountFromMinecart(instance: KarakuriInstance) {
    val motion = instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion ?: return
    val cart = motion.minecart

    if (cart.isValid) {
      cart.removeScoreboardTag(TAG_MINECART_CONTRAPTION)
      cart.removeScoreboardTag("$TAG_MINECART_INSTANCE_PREFIX${instance.id}")
      restoreSpawnerCartToRideable(cart)
    }

    // 現在のトロッコ位置に新しい初期原点を設定して停止
    val newOrigin = instance.displayEntity.location.clone()
    instance.stopMotion()
    instance.updateOrigin(newOrigin)
  }

  data class RayTraceHit(
    val instance: KarakuriInstance,
    val hitDisplay: Display,
    val hitBlockLocation: Location,
  )

  /**
   * プレイヤーの視線から、最も近いからくりインスタンスを検索（マルチブロック対応）
   */
  fun rayTrace(start: Location, direction: Vector, maxDistance: Double = 5.0): KarakuriInstance? {
    return rayTraceDetailed(start, direction, maxDistance)?.instance
  }

  /**
   * 視線先にあるからくりインスタンスと、具体的にヒットしたパーツ（ブロック位置）を検索
   * 静止時・回転時（開いたドアなど）問わず、マルチブロックの全パーツの全位置でクリックを完全検知。
   */
  fun rayTraceDetailed(start: Location, direction: Vector, maxDistance: Double = 5.0): RayTraceHit? {
    val startVec = start.toVector()
    val dir = direction.clone().normalize()
    val world = start.world ?: return null

    var closestHit: RayTraceHit? = null
    var closestDistSq = maxDistance * maxDistance

    for (inst in instances.values) {
      if (!inst.isValid() || inst.displayEntity.world != world) continue

      val isChild = inst.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                    inst.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion
      if (isChild) {
        // 子構造の場合: 実際に公転テレポートされている DisplayEntity の現在位置から直接ヒット判定！
        fun checkDisplayEntityHit(display: Display, origLoc: Location) {
          val eLoc = display.location
          val isItem = display is org.bukkit.entity.ItemDisplay
          val cx = if (isItem) eLoc.x else eLoc.x + 0.5
          val cy = if (isItem) eLoc.y else eLoc.y + 0.5
          val cz = if (isItem) eLoc.z else eLoc.z + 0.5
          val curBox = BoundingBox(
            cx - 0.65, cy - 0.65, cz - 0.65,
            cx + 0.65, cy + 0.65, cz + 0.65
          )
          val hitCur = curBox.rayTrace(startVec, dir, maxDistance)
          if (hitCur != null) {
            val distSq = startVec.distanceSquared(hitCur.hitPosition)
            if (distSq < closestDistSq) {
              closestDistSq = distSq
              closestHit = RayTraceHit(inst, display, origLoc.clone())
            }
          }
        }

        checkDisplayEntityHit(inst.displayEntity, inst.originLocation)
        for (part in inst.additionalParts) {
          if (part.displayEntity.isValid) {
            checkDisplayEntityHit(part.displayEntity, part.originLocation)
          }
        }
        continue
      }

      val curRot = inst.currentRotation
      val pivotLoc = inst.worldPivotLocation

      // ルートパーツの現在位置と初期位置の移動差分（トロッコ移動や直線移動を反映）
      val rootOffset = inst.getBaseEntityOffset(inst.displayEntity, inst.originalBlockData)
      val deltaX = if (inst.isYawModeActive) 0.0 else inst.displayEntity.location.x - (inst.originLocation.x + rootOffset.x)
      val deltaY = if (inst.isYawModeActive) 0.0 else inst.displayEntity.location.y - (inst.originLocation.y + rootOffset.y)
      val deltaZ = if (inst.isYawModeActive) 0.0 else inst.displayEntity.location.z - (inst.originLocation.z + rootOffset.z)

      // 構成する全 1x1x1 個別ブロック位置を収集（直方体統合後でも各ブロック単位で精密判定）
      val blockLocations = if (inst.constituentBlocks.isNotEmpty()) {
        inst.constituentBlocks.keys.toList()
      } else {
        val list = mutableListOf(inst.originLocation)
        for (part in inst.additionalParts) {
          if (part.displayEntity.isValid) {
            list.add(part.originLocation)
          }
        }
        list
      }

      for (origLoc in blockLocations) {
        // (1) 静止位置（原点ブロック）のバウンディングボックス判定（移動差分がほぼゼロの場合のみ有効）
        val isStationary = kotlin.math.abs(deltaX) < 0.05 && kotlin.math.abs(deltaY) < 0.05 && kotlin.math.abs(deltaZ) < 0.05
        if (isStationary) {
          val initialBox = BoundingBox(
            origLoc.x, origLoc.y, origLoc.z,
            origLoc.x + 1.0, origLoc.y + 1.0, origLoc.z + 1.0
          ).expand(0.15)

          val hitInitial = initialBox.rayTrace(startVec, dir, maxDistance)
          if (hitInitial != null) {
            val distSq = startVec.distanceSquared(hitInitial.hitPosition)
            if (distSq < closestDistSq) {
              closestDistSq = distSq
              closestHit = RayTraceHit(inst, inst.displayEntity, origLoc.clone())
            }
          }
        }

        // (2) 現在の変位後（回転・平行移動・トロッコ追従など）のブロック位置の判定
        // ピボット中心からの相対ベクトルを現在のクォータニオンで回転し、移動差分を加算
        val relCenter = org.joml.Vector3f(
          (origLoc.x + 0.5 - pivotLoc.x).toFloat(),
          (origLoc.y + 0.5 - pivotLoc.y).toFloat(),
          (origLoc.z + 0.5 - pivotLoc.z).toFloat()
        )
        val rotatedCenter = org.joml.Vector3f(relCenter).rotate(curRot)
        val curCenterX = pivotLoc.x + deltaX + rotatedCenter.x
        val curCenterY = pivotLoc.y + deltaY + rotatedCenter.y
        val curCenterZ = pivotLoc.z + deltaZ + rotatedCenter.z

        val curBox = BoundingBox(
          curCenterX - 0.65, curCenterY - 0.65, curCenterZ - 0.65,
          curCenterX + 0.65, curCenterY + 0.65, curCenterZ + 0.65
        )

        val hitCur = curBox.rayTrace(startVec, dir, maxDistance)
        if (hitCur != null) {
          val distSq = startVec.distanceSquared(hitCur.hitPosition)
          if (distSq < closestDistSq) {
            closestDistSq = distSq
            closestHit = RayTraceHit(inst, inst.displayEntity, origLoc.clone())
          }
        }
      }
    }
    return closestHit
  }

  /**
   * 実在ブロックから BlockData をそのまま引き継いでスポーンする。
   * 全ブロックを BlockDisplay で生成し、BlockData を100%完全保持。
   * 全軸完全対称ピボット (0.5, 0.5, 0.5) により、ブレ・公転ゼロで自転。
   */
  fun spawn(
    block: Block,
    customId: String? = null,
  ): KarakuriInstance {
    val loc = Location(block.world, block.x.toDouble(), block.y.toDouble(), block.z.toDouble(), 0f, 0f)
    val blockData = block.blockData.clone()
    val skullProfile = (block.state as? org.bukkit.block.Skull)?.playerProfile
    block.setType(Material.AIR, false) // 物理更新なしでAIR化（ステート破壊を防止）
    return spawn(loc, blockData, customId, skullProfile)
  }

  /**
   * ダブルハーフブロックに対応するフルブロックの Material を取得
   */
  fun getDoubleSlabMaterial(slabMaterial: Material): Material {
    val name = slabMaterial.name
    val base = name.removeSuffix("_SLAB")
    // 例: OAK_SLAB -> OAK_PLANKS, STONE_SLAB -> STONE
    Material.getMaterial("${base}_PLANKS")?.let { return it }
    Material.getMaterial("${base}_BLOCK")?.let { return it }
    Material.getMaterial("${base}S")?.let { return it }
    Material.getMaterial(base)?.let { return it }
    return slabMaterial
  }

  /**
   * BlockData の向き・形状プロパティに応じた初期姿勢クォータニオン (rightRotation) を計算
   * ItemDisplay (NONE) の中心原点 (0, 0, 0) を1ミリも動かさずに、設置された向きへ正確に回転！
   */
  fun calculateInitialRightRotation(blockData: BlockData): Quaternionf {
    val mat = blockData.material
    val quat = Quaternionf()

    // 1. ハーフブロック (Slab)
    if (blockData is org.bukkit.block.data.type.Slab) {
      if (blockData.type == org.bukkit.block.data.type.Slab.Type.TOP) {
        // 上付きハーフブロック: X軸180度反転でブロックの上半分に配置
        return quat.rotateX(Math.PI.toFloat())
      }
      return quat
    }

    // 2. 階段 (Stairs)
    if (blockData is org.bukkit.block.data.type.Stairs) {
      // バニラの階段アイテムモデルの基準向きは EAST, BOTTOM
      when (blockData.facing) {
        org.bukkit.block.BlockFace.EAST -> {}
        org.bukkit.block.BlockFace.SOUTH -> quat.rotateY((-Math.PI / 2.0).toFloat())
        org.bukkit.block.BlockFace.WEST -> quat.rotateY(Math.PI.toFloat())
        org.bukkit.block.BlockFace.NORTH -> quat.rotateY((Math.PI / 2.0).toFloat())
        else -> {}
      }
      if (blockData.half == org.bukkit.block.data.Bisected.Half.TOP) {
        quat.rotateX(Math.PI.toFloat())
      }
      return quat
    }

    // 3. Directional (エンドロッド、避雷針、かまど、オブザーバー、壁掛け頭等)
    if (blockData is org.bukkit.block.data.Directional) {
      if (mat == Material.END_ROD || mat == Material.LIGHTNING_ROD) {
        // エンドロッド・避雷針: デフォルトは UP (+Y)
        return when (blockData.facing) {
          org.bukkit.block.BlockFace.UP -> quat
          org.bukkit.block.BlockFace.DOWN -> quat.rotateX(Math.PI.toFloat())
          org.bukkit.block.BlockFace.NORTH -> quat.rotateX((-Math.PI / 2.0).toFloat())
          org.bukkit.block.BlockFace.SOUTH -> quat.rotateX((Math.PI / 2.0).toFloat())
          org.bukkit.block.BlockFace.WEST -> quat.rotateZ((Math.PI / 2.0).toFloat())
          org.bukkit.block.BlockFace.EAST -> quat.rotateZ((-Math.PI / 2.0).toFloat())
          else -> quat
        }
      } else {
        // 一般的な Directional ブロック (PLAYER_WALL_HEAD含む): デフォルトは NORTH (-Z)
        return when (blockData.facing) {
          org.bukkit.block.BlockFace.NORTH -> quat
          org.bukkit.block.BlockFace.SOUTH -> quat.rotateY(Math.PI.toFloat())
          org.bukkit.block.BlockFace.WEST -> quat.rotateY((Math.PI / 2.0).toFloat())
          org.bukkit.block.BlockFace.EAST -> quat.rotateY((-Math.PI / 2.0).toFloat())
          org.bukkit.block.BlockFace.UP -> quat.rotateX((-Math.PI / 2.0).toFloat())
          org.bukkit.block.BlockFace.DOWN -> quat.rotateX((Math.PI / 2.0).toFloat())
          else -> quat
        }
      }
    }

    // 4. Orientable (鎖, 原木, 柱, 玄武岩等)
    if (blockData is org.bukkit.block.data.Orientable) {
      return when (blockData.axis) {
        org.bukkit.Axis.Y -> quat
        org.bukkit.Axis.X -> quat.rotateZ((Math.PI / 2.0).toFloat())
        org.bukkit.Axis.Z -> quat.rotateX((Math.PI / 2.0).toFloat())
      }
    }

    // 5. Rotatable (頭・看板等 16方向)
    // バニラの仕様上、設置時プレイヤーの視線方向が rotation に記録され、顔はプレイヤー側 (180度反転) を向く
    if (blockData is org.bukkit.block.data.Rotatable) {
      val dir = blockData.rotation.direction
      if (dir.lengthSquared() > 0.001) {
        val angleRad = kotlin.math.atan2(dir.x, dir.z).toFloat()
        return quat.rotateY(angleRad)
      }
    }

    return quat
  }

  /**
   * ブロックの忠実な外観表示に BlockDisplay が必須かどうかを精密判定。
   * 建築勢がデバッグ棒で弄った変化（階段形状、フェンス・塀の接続、トラップドアの開閉、鉄格子、水没など）
   * を100%完全保持・忠実描画するため、これらは BlockDisplay で生成します。
   * 一方、フルキューブ(石や木材等)やエンドロッド・避雷針など、ItemDisplay で幾何学的に完全一致するものは
   * 中心原点方式(ItemDisplay NONE)を採用し、高速回転時のブレ 0.000000 mm を両立します。
   */
  fun shouldUseBlockDisplay(blockData: BlockData): Boolean {
    val mat = blockData.material
    if (!mat.isItem) return true // 非アイテム（水、溶岩、ピストンヘッド等）

    // 1. 形状・接続・開閉等のバニラ特殊ステートを持つブロックタイプ（ItemDisplay では単体アイテムになってしまうもの）
    if (blockData is org.bukkit.block.data.type.Stairs) return true
    if (blockData is org.bukkit.block.data.type.Fence) return true
    if (blockData is org.bukkit.block.data.type.Wall) return true
    if (blockData is org.bukkit.block.data.type.TrapDoor) return true
    if (blockData is org.bukkit.block.data.type.Door) return true
    if (blockData is org.bukkit.block.data.type.Gate) return true
    if (blockData is org.bukkit.block.data.MultipleFacing) return true // 鉄格子、板ガラス、レッドストーンワイヤー等
    if (blockData is org.bukkit.block.data.type.Lantern) return true
    if (blockData is org.bukkit.block.data.type.Campfire) return true
    if (blockData is org.bukkit.block.data.type.Candle) return true
    if (blockData is org.bukkit.block.data.type.Bed) return true
    if (blockData is org.bukkit.block.data.type.Chest) return true
    if (blockData is org.bukkit.block.data.type.Speleothem) return true
    if (blockData is org.bukkit.block.data.type.BigDripleaf) return true
    if (blockData is org.bukkit.block.data.type.Cake) return true
    if (blockData is org.bukkit.block.data.type.Comparator) return true
    if (blockData is org.bukkit.block.data.type.Repeater) return true
    if (blockData is org.bukkit.block.data.type.DaylightDetector) return true
    if (blockData is org.bukkit.block.data.type.Observer) return true
    if (blockData is org.bukkit.block.data.type.Piston) return true
    if (blockData is org.bukkit.block.data.type.PistonHead) return true
    if (blockData is org.bukkit.block.data.type.SculkSensor) return true
    if (blockData is org.bukkit.block.data.type.SculkShrieker) return true
    if (blockData is org.bukkit.block.data.type.Snow) return true
    if (blockData is org.bukkit.block.data.type.Lectern) return true
    if (blockData is org.bukkit.block.data.type.RedstoneWire) return true
    if (blockData is org.bukkit.block.data.type.Tripwire) return true
    if (blockData is org.bukkit.block.data.type.TripwireHook) return true
    if (blockData is org.bukkit.block.data.type.Sign) return true
    if (blockData is org.bukkit.block.data.type.WallSign) return true
    if (blockData is org.bukkit.block.data.type.HangingSign) return true
    if (blockData is org.bukkit.block.data.type.WallHangingSign) return true

    // プレイヤーの頭 (Player Head / Player Wall Head):
    // BlockDisplay は Minecraft の仕様上 TileEntity(カスタムスキン) を描画できないため、
    // スキンを100%忠実描画できる ItemDisplay (SkullMeta) を必須適用！
    if (mat == Material.PLAYER_HEAD || mat == Material.PLAYER_WALL_HEAD) return false

    // 2. 水没 (Waterlogged): ItemDisplay では水を描画できないため BlockDisplay 必須
    if (blockData is org.bukkit.block.data.Waterlogged && blockData.isWaterlogged) return true

    // 3. デバッグ棒によって変更されたプロパティがあるかチェック
    val defaultData = mat.createBlockData()
    if (blockData.asString != defaultData.asString) {
      // エンドロッド/避雷針(向き全対応)、プレイヤーの頭(全角度対応)、ハーフブロック(上下ダブル対応)、Orientable(X/Y/Z軸対応)
      // これらは ItemDisplay の initialRightRotation で完璧に向きを再現できるためブレゼロ・スキン保持優先
      val isHandledByItemDisplay = (mat == Material.END_ROD || mat == Material.LIGHTNING_ROD) ||
        (mat == Material.PLAYER_HEAD || mat == Material.PLAYER_WALL_HEAD) ||
        (blockData is org.bukkit.block.data.type.Slab && !blockData.isWaterlogged) ||
        (blockData is org.bukkit.block.data.Orientable && !(blockData is org.bukkit.block.data.Waterlogged && blockData.isWaterlogged))
      if (!isHandledByItemDisplay) {
        return true
      }
    }

    // 4. エンドロッド・避雷針 (向き全対応、検証基準ブロック) -> ItemDisplay でブレゼロ
    if (mat == Material.END_ROD || mat == Material.LIGHTNING_ROD) return false

    // 5. ハーフブロック (Slab): 上/下/ダブル対応 -> ItemDisplay
    if (blockData is org.bukkit.block.data.type.Slab) return false

    // 6. 軸ブロック (Orientable: 鎖、原木、玄武岩等) -> ItemDisplay
    if (blockData is org.bukkit.block.data.Orientable) return false

    // 7. フルブロック (不透過・直方体) は ItemDisplay NONE でブレ 0.000000 mm
    if (mat.isOccluding) {
      return false
    }

    // その他、外観の一致を最優先するため BlockDisplay を使用
    return true
  }

  data class BoxPlacement(
    val spawnLocation: Location,
    val rightRotation: Quaternionf,
  )

  /**
   * 統合直方体（MergedBox）のスポーン位置と initialRightRotation をピボット基準で最適化する。
   * ピボットからマイナス方向（西・下・北）に伸びる長大直方体の場合、
   * 原点をピボットから一番遠い先端ではなく「ピボットに最も近い根元の角」に反転配置し、
   * 180度回転 (rightRotation) を与えることで、物理的な占有空間を1ミリも狂わせずに
   * ピボット距離を極小化（7〜8m -> 1m以下）し、高速回転時の荒ぶり・五角形ブレを完全に根絶する。
   */
  private fun calculateBoxPlacement(box: BoxDef, pivotLoc: Location): BoxPlacement {
    val sx = box.sizeX
    val sy = box.sizeY
    val sz = box.sizeZ

    // 単体ブロックは回転・オフセット不要
    if (sx == 1 && sy == 1 && sz == 1) {
      return BoxPlacement(box.loc.clone(), Quaternionf())
    }

    val xmin = box.loc.blockX.toDouble()
    val ymin = box.loc.blockY.toDouble()
    val zmin = box.loc.blockZ.toDouble()

    val xmax = xmin + sx
    val ymax = ymin + sy
    val zmax = zmin + sz

    val px = pivotLoc.x
    val py = pivotLoc.y
    val pz = pivotLoc.z

    // 各軸について、直方体の端点とピボットの距離を比較
    val isXMaxCloser = (sx > 1) && (kotlin.math.abs(px - xmax) < kotlin.math.abs(px - xmin))
    val isYMaxCloser = (sy > 1) && (kotlin.math.abs(py - ymax) < kotlin.math.abs(py - ymin))
    val isZMaxCloser = (sz > 1) && (kotlin.math.abs(pz - zmax) < kotlin.math.abs(pz - zmin))

    val maxDim = maxOf(sx, sy, sz)

    if (maxDim == sx && isXMaxCloser) {
      // X軸が長軸で、ピボットが東(xmax)側にある場合（西向きに伸びる直方体）
      // Y軸周り180度回転 (Yaw=180): (x, y, z) -> (-x, y, -z)
      // 原点を (xmax, ymin, zmax) に置くことで、
      // 空間占有が 100% 元と完全一致し、ピボット距離が数mから1m以下に激減する
      val spawnLoc = Location(box.loc.world, xmax, ymin, zmax, 0f, 0f)
      val q = Quaternionf().rotateY(Math.PI.toFloat())
      return BoxPlacement(spawnLoc, q)
    } else if (maxDim == sy && isYMaxCloser) {
      // Y軸が長軸で、ピボットが上(ymax)側にある場合（下向きに伸びる直方体）
      // X軸周り180度回転 (Pitch=180): (x, y, z) -> (x, -y, -z)
      // 原点を (xmin, ymax, zmax) に置くことで、
      // 空間占有が 100% 元と完全一致し、ピボット距離が数mから1m以下に激減する
      val spawnLoc = Location(box.loc.world, xmin, ymax, zmax, 0f, 0f)
      val q = Quaternionf().rotateX(Math.PI.toFloat())
      return BoxPlacement(spawnLoc, q)
    } else if (maxDim == sz && isZMaxCloser) {
      // Z軸が長軸で、ピボットが南(zmax)側にある場合（北向きに伸びる直方体）
      // Y軸周り180度回転 (Yaw=180): (x, y, z) -> (-x, y, -z)
      // 原点を (xmax, ymin, zmax) に置くことで、
      // 空間占有が 100% 元と完全一致し、ピボット距離が数mから1m以下に激減する
      val spawnLoc = Location(box.loc.world, xmax, ymin, zmax, 0f, 0f)
      val q = Quaternionf().rotateY(Math.PI.toFloat())
      return BoxPlacement(spawnLoc, q)
    }

    return BoxPlacement(box.loc.clone(), Quaternionf())
  }

  /**
   * 構成ブロックの描画用 Display Entity を生成する。
   * フルブロック・原木・エンドロッド・頭等: ItemDisplay NONE 1.0x (中心原点自転・ブレゼロ)
   * 建築ステート保持ブロック: BlockDisplay
   */
  fun createDisplayEntity(
    world: org.bukkit.World,
    cornerLoc: Location,
    blockData: BlockData,
    skullProfile: com.destroystokyo.paper.profile.PlayerProfile?,
    id: String,
  ): Pair<Display, Quaternionf> {
    val mat = blockData.material
    if (!shouldUseBlockDisplay(blockData)) {
      // 中心原点方式（ItemDisplay NONE 1.0x + initialRightRotation）
      val wallOffset = getPlayerWallHeadOffset(blockData)
      val centerLoc = Location(
        cornerLoc.world,
        cornerLoc.blockX.toDouble() + 0.5 + wallOffset.x,
        cornerLoc.blockY.toDouble() + 0.5 + wallOffset.y,
        cornerLoc.blockZ.toDouble() + 0.5 + wallOffset.z,
        0.0f,
        0.0f
      )

      val initialRightRot = calculateInitialRightRotation(blockData)
      val itemMat = if (mat == Material.PLAYER_WALL_HEAD) {
        Material.PLAYER_HEAD
      } else if (blockData is org.bukkit.block.data.type.Slab && blockData.type == org.bukkit.block.data.type.Slab.Type.DOUBLE) {
        getDoubleSlabMaterial(mat)
      } else {
        mat
      }

      val itemDisplay = world.spawnEntity(centerLoc, EntityType.ITEM_DISPLAY) as org.bukkit.entity.ItemDisplay
      val itemStack = org.bukkit.inventory.ItemStack(itemMat)
      if (skullProfile != null) {
        val meta = itemStack.itemMeta as? org.bukkit.inventory.meta.SkullMeta
        if (meta != null) {
          meta.playerProfile = skullProfile
          itemStack.itemMeta = meta
        }
      }
      itemDisplay.setItemStack(itemStack)
      itemDisplay.itemDisplayTransform = org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE
      itemDisplay.transformation = org.bukkit.util.Transformation(
        org.joml.Vector3f(0f, 0f, 0f),
        org.joml.Quaternionf(),
        org.joml.Vector3f(1.0f, 1.0f, 1.0f),
        initialRightRot
      )
      itemDisplay.isPersistent = false
      itemDisplay.teleportDuration = 0
      itemDisplay.interpolationDuration = 0
      itemDisplay.interpolationDelay = 0
      itemDisplay.addScoreboardTag(TAG_ENTITY)
      itemDisplay.addScoreboardTag("$TAG_ID_PREFIX$id")
      return itemDisplay to initialRightRot
    }

    // 建築ステート・形状保持優先（BlockDisplay 1.0x）
    val blockDisplay = world.spawnEntity(cornerLoc, EntityType.BLOCK_DISPLAY) as BlockDisplay
    blockDisplay.isPersistent = false
    blockDisplay.block = blockData
    blockDisplay.teleportDuration = 0
    blockDisplay.interpolationDuration = 0
    blockDisplay.interpolationDelay = 0
    blockDisplay.transformation = org.bukkit.util.Transformation(
      org.joml.Vector3f(0f, 0f, 0f),
      org.joml.Quaternionf(),
      org.joml.Vector3f(1.0f, 1.0f, 1.0f),
      org.joml.Quaternionf()
    )
    blockDisplay.addScoreboardTag(TAG_ENTITY)
    blockDisplay.addScoreboardTag("$TAG_ID_PREFIX$id")
    return blockDisplay to org.joml.Quaternionf()
  }

  /**
   * BlockData を指定してからくりをスポーンする
   * 建築ステート・デバッグ棒変更があるブロック: BlockDisplay で100%完全描画。
   * フルブロック・エンドロッド・頭・ハーフブロック・鎖等: ItemDisplay (NONE 1.0x) でスキン描画＆ブレ 0.000000 mm 自転。
   */
  fun spawn(
    location: Location,
    blockData: BlockData,
    customId: String? = null,
    skullProfile: com.destroystokyo.paper.profile.PlayerProfile? = null,
  ): KarakuriInstance {
    val id = customId ?: "kk-${idCounter.getAndIncrement()}"
    remove(id)

    val world = location.world ?: throw IllegalArgumentException("Location world cannot be null")

    val cornerLoc = Location(
      location.world,
      location.blockX.toDouble(),
      location.blockY.toDouble(),
      location.blockZ.toDouble(),
      0.0f,
      0.0f
    )

    val (display, initialRightRot) = createDisplayEntity(world, cornerLoc, blockData, skullProfile, id)

    val interactionLoc = cornerLoc.clone().add(0.5, 0.0, 0.5)
    val interaction = world.spawn(interactionLoc, org.bukkit.entity.Interaction::class.java) {
      it.isPersistent = false
      it.interactionWidth = 1.0f
      it.interactionHeight = if (io.oyasai.karakuri.engine.MinecartCouplingManager.isRail(blockData.material)) 0.2f else 1.0f
      it.isResponsive = true
      it.addScoreboardTag(TAG_ENTITY)
      it.addScoreboardTag("$TAG_ID_PREFIX$id")
    }

    val instance = KarakuriInstance(
      id = id,
      displayEntity = display,
      baseLocation = cornerLoc.clone(),
      additionalParts = mutableListOf(),
      interactionEntity = interaction,
      originalBlockData = blockData.clone(),
      initialRightRotation = initialRightRot,
      skullProfile = skullProfile,
    )
    val wallOffset = getPlayerWallHeadOffset(blockData)
    instance.pivotOffset = if (display is org.bukkit.entity.ItemDisplay) {
      org.joml.Vector3f(-wallOffset.x, -wallOffset.y, -wallOffset.z)
    } else {
      org.joml.Vector3f(0.5f, 0.5f, 0.5f)
    }
    instance.scale = org.joml.Vector3f(1.0f, 1.0f, 1.0f)
    instance.worldPivotLocation = Location(
      location.world,
      location.blockX.toDouble() + 0.5,
      location.blockY.toDouble() + 0.5,
      location.blockZ.toDouble() + 0.5
    )

    instance.constituentBlocks[cornerLoc] = blockData.clone()
    if (skullProfile != null) {
      instance.constituentSkullProfiles[cornerLoc] = skullProfile
    }

    instances[id] = instance
    entityIdToInstanceId[display.uniqueId] = id
    entityIdToInstanceId[interaction.uniqueId] = id
    return instance
  }

  /**
   * Material からデフォルト BlockData でスポーンする（下位互換）
   */
  fun spawn(
    location: Location,
    material: Material,
    customId: String? = null,
  ): KarakuriInstance {
    val blockData = if (material.isBlock) material.createBlockData() else Material.STONE.createBlockData()
    return spawn(location, blockData, customId)
  }

  /**
   * 接着された複数の建築ブロックをまとめて1つのからくり（マルチブロック）としてスポーンする
   * 全ブロックの向き・デバッグ棒プロパティ（BlockData）を一括先行退避し、100%完全保持。
   */
  fun spawnComposite(
    blocks: List<Block>,
    pivotBlock: Block,
    customId: String? = null,
  ): KarakuriInstance {
    val id = customId ?: "kk-${idCounter.getAndIncrement()}"
    remove(id)

    val world = pivotBlock.world

    // ★重要: ブロックを1つでも消す前に、全ブロックの BlockData と TileEntity(頭のスキン等) を一括先行退避！
    // 物理更新による階段形状やフェンス接続、エンドロッド向き等のリセットを完全遮断。
    val blockDataMap = blocks.associateWith { it.blockData.clone() }
    val skullProfileMap = blocks.associateWith { (it.state as? org.bukkit.block.Skull)?.playerProfile }
    val pivotData = blockDataMap[pivotBlock] ?: pivotBlock.blockData.clone()
    val pivotProfile = skullProfileMap[pivotBlock]

    // 1. ピボットブロックの生成（プレイヤーの頭は ItemDisplay でスキン忠実描画、他は BlockDisplay）
    val pivotLoc = Location(
      world,
      pivotBlock.x.toDouble(),
      pivotBlock.y.toDouble(),
      pivotBlock.z.toDouble(),
      0f,
      0f
    )
    pivotBlock.setType(Material.AIR, false) // 物理更新なし

    // 1. ピボットブロックの生成（ItemDisplay NONE 1.0x による中心原点ブレゼロ自転、または BlockDisplay）
    val (rootDisplay, rootRightRot) = createDisplayEntity(world, pivotLoc, pivotData, pivotProfile, id)

    val rootInteractionLoc = pivotLoc.clone().add(0.5, 0.0, 0.5)
    val rootInteraction = world.spawn(rootInteractionLoc, org.bukkit.entity.Interaction::class.java) {
      it.isPersistent = false
      it.interactionWidth = 1.0f
      it.interactionHeight = if (io.oyasai.karakuri.engine.MinecartCouplingManager.isRail(pivotData.material)) 0.2f else 1.0f
      it.isResponsive = true
      it.addScoreboardTag(TAG_ENTITY)
      it.addScoreboardTag("$TAG_ID_PREFIX$id")
    }

    val additionalParts = mutableListOf<KarakuriInstance.BlockPart>()

    // 2. 残りのブロックをパーツとして生成
    for (block in blocks) {
      if (block.x == pivotBlock.x && block.y == pivotBlock.y && block.z == pivotBlock.z) continue
      val partData = blockDataMap[block] ?: block.blockData.clone()
      val partProfile = skullProfileMap[block]
      block.setType(Material.AIR, false) // 物理更新なし

      val partLoc = Location(world, block.x.toDouble(), block.y.toDouble(), block.z.toDouble(), 0f, 0f)
      val (partDisplay, partRightRot) = createDisplayEntity(world, partLoc, partData, partProfile, id)

      val partInteractionLoc = partLoc.clone().add(0.5, 0.0, 0.5)
      val partInteraction = world.spawn(partInteractionLoc, org.bukkit.entity.Interaction::class.java) {
        it.isPersistent = false
        it.interactionWidth = 1.0f
        it.interactionHeight = if (io.oyasai.karakuri.engine.MinecartCouplingManager.isRail(partData.material)) 0.2f else 1.0f
        it.isResponsive = true
        it.addScoreboardTag(TAG_ENTITY)
        it.addScoreboardTag("$TAG_ID_PREFIX$id")
      }

      additionalParts.add(
        KarakuriInstance.BlockPart(
          displayEntity = partDisplay,
          originLocation = partLoc.clone(),
          interactionEntity = partInteraction,
          originalBlockData = partData.clone(),
          initialRightRotation = partRightRot,
          skullProfile = partProfile,
        )
      )
      entityIdToInstanceId[partDisplay.uniqueId] = id
      entityIdToInstanceId[partInteraction.uniqueId] = id
    }

    val instance = KarakuriInstance(
      id = id,
      displayEntity = rootDisplay,
      baseLocation = pivotLoc.clone(),
      additionalParts = additionalParts,
      interactionEntity = rootInteraction,
      originalBlockData = pivotData.clone(),
      initialRightRotation = rootRightRot,
      skullProfile = pivotProfile,
    )
    // 初期ピボットはクリックされたブロックの中心
    instance.setPivotToBlock(pivotBlock.location, syncToDb = false)

    // 構成ブロック情報を完全退避・保持（撤去時の完全復元や、直方体統合時の個別ブロック座標特定に使用）
    for (block in blocks) {
      val bLoc = Location(world, block.x.toDouble(), block.y.toDouble(), block.z.toDouble(), 0f, 0f)
      val bData = blockDataMap[block] ?: block.blockData.clone()
      instance.constituentBlocks[bLoc] = bData
      val bProfile = skullProfileMap[block]
      if (bProfile != null) {
        instance.constituentSkullProfiles[bLoc] = bProfile
      }
    }

    instances[id] = instance
    entityIdToInstanceId[rootDisplay.uniqueId] = id
    entityIdToInstanceId[rootInteraction.uniqueId] = id
    return instance
  }

  /**
   * からくりの構成エンティティを再構築する（軽量化 ON/OFF 切り替え）。
   * 最適化 ON: 隣接する同種フルブロックを 3D 貪欲法 (Greedy Meshing) で直方体に統合し、エンティティ数を大幅削減。
   * 最適化 OFF: 全ブロックを元の 1x1x1 個別エンティティに展開。
   * 現在の回転姿勢・ピボット中心・移動オフセットを1ミリも狂わせずに完全に引き継ぎます。
   */
  fun rebuildEntities(
    instance: KarakuriInstance,
    optimize: Boolean,
    allowNonFull: Boolean = instance.optimizeNonFullBlocks
  ) {
    val world = instance.originLocation.world ?: return
    if (instance.constituentBlocks.isEmpty()) {
      instance.originalBlockData?.let { instance.constituentBlocks[instance.originLocation] = it }
      instance.skullProfile?.let { instance.constituentSkullProfiles[instance.originLocation] = it }
      for (part in instance.additionalParts) {
        part.originalBlockData?.let { instance.constituentBlocks[part.originLocation] = it }
        part.skullProfile?.let { instance.constituentSkullProfiles[part.originLocation] = it }
      }
    }
    if (instance.constituentBlocks.isEmpty()) return

    // 1. 現在の状態（回転、ピボット、移動オフセット等）を退避
    val currentRot = Quaternionf(instance.currentRotation)
    val curWorldPivot = instance.worldPivotLocation.clone()

    val oldRootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
    val curRootLoc = instance.displayEntity.location
    val deltaX = curRootLoc.x - (instance.originLocation.x + oldRootOffset.x)
    val deltaY = curRootLoc.y - (instance.originLocation.y + oldRootOffset.y)
    val deltaZ = curRootLoc.z - (instance.originLocation.z + oldRootOffset.z)
    val hasDelta = (kotlin.math.abs(deltaX) > 0.001 || kotlin.math.abs(deltaY) > 0.001 || kotlin.math.abs(deltaZ) > 0.001)

    // 2. 既存エンティティをワールドおよびマップから削除
    instance.cleanupDualEntities()
    instance.restoreFromYawMode()
    entityIdToInstanceId.remove(instance.displayEntity.uniqueId)
    if (instance.displayEntity.isValid) {
      instance.displayEntity.remove()
    }
    instance.interactionEntity?.let {
      entityIdToInstanceId.remove(it.uniqueId)
      if (it.isValid) it.remove()
    }
    for (part in instance.additionalParts) {
      entityIdToInstanceId.remove(part.displayEntity.uniqueId)
      if (part.displayEntity.isValid) part.displayEntity.remove()
      part.interactionEntity?.let {
        entityIdToInstanceId.remove(it.uniqueId)
        if (it.isValid) it.remove()
      }
    }
    instance.additionalParts.clear()

    // 3. ボックス定義リストの生成
    val boxDefs = mutableListOf<BoxDef>()

    if (optimize) {
      val posMap = instance.constituentBlocks.mapKeys { (loc, _) ->
        BlockPos(loc.blockX, loc.blockY, loc.blockZ)
      }
      val packed = GreedyBoxPacker.pack(posMap, allowNonFull)
      for (mb in packed) {
        val bLoc = Location(world, mb.originX.toDouble(), mb.originY.toDouble(), mb.originZ.toDouble(), 0f, 0f)
        val profile = instance.constituentSkullProfiles[bLoc]
        boxDefs.add(BoxDef(bLoc, mb.sizeX, mb.sizeY, mb.sizeZ, mb.blockData, profile))
      }
    } else {
      for ((bLoc, bData) in instance.constituentBlocks) {
        val profile = instance.constituentSkullProfiles[bLoc]
        boxDefs.add(BoxDef(bLoc.clone(), 1, 1, 1, bData, profile))
      }
    }

    if (boxDefs.isEmpty()) return

    // ルートボックスを選択（元の originLocation に最も近い/一致するもの）
    val rootBox = boxDefs.find { box ->
      val ox = instance.originLocation.blockX
      val oy = instance.originLocation.blockY
      val oz = instance.originLocation.blockZ
      ox >= box.loc.blockX && ox < box.loc.blockX + box.sizeX &&
      oy >= box.loc.blockY && oy < box.loc.blockY + box.sizeY &&
      oz >= box.loc.blockZ && oz < box.loc.blockZ + box.sizeZ
    } ?: boxDefs.first()

    val partBoxes = boxDefs.filter { it !== rootBox }

    fun spawnDisplayAndInteraction(
      box: BoxDef
    ): Triple<Display, org.bukkit.entity.Interaction, Pair<Quaternionf, Location>> {
      val isSingleBlock = (box.sizeX == 1 && box.sizeY == 1 && box.sizeZ == 1)
      val (disp, rightRot, spawnLoc) = if (isSingleBlock) {
        val (d, r) = createDisplayEntity(world, box.loc, box.blockData, box.skullProfile, instance.id)
        Triple(d, r, box.loc.clone())
      } else {
        val placement = calculateBoxPlacement(box, instance.worldPivotLocation)
        val blockDisplay = world.spawnEntity(placement.spawnLocation, EntityType.BLOCK_DISPLAY) as BlockDisplay
        blockDisplay.isPersistent = false
        blockDisplay.block = box.blockData
        blockDisplay.teleportDuration = 0
        blockDisplay.interpolationDuration = 0
        blockDisplay.interpolationDelay = 0
        blockDisplay.transformation = org.bukkit.util.Transformation(
          org.joml.Vector3f(0f, 0f, 0f),
          org.joml.Quaternionf(),
          org.joml.Vector3f(box.sizeX.toFloat(), box.sizeY.toFloat(), box.sizeZ.toFloat()),
          placement.rightRotation
        )
        blockDisplay.addScoreboardTag(TAG_ENTITY)
        blockDisplay.addScoreboardTag("$TAG_ID_PREFIX${instance.id}")
        Triple(blockDisplay, placement.rightRotation, placement.spawnLocation)
      }

      disp.isPersistent = false
      disp.teleportDuration = 0
      disp.interpolationDuration = 0
      disp.interpolationDelay = 0
      disp.addScoreboardTag(TAG_ENTITY)
      disp.addScoreboardTag("$TAG_ID_PREFIX${instance.id}")

      val interLoc = box.loc.clone().add(box.sizeX / 2.0, 0.0, box.sizeZ / 2.0)
      val inter = world.spawn(interLoc, org.bukkit.entity.Interaction::class.java) {
        it.isPersistent = false
        it.interactionWidth = maxOf(box.sizeX, box.sizeZ).toFloat()
        it.interactionHeight = box.sizeY.toFloat()
        it.isResponsive = true
        it.addScoreboardTag(TAG_ENTITY)
        it.addScoreboardTag("$TAG_ID_PREFIX${instance.id}")
      }

      return Triple(disp, inter, rightRot to spawnLoc)
    }

    // 4. ルートエンティティの更新
    val (rootDisp, rootInter, rootInfo) = spawnDisplayAndInteraction(rootBox)
    val (rootRightRot, rootSpawnLoc) = rootInfo
    instance.displayEntity = rootDisp
    instance.interactionEntity = rootInter
    instance.originalBlockData = rootBox.blockData
    instance.initialRightRotation = rootRightRot
    instance.skullProfile = rootBox.skullProfile
    instance.scale = Vector3f(rootBox.sizeX.toFloat(), rootBox.sizeY.toFloat(), rootBox.sizeZ.toFloat())
    instance.updateOrigin(rootSpawnLoc)

    entityIdToInstanceId[rootDisp.uniqueId] = instance.id
    entityIdToInstanceId[rootInter.uniqueId] = instance.id

    // 5. パーツエンティティの更新
    for (pb in partBoxes) {
      val (partDisp, partInter, partInfo) = spawnDisplayAndInteraction(pb)
      val (partRightRot, partSpawnLoc) = partInfo
      val partScale = Vector3f(pb.sizeX.toFloat(), pb.sizeY.toFloat(), pb.sizeZ.toFloat())
      instance.additionalParts.add(
        KarakuriInstance.BlockPart(
          displayEntity = partDisp,
          originLocation = partSpawnLoc,
          interactionEntity = partInter,
          originalBlockData = pb.blockData,
          initialRightRotation = partRightRot,
          skullProfile = pb.skullProfile,
          partScale = partScale
        )
      )
      entityIdToInstanceId[partDisp.uniqueId] = instance.id
      entityIdToInstanceId[partInter.uniqueId] = instance.id
    }

    // 6. 状態の復帰
    instance.isOptimized = optimize
    instance.optimizeNonFullBlocks = allowNonFull
    instance.worldPivotLocation = curWorldPivot
    val rootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
    val dx = (instance.worldPivotLocation.x - (instance.originLocation.x + rootOffset.x.toDouble())).toFloat()
    val dy = (instance.worldPivotLocation.y - (instance.originLocation.y + rootOffset.y.toDouble())).toFloat()
    val dz = (instance.worldPivotLocation.z - (instance.originLocation.z + rootOffset.z.toDouble())).toFloat()
    instance.pivotOffset = Vector3f(dx, dy, dz)

    // モーション種別に応じた安全な位置・姿勢復元
    val motion = instance.activeMotion
    val isRelativeMotion = (motion is io.oyasai.karakuri.motion.MinecartMotion ||
                            motion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                            motion is io.oyasai.karakuri.motion.ChainChildContraptionMotion)
    val isPureRotation = (motion is io.oyasai.karakuri.motion.DoorMotion ||
                          motion is io.oyasai.karakuri.motion.ContinuousSpinMotion ||
                          motion is io.oyasai.karakuri.motion.AngleRotationMotion ||
                          motion == null)

    if (isRelativeMotion) {
      // トロッコ連動や子構造は、次Tickで親構造やトロッコの現在位置へ正確に追従されるため、
      // ここでの個別テレポートはスキップし、位置競合やズレを防止する。
      instance.updateCurrentRotation(currentRot)
    } else if (isPureRotation) {
      // 純粋回転からくり（風車・水車・ドア・停止中など）は、原点位置でスポーンされた実体位置が100%正しい。
      if (motion is io.oyasai.karakuri.motion.ContinuousSpinMotion) {
        // ContinuousSpinMotion の場合、新しい直方体構成でモーションをシームレスに再開始
        motion.onStart(instance)
      } else {
        instance.applyRotation(currentRot, 0, 0, null)
      }
    } else {
      // 直線運動など平行移動を行うからくりのみ、変位を復元
      if (hasDelta) {
        val targetLoc = instance.originLocation.clone().add(deltaX, deltaY, deltaZ)
        instance.applySmoothTeleport(targetLoc, 0)
      }
      instance.applyRotation(currentRot, 0, 0, null)
    }

    // 幾何情報・構成設定をDBへ同期
    syncInstanceGeometry(instance)
    database?.updateInstanceFlags(instance)
  }

  fun tick(currentTick: Long) {
    val all = instances.values.toList()
    if (all.isEmpty()) return

    // 1. 無効エンティティのクリーンアップ
    for (instance in all) {
      if (instance.isDormant) continue
      if (!instance.isValid()) {
        entityIdToInstanceId.remove(instance.displayEntity.uniqueId)
        instance.interactionEntity?.let { entityIdToInstanceId.remove(it.uniqueId) }
        for (part in instance.additionalParts) {
          entityIdToInstanceId.remove(part.displayEntity.uniqueId)
          part.interactionEntity?.let { entityIdToInstanceId.remove(it.uniqueId) }
        }
        instances.remove(instance.id)
      }
    }

    // 2. 親構造・独立構造を先に tick（親が新しい位置・姿勢にテレポート完了）
    // 3. 子構造（ChildContraptionMotion, ChainChildContraptionMotion）を後に tick
    // これにより、子構造は常に親構造の最新フレーム座標を読み取り、トロッコ走行中等の1Tick追従遅れを100%根絶！
    val parents = mutableListOf<KarakuriInstance>()
    val children = mutableListOf<KarakuriInstance>()

    for (instance in instances.values) {
      if (instance.isDormant || !instance.isValid()) continue
      val motion = instance.activeMotion
      if (motion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
          motion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
        children.add(instance)
      } else {
        parents.add(instance)
      }
    }

    for (inst in parents) {
      inst.tick(currentTick)
      if (inst.activeMotion != null) {
        database?.markPoseDirty(inst)
      }
    }

    for (inst in children) {
      inst.tick(currentTick)
      if (inst.activeMotion != null) {
        database?.markPoseDirty(inst)
      }
    }
  }

  fun remove(id: String): Boolean {
    val instance = instances.remove(id) ?: return false
    entityIdToInstanceId.remove(instance.displayEntity.uniqueId)
    instance.interactionEntity?.let { entityIdToInstanceId.remove(it.uniqueId) }
    for (part in instance.additionalParts) {
      entityIdToInstanceId.remove(part.displayEntity.uniqueId)
      part.interactionEntity?.let { entityIdToInstanceId.remove(it.uniqueId) }
    }
    (instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion)?.let {
      if (it.minecart.isValid) {
        it.minecart.removeScoreboardTag(TAG_MINECART_CONTRAPTION)
        it.minecart.removeScoreboardTag("$TAG_MINECART_INSTANCE_PREFIX$id")
        restoreSpawnerCartToRideable(it.minecart)
      }
    }
    collisionEngine?.unregisterInstanceRiders(id)
    instance.remove()
    database?.deleteInstance(id)
    return true
  }

  fun clearAll(): Int {
    var removedCount = 0
    instances.values.forEach {
      it.remove()
      removedCount++
    }
    instances.clear()
    entityIdToInstanceId.clear()

    for (world in Bukkit.getWorlds()) {
      for (entity in world.getEntitiesByClass(Display::class.java)) {
        if (entity.scoreboardTags.contains(TAG_ENTITY)) {
          entity.remove()
          removedCount++
        }
      }
    }
    return removedCount
  }

  // =========================================================================
  // 永続化・復元・revert・休眠 (Dormant) 管理システム
  // =========================================================================

  /**
   * 指定された UUID が現在アクティブかつ管理対象の正規からくりエンティティかを判定
   */
  fun isLegitimateActiveEntity(uuid: java.util.UUID): Boolean {
    val instId = entityIdToInstanceId[uuid] ?: return false
    val inst = instances[instId] ?: return false
    if (inst.isDormant) return false
    if (inst.displayEntity.isValid && inst.displayEntity.uniqueId == uuid) return true
    if (inst.interactionEntity?.isValid == true && inst.interactionEntity?.uniqueId == uuid) return true
    return inst.additionalParts.any {
      (it.displayEntity.isValid && it.displayEntity.uniqueId == uuid) ||
      (it.interactionEntity?.isValid == true && it.interactionEntity?.uniqueId == uuid)
    }
  }

  /**
   * 起動時および手動実行時の旧版・孤立・重複残骸エンティティのクリーンアップ
   */
  fun cleanOldLeftoverEntities(): Int {
    var cleanedEntities = 0
    for (world in Bukkit.getWorlds()) {
      for (entity in world.entities) {
        val tags = entity.scoreboardTags
        val hasKarakuriTag = tags.contains(TAG_ENTITY) || tags.any { it.startsWith(TAG_ID_PREFIX) }
        val isKarakuriDisplay = hasKarakuriTag && (entity is Display || entity is org.bukkit.entity.Interaction)
        val isFloorGhast = tags.contains(io.oyasai.karakuri.collision.CollisionEngine.TAG_FLOOR_GHAST)

        if (isKarakuriDisplay || isFloorGhast) {
          if (!isLegitimateActiveEntity(entity.uniqueId)) {
            entity.remove()
            entityIdToInstanceId.remove(entity.uniqueId)
            cleanedEntities++
          }
        }
      }
    }
    if (cleanedEntities > 0) {
      Bukkit.getLogger().info("[Karakuri] 旧版または孤立した残骸エンティティを $cleanedEntities 体掃除しました。")
    }
    return cleanedEntities
  }

  /**
   * 起動時に SQLite DB から全からくりを読み込み、自己修復・再構築を行う
   */
  fun loadAndReconcileAll() {
    cleanOldLeftoverEntities()

    val db = database ?: return
    val records = db.loadAllInstances()
    if (records.isEmpty()) return

    // 親構造を先に復元し、子構造を後に復元（親ID参照を保証）
    val sortedRecords = records.sortedBy { r ->
      if (r.motionType == "CHILD" || r.motionType == "CHAIN_CHILD") 1 else 0
    }

    for (record in sortedRecords) {
      val world = Bukkit.getWorld(record.worldName)
      if (world == null) {
        Bukkit.getLogger().warning("[Karakuri] ワールド '${record.worldName}' が見つからないため、からくり [${record.id}] をスキップしました。")
        continue
      }

      // トロッコや構成ブロックが存在するチャンクを確実に先読みロード
      world.loadChunk(record.origin.blockX shr 4, record.origin.blockZ shr 4, true)
      world.loadChunk(record.currentPos.blockX shr 4, record.currentPos.blockZ shr 4, true)
      world.loadChunk(record.worldPivot.blockX shr 4, record.worldPivot.blockZ shr 4, true)
      for (bLoc in record.blocks.keys) {
        world.loadChunk(bLoc.blockX shr 4, bLoc.blockZ shr 4, true)
      }

      when (record.state) {
        "converting" -> {
          // 変換の途中で落ちた場合: 元ブロックが残っていれば未完了として削除、消えていれば完了として active 化
          val hasOriginalBlocks = record.blocks.keys.any { loc -> !world.getBlockAt(loc).type.isAir }
          if (hasOriginalBlocks) {
            db.deleteInstance(record.id)
            Bukkit.getLogger().info("[Karakuri] 変換未完了の残存データ [${record.id}] を安全に削除しました。")
          } else {
            db.markInstanceActive(record.id)
            instantiateAndRestore(record, world)
          }
        }
        "reverting" -> {
          // revert の途中で落ちた場合: revert を最初から再開して完了させる
          val dummyInst = instantiateLogicalOnly(record, world)
          instances[record.id] = dummyInst
          revert(record.id)
        }
        "active" -> {
          instantiateAndRestore(record, world)
        }
      }
    }

    // ユーザー指示: 既存のからくりで 256 RPM を超えているものがあれば 256 RPM にクランプして保存
    clampAllInstanceRpmsToMax256()
  }

  /**
   * 全インスタンスの回転速度 (RPM) を検査し、256 RPM を超えているものを 256 RPM にクランプして DB 保存する
   */
  fun clampAllInstanceRpmsToMax256() {
    for (instance in instances.values) {
      var changed = false
      when (val m = instance.activeMotion) {
        is io.oyasai.karakuri.motion.ContinuousSpinMotion -> {
          if (m.rpm > 256.0f) {
            m.rpm = 256.0f
            changed = true
          }
        }
        is io.oyasai.karakuri.motion.DoorMotion -> {
          if (m.rpm > 256.0f) {
            m.rpm = 256.0f
            changed = true
          }
        }
        is io.oyasai.karakuri.motion.MinecartMotion -> {
          if (kotlin.math.abs(m.rpm) > 256.0f) {
            m.rpm = m.rpm.coerceIn(-256.0f, 256.0f)
            changed = true
          }
        }
        is io.oyasai.karakuri.motion.ChildContraptionMotion -> {
          if (m.rpm > 256.0f) {
            m.rpm = 256.0f
            changed = true
          }
        }
        is io.oyasai.karakuri.motion.ChainChildContraptionMotion -> {
          if (m.rpm > 256.0f) {
            m.rpm = 256.0f
            changed = true
          }
        }
        else -> {}
      }

      val cfg = io.oyasai.karakuri.gui.KarakuriProgramGUI.programConfigs[instance.id]
      if (cfg != null) {
        var cfgChanged = false
        for (step in cfg.steps) {
          if (step.spinRpm > 256.0f) {
            step.spinRpm = 256.0f
            cfgChanged = true
          } else if (step.spinRpm < -256.0f) {
            step.spinRpm = -256.0f
            cfgChanged = true
          }
          if (step.speedStartRpm > 256.0f) {
            step.speedStartRpm = 256.0f
            cfgChanged = true
          }
          if (step.speedEndRpm > 256.0f) {
            step.speedEndRpm = 256.0f
            cfgChanged = true
          }
        }
        if (cfgChanged) {
          val actions = cfg.steps.mapNotNull { it.toAction() }
          if (actions.isNotEmpty() && instance.activeMotion is io.oyasai.karakuri.motion.ProgramMotion) {
            instance.setMotion(io.oyasai.karakuri.motion.ProgramMotion(actions, cfg.loopMode))
          }
          changed = true
        }
      }

      if (changed) {
        saveInstanceMotion(instance)
      }
    }
  }

  private fun instantiateLogicalOnly(
    record: io.oyasai.karakuri.persistence.KarakuriDatabase.StoredInstanceRecord,
    world: org.bukkit.World,
  ): KarakuriInstance {
    val safeSpawn = world.spawnLocation
    val dummyDisplay = world.spawnEntity(safeSpawn, EntityType.BLOCK_DISPLAY) as BlockDisplay
    dummyDisplay.isPersistent = false
    dummyDisplay.remove() // メモリ保持用のダミー

    val inst = KarakuriInstance(
      id = record.id,
      displayEntity = dummyDisplay,
      baseLocation = record.origin.clone(),
      additionalParts = mutableListOf(),
      interactionEntity = null,
      originalBlockData = record.blocks[record.origin] ?: Material.STONE.createBlockData(),
      initialRightRotation = record.initialRightRotation,
      skullProfile = record.skullProfiles[record.origin]
    )
    inst.updateOrigin(record.origin)
    inst.worldPivotLocation = record.worldPivot
    inst.pivotOffset = record.pivotOffset
    inst.scale = record.scale
    inst.isOptimized = record.isOptimized
    inst.optimizeNonFullBlocks = record.optimizeNonFull
    inst.isImmovable = record.isImmovable
    inst.constituentBlocks.putAll(record.blocks)
    inst.constituentSkullProfiles.putAll(record.skullProfiles)
    inst.updateCurrentRotation(record.currentRotation)
    database?.loadRedstoneLinks(inst)

    return inst
  }

  private fun instantiateAndRestore(
    record: io.oyasai.karakuri.persistence.KarakuriDatabase.StoredInstanceRecord,
    world: org.bukkit.World,
  ) {
    val inst = instantiateLogicalOnly(record, world)
    instances[record.id] = inst

    if (areAllOccupiedChunksLoaded(inst)) {
      restoreEntities(inst, record)

      // 実体エンティティが生成された後にモーションを適用・同期（トロッコや親ピストン位置への正確なスナップ）
      if (record.motionType != null && record.motionData != null) {
        io.oyasai.karakuri.persistence.KarakuriMotionSerializer.deserializeAndApply(inst, record.motionType, record.motionData)
      } else {
        inst.setMotion(io.oyasai.karakuri.motion.DoorMotion(), saveToDb = false)
      }

      inst.isDormant = false
      Bukkit.getLogger().info("[Karakuri] からくり [${record.id}] を保存姿勢・動作モードで正常に再構築・復元しました。")
    } else {
      inst.isDormant = true
      Bukkit.getLogger().info("[Karakuri] からくり [${record.id}] のチャンクが未ロードのため休眠状態で保持しました。")
    }
  }

  /**
   * DBレコードから DisplayEntity / Interaction を再生成して保存姿勢を復元
   */
  fun restoreEntities(
    instance: KarakuriInstance,
    record: io.oyasai.karakuri.persistence.KarakuriDatabase.StoredInstanceRecord,
  ) {
    val world = instance.originLocation.world

    // 1. 既存のメモリ参照エンティティをワールドおよび追跡マップから完全削除
    entityIdToInstanceId.remove(instance.displayEntity.uniqueId)
    if (instance.displayEntity.isValid) instance.displayEntity.remove()
    instance.interactionEntity?.let {
      entityIdToInstanceId.remove(it.uniqueId)
      if (it.isValid) it.remove()
    }
    for (part in instance.additionalParts) {
      entityIdToInstanceId.remove(part.displayEntity.uniqueId)
      if (part.displayEntity.isValid) part.displayEntity.remove()
      part.interactionEntity?.let {
        entityIdToInstanceId.remove(it.uniqueId)
        if (it.isValid) it.remove()
      }
    }
    instance.additionalParts.clear()

    // 2. このからくりに関連する古い残骸・二重生成エンティティをピボット周囲および構成ブロック位置から一掃
    val pivotLoc = instance.worldPivotLocation
    val maxSpan = maxOf(instance.scale.x, instance.scale.y, instance.scale.z, 16f).toDouble() + 16.0
    val targetTag = "$TAG_ID_PREFIX${instance.id}"
    for (nearby in world.getNearbyEntities(pivotLoc, maxSpan, maxSpan, maxSpan)) {
      if (nearby.scoreboardTags.contains(targetTag)) {
        nearby.remove()
        entityIdToInstanceId.remove(nearby.uniqueId)
      }
    }

    // 最適化設定に基づきエンティティ定義を作成
    val boxDefs = mutableListOf<BoxDef>()
    if (instance.isOptimized) {
      val posMap = instance.constituentBlocks.mapKeys { (loc, _) -> BlockPos(loc.blockX, loc.blockY, loc.blockZ) }
      val packed = GreedyBoxPacker.pack(posMap, instance.optimizeNonFullBlocks)
      for (mb in packed) {
        val bLoc = Location(world, mb.originX.toDouble(), mb.originY.toDouble(), mb.originZ.toDouble(), 0f, 0f)
        val profile = instance.constituentSkullProfiles[bLoc]
        boxDefs.add(BoxDef(bLoc, mb.sizeX, mb.sizeY, mb.sizeZ, mb.blockData, profile))
      }
    } else {
      for ((bLoc, bData) in instance.constituentBlocks) {
        val profile = instance.constituentSkullProfiles[bLoc]
        boxDefs.add(BoxDef(bLoc.clone(), 1, 1, 1, bData, profile))
      }
    }

    if (boxDefs.isEmpty()) return

    val rootBox: BoxDef = boxDefs.find { box: BoxDef ->
      val ox = instance.originLocation.blockX
      val oy = instance.originLocation.blockY
      val oz = instance.originLocation.blockZ
      ox >= box.loc.blockX && ox < box.loc.blockX + box.sizeX &&
      oy >= box.loc.blockY && oy < box.loc.blockY + box.sizeY &&
      oz >= box.loc.blockZ && oz < box.loc.blockZ + box.sizeZ
    } ?: boxDefs.first()

    val partBoxes: List<BoxDef> = boxDefs.filter { it !== rootBox }

    fun spawnEntityBox(
      box: BoxDef
    ): Triple<Display, org.bukkit.entity.Interaction, Pair<Quaternionf, Location>> {
      val isSingleBlock = (box.sizeX == 1 && box.sizeY == 1 && box.sizeZ == 1)
      val (disp, rightRot, spawnLoc) = if (isSingleBlock) {
        val (d, r) = createDisplayEntity(world, box.loc, box.blockData, box.skullProfile, instance.id)
        Triple(d, r, box.loc.clone())
      } else {
        val placement = calculateBoxPlacement(box, instance.worldPivotLocation)
        val blockDisplay = world.spawnEntity(placement.spawnLocation, EntityType.BLOCK_DISPLAY) as BlockDisplay
        blockDisplay.isPersistent = false
        blockDisplay.block = box.blockData
        blockDisplay.teleportDuration = 0
        blockDisplay.interpolationDuration = 0
        blockDisplay.interpolationDelay = 0
        blockDisplay.transformation = org.bukkit.util.Transformation(
          org.joml.Vector3f(0f, 0f, 0f),
          org.joml.Quaternionf(),
          org.joml.Vector3f(box.sizeX.toFloat(), box.sizeY.toFloat(), box.sizeZ.toFloat()),
          placement.rightRotation
        )
        blockDisplay.addScoreboardTag(TAG_ENTITY)
        blockDisplay.addScoreboardTag("$TAG_ID_PREFIX${instance.id}")
        Triple(blockDisplay, placement.rightRotation, placement.spawnLocation)
      }

      disp.teleportDuration = 0
      disp.interpolationDuration = 0
      disp.interpolationDelay = 0
      disp.addScoreboardTag(TAG_ENTITY)
      disp.addScoreboardTag("$TAG_ID_PREFIX${instance.id}")

      val interLoc = box.loc.clone().add(box.sizeX / 2.0, 0.0, box.sizeZ / 2.0)
      val inter = world.spawn(interLoc, org.bukkit.entity.Interaction::class.java) {
        it.isPersistent = false
        it.interactionWidth = maxOf(box.sizeX, box.sizeZ).toFloat()
        it.interactionHeight = box.sizeY.toFloat()
        it.isResponsive = true
        it.addScoreboardTag(TAG_ENTITY)
        it.addScoreboardTag("$TAG_ID_PREFIX${instance.id}")
      }

      return Triple(disp, inter, rightRot to spawnLoc)
    }

    // ルートエンティティ生成
    val (rootDisp, rootInter, rootInfo) = spawnEntityBox(rootBox)
    val (rootRightRot, rootSpawnLoc) = rootInfo
    instance.displayEntity = rootDisp
    instance.interactionEntity = rootInter
    instance.originalBlockData = rootBox.blockData
    instance.initialRightRotation = rootRightRot
    instance.skullProfile = rootBox.skullProfile
    instance.scale = Vector3f(rootBox.sizeX.toFloat(), rootBox.sizeY.toFloat(), rootBox.sizeZ.toFloat())
    instance.updateOrigin(rootSpawnLoc)
    val rootOffset = instance.getBaseEntityOffset(rootDisp, instance.originalBlockData)
    val dx = (instance.worldPivotLocation.x - (instance.originLocation.x + rootOffset.x.toDouble())).toFloat()
    val dy = (instance.worldPivotLocation.y - (instance.originLocation.y + rootOffset.y.toDouble())).toFloat()
    val dz = (instance.worldPivotLocation.z - (instance.originLocation.z + rootOffset.z.toDouble())).toFloat()
    instance.pivotOffset = Vector3f(dx, dy, dz)

    entityIdToInstanceId[rootDisp.uniqueId] = instance.id
    entityIdToInstanceId[rootInter.uniqueId] = instance.id

    // パーツエンティティ生成
    for (pb in partBoxes) {
      val (partDisp, partInter, partInfo) = spawnEntityBox(pb)
      val (partRightRot, partSpawnLoc) = partInfo
      val partScale = Vector3f(pb.sizeX.toFloat(), pb.sizeY.toFloat(), pb.sizeZ.toFloat())
      instance.additionalParts.add(
        KarakuriInstance.BlockPart(
          displayEntity = partDisp,
          originLocation = partSpawnLoc,
          interactionEntity = partInter,
          originalBlockData = pb.blockData,
          initialRightRotation = partRightRot,
          skullProfile = pb.skullProfile,
          partScale = partScale
        )
      )
      entityIdToInstanceId[partDisp.uniqueId] = instance.id
      entityIdToInstanceId[partInter.uniqueId] = instance.id
    }

    // モーション種別に応じた安全な位置・姿勢復元
    val mType = record.motionType
    val isRelativeMotion = (mType == "MINECART" || mType == "CHILD" || mType == "CHAIN_CHILD")
    val isPureRotation = (mType == "DOOR" || mType == "CONTINUOUS_SPIN" || mType == "ANGLE_ROTATION" || mType == null)

    if (isRelativeMotion) {
      // トロッコ連動や子構造は、直後の deserializeAndApply で親構造やトロッコの現在位置へ正確にスナップされるため、
      // ここでの個別テレポートはスキップし、位置競合やズレを防止する。
      instance.updateCurrentRotation(record.currentRotation)
    } else if (isPureRotation) {
      // 純粋回転からくり（風車・水車・ドア・停止中など）は、原点位置でスポーンされた実体位置が100%正しい。
      // 平行移動テレポートは一切行わず、保存された回転姿勢のみを中心に適用することで、ピボットのズレや吹き飛びを完全防止する。
      instance.applyRotation(record.currentRotation, 0, 0, null)
    } else {
      // 直線運動など平行移動を行うからくりのみ、保存位置への変位を復元
      val rootInitLoc = rootDisp.location
      val deltaX = record.currentPos.x - rootInitLoc.x
      val deltaY = record.currentPos.y - rootInitLoc.y
      val deltaZ = record.currentPos.z - rootInitLoc.z
      val distSq = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ

      if (distSq > 0.01) {
        val targetLoc = instance.originLocation.clone().add(deltaX, deltaY, deltaZ)
        instance.applySmoothTeleport(targetLoc, 0)
      }
      instance.applyRotation(record.currentRotation, 0, 0, null)
    }
  }

  /**
   * 占有する全チャンクが読み込み済みか判定
   */
  fun areAllOccupiedChunksLoaded(instance: KarakuriInstance): Boolean {
    val world = instance.originLocation.world
    for (chunkKey in instance.getOccupiedChunkKeys()) {
      val cx = (chunkKey and 0xFFFFFFFFL).toInt()
      val cz = (chunkKey ushr 32).toInt()
      if (!world.isChunkLoaded(cx, cz)) {
        return false
      }
    }
    return true
  }

  /**
   * 休眠状態（Dormant）の切替
   */
  fun setDormant(instance: KarakuriInstance, dormant: Boolean) {
    if (instance.isDormant == dormant) return
    instance.isDormant = dormant

    if (dormant) {
      // 休眠化: 表示・Interactionを撤去し、プレイヤーを安全に降車
      if (instance.displayEntity.isValid) {
        instance.lastKnownLocation = instance.displayEntity.location.clone()
        database?.markPoseDirty(instance)
      }
      collisionEngine?.unregisterInstanceRiders(instance.id)
      entityIdToInstanceId.remove(instance.displayEntity.uniqueId)
      if (instance.displayEntity.isValid) instance.displayEntity.remove()
      instance.interactionEntity?.let {
        entityIdToInstanceId.remove(it.uniqueId)
        if (it.isValid) it.remove()
      }
      for (part in instance.additionalParts) {
        entityIdToInstanceId.remove(part.displayEntity.uniqueId)
        if (part.displayEntity.isValid) part.displayEntity.remove()
        part.interactionEntity?.let {
          entityIdToInstanceId.remove(it.uniqueId)
          if (it.isValid) it.remove()
        }
      }
      instance.additionalParts.clear()
    } else {
      // 復帰化: 現在姿勢スナップショットから再構築
      val curPos = instance.lastKnownLocation ?: instance.baseLocation.clone()
      val curRot = Quaternionf(instance.currentRotation)
      val (mType, mData) = io.oyasai.karakuri.persistence.KarakuriMotionSerializer.serialize(instance)
      val record = io.oyasai.karakuri.persistence.KarakuriDatabase.StoredInstanceRecord(
          id = instance.id,
          worldName = instance.originLocation.world.name,
          origin = instance.originLocation.clone(),
          worldPivot = instance.worldPivotLocation.clone(),
          pivotOffset = Vector3f(instance.pivotOffset),
          scale = Vector3f(instance.scale),
          initialRightRotation = Quaternionf(instance.initialRightRotation),
          currentPos = curPos,
          currentRotation = curRot,
          isOptimized = instance.isOptimized,
          optimizeNonFull = instance.optimizeNonFullBlocks,
          isImmovable = instance.isImmovable,
          state = "active",
          motionType = mType,
          motionData = mData,
          blocks = instance.constituentBlocks,
          skullProfiles = instance.constituentSkullProfiles
        )
      restoreEntities(instance, record)
      if (instance.activeMotion == null && record.motionType != null && record.motionData != null) {
        io.oyasai.karakuri.persistence.KarakuriMotionSerializer.deserializeAndApply(instance, record.motionType, record.motionData)
      } else {
        (instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion)?.requestUpdate()
        (instance.activeMotion as? io.oyasai.karakuri.motion.ChildContraptionMotion)?.requestSnap()
        (instance.activeMotion as? io.oyasai.karakuri.motion.ChainChildContraptionMotion)?.requestSnap()
      }
    }
  }

  fun handleChunkUnload(chunk: org.bukkit.Chunk) {
    val chunkKey = chunk.chunkKey
    for (instance in instances.values) {
      if (!instance.isDormant && instance.getOccupiedChunkKeys().contains(chunkKey)) {
        setDormant(instance, true)
      }
    }
  }

  fun handleChunkLoad(chunk: org.bukkit.Chunk) {
    val chunkKey = chunk.chunkKey
    for (instance in instances.values) {
      if (instance.isDormant && instance.getOccupiedChunkKeys().contains(chunkKey)) {
        if (areAllOccupiedChunksLoaded(instance)) {
          setDormant(instance, false)
        }
      }
    }
  }

  fun handleEntitiesLoad(entities: List<org.bukkit.entity.Entity>) {
    for (entity in entities) {
      val tags = entity.scoreboardTags
      val hasKarakuriTag = tags.contains(TAG_ENTITY) || tags.any { it.startsWith(TAG_ID_PREFIX) }
      val isKarakuriDisplay = hasKarakuriTag && (entity is Display || entity is org.bukkit.entity.Interaction)
      val isFloorGhast = tags.contains(io.oyasai.karakuri.collision.CollisionEngine.TAG_FLOOR_GHAST)

      if (isKarakuriDisplay || isFloorGhast) {
        if (!isLegitimateActiveEntity(entity.uniqueId)) {
          entity.remove()
          entityIdToInstanceId.remove(entity.uniqueId)
        }
      } else if (entity is org.bukkit.entity.Minecart && tags.contains(TAG_MINECART_CONTRAPTION)) {
        val cartIdTag = tags.firstOrNull { it.startsWith(TAG_MINECART_INSTANCE_PREFIX) }
        val id = cartIdTag?.removePrefix(TAG_MINECART_INSTANCE_PREFIX)
        val inst = if (id != null) instances[id] else null
        if (inst != null) {
          val currentMotion = inst.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion
          if (currentMotion == null || !currentMotion.minecart.isValid || currentMotion.minecart.uniqueId != entity.uniqueId) {
            // トロッコが遅延ロードされたため、からくりをこのトロッコに即時マウント・再接続
            val record = database?.loadInstance(inst.id)
            if (record?.motionType == "MINECART" && record.motionData != null) {
              io.oyasai.karakuri.persistence.KarakuriMotionSerializer.deserializeAndApply(inst, record.motionType, record.motionData)
              org.bukkit.Bukkit.getLogger().info("[Karakuri] チャンクロードされたトロッコ [${entity.uniqueId}] にからくり [${inst.id}] を再マウントしました。")
            }
          }
        }
      }
    }
  }

  /**
   * からくりを安全に元の建築ブロックに戻す（revert）
   */
  fun revert(id: String, player: org.bukkit.entity.Player? = null): Boolean {
    val instance = instances[id] ?: return false
    val db = database

    // 親構造に接続されていた子構造の独立化＆初期位置復元
    val childInstances = instances.values.filter { other ->
      val m = other.activeMotion
      (m is io.oyasai.karakuri.motion.ChildContraptionMotion && m.parentId == id) ||
      (m is io.oyasai.karakuri.motion.ChainChildContraptionMotion && m.parentId == id)
    }

    for (child in childInstances) {
      // 1. 初期位置・初期姿勢にリセット（親の初期位置・ピストン位置準拠の原点座標に戻る）
      child.resetToBase(stopMotion = true)

      // 2. ピボット中心を自身のルートブロック中心に戻す
      child.worldPivotLocation = Location(
        child.originLocation.world,
        child.originLocation.blockX + 0.5,
        child.originLocation.blockY + 0.5,
        child.originLocation.blockZ + 0.5
      )
      child.pivotOffset = if (child.displayEntity is org.bukkit.entity.ItemDisplay) Vector3f(0f, 0f, 0f) else Vector3f(0.5f, 0.5f, 0.5f)

      // 3. 子構造モーションを解除し、ただの独立からくり（デフォルトの DoorMotion）に切り替える
      child.setMotion(io.oyasai.karakuri.motion.DoorMotion(), saveToDb = true)
      syncInstanceGeometry(child)
      database?.updateInstanceGeometry(child)

      player?.sendMessage(net.kyori.adventure.text.Component.text("✦ 親からくり撤去に伴い、子構造 [${child.id}] を初期位置に戻し、通常の独立からくりに変換しました！", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
    }

    var restoredCount = 0
    var conflictCount = 0
    val world = instance.originLocation.world

    for ((loc, blockData) in instance.constituentBlocks) {
      val currentBlock = world.getBlockAt(loc)
      if (currentBlock.type.isAir) {
        currentBlock.setBlockData(blockData, false)
        val profile = instance.constituentSkullProfiles[loc]
        if (profile != null && currentBlock.state is org.bukkit.block.Skull) {
          val skull = currentBlock.state as org.bukkit.block.Skull
          skull.setPlayerProfile(profile)
          skull.update(true, false)
        }
        db?.updateBlockRevertStatus(id, loc.blockX, loc.blockY, loc.blockZ, "restored")
        restoredCount++
      } else if (currentBlock.blockData == blockData) {
        db?.updateBlockRevertStatus(id, loc.blockX, loc.blockY, loc.blockZ, "restored")
        restoredCount++
      } else {
        // 別のブロックが置かれている（競合）ため上書き破壊せずスキップ
        db?.updateBlockRevertStatus(id, loc.blockX, loc.blockY, loc.blockZ, "conflict")
        conflictCount++
      }
    }

    // 乗客の安全降車とエンティティ撤去
    collisionEngine?.unregisterInstanceRiders(id)
    (instance.activeMotion as? io.oyasai.karakuri.motion.MinecartMotion)?.let {
      if (it.minecart.isValid) {
        it.minecart.removeScoreboardTag(TAG_MINECART_CONTRAPTION)
        it.minecart.removeScoreboardTag("$TAG_MINECART_INSTANCE_PREFIX$id")
        restoreSpawnerCartToRideable(it.minecart)
      }
    }

    instance.remove()
    instances.remove(id)
    entityIdToInstanceId.remove(instance.displayEntity.uniqueId)
    instance.interactionEntity?.let { entityIdToInstanceId.remove(it.uniqueId) }
    for (part in instance.additionalParts) {
      entityIdToInstanceId.remove(part.displayEntity.uniqueId)
      part.interactionEntity?.let { entityIdToInstanceId.remove(it.uniqueId) }
    }

    db?.deleteInstance(id)

    if (player != null) {
      if (conflictCount == 0) {
        player.sendMessage(net.kyori.adventure.text.Component.text("✦ からくり [$id] を元の建築ブロックに戻しました！ ($restoredCount ブロック復元)", net.kyori.adventure.text.format.NamedTextColor.GREEN))
      } else {
        player.sendMessage(net.kyori.adventure.text.Component.text("⚠️ からくり [$id] を復元しましたが、$conflictCount 箇所のブロックが競合したため保護・スキップされました。 ($restoredCount ブロック復元)", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
      }
      player.playSound(instance.originLocation, org.bukkit.Sound.BLOCK_ANVIL_USE, 1.0f, 1.4f)
    }
    return true
  }

  /**
   * 接着建築物を非同期にDB先行確定してからくり化する
   */
  fun spawnCompositeAsync(
    blocks: List<Block>,
    pivotBlock: Block,
    player: org.bukkit.entity.Player? = null,
    customId: String? = null,
    onSuccess: (KarakuriInstance) -> Unit = {},
  ) {
    val db = database
    if (db == null) {
      val inst = spawnComposite(blocks, pivotBlock, customId)
      onSuccess(inst)
      return
    }

    val id = customId ?: "kk-${idCounter.getAndIncrement()}"
    val world = pivotBlock.world

    val blockDataMap = mutableMapOf<Location, BlockData>()
    val skullProfileMap = mutableMapOf<Location, com.destroystokyo.paper.profile.PlayerProfile>()

    for (b in blocks) {
      val loc = Location(world, b.x.toDouble(), b.y.toDouble(), b.z.toDouble())
      val data = b.blockData.clone()
      blockDataMap[loc] = data
      val profile = (b.state as? org.bukkit.block.Skull)?.playerProfile
      if (profile != null) {
        skullProfileMap[loc] = profile
      }
    }

    val pivotLoc = Location(world, pivotBlock.x.toDouble(), pivotBlock.y.toDouble(), pivotBlock.z.toDouble())
    val pivotData = blockDataMap[pivotLoc] ?: pivotBlock.blockData.clone()
    val isPivotSkull = pivotData.material == Material.PLAYER_HEAD || pivotData.material == Material.PLAYER_WALL_HEAD
    val pivotProfile = skullProfileMap[pivotLoc]

    val defaultDoor = io.oyasai.karakuri.motion.DoorMotion()
    val (defaultType, defaultData) = io.oyasai.karakuri.persistence.KarakuriMotionSerializer.serializeMotion(defaultDoor, id)

    val initialSpawnPos = if (isPivotSkull) {
      val wallOffset = getPlayerWallHeadOffset(pivotData)
      pivotLoc.clone().add(0.5 + wallOffset.x, 0.5 + wallOffset.y, 0.5 + wallOffset.z)
    } else {
      pivotLoc.clone()
    }

    db.saveNewInstanceConvertingRaw(
      id = id,
      worldName = world.name,
      origin = pivotLoc,
      worldPivot = pivotLoc.clone().add(0.5, 0.5, 0.5),
      pivotOffset = if (isPivotSkull) Vector3f(0f, 0f, 0f) else Vector3f(0.5f, 0.5f, 0.5f),
      scale = Vector3f(1f, 1f, 1f),
      initialRightRotation = calculateInitialRightRotation(pivotData),
      currentPos = initialSpawnPos,
      currentRotation = Quaternionf(),
      isOptimized = false,
      optimizeNonFull = false,
      isImmovable = false,
      blocksSnapshot = blockDataMap,
      skullsSnapshot = skullProfileMap,
      motionType = defaultType,
      motionData = defaultData
    ) { success ->
      if (!success) {
        player?.sendMessage(net.kyori.adventure.text.Component.text("⚠️ データベースへの保存に失敗したため、変換を中断しました。", net.kyori.adventure.text.format.NamedTextColor.RED))
        return@saveNewInstanceConvertingRaw
      }

      // 待機中にブロックが変更されていないか再照合
      var isConsistent = true
      for (b in blocks) {
        val loc = Location(world, b.x.toDouble(), b.y.toDouble(), b.z.toDouble())
        val expected = blockDataMap[loc]
        if (b.blockData != expected) {
          isConsistent = false
          break
        }
      }

      if (!isConsistent) {
        db.deleteInstance(id)
        player?.sendMessage(net.kyori.adventure.text.Component.text("⚠️ 変換待機中にブロックが変更されたため、からくり化を中止しました。", net.kyori.adventure.text.format.NamedTextColor.RED))
        return@saveNewInstanceConvertingRaw
      }

      // 検証成功: ブロックをAIR化し、エンティティを生成
      val instance = spawnComposite(blocks, pivotBlock, id)
      db.markInstanceActive(id)
      db.updateInstanceGeometry(instance)
      onSuccess(instance)
    }
  }

  /**
   * からくりを手動作動（素手Shift右クリックまたはRSランプ点灯）
   */
  fun triggerManual(player: org.bukkit.entity.Player?, targetKarakuri: KarakuriInstance) {
    val motion = targetKarakuri.activeMotion
    val loc = targetKarakuri.displayEntity.location

    when (motion) {
      is io.oyasai.karakuri.motion.DoorMotion -> {
        motion.toggle(targetKarakuri, isManual = true)
        player?.sendActionBar(net.kyori.adventure.text.Component.text(if (motion.isOpened) "🚪 ドアを開けました" else "🚪 ドアを閉めました", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
      }
      is io.oyasai.karakuri.motion.IntegratedLinearMotion -> {
        val result = motion.toggle(targetKarakuri)
        when (result) {
          io.oyasai.karakuri.motion.IntegratedLinearMotion.ToggleResult.STARTED -> {
            KarakuriSounds.playManual(loc)
            player?.sendActionBar(net.kyori.adventure.text.Component.text("▶ 直線運動を開始しました！", net.kyori.adventure.text.format.NamedTextColor.GREEN))
          }
          io.oyasai.karakuri.motion.IntegratedLinearMotion.ToggleResult.PAUSED -> {
            player?.sendActionBar(net.kyori.adventure.text.Component.text("⏸️ 直線運動を一時停止しました", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
          }
          io.oyasai.karakuri.motion.IntegratedLinearMotion.ToggleResult.RESUMED -> {
            KarakuriSounds.playManual(loc)
            player?.sendActionBar(net.kyori.adventure.text.Component.text("▶ 直線運動を再開しました", net.kyori.adventure.text.format.NamedTextColor.AQUA))
          }
          io.oyasai.karakuri.motion.IntegratedLinearMotion.ToggleResult.TOGGLED -> {
            KarakuriSounds.playManual(loc)
            player?.sendActionBar(net.kyori.adventure.text.Component.text("↔ 直線運動を作動しました！", net.kyori.adventure.text.format.NamedTextColor.AQUA))
          }
        }
      }
      is io.oyasai.karakuri.motion.ProgramMotion -> {
        val result = motion.toggle(targetKarakuri)
        when (result) {
          io.oyasai.karakuri.motion.ProgramMotion.ProgramToggleResult.RESET_TO_BASE -> {
            player?.sendActionBar(net.kyori.adventure.text.Component.text("⏮️ 初期位置・初期姿勢にリセットしました（素手クリックで再生開始）", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
            KarakuriSounds.playManual(loc)
          }
          io.oyasai.karakuri.motion.ProgramMotion.ProgramToggleResult.STARTED -> {
            player?.sendActionBar(net.kyori.adventure.text.Component.text("▶ プログラムの再生を開始しました！", net.kyori.adventure.text.format.NamedTextColor.GREEN))
            KarakuriSounds.playManual(loc)
          }
          io.oyasai.karakuri.motion.ProgramMotion.ProgramToggleResult.PAUSED -> {
            player?.sendActionBar(net.kyori.adventure.text.Component.text("⏸️ プログラムの動作を一時停止しました", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
          }
          io.oyasai.karakuri.motion.ProgramMotion.ProgramToggleResult.RESUMED -> {
            player?.sendActionBar(net.kyori.adventure.text.Component.text("▶ プログラムの動作を再開しました", net.kyori.adventure.text.format.NamedTextColor.AQUA))
            KarakuriSounds.playManual(loc)
          }
          io.oyasai.karakuri.motion.ProgramMotion.ProgramToggleResult.REVERSED -> {
            player?.sendActionBar(net.kyori.adventure.text.Component.text("🔄 プログラムの進行方向を反転しました", net.kyori.adventure.text.format.NamedTextColor.AQUA))
            KarakuriSounds.playManual(loc)
          }
        }
      }
      is io.oyasai.karakuri.motion.ContinuousSpinMotion -> {
        val running = motion.toggle(targetKarakuri)
        if (running) {
          KarakuriSounds.playManual(loc)
          player?.sendActionBar(net.kyori.adventure.text.Component.text("▶ 回転を作動しました！", net.kyori.adventure.text.format.NamedTextColor.GREEN))
        } else {
          player?.sendActionBar(net.kyori.adventure.text.Component.text("⏸️ 回転を一時停止しました", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
        }
      }
      is io.oyasai.karakuri.motion.ChildContraptionMotion -> {
        motion.isSpinActive = !motion.isSpinActive
        if (!motion.isSpinActive) motion.currentSpinAngleRad = 0.0
        motion.requestSnap()
        if (motion.isSpinActive) {
          KarakuriSounds.playManual(loc)
          player?.sendActionBar(net.kyori.adventure.text.Component.text("▶ 子構造の自転を作動しました！", net.kyori.adventure.text.format.NamedTextColor.GREEN))
        } else {
          player?.sendActionBar(net.kyori.adventure.text.Component.text("⏸️ 子構造の自転を一時停止しました", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
        }
      }
      is io.oyasai.karakuri.motion.ChainChildContraptionMotion -> {
        motion.isRunning = !motion.isRunning
        if (motion.isRunning) {
          KarakuriSounds.playManual(loc)
          player?.sendActionBar(net.kyori.adventure.text.Component.text("▶ 鎖の直線移動を作動しました！", net.kyori.adventure.text.format.NamedTextColor.GREEN))
        } else {
          player?.sendActionBar(net.kyori.adventure.text.Component.text("⏸️ 鎖の直線移動を一時停止しました", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
        }
      }
      else -> {
        KarakuriSounds.playManual(loc)
        player?.sendActionBar(net.kyori.adventure.text.Component.text("✦ からくりを作動させました！", net.kyori.adventure.text.format.NamedTextColor.GOLD))
      }
    }

    try {
      saveInstanceMotion(targetKarakuri)
    } catch (_: Exception) {}
  }
}
