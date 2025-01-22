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

// Provides extensions for the JvmCompilationTask protocol buffer.
package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.toolchain.KotlincInvoker
import io.bazel.kotlin.builder.utils.bazelRuleKind
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.builder.utils.partitionJvmSources
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

private const val API_VERSION_ARG = "-api-version"
private const val LANGUAGE_VERSION_ARG = "-language-version"

private const val MANIFEST_DIR = "META-INF/"

private fun createClasspath(task: JvmCompilationTask): String {
  return task.inputs.classpath.joinToString(File.pathSeparator) { it.toString() }
}

internal fun baseArgs(
  task: JvmCompilationTask,
  overrides: Map<String, String> = emptyMap(),
): CompilationArgs {
  val compilationArgs = CompilationArgs()
  var classpath = createClasspath(task)
  if (Files.exists(task.directories.generatedClasses)) {
    classpath += File.pathSeparator + task.directories.generatedClasses.toString()
  }
  return compilationArgs
    .flag("-cp", classpath)
    .flag(API_VERSION_ARG, overrides[API_VERSION_ARG] ?: task.info.toolchainInfo.apiVersion)
    .flag(
      LANGUAGE_VERSION_ARG,
      overrides[LANGUAGE_VERSION_ARG] ?: task.info.toolchainInfo.languageVersion,
    )
    .flag("-jvm-target", task.jvmTarget!!)
    .flag("-module-name", task.info.moduleName)
}

internal fun configurePlugins(
  task: JvmCompilationTask,
  options: List<String>,
  classpath: List<Path>,
  args: CompilationArgs,
) {
  for (it in classpath) {
    args.xFlag("plugin", it.toString())
  }

  val dirs = task.directories
  val optionTokens = mapOf(
    "{generatedClasses}" to dirs.generatedClasses,
    "{stubs}" to dirs.temp.resolve("stubs").toString(),
    "{temp}" to dirs.temp.toString(),
    "{generatedSources}" to dirs.generatedSources,
    "{classpath}" to classpath.joinToString(File.pathSeparator),
  )
  for (opt in options) {
    val formatted = optionTokens.entries.fold(opt) { formatting, (token, value) ->
      formatting.replace(token, value.toString())
    }
    args.flag("-P", "plugin:$formatted")
  }
}

internal fun runPlugins(
  task: JvmCompilationTask,
) {
  val inputs = task.inputs
  if ((inputs.processors.isEmpty() && inputs.stubsPluginClasspath.isEmpty()) ||
    inputs.kotlinSources.isEmpty()) {
    return
  }
}

/**
 * Produce the primary output jar.
 */
internal fun createOutputJar(task: JvmCompilationTask) {
  JarCreator(
    path = task.outputs.jar!!,
    targetLabel = task.info.label,
    injectingRuleKind = task.info.bazelRuleKind,
  ).use {
    it.addDirectory(task.directories.classes)
    it.addDirectory(task.directories.generatedClasses)
  }
}

/**
 * Compiles Kotlin sources to classes. Does not compile Java sources.
 */
fun compileKotlin(
  compilationTask: JvmCompilationTask,
  context: CompilationTaskContext,
  compiler: KotlincInvoker,
  args: List<String>,
  printOnFail: Boolean = true,
): List<String> {
  val inputs = compilationTask.inputs
  if (inputs.kotlinSources.isEmpty()) {
    return emptyList()
  }

  val dirs = compilationTask.directories
  context.whenTracing {
    context.printLines("compileKotlin arguments:\n", args.asSequence())
  }
  val output = context.executeCompilerTask(
    args = args,
    compiler = compiler,
    printOnFail = printOnFail,
  )
  if (context.isTracing) {
    context.printLines(
      "kotlinc Files Created:",
      sequenceOf(
        dirs.classes,
        dirs.generatedClasses,
        dirs.generatedSources,
        dirs.generatedJavaSources,
        dirs.temp,
      )
        .flatMap { filePath ->
          if (Files.isDirectory(filePath)) {
            Files.walk(filePath).use { file ->
              file.filter { !Files.isDirectory(it) }.collect(Collectors.toList())
            }
          } else {
            emptyList()
          }
        }
        .map { it.toString() },
    )
  }
  return output
}

internal fun expandWithSources(
  sources: Iterator<String>,
  kotlinSources: MutableList<String>,
  javaSources: MutableList<String>,
  srcJarsDir: Path,
  generatedClasses: Path,
) {
  filterOutNonCompilableSources(copyManifestFilesToGeneratedClasses(
    iterator = sources,
    srcJarsDir = srcJarsDir,
    generatedClasses = generatedClasses
  ))
    .partitionJvmSources(
      kt = { kotlinSources.add(it) },
      java = { javaSources.add(it) },
    )
}

/**
 * Copy generated manifest files from KSP task into generated folder
 */
private fun copyManifestFilesToGeneratedClasses(
  iterator: Iterator<String>,
  srcJarsDir: Path,
  generatedClasses: Path,
): Iterator<String> {
  val result = mutableSetOf<String>()
  for (it in iterator) {
    if (it.contains("/$MANIFEST_DIR")) {
      val path = Path.of(it)
      if (Files.exists(srcJarsDir)) {
        val relativePath = srcJarsDir.relativize(path)
        val destPath = generatedClasses.resolve(relativePath)
        Files.createDirectories(destPath.parent)
        Files.copy(path, destPath, StandardCopyOption.REPLACE_EXISTING)
      }
    }
    result.add(it)
  }
  return result.iterator()
}

/**
 * Only keep java and kotlin files for the iterator. Filter all other non-compilable files.
 */
private fun filterOutNonCompilableSources(iterator: Iterator<String>): Iterator<String> {
  val result = mutableListOf<String>()
  iterator.forEach {
    if (it.endsWith(".kt") or it.endsWith(".java")) result.add(it)
  }
  return result.iterator()
}
