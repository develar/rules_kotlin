/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.builder.utils

import com.google.devtools.build.runfiles.Runfiles
import java.io.FileNotFoundException
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path

private val runfiles by lazy {
  Runfiles.preload().unmapped()
}

/** Utility class for getting runfiles on windows and *nix.  */
object BazelRunFiles {
  /** Resolve as path in FileSystem. */
  @JvmStatic
  fun resolveVerifiedFromProperty(
    fileSystem: FileSystem,
    key: String,
  ): Path {
    val path = System.getProperty(key)
      ?: throw FileNotFoundException("no reference for $key in ${System.getProperties()}")
    val file = fileSystem.getPath(runfiles.rlocation(path))
    require(Files.exists(file)) {
      "$file does not exist in the runfiles!"
    }
    return file
  }
}

/**
 * Resolve a run file
 */
fun resolveVerifiedFromProperty(key: String): Path {
  val path = (System.getProperty(key)
    ?: throw FileNotFoundException("no reference for $key in ${System.getProperties()}"))
  val file = Path.of(runfiles.rlocation(path))
  require(Files.exists(file)) {
    "$file does not exist in the runfiles!"
  }
  return file
}
