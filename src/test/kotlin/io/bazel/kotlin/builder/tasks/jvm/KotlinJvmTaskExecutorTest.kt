package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.KotlinJvmTestBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinJvmTaskExecutorTest {

  private val ctx = KotlinJvmTestBuilder()

  @Test
  fun testSimpleGeneratedNonJvmSourcesIgnored() {
    ctx.resetForNext()
    ctx.writeGeneratedSourceFile(
      "AGenClass.kt",
      arrayOf("package something.gen;", "class AGenClass{}")
    )
    ctx.writeGeneratedSourceFile(
      "AnotherGenClass.java",
      arrayOf("package something.gen;", "class AnotherGenClass{}")
    )
    ctx.writeGeneratedSourceFile(
      "ignore-me.txt",
      arrayOf("contents do not matter")
    )
    ctx.writeSourceFile(
      "ignore-me-regular-src.kt",
      arrayOf("contents do not matter")
    )
    ctx.writeSourceFile(
      "ignore-me-another-regular-src.java",
      arrayOf("contents do not matter")
    )
    val compileTask = ctx.buildTask()

    assertTrue(compileTask.inputs.javaSources.isEmpty())
    assertTrue(compileTask.inputs.kotlinSources.isEmpty())

    val expandedCompileTask = compileTask

    assertTrue(compileTask.inputs.javaSources.isEmpty())
    assertTrue(compileTask.inputs.kotlinSources.isEmpty())

    assertTrue(expandedCompileTask.inputs.javaSources.isNotEmpty())
    assertNotNull(expandedCompileTask.inputs.javaSources.find { path ->
      path.endsWith("generated_sources/AnotherGenClass.java")
    })
    assertEquals(expandedCompileTask.inputs.javaSources.size, 1)
    assertNotNull(expandedCompileTask.inputs.kotlinSources.find { path ->
      path.endsWith("generated_sources/AGenClass.kt")
    })
    assertEquals(expandedCompileTask.inputs.kotlinSources.size, 1)
  }
}
