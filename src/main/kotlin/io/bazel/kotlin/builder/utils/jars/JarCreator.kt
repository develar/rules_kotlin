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
package io.bazel.kotlin.builder.utils.jars

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * A class for creating Jar files. Allows normalization of Jar entries by setting their timestamp to
 * the DOS epoch. All Jar entries are sorted alphabetically.
 */
@Suppress("unused")
class JarCreator(
  path: Path,
  private val targetLabel: String,
  private val injectingRuleKind: String,
) : AutoCloseable {
  // map from Jar entry names to files
  private val jarEntries = HashMap<String, Path>()
  private var mainClass: String? = null

  private val output = JarOutputStream(BufferedOutputStream(Files.newOutputStream(path)))

  /**
   * Adds the contents of a directory to the Jar file. All files below this directory will be added
   * to the Jar file using the name relative to the directory as the name for the Jar entry.
   *
   * @param startDir the directory to add to the jar
   */
  fun addDirectory(startDir: Path) {
    val localPrefixLength = startDir.toString().length + 1
    val dirCandidates = ArrayDeque<Path>()
    dirCandidates.add(startDir)
    val tempList = ArrayList<Path>()
    while (true) {
      val dir = dirCandidates.pollFirst() ?: break
      tempList.clear()
      val dirStream = try {
        Files.newDirectoryStream(dir)
      } catch (_: NoSuchFileException) {
        continue
      }

      dirStream.use {
        tempList.addAll(it)
      }

      tempList.sort()
      for (file in tempList) {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        var key = file.toString().substring(localPrefixLength).replace(File.separatorChar, '/')
        if (attributes.isDirectory) {
          dirCandidates.add(file)
          key += "/"

          if (jarEntries.put(key, file) == null) {
            writeDirEntry(output = output, name = key)
          }
        } else
          if (jarEntries.put(key, file) == null && key != JarHelper.MANIFEST_NAME) {
            writeEntry(output = output, name = key, path = file, size = attributes.size())
          }
      }
    }
  }

  @Throws(IOException::class)
  private fun manifestContentImpl(existingFile: Path?): ByteArray {
    val manifest = if (existingFile == null) {
      Manifest()
    } else {
      Files.newInputStream(existingFile).use { Manifest(it) }
    }

    var m = manifest
    m.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
    m.mainAttributes.putIfAbsent(Attributes.Name("Created-By"), "io.bazel.rules.kotlin")

    val attributes = m.mainAttributes

    if (mainClass != null) {
      attributes[Attributes.Name.MAIN_CLASS] = mainClass
    }
    attributes[JarOwner.TARGET_LABEL] = targetLabel
    attributes[JarOwner.INJECTING_RULE_KIND] = injectingRuleKind

    val out = ByteArrayOutputStream()
    manifest.write(out)
    return out.toByteArray()
  }

  override fun close() {
    try {
      // create the manifest entry in the Jar file
      val content = manifestContentImpl(jarEntries.remove(JarHelper.MANIFEST_NAME))
      val entry = JarEntry(JarHelper.MANIFEST_NAME)
      entry.time = JarHelper.DEFAULT_TIMESTAMP
      entry.size = content.size.toLong()
      entry.method = ZipEntry.STORED
      val crc = CRC32()
      crc.update(content)
      entry.crc = crc.value
      output.putNextEntry(entry)
      output.write(content)
      output.closeEntry()
    } finally {
      output.close()
    }
  }
}
