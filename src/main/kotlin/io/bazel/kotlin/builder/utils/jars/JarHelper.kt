// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Copied from bazel core and there is some code in other branches which will use the some of the unused elements. Fix
// this later on.
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.bazel.kotlin.builder.utils.jars

import io.bazel.kotlin.builder.utils.jars.JarHelper.DEFAULT_TIMESTAMP
import io.bazel.kotlin.builder.utils.jars.JarHelper.MINIMUM_TIMESTAMP_INCREMENT
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.CRC32

@JvmField
internal val EMPTY_BYTEARRAY = ByteArray(0)

/**
 * A simple helper class for creating Jar files. All Jar entries are sorted alphabetically. Allows
 * normalization of Jar entries by setting the timestamp of non-.class files to the DOS epoch.
 * Timestamps of .class files are set to the DOS epoch + 2 seconds (The zip timestamp granularity)
 * Adjusting the timestamp for .class files is necessary since otherwise javac will recompile java
 * files if both the java file and its .class file are present.
 */
internal object JarHelper {
  const val MANIFEST_NAME = JarFile.MANIFEST_NAME
  const val SERVICES_DIR = "META-INF/services/"

  // Normalized timestamp for zip entries
  // We do not include the system's default timezone and locale and additionally avoid the unix epoch
  // to ensure Java's zip implementation does not add the System's timezone into the extra field of the zip entry
  val DEFAULT_TIMESTAMP = GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).getTimeInMillis()

  // ZIP timestamps have a resolution of 2 seconds.
  // see http://www.info-zip.org/FAQ.html#limits
  const val MINIMUM_TIMESTAMP_INCREMENT = 2000L
}

/**
 * Copies a single entry into the jar. This variant differs from the other [writeEntry] in two ways. Firstly the
 * jar contents are already loaded in memory and Secondly the [name] and [path] entries don't necessarily have a
 * correspondence.
 *
 * @param data if this is an empty array, then the entry is a directory.
 */
internal fun writeEntry(
  output: JarOutputStream,
  name: String,
  data: ByteArray,
) {
  val outEntry = JarEntry(name)
  outEntry.time = if (name.endsWith(".class")) {
    DEFAULT_TIMESTAMP + MINIMUM_TIMESTAMP_INCREMENT
  } else {
    DEFAULT_TIMESTAMP
  }
  outEntry.size = data.size.toLong()

  outEntry.method = JarEntry.STORED
  if (data.isEmpty()) {
    outEntry.crc = 0
    output.putNextEntry(outEntry)
  } else {
    val crc = CRC32()
    crc.update(data)
    outEntry.crc = crc.value
    output.putNextEntry(outEntry)
    output.write(data)
  }
  output.closeEntry()
}

/**
 * Copies file or directory entries from the file system into the jar. Directory entries will be
 * detected and their names automatically '/' suffixed.
 */
@Throws(IOException::class)
internal fun writeEntry(
  output: JarOutputStream,
  name: String,
  path: Path,
  size: Long,
) {
  // Create a new entry
  val outEntry = JarEntry(name)
  outEntry.time = if (name.endsWith(".class")) {
    DEFAULT_TIMESTAMP + MINIMUM_TIMESTAMP_INCREMENT
  } else {
    DEFAULT_TIMESTAMP
  }
  if (size <= 0L) {
    outEntry.size = 0
    outEntry.method = JarEntry.STORED
    outEntry.crc = 0
    output.putNextEntry(outEntry)
  } else {
    outEntry.size = size
    outEntry.method = JarEntry.STORED
    // ZipFile requires us to calculate the CRC-32 for any STORED entry.
    // It would be nicer to do this via DigestInputStream, but
    // the architecture of ZipOutputStream requires us to know the CRC-32
    // before we write the data to the stream.
    val bytes = Files.readAllBytes(path)
    val crc = CRC32()
    crc.update(bytes)
    outEntry.crc = crc.value
    output.putNextEntry(outEntry)
    output.write(bytes)
  }
  output.closeEntry()
}

@Throws(IOException::class)
internal fun writeDirEntry(
  output: JarOutputStream,
  name: String,
) {
  val outEntry = JarEntry(name)
  outEntry.time = DEFAULT_TIMESTAMP
  outEntry.size = 0
  outEntry.method = JarEntry.STORED
  outEntry.crc = 0
  output.putNextEntry(outEntry)
  output.closeEntry()
}
