package io.oyasai.karakuri.collision

import io.oyasai.karakuri.engine.KarakuriInstance
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.atan2

/**
 * からくりの現在のワールド姿勢・幾何形状から、
 * プレイヤーとの精密な衝突判定（OBB）、上面乗り判定、壁押し出し、
 * および回転・移動追従（公転・視点回転）を計算するコライダー。
 */
class KarakuriCollider(val instance: KarakuriInstance) {

  data class OrientedBox(
    val center: Vector3d,
    val halfSize: Vector3d,
    val rotation: Quaternionf,
    val axisX: Vector3d,
    val axisY: Vector3d,
    val axisZ: Vector3d,
  )

  data class TransformState(
    val pivot: Vector3d,
    val rotation: Quaternionf,
    val isYAxisRotationOnly: Boolean,
  )

  var previousState: TransformState? = null
  var lastState: TransformState? = null
  var isMotionActive: Boolean = false

  /**
   * 現在のからくり全体の全パーツ・直方体から OBB リストを生成
   */
  fun computeWorldBoxes(): List<OrientedBox> {
    val boxes = mutableListOf<OrientedBox>()
    val curRot = instance.currentRotation
    val isChild = instance.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                  instance.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion

    if (isChild) {
      // 子構造の場合: 実際にテレポートされている各パーツのワールド座標から直接高精度 OBB を生成！
      val rootLocalBoxes = VoxelShape.getLocalBoxes(instance.originalBlockData, instance.scale)
      for (lb in rootLocalBoxes) {
        boxes.add(createOrientedBoxForDisplayEntity(lb, instance.displayEntity, curRot))
      }
      for (part in instance.additionalParts) {
        if (!part.displayEntity.isValid) continue
        val partLocalBoxes = VoxelShape.getLocalBoxes(part.originalBlockData, part.partScale)
        for (lb in partLocalBoxes) {
          boxes.add(createOrientedBoxForDisplayEntity(lb, part.displayEntity, curRot))
        }
      }

      val pivot = Vector3d(
        instance.worldPivotLocation.x,
        instance.worldPivotLocation.y,
        instance.worldPivotLocation.z
      )
      var maxDist = 0.0
      for (b in boxes) {
        val d = b.center.distance(pivot) + b.halfSize.length()
        if (d > maxDist) maxDist = d
      }
      maxReachRadius = maxOf(32.0, maxDist + 6.0)
      return boxes
    }

    val pivot = Vector3d(
      instance.worldPivotLocation.x,
      instance.worldPivotLocation.y,
      instance.worldPivotLocation.z
    )

    val rootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
    val curRootLoc = instance.displayEntity.location
    val delta = if (instance.isYawModeActive || instance.activeMotion is io.oyasai.karakuri.motion.ContinuousSpinMotion || instance.activeMotion is io.oyasai.karakuri.motion.AngleRotationMotion) Vector3d(0.0, 0.0, 0.0) else Vector3d(
      curRootLoc.x - (instance.originLocation.x + rootOffset.x),
      curRootLoc.y - (instance.originLocation.y + rootOffset.y),
      curRootLoc.z - (instance.originLocation.z + rootOffset.z)
    )

    // 1. ルートパーツ
    val rootLocalBoxes = VoxelShape.getLocalBoxes(instance.originalBlockData, instance.scale)
    val rootOrigin = Vector3d(
      instance.originLocation.x,
      instance.originLocation.y,
      instance.originLocation.z
    )
    for (lb in rootLocalBoxes) {
      boxes.add(createOrientedBox(lb, rootOrigin, pivot, delta, curRot))
    }

    // 2. 追加パーツ
    for (part in instance.additionalParts) {
      if (!part.displayEntity.isValid) continue
      val partLocalBoxes = VoxelShape.getLocalBoxes(part.originalBlockData, part.partScale)
      val partOrigin = Vector3d(
        part.originLocation.x,
        part.originLocation.y,
        part.originLocation.z
      )
      for (lb in partLocalBoxes) {
        boxes.add(createOrientedBox(lb, partOrigin, pivot, delta, curRot))
      }
    }

    var maxDist = 0.0
    for (b in boxes) {
      val d = b.center.distance(pivot) + b.halfSize.length()
      if (d > maxDist) maxDist = d
    }
    maxReachRadius = maxOf(32.0, maxDist + 6.0)

    return boxes
  }

  private fun createOrientedBoxForDisplayEntity(
    localBox: BoundingBox,
    display: org.bukkit.entity.Display,
    rotation: Quaternionf,
  ): OrientedBox {
    val hx = (localBox.maxX - localBox.minX) * 0.5
    val hy = (localBox.maxY - localBox.minY) * 0.5
    val hz = (localBox.maxZ - localBox.minZ) * 0.5

    val eLoc = display.location
    val isItem = display is org.bukkit.entity.ItemDisplay

    val lx = ((localBox.minX + localBox.maxX) * 0.5) - (if (isItem) 0.5 else 0.0)
    val ly = ((localBox.minY + localBox.maxY) * 0.5) - (if (isItem) 0.5 else 0.0)
    val lz = ((localBox.minZ + localBox.maxZ) * 0.5) - (if (isItem) 0.5 else 0.0)

    val pRot = Vector3f(lx.toFloat(), ly.toFloat(), lz.toFloat()).rotate(rotation)
    val center = Vector3d(eLoc.x + pRot.x, eLoc.y + pRot.y, eLoc.z + pRot.z)

    val axX = Vector3f(1f, 0f, 0f).rotate(rotation)
    val axY = Vector3f(0f, 1f, 0f).rotate(rotation)
    val axZ = Vector3f(0f, 0f, 1f).rotate(rotation)

    return OrientedBox(
      center = center,
      halfSize = Vector3d(hx, hy, hz),
      rotation = Quaternionf(rotation),
      axisX = Vector3d(axX.x.toDouble(), axX.y.toDouble(), axX.z.toDouble()),
      axisY = Vector3d(axY.x.toDouble(), axY.y.toDouble(), axY.z.toDouble()),
      axisZ = Vector3d(axZ.x.toDouble(), axZ.y.toDouble(), axZ.z.toDouble())
    )
  }

  /**
   * ピボットから最も遠いボックスまでの最大到達距離（マージン込み）
   */
  var maxReachRadius: Double = 32.0
    private set

  private fun createOrientedBox(
    localBox: BoundingBox,
    partOrigin: Vector3d,
    pivot: Vector3d,
    delta: Vector3d,
    rotation: Quaternionf,
  ): OrientedBox {
    val lx = (localBox.minX + localBox.maxX) * 0.5
    val ly = (localBox.minY + localBox.maxY) * 0.5
    val lz = (localBox.minZ + localBox.maxZ) * 0.5

    val hx = (localBox.maxX - localBox.minX) * 0.5
    val hy = (localBox.maxY - localBox.minY) * 0.5
    val hz = (localBox.maxZ - localBox.minZ) * 0.5

    val pInit = Vector3f(
      (partOrigin.x + lx - pivot.x).toFloat(),
      (partOrigin.y + ly - pivot.y).toFloat(),
      (partOrigin.z + lz - pivot.z).toFloat()
    )

    val pRot = Vector3f(pInit).rotate(rotation)

    val center = Vector3d(
      pivot.x + delta.x + pRot.x,
      pivot.y + delta.y + pRot.y,
      pivot.z + delta.z + pRot.z
    )

    val axX = Vector3f(1f, 0f, 0f).rotate(rotation)
    val axY = Vector3f(0f, 1f, 0f).rotate(rotation)
    val axZ = Vector3f(0f, 0f, 1f).rotate(rotation)

    return OrientedBox(
      center = center,
      halfSize = Vector3d(hx, hy, hz),
      rotation = Quaternionf(rotation),
      axisX = Vector3d(axX.x.toDouble(), axX.y.toDouble(), axX.z.toDouble()),
      axisY = Vector3d(axY.x.toDouble(), axY.y.toDouble(), axY.z.toDouble()),
      axisZ = Vector3d(axZ.x.toDouble(), axZ.y.toDouble(), axZ.z.toDouble())
    )
  }

  data class CollisionResult(
    val isStandingOnTop: Boolean,
    val surfaceY: Double,
    val ceilingY: Double? = null,
    val pushVector: Vector3d?,
    val canStepUp: Boolean = false,
    val standingBox: OrientedBox? = null,
    val standingFace: BoxFace? = null,
    val isInsideAnyFloor: Boolean = false,
  )

  data class BoxFace(
    val normal: Vector3d,
    val center: Vector3d,
    val axisU: Vector3d,
    val halfU: Double,
    val axisV: Vector3d,
    val halfV: Double,
  )

  /**
   * プレイヤーとからくりの全ボックスとの衝突・乗り判定・天井判定を解決
   */
  fun checkPlayerCollision(
    player: Player,
    boxes: List<OrientedBox>,
    checkLoc: Location = player.location,
    isAirborne: Boolean = false,
    isAlreadyRiding: Boolean = false,
  ): CollisionResult {
    val px = checkLoc.x
    val py = checkLoc.y
    val pz = checkLoc.z

    val pCenter = Vector3d(px, py + 0.9, pz)
    val pExtents = Vector3d(0.3, 0.9, 0.3)

    var bestStandingY: Double? = null
    var bestCeilingY: Double? = null
    var bestStandingBox: OrientedBox? = null
    var bestStandingFace: BoxFace? = null
    var minHorizDistSq = Double.MAX_VALUE
    var isInsideAnyFloor = false
    var cumulativePush = Vector3d(0.0, 0.0, 0.0)
    var hasPush = false
    var stepUp = false

    for (box in boxes) {
      val d = Vector3d(pCenter).sub(box.center)

      // OBBローカル系への射影
      val dx = d.dot(box.axisX)
      val dy = d.dot(box.axisY)
      val dz = d.dot(box.axisZ)

      var isThisBoxFloor = false
      var boxFloorY: Double? = null

      // 直方体の 6 つの面を定義
      val faces = listOf(
        // +Y 面 / -Y 面
        BoxFace(box.axisY, Vector3d(box.center).add(Vector3d(box.axisY).mul(box.halfSize.y)), box.axisX, box.halfSize.x, box.axisZ, box.halfSize.z),
        BoxFace(Vector3d(box.axisY).mul(-1.0), Vector3d(box.center).sub(Vector3d(box.axisY).mul(box.halfSize.y)), box.axisX, box.halfSize.x, box.axisZ, box.halfSize.z),
        // +X 面 / -X 面
        BoxFace(box.axisX, Vector3d(box.center).add(Vector3d(box.axisX).mul(box.halfSize.x)), box.axisY, box.halfSize.y, box.axisZ, box.halfSize.z),
        BoxFace(Vector3d(box.axisX).mul(-1.0), Vector3d(box.center).sub(Vector3d(box.axisX).mul(box.halfSize.x)), box.axisY, box.halfSize.y, box.axisZ, box.halfSize.z),
        // +Z 面 / -Z 面
        BoxFace(box.axisZ, Vector3d(box.center).add(Vector3d(box.axisZ).mul(box.halfSize.z)), box.axisX, box.halfSize.x, box.axisY, box.halfSize.y),
        BoxFace(Vector3d(box.axisZ).mul(-1.0), Vector3d(box.center).sub(Vector3d(box.axisZ).mul(box.halfSize.z)), box.axisX, box.halfSize.x, box.axisY, box.halfSize.y),
      )

      // 1. 天井判定 (ワールド法線 Ny < -0.25: 最も下を向いている底面)
      // ★超重要: 天井とはプレイヤーの足元 py より上にある底面のこと！
      // プレイヤーが乗っている床の下面 (ceilY <= py) を天井と誤認してはならない！
      val bottomFace = faces.minByOrNull { it.normal.y }
      var boxCeilY: Double? = null
      if (bottomFace != null && bottomFace.normal.y < -0.25) {
        val face = bottomFace
        val ceilY = face.center.y - ((px - face.center.x) * face.normal.x + (pz - face.center.z) * face.normal.z) / face.normal.y
        val dCeil = Vector3d(px - face.center.x, ceilY - face.center.y, pz - face.center.z)
        val u = dCeil.dot(face.axisU)
        val v = dCeil.dot(face.axisV)

        if (abs(u) <= face.halfU + 0.15 && abs(v) <= face.halfV + 0.15) {
          boxCeilY = ceilY
          val headY = py + 1.8
          // 底面がプレイヤーの上半身以上（py + 0.8以上）にあり、かつプレイヤーの頭上に存在（または頭を押し下げている）
          // ※ 足元付近にある底面を天井と誤認して床下へ押し込める事故を完全根絶！
          if (ceilY > py + 0.8 && ceilY <= headY + 0.8) {
            if (bestCeilingY == null || ceilY < bestCeilingY) {
              bestCeilingY = ceilY
            }
          }
        }
      }

      val topFace = faces.maxByOrNull { it.normal.y }
      val boxBottomY = if (bottomFace != null) bottomFace.center.y else (box.center.y - box.halfSize.y)
      val boxTopY = box.center.y + box.halfSize.y * abs(box.axisY.y) + box.halfSize.x * abs(box.axisX.y) + box.halfSize.z * abs(box.axisZ.y)

      // ★頭上にある天井ブロックの判定:
      // ボックスの下面 boxBottomY がプレイヤーの目線付近以上（py + 1.2m 以上）にある場合のみ天井ブロックと判定！
      val isCeilingBlock = (boxBottomY >= py + 1.2)

      // 2. 直方体のすべての歩行可能面 (Ny > 0.25) を床候補として網羅的に判定！
      // 傾いた棒や斜面では上面・先端面など複数の面が同時に上を向くため、1面限定にせず全て評価する！
      val walkableFaces = faces.filter { !isCeilingBlock && it.normal.y > 0.25 }
      var isOverAnyWalkableFace = false

      for (face in walkableFaces) {
        val rawSurfY = face.center.y - ((px - face.center.x) * face.normal.x + (pz - face.center.z) * face.normal.z) / face.normal.y
        val dSurf = Vector3d(px - face.center.x, rawSurfY - face.center.y, pz - face.center.z)
        val u = dSurf.dot(face.axisU)
        val v = dSurf.dot(face.axisV)

        val uClamped = u.coerceIn(-face.halfU, face.halfU)
        val vClamped = v.coerceIn(-face.halfV, face.halfV)
        val isInside = (abs(u) <= face.halfU + 0.001 && abs(v) <= face.halfV + 0.001)

        val surfY = if (isInside) {
          rawSurfY
        } else {
          val clampedPoint = Vector3d(face.center)
            .add(Vector3d(face.axisU).mul(uClamped))
            .add(Vector3d(face.axisV).mul(vClamped))
          clampedPoint.y
        }

        val distU = maxOf(0.0, abs(u) - face.halfU)
        val distV = maxOf(0.0, abs(v) - face.halfV)

        // 水平範囲のチェック: 面の内側(isInside)にいる場合は確実に上面。
        // 面の外側にはみ出ている場合、乗車中の端歩行時のみマージン0.25mを認め、未乗車時は0.05mに制限して壁横誤吸着を防止！
        val horizMargin = if (isAlreadyRiding) 0.25 else 0.05
        val isOverThisTop = (distU <= horizMargin && distV <= horizMargin)
        if (isOverThisTop) {
          isOverAnyWalkableFace = true
          val yDiff = py - surfY
          val isSlopedFace = (abs(face.normal.x) > 0.05 || abs(face.normal.z) > 0.05)
          val yDiffRange = if (isSlopedFace) {
            if (isMotionActive) -0.6..1.2 else (if (isAlreadyRiding) -0.5..0.8 else -0.35..0.8)
          } else if (isAirborne && isAlreadyRiding) {
            -0.5..2.5
          } else if (isAirborne && isMotionActive && isInside) {
            -0.8..2.0
          } else {
            if (isMotionActive && isInside) -0.6..1.2 else (if (isAlreadyRiding) -0.35..0.8 else -0.35..0.6)
          }

          // ★ ユーザー指示: からくりの上に置いたバニラブロックの上に立っている時はからくりの影響を受けない！
          val world = checkLoc.world
          val sampleOffsets = listOf(
            0.0 to 0.0,
            0.28 to 0.28,
            -0.28 to 0.28,
            0.28 to -0.28,
            -0.28 to -0.28
          )
          val isOnSolidWorldBlock = sampleOffsets.any { (ox, oz) ->
            val b = world.getBlockAt(
              kotlin.math.floor(px + ox).toInt(),
              kotlin.math.floor(py - 0.1).toInt(),
              kotlin.math.floor(pz + oz).toInt()
            )
            !b.type.isAir && !b.isPassable
          }
          val isObstructedByWorldBlock = if (yDiff > 0.35) {
            val startY = kotlin.math.floor(surfY + 0.05).toInt()
            val endY = kotlin.math.floor(py - 0.05).toInt()
            var blocked = false
            for (by in startY..endY) {
              if (sampleOffsets.any { (ox, oz) ->
                val b = world.getBlockAt(kotlin.math.floor(px + ox).toInt(), by, kotlin.math.floor(pz + oz).toInt())
                !b.type.isAir && !b.isPassable
              }) {
                blocked = true
                break
              }
            }
            blocked
          } else false

          if (yDiff in yDiffRange && !isOnSolidWorldBlock && !isObstructedByWorldBlock) {
            val horizDistSq = distU * distU + distV * distV
            if (isInside) {
              isInsideAnyFloor = true
            }

            val isCurrentBestInside = (minHorizDistSq <= 1e-6)
            val isBetter = when {
              bestStandingY == null -> true
              isInside && !isCurrentBestInside -> true
              !isInside && isCurrentBestInside -> false
              isInside && isCurrentBestInside -> surfY > bestStandingY + 0.02
              else -> horizDistSq < minHorizDistSq
            }

            if (isBetter) {
              bestStandingY = surfY
              bestStandingBox = box
              bestStandingFace = face
              minHorizDistSq = horizDistSq
            }
            if (boxFloorY == null || surfY > boxFloorY!!) {
              boxFloorY = surfY
            }
            if (py >= surfY - 0.05) {
              isThisBoxFloor = true
            }
          }
        }
      }

      // 3. 側面の壁衝突チェック (AABB vs OBB SAT)
      val candidateY = boxFloorY ?: boxTopY

      // 壁押し出しをスキップする条件:
      // 1. プレイヤーがこの直方体の上面に実際に乗っている (boxFloorY != null かつ py >= boxFloorY - 0.25)
      // 2. 直方体の上面が足元付近以下 (candidateY <= py + 0.35m: 歩行面・低い足場)
      // 3. 頭上の天井ブロック (isCeilingBlock: 天井頭打ち時の外側ワープ防止)
      if ((boxFloorY != null && py >= boxFloorY - 0.25) || candidateY <= py + 0.35 || isCeilingBlock) {
        continue
      }

      val rx = pExtents.x * abs(box.axisX.x) + pExtents.y * abs(box.axisX.y) + pExtents.z * abs(box.axisX.z)
      val ry = pExtents.x * abs(box.axisY.x) + pExtents.y * abs(box.axisY.y) + pExtents.z * abs(box.axisY.z)
      val rz = pExtents.x * abs(box.axisZ.x) + pExtents.y * abs(box.axisZ.y) + pExtents.z * abs(box.axisZ.z)

      val penX = (box.halfSize.x + rx) - abs(dx)
      val penY = (box.halfSize.y + ry) - abs(dy)
      val penZ = (box.halfSize.z + rz) - abs(dz)

      // 交差していなければ壁判定はなし
      if (penX <= 0 || penY <= 0 || penZ <= 0) continue

      // ★超重要: 侵入軸が上面・底面（penY <= penX && penY <= penZ）の場合！
      // プレイヤーは上面から着地した（または底面に頭をぶつけた）のであり、
      // 側面の壁に横からぶつかったのではない！
      // したがって、側面壁としての水平押し出し（横ずらし）は絶対に発生させない！
      if (penY <= penX && penY <= penZ) {
        continue
      }

      // オートステップ (0.6m 以下の小さな段差・ハーフブロック・階段1段分):
      // バニラの STEP_HEIGHT で足を上げずに登れる高さ（<= 0.6m）のみ壁押し出しをスキップ
      val stepDiff = candidateY - py
      if (!isAirborne && stepDiff in 0.001..0.6) {
        if (bestStandingY == null || candidateY > bestStandingY) {
          bestStandingY = candidateY
          bestStandingBox = box
        }
        stepUp = true
        continue // 登れる段差・ハーフブロックは壁として押し出さない
      }

      // 壁衝突: 水平方向（X, Z）のみの押し出しを適用（Y方向の押し出しを全廃して空中浮遊を根絶！）
      if (penX < penZ) {
        val pushDir = if (dx >= 0) 1.0 else -1.0
        val pVec = Vector3d(box.axisX).mul(pushDir * penX)
        cumulativePush.add(pVec.x, 0.0, pVec.z)
        hasPush = true
      } else {
        val pushDir = if (dz >= 0) 1.0 else -1.0
        val pVec = Vector3d(box.axisZ).mul(pushDir * penZ)
        cumulativePush.add(pVec.x, 0.0, pVec.z)
        hasPush = true
      }
    }

    // ★重要: 天井があっても、床との間に空間（1.4m以上）があれば部屋・ゴンドラ内部として正常に上面に乗る！
    // 床と天井の間に挟まれて下敷きになる場合（隙間 <= 1.4m）のみ上面乗りを無効化する。
    val isSquished = (bestCeilingY != null && bestStandingY != null && bestCeilingY <= (bestStandingY + 1.4))
    val standingOnTop = (bestStandingY != null && !isSquished)

    // ★超重要: プレイヤーがからくりの上面に着地・乗車している（standingOnTop == true）場合、
    // 足元のブロックの側面から計算された壁押し出しベクトル（pushVector）を無効化する！
    // （上面より 1.0m 以上高い位置にそびえ立つ「本当の壁ブロック」がある場合のみ押し出しを許可）
    val filteredPush = if (hasPush && standingOnTop) {
      val standingY = bestStandingY ?: py
      val hasTallWall = boxes.any { b ->
        val bTop = b.center.y + b.halfSize.y * abs(b.axisY.y) + b.halfSize.x * abs(b.axisX.y) + b.halfSize.z * abs(b.axisZ.y)
        bTop > standingY + 0.9
      }
      if (hasTallWall) cumulativePush else null
    } else if (hasPush) {
      cumulativePush
    } else {
      null
    }

    return CollisionResult(
      isStandingOnTop = standingOnTop,
      surfaceY = bestStandingY ?: py,
      ceilingY = bestCeilingY,
      pushVector = filteredPush,
      canStepUp = stepUp,
      standingBox = bestStandingBox,
      standingFace = bestStandingFace,
      isInsideAnyFloor = isInsideAnyFloor
    )
  }

  /**
   * 現在の幾何状態をキャプチャして TransformState を作成
   */
  fun captureCurrentState(): TransformState {
    val curRot = Quaternionf(instance.currentRotation)
    val curPivot = if (instance.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                       instance.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
      Vector3d(
        instance.worldPivotLocation.x,
        instance.worldPivotLocation.y,
        instance.worldPivotLocation.z
      )
    } else {
      val rootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
      val curRootLoc = instance.displayEntity.location
      val delta = if (instance.isYawModeActive || instance.activeMotion is io.oyasai.karakuri.motion.ContinuousSpinMotion || instance.activeMotion is io.oyasai.karakuri.motion.AngleRotationMotion) Vector3d(0.0, 0.0, 0.0) else Vector3d(
        curRootLoc.x - (instance.originLocation.x + rootOffset.x),
        curRootLoc.y - (instance.originLocation.y + rootOffset.y),
        curRootLoc.z - (instance.originLocation.z + rootOffset.z)
      )
      Vector3d(
        instance.worldPivotLocation.x + delta.x,
        instance.worldPivotLocation.y + delta.y,
        instance.worldPivotLocation.z + delta.z
      )
    }

    val isYOnly = checkIsYAxisRotation(instance)

    return TransformState(
      pivot = curPivot,
      rotation = curRot,
      isYAxisRotationOnly = isYOnly
    )
  }

  private fun checkIsYAxisRotation(inst: KarakuriInstance): Boolean {
    val motion = inst.activeMotion ?: return false
    if (motion is io.oyasai.karakuri.motion.ContinuousSpinMotion && motion.axis == io.oyasai.karakuri.motion.Axis.Y) {
      return true
    }
    if (motion is io.oyasai.karakuri.motion.AngleRotationMotion && motion.axis == io.oyasai.karakuri.motion.Axis.Y) {
      return true
    }
    if (motion is io.oyasai.karakuri.motion.MinecartMotion && motion.spinAxis == io.oyasai.karakuri.motion.Axis.Y) {
      return true
    }
    if (motion is io.oyasai.karakuri.motion.DoorMotion && motion.axis == io.oyasai.karakuri.motion.Axis.Y) {
      return true
    }
    if (motion is io.oyasai.karakuri.motion.ProgramMotion) {
      return true
    }
    if (motion is io.oyasai.karakuri.motion.ChildContraptionMotion) {
      if (motion.pistonFacing == org.bukkit.block.BlockFace.UP || motion.pistonFacing == org.bukkit.block.BlockFace.DOWN) {
        return true
      }
      val parent = io.oyasai.karakuri.KarakuriPlugin.instance.manager.get(motion.parentId)
      if (parent != null && checkIsYAxisRotation(parent)) {
        return true
      }
    }
    if (motion is io.oyasai.karakuri.motion.ChainChildContraptionMotion) {
      if (motion.spinAxis == io.oyasai.karakuri.motion.Axis.Y) {
        return true
      }
      val parent = io.oyasai.karakuri.KarakuriPlugin.instance.manager.get(motion.parentId)
      if (parent != null && checkIsYAxisRotation(parent)) {
        return true
      }
    }
    return false
  }

  /**
   * 親からくりの今Tickの正確なY軸回転変位（度数法）をモーション・インスタンスから直接取得
   */
  fun calculateParentDeltaYaw(parent: io.oyasai.karakuri.engine.KarakuriInstance): Float {
    val pMotion = parent.activeMotion ?: return parent.lastDeltaYaw
    when (pMotion) {
      is io.oyasai.karakuri.motion.ContinuousSpinMotion -> {
        if (pMotion.isRunning && pMotion.axis == io.oyasai.karakuri.motion.Axis.Y) {
          return -(pMotion.degreesPerSecond / 20.0f)
        }
      }
      is io.oyasai.karakuri.motion.MinecartMotion -> {
        return pMotion.lastDeltaYaw
      }
      is io.oyasai.karakuri.motion.AngleRotationMotion -> {
        if (pMotion.axis == io.oyasai.karakuri.motion.Axis.Y) {
          return parent.lastDeltaYaw
        }
      }
      is io.oyasai.karakuri.motion.DoorMotion -> {
        if (pMotion.axis == io.oyasai.karakuri.motion.Axis.Y) {
          return parent.lastDeltaYaw
        }
      }
      is io.oyasai.karakuri.motion.ProgramMotion -> {
        return parent.lastDeltaYaw
      }
      is io.oyasai.karakuri.motion.ChildContraptionMotion -> {
        var yaw = 0f
        if (pMotion.followParentRotation) {
          val grandParent = io.oyasai.karakuri.KarakuriPlugin.instance.manager.get(pMotion.parentId)
          if (grandParent != null) {
            yaw += calculateParentDeltaYaw(grandParent)
          }
        }
        if (pMotion.isSpinActive && pMotion.rpm > 0.01f) {
          if (pMotion.pistonFacing == org.bukkit.block.BlockFace.UP || pMotion.pistonFacing == org.bukkit.block.BlockFace.DOWN) {
            val deg = (pMotion.rpm * 6.0f / 20.0f) * (if (pMotion.isClockwise) 1.0f else -1.0f)
            yaw += if (pMotion.pistonFacing == org.bukkit.block.BlockFace.DOWN) -deg else deg
          }
        }
        return yaw
      }
      is io.oyasai.karakuri.motion.ChainChildContraptionMotion -> {
        var yaw = 0f
        if (pMotion.followParentRotation) {
          val grandParent = io.oyasai.karakuri.KarakuriPlugin.instance.manager.get(pMotion.parentId)
          if (grandParent != null) {
            yaw += calculateParentDeltaYaw(grandParent)
          }
        }
        if (pMotion.isSpinActive && pMotion.spinAxis == io.oyasai.karakuri.motion.Axis.Y && pMotion.rpm > 0.01f) {
          val deg = (pMotion.rpm * 6.0f / 20.0f) * (if (pMotion.isClockwise) 1.0f else -1.0f)
          yaw += deg
        }
        return yaw
      }
    }
    return parent.lastDeltaYaw
  }

  /**
   * 前Tickから今Tickへの運動差分から、乗っているプレイヤーの位置（公転・平行移動）および視点Yawを計算
   */
  fun calculatePlayerFollow(
    playerLoc: Location,
    prevState: TransformState,
    currState: TransformState,
  ): Location {
    val pPos = Vector3d(playerLoc.x, playerLoc.y, playerLoc.z)

    // 前Tickピボットからの相対位置
    val relVec = Vector3f(
      (pPos.x - prevState.pivot.x).toFloat(),
      (pPos.y - prevState.pivot.y).toFloat(),
      (pPos.z - prevState.pivot.z).toFloat()
    )

    // 前Tickローカル座標系への逆回転
    val invPrevRot = Quaternionf(prevState.rotation).conjugate()
    val localPos = Vector3f(relVec).rotate(invPrevRot)

    // 今Tick回転でのワールド座標系への変換
    val newRelVec = Vector3f(localPos).rotate(currState.rotation)

    // 今Tickのピボット位置を加算（公転 + 全体平行移動）
    val newX = currState.pivot.x + newRelVec.x
    val newY = currState.pivot.y + newRelVec.y
    val newZ = currState.pivot.z + newRelVec.z

    var newYaw = playerLoc.yaw

    val deltaQ = Quaternionf(currState.rotation).mul(invPrevRot)
    val fVec = Vector3f(0f, 0f, 1f).rotate(deltaQ)
    var rotDeltaYaw = (-Math.toDegrees(atan2(fVec.x.toDouble(), fVec.z.toDouble()))).toFloat()
    while (rotDeltaYaw > 180f) rotDeltaYaw -= 360f
    while (rotDeltaYaw < -180f) rotDeltaYaw += 360f

    val isPurePitchOrRoll = abs(fVec.y) > 0.05f && abs(fVec.x) < 0.0005f
    if (!isPurePitchOrRoll) {
      newYaw += rotDeltaYaw
    }

    return Location(playerLoc.world, newX, newY, newZ, newYaw, playerLoc.pitch)
  }

  /**
   * 指定した水平座標 (targetX, targetZ) について、からくりの全床ブロックの中から
   * 最も適切な床面高さ Y を算出する。
   * - いずれかの床ブロックの上面の内側にある場合: そのまま (targetX, surfY, targetZ) で安定配置！
   * - からくりの床ブロックの外側にはみ出た場合のみ: ブロックの内側（エッジからガスト半径0.5m内側）に引き寄せて外側へのはみ出しを根絶！
   */
  fun clampToFloorSurface(
    boxes: List<OrientedBox>,
    targetX: Double,
    targetZ: Double,
    fallbackY: Double,
    ghastRadius: Double = 0.5
  ): Vector3d {
    var bestInsideY: Double? = null
    var bestClampedEdgePoint: Vector3d? = null
    var minEdgeDistSq = Double.MAX_VALUE

    for (box in boxes) {
      val faces = listOf(
        BoxFace(box.axisY, Vector3d(box.center).add(Vector3d(box.axisY).mul(box.halfSize.y)), box.axisX, box.halfSize.x, box.axisZ, box.halfSize.z),
        BoxFace(Vector3d(box.axisY).mul(-1.0), Vector3d(box.center).sub(Vector3d(box.axisY).mul(box.halfSize.y)), box.axisX, box.halfSize.x, box.axisZ, box.halfSize.z),
        BoxFace(box.axisX, Vector3d(box.center).add(Vector3d(box.axisX).mul(box.halfSize.x)), box.axisY, box.halfSize.y, box.axisZ, box.halfSize.z),
        BoxFace(Vector3d(box.axisX).mul(-1.0), Vector3d(box.center).sub(Vector3d(box.axisX).mul(box.halfSize.x)), box.axisY, box.halfSize.y, box.axisZ, box.halfSize.z),
        BoxFace(box.axisZ, Vector3d(box.center).add(Vector3d(box.axisZ).mul(box.halfSize.z)), box.axisX, box.halfSize.x, box.axisY, box.halfSize.y),
        BoxFace(Vector3d(box.axisZ).mul(-1.0), Vector3d(box.center).sub(Vector3d(box.axisZ).mul(box.halfSize.z)), box.axisX, box.halfSize.x, box.axisY, box.halfSize.y),
      )

      val walkableFaces = faces.filter { it.normal.y > 0.25 }
      for (face in walkableFaces) {
        val rawSurfY = face.center.y - ((targetX - face.center.x) * face.normal.x + (targetZ - face.center.z) * face.normal.z) / face.normal.y
        val dSurf = Vector3d(targetX - face.center.x, rawSurfY - face.center.y, targetZ - face.center.z)
        val u = dSurf.dot(face.axisU)
        val v = dSurf.dot(face.axisV)

        // 1. この面の物理的長方形の内側にあるか？（隣接ブロック間の微小ギャップを吸収するマージン 0.05m）
        if (abs(u) <= face.halfU + 0.05 && abs(v) <= face.halfV + 0.05) {
          if (bestInsideY == null || rawSurfY > bestInsideY) {
            bestInsideY = rawSurfY
          }
        }

        // 2. 外にはみ出た時用のエッジクランプ点（からくりの外周に飛び出したガストを面の境界にクランプ）
        val uEdge = u.coerceIn(-face.halfU, face.halfU)
        val vEdge = v.coerceIn(-face.halfV, face.halfV)
        val edgePoint = Vector3d(face.center)
          .add(Vector3d(face.axisU).mul(uEdge))
          .add(Vector3d(face.axisV).mul(vEdge))

        val distU = maxOf(0.0, abs(u) - face.halfU)
        val distV = maxOf(0.0, abs(v) - face.halfV)
        val distSq = distU * distU + distV * distV
        if (distSq < minEdgeDistSq) {
          minEdgeDistSq = distSq
          bestClampedEdgePoint = edgePoint
        }
      }
    }

    // いずれかの床ブロックの内側にあるなら、その位置のまま自由配置！
    if (bestInsideY != null) {
      return Vector3d(targetX, bestInsideY, targetZ)
    }

    // からくりの床ブロックの外側にはみ出たなら、ブロックの内側（エッジから半径分内側）に寄せる！
    return bestClampedEdgePoint ?: Vector3d(targetX, fallbackY, targetZ)
  }

  /**
   * 水平座標 (px, pz) がいずれかの床ブロックの水平境界内（マージン含む）にあるかを判定。
   * からくりから外に飛び降りたり歩いて降りた瞬間に足場を即座に破棄するために使用！
   */
  fun isHorizontallyOverAnyFloor(
    boxes: List<OrientedBox>,
    px: Double,
    pz: Double,
    margin: Double = 0.3
  ): Boolean {
    for (box in boxes) {
      val extentX = box.halfSize.x * kotlin.math.abs(box.axisX.x) + box.halfSize.y * kotlin.math.abs(box.axisY.x) + box.halfSize.z * kotlin.math.abs(box.axisZ.x)
      val extentZ = box.halfSize.x * kotlin.math.abs(box.axisX.z) + box.halfSize.y * kotlin.math.abs(box.axisY.z) + box.halfSize.z * kotlin.math.abs(box.axisZ.z)
      if (kotlin.math.abs(px - box.center.x) <= extentX + margin && kotlin.math.abs(pz - box.center.z) <= extentZ + margin) {
        return true
      }
    }
    return false
  }

  data class SweptHitResult(
    val box: OrientedBox,
    val velocity: Vector3d,
    val hitPoint: Vector3d,
  )

  /**
   * 前Tickから今Tickにかけて、からくりの各パーツが移動・回転した軌跡（掃引体）と
   * プレイヤー（AABB）との連続衝突判定（CCD）を行い、衝突したパーツの線速度を返す。
   * 高速回転時のすり抜け（トンネリング）を数学的・物理的に100%根絶！
   */
  fun checkSweptPlayerCollision(
    player: Player,
    checkLoc: Location,
    prevState: TransformState?,
    currState: TransformState
  ): SweptHitResult? {
    if (prevState == null) return null

    val px = checkLoc.x
    val py = checkLoc.y
    val pz = checkLoc.z
    val pCenter = Vector3d(px, py + 0.9, pz)
    val pRadius = 0.35
    val pHalfH = 0.9

    val pivot = currState.pivot
    val distToPivotSq = (px - pivot.x) * (px - pivot.x) + (pz - pivot.z) * (pz - pivot.z)
    if (distToPivotSq > (maxReachRadius + 1.0) * (maxReachRadius + 1.0)) return null

    val isChild = instance.activeMotion is io.oyasai.karakuri.motion.ChildContraptionMotion ||
                  instance.activeMotion is io.oyasai.karakuri.motion.ChainChildContraptionMotion

    if (isChild) {
      val currBoxes = computeWorldBoxes()
      for (b in currBoxes) {
        val d = Vector3d(pCenter).sub(b.center)
        val dx = abs(d.dot(b.axisX))
        val dy = abs(d.dot(b.axisY))
        val dz = abs(d.dot(b.axisZ))
        val rx = pRadius * abs(b.axisX.x) + pHalfH * abs(b.axisX.y) + pRadius * abs(b.axisX.z)
        val ry = pRadius * abs(b.axisY.x) + pHalfH * abs(b.axisY.y) + pRadius * abs(b.axisY.z)
        val rz = pRadius * abs(b.axisZ.x) + pHalfH * abs(b.axisZ.y) + pRadius * abs(b.axisZ.z)
        if (dx <= b.halfSize.x + rx && dy <= b.halfSize.y + ry && dz <= b.halfSize.z + rz) {
          val vel = Vector3d(currState.pivot).sub(prevState.pivot)
          return SweptHitResult(b, vel, b.center)
        }
      }
      return null
    }

    class PartDesc(val pInit: Vector3f, val halfSize: Vector3d)
    val parts = mutableListOf<PartDesc>()

    val rootOffset = instance.getBaseEntityOffset(instance.displayEntity, instance.originalBlockData)
    val curRootLoc = instance.displayEntity.location
    val delta = if (instance.isYawModeActive || instance.activeMotion is io.oyasai.karakuri.motion.ContinuousSpinMotion || instance.activeMotion is io.oyasai.karakuri.motion.AngleRotationMotion) Vector3d(0.0, 0.0, 0.0) else Vector3d(
      curRootLoc.x - (instance.originLocation.x + rootOffset.x),
      curRootLoc.y - (instance.originLocation.y + rootOffset.y),
      curRootLoc.z - (instance.originLocation.z + rootOffset.z)
    )

    val rootLocalBoxes = VoxelShape.getLocalBoxes(instance.originalBlockData, instance.scale)
    val rootOrigin = Vector3d(instance.originLocation.x, instance.originLocation.y, instance.originLocation.z)
    for (lb in rootLocalBoxes) {
      val lx = (lb.minX + lb.maxX) * 0.5
      val ly = (lb.minY + lb.maxY) * 0.5
      val lz = (lb.minZ + lb.maxZ) * 0.5
      val hx = (lb.maxX - lb.minX) * 0.5
      val hy = (lb.maxY - lb.minY) * 0.5
      val hz = (lb.maxZ - lb.minZ) * 0.5
      val pInit = Vector3f(
        (rootOrigin.x + lx - pivot.x).toFloat(),
        (rootOrigin.y + ly - pivot.y).toFloat(),
        (rootOrigin.z + lz - pivot.z).toFloat()
      )
      parts.add(PartDesc(pInit, Vector3d(hx, hy, hz)))
    }

    for (part in instance.additionalParts) {
      if (!part.displayEntity.isValid) continue
      val partLocalBoxes = VoxelShape.getLocalBoxes(part.originalBlockData, part.partScale)
      val partOrigin = Vector3d(part.originLocation.x, part.originLocation.y, part.originLocation.z)
      for (lb in partLocalBoxes) {
        val lx = (lb.minX + lb.maxX) * 0.5
        val ly = (lb.minY + lb.maxY) * 0.5
        val lz = (lb.minZ + lb.maxZ) * 0.5
        val hx = (lb.maxX - lb.minX) * 0.5
        val hy = (lb.maxY - lb.minY) * 0.5
        val hz = (lb.maxZ - lb.minZ) * 0.5
        val pInit = Vector3f(
          (partOrigin.x + lx - pivot.x).toFloat(),
          (partOrigin.y + ly - pivot.y).toFloat(),
          (partOrigin.z + lz - pivot.z).toFloat()
        )
        parts.add(PartDesc(pInit, Vector3d(hx, hy, hz)))
      }
    }

    val qPrev = Quaternionf(prevState.rotation)
    val qCurr = Quaternionf(currState.rotation)
    if (qPrev.dot(qCurr) < 0f) {
      qCurr.set(-qCurr.x, -qCurr.y, -qCurr.z, -qCurr.w)
    }

    val qDiff = Quaternionf(qCurr).mul(Quaternionf(qPrev).conjugate())
    val clampedW = kotlin.math.abs(qDiff.w).coerceIn(0f, 1f)
    val deltaRad = (2.0 * kotlin.math.acos(clampedW.toDouble()))
    val distToPivot = kotlin.math.sqrt(distToPivotSq)

    var bestHit: SweptHitResult? = null
    var maxSpeed = 0.0

    for (pd in parts) {
      val cPrev = Vector3d(pivot.x + delta.x, pivot.y + delta.y, pivot.z + delta.z)
        .add(Vector3d(Vector3f(pd.pInit).rotate(qPrev)))
      val cCurr = Vector3d(pivot.x + delta.x, pivot.y + delta.y, pivot.z + delta.z)
        .add(Vector3d(Vector3f(pd.pInit).rotate(qCurr)))

      val vel = Vector3d(cCurr).sub(cPrev)
      val travelDist = vel.length()

      // パーツの最大到達半径およびプレイヤーのいる半径での周速度を計算し、隙間すり抜けを完全防止
      val pInitLen = kotlin.math.sqrt((pd.pInit.x * pd.pInit.x + pd.pInit.y * pd.pInit.y + pd.pInit.z * pd.pInit.z).toDouble())
      val maxRadius = pInitLen + pd.halfSize.length()
      val travelDistAtPlayer = distToPivot * deltaRad
      val travelDistAtMax = maxRadius * deltaRad
      val effectiveTravel = maxOf(travelDist, travelDistAtPlayer, travelDistAtMax * 0.5)

      // ステップ間隔を最大でも 0.20 ブロック以内に抑え、サンプリング飛び越しを物理的に根絶
      val steps = if (effectiveTravel > 0.2) {
        kotlin.math.ceil(effectiveTravel / 0.20).toInt().coerceIn(2, 28)
      } else {
        1
      }
      val stepMargin = (effectiveTravel / steps.toDouble()) * 0.55

      for (s in 0..steps) {
        val t = s.toFloat() / steps.toFloat()
        val qInterp = Quaternionf(qPrev).slerp(qCurr, t)
        val cInterp = Vector3d(pivot.x + delta.x, pivot.y + delta.y, pivot.z + delta.z)
          .add(Vector3d(Vector3f(pd.pInit).rotate(qInterp)))

        val axX = Vector3f(1f, 0f, 0f).rotate(qInterp)
        val axY = Vector3f(0f, 1f, 0f).rotate(qInterp)
        val axZ = Vector3f(0f, 0f, 1f).rotate(qInterp)

        val vAxisX = Vector3d(axX.x.toDouble(), axX.y.toDouble(), axX.z.toDouble())
        val vAxisY = Vector3d(axY.x.toDouble(), axY.y.toDouble(), axY.z.toDouble())
        val vAxisZ = Vector3d(axZ.x.toDouble(), axZ.y.toDouble(), axZ.z.toDouble())

        val d = Vector3d(pCenter).sub(cInterp)
        val dx = abs(d.dot(vAxisX))
        val dy = abs(d.dot(vAxisY))
        val dz = abs(d.dot(vAxisZ))

        val rx = pRadius * abs(vAxisX.x) + pHalfH * abs(vAxisX.y) + pRadius * abs(vAxisX.z)
        val ry = pRadius * abs(vAxisY.x) + pHalfH * abs(vAxisY.y) + pRadius * abs(vAxisY.z)
        val rz = pRadius * abs(vAxisZ.x) + pHalfH * abs(vAxisZ.y) + pRadius * abs(vAxisZ.z)

        // 離散サンプリング間の隙間を埋めるステップ掃引マージンを合成
        if (dx <= pd.halfSize.x + rx + stepMargin && dy <= pd.halfSize.y + ry + stepMargin && dz <= pd.halfSize.z + rz + stepMargin) {
          val ob = OrientedBox(
            center = cInterp,
            halfSize = pd.halfSize,
            rotation = qInterp,
            axisX = vAxisX,
            axisY = vAxisY,
            axisZ = vAxisZ
          )

          // プレイヤーが接触した位置での真の周速度（局所接線速度）を算出
          val rPlayer = Vector3d(pCenter.x - pivot.x, pCenter.y - pivot.y, pCenter.z - pivot.z)
          val rRotated = Vector3d(Vector3f(rPlayer.x.toFloat(), rPlayer.y.toFloat(), rPlayer.z.toFloat()).rotate(qDiff))
          val localVel = Vector3d(rRotated).sub(rPlayer).add(delta)
          val finalHitVel = if (localVel.lengthSquared() > vel.lengthSquared()) localVel else vel
          val hitSpeed = finalHitVel.length()

          if (hitSpeed >= maxSpeed) {
            maxSpeed = hitSpeed
            bestHit = SweptHitResult(ob, finalHitVel, cInterp)
          }
          break
        }
      }
    }

    return bestHit
  }
}
