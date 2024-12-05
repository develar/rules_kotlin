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

package io.bazel.worker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InvocationWorkerTest {
  @Test
  fun start() {
    val args = listOf("--mammal", "bunny")
    assertThat(
      InvocationWorker(args).start { _, actualArgs ->
        when (actualArgs) {
          args -> 0
          else -> error("want $args, got $actualArgs")
        }
      }
    ).isEqualTo(0)
  }

  @Test
  fun error() {
    assertThat(
      InvocationWorker(emptyList()).start { _, _ -> throw RuntimeException("error") }
    ).isEqualTo(1)
  }
}
