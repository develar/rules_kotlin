/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.compiler

import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services

@Suppress("unused")
class BazelK2JVMCompiler {
  companion object {
    @JvmStatic
    fun exec(
      errStream: java.io.PrintStream,
      args: Array<String>,
    ): Int {
      val compiler = K2JVMCompiler()
      val arguments = compiler.createArguments()
      compiler.parseArguments(args, arguments)

      val collector =
        PrintingMessageCollector(errStream, MessageRenderer.PLAIN_RELATIVE_PATHS, arguments.verbose)
      return compiler.exec(collector, Services.EMPTY, arguments).code
    }
  }
}
