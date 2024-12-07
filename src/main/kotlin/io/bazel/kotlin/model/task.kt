package io.bazel.kotlin.model

import java.nio.file.Path

data class KotlinToolchainInfo(
  @JvmField val languageVersion: String,
  @JvmField val apiVersion: String,
  @JvmField val coroutines: String? = null,
)

enum class RuleKind {
  LIBRARY,
  BINARY,
  TEST,
  IMPORT
}

enum class Platform {
  JVM, UNRECOGNIZED
}

data class CompilationTaskInfo(
  @JvmField val label: String,
  @JvmField val platform: Platform,
  @JvmField val ruleKind: RuleKind,
  @JvmField val moduleName: String,
  @JvmField val passthroughFlags: List<String>,
  @JvmField val toolchainInfo: KotlinToolchainInfo,
  @JvmField val primaryOutputPath: String? = null,
  @JvmField val debug: List<String>,
  @JvmField val strictKotlinDeps: String,
  @JvmField val reducedClasspathMode: String,
)

data class JvmCompilationTask(
  @JvmField val jvmTarget: String? = null,
  @JvmField val info: CompilationTaskInfo,
  @JvmField val directories: Directories,
  @JvmField val outputs: Outputs,
  @JvmField val inputs: Inputs,
  @JvmField val compileKotlin: Boolean,
  @JvmField val instrumentCoverage: Boolean,

  @JvmField val friendPaths: List<Path>,
)

data class Directories(
  @JvmField val classes: Path,
  @JvmField val generatedClasses: Path,
  @JvmField val generatedSources: Path,
  @JvmField val incrementalData: Path,
  @JvmField val temp: Path,
  @JvmField val abiClasses: Path?,
  @JvmField val generatedJavaSources: Path,
  @JvmField val coverageMetadataClasses: Path?,
)

data class Outputs(
  @JvmField val jar: Path?,
  @JvmField val jdeps: Path?,
  @JvmField val srcjar: Path?,
  @JvmField val abiJar: Path?,
  @JvmField val generatedJavaSrcJar: String? = null,
  @JvmField val generatedClassJar: String? = null,
  @JvmField val generatedKspSrcJar: Path?,
)

data class Inputs(
  @JvmField val classpath: List<Path>,
  @JvmField val directDependencies: List<String>,

  @JvmField val kotlinSources: List<String>,
  @JvmField val javaSources: List<String>,

  @JvmField val processors: List<String>,
  @JvmField val processorPaths: List<String>,
  @JvmField val stubsPluginOptions: List<String>,
  @JvmField val stubsPlugins: List<String> = emptyList(),
  @JvmField val stubsPluginClasspath: List<String>,
  @JvmField val compilerPluginOptions: List<String>,
  @JvmField val compilerPlugins: List<String> = emptyList(),
  @JvmField val compilerPluginClasspath: List<Path>,
  @JvmField val javacFlags: List<String> = emptyList(),
  @JvmField val depsArtifacts: List<String>,
)

