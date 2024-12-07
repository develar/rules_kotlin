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

import com.google.devtools.build.lib.worker.ProtoWorkerMessageProcessor
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkRequestCallback
import com.google.devtools.build.lib.worker.WorkRequestHandler.WorkRequestHandlerBuilder
import java.io.IOException
import java.nio.file.Path

/** Worker executes a unit of Work */
interface Worker {
  fun start(execute: Work): Int
}

fun createWorker(args: List<String>): Worker {
  return if ("--persistent_worker" in args) PersistentWorker() else InvocationWorker(args)
}

/** Task for Worker execution. */
fun interface Work {
  operator fun invoke(
    ctx: TaskContext,
    args: List<String>,
  ): Int
}

/**
 * PersistentWorker satisfies Bazel persistent worker protocol for executing work.
 *
 * Supports multiplex (https://docs.bazel.build/versions/master/multiplex-worker.html) provided
 * the work is thread/coroutine safe.
 */
@PublishedApi
internal class PersistentWorker : Worker {
  private val processWorkingDir = Path.of(".").toAbsolutePath().normalize()

  override fun start(executeTask: Work): Int {
    WorkerContext(name = "worker").use { workerContext ->
      val realStdErr = System.err
      try {
        val workerHandler = WorkRequestHandlerBuilder(
          WorkRequestCallback { request, printWriter ->
            val workingDir = request.sandboxDir?.let { processWorkingDir.resolve(it) }
              ?: processWorkingDir
            val result = doTask(
              workingDir = workingDir,
              workerContext = workerContext,
              name = "request ${request.requestId}",
              task = { taskContext -> executeTask(taskContext, request.argumentsList) },
            )
            printWriter.print(result.log.out.toString())
            result.status
          },
          realStdErr,
          ProtoWorkerMessageProcessor(System.`in`, System.out),
        )
          .build()
        workerHandler.processRequests()
      } catch (e: IOException) {
        workerContext.scopeLogging.error(e) { "Unknown IO exception" }
        e.printStackTrace(realStdErr)
        return 1
      }
      return 0
    }
  }
}

class InvocationWorker(
  private val args: List<String>,
) : Worker {
  private val processWorkingDir = Path.of(".").toAbsolutePath().normalize()

  override fun start(execute: Work): Int {
    return runCatching {
      WorkerContext.run { workerContext ->
        val result = doTask(
          workingDir = processWorkingDir,
          workerContext = workerContext,
          name = "invocation"
        ) { ctx -> execute(ctx, args) }
        print(result.log.out.toString())
        result.status
      }
    }.recover {
      1
    }.getOrThrow()
  }
}
