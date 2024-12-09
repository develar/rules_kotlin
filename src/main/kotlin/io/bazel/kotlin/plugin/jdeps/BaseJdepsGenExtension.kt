package io.bazel.kotlin.plugin.jdeps

import com.google.devtools.build.lib.view.proto.Deps
import io.bazel.kotlin.builder.utils.jars.JarOwner
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.nio.file.Files
import java.nio.file.Path

sealed class BaseJdepsGenExtension(
  protected val configuration: CompilerConfiguration,
) {
  protected fun onAnalysisCompleted(
    explicitClassesCanonicalPaths: Set<String>,
    implicitClassesCanonicalPaths: Set<String>,
  ) {
    val directDeps = configuration.getList(JdepsGenConfigurationKeys.DIRECT_DEPENDENCIES)
    val targetLabel = configuration.getNotNull(JdepsGenConfigurationKeys.TARGET_LABEL)
    val explicitDeps = createDepsMap(explicitClassesCanonicalPaths)

    doWriteJdeps(
      directDeps = directDeps,
      targetLabel = targetLabel,
      explicitDeps = explicitDeps,
      implicitClassesCanonicalPaths = implicitClassesCanonicalPaths,
      configuration = configuration,
    )

    doStrictDeps(
      compilerConfiguration = configuration,
      targetLabel = targetLabel,
      directDeps = directDeps,
      explicitDeps = explicitDeps,
    )
  }
}

/**
 * Returns a map of jars to classes loaded from those jars.
 */
private fun createDepsMap(classes: Set<String>): Map<String, List<String>> {
  val jarsToClasses = HashMap<String, MutableList<String>>()
  for (aClass in classes) {
    val parts = aClass.split("!/")
    val jarPath = parts[0]
    if (jarPath.endsWith(".jar")) {
      jarsToClasses.computeIfAbsent(jarPath) { ArrayList() }.add(parts[1])
    }
  }
  return jarsToClasses
}

private fun doWriteJdeps(
  directDeps: MutableList<String>,
  targetLabel: String,
  explicitDeps: Map<String, List<String>>,
  implicitClassesCanonicalPaths: Set<String>,
  configuration: CompilerConfiguration,
) {
  val implicitDeps = createDepsMap(implicitClassesCanonicalPaths)

  val deps = mutableListOf<Deps.Dependency>()

  val unusedDeps = directDeps.subtract(explicitDeps.keys)
  for (jarPath in unusedDeps) {
    val dependency = Deps.Dependency.newBuilder()
    dependency.kind = Deps.Dependency.Kind.UNUSED
    dependency.path = jarPath
    deps.add(dependency.build())
  }

  for ((jarPath, _) in explicitDeps) {
    val dependency = Deps.Dependency.newBuilder()
    dependency.kind = Deps.Dependency.Kind.EXPLICIT
    dependency.path = jarPath
    deps.add(dependency.build())
  }

  for (path in implicitDeps.keys.subtract(explicitDeps.keys)) {
    val dependency = Deps.Dependency.newBuilder()
    dependency.kind = Deps.Dependency.Kind.IMPLICIT
    dependency.path = path
    deps.add(dependency.build())
  }

  val rootBuilder = Deps.Dependencies.newBuilder()
  rootBuilder.success = true
  rootBuilder.ruleLabel = targetLabel

  deps.sortBy { it.path }
  rootBuilder.addAllDependency(deps)
  // build and write out deps.proto
  val jdepsOutput = configuration.getNotNull(JdepsGenConfigurationKeys.OUTPUT_JDEPS)
  Files.write(Path.of(jdepsOutput), rootBuilder.build().toByteArray())
}

private fun doStrictDeps(
  compilerConfiguration: CompilerConfiguration,
  targetLabel: String,
  directDeps: MutableList<String>,
  explicitDeps: Map<String, List<String>>,
) {
  when (compilerConfiguration.getNotNull(JdepsGenConfigurationKeys.STRICT_KOTLIN_DEPS)) {
    "warn" -> checkStrictDeps(explicitDeps, directDeps, targetLabel)
    "error" -> {
      require(!checkStrictDeps(explicitDeps, directDeps, targetLabel)) {
        "Strict Deps Violations - please fix"
      }
    }
  }
}

/**
 * Prints strict deps warnings and returns true if violations were found.
 */
private fun checkStrictDeps(
  result: Map<String, List<String>>,
  directDeps: List<String>,
  targetLabel: String,
): Boolean {
  val missingStrictDeps = result.keys
    .asSequence()
    .filter { !directDeps.contains(it) }
    .map { JarOwner.readJarOwnerFromManifest(Path.of(it)) }
    .toList()

  if (missingStrictDeps.isEmpty()) {
    return false
  }

  val missingStrictLabels = missingStrictDeps.mapNotNull { it.label }

  val open = "\u001b[35m\u001b[1m"
  val close = "\u001b[0m"

  var command =
    """
    $open ** Please add the following dependencies:$close
    ${
      missingStrictDeps.map { it.label ?: it.jar }.joinToString(" ")
    } to $targetLabel
    """

  if (missingStrictLabels.isNotEmpty()) {
    command += """$open ** You can use the following buildozer command:$close
    buildozer 'add deps ${
      missingStrictLabels.joinToString(" ")
    }' $targetLabel
    """
  }

  println(command.trimIndent())
  return true
}

