package io.oyasai.karakuri.persistence

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.oyasai.karakuri.engine.KarakuriInstance
import io.oyasai.karakuri.gui.ActionType
import io.oyasai.karakuri.gui.KarakuriProgramGUI
import io.oyasai.karakuri.gui.StepConfig
import io.oyasai.karakuri.motion.AngleRotationMotion
import io.oyasai.karakuri.motion.Axis
import io.oyasai.karakuri.motion.ChainChildContraptionMotion
import io.oyasai.karakuri.motion.ChildContraptionMotion
import io.oyasai.karakuri.motion.ContinuousSpinMotion
import io.oyasai.karakuri.motion.DoorMotion
import io.oyasai.karakuri.motion.IntegratedLinearMotion
import io.oyasai.karakuri.motion.LinearLoopMode
import io.oyasai.karakuri.motion.MinecartMotion
import io.oyasai.karakuri.motion.MotionController
import io.oyasai.karakuri.motion.ProgramLoopMode
import io.oyasai.karakuri.motion.ProgramMotion
import org.bukkit.Location
import org.bukkit.block.BlockFace

/**
 * からくりの動作モード・パラメータのシリアライズおよびデシリアライズを担当するコンバーター
 */
object KarakuriMotionSerializer {

  /**
   * インスタンスの現在の activeMotion から (motion_type, motion_data_json) を生成
   */
  fun serialize(instance: KarakuriInstance): Pair<String?, String?> {
    return serializeMotion(instance.activeMotion, instance.id)
  }

  /**
   * MotionController オブジェクトから直接 (motion_type, motion_data_json) を生成
   */
  fun serializeMotion(motion: MotionController?, instanceId: String? = null): Pair<String?, String?> {
    if (motion == null) return Pair(null, null)

    return when (motion) {
      is DoorMotion -> {
        val obj = JsonObject().apply {
          addProperty("axis", motion.axis.name)
          addProperty("openAngleDeg", motion.openAngleDeg)
          addProperty("rpm", motion.rpm)
          addProperty("autoCloseTicks", motion.autoCloseTicks)
          addProperty("clockwise", motion.clockwise)
          addProperty("isOpened", motion.isOpened)
        }
        Pair("DOOR", obj.toString())
      }
      is ContinuousSpinMotion -> {
        val obj = JsonObject().apply {
          addProperty("axis", motion.axis.name)
          addProperty("degreesPerSecond", motion.degreesPerSecond)
          addProperty("isRunning", motion.isRunning)
        }
        Pair("CONTINUOUS_SPIN", obj.toString())
      }
      is AngleRotationMotion -> {
        val obj = JsonObject().apply {
          addProperty("axis", motion.axis.name)
          addProperty("targetAngleDeg", motion.targetAngleDeg)
          addProperty("durationTicks", motion.durationTicks)
        }
        Pair("ANGLE_ROTATION", obj.toString())
      }
      is IntegratedLinearMotion -> {
        val obj = JsonObject().apply {
          motion.startLocation?.let {
            addProperty("startX", it.x)
            addProperty("startY", it.y)
            addProperty("startZ", it.z)
          }
          motion.endLocation?.let {
            addProperty("endX", it.x)
            addProperty("endY", it.y)
            addProperty("endZ", it.z)
          }
          addProperty("durationTicks", motion.durationTicks)
          addProperty("loopMode", motion.loopMode.name)
          addProperty("pauseTicks", motion.pauseTicks)
          addProperty("blocksPerSecond", motion.blocksPerSecond)
          addProperty("isRunning", motion.isRunning)
          addProperty("isAtEnd", motion.isAtEnd)
        }
        Pair("LINEAR", obj.toString())
      }
      is ProgramMotion -> {
        val targetId = instanceId ?: ""
        val config = KarakuriProgramGUI.programConfigs[targetId] ?: KarakuriProgramGUI.getConfig(targetId)
        val obj = JsonObject().apply {
          addProperty("loopMode", config.loopMode.name)
          addProperty("isPlaying", motion.isPlaying)
          val stepsArray = com.google.gson.JsonArray()
          for (step in config.steps) {
            val sObj = JsonObject().apply {
              addProperty("actionType", step.actionType.name)
              addProperty("moveDx", step.moveDx)
              addProperty("moveDy", step.moveDy)
              addProperty("moveDz", step.moveDz)
              addProperty("moveDurationTicks", step.moveDurationTicks)
              addProperty("rotateAxis", step.rotateAxis.name)
              addProperty("rotateAngleDeg", step.rotateAngleDeg)
              addProperty("rotateDurationTicks", step.rotateDurationTicks)
              addProperty("spinAxis", step.spinAxis.name)
              addProperty("spinRpm", step.spinRpm)
              addProperty("spinDurationTicks", step.spinDurationTicks)
              addProperty("waitTicks", step.waitTicks)
              addProperty("isSimultaneous", step.isSimultaneous)
              addProperty("speedAxis", step.speedAxis.name)
              addProperty("speedStartRpm", step.speedStartRpm)
              addProperty("speedEndRpm", step.speedEndRpm)
              addProperty("speedDurationTicks", step.speedDurationTicks)
              addProperty("speedIsClockwise", step.speedIsClockwise)
            }
            stepsArray.add(sObj)
          }
          add("steps", stepsArray)
        }
        Pair("PROGRAM", obj.toString())
      }
      is ChildContraptionMotion -> {
        val obj = JsonObject().apply {
          addProperty("parentId", motion.parentId)
          addProperty("pistonX", motion.pistonOriginLoc.x)
          addProperty("pistonY", motion.pistonOriginLoc.y)
          addProperty("pistonZ", motion.pistonOriginLoc.z)
          addProperty("pistonFacing", motion.pistonFacing.name)
          addProperty("followParentRotation", motion.followParentRotation)
          addProperty("rpm", motion.rpm)
          addProperty("isClockwise", motion.isClockwise)
          addProperty("isSpinActive", motion.isSpinActive)
        }
        Pair("CHILD", obj.toString())
      }
      is ChainChildContraptionMotion -> {
        val obj = JsonObject().apply {
          addProperty("parentId", motion.parentId)
          addProperty("chainAxis", motion.chainAxis.name)
          addProperty("minX", motion.minChainLoc.x)
          addProperty("minY", motion.minChainLoc.y)
          addProperty("minZ", motion.minChainLoc.z)
          addProperty("maxX", motion.maxChainLoc.x)
          addProperty("maxY", motion.maxChainLoc.y)
          addProperty("maxZ", motion.maxChainLoc.z)
          addProperty("initX", motion.initialConnectLoc.x)
          addProperty("initY", motion.initialConnectLoc.y)
          addProperty("initZ", motion.initialConnectLoc.z)
          addProperty("speed", motion.speed)
          addProperty("linearMode", motion.linearMode.name)
          addProperty("isRunning", motion.isRunning)
          addProperty("followParentRotation", motion.followParentRotation)
          if (motion.spinAxis != null) {
            addProperty("spinAxis", motion.spinAxis!!.name)
          }
          addProperty("rpm", motion.rpm)
          addProperty("isClockwise", motion.isClockwise)
          addProperty("isSpinActive", motion.isSpinActive)
        }
        Pair("CHAIN_CHILD", obj.toString())
      }
      is MinecartMotion -> {
        val obj = JsonObject().apply {
          addProperty("cartUuid", motion.minecart.uniqueId.toString())
          addProperty("followRotation", motion.followRotation)
          addProperty("followSlope", motion.followSlope)
          addProperty("forwardFacing", motion.forwardFacing.name)
          if (motion.spinAxis != null) {
            addProperty("spinAxis", motion.spinAxis!!.name)
          }
          addProperty("rpm", motion.rpm)
          addProperty("autoReverseAtRailEnd", motion.autoReverseAtRailEnd)
        }
        Pair("MINECART", obj.toString())
      }
      else -> Pair(null, null)
    }
  }

  /**
   * 保存されていた motion_type と motion_data から MotionController を復元し、インスタンスに適用
   */
  fun deserializeAndApply(
    instance: KarakuriInstance,
    motionType: String?,
    motionData: String?,
  ) {
    if (motionType == null || motionData == null) return

    val world = instance.originLocation.world

    try {
      val elem = JsonParser.parseString(motionData)
      if (!elem.isJsonObject) return
      val json = elem.asJsonObject

      when (motionType) {
        "DOOR" -> {
          val axis = Axis.valueOf(json.get("axis").asString)
          val openAngleDeg = json.get("openAngleDeg").asFloat
          val rpm = json.get("rpm").asFloat.coerceIn(0.1f, 256f)
          val autoCloseTicks = json.get("autoCloseTicks").asInt
          val clockwise = json.get("clockwise").asBoolean
          val isOpened = json.has("isOpened") && json.get("isOpened").asBoolean

          val doorMotion = DoorMotion(
            axis = axis,
            openAngleDeg = openAngleDeg,
            rpm = rpm,
            autoCloseTicks = autoCloseTicks,
            clockwise = clockwise
          )
          doorMotion.initRestoredState(isOpened)
          instance.setMotion(doorMotion, saveToDb = false)
        }

        "CONTINUOUS_SPIN" -> {
          val axis = Axis.valueOf(json.get("axis").asString)
          val degreesPerSecond = json.get("degreesPerSecond").asFloat.coerceIn(-768f, 768f)
          val isRunning = if (json.has("isRunning")) json.get("isRunning").asBoolean else true

          val spinMotion = ContinuousSpinMotion(
            axis = axis,
            degreesPerSecond = degreesPerSecond,
            isRunning = isRunning
          )
          instance.setMotion(spinMotion, saveToDb = false)
        }

        "ANGLE_ROTATION" -> {
          val axis = Axis.valueOf(json.get("axis").asString)
          val targetAngleDeg = json.get("targetAngleDeg").asFloat
          val durationTicks = json.get("durationTicks").asInt

          val angleMotion = AngleRotationMotion(
            axis = axis,
            targetAngleDeg = targetAngleDeg,
            durationTicks = durationTicks
          )
          instance.setMotion(angleMotion, saveToDb = false)
        }

        "LINEAR" -> {
          val startLoc = if (json.has("startX")) {
            Location(world, json.get("startX").asDouble, json.get("startY").asDouble, json.get("startZ").asDouble)
          } else instance.originLocation.clone()

          val endLoc = if (json.has("endX")) {
            Location(world, json.get("endX").asDouble, json.get("endY").asDouble, json.get("endZ").asDouble)
          } else startLoc.clone().add(0.0, 3.0, 0.0)

          val durationTicks = json.get("durationTicks").asInt
          val loopMode = try { LinearLoopMode.valueOf(json.get("loopMode").asString) } catch (_: Exception) { LinearLoopMode.AUTO_PING_PONG }
          val pauseTicks = json.get("pauseTicks").asInt
          val blocksPerSecond = if (json.has("blocksPerSecond")) json.get("blocksPerSecond").asDouble else 2.0
          val isRunning = json.has("isRunning") && json.get("isRunning").asBoolean

          val linearMotion = IntegratedLinearMotion(
            startLocation = startLoc,
            endLocation = endLoc,
            durationTicks = durationTicks,
            loopMode = loopMode,
            pauseTicks = pauseTicks,
            blocksPerSecond = blocksPerSecond
          )
          instance.setMotion(linearMotion, saveToDb = false)

          // サーバー停止直前に動作中だった場合、自動で動作を再開！
          if (isRunning) {
            linearMotion.toggle(instance)
          }
        }

        "PROGRAM" -> {
          val loopMode = ProgramLoopMode.valueOf(json.get("loopMode").asString)
          val isPlaying = if (json.has("isPlaying")) json.get("isPlaying").asBoolean else false
          val stepsList = mutableListOf<StepConfig>()

          if (json.has("steps")) {
            val stepsArray = json.getAsJsonArray("steps")
            for (stepElem in stepsArray) {
              val sObj = stepElem.asJsonObject
              stepsList.add(
                StepConfig(
                  actionType = ActionType.valueOf(sObj.get("actionType").asString),
                  moveDx = sObj.get("moveDx").asDouble,
                  moveDy = sObj.get("moveDy").asDouble,
                  moveDz = sObj.get("moveDz").asDouble,
                  moveDurationTicks = sObj.get("moveDurationTicks").asInt,
                  rotateAxis = Axis.valueOf(sObj.get("rotateAxis").asString),
                  rotateAngleDeg = sObj.get("rotateAngleDeg").asFloat,
                  rotateDurationTicks = sObj.get("rotateDurationTicks").asInt,
                  spinAxis = Axis.valueOf(sObj.get("spinAxis").asString),
                  spinRpm = sObj.get("spinRpm").asFloat.coerceIn(-256f, 256f),
                  spinDurationTicks = sObj.get("spinDurationTicks").asInt,
                  waitTicks = sObj.get("waitTicks").asInt,
                  isSimultaneous = sObj.get("isSimultaneous").asBoolean,
                  speedAxis = if (sObj.has("speedAxis")) Axis.valueOf(sObj.get("speedAxis").asString) else Axis.Y,
                  speedStartRpm = (if (sObj.has("speedStartRpm")) sObj.get("speedStartRpm").asFloat else 32f).coerceIn(0f, 256f),
                  speedEndRpm = (if (sObj.has("speedEndRpm")) sObj.get("speedEndRpm").asFloat else 16f).coerceIn(0f, 256f),
                  speedDurationTicks = if (sObj.has("speedDurationTicks")) sObj.get("speedDurationTicks").asInt else 120,
                  speedIsClockwise = if (sObj.has("speedIsClockwise")) sObj.get("speedIsClockwise").asBoolean else true,
                )
              )
            }
          }

          val config = KarakuriProgramGUI.ProgramConfig(steps = stepsList, loopMode = loopMode)
          KarakuriProgramGUI.programConfigs[instance.id] = config

          val actions = config.steps.mapNotNull { it.toAction() }
          if (actions.isNotEmpty()) {
            val progMotion = ProgramMotion(actions, loopMode)
            progMotion.initRestoredState(isPlaying)
            instance.setMotion(progMotion, saveToDb = false)
          }
        }

        "CHILD" -> {
          val parentId = json.get("parentId").asString
          val pistonLoc = Location(
            world,
            json.get("pistonX").asDouble,
            json.get("pistonY").asDouble,
            json.get("pistonZ").asDouble
          )
          val pistonFacing = BlockFace.valueOf(json.get("pistonFacing").asString)
          val followParentRotation = json.get("followParentRotation").asBoolean
          val rpm = json.get("rpm").asFloat.coerceIn(0.1f, 256f)
          val isClockwise = json.get("isClockwise").asBoolean
          val isSpinActive = json.get("isSpinActive").asBoolean

          val childMotion = ChildContraptionMotion(
            parentId = parentId,
            pistonOriginLoc = pistonLoc,
            pistonFacing = pistonFacing,
            followParentRotation = followParentRotation,
            rpm = rpm,
            isClockwise = isClockwise,
            isSpinActive = isSpinActive
          )
          instance.setMotion(childMotion, saveToDb = false)
        }

        "CHAIN_CHILD" -> {
          val parentId = json.get("parentId").asString
          val chainAxis = org.bukkit.Axis.valueOf(json.get("chainAxis").asString)
          val minLoc = Location(world, json.get("minX").asDouble, json.get("minY").asDouble, json.get("minZ").asDouble)
          val maxLoc = Location(world, json.get("maxX").asDouble, json.get("maxY").asDouble, json.get("maxZ").asDouble)
          val initLoc = Location(world, json.get("initX").asDouble, json.get("initY").asDouble, json.get("initZ").asDouble)
          val speed = json.get("speed").asDouble
          val linearMode = try { LinearLoopMode.valueOf(json.get("linearMode").asString) } catch (_: Exception) { LinearLoopMode.AUTO_PING_PONG }
          val isRunning = json.get("isRunning").asBoolean
          val followParentRotation = json.get("followParentRotation").asBoolean
          val spinAxis = if (json.has("spinAxis") && !json.get("spinAxis").isJsonNull) {
            Axis.valueOf(json.get("spinAxis").asString)
          } else null
          val rpm = json.get("rpm").asFloat.coerceIn(0.1f, 256f)
          val isClockwise = json.get("isClockwise").asBoolean
          val isSpinActive = json.get("isSpinActive").asBoolean

          val chainMotion = ChainChildContraptionMotion(
            parentId = parentId,
            chainAxis = chainAxis,
            minChainLoc = minLoc,
            maxChainLoc = maxLoc,
            initialConnectLoc = initLoc,
            speed = speed,
            linearMode = linearMode,
            isRunning = isRunning,
            followParentRotation = followParentRotation,
            spinAxis = spinAxis,
            rpm = rpm,
            isClockwise = isClockwise,
            isSpinActive = isSpinActive
          )
          instance.setMotion(chainMotion, saveToDb = false)
        }

        "MINECART" -> {
          // トロッコ連動の復元
          val followRot = if (json.has("followRotation")) json.get("followRotation").asBoolean else true
          val followSlope = if (json.has("followSlope")) json.get("followSlope").asBoolean else true
          val forwardFacing = if (json.has("forwardFacing")) {
            BlockFace.valueOf(json.get("forwardFacing").asString)
          } else BlockFace.SOUTH
          val spinAxis = if (json.has("spinAxis") && !json.get("spinAxis").isJsonNull) {
            Axis.valueOf(json.get("spinAxis").asString)
          } else null
          val rpm = (if (json.has("rpm")) json.get("rpm").asFloat else 16f).coerceIn(-256f, 256f)
          val autoReverseAtRailEnd = if (json.has("autoReverseAtRailEnd")) json.get("autoReverseAtRailEnd").asBoolean else true

          val cartUuid = if (json.has("cartUuid")) {
            try { java.util.UUID.fromString(json.get("cartUuid").asString) } catch (_: Exception) { null }
          } else null

          var cart: org.bukkit.entity.Minecart? = null
          if (cartUuid != null) {
            cart = org.bukkit.Bukkit.getEntity(cartUuid) as? org.bukkit.entity.Minecart
          }

          if (cart == null) {
            val tag = "${io.oyasai.karakuri.engine.KarakuriManager.TAG_MINECART_INSTANCE_PREFIX}${instance.id}"
            val searchLoc = if (instance.displayEntity.isValid) instance.displayEntity.location else instance.worldPivotLocation
            val nearbyCarts = world.getNearbyEntities(searchLoc, 64.0, 64.0, 64.0) {
              it is org.bukkit.entity.Minecart && it.scoreboardTags.contains(tag)
            }
            cart = nearbyCarts.firstOrNull() as? org.bukkit.entity.Minecart

            if (cart == null) {
              cart = world.entities.filterIsInstance<org.bukkit.entity.Minecart>().firstOrNull {
                it.scoreboardTags.contains(tag)
              }
            }
          }

          if (cart != null && cart.isValid) {
            val motion = MinecartMotion(
              minecart = cart,
              followRotation = followRot,
              followSlope = followSlope,
              forwardFacing = forwardFacing,
              spinAxis = spinAxis,
              rpm = rpm,
              autoReverseAtRailEnd = autoReverseAtRailEnd
            )
            instance.setMotion(motion, saveToDb = false)
            motion.requestUpdate()
            org.bukkit.Bukkit.getLogger().info("[Karakuri] からくり [${instance.id}] をトロッコ [${cart.uniqueId}] に正常に再マウント・復元しました。")
          } else {
            // サーバー起動直後等でエンティティ未ロードの場合に備え、遅延リトライをスケジュール
            org.bukkit.Bukkit.getScheduler().runTaskLater(io.oyasai.karakuri.KarakuriPlugin.instance, Runnable {
              if (instance.activeMotion != null) return@Runnable
              val tag = "${io.oyasai.karakuri.engine.KarakuriManager.TAG_MINECART_INSTANCE_PREFIX}${instance.id}"
              val delayedCart = (cartUuid?.let { org.bukkit.Bukkit.getEntity(it) } as? org.bukkit.entity.Minecart)
                ?: world.entities.filterIsInstance<org.bukkit.entity.Minecart>().firstOrNull { it.scoreboardTags.contains(tag) }
              if (delayedCart != null && delayedCart.isValid) {
                val motion = MinecartMotion(
                  minecart = delayedCart,
                  followRotation = followRot,
                  followSlope = followSlope,
                  forwardFacing = forwardFacing,
                  spinAxis = spinAxis,
                  rpm = rpm,
                  autoReverseAtRailEnd = autoReverseAtRailEnd
                )
                instance.setMotion(motion, saveToDb = false)
                for (other in io.oyasai.karakuri.KarakuriPlugin.instance.manager.getAll()) {
                  val chm = other.activeMotion
                  if (chm is ChildContraptionMotion && chm.parentId == instance.id) {
                    chm.requestSnap()
                  } else if (chm is ChainChildContraptionMotion && chm.parentId == instance.id) {
                    chm.requestSnap()
                  }
                }
                org.bukkit.Bukkit.getLogger().info("[Karakuri] からくり [${instance.id}] を遅延ロードされたトロッコ [${delayedCart.uniqueId}] に再マウント・復元しました。")
              } else {
                org.bukkit.Bukkit.getLogger().warning("[Karakuri] からくり [${instance.id}] の連動トロッコが見つからなかったため、停止状態で保持しました。")
              }
            }, 20L)
          }
        }
      }
    } catch (e: Exception) {
      org.bukkit.Bukkit.getLogger().warning("[Karakuri] モーション復元失敗 [${instance.id}]: ${e.message}")
    }
  }
}
