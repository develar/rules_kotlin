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
import io.bazel.kotlin.builder.utils.IS_JVM_SOURCE_FILE
import io.bazel.kotlin.builder.utils.bazelRuleKind
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.builder.utils.jars.SourceJarExtractor
import io.bazel.kotlin.builder.utils.partitionJvmSources
import io.bazel.kotlin.model.JvmCompilationTask
import io.bazel.kotlin.model.JvmCompilationTask.Directories
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.stream.Stream

private const val SOURCE_JARS_DIR = "_srcjars"
private const val API_VERSION_ARG = "-api-version"
private const val LANGUAGE_VERSION_ARG = "-language-version"

private const val MANIFEST_DIR = "META-INF/"

fun JvmCompilationTask.codeGenArgs(): CompilationArgs {
  return CompilationArgs()
    .absolutePaths(info.friendPathsList) {
      "-Xfriend-paths=${it.joinToString(X_FRIENDS_PATH_SEPARATOR)}"
    }.flag("-d", directories.classes)
    .values(info.passthroughFlagsList)
}

fun JvmCompilationTask.baseArgs(overrides: Map<String, String> = emptyMap()): CompilationArgs {
  val classpath = when (info.reducedClasspathMode) {
    "KOTLINBUILDER_REDUCED" -> {
      val transitiveDepsForCompile = LinkedHashSet<String>()
      for (jdepsPath in inputs.depsArtifactsList) {
        BufferedInputStream(Paths.get(jdepsPath).toFile().inputStream()).use {
          val deps = Dependencies.parseFrom(it)
          deps.dependencyList.forEach { dep ->
            if (dep.kind == Deps.Dependency.Kind.EXPLICIT) {
              transitiveDepsForCompile.add(dep.path)
            }
          }
        }
      }
      inputs.directDependenciesList + transitiveDepsForCompile
    }

    else -> inputs.classpathList
  } as List<String>

  return CompilationArgs()
    .flag("-cp")
    .paths(
      classpath + directories.generatedClasses,
    ) {
      it
        .map(Path::toString)
        .joinToString(File.pathSeparator)
    }.flag(API_VERSION_ARG, overrides[API_VERSION_ARG] ?: info.toolchainInfo.common.apiVersion)
    .flag(
      LANGUAGE_VERSION_ARG,
      overrides[LANGUAGE_VERSION_ARG] ?: info.toolchainInfo.common.languageVersion,
    ).flag("-jvm-target", info.toolchainInfo.jvm.jvmTarget)
    .flag("-module-name", info.moduleName)
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
    "{stubs}" to dirs.stubs,
    "{temp}" to dirs.temp,
    "{generatedSources}" to dirs.generatedSources,
    "{classpath}" to classpath.joinToString(File.pathSeparator),
  )
  for (opt in options) {
    val formatted = optionTokens.entries.fold(opt) { formatting, (token, value) ->
      formatting.replace(token, value)
    }
    compilationArgs.flag("-P", "plugin:$formatted")
  }
  return compilationArgs
}

internal fun JvmCompilationTask.preProcessingSteps(
  context: CompilationTaskContext,
): JvmCompilationTask = context.execute("expand sources") { expandWithSourceJarSources() }

internal fun encodeMap(options: Map<String, String>): String {
  val os = ByteArrayOutputStream()
  val oos = ObjectOutputStream(os)

  oos.writeInt(options.size)
  for ((key, value) in options.entries) {
    oos.writeUTF(key)
    oos.writeUTF(value)
  }

  oos.flush()
  return Base64
    .getEncoder()
    .encodeToString(os.toByteArray())
}

internal fun JvmCompilationTask.kaptArgs(
  context: CompilationTaskContext,
  plugins: InternalCompilerPlugins,
  aptMode: String,
): CompilationArgs {
  val javacArgs =
    mapOf<String, String>(
      "-target" to info.toolchainInfo.jvm.jvmTarget,
      "-source" to info.toolchainInfo.jvm.jvmTarget,
    )
  return CompilationArgs().apply {
    xFlag("plugin", plugins.kapt.jarPath)

    val values =
      arrayOf(
        "sources" to listOf(directories.generatedJavaSources),
        "classes" to listOf(directories.generatedClasses),
        "stubs" to listOf(directories.stubs),
        "incrementalData" to listOf(directories.incrementalData),
        "javacArguments" to listOf(javacArgs.let(::encodeMap)),
        "correctErrorTypes" to listOf("false"),
        "verbose" to listOf(context.whenTracing { "true" } ?: "false"),
        "apclasspath" to inputs.processorpathsList,
        "aptMode" to listOf(aptMode),
      )
    val version =
      info.toolchainInfo.common.apiVersion
        .toFloat()
    when {
      version < 1.5 ->
        base64Encode(
          "-P",
          *values + ("processors" to inputs.processorsList).asKeyToCommaList(),
        ) { enc -> "plugin:${plugins.kapt.id}:configuration=$enc" }
      else ->
        repeatFlag(
          "-P",
          *values + ("processors" to inputs.processorsList),
        ) { option, value ->
          "plugin:${plugins.kapt.id}:$option=$value"
        }
    }
  }
}

internal fun JvmCompilationTask.kspArgs(plugins: InternalCompilerPlugins): CompilationArgs =
  CompilationArgs().apply {
    plugin(plugins.kspSymbolProcessingCommandLine)
    plugin(plugins.kspSymbolProcessingApi) {
      flag("-Xallow-no-source-files")

      val values =
        arrayOf(
          "apclasspath" to listOf(inputs.processorpathsList.joinToString(File.pathSeparator)),
          // projectBaseDir shouldn't matter because incremental is disabled
          "projectBaseDir" to listOf(directories.incrementalData),
          // Disable incremental mode
          "incremental" to listOf("false"),
          // Directory where class files are written to. Files written to this directory are class
          // files being written directly from the annotation processor, not Kotlinc
          "classOutputDir" to listOf(directories.generatedClasses),
          // Directory where generated Java sources files are written to
          "javaOutputDir" to listOf(directories.generatedJavaSources),
          // Directory where generated Kotlin sources files are written to
          "kotlinOutputDir" to listOf(directories.generatedSources),
          // Directory where META-INF data is written to. This might not be the most ideal place to
          // write this. Maybe just directly to the classes' directory?
          "resourceOutputDir" to listOf(directories.generatedSources),
          // TODO(bencodes) Not sure what this directory is yet.
          "kspOutputDir" to listOf(directories.incrementalData),
          // Directory to write KSP caches. Shouldn't matter because incremental is disabled
          "cachesDir" to listOf(directories.incrementalData),
          // Set withCompilation to false because we run this as part of the standard kotlinc pass
          // If we ever want to flip this to true, we probably want to integrate this directly
          // into the KotlinCompile action.
          "withCompilation" to listOf("false"),
          // Set returnOkOnError to false because we want to fail the build if there are any errors
          "returnOkOnError" to listOf("false"),
          // TODO(bencodes) This should probably be enabled via some KSP options
          "allWarningsAsErrors" to listOf("false"),
        )

      values.forEach { pair ->
        pair.second.forEach { value ->
          flag(pair.first, value)
        }
      }
    }
  }

private fun Pair<String, List<String>>.asKeyToCommaList() =
  first to listOf(second.joinToString(","))

internal fun JvmCompilationTask.runPlugins(
  context: CompilationTaskContext,
  plugins: InternalCompilerPlugins,
  compiler: KotlinToolchain.KotlincInvoker,
): JvmCompilationTask {
  if (
    (
      inputs.processorsList.isEmpty() &&
        inputs.stubsPluginClasspathList.isEmpty()
    ) ||
    inputs.kotlinSourcesList.isEmpty()
  ) {
    return this
  } else {
    if (!outputs.generatedKspSrcJar.isNullOrEmpty()) {
      return runKspPlugin(context, plugins, compiler)
    } else if (!outputs.generatedClassJar.isNullOrEmpty()) {
      return runKaptPlugin(context, plugins, compiler)
    } else {
      return this
    }
  }
}

private fun JvmCompilationTask.runKaptPlugin(
  context: CompilationTaskContext,
  plugins: InternalCompilerPlugins,
  compiler: KotlinToolchain.KotlincInvoker,
): JvmCompilationTask {
  return context.execute("kapt (${inputs.processorsList.joinToString(", ")})") {
    baseArgs()
      .plus(
        plugins(
          compilationTask = this,
          options = inputs.stubsPluginOptionsList,
          classpath = inputs.stubsPluginClasspathList,
        ),
      ).plus(
        kaptArgs(context, plugins, "stubsAndApt"),
      ).flag("-d", directories.generatedClasses)
      .values(inputs.kotlinSourcesList)
      .values(inputs.javaSourcesList)
      .list()
      .let { args ->
        context.executeCompilerTask(
          args,
          compiler::compile,
          printOnSuccess = context.whenTracing { false } != false,
        )
      }.let { outputLines ->
        // if tracing is enabled, the output should be formatted in a special way, if we aren't
        // tracing then any compiler output would make it's way to the console as is.
        context.whenTracing {
          printLines("kapt output", outputLines)
        }
        return@let expandWithGeneratedSources()
      }
  }
}

private fun JvmCompilationTask.runKspPlugin(
  context: CompilationTaskContext,
  plugins: InternalCompilerPlugins,
  compiler: KotlinToolchain.KotlincInvoker,
): JvmCompilationTask {
  return context.execute("Ksp (${inputs.processorsList.joinToString(", ")})") {
    val overrides =
      mutableMapOf(
        API_VERSION_ARG to kspKotlinToolchainVersion(info.toolchainInfo.common.apiVersion),
        LANGUAGE_VERSION_ARG to
          kspKotlinToolchainVersion(
            info.toolchainInfo.common.languageVersion,
          ),
      )
    baseArgs(overrides)
      .plus(kspArgs(plugins))
      .flag("-d", directories.generatedClasses)
      .values(inputs.kotlinSourcesList)
      .values(inputs.javaSourcesList)
      .list()
      .let { args ->
        context.executeCompilerTask(
          args,
          compiler::compile,
          printOnSuccess = context.whenTracing { false } != false,
        )
      }.let { outputLines ->
        /*
if tracing is enabled, the output should be formatted in a special way, if we aren't
tracing then any compiler output would make it's way to the console as is.
*/
        context.whenTracing {
          printLines("Ksp output", outputLines)
        }
        return@let expandWithGeneratedSources()
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
    it.addDirectory(Path.of(directories.classes))
    it.addDirectory(Path.of(directories.javaClasses))
    it.addDirectory(Path.of(directories.generatedClasses))
  }
}

/**
 * Produce the primary output jar.
 */
internal fun JvmCompilationTask.createAbiJar() =
  JarCreator(
    path = Path.of(outputs.abijar),
    targetLabel = info.label,
    injectingRuleKind = info.bazelRuleKind,
  ).use {
    it.addDirectory(Path.of(directories.abiClasses))
    it.addDirectory(Path.of(directories.generatedClasses))
  }

/**
 * Produce a jar of sources generated by KAPT.
 */
internal fun JvmCompilationTask.createGeneratedJavaSrcJar() {
  JarCreator(
    path = Path.of(outputs.generatedJavaSrcJar),
    targetLabel = info.label,
    injectingRuleKind = info.bazelRuleKind,
  ).use {
    it.addDirectory(Path.of(directories.generatedJavaSources))
  }
}

/**
 * Produce a stub jar of classes generated by KAPT.
 */
internal fun JvmCompilationTask.createGeneratedStubJar() {
  JarCreator(
    path = Path.of(outputs.generatedJavaStubJar),
    targetLabel = info.label,
    injectingRuleKind = info.bazelRuleKind,
  ).use {
    it.addDirectory(Paths.get(directories.incrementalData))
  }
}

/**
 * Produce a jar of classes generated by KAPT.
 */
internal fun JvmCompilationTask.createGeneratedClassJar() {
  JarCreator(
    path = Path.of(outputs.generatedClassJar),
    targetLabel = info.label,
    injectingRuleKind = info.bazelRuleKind,
  ).use {
    it.addDirectory(Paths.get(directories.generatedClasses))
  }
}

internal fun JvmCompilationTask.createGeneratedKspKotlinSrcJar() {
  JarCreator(
    path = Path.of(outputs.generatedKspSrcJar),
    targetLabel = info.label,
    injectingRuleKind = info.bazelRuleKind,
  ).use {
    it.addDirectory(Path.of(directories.generatedSources))
    it.addDirectory(Path.of(directories.generatedJavaSources))
  }
}

/**
 * Compiles Kotlin sources to classes. Does not compile Java sources.
 */
fun compileKotlin(
  compilationTask: JvmCompilationTask,
  context: CompilationTaskContext,
  compiler: KotlinToolchain.KotlincInvoker,
  args: CompilationArgs = compilationTask.baseArgs(),
  printOnFail: Boolean = true,
): List<String> {
  val inputs = compilationTask.inputs
  if (inputs.kotlinSourcesList.isEmpty()) {
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
        options = inputs.compilerPluginOptionsList,
        classpath = inputs.compilerPluginClasspathList,
      )
    ).values(inputs.javaSourcesList)
    .values(inputs.kotlinSourcesList)
    .flag("-d", dirs.classes)
    .list()
  context.whenTracing {
    context.printLines("compileKotlin arguments:\n", argList)
  }
  val task = context.executeCompilerTask(
    args = argList,
    compile = compiler::compile,
    printOnFail = printOnFail,
  )
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
        .flatMap {
          Files.walk(Path.of(it)).use { s -> s.filter { !Files.isDirectory(it) }.toList() }
        }
        .map { it.toString() }
        .toList(),
    )
  }
  return task
}

/**
 * If any srcjars were provided expand the jars sources and create a new [JvmCompilationTask] with the
 * Java, Kotlin sources and META folder merged in.
 */
internal fun JvmCompilationTask.expandWithSourceJarSources(): JvmCompilationTask =
  if (inputs.sourceJarsList.isEmpty()) {
    this
  } else {
    expandWithSources(
      SourceJarExtractor(
        destDir = Paths.get(directories.temp).resolve(SOURCE_JARS_DIR),
        fileMatcher = { str: String -> IS_JVM_SOURCE_FILE.test(str) || "/$MANIFEST_DIR" in str },
      ).also {
        it.jarFiles.addAll(inputs.sourceJarsList.map { p -> Paths.get(p) })
        it.execute()
      }.sourcesList
        .iterator(),
    )
  }

private val Directories.stubs
  get() =
    Files
      .createDirectories(
        Paths
          .get(temp)
          .resolve("stubs"),
      ).toString()
private val Directories.incrementalData
  get() =
    Files
      .createDirectories(
        Paths
          .get(temp)
          .resolve("incrementalData"),
      ).toString()

/**
 * Create a new [JvmCompilationTask] with sources found in the generatedSources directory. This should be run after
 * annotation processors have been run.
 */
fun JvmCompilationTask.expandWithGeneratedSources(): JvmCompilationTask =
  expandWithSources(
    Stream
      .of(directories.generatedSources, directories.generatedJavaSources)
      .map { s -> Paths.get(s) }
      .flatMap { p -> Files.walk(p) }
      .filter { !Files.isDirectory(it) }
      .map { it.toString() }
      .distinct()
      .iterator(),
  )

private fun JvmCompilationTask.expandWithSources(sources: Iterator<String>): JvmCompilationTask =
  updateBuilder { builder ->
    sources
      .copyManifestFilesToGeneratedClasses(directories)
      .filterOutNonCompilableSources()
      .partitionJvmSources(
        { builder.inputsBuilder.addKotlinSources(it) },
        { builder.inputsBuilder.addJavaSources(it) },
      )
  }

private fun JvmCompilationTask.updateBuilder(
  block: (JvmCompilationTask.Builder) -> Unit,
): JvmCompilationTask =
  toBuilder().let {
    block(it)
    it.build()
  }

/**
 * Copy generated manifest files from KSP task into generated folder
 */
internal fun Iterator<String>.copyManifestFilesToGeneratedClasses(
  directories: Directories,
): Iterator<String> {
  val result = mutableSetOf<String>()
  this.forEach {
    if ("/$MANIFEST_DIR" in it) {
      val path = Paths.get(it)
      val srcJarsPath = Paths.get(directories.temp, SOURCE_JARS_DIR)
      if (Files.exists(srcJarsPath)) {
        val relativePath = srcJarsPath.relativize(path)
        val destPath = Paths.get(directories.generatedClasses).resolve(relativePath)
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
private fun Iterator<String>.filterOutNonCompilableSources(): Iterator<String> {
  val result = mutableListOf<String>()
  this.forEach {
    if (it.endsWith(".kt") or it.endsWith(".java")) result.add(it)
  }
  return result.iterator()
}
