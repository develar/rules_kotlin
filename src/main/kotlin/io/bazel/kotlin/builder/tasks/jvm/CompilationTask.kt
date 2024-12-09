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

import com.google.devtools.build.lib.view.proto.Deps
import com.google.devtools.build.lib.view.proto.Deps.Dependencies
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.toolchain.KotlinToolchain
import io.bazel.kotlin.builder.toolchain.KotlincInvoker
import io.bazel.kotlin.builder.utils.bazelRuleKind
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.builder.utils.partitionJvmSources
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.sequences.plus

private const val API_VERSION_ARG = "-api-version"
private const val LANGUAGE_VERSION_ARG = "-language-version"

private const val MANIFEST_DIR = "META-INF/"

private fun createClasspath(task: JvmCompilationTask): String {
  if (task.info.reducedClasspathMode != "KOTLINBUILDER_REDUCED") {
    return task.inputs.classpath.joinToString(File.pathSeparator) { it.toString() }
  }

  val transitiveDepsForCompile = LinkedHashSet<String>()
  for (jdepsPath in task.inputs.depsArtifacts) {
    BufferedInputStream(Files.newInputStream(Path.of(jdepsPath))).use {
      val deps = Dependencies.parseFrom(it)
      for (dep in deps.dependencyList) {
        if (dep.kind == Deps.Dependency.Kind.EXPLICIT) {
          transitiveDepsForCompile.add(dep.path)
        }
      }
    }
  }

  return (task.inputs.directDependencies.asSequence() + transitiveDepsForCompile)
    .joinToString(File.pathSeparator)
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

internal fun kspArgs(task: JvmCompilationTask, toolchain: KotlinToolchain): CompilationArgs {
  val dirs = task.directories
  val generatedJavaSources = dirs.generatedJavaSources
  clearDirContent(generatedJavaSources)

  val args = CompilationArgs()
  args.plugin(toolchain.kspSymbolProcessingCommandLine)
  args.plugin(toolchain.kspSymbolProcessingApi) {
    args.flag("-Xallow-no-source-files")
    val values = arrayOf(
      "apclasspath" to listOf(task.inputs.processorPaths.joinToString(File.pathSeparator)),
      // projectBaseDir shouldn't matter because incremental is disabled
      "projectBaseDir" to listOf(dirs.incrementalData),
      // Disable incremental mode
      "incremental" to listOf("false"),
      // Directory where class files are written to. Files written to this directory are class
      // files being written directly from the annotation processor, not Kotlinc
      "classOutputDir" to listOf(dirs.generatedClasses),
      // Directory where generated Java sources files are written to
      "javaOutputDir" to listOf(generatedJavaSources),
      // Directory where generated Kotlin sources files are written to
      "kotlinOutputDir" to listOf(dirs.generatedSources),
      // Directory where META-INF data is written to. This might not be the most ideal place to
      // write this. Maybe just directly to the classes' directory?
      "resourceOutputDir" to listOf(dirs.generatedSources),
      // TODO(bencodes) Not sure what this directory is yet.
      "kspOutputDir" to listOf(dirs.incrementalData),
      // Directory to write KSP caches. Shouldn't matter because incremental is disabled
      "cachesDir" to listOf(dirs.incrementalData),
      // Set withCompilation to false because we run this as part of the standard kotlinc pass
      // If we ever want to flip this to true, we probably want to integrate this directly
      // into the KotlinCompile action.
      "withCompilation" to listOf("false"),
      // Set returnOkOnError to false because we want to fail the build if there are any errors
      "returnOkOnError" to listOf("false"),
      // TODO(bencodes) This should probably be enabled via some KSP options
      "allWarningsAsErrors" to listOf("false"),
    )

    for (pair in values) {
      for (value in pair.second) {
        args.flag(pair.first, value.toString())
      }
    }
  }
  return args
}

internal fun runPlugins(
  task: JvmCompilationTask,
  context: CompilationTaskContext,
  plugins: KotlinToolchain,
  compiler: KotlincInvoker,
) {
  val inputs = task.inputs
  if ((inputs.processors.isEmpty() && inputs.stubsPluginClasspath.isEmpty()) ||
    inputs.kotlinSources.isEmpty()) {
    return
  }

  if (task.outputs.generatedKspSrcJar != null) {
    runKspPlugin(
      task = task,
      context = context,
      toolchain = plugins,
      compiler = compiler,
    )
  }
}

private fun runKspPlugin(
  task: JvmCompilationTask,
  context: CompilationTaskContext,
  toolchain: KotlinToolchain,
  compiler: KotlincInvoker,
) {
  return context.execute("Ksp (${task.inputs.processors.joinToString(", ")})") {
    val overrides = mutableMapOf(
      API_VERSION_ARG to kspKotlinToolchainVersion(task.info.toolchainInfo.apiVersion),
      LANGUAGE_VERSION_ARG to kspKotlinToolchainVersion(task.info.toolchainInfo.languageVersion),
    )
    val args = baseArgs(task, overrides)
      .plus(kspArgs(task, toolchain))
      .flag("-d", task.directories.generatedClasses.toString())
      .values(task.inputs.kotlinSources)
      .values(task.inputs.javaSources)
      .toList()
    val outputLines = context.executeCompilerTask(
      args = args,
      compiler = compiler,
      printOnSuccess = context.isTracing,
    )
    // if tracing is enabled, the output should be formatted in a special way, if we aren't
    // tracing then any compiler output would make it's way to the console as is.
    context.whenTracing {
      printLines("Ksp output", outputLines.asSequence())
    }
  }
}

private fun kspKotlinToolchainVersion(version: String): String {
  // KSP doesn't support Kotlin 2.0 yet, so we need to use 1.9
  return if (version.toFloat() >= 2.0) "1.9" else version
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
    val depBuilder = Dependencies.newBuilder()
    depBuilder.ruleLabel = compilationTask.info.label
    Files.write(compilationTask.outputs.jdeps!!, depBuilder.build().toByteArray())
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

@OptIn(ExperimentalPathApi::class)
fun clearDirContent(dir: Path) {
  dir.deleteRecursively()
  Files.createDirectories(dir)
}
