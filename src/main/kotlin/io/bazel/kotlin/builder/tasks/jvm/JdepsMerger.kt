package io.bazel.kotlin.builder.tasks.jvm

import com.google.devtools.build.lib.view.proto.Deps
import io.bazel.kotlin.builder.utils.Flag
import io.bazel.kotlin.builder.utils.jars.JarOwner
import io.bazel.worker.WorkerContext
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Declares the flags used by the java builder.
 */
enum class JdepsMergerFlags(
  override val flag: String,
) : Flag {
  INPUTS("--inputs"),
  OUTPUT("--output"),
  TARGET_LABEL("--target_label"),
  REPORT_UNUSED_DEPS("--report_unused_deps"),
}

/**
 * Persistent worker capable command line program for merging multiple Jdeps files into a single
 * file.
 */
class JdepsMerger {
  companion object {
    private fun readJarOwnerFromManifest(jarPath: Path): String? {
      JarFile(jarPath.toFile()).use { jarFile ->
        val manifest = jarFile.manifest ?: return null
        return manifest.mainAttributes.getValue(JarOwner.TARGET_LABEL)
      }
    }

    fun merge(
      ctx: WorkerContext.TaskContext,
      label: String,
      inputs: List<String>,
      output: String,
      reportUnusedDeps: String,
    ): Int {
      val rootBuilder = Deps.Dependencies.newBuilder()
      rootBuilder.success = false
      rootBuilder.ruleLabel = label

      val dependencyMap = sortedMapOf<String, Deps.Dependency>()
      for (input in inputs) {
        val deps = BufferedInputStream(Files.newInputStream(Path.of(input))).use { input ->
          Deps.Dependencies.parseFrom(input)
        }
        for (dep in deps.dependencyList) {
          val oldDep = dependencyMap[dep.path]
          // Replace dependency if it has a stronger kind than one we encountered before.
          if (oldDep == null || oldDep.kind > dep.kind) {
            dependencyMap.put(dep.path, dep)
          }
        }
      }

      rootBuilder.addAllDependency(dependencyMap.values)
      rootBuilder.success = true

      Files.write(Path.of(output), rootBuilder.build().toByteArray())

      if (reportUnusedDeps == "off") {
        return 0
      }

      val kindMap = LinkedHashMap<String, Deps.Dependency.Kind>()

      // A target might produce multiple jars (Android produces `_resources.jar`),
      // so we need to make sure we don't mart the dependency as unused unless all the jars are unused.
      for (dep in dependencyMap.values) {
        var label = readJarOwnerFromManifest(Path.of(dep.path)) ?: continue
        if (label.startsWith("@@") || label.startsWith("@/")) {
          label = label.substring(1)
        }
        if (kindMap.getOrDefault(label, Deps.Dependency.Kind.UNUSED) >= dep.kind) {
          kindMap.put(label, dep.kind)
        }
      }

      val unusedLabels = kindMap.entries
        .asSequence()
        .filter { it.value == Deps.Dependency.Kind.UNUSED }
        .map { it.key }
        .filter { it != label }
        .toList()

      if (unusedLabels.isNotEmpty()) {
        ctx.info {
          val open = "\u001b[35m\u001b[1m"
          val close = "\u001b[0m"
          return@info """
          |$open ** Please remove the following dependencies:$close ${unusedLabels.joinToString(
            " ",
          )} from $label 
          |$open ** You can use the following buildozer command:$close buildozer 'remove deps ${
            unusedLabels.joinToString(" ")
          }' $label
          """.trimMargin()
        }
        return if (reportUnusedDeps == "error") 1 else 0
      }
      return 0
    }
  }
}
