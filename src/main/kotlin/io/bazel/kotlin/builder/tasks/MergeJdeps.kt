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

package io.bazel.kotlin.builder.tasks

import io.bazel.kotlin.builder.tasks.jvm.JdepsMerger
import io.bazel.kotlin.builder.tasks.jvm.JdepsMergerFlags
import io.bazel.kotlin.builder.utils.ArgMap
import io.bazel.kotlin.builder.utils.ArgMaps
import io.bazel.worker.Status
import io.bazel.worker.Work
import io.bazel.worker.WorkerContext
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files

class MergeJdeps : Work {
  private val FLAGFILE_RE = Regex("""^--flagfile=((.*)-(\d+).params)$""")

  override fun invoke(
    ctx: WorkerContext.TaskContext,
    args: Iterable<String>,
  ): Status {
    val argMap = getArgs(args.toList())
    val inputs = argMap.mandatory(JdepsMergerFlags.INPUTS)
    val output = argMap.mandatorySingle(JdepsMergerFlags.OUTPUT)
    val label = argMap.mandatorySingle(JdepsMergerFlags.TARGET_LABEL)
    val reportUnusedDeps = argMap.mandatorySingle(JdepsMergerFlags.REPORT_UNUSED_DEPS)
    val result = JdepsMerger.merge(
      ctx = ctx,
      label = label,
      inputs = inputs,
      output = output,
      reportUnusedDeps = reportUnusedDeps,
    )
    return if (result == 0) Status.SUCCESS else Status.ERROR
  }

  private fun getArgs(args: List<String>): ArgMap {
    check(args.isNotEmpty()) { "expected at least a single arg got: ${args.joinToString(" ")}" }
    val lines = FLAGFILE_RE.matchEntire(args[0])?.groups?.get(1)?.let {
      Files.readAllLines(FileSystems.getDefault().getPath(it.value), StandardCharsets.UTF_8)
    } ?: args

    return ArgMaps.from(lines)
  }
}
