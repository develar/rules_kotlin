package io.bazel.kotlin.plugin.jdeps.k2

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.utils.sourceElement
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.forEachType
import org.jetbrains.kotlin.name.ClassId

//private const val JAR_FILE_SEPARATOR = "!/"
private const val ANONYMOUS = "<anonymous>"

internal class ClassUsageRecorder(
//  private val rootPath: String,
) {
  @JvmField val explicitClassesCanonicalPaths: MutableSet<String> = HashSet()
  @JvmField val implicitClassesCanonicalPaths: MutableSet<String> = HashSet()

//  private val javaHome: String by lazy { System.getenv()["JAVA_HOME"] ?: "<not set>" }

  internal fun recordTypeRef(
    typeRef: FirTypeRef,
    context: CheckerContext,
    isExplicit: Boolean = true,
    collectTypeArguments: Boolean = true,
    visited: MutableSet<Pair<ClassId, Boolean>> = mutableSetOf(),
  ) {
    recordConeType(typeRef.coneType, context, isExplicit, collectTypeArguments, visited)
  }

  internal fun recordConeType(
    coneKotlinType: ConeKotlinType,
    context: CheckerContext,
    isExplicit: Boolean = true,
    collectTypeArguments: Boolean = true,
    visited: MutableSet<Pair<ClassId, Boolean>> = mutableSetOf(),
  ) {
    if (collectTypeArguments) {
      coneKotlinType.forEachType(
        action = { coneType ->
          val classId = coneType.classId ?: return@forEachType
          if (ANONYMOUS in classId.toString()) return@forEachType
          context.session.symbolProvider
            .getClassLikeSymbolByClassId(classId)
            ?.let { recordClass(
              firClass = it,
              context = context,
              isExplicit = isExplicit,
              collectTypeArguments = collectTypeArguments,
              visited = visited
            ) }
        },
      )
    } else {
      coneKotlinType.classId?.let { classId ->
        if (!classId.isLocal) {
          context.session.symbolProvider
            .getClassLikeSymbolByClassId(classId)
            ?.let { recordClass(
              firClass = it,
              context = context,
              isExplicit = isExplicit,
              collectTypeArguments = false,
              visited = visited
            ) }
        }
      }
    }
  }

  internal fun recordClass(
    firClass: FirClassLikeSymbol<*>,
    context: CheckerContext,
    isExplicit: Boolean = true,
    collectTypeArguments: Boolean = true,
    visited: MutableSet<Pair<ClassId, Boolean>>,
  ) {
    val classIdAndIsExplicit = firClass.classId to isExplicit
    if (!visited.add(classIdAndIsExplicit)) {
      return
    }

    firClass.sourceElement?.binaryClass()?.let { recordClass(path = it, isExplicit = isExplicit) }

    if (firClass is FirClassSymbol<*>) {
      for (typeRef in firClass.resolvedSuperTypeRefs) {
        recordTypeRef(
          typeRef = typeRef,
          context = context,
          isExplicit = false,
          collectTypeArguments = collectTypeArguments,
          visited = visited,
        )
      }
      if (collectTypeArguments) {
        firClass.typeParameterSymbols
          .asSequence()
          .flatMap { it.resolvedBounds }
          .forEach {
            recordTypeRef(
              typeRef = it,
              context = context,
              isExplicit = isExplicit,
              collectTypeArguments = true,
              visited = visited,
            )
          }
      }
    }
  }

  internal fun recordClass(
    path: String,
    isExplicit: Boolean,
  ) {
    if (isExplicit) {
      explicitClassesCanonicalPaths.add(path)
    } else {
      implicitClassesCanonicalPaths.add(path)
    }

//    if (path.contains(JAR_FILE_SEPARATOR) && !path.contains(javaHome)) {
//      val (jarPath, classPath) = path.split(JAR_FILE_SEPARATOR)
//      // Convert jar files in current directory to relative paths. Remaining absolute are outside
//      // of project and should be ignored
//      val relativizedJarPath = Paths.get(jarPath.replace(rootPath, ""))
//      if (!relativizedJarPath.isAbsolute) {
//        val occurrences =
//          results.computeIfAbsent(relativizedJarPath.toString()) { sortedSetOf<String>() }
//        if (!isJvmClass(classPath)) {
//          occurrences.add(classPath)
//        }
//      }
//    }
  }
}
