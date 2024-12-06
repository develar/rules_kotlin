/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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
@file:JvmName("IOUtils")

package io.bazel.kotlin.builder.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

fun Path.resolveNewDirectories(vararg parts: String): Path? {
  return Files.createDirectories(
    parts.fold(this, Path::resolve),
  )
}

fun verified(file: Path): File {
  return file
    .toFile()
    .also { check(it.exists()) { "file did not exist: $file" } }
}
