package io.bazel.kotlin.builder.tasks.jvm

import com.google.common.truth.Truth.assertThat
import com.google.devtools.build.lib.view.proto.Deps
import com.google.devtools.build.lib.view.proto.Deps.Dependency
import io.bazel.kotlin.builder.tasks.MergeJdeps
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.worker.WorkerContext
import io.bazel.worker.doTask
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path

@RunWith(JUnit4::class)
class JdepsMergerTest {

  private val wrkDir = Files.createTempDirectory("JdepsMergerEnvironmentTest")

  private fun jdeps(
    path: String,
    buildDeps: (Deps.Dependencies.Builder.() -> Deps.Dependencies.Builder),
  ): Path {
    val jdepsPath = Files.createDirectories(wrkDir.resolve("jdeps")).resolve(path)

    BufferedOutputStream(Files.newOutputStream(jdepsPath)).use {
      Deps.Dependencies.newBuilder().buildDeps().build().writeTo(it)
    }

    return jdepsPath
  }

  private fun out(name: String): Path {
    return Files.createDirectories(wrkDir.resolve("out")).resolve(name)
  }

  fun ktJvmLibrary(label: String, suffix: String = ""): String {
    val path = Files.createDirectories(wrkDir.resolve("out")).resolve("lib${label}$suffix.jar")
    JarCreator(
      path = path,
      targetLabel = label,
      injectingRuleKind = "kt_jvm_library",
    ).use {
    }
    return path.toString()
  }

  @Test
  fun `merge all deps`() {
    val kotlinJdeps = jdeps("kt.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.EXPLICIT
          path = "/path/to/kt_dep.jar"
          build()
        },
      )
    }

    val javaJdeps = jdeps("java.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.EXPLICIT
          path = "/path/to/java_dep.jar"
          build()
        },
      )
    }

    val mergedJdeps = out("merged.jdeps")

    val result = WorkerContext.run { workerContext ->
      doTask(workerContext, "jdepsmerge") { taskCtx ->
        MergeJdeps().invoke(
          taskCtx,
          args {
            flag(JdepsMergerFlags.TARGET_LABEL, "//foo/bar:baz")
            input(kotlinJdeps)
            input(javaJdeps)
            flag(JdepsMergerFlags.OUTPUT, mergedJdeps)
            flag(JdepsMergerFlags.REPORT_UNUSED_DEPS, "off")
          },
        )
      }
    }

    assertThat(result.status).isEqualTo(0)

    val depsProto = depsProto(mergedJdeps)
    assertThat(depsProto.ruleLabel).isEqualTo("//foo/bar:baz")
    assertThat(depsProto.dependencyList.map { it.path }).containsExactly(
      "/path/to/kt_dep.jar",
      "/path/to/java_dep.jar",
    )
  }

  @Test
  fun `merge conflicting deps`() {
    val kotlinJdeps = jdeps("kt.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.UNUSED
          path = "/path/to/shared_dep.jar"
          build()
        },
      )
    }

    val javaJdeps = jdeps("java.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.EXPLICIT
          path = "/path/to/shared_dep.jar"
          build()
        },
      )
    }

    val mergedJdeps = out("merged.jdeps")

    val result = WorkerContext.run { workerContext ->
      doTask(workerContext, "jdepsmerge") { taskCtx ->
        MergeJdeps().invoke(
          taskCtx,
          args {
            flag(JdepsMergerFlags.TARGET_LABEL, "//foo/bar:baz")
            input(kotlinJdeps)
            input(javaJdeps)
            flag(JdepsMergerFlags.OUTPUT, mergedJdeps)
            flag(JdepsMergerFlags.REPORT_UNUSED_DEPS, "off")
          },
        )
      }
    }

    assertThat(result.status).isEqualTo(0)

    val depsProto = depsProto(mergedJdeps)
    assertThat(depsProto.dependencyList.map { it.path }).containsExactly("/path/to/shared_dep.jar")
    assertThat(depsProto.dependencyList.map { it.kind }).containsExactly(Dependency.Kind.EXPLICIT)
  }

  @Test
  fun `unused deps report warning`() {
    val unusedKotlinDep = ktJvmLibrary("kotlin_dep")
    val kotlinJdeps = jdeps("kt.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.UNUSED
          path = unusedKotlinDep
          build()
        },
      )
    }

    val javaJdeps = jdeps("java.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.EXPLICIT
          path = ktJvmLibrary("java_dep")
          build()
        },
      )
    }

    val mergedJdeps = out("merged.jdeps")

    val result = WorkerContext.run { workerContext ->
      doTask(workerContext, "jdepsmerge") { taskCtx ->
        MergeJdeps().invoke(
          taskCtx,
          args {
            input(kotlinJdeps)
            input(javaJdeps)
            flag(JdepsMergerFlags.TARGET_LABEL, "//foo/bar:baz")
            flag(JdepsMergerFlags.OUTPUT, mergedJdeps)
            flag(JdepsMergerFlags.REPORT_UNUSED_DEPS, "warn")
          },
        )
      }
    }
    assertThat(result.status).isEqualTo(0)
    assertThat(result.log.out.toString()).contains("'remove deps kotlin_dep' //foo/bar:baz")

    val depsProto = depsProto(mergedJdeps)
    assertThat(
      depsProto.dependencyList
        .filter { it.kind == Dependency.Kind.UNUSED }
        .map { it.path },
    ).containsExactly(unusedKotlinDep)
  }

  @Test
  fun `unused deps report error`() {
    val unusedKotlinDep = ktJvmLibrary("kotlin_dep")
    val kotlinJdeps = jdeps("kt.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.UNUSED
          path = unusedKotlinDep
          build()
        },
      )
    }

    val javaJdeps = jdeps("java.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.EXPLICIT
          path = ktJvmLibrary("java_dep")
          build()
        },
      )
    }

    val mergedJdeps = out("merged.jdeps")

    val worker = MergeJdeps()
    val result = WorkerContext.run { workerContext ->
      doTask(workerContext, "jdepsmerge") { taskCtx ->
        worker.invoke(
          taskCtx,
          args {
            input(kotlinJdeps)
            input(javaJdeps)
            flag(JdepsMergerFlags.TARGET_LABEL, "//foo/bar:baz")
            flag(JdepsMergerFlags.OUTPUT, mergedJdeps)
            flag(JdepsMergerFlags.REPORT_UNUSED_DEPS, "error")
          },
        )
      }
    }
    assertThat(result.status).isEqualTo(1)
    assertThat(result.log.out.toString()).contains("'remove deps kotlin_dep' //foo/bar:baz")

    val depsProto = depsProto(mergedJdeps)
    assertThat(
      depsProto.dependencyList
        .filter { it.kind == Dependency.Kind.UNUSED }
        .map { it.path },
    ).containsExactly(unusedKotlinDep)
  }

  @Test
  fun `unused deps multiple jars for label`() {
    val unusedKotlinDepA = ktJvmLibrary("kotlin_dep", "_a")
    val unusedKotlinDepB = ktJvmLibrary("kotlin_dep", "_b")
    val kotlinJdeps = jdeps("kt.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.UNUSED
          path = unusedKotlinDepA
          build()
        },
      )
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.UNUSED
          path = unusedKotlinDepB
          build()
        },
      )
    }

    val mergedJdeps = out("merged.jdeps")

    val result = WorkerContext.run { workerContext ->
      doTask(workerContext, "jdepsmerge") { taskCtx ->
        MergeJdeps().invoke(
          taskCtx,
          args {
            input(kotlinJdeps)
            flag(JdepsMergerFlags.TARGET_LABEL, "//foo/bar:baz")
            flag(JdepsMergerFlags.OUTPUT, mergedJdeps)
            flag(JdepsMergerFlags.REPORT_UNUSED_DEPS, "warn")
          },
        )
      }
    }
    assertThat(result.status).isEqualTo(0)
    assertThat(result.log.out.toString()).contains("'remove deps kotlin_dep' //foo/bar:baz")
  }

  @Test
  fun `used deps multiple jars for label`() {
    val unusedKotlinDep = ktJvmLibrary("kotlin_dep", "_a")
    val usedKotlinDep = ktJvmLibrary("kotlin_dep", "_b")
    val kotlinJdeps = jdeps("kt.jdeps") {
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.UNUSED
          path = unusedKotlinDep
          build()
        },
      )
      addDependency(
        with(Dependency.newBuilder()) {
          kind = Dependency.Kind.EXPLICIT
          path = usedKotlinDep
          build()
        },
      )
    }

    val mergedJdeps = out("merged.jdeps")

    val result = WorkerContext.run { workerContext ->
      doTask(workerContext, "jdepsmerge") { taskCtx ->
        MergeJdeps().invoke(
          taskCtx,
          args {
            input(kotlinJdeps)
            flag(JdepsMergerFlags.TARGET_LABEL, "//foo/bar:baz")
            flag(JdepsMergerFlags.OUTPUT, mergedJdeps)
            flag(JdepsMergerFlags.REPORT_UNUSED_DEPS, "warn")
          },
        )
      }
    }
    assertThat(result.status).isEqualTo(0)

    val depsProto = depsProto(mergedJdeps)
    assertThat(
      depsProto.dependencyList
        .filter { it.kind == Dependency.Kind.EXPLICIT }
        .map { it.path },
    ).containsExactly(usedKotlinDep)
  }

  private fun depsProto(mergedJdeps: Path) =
    Deps.Dependencies.parseFrom(BufferedInputStream(Files.newInputStream(mergedJdeps)))

  private fun args(init: ArgsBuilder.() -> Unit) = with(ArgsBuilder()) {
    init()
    list()
  }

  class ArgsBuilder(val args: MutableMap<JdepsMergerFlags, MutableList<String>> = mutableMapOf()) {
    fun flag(flag: JdepsMergerFlags, value: String) {
      args[flag] = (args[flag] ?: mutableListOf()).also {
        it.add(value)
      }
    }

    fun flag(flag: JdepsMergerFlags, p: Path) {
      flag(flag, p.toString())
    }

    fun input(src: Path) {
      flag(JdepsMergerFlags.INPUTS, src.toString())
    }

    fun list(): List<String> {
      return args.flatMap { entry ->
        entry.value.flatMap { value ->
          listOf(entry.key.name, value)
        }
      }
    }
  }
}
