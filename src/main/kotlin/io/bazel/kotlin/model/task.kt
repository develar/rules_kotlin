package io.bazel.kotlin.model

import java.nio.file.Path

data class KotlinToolchainInfo(
  @JvmField val languageVersion: String,
  @JvmField val apiVersion: String,
  @JvmField val coroutines: String? = null,
  @JvmField val jvmTarget: String? = null,
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
  @JvmField val friendPaths: List<String>,
  @JvmField val primaryOutputPath: String? = null,
  @JvmField val debug: List<String>,
  @JvmField val strictKotlinDeps: String,
  @JvmField val reducedClasspathMode: String,
)

data class JvmCompilationTask(
  @JvmField val info: CompilationTaskInfo,
  @JvmField val directories: Directories,
  @JvmField val outputs: Outputs,
  @JvmField val inputs: Inputs,
  @JvmField val compileKotlin: Boolean,
  @JvmField val instrumentCoverage: Boolean,
) {
  data class Directories(
    @JvmField val classes: Path,
    @JvmField val generatedClasses: Path,
    @JvmField val generatedSources: Path,
    @JvmField val temp: Path,
    @JvmField val generatedStubClasses: String,
    @JvmField val abiClasses: Path?,
    @JvmField val generatedJavaSources: Path,
    @JvmField val javaClasses: Path,
    @JvmField val coverageMetadataClasses: Path?,
  )

  data class Outputs(
    @JvmField val jar: String?,
    @JvmField val jdeps: String?,
    @JvmField val srcjar: String?,
    @JvmField val abijar: String?,
    @JvmField val generatedJavaSrcJar: String? = null,
    @JvmField val generatedJavaStubJar: String? = null,
    @JvmField val generatedClassJar: String? = null,
    @JvmField val generatedKspSrcJar: String?,
  )

  data class Inputs(
    @JvmField val classpath: List<String>,
    @JvmField val directDependencies: List<String>,
    @JvmField val kotlinSources: List<String>,
    @JvmField val javaSources: List<String>,
    @JvmField val sourceJars: List<String>,
    @JvmField val processors: List<String>,
    @JvmField val processorpaths: List<String>,
    @JvmField val stubsPluginOptions: List<String>,
    @JvmField val stubsPlugins: List<String> = emptyList(),
    @JvmField val stubsPluginClasspath: List<String>,
    @JvmField val compilerPluginOptions: List<String>,
    @JvmField val compilerPlugins: List<String> = emptyList(),
    @JvmField val compilerPluginClasspath: List<String>,
    @JvmField val javacFlags: List<String> = emptyList(),
    @JvmField val depsArtifacts: List<String>,
  )
}

