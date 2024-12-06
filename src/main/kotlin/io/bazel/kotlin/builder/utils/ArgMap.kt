/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.bazel.kotlin.builder.utils

import java.util.*

class ArgMap<T : Enum<T>>(
  private val map: EnumMap<T, MutableList<String>>,
) {
  /**
   * Get the mandatory single value from a key
   */
  fun mandatorySingle(key: T): String {
    return requireNotNull(optionalSingle(key)) { "$key is not optional" }
  }

  fun optionalSingle(key: T): String? {
    return map[key]?.let {
      when (it.size) {
        0 -> throw IllegalArgumentException("$key did not have a value")
        1 -> it[0]
        else -> throw IllegalArgumentException("$key should have a single value: $it")
      }
    }
  }

  fun mandatory(key: T): List<String> {
    return map[key] ?: throw IllegalArgumentException("$key is not optional")
  }

  fun has(key: T): Boolean = map[key]?.isNotEmpty() ?: false

  fun optional(key: T): List<String>? = map[key]
}

fun <T : Enum<T>> createArgMap(
  args: List<String>,
  enumClass: Class<T>,
): ArgMap<T> {
  val result = EnumMap<T, MutableList<String>>(enumClass)
  val keyString = args.first().also { require(it.startsWith("--")) { "first arg must be a flag" } }
    .substring(2)
  var currentKey = java.lang.Enum.valueOf(enumClass, keyString.uppercase())
  val currentValue = mutableListOf<String>()

  fun mergeCurrent() {
    result.computeIfAbsent(currentKey) { mutableListOf() }.addAll(currentValue)
    currentValue.clear()
  }

  for (it in args.drop(1)) {
    if (it.startsWith("--")) {
      mergeCurrent()
      currentKey = java.lang.Enum.valueOf(enumClass, it.substring(2).uppercase())
    } else {
      currentValue.add(it)
    }
  }
  mergeCurrent()
  return ArgMap(result)
}
