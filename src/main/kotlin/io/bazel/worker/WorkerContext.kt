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

import io.bazel.worker.ContextLog.Granularity
import io.bazel.worker.ContextLog.Granularity.INFO
import io.bazel.worker.ContextLog.ScopeLogging
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InterruptedIOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.util.logging.StreamHandler
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** WorkerContext encapsulates logging, filesystem, and profiling for a task invocation. */
class WorkerContext @PublishedApi internal constructor(
  private val name: String = Companion::class.java.canonicalName,
  private val verbose: Granularity = INFO,
) : Closeable {
  @JvmField val scopeLogging: ScopeLogging = ContextLogger(
    name = name,
    level = verbose.level,
    propagateTo = null,
  )

  companion object {
    inline fun <T : Any?> run(
      named: String = "worker",
      verbose: Granularity = INFO,
      report: (ContextLog) -> Unit = {},
      work: (WorkerContext) -> T,
    ): T {
      val workerContext = WorkerContext(verbose = verbose, name = named)
      val status = workerContext.use(work)
      report(workerContext.scopeLogging.contents())
      return status
    }
  }

  override fun close() {
    scopeLogging.info { "ending worker context" }
  }
}

@OptIn(ExperimentalPathApi::class)
inline fun doTask(
  workingDir: Path,
  workerContext: WorkerContext,
  name: String,
  task: (sub: TaskContext) -> Int,
): TaskResult {
  workerContext.scopeLogging.info { "start task $name" }
  val subLogging = workerContext.scopeLogging.narrowTo(name)
  val tempDir = Files.createTempDirectory(workingDir, "kotlinc")
  val status = try {
    try {
      TaskResult(task(TaskContext(workingDir = tempDir, logging = subLogging)), subLogging.contents())
    } catch (e: Throwable) {
      when (e.causes.lastOrNull()) {
        is InterruptedException, is InterruptedIOException -> subLogging.error(e) { "ERROR: Interrupted" }
        else -> subLogging.error(e) { "ERROR: unexpected exception" }
      }
      TaskResult(1, subLogging.contents())
    }
  }
  finally {
    tempDir.deleteRecursively()
  }
  workerContext.scopeLogging.info { "end task $name: ${status.status}" }
  return status
}

private class ContextLogger(
  val name: String,
  val level: Level,
  val propagateTo: ContextLogger? = null,
) : ScopeLogging {
  private val profiles = mutableListOf<String>()

  private val out by lazy {
    ByteArrayOutputStream()
  }

  private val handler by lazy {
    StreamHandler(out, SimpleFormatter()).also { h ->
      h.level = this.level
    }
  }

  private val logger: Logger by lazy {
    object : Logger(name, null) {}.apply {
      level = level
      propagateTo?.apply { parent = logger }
      addHandler(handler)
    }
  }

  private val sourceName by lazy {
    propagateTo?.name ?: "global"
  }

  override fun info(msg: () -> String) {
    logger.logp(Level.INFO, sourceName, name, msg)
  }

  override fun error(
    t: Throwable,
    msg: () -> String,
  ) {
    logger.logp(Level.SEVERE, sourceName, name, t, msg)
  }

  override fun error(msg: () -> String) {
    logger.logp(Level.SEVERE, sourceName, name, msg)
  }

  override fun debug(msg: () -> String) {
    logger.logp(Level.FINE, sourceName, name, msg)
  }

  override fun narrowTo(name: String): ScopeLogging = ContextLogger(name, level, this)

  override fun contents() = handler.flush().run { ContextLog(out.toByteArray(), profiles) }

  override fun asPrintStream(): PrintStream = PrintStream(out, true)
}

data class TaskResult(
  @JvmField val status: Int,
  @JvmField val log: ContextLog,
)

@PublishedApi
internal val Throwable.causes
  get(): Sequence<Throwable> {
    return cause?.let { c -> sequenceOf(c) + c.causes } ?: emptySequence()
  }

class TaskContext @PublishedApi internal constructor(
  @JvmField val workingDir: Path,
  logging: ScopeLogging,
) : ScopeLogging by logging
