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

import io.bazel.kotlin.builder.utils.BazelRunFiles
import io.bazel.kotlin.builder.utils.verified
import java.io.File
import java.io.PrintStream
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URLClassLoader

class KotlinToolchain private constructor(
  private val baseJars: List<File>,
  @JvmField val kapt3Plugin: CompilerPlugin,
  @JvmField val jvmAbiGen: CompilerPlugin,
  @JvmField val skipCodeGen: CompilerPlugin,
  @JvmField val jdepsGen: CompilerPlugin,
  @JvmField val kspSymbolProcessingApi: CompilerPlugin,
  @JvmField val kspSymbolProcessingCommandLine: CompilerPlugin,
) {
  companion object {
    private val JVM_ABI_PLUGIN by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlin...jvm-abi-gen",
        ).toPath()
    }

    private val KAPT_PLUGIN by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlin...kapt",
        ).toPath()
    }

    private val COMPILER by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@rules_kotlin...compiler",
        ).toPath()
    }

    private val SKIP_CODE_GEN_PLUGIN by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@rules_kotlin...skip-code-gen",
        ).toPath()
    }

    private val JDEPS_GEN_PLUGIN by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@rules_kotlin...jdeps-gen",
        ).toPath()
    }

    private val KOTLINC by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlin...kotlin-compiler",
        ).toPath()
    }

    private val KSP_SYMBOL_PROCESSING_API by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_google_ksp...symbol-processing-api",
        ).toPath()
    }

    private val KSP_SYMBOL_PROCESSING_CMDLINE by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_google_ksp...symbol-processing-cmdline",
        ).toPath()
    }

    private val KOTLIN_REFLECT by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@rules_kotlin..kotlin.compiler.kotlin-reflect",
        ).toPath()
    }

    private val KOTLINX_SERIALIZATION_CORE_JVM by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlinx...serialization-core-jvm",
        ).toPath()
    }

    private val KOTLINX_SERIALIZATION_JSON by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlinx...serialization-json",
        ).toPath()
    }

    private val KOTLINX_SERIALIZATION_JSON_JVM by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlinx...serialization-json-jvm",
        ).toPath()
    }

    fun createToolchain(): KotlinToolchain {
      return createToolchain(
        KOTLINC.verified().absoluteFile,
        COMPILER.verified().absoluteFile,
        JVM_ABI_PLUGIN.verified().absoluteFile,
        SKIP_CODE_GEN_PLUGIN.verified().absoluteFile,
        JDEPS_GEN_PLUGIN.verified().absoluteFile,
        KAPT_PLUGIN.verified().absoluteFile,
        KSP_SYMBOL_PROCESSING_API.toFile(),
        KSP_SYMBOL_PROCESSING_CMDLINE.toFile(),
        KOTLINX_SERIALIZATION_CORE_JVM.toFile(),
        KOTLINX_SERIALIZATION_JSON.toFile(),
        KOTLINX_SERIALIZATION_JSON_JVM.toFile(),
      )
    }

    fun createToolchain(
      kotlinc: File,
      compiler: File,
      jvmAbiGenFile: File,
      skipCodeGenFile: File,
      jdepsGenFile: File,
      kaptFile: File,
      kspSymbolProcessingApi: File,
      kspSymbolProcessingCommandLine: File,
      kotlinxSerializationCoreJvm: File,
      kotlinxSerializationJson: File,
      kotlinxSerializationJsonJvm: File,
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
          jvmAbiGenFile.path,
          "org.jetbrains.kotlin.jvm.abi",
        ),
        skipCodeGen = CompilerPlugin(
          skipCodeGenFile.path,
          "io.bazel.kotlin.plugin.SkipCodeGen",
        ),
        jdepsGen = CompilerPlugin(
          jdepsGenFile.path,
          "io.bazel.kotlin.plugin.jdeps.JDepsGen",
        ),
        kapt3Plugin = CompilerPlugin(
          kaptFile.path,
          "org.jetbrains.kotlin.kapt3",
        ),
        kspSymbolProcessingApi = CompilerPlugin(
          kspSymbolProcessingApi.absolutePath,
          "com.google.devtools.ksp.symbol-processing",
        ),
        kspSymbolProcessingCommandLine = CompilerPlugin(
          kspSymbolProcessingCommandLine.absolutePath,
          "com.google.devtools.ksp.symbol-processing",
        ),
      )
    }

    internal fun createClassLoader(toolchain: KotlinToolchain): URLClassLoader {
      val baseJars = toolchain.baseJars
      return try {
        // not system, but platform as parent - we should not include app classpath, only platform (JDK)
        URLClassLoader(
          baseJars.map { it.toURI().toURL() }.toTypedArray(),
          ClassLoader.getPlatformClassLoader(),
        )
      } catch (e: Exception) {
        throw RuntimeException(baseJars.toString(), e)
      }
    }
  }

  fun toolchainWithReflect(kotlinReflect: File? = null): KotlinToolchain {
    return KotlinToolchain(
      baseJars = baseJars + listOf(kotlinReflect ?: KOTLIN_REFLECT.toFile()),
      kapt3Plugin = kapt3Plugin,
      jvmAbiGen = jvmAbiGen,
      skipCodeGen = skipCodeGen,
      jdepsGen = jdepsGen,
      kspSymbolProcessingApi = kspSymbolProcessingApi,
      kspSymbolProcessingCommandLine = kspSymbolProcessingCommandLine,
    )
  }
}

data class CompilerPlugin(
  @JvmField val jarPath: String,
  @JvmField val id: String,
)

private val lookup = MethodHandles.lookup()

class KotlincInvoker(toolchain: KotlinToolchain) {
  private val execMethod: MethodHandle

  init {
    System.setProperty("zip.handler.uses.crc.instead.of.timestamp", "true")
    execMethod = lookup.findStatic(
      KotlinToolchain.createClassLoader(toolchain).loadClass("io.bazel.kotlin.compiler.BazelK2JVMCompiler"),
      "exec",
      MethodType.methodType(Integer.TYPE, PrintStream::class.java, Array<String>::class.java),
    )
  }

  // Kotlin error codes:
  // 1 is a standard compilation error
  // 2 is an internal error
  // 3 is the script execution error
  fun compile(
    args: Array<String>,
    out: PrintStream,
  ): Int {
    return execMethod.invokeExact(out, args) as Int
  }
}
