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
package io.bazel.kotlin.builder.tasks

import io.bazel.kotlin.builder.tasks.jvm.KotlinJvmTaskExecutor
import io.bazel.kotlin.builder.tasks.jvm.expandWithSources
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.utils.ArgMap
import io.bazel.kotlin.builder.utils.createArgMap
import io.bazel.kotlin.builder.utils.jars.SourceJarExtractor
import io.bazel.kotlin.model.*
import io.bazel.worker.ContextLog.Logging
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

private val FLAG_FILE_RE = Pattern.compile("""^--flagfile=((.*)-(\d+).params)$""").toRegex()

private enum class KotlinBuilderFlags {
  TARGET_LABEL,
  CLASSPATH,
  DIRECT_DEPENDENCIES,
  DEPS_ARTIFACTS,

  KOTLIN_SOURCES,
  JAVA_SOURCES,

  SOURCE_JARS,
  PROCESSOR_PATH,
  PROCESSORS,

  STUBS_PLUGIN_OPTIONS,
  STUBS_PLUGIN_CLASSPATH,

  COMPILER_PLUGIN_OPTIONS,
  COMPILER_PLUGIN_CLASSPATH,
  OUTPUT,
  RULE_KIND,
  KOTLIN_MODULE_NAME,
  KOTLIN_PASSTHROUGH_FLAGS,
  KOTLIN_API_VERSION,
  KOTLIN_LANGUAGE_VERSION,
  KOTLIN_JVM_TARGET,
  KOTLIN_OUTPUT_SRCJAR,
  KOTLIN_FRIEND_PATHS,
  KOTLIN_OUTPUT_JDEPS,
  KOTLIN_DEBUG_TAGS,
  ABI_JAR,
  GENERATED_JAVA_SRCJAR,
  BUILD_KOTLIN,
  STRICT_KOTLIN_DEPS,
  REDUCED_CLASSPATH_MODE,
  INSTRUMENT_COVERAGE,
  KSP_GENERATED_JAVA_SRCJAR,
}

fun buildKotlin(
  workingDir: Path,
  out: PrintStream,
  args: List<String>,
  logging: Logging,
  jvmTaskExecutor: KotlinJvmTaskExecutor,
): Int {
  check(args.isNotEmpty()) {
    "expected at least a single arg got: ${args.joinToString(" ")}"
  }

  val argMap = createArgMap(
    FLAG_FILE_RE.matchEntire(args[0])?.groups?.get(1)?.let {
      Files.readAllLines(Path.of(it.value))
    } ?: args,
    enumClass = KotlinBuilderFlags::class.java,
  )
  val task = createBuildTask(argMap)
  val compileContext = CompilationTaskContext(
    label = task.label,
    debug = task.debug,
    out = out,
    executionRoot = Path.of("").toAbsolutePath().toString() + File.separator
  )
  var success = false
  try {
    when (task.platform) {
      Platform.JVM -> {
        val task = createJvmTask(info = task, workingDir = workingDir, args = argMap, context = compileContext)
        compileContext.whenTracing {
          printLines(
            header = "jvm task message:",
            lines = formatDataClassToString(task.toString()).splitToSequence('\n'),
            filterEmpty = true,
          )
        }
        jvmTaskExecutor.execute(context = compileContext, task = task)
      }

      Platform.UNRECOGNIZED -> throw IllegalStateException("unrecognized platform: $task")
    }
    success = true
  } catch (e: CompilationStatusException) {
    logging.error { "Compilation failure: ${e.message}" }
    return e.status
  } catch (throwable: Throwable) {
    logging.error(throwable) { "Uncaught exception" }
  } finally {
    compileContext.finalize(success)
  }
  return 0
}

private fun formatDataClassToString(input: String): CharSequence {
  val indentUnit = "  "
  var currentIndent = ""
  val result = StringBuilder()

  for (char in input) {
    when (char) {
      '{', '[', '(' -> {
        result.append("$char\n")
        currentIndent += indentUnit
        result.append(currentIndent)
      }

      '}', ']', ')' -> {
        result.append("\n")
        currentIndent = currentIndent.dropLast(indentUnit.length)
        result.append(currentIndent).append(char)
      }

      ',' -> result.append(",\n").append(currentIndent)
      else -> result.append(char)
    }
  }

  return result
}

private fun createBuildTask(argMap: ArgMap<KotlinBuilderFlags>): CompilationTaskInfo {
  val ruleKind = argMap.mandatorySingle(KotlinBuilderFlags.RULE_KIND).split('_')
  check(ruleKind.size == 3 && ruleKind[0] == "kt") {
    "invalid rule kind $ruleKind"
  }

  return CompilationTaskInfo(
    debug = argMap.mandatory(KotlinBuilderFlags.KOTLIN_DEBUG_TAGS),
    label = argMap.mandatorySingle(KotlinBuilderFlags.TARGET_LABEL),
    ruleKind = checkNotNull(RuleKind.valueOf(ruleKind[2].uppercase())) {
      "unrecognized rule kind ${ruleKind[2]}"
    },
    platform = checkNotNull(Platform.valueOf(ruleKind[1].uppercase())) {
      "unrecognized platform ${ruleKind[1]}"
    },
    moduleName = argMap.mandatorySingle(KotlinBuilderFlags.KOTLIN_MODULE_NAME).also {
      check(it.isNotBlank()) { "--kotlin_module_name should not be blank" }
    },
    passthroughFlags = argMap.optional(KotlinBuilderFlags.KOTLIN_PASSTHROUGH_FLAGS) ?: emptyList(),
    strictKotlinDeps = argMap.mandatorySingle(KotlinBuilderFlags.STRICT_KOTLIN_DEPS),
    reducedClasspathMode = argMap.mandatorySingle(KotlinBuilderFlags.REDUCED_CLASSPATH_MODE),
    toolchainInfo = KotlinToolchainInfo(
      apiVersion = argMap.mandatorySingle(KotlinBuilderFlags.KOTLIN_API_VERSION),
      languageVersion = argMap.mandatorySingle(KotlinBuilderFlags.KOTLIN_LANGUAGE_VERSION),
    ),
  )
}

@OptIn(ExperimentalPathApi::class)
private fun createJvmTask(
  info: CompilationTaskInfo,
  workingDir: Path,
  args: ArgMap<KotlinBuilderFlags>,
  context: CompilationTaskContext,
): JvmCompilationTask {
  var outJar = args.optionalSingle(KotlinBuilderFlags.OUTPUT)
  val generatedKspSrcJar = args.optionalSingle(KotlinBuilderFlags.KSP_GENERATED_JAVA_SRCJAR)
  if (outJar == null) {
    outJar = generatedKspSrcJar!!
  }
  val jar = workingDir.resolve(outJar)

  val kotlincDir = jar.parent.resolve("_kotlinc")
  Files.createDirectories(kotlincDir)
  val targetName = jar.fileName.toString().substringBeforeLast(".jar")

  fun resolveAndCreate(part: String): Path {
    val dir = kotlincDir.resolve("$targetName-$part")
    dir.deleteRecursively()
    Files.createDirectory(dir)
    return dir
  }

  fun resolve(part: String): Path {
    return kotlincDir.resolve("$targetName-$part")
  }

  val tempDir = resolve("temp")
  val generatedClassDir = resolve("generated_classes")

  val sourceJars = args.optional(KotlinBuilderFlags.SOURCE_JARS) ?: emptyList()
  val kotlinSources: List<String>
  val javaSources: List<String>
  if (sourceJars.isEmpty()) {
    kotlinSources = args.optional(KotlinBuilderFlags.KOTLIN_SOURCES) ?: emptyList()
    javaSources = args.optional(KotlinBuilderFlags.JAVA_SOURCES) ?: emptyList()
  } else {
    val kotlinSourcesMutable = mutableListOf<String>()
    val javaSourcesMutable = mutableListOf<String>()
    context.execute("expand sources") {
      if (!sourceJars.isEmpty()) {
        val srcJarsDir = tempDir.resolve("src-jars")
        val sourceJarExtractor = SourceJarExtractor(
          destDir = srcJarsDir,
          fileMatcher = { it.endsWith(".kt") || it.endsWith(".java") || it.contains("/META-INF/") },
        )
        sourceJarExtractor.jarFiles.addAll(sourceJars.map { p -> Path.of(p) })
        sourceJarExtractor.execute()
        expandWithSources(
          javaSources = kotlinSourcesMutable,
          kotlinSources = javaSourcesMutable,
          sources = sourceJarExtractor.sourcesList.iterator(),
          srcJarsDir = srcJarsDir,
          generatedClasses = generatedClassDir
        )
      }
    }

    kotlinSources = kotlinSourcesMutable
    javaSources = javaSourcesMutable
  }

  fun pathList(list: List<String>): List<Path> {
    return list.map { workingDir.resolve(it).toAbsolutePath().normalize() }
  }

  val root = JvmCompilationTask(
    jvmTarget = args.mandatorySingle(KotlinBuilderFlags.KOTLIN_JVM_TARGET),
    info = info,
    compileKotlin = args.optionalSingle(KotlinBuilderFlags.BUILD_KOTLIN).let { it == null || it.toBoolean() },
    instrumentCoverage = args.optionalSingle(KotlinBuilderFlags.INSTRUMENT_COVERAGE).toBoolean(),
    outputs = Outputs(
      jar = jar,
      srcjar = args.optionalSingle(KotlinBuilderFlags.KOTLIN_OUTPUT_SRCJAR)?.let { workingDir.resolve(it) },
      jdeps = args.optionalSingle(KotlinBuilderFlags.KOTLIN_OUTPUT_JDEPS)?.let { workingDir.resolve(it) },
      generatedJavaSrcJar = args.optionalSingle(KotlinBuilderFlags.GENERATED_JAVA_SRCJAR),
      abiJar = args.optionalSingle(KotlinBuilderFlags.ABI_JAR)?.let { workingDir.resolve(it) },
      generatedKspSrcJar = generatedKspSrcJar?.let { workingDir.resolve(it) },
    ),
    directories = Directories(
      classes = resolveAndCreate("classes"),
      abiClasses = if (args.has(KotlinBuilderFlags.ABI_JAR)) {
        resolveAndCreate("abi-classes")
      } else null,

      temp = tempDir,
      incrementalData = resolve("incremental"),

      generatedClasses = generatedClassDir,
      generatedSources = resolve("generated_sources"),
      generatedJavaSources = resolve("generated_java_sources"),
      coverageMetadataClasses = resolve("coverage-metadata"),
    ),
    friendPaths = args.optional(KotlinBuilderFlags.KOTLIN_FRIEND_PATHS)?.let { pathList(it) } ?: emptyList(),
    inputs = Inputs(
      classpath = pathList(args.mandatory(KotlinBuilderFlags.CLASSPATH)),
      depsArtifacts = args.optional(KotlinBuilderFlags.DEPS_ARTIFACTS) ?: emptyList(),
      directDependencies = args.mandatory(KotlinBuilderFlags.DIRECT_DEPENDENCIES),
      processors = args.optional(KotlinBuilderFlags.PROCESSORS) ?: emptyList(),
      processorPaths = args.optional(KotlinBuilderFlags.PROCESSOR_PATH) ?: emptyList(),
      stubsPluginOptions = args.optional(KotlinBuilderFlags.STUBS_PLUGIN_OPTIONS) ?: emptyList(),
      stubsPluginClasspath = args.optional(KotlinBuilderFlags.STUBS_PLUGIN_CLASSPATH) ?: emptyList(),
      compilerPluginOptions = args.optional(KotlinBuilderFlags.COMPILER_PLUGIN_OPTIONS) ?: emptyList(),
      compilerPluginClasspath = args.optional(KotlinBuilderFlags.COMPILER_PLUGIN_CLASSPATH)?.let { pathList(it) } ?: emptyList(),

      kotlinSources = kotlinSources,
      javaSources = javaSources,
    ),
  )
  return root
}

