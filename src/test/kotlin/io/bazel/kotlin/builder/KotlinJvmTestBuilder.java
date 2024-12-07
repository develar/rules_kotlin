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
package io.bazel.kotlin.builder;

import io.bazel.kotlin.builder.Deps.AnnotationProcessor;
import io.bazel.kotlin.builder.Deps.Dep;
import io.bazel.kotlin.builder.KotlinJvmTestBuilder.JvmCompilationTaskBuilder.DirectoriesBuilder;
import io.bazel.kotlin.builder.tasks.jvm.KotlinJvmTaskExecutor;
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext;
import io.bazel.kotlin.builder.toolchain.KotlinToolchain;
import io.bazel.kotlin.model.Directories;
import io.bazel.kotlin.model.Inputs;
import io.bazel.kotlin.model.JvmCompilationTask;
import io.bazel.kotlin.model.Outputs;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class KotlinJvmTestBuilder extends KotlinAbstractTestBuilder<JvmCompilationTask> {
  private static JvmCompilationTaskBuilder taskBuilder = new JvmCompilationTaskBuilder();
  private static final EnumSet<DirectoryType> ALL_DIRECTORY_TYPES =
    EnumSet.of(
      DirectoryType.SOURCES,
      DirectoryType.CLASSES,
      DirectoryType.JAVA_CLASSES,
      DirectoryType.ABI_CLASSES,
      DirectoryType.SOURCE_GEN,
      DirectoryType.JAVA_SOURCE_GEN,
      DirectoryType.GENERATED_CLASSES,
      DirectoryType.TEMP,
      DirectoryType.COVERAGE_METADATA);
  @SuppressWarnings({"unused", "WeakerAccess"})
  public static Dep
    KOTLIN_ANNOTATIONS = Dep.fromLabel("//kotlin/compiler:annotations"),
    KOTLIN_STDLIB = Dep.fromLabel("//kotlin/compiler:kotlin-stdlib"),
    KOTLIN_STDLIB_JDK7 = Dep.fromLabel("//kotlin/compiler:kotlin-stdlib-jdk7"),
    KOTLIN_STDLIB_JDK8 = Dep.fromLabel("//kotlin/compiler:kotlin-stdlib-jdk8"),
    JVM_ABI_GEN = Dep.fromLabel("//kotlin/compiler:jvm-abi-gen");
  private static KotlinBuilderTestComponent component;
  private final TaskBuilder taskBuilderInstance = new TaskBuilder();

  private static KotlinBuilderTestComponent component() {
    if (component == null) {
      KotlinToolchain toolchain = toolchainForTest();
      KotlinJvmTaskExecutor executor = new KotlinJvmTaskExecutor(toolchain);
      component = new KotlinBuilderTestComponent() {
        @Override
        public KotlinJvmTaskExecutor jvmTaskExecutor() {
          return executor;
        }
      };
    }
    return component;
  }

  @Override
  void setupForNext(CompilationTaskInfoBuilder taskInfo) {
    taskBuilder = new JvmCompilationTaskBuilder();
    taskBuilder.setInfo(taskInfo);

    DirectoryType.createAll(instanceRoot(), ALL_DIRECTORY_TYPES);

    taskBuilder.setInputs(
      new InputsBuilder()
        .setClasspath(List.of(Path.of(KOTLIN_STDLIB.singleCompileJar()), Path.of(KOTLIN_STDLIB_JDK7.singleCompileJar()), Path.of(KOTLIN_STDLIB_JDK8.singleCompileJar())))
    );

    taskBuilder.setDirectories(
      new DirectoriesBuilder()
        .setClasses(directory(DirectoryType.CLASSES).toAbsolutePath().toString())
        .setJavaClasses(directory(DirectoryType.JAVA_CLASSES).toAbsolutePath().toString())
        .setAbiClasses(directory(DirectoryType.ABI_CLASSES).toAbsolutePath().toString())
        .setGeneratedSources(directory(DirectoryType.SOURCE_GEN).toAbsolutePath().toString())
        .setGeneratedJavaSources(directory(DirectoryType.JAVA_SOURCE_GEN).toAbsolutePath().toString())
        .setTemp(directory(DirectoryType.TEMP).toAbsolutePath().toString())
        .setGeneratedClasses(directory(DirectoryType.GENERATED_CLASSES).toAbsolutePath().toString())
        .setCoverageMetadataClasses(directory(DirectoryType.COVERAGE_METADATA).toAbsolutePath().toString())
        .build()
    );
  }

  @Override
  public JvmCompilationTask buildTask() {
    return taskBuilder.build();
  }

  @SafeVarargs
  public final Dep runCompileTask(Consumer<TaskBuilder>... setup) {
    return executeTask(component().jvmTaskExecutor()::execute, setup);
  }

  private Dep executeTask(
    BiConsumer<CompilationTaskContext, JvmCompilationTask> executor,
    Consumer<TaskBuilder>[] setup) {
    resetForNext();
    Stream.of(setup).forEach(it -> it.accept(taskBuilderInstance));
    return runCompileTask(
      (taskContext, task) -> {
        executor.accept(taskContext, task);

        Outputs outputs = task.outputs;
        assertFilesExist(
          Stream.of(
              outputs.abiJar,
              outputs.jar,
              outputs.jdeps,
              outputs.srcjar)
            .filter(p -> p != null)
            .map(p -> p.toString())
            .toArray(String[]::new)
        );

        return Dep.builder()
          .label(taskBuilder.info.label)
          .compileJars(List.of(
            (outputs.abiJar == null ? outputs.jar : outputs.abiJar).toString()
          ))
          .jdeps(outputs.jdeps.toString())
          .runtimeDeps(taskBuilder.inputs.classpath.stream().map(Path::toString).collect(Collectors.toList()))
          .sourceJar(taskBuilder.outputs.srcjar.toString())
          .build();
      });
  }

  public void tearDown() {
    component = null;
  }

  public static class InputsBuilder {
    private List<Path> classpath = new ArrayList<>();
    private List<String> directDependencies = new ArrayList<>();
    private List<String> kotlinSources = new ArrayList<>();
    private List<String> javaSources = new ArrayList<>();
    private List<String> sourceJars = new ArrayList<>();
    List<String> processors = new ArrayList<>();
    private List<String> processorpaths = new ArrayList<>();
    private List<String> stubsPluginOptions = new ArrayList<>();
    private List<String> stubsPlugins = new ArrayList<>();
    private List<String> stubsPluginClasspath = new ArrayList<>();
    private List<String> compilerPluginOptions = new ArrayList<>();
    private List<String> compilerPlugins = new ArrayList<>();
    private List<Path> compilerPluginClasspath = new ArrayList<>();
    private List<String> javacFlags = new ArrayList<>();
    private List<String> depsArtifacts = new ArrayList<>();

    public InputsBuilder setClasspath(List<Path> classpath) {
      this.classpath = classpath;
      return this;
    }

    public InputsBuilder setDirectDependencies(List<String> directDependencies) {
      this.directDependencies = directDependencies;
      return this;
    }

    public InputsBuilder setKotlinSources(List<String> kotlinSources) {
      this.kotlinSources = kotlinSources;
      return this;
    }

    public InputsBuilder setJavaSources(List<String> javaSources) {
      this.javaSources = javaSources;
      return this;
    }

    public InputsBuilder setSourceJars(List<String> sourceJars) {
      this.sourceJars = sourceJars;
      return this;
    }

    public InputsBuilder setProcessors(List<String> processors) {
      this.processors = processors;
      return this;
    }

    public InputsBuilder setProcessorpaths(List<String> processorpaths) {
      this.processorpaths = processorpaths;
      return this;
    }

    public InputsBuilder setStubsPluginOptions(List<String> stubsPluginOptions) {
      this.stubsPluginOptions = stubsPluginOptions;
      return this;
    }

    public InputsBuilder setStubsPlugins(List<String> stubsPlugins) {
      this.stubsPlugins = stubsPlugins;
      return this;
    }

    public InputsBuilder setStubsPluginClasspath(List<String> stubsPluginClasspath) {
      this.stubsPluginClasspath = stubsPluginClasspath;
      return this;
    }

    public InputsBuilder setCompilerPluginOptions(List<String> compilerPluginOptions) {
      this.compilerPluginOptions = compilerPluginOptions;
      return this;
    }

    public InputsBuilder setCompilerPlugins(List<String> compilerPlugins) {
      this.compilerPlugins = compilerPlugins;
      return this;
    }

    public InputsBuilder setCompilerPluginClasspath(List<Path> compilerPluginClasspath) {
      this.compilerPluginClasspath = compilerPluginClasspath;
      return this;
    }

    public InputsBuilder setJavacFlags(List<String> javacFlags) {
      this.javacFlags = javacFlags;
      return this;
    }

    public InputsBuilder setDepsArtifacts(List<String> depsArtifacts) {
      this.depsArtifacts = depsArtifacts;
      return this;
    }

    public Inputs build() {
      return new Inputs(
        classpath,
        directDependencies,
        kotlinSources,
        javaSources,
        processors,
        processorpaths,
        stubsPluginOptions,
        stubsPlugins,
        stubsPluginClasspath,
        compilerPluginOptions,
        compilerPlugins,
        compilerPluginClasspath,
        javacFlags,
        depsArtifacts
      );
    }
  }

  public class TaskBuilder {
    TaskBuilder() {
    }

    public void setLabel(String label) {
      taskBuilder.info.setLabel(label);
    }

    public void addSource(String filename, String... lines) {
      String pathAsString = writeSourceFile(filename, lines).toString();
      if (pathAsString.endsWith(".kt")) {
        taskBuilder.inputs.kotlinSources.add(pathAsString);
      }
      else if (pathAsString.endsWith(".java")) {
        taskBuilder.inputs.javaSources.add(pathAsString);
      }
      else {
        throw new RuntimeException("unhandled file type: " + pathAsString);
      }
    }

    public TaskBuilder compileJava() {
      return this;
    }

    public TaskBuilder compileKotlin() {
      taskBuilder.info.debug.add("trace");
      taskBuilder.info.debug.add("timings");
      taskBuilder.setCompileKotlin(true);
      return this;
    }

    public TaskBuilder coverage() {
      taskBuilder.setInstrumentCoverage(true);
      return this;
    }

    public void addAnnotationProcessors(AnnotationProcessor... annotationProcessors) {
      if (!taskBuilder.inputs.processors.isEmpty()) {
        throw new IllegalStateException("processors already set");
      }
      Set<String> processorClasses = new HashSet<>();
      taskBuilder
        .inputs
        .processorpaths
        .addAll(
          Stream.of(annotationProcessors)
            .peek(it -> processorClasses.add(it.processClass()))
            .flatMap(it -> it.processorPath().stream())
            .distinct()
            .toList());
      taskBuilder.inputs.processors = new ArrayList<>(processorClasses);
    }

    public void addDirectDependencies(Dep... dependencies) {
      Dep.classpathOf(dependencies).forEach(dependency -> {
        taskBuilder.inputs.classpath.add(Path.of(dependency));
        taskBuilder.inputs.directDependencies.add(dependency);
      });
    }

    public void addTransitiveDependencies(Dep... dependencies) {
      Dep.classpathOf(dependencies).forEach(dependency -> {
        taskBuilder.inputs.classpath.add(Path.of(dependency));
      });
    }

    public TaskBuilder outputSrcJar() {
      taskBuilder.outputs
        .setSrcjar(instanceRoot().resolve("jar_file-sources.jar").toAbsolutePath());
      return this;
    }

    public TaskBuilder outputJar() {
      taskBuilder.outputs
        .setJar(instanceRoot().resolve("jar_file.jar").toAbsolutePath());
      return this;
    }

    public TaskBuilder outputJdeps() {
      taskBuilder.outputs
        .setJdeps(instanceRoot().resolve("jdeps_file.jdeps").toAbsolutePath());
      return this;
    }

    public TaskBuilder kotlinStrictDeps(String level) {
      taskBuilder.info.setStrictKotlinDeps(level);
      return this;
    }

    public TaskBuilder outputAbiJar() {
      taskBuilder.outputs
        .setAbijar(instanceRoot().resolve("abi.jar").toAbsolutePath());
      return this;
    }

    public TaskBuilder generatedSourceJar() {
      taskBuilder.outputs
        .setGeneratedJavaSrcJar(instanceRoot().resolve("gen-src.jar").toAbsolutePath().toString());
      return this;
    }

    public TaskBuilder incrementalData() {
      taskBuilder.outputs
        .setGeneratedClassJar(instanceRoot().resolve("incremental.jar").toAbsolutePath().toString());
      return this;
    }

    public TaskBuilder useK2() {
      taskBuilder.info.toolchainInfo.languageVersion = "2.0";
      return this;
    }
  }

  public static class JvmCompilationTaskBuilder {
    private CompilationTaskInfoBuilder info;
    private Directories directories;
    private OutputsBuilder outputs;
    private InputsBuilder inputs;
    private boolean compileKotlin;
    private boolean instrumentCoverage;

    public JvmCompilationTaskBuilder setInfo(CompilationTaskInfoBuilder info) {
      this.info = info;
      return this;
    }

    public JvmCompilationTaskBuilder setDirectories(Directories directories) {
      this.directories = directories;
      return this;
    }

    public JvmCompilationTaskBuilder setOutputs(OutputsBuilder outputs) {
      this.outputs = outputs;
      return this;
    }

    public JvmCompilationTaskBuilder setInputs(InputsBuilder inputs) {
      this.inputs = inputs;
      return this;
    }

    public JvmCompilationTaskBuilder setCompileKotlin(boolean compileKotlin) {
      this.compileKotlin = compileKotlin;
      return this;
    }

    public JvmCompilationTaskBuilder setInstrumentCoverage(boolean instrumentCoverage) {
      this.instrumentCoverage = instrumentCoverage;
      return this;
    }

    public JvmCompilationTask build() {
      return new JvmCompilationTask(
        "1.8",
        info.build(),
        directories,
        outputs.build(),
        inputs.build(),
        compileKotlin,
        instrumentCoverage,
        List.of()
      );
    }

    public static class DirectoriesBuilder {
      private String classes;
      private String generatedClasses;
      private String generatedSources;
      private String temp;
      private String generatedStubClasses;
      private String abiClasses;
      private String generatedJavaSources;
      private String javaClasses;
      private String coverageMetadataClasses;

      public DirectoriesBuilder setClasses(String classes) {
        this.classes = classes;
        return this;
      }

      public DirectoriesBuilder setGeneratedClasses(String generatedClasses) {
        this.generatedClasses = generatedClasses;
        return this;
      }

      public DirectoriesBuilder setGeneratedSources(String generatedSources) {
        this.generatedSources = generatedSources;
        return this;
      }

      public DirectoriesBuilder setTemp(String temp) {
        this.temp = temp;
        return this;
      }

      public DirectoriesBuilder setGeneratedStubClasses(String generatedStubClasses) {
        this.generatedStubClasses = generatedStubClasses;
        return this;
      }

      public DirectoriesBuilder setAbiClasses(String abiClasses) {
        this.abiClasses = abiClasses;
        return this;
      }

      public DirectoriesBuilder setGeneratedJavaSources(String generatedJavaSources) {
        this.generatedJavaSources = generatedJavaSources;
        return this;
      }

      public DirectoriesBuilder setJavaClasses(String javaClasses) {
        this.javaClasses = javaClasses;
        return this;
      }

      public DirectoriesBuilder setCoverageMetadataClasses(String coverageMetadataClasses) {
        this.coverageMetadataClasses = coverageMetadataClasses;
        return this;
      }

      public Directories build() {
        return new Directories(
          Path.of(classes),
          Path.of(generatedClasses),
          Path.of(generatedSources),
          Path.of(temp),
          Path.of(temp),
          Path.of(abiClasses),
          Path.of(javaClasses),
          Path.of(coverageMetadataClasses)
        );
      }
    }

    public static class OutputsBuilder {
      private Path jar;
      private Path jdeps;
      private Path srcjar;
      private Path abijar;
      private String generatedJavaSrcJar;
      private String generatedClassJar;
      private Path generatedKspSrcJar;

      public OutputsBuilder setJar(Path jar) {
        this.jar = jar;
        return this;
      }

      public OutputsBuilder setJdeps(Path jdeps) {
        this.jdeps = jdeps;
        return this;
      }

      public OutputsBuilder setSrcjar(Path srcjar) {
        this.srcjar = srcjar;
        return this;
      }

      public OutputsBuilder setAbijar(Path abijar) {
        this.abijar = abijar;
        return this;
      }

      public OutputsBuilder setGeneratedJavaSrcJar(String generatedJavaSrcJar) {
        this.generatedJavaSrcJar = generatedJavaSrcJar;
        return this;
      }

      public OutputsBuilder setGeneratedClassJar(String generatedClassJar) {
        this.generatedClassJar = generatedClassJar;
        return this;
      }

      public OutputsBuilder setGeneratedKspSrcJar(Path generatedKspSrcJar) {
        this.generatedKspSrcJar = generatedKspSrcJar;
        return this;
      }

      public Outputs build() {
        return new Outputs(
          jar,
          jdeps,
          srcjar,
          abijar,
          generatedJavaSrcJar,
          generatedClassJar,
          generatedKspSrcJar
        );
      }
    }
  }
}
