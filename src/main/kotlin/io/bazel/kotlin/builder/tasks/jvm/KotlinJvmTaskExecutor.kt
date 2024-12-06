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
import io.bazel.kotlin.builder.toolchain.KotlincInvoker
import io.bazel.kotlin.builder.utils.bazelRuleKind
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.model.JvmCompilationTask
import java.nio.file.Path

class KotlinJvmTaskExecutor(
  private val compiler: KotlincInvoker,
  private val plugins: InternalCompilerPlugins,
) {
  fun execute(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
  ) {
    val preprocessedTask = runPlugins(preProcessingSteps(task, context), context, plugins, compiler)
    context.execute("compile classes") {
      if (preprocessedTask.compileKotlin) {
        context.execute("kotlinc") {
          doCompileKotlin(preprocessedTask, context, compiler, plugins)
        }
      }
      doExecute(preprocessedTask, context)
    }
  }
}

private fun doCompileKotlin(
  preprocessedTask: JvmCompilationTask,
  context: CompilationTaskContext,
  compiler: KotlincInvoker,
  plugins: InternalCompilerPlugins,
) {
  val outputs = preprocessedTask.outputs
  compileKotlin(
    compilationTask = preprocessedTask,
    context = context,
    compiler = compiler,
    args = preprocessedTask.baseArgs().given(outputs.jdeps).notEmpty {
      plugin(plugins.jdeps) {
        flag("output", outputs.jdeps)
        flag("target_label", preprocessedTask.info.label)
        preprocessedTask.inputs.directDependenciesList.forEach {
          flag("direct_dependencies", it)
        }
        flag("strict_kotlin_deps", preprocessedTask.info.strictKotlinDeps)
      }
    }.given(outputs.jar).notEmpty {
      append(codeGenArgs(preprocessedTask))
    }.given(outputs.abijar).notEmpty {
      plugin(plugins.jvmAbiGen) {
        flag("outputDir", preprocessedTask.directories.abiClasses)
      }
      given(outputs.jar).empty {
        plugin(plugins.skipCodeGen)
      }
    },
    printOnFail = false,
  )
}

private fun doExecute(
  preprocessedTask: JvmCompilationTask,
  context: CompilationTaskContext,
) {
  val outputs = preprocessedTask.outputs
  if (outputs.jar.isNotEmpty()) {
    if (preprocessedTask.instrumentCoverage) {
      context.execute(
        "create instrumented jar",
        preprocessedTask::createCoverageInstrumentedJar,
      )
    } else {
      context.execute("create jar", preprocessedTask::createOutputJar)
    }
  }
  if (outputs.abijar.isNotEmpty()) {
    context.execute("create abi jar") {
      JarCreator(
        path = Path.of(preprocessedTask.outputs.abijar),
        targetLabel = preprocessedTask.info.label,
        injectingRuleKind = preprocessedTask.info.bazelRuleKind,
      ).use {
        it.addDirectory(Path.of(preprocessedTask.directories.abiClasses))
        it.addDirectory(Path.of(preprocessedTask.directories.generatedClasses))
      }
    }
  }
  if (outputs.generatedJavaSrcJar.isNotEmpty()) {
    context.execute("creating KAPT generated Java source jar") {
      JarCreator(
        path = Path.of(preprocessedTask.outputs.generatedJavaSrcJar),
        targetLabel = preprocessedTask.info.label,
        injectingRuleKind = preprocessedTask.info.bazelRuleKind,
      ).use {
        it.addDirectory(Path.of(preprocessedTask.directories.generatedJavaSources))
      }
    }
  }
  if (outputs.generatedJavaStubJar.isNotEmpty()) {
    context.execute("creating KAPT generated Kotlin stubs jar") {
      JarCreator(
        path = Path.of(preprocessedTask.outputs.generatedJavaStubJar),
        targetLabel = preprocessedTask.info.label,
        injectingRuleKind = preprocessedTask.info.bazelRuleKind,
      ).use {
        it.addDirectory(Path.of(preprocessedTask.directories.incrementalData))
      }
    }
  }
  if (outputs.generatedClassJar.isNotEmpty()) {
    context.execute("creating KAPT generated stub class jar") {
      JarCreator(
        path = Path.of(preprocessedTask.outputs.generatedClassJar),
        targetLabel = preprocessedTask.info.label,
        injectingRuleKind = preprocessedTask.info.bazelRuleKind,
      ).use {
        it.addDirectory(Path.of(preprocessedTask.directories.generatedClasses))
      }
    }
  }
  if (outputs.generatedKspSrcJar.isNotEmpty()) {
    context.execute("creating KSP generated src jar") {
      JarCreator(
        path = Path.of(preprocessedTask.outputs.generatedKspSrcJar),
        targetLabel = preprocessedTask.info.label,
        injectingRuleKind = preprocessedTask.info.bazelRuleKind,
      ).use {
        it.addDirectory(Path.of(preprocessedTask.directories.generatedSources))
        it.addDirectory(Path.of(preprocessedTask.directories.generatedJavaSources))
      }
    }
  }
}
