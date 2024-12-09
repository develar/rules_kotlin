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
package io.bazel.kotlin.builder.toolchain

import io.bazel.kotlin.builder.utils.resolveVerifiedFromProperty
import java.io.PrintStream
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URLClassLoader
import java.nio.file.Path

class KotlinToolchain private constructor(
  private val baseJars: List<Path>,
  @JvmField val jvmAbiGen: CompilerPlugin,
  @JvmField val skipCodeGen: CompilerPlugin,
  @JvmField val jdepsGen: CompilerPlugin,
  @JvmField val kspSymbolProcessingApi: CompilerPlugin,
  @JvmField val kspSymbolProcessingCommandLine: CompilerPlugin,
) {
  companion object {
    private val COMPILER by lazy {
      resolveVerifiedFromProperty("@rules_kotlin...compiler")
    }

    private val SKIP_CODE_GEN_PLUGIN by lazy {
      resolveVerifiedFromProperty("@rules_kotlin...skip-code-gen")
    }

    private val JDEPS_GEN_PLUGIN by lazy {
      resolveVerifiedFromProperty("@rules_kotlin...jdeps-gen")
    }

    private val KSP_SYMBOL_PROCESSING_API by lazy {
      resolveVerifiedFromProperty("@com_github_google_ksp...symbol-processing-api")
    }

    private val KSP_SYMBOL_PROCESSING_CMDLINE by lazy {
      resolveVerifiedFromProperty("@com_github_google_ksp...symbol-processing-cmdline")
    }

    private val KOTLIN_REFLECT by lazy {
      resolveVerifiedFromProperty("@rules_kotlin..kotlin.compiler.kotlin-reflect")
    }

    fun createToolchain(): KotlinToolchain {
      return createToolchain(
        kotlinc = resolveVerifiedFromProperty("@com_github_jetbrains_kotlin...kotlin-compiler"),
        compiler = COMPILER,
        jvmAbiGenFile = resolveVerifiedFromProperty("@com_github_jetbrains_kotlin...jvm-abi-gen"),
        skipCodeGenFile = SKIP_CODE_GEN_PLUGIN,
        jdepsGenFile = JDEPS_GEN_PLUGIN,
        kspSymbolProcessingApi = KSP_SYMBOL_PROCESSING_API,
        kspSymbolProcessingCommandLine = KSP_SYMBOL_PROCESSING_CMDLINE,
        kotlinxSerializationCoreJvm = resolveVerifiedFromProperty("@com_github_jetbrains_kotlinx...serialization-core-jvm"),
        kotlinxSerializationJson = resolveVerifiedFromProperty("@com_github_jetbrains_kotlinx...serialization-json"),
        kotlinxSerializationJsonJvm = resolveVerifiedFromProperty("@com_github_jetbrains_kotlinx...serialization-json-jvm"),
      )
    }

    fun createToolchain(
      kotlinc: Path,
      compiler: Path,
      jvmAbiGenFile: Path,
      skipCodeGenFile: Path,
      jdepsGenFile: Path,
      kspSymbolProcessingApi: Path,
      kspSymbolProcessingCommandLine: Path,
      kotlinxSerializationCoreJvm: Path,
      kotlinxSerializationJson: Path,
      kotlinxSerializationJsonJvm: Path,
    ): KotlinToolchain {
      return KotlinToolchain(
        baseJars = listOf(
          kotlinc,
          compiler,
          // plugins *must* be preloaded. Not doing so causes class conflicts
          // (and a NoClassDef err) in the compiler extension interfaces.
          // This may cause issues in accepting user defined compiler plugins.
          jvmAbiGenFile,
          skipCodeGenFile,
          jdepsGenFile,
          kspSymbolProcessingApi,
          kspSymbolProcessingCommandLine,
          kotlinxSerializationCoreJvm,
          kotlinxSerializationJson,
          kotlinxSerializationJsonJvm,
        ),
        jvmAbiGen = CompilerPlugin(
          jvmAbiGenFile.toString(),
          "org.jetbrains.kotlin.jvm.abi",
        ),
        skipCodeGen = CompilerPlugin(
          skipCodeGenFile.toString(),
          "io.bazel.kotlin.plugin.SkipCodeGen",
        ),
        jdepsGen = CompilerPlugin(
          jdepsGenFile.toString(),
          "io.bazel.kotlin.plugin.jdeps.JDepsGen",
        ),
        kspSymbolProcessingApi = CompilerPlugin(
          kspSymbolProcessingApi.toAbsolutePath().toString(),
          "com.google.devtools.ksp.symbol-processing",
        ),
        kspSymbolProcessingCommandLine = CompilerPlugin(
          kspSymbolProcessingCommandLine.toAbsolutePath().toString(),
          "com.google.devtools.ksp.symbol-processing",
        ),
      )
    }
  }

  fun getBaseJarsWithReflect(): List<Path> = baseJars + listOf(KOTLIN_REFLECT)
}

data class CompilerPlugin(
  @JvmField val jarPath: String,
  @JvmField val id: String,
)

class KotlincInvoker(baseJars: List<Path>) {
  private val execMethod: MethodHandle

  init {
    System.setProperty("zip.handler.uses.crc.instead.of.timestamp", "true")

    val classloader = try {
      // not system, but platform as parent - we should not include app classpath, only platform (JDK)
      URLClassLoader(
        baseJars.map { it.toUri().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
      )
    } catch (e: Exception) {
      throw RuntimeException(baseJars.toString(), e)
    }
    execMethod = MethodHandles.lookup().findStatic(
      classloader.loadClass("io.bazel.kotlin.compiler.BazelK2JVMCompiler"),
      "exec",
      MethodType.methodType(Integer.TYPE, PrintStream::class.java, Array<String>::class.java),
    )
  }

  // Kotlin error codes:
  // 1 is a standard compilation error
  // 2 is an internal error
  // 3 is the script execution error
  fun compile(
    args: List<String>,
    out: PrintStream,
  ): Int {
    return execMethod.invokeExact(out, args.toTypedArray()) as Int
  }
}
