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
package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.toolchain.KotlinToolchain
import io.bazel.kotlin.builder.toolchain.KotlincInvoker
import io.bazel.kotlin.builder.utils.bazelRuleKind
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.model.JvmCompilationTask
import java.nio.file.Path

/**
 * Due to an inconsistency in the handling of -Xfriends-path, jvm uses a comma (property list
 * separator)
 */
private const val X_FRIENDS_PATH_SEPARATOR = ","

class KotlinJvmTaskExecutor(
  private val toolchain: KotlinToolchain,
) {
  private val compiler = KotlincInvoker(baseJars = toolchain.getBaseJarsWithReflect())

  fun execute(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
  ) {
    runPlugins(
      task = task,
    )
    context.execute("compile classes") {
      if (task.compileKotlin) {
        context.execute("kotlinc") {
          doCompileKotlin(task, context, compiler, toolchain)
        }
      }
      doExecute(task, context)
    }
  }
}

private fun doCompileKotlin(
  task: JvmCompilationTask,
  context: CompilationTaskContext,
  compiler: KotlincInvoker,
  toolchain: KotlinToolchain,
) {
  val outputs = task.outputs

  val args = baseArgs(task)
  val inputs = task.inputs
  outputs.jar?.let {
    if (task.friendPaths.isNotEmpty()) {
      @Suppress("SpellCheckingInspection")
      args.value("-Xfriend-paths=" + task.friendPaths.joinToString(X_FRIENDS_PATH_SEPARATOR))
    }

    args.flag("-d", task.directories.classes.toString())
    args.values(task.info.passthroughFlags)
  }

  if (outputs.jar == null) {
    args.plugin(toolchain.skipCodeGen)
  }

  configurePlugins(
    args = args,
    task = task,
    options = inputs.compilerPluginOptions,
    classpath = inputs.compilerPluginClasspath,
  )

  args
    .values(inputs.javaSources)
    .values(inputs.kotlinSources)
    .flag("-d", task.directories.classes.toString())

  compileKotlin(
    compilationTask = task,
    context = context,
    compiler = compiler,
    args = args.toList(),
    printOnFail = false,
  )
}

private fun doExecute(
  task: JvmCompilationTask,
  context: CompilationTaskContext,
) {
  val outputs = task.outputs
  if (outputs.jar != null) {
    context.execute("create jar") { createOutputJar(task) }
  }
  if (outputs.abiJar != null) {
    context.execute("create abi jar") {
      JarCreator(
        path = outputs.abiJar!!,
        targetLabel = task.info.label,
        injectingRuleKind = task.info.bazelRuleKind,
      ).use {
        it.addDirectory(task.directories.abiClasses!!)
        it.addDirectory(task.directories.generatedClasses)
      }
    }
  }
  if (!outputs.generatedJavaSrcJar.isNullOrEmpty()) {
    context.execute("creating KAPT generated Java source jar") {
      JarCreator(
        path = Path.of(outputs.generatedJavaSrcJar),
        targetLabel = task.info.label,
        injectingRuleKind = task.info.bazelRuleKind,
      ).use {
        it.addDirectory(task.directories.generatedJavaSources)
      }
    }
  }
  if (!outputs.generatedClassJar.isNullOrEmpty()) {
    context.execute("creating KAPT generated stub class jar") {
      JarCreator(
        path = Path.of(outputs.generatedClassJar),
        targetLabel = task.info.label,
        injectingRuleKind = task.info.bazelRuleKind,
      ).use {
        it.addDirectory(task.directories.generatedClasses)
      }
    }
  }
  if (outputs.generatedKspSrcJar != null) {
    context.execute("creating KSP generated src jar") {
      JarCreator(
        path = task.outputs.generatedKspSrcJar!!,
        targetLabel = task.info.label,
        injectingRuleKind = task.info.bazelRuleKind,
      ).use {
        it.addDirectory(task.directories.generatedSources)
        it.addDirectory(task.directories.generatedJavaSources)
      }
    }
  }
}
