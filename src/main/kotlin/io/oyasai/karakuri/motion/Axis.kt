package io.oyasai.karakuri.motion

import org.joml.Vector3f

/**
 * からくりの回転軸を定義する列挙型
 */
enum class Axis(val vector: Vector3f) {
  X(Vector3f(1.0f, 0.0f, 0.0f)),
  Y(Vector3f(0.0f, 1.0f, 0.0f)),
  Z(Vector3f(0.0f, 0.0f, 1.0f));

  companion object {
    fun fromString(str: String): Axis? {
      return when (str.lowercase()) {
        "x", "pitch" -> X
        "y", "yaw" -> Y
        "z", "roll" -> Z
        else -> null
      }
    }
  }
}
