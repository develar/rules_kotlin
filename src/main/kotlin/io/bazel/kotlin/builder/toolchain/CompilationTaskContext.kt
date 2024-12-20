/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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
package io.bazel.kotlin.builder.toolchain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CompilationTaskContext(
  private val label: String,
  debug: List<String>,
  private val out: PrintStream,
  private val executionRoot: String,
) {
  private val start = System.currentTimeMillis()
  private var timings: MutableList<String>?
  private var level = -1
  @JvmField val isTracing: Boolean

  init {
    val debugging = HashSet(debug)
    timings = if (debugging.contains("timings")) mutableListOf() else null
    isTracing = debugging.contains("trace")
  }

  @Suppress("unused")
  fun print(msg: String) {
    out.println(msg)
  }

  /**
   * Print a list of debugging lines.
   *
   * @param header a header string
   * @param lines a list of lines to print out
   * @param prefix a prefix to add to each line
   * @param filterEmpty if empty lines should be discarded or not
   */
  fun printLines(
    header: String,
    lines: Sequence<String>,
    prefix: String = "|  ",
    filterEmpty: Boolean = false,
  ) {
    check(header.isNotEmpty())
    out.println(if (header.endsWith(":")) header else "$header:")
    for (line in lines) {
      if (line.isNotEmpty() || !filterEmpty) {
        out.println("$prefix$line")
      }
    }
    out.println()
  }

  inline fun whenTracing(block: CompilationTaskContext.() -> Unit) {
    if (isTracing) block() else null
  }

  /**
   * This method normalizes and reports the output from the Kotlin compiler.
   */
  fun printCompilerOutput(lines: List<String>) {
    lines.map(::trimExecutionRootPrefix).forEach(out::println)
  }

  private fun trimExecutionRootPrefix(toPrint: String): String {
    // trim off the workspace component
    if (toPrint.startsWith(executionRoot)) {
      return toPrint.replaceFirst(executionRoot, "")
    } else {
      return toPrint
    }
  }

  /**
   * Execute a compilation task.
   *
   * @throws CompilationStatusException if the compiler returns a status of anything but zero.
   * @param args the compiler command line switches
   * @param printOnFail if this is true the output will be printed if the task fails else the caller is responsible
   *  for logging it by catching the [CompilationStatusException] exception.
   */
  fun executeCompilerTask(
    args: List<String>,
    compiler: KotlincInvoker,
    printOnFail: Boolean = true,
    printOnSuccess: Boolean = true,
  ): List<String> {
    val outputStream = ByteArrayOutputStream()
    val ps = PrintStream(outputStream)
    val result = compiler.compile(args, ps)
    val output = ByteArrayInputStream(outputStream.toByteArray()).bufferedReader().readLines()
    if (result != 0) {
      if (printOnFail) {
        printCompilerOutput(output)
        throw CompilationStatusException("compile phase failed", result)
      }
      throw CompilationStatusException("compile phase failed", result, output)
    } else if (printOnSuccess) {
      printCompilerOutput(output)
    }
    return output
  }

  /**
   * Runs a task and records the timings.
   */
  fun <T> execute(
    name: String,
    task: () -> T,
  ): T = if (timings == null) task() else pushTimedTask(name, task)

  private inline fun <T> pushTimedTask(
    name: String,
    task: () -> T,
  ): T {
    level += 1
    val previousTimings = timings
    timings = mutableListOf()
    try {
      val start = System.currentTimeMillis()
      val result = task()
      val stop = System.currentTimeMillis()
      previousTimings!!.add("${"  ".repeat(level)} * $name: ${stop - start} ms")
      previousTimings.addAll(timings!!)
      return result
    } finally {
      level -= 1
      timings = previousTimings
    }
  }

  /**
   * This method should be called at the end of builder invocation.
   *
   * @param successful true if the task finished successfully.
   */
  fun finalize(successful: Boolean) {
    if (successful) {
      timings?.also {
        printLines(
          "Task timings for $label (total: ${System.currentTimeMillis() - start} ms)",
          it.asSequence(),
        )
      }
    }
  }
}
