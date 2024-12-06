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

import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.*

private enum class Flag {
  PESSIMIST,
  OPTIMIST,
  IMMATERIAL
}

@RunWith(JUnit4::class)
class ArgMapTest {
  @Test
  fun hasAll() {
    val args = ArgMap(
      EnumMap<Flag, MutableList<String>>(
        mapOf(
          Flag.PESSIMIST to mutableListOf(),
          Flag.OPTIMIST to mutableListOf("half"),
        ),
      ),
    )
    Truth.assertThat(args.has(Flag.OPTIMIST)).isTrue()
    Truth.assertThat(args.has(Flag.PESSIMIST)).isFalse()
    Truth.assertThat(args.has(Flag.OPTIMIST)).isFalse()
    Truth.assertThat(args.has(Flag.IMMATERIAL)).isFalse()
  }
}
