/*
 * Copyright 2021 The Bazel Authors. All rights reserved.
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

package io.bazel.kotlin.builder.cmd

import io.bazel.kotlin.builder.tasks.buildKotlin
import io.bazel.kotlin.builder.tasks.jvm.KotlinJvmTaskExecutor
import io.bazel.kotlin.builder.toolchain.KotlinToolchain
import io.bazel.worker.createWorker
import kotlin.system.exitProcess

object Build {
  @JvmStatic
  fun main(args: Array<String>) {
    val jvmTaskExecutor = KotlinJvmTaskExecutor(KotlinToolchain.createToolchain())
    val status = createWorker(args.asList()).start { taskContext, args ->
      buildKotlin(
        workingDir = taskContext.workingDir,
        args = args,
        jvmTaskExecutor = jvmTaskExecutor,
        out = taskContext.logging.asPrintStream(),
        logging = taskContext.logging,
      )
    }
    exitProcess(status)
  }
}
