package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.utils.bazelRuleKind
import io.bazel.kotlin.builder.utils.jars.JarCreator
import io.bazel.kotlin.model.JvmCompilationTask
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

internal fun JvmCompilationTask.createCoverageInstrumentedJar() {
  val instrumentedClassesDirectory = Paths.get(directories.coverageMetadataClasses)
  Files.createDirectories(instrumentedClassesDirectory)

  val instr = Instrumenter(OfflineInstrumentationAccessGenerator())

  instrumentRecursively(instr, instrumentedClassesDirectory, Path.of(directories.classes))
  instrumentRecursively(instr, instrumentedClassesDirectory, Path.of(directories.javaClasses))
  instrumentRecursively(
    instr,
    instrumentedClassesDirectory,
    Path.of(directories.generatedClasses),
  )

  val pathsForCoverage =
    instrumentedClassesDirectory.resolve(
      "${Path.of(outputs.jar).fileName}-paths-for-coverage.txt",
    )
  Files.write(
    pathsForCoverage,
    inputs.javaSources + inputs.kotlinSources,
  )

  JarCreator(
    path = Path.of(outputs.jar),
    targetLabel = info.label,
    injectingRuleKind = info.bazelRuleKind,
  ).use {
    it.addDirectory(Path.of(directories.classes))
    it.addDirectory(Path.of(directories.javaClasses))
    it.addDirectory(Path.of(directories.generatedClasses))
    it.addDirectory(instrumentedClassesDirectory)
  }
}

private fun instrumentRecursively(
  instr: Instrumenter,
  metadataDir: Path,
  root: Path,
) {
  val visitor =
    object : SimpleFileVisitor<Path>() {
      override fun visitFile(
        file: Path,
        attrs: BasicFileAttributes,
      ): FileVisitResult {
        if (file.toFile().extension != "class") {
          return FileVisitResult.CONTINUE
        }

        val absoluteUninstrumentedCopy = Path.of("$file.uninstrumented")
        val uninstrumentedCopy = metadataDir.resolve(root.relativize(absoluteUninstrumentedCopy))

        Files.createDirectories(uninstrumentedCopy.parent)
        Files.move(file, uninstrumentedCopy)

        Files.newInputStream(uninstrumentedCopy).buffered().use { input ->
          Files.newOutputStream(file).buffered().use { output ->
            instr.instrument(input, output, file.toString())
          }
        }

        return FileVisitResult.CONTINUE
      }
    }
  Files.walkFileTree(root, visitor)
}
