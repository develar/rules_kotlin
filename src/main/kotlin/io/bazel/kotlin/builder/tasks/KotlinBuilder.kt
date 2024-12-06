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
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.utils.ArgMap
import io.bazel.kotlin.builder.utils.Flag
import io.bazel.kotlin.builder.utils.createArgMap
import io.bazel.kotlin.builder.utils.partitionJvmSources
import io.bazel.kotlin.model.*
import io.bazel.kotlin.model.JvmCompilationTask.Directories
import io.bazel.kotlin.model.JvmCompilationTask.Outputs
import io.bazel.worker.TaskContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

private val FLAG_FILE_RE = Pattern.compile("""^--flagfile=((.*)-(\d+).params)$""").toRegex()

private enum class KotlinBuilderFlags(
  override val flag: String,
) : Flag {
  TARGET_LABEL("--target_label"),
  CLASSPATH("--classpath"),
  DIRECT_DEPENDENCIES("--direct_dependencies"),
  DEPS_ARTIFACTS("--deps_artifacts"),
  SOURCES("--sources"),
  SOURCE_JARS("--source_jars"),
  PROCESSOR_PATH("--processor_path"),
  PROCESSORS("--processors"),
  STUBS_PLUGIN_OPTIONS("--stubs_plugin_options"),
  STUBS_PLUGIN_CLASS_PATH("--stubs_plugin_classpath"),
  COMPILER_PLUGIN_OPTIONS("--compiler_plugin_options"),
  COMPILER_PLUGIN_CLASS_PATH("--compiler_plugin_classpath"),
  OUTPUT("--output"),
  RULE_KIND("--rule_kind"),
  MODULE_NAME("--kotlin_module_name"),
  PASSTHROUGH_FLAGS("--kotlin_passthrough_flags"),
  API_VERSION("--kotlin_api_version"),
  LANGUAGE_VERSION("--kotlin_language_version"),
  JVM_TARGET("--kotlin_jvm_target"),
  OUTPUT_SRCJAR("--kotlin_output_srcjar"),
  FRIEND_PATHS("--kotlin_friend_paths"),
  OUTPUT_JDEPS("--kotlin_output_jdeps"),
  DEBUG("--kotlin_debug_tags"),
  ABI_JAR("--abi_jar"),
  GENERATED_JAVA_SRC_JAR("--generated_java_srcjar"),
  BUILD_KOTLIN("--build_kotlin"),
  STRICT_KOTLIN_DEPS("--strict_kotlin_deps"),
  REDUCED_CLASSPATH_MODE("--reduced_classpath_mode"),
  INSTRUMENT_COVERAGE("--instrument_coverage"),
  KSP_GENERATED_JAVA_SRCJAR("--ksp_generated_java_srcjar"),
}

fun buildKotlin(
  taskContext: TaskContext,
  args: List<String>,
  jvmTaskExecutor: KotlinJvmTaskExecutor,
): Int {
  check(args.isNotEmpty()) { "expected at least a single arg got: ${args.joinToString(" ")}" }
  val argMap = createArgMap(
    FLAG_FILE_RE.matchEntire(args[0])?.groups?.get(1)?.let {
      Files.readAllLines(Path.of(it.value))
    } ?: args,
  )
  val compileContext = CompilationTaskContext(
    createBuildTaskInfo(argMap),
    taskContext.asPrintStream(),
  )
  var success = false
  try {
    when (compileContext.info.platform) {
      Platform.JVM -> executeJvmTask(
        context = compileContext,
        workingDir = taskContext.directory,
        argMap = argMap,
        jvmTaskExecutor = jvmTaskExecutor,
      )

      Platform.UNRECOGNIZED -> throw IllegalStateException(
        "unrecognized platform: ${compileContext.info}",
      )
    }
    success = true
  } catch (e: CompilationStatusException) {
    taskContext.error { "Compilation failure: ${e.message}" }
    return e.status
  } catch (throwable: Throwable) {
    taskContext.error(throwable) { "Uncaught exception" }
  } finally {
    compileContext.finalize(success)
  }
  return 0
}

private fun createBuildTaskInfo(argMap: ArgMap): CompilationTaskInfo {
  val ruleKind = argMap.mandatorySingle(KotlinBuilderFlags.RULE_KIND).split('_')
  check(ruleKind.size == 3 && ruleKind[0] == "kt") {
    "invalid rule kind $ruleKind"
  }

  return CompilationTaskInfo(
    debug = argMap.mandatory(KotlinBuilderFlags.DEBUG),
    label = argMap.mandatorySingle(KotlinBuilderFlags.TARGET_LABEL),
    ruleKind = checkNotNull(RuleKind.valueOf(ruleKind[2].uppercase())) {
      "unrecognized rule kind ${ruleKind[2]}"
    },
    platform = checkNotNull(Platform.valueOf(ruleKind[1].uppercase())) {
      "unrecognized platform ${ruleKind[1]}"
    },
    moduleName = argMap.mandatorySingle(KotlinBuilderFlags.MODULE_NAME).also {
      check(it.isNotBlank()) { "--kotlin_module_name should not be blank" }
    },
    passthroughFlags = argMap.optional(KotlinBuilderFlags.PASSTHROUGH_FLAGS) ?: emptyList(),
    friendPaths = argMap.optional(KotlinBuilderFlags.FRIEND_PATHS) ?: emptyList(),
    strictKotlinDeps = argMap.mandatorySingle(KotlinBuilderFlags.STRICT_KOTLIN_DEPS),
    reducedClasspathMode = argMap.mandatorySingle(KotlinBuilderFlags.REDUCED_CLASSPATH_MODE),
    toolchainInfo = KotlinToolchainInfo(
      apiVersion = argMap.mandatorySingle(KotlinBuilderFlags.API_VERSION),
      languageVersion = argMap.mandatorySingle(KotlinBuilderFlags.LANGUAGE_VERSION),
    ),
  )
}

private fun executeJvmTask(
  context: CompilationTaskContext,
  workingDir: Path,
  argMap: ArgMap,
  jvmTaskExecutor: KotlinJvmTaskExecutor,
) {
  val task = buildJvmTask(context.info, workingDir, argMap)
  context.whenTracing {
    printLines(
      header = "jvm task message:",
      lines = task.toString().splitToSequence('\n'),
      filterEmpty = true,
    )
  }
  jvmTaskExecutor.execute(context, task)
}

private fun buildJvmTask(
  info: CompilationTaskInfo,
  workingDir: Path,
  argMap: ArgMap,
): JvmCompilationTask {
  val moduleName = argMap.mandatorySingle(KotlinBuilderFlags.MODULE_NAME)

  val kotlinSources = mutableListOf<String>()
  val javSources = mutableListOf<String>()
  argMap.optional(KotlinBuilderFlags.SOURCES)?.iterator()?.partitionJvmSources(
    kt = { kotlinSources.add(it) },
    java = { javSources.add(it) },
  )

  val root = JvmCompilationTask(
    info = info.copy(
      toolchainInfo = info.toolchainInfo.copy(
        jvmTarget = argMap.mandatorySingle(
          KotlinBuilderFlags.JVM_TARGET,
        ),
      ),
    ),
    compileKotlin = argMap.mandatorySingle(KotlinBuilderFlags.BUILD_KOTLIN).toBoolean(),
    instrumentCoverage = argMap.mandatorySingle(KotlinBuilderFlags.INSTRUMENT_COVERAGE).toBoolean(),
    outputs = Outputs(
      jar = argMap.optionalSingle(KotlinBuilderFlags.OUTPUT),
      srcjar = argMap.optionalSingle(KotlinBuilderFlags.OUTPUT_SRCJAR),
      jdeps = argMap.optionalSingle(KotlinBuilderFlags.OUTPUT_JDEPS),
      generatedJavaSrcJar = argMap.optionalSingle(KotlinBuilderFlags.GENERATED_JAVA_SRC_JAR),
      abijar = argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR),
      generatedKspSrcJar = argMap.optionalSingle(KotlinBuilderFlags.KSP_GENERATED_JAVA_SRCJAR),
    ),
    directories = Directories(
      classes = resolveNewDirectories(workingDir, getOutputDirPath(moduleName, "classes")),
      javaClasses = resolveNewDirectories(workingDir, getOutputDirPath(moduleName, "java_classes")),
      abiClasses = if (argMap.hasAll(KotlinBuilderFlags.ABI_JAR)) {
        resolveNewDirectories(workingDir, getOutputDirPath(moduleName, "abi_classes"))
      } else null,
      generatedClasses = resolveNewDirectories(
        workingDir,
        getOutputDirPath(moduleName, "generated_classes"),
      ),
      temp = resolveNewDirectories(workingDir, getOutputDirPath(moduleName, "temp")),
      generatedSources = resolveNewDirectories(
        workingDir,
        getOutputDirPath(moduleName, "generated_sources"),
      ),
      generatedJavaSources = resolveNewDirectories(
        workingDir,
        getOutputDirPath(moduleName, "generated_java_sources"),
      ),
      generatedStubClasses = resolveNewDirectories(
        workingDir,
        getOutputDirPath(moduleName, "stubs"),
      ).toString(),
      coverageMetadataClasses = resolveNewDirectories(
        workingDir,
        getOutputDirPath(moduleName, "coverage-metadata"),
      ),
    ),
    inputs = JvmCompilationTask.Inputs(
      classpath = argMap.mandatory(KotlinBuilderFlags.CLASSPATH),
      depsArtifacts = argMap.optional(KotlinBuilderFlags.DEPS_ARTIFACTS) ?: emptyList(),
      directDependencies = argMap.mandatory(KotlinBuilderFlags.DIRECT_DEPENDENCIES),
      processors = argMap.optional(KotlinBuilderFlags.PROCESSORS) ?: emptyList(),
      processorpaths = argMap.optional(KotlinBuilderFlags.PROCESSOR_PATH) ?: emptyList(),
      stubsPluginOptions = argMap.optional(KotlinBuilderFlags.STUBS_PLUGIN_OPTIONS) ?: emptyList(),
      stubsPluginClasspath = argMap.optional(KotlinBuilderFlags.STUBS_PLUGIN_CLASS_PATH)
        ?: emptyList(),
      compilerPluginOptions = argMap.optional(KotlinBuilderFlags.COMPILER_PLUGIN_OPTIONS)
        ?: emptyList(),
      compilerPluginClasspath = argMap.optional(KotlinBuilderFlags.COMPILER_PLUGIN_CLASS_PATH)
        ?: emptyList(),
      sourceJars = argMap.optional(KotlinBuilderFlags.SOURCE_JARS) ?: emptyList(),
      kotlinSources = kotlinSources,
      javaSources = javSources,
    ),
  )
  return root
}

private fun getOutputDirPath(
  moduleName: String,
  dirName: String,
) = "_kotlinc/${moduleName}_jvm/$dirName"

private fun resolveNewDirectories(file: Path, part: String): String {
  return Files.createDirectories(file.resolve(part)).toString()
}
