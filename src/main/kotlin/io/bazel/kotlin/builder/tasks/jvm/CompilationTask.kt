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
import io.bazel.kotlin.builder.utils.IS_JVM_SOURCE_FILE
import io.bazel.kotlin.builder.utils.bazelRuleKind
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.builder.utils.jars.SourceJarExtractor
import io.bazel.kotlin.builder.utils.partitionJvmSources
import io.bazel.kotlin.model.Directories
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import java.util.stream.Stream

private const val SOURCE_JARS_DIR = "_srcjars"
private const val API_VERSION_ARG = "-api-version"
private const val LANGUAGE_VERSION_ARG = "-language-version"

/**
 * Due to an inconsistency in the handling of -Xfriends-path, jvm uses a comma (property list
 * separator)
 */
private const val X_FRIENDS_PATH_SEPARATOR = ","

private const val MANIFEST_DIR = "META-INF/"

fun codeGenArgs(compilationTask: JvmCompilationTask): CompilationArgs {
  return CompilationArgs()
    .absolutePaths(compilationTask.info.friendPaths) {
      "-Xfriend-paths=${it.joinToString(X_FRIENDS_PATH_SEPARATOR)}"
    }.flag("-d", compilationTask.directories.classes.toString())
    .values(compilationTask.info.passthroughFlags)
}

fun baseArgs(task: JvmCompilationTask, overrides: Map<String, String> = emptyMap()): CompilationArgs {
  val classpath: Sequence<String> = if (task.info.reducedClasspathMode == "KOTLINBUILDER_REDUCED") {
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
    task.inputs.directDependencies.asSequence() + transitiveDepsForCompile
  }
  else {
    task.inputs.classpath.asSequence()
  }

  val classPathString = (classpath + task.directories.generatedClasses).joinToString(File.pathSeparator)

  val compilationArgs = CompilationArgs()
  compilationArgs.flag("-cp").value(classPathString)
  return compilationArgs
    .flag(API_VERSION_ARG, overrides[API_VERSION_ARG] ?: task.info.toolchainInfo.apiVersion)
    .flag(
      LANGUAGE_VERSION_ARG,
      overrides[LANGUAGE_VERSION_ARG] ?: task.info.toolchainInfo.languageVersion,
    )
    .flag("-jvm-target", task.jvmTarget!!)
    .flag("-module-name", task.info.moduleName)
}

internal fun plugins(
  compilationTask: JvmCompilationTask,
  options: List<String>,
  classpath: List<String>,
): CompilationArgs {
  val compilationArgs = CompilationArgs()
  for (it in classpath) {
    compilationArgs.xFlag("plugin", it)
  }

  val dirs = compilationTask.directories
  val optionTokens = mapOf(
    "{generatedClasses}" to dirs.generatedClasses,
    "{stubs}" to Files.createDirectories(dirs.temp.resolve("stubs")).toString(),
    "{temp}" to dirs.temp.toString(),
    "{generatedSources}" to dirs.generatedSources,
    "{classpath}" to classpath.joinToString(File.pathSeparator),
  )
  for (opt in options) {
    val formatted = optionTokens.entries.fold(opt) { formatting, (token, value) ->
      formatting.replace(token, value.toString())
    }
    compilationArgs.flag("-P", "plugin:$formatted")
  }
  return compilationArgs
}

internal fun preProcessingSteps(
  task: JvmCompilationTask,
  context: CompilationTaskContext,
): JvmCompilationTask {
  return context.execute("expand sources") {
    if (task.inputs.sourceJars.isEmpty()) {
      task
    } else {
      val sourceJarExtractor = SourceJarExtractor(
        destDir = task.directories.temp.resolve(SOURCE_JARS_DIR),
        fileMatcher = { str -> IS_JVM_SOURCE_FILE.test(str) || "/$MANIFEST_DIR" in str },
      )
      sourceJarExtractor.jarFiles.addAll(task.inputs.sourceJars.map { p -> Path.of(p) })
      sourceJarExtractor.execute()
      expandWithSources(task, sourceJarExtractor.sourcesList.iterator())
    }
  }
}

internal fun kspArgs(task: JvmCompilationTask, toolchain: KotlinToolchain): CompilationArgs {
  val args = CompilationArgs()
  args.plugin(toolchain.kspSymbolProcessingCommandLine)
  val dirs = task.directories
  args.plugin(toolchain.kspSymbolProcessingApi) {
    args.flag("-Xallow-no-source-files")

    val values =
      arrayOf(
        "apclasspath" to listOf(task.inputs.processorPaths.joinToString(File.pathSeparator)),
        // projectBaseDir shouldn't matter because incremental is disabled
        "projectBaseDir" to listOf(dirs.incrementalData),
        // Disable incremental mode
        "incremental" to listOf("false"),
        // Directory where class files are written to. Files written to this directory are class
        // files being written directly from the annotation processor, not Kotlinc
        "classOutputDir" to listOf(dirs.generatedClasses),
        // Directory where generated Java sources files are written to
        "javaOutputDir" to listOf(dirs.generatedJavaSources),
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
  compilationTask: JvmCompilationTask,
  context: CompilationTaskContext,
  plugins: KotlinToolchain,
  compiler: KotlincInvoker,
): JvmCompilationTask {
  if ((compilationTask.inputs.processors.isEmpty() && compilationTask.inputs.stubsPluginClasspath.isEmpty()) ||
    compilationTask.inputs.kotlinSources.isEmpty()) {
    return compilationTask
  }
  return when {
    !compilationTask.outputs.generatedKspSrcJar.isNullOrEmpty() -> compilationTask.runKspPlugin(
      context,
      plugins,
      compiler
    )
    else -> compilationTask
  }
}

private fun JvmCompilationTask.runKspPlugin(
  context: CompilationTaskContext,
  toolchain: KotlinToolchain,
  compiler: KotlincInvoker,
): JvmCompilationTask {
  return context.execute("Ksp (${inputs.processors.joinToString(", ")})") {
    val overrides = mutableMapOf(
      API_VERSION_ARG to kspKotlinToolchainVersion(info.toolchainInfo.apiVersion),
      LANGUAGE_VERSION_ARG to kspKotlinToolchainVersion(info.toolchainInfo.languageVersion),
    )
    baseArgs(this, overrides)
      .plus(kspArgs(this, toolchain))
      .flag("-d", directories.generatedClasses.toString())
      .values(inputs.kotlinSources)
      .values(inputs.javaSources)
      .list()
      .let { args ->
        context.executeCompilerTask(
          args,
          compiler::compile,
          printOnSuccess = context.isTracing,
        )
      }.let { outputLines ->
        /*
if tracing is enabled, the output should be formatted in a special way, if we aren't
tracing then any compiler output would make it's way to the console as is.
*/
        context.whenTracing {
          printLines("Ksp output", outputLines.asSequence())
        }
        return@let expandWithGeneratedSources(this)
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
internal fun JvmCompilationTask.createOutputJar() {
  JarCreator(
    path = Path.of(outputs.jar),
    targetLabel = info.label,
    injectingRuleKind = info.bazelRuleKind,
  ).use {
    it.addDirectory(directories.classes)
    it.addDirectory(directories.javaClasses)
    it.addDirectory(directories.generatedClasses)
  }
}

/**
 * Compiles Kotlin sources to classes. Does not compile Java sources.
 */
fun compileKotlin(
  compilationTask: JvmCompilationTask,
  context: CompilationTaskContext,
  compiler: KotlincInvoker,
  args: CompilationArgs = baseArgs(compilationTask),
  printOnFail: Boolean = true,
): List<String> {
  val inputs = compilationTask.inputs
  if (inputs.kotlinSources.isEmpty()) {
    val file = Path.of(compilationTask.outputs.jdeps)
    Files.deleteIfExists(file)
    Files.newOutputStream(Files.createFile(file)).use {
      val depBuilder = Dependencies.newBuilder()
      depBuilder.ruleLabel = compilationTask.info.label
      depBuilder.build()
    }
    return emptyList()
  }

  val dirs = compilationTask.directories
  val argList = (
    args +
      plugins(
        compilationTask = compilationTask,
        options = inputs.compilerPluginOptions,
        classpath = inputs.compilerPluginClasspath,
      )
    ).values(inputs.javaSources)
    .values(inputs.kotlinSources)
    .flag("-d", dirs.classes.toString())
    .list()
  context.whenTracing {
    context.printLines("compileKotlin arguments:\n", argList.asSequence())
  }
  val task = context.executeCompilerTask(args = argList, compile = compiler::compile, printOnFail = printOnFail)
  context.whenTracing {
    printLines(
      "kotlinc Files Created:",
      sequenceOf(
        dirs.classes,
        dirs.generatedClasses,
        dirs.generatedSources,
        dirs.generatedJavaSources,
        dirs.temp,
      )
        .flatMap { filePath ->
          Files.walk(filePath).use { file ->
            file.filter { !Files.isDirectory(it) }.collect(Collectors.toList())
          }
        }
        .map { it.toString() },
    )
  }
  return task
}

val Directories.incrementalData
  get() = Files.createDirectories(temp.resolve("incrementalData")).toString()

/**
 * Create a new [JvmCompilationTask] with sources found in the generatedSources directory.
 * This should be run after annotation processors have been run.
 */
fun expandWithGeneratedSources(task: JvmCompilationTask): JvmCompilationTask {
  return expandWithSources(
    task,
    Stream
      .of(task.directories.generatedSources, task.directories.generatedJavaSources)
      .flatMap { p -> Files.walk(p) }
      .filter { !Files.isDirectory(it) }
      .map { it.toString() }
      .distinct()
      .iterator(),
  )
}

private fun expandWithSources(task: JvmCompilationTask, sources: Iterator<String>): JvmCompilationTask {
  val kotlinSources = task.inputs.kotlinSources.toMutableList()
  val javaSources = task.inputs.javaSources.toMutableList()
  filterOutNonCompilableSources(copyManifestFilesToGeneratedClasses(sources, task.directories))
    .partitionJvmSources(
      kt = { kotlinSources.add(it) },
      java = { javaSources.add(it) },
    )

  return task.copy(
    inputs = task.inputs.copy(
      kotlinSources = java.util.List.copyOf(kotlinSources),
      javaSources = java.util.List.copyOf(javaSources)
    ),
  )
}

/**
 * Copy generated manifest files from KSP task into generated folder
 */
private fun copyManifestFilesToGeneratedClasses(
  iterator: Iterator<String>,
  directories: Directories,
): Iterator<String> {
  val result = mutableSetOf<String>()
  for (it in iterator) {
    if ("/$MANIFEST_DIR" in it) {
      val path = Path.of(it)
      val srcJarsPath = directories.temp.resolve(SOURCE_JARS_DIR)
      if (Files.exists(srcJarsPath)) {
        val relativePath = srcJarsPath.relativize(path)
        val destPath = directories.generatedClasses.resolve(relativePath)
        destPath.parent.toFile().mkdirs()
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
