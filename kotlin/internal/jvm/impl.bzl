# Copyright 2018 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("@rules_java//java:defs.bzl", "JavaInfo", "JavaPluginInfo", "java_common")
load(
    "//kotlin/internal:defs.bzl",
    "KtCompilerPluginOption",
    "KtPluginConfiguration",
    _KspPluginInfo = "KspPluginInfo",
    _KtCompilerPluginInfo = "KtCompilerPluginInfo",
    _KtJvmInfo = "KtJvmInfo",
    _TOOLCHAIN_TYPE = "TOOLCHAIN_TYPE",
)
load(
    "//kotlin/internal/jvm:compile.bzl",
    "export_only_providers",
    _kt_jvm_produce_jar_actions = "kt_jvm_produce_jar_actions",
)
load(
    "//kotlin/internal/utils:utils.bzl",
    _utils = "utils",
)
load("//third_party:jarjar.bzl", "jarjar_action")

# borrowed from skylib to avoid adding that to the release.
def _is_absolute(path):
    return path.startswith("/") or (len(path) > 2 and path[1] == ":")

def _make_providers(ctx, providers, transitive_files = depset(order = "default")):
    files = [ctx.outputs.jar]
    if providers.java.outputs.jdeps:
        files.append(providers.java.outputs.jdeps)
    return [
        providers.java,
        providers.kt,
        providers.instrumented_files,
        DefaultInfo(
            files = depset(files),
            runfiles = ctx.runfiles(
                # explicitly include data files, otherwise they appear to be missing
                files = ctx.files.data,
                transitive_files = transitive_files,
                # continue to use collect_default until proper transitive data collecting is
                # implmented.
                collect_default = True,
            ),
        ),
    ]

def _write_launcher_action(ctx, rjars, main_class, jvm_flags):
    """Macro that writes out a launcher script shell script.
      Args:
        rjars: All of the runtime jars required to launch this java target.
        main_class: the main class to launch.
        jvm_flags: The flags that should be passed to the jvm.
        args: Args that should be passed to the Binary.
    """
    jvm_flags = " ".join([ctx.expand_location(f, ctx.attr.data) for f in jvm_flags])
    template = ctx.attr._java_stub_template.files.to_list()[0]

    java_runtime = ctx.toolchains["@bazel_tools//tools/jdk:runtime_toolchain_type"].java_runtime
    java_bin_path = java_runtime.java_executable_exec_path

    # Following https://github.com/bazelbuild/bazel/blob/6d5b084025a26f2f6d5041f7a9e8d302c590bc80/src/main/starlark/builtins_bzl/bazel/java/bazel_java_binary.bzl#L66-L67
    # Enable the security manager past deprecation.
    # On bazel 6, this check isn't possible...
    if getattr(java_runtime, "version", 0) >= 17:
        jvm_flags = jvm_flags + " -Djava.security.manager=allow"

    classpath = ctx.configuration.host_path_separator.join(
        ["${RUNPATH}%s" % (j.short_path) for j in rjars.to_list()],
    )

    ctx.actions.expand_template(
        template = template,
        output = ctx.outputs.executable,
        substitutions = {
            "%classpath%": classpath,
            "%runfiles_manifest_only%": "",
            "%java_start_class%": main_class,
            "%javabin%": "JAVABIN=" + java_bin_path,
            "%jvm_flags%": jvm_flags,
            "%set_jacoco_metadata%": "",
            "%set_jacoco_main_class%": "",
            "%set_jacoco_java_runfiles_root%": "",
            "%set_java_coverage_new_implementation%": """export JAVA_COVERAGE_NEW_IMPLEMENTATION=NO""",
            "%workspace_prefix%": ctx.workspace_name + "/",
            "%test_runtime_classpath_file%": "export TEST_RUNTIME_CLASSPATH_FILE=${JAVA_RUNFILES}",
            "%needs_runfiles%": "0" if _is_absolute(java_bin_path) else "1",
        },
        is_executable = True,
    )
    return []

def kt_jvm_import_impl(ctx):
    class_jar = ctx.file.jar
    source_jar = ctx.file.srcjar
    return [
        DefaultInfo(
            files = depset(direct = [class_jar]),
            runfiles = ctx.runfiles(
                # Append class jar with the optional sources jar
                files = [class_jar] + [source_jar] if source_jar else [],
            ).merge_all([d[DefaultInfo].default_runfiles for d in ctx.attr.deps]),
        ),
        JavaInfo(
            output_jar = class_jar,
            compile_jar = class_jar,
            source_jar = source_jar,
            runtime_deps = [dep[JavaInfo] for dep in ctx.attr.runtime_deps if JavaInfo in dep],
            deps = [dep[JavaInfo] for dep in ctx.attr.deps if JavaInfo in dep],
            exports = [d[JavaInfo] for d in getattr(ctx.attr, "exports", [])],
            neverlink = getattr(ctx.attr, "neverlink", False),
        ),
        _KtJvmInfo(
             module_name = _utils.derive_module_name(ctx),
             module_jars = [],
             exported_compiler_plugins = depset(getattr(ctx.attr, "exported_compiler_plugins", [])),
             outputs = struct(
                 jars = [struct(class_jar = ctx.file.jar, source_jar = source_jar, ijar = None)],
             ),
         ),
    ]

def kt_jvm_library_impl(ctx):
    if ctx.attr.neverlink and ctx.attr.runtime_deps:
        fail("runtime_deps and neverlink is nonsensical.", attr = "runtime_deps")

    return _make_providers(
        ctx,
        _kt_jvm_produce_jar_actions(ctx, "kt_jvm_library") if ctx.attr.srcs else export_only_providers(
            ctx = ctx,
            actions = ctx.actions,
            outputs = ctx.outputs,
            attr = ctx.attr,
        ),
    )

def kt_jvm_binary_impl(ctx):
    providers = _kt_jvm_produce_jar_actions(ctx, "kt_jvm_binary")
    jvm_flags = []
    if hasattr(ctx.fragments.java, "default_jvm_opts"):
        jvm_flags = ctx.fragments.java.default_jvm_opts
    jvm_flags.extend(ctx.attr.jvm_flags)
    _write_launcher_action(
        ctx,
        providers.java.transitive_runtime_jars,
        ctx.attr.main_class,
        jvm_flags,
    )
    if len(ctx.attr.srcs) == 0 and len(ctx.attr.deps) > 0:
        fail("deps without srcs is invalid. To add runtime classpath and resources, use runtime_deps.", attr = "deps")

    return _make_providers(
        ctx,
        providers,
        depset(
            order = "default",
            transitive = [providers.java.transitive_runtime_jars],
            direct = ctx.files._java_runtime,
        ),
    )

_SPLIT_STRINGS = [
    "src/test/java/",
    "src/test/kotlin/",
    "javatests/",
    "kotlin/",
    "java/",
    "test/",
]

def kt_jvm_junit_test_impl(ctx):
    providers = _kt_jvm_produce_jar_actions(ctx, "kt_jvm_test")
    runtime_jars = depset(ctx.files._bazel_test_runner, transitive = [providers.java.transitive_runtime_jars])

    coverage_runfiles = []
    test_class = ctx.attr.test_class

    # If no test_class, do a best-effort attempt to infer one.
    if not bool(ctx.attr.test_class):
        for file in ctx.files.srcs:
            package_relative_path = file.path.replace(ctx.label.package + "/", "")
            if package_relative_path.split(".")[0] == ctx.attr.name:
                for splitter in _SPLIT_STRINGS:
                    elements = file.short_path.split(splitter, 1)
                    if len(elements) == 2:
                        test_class = elements[1].split(".")[0].replace("/", ".")
                        break

    jvm_flags = []
    if hasattr(ctx.fragments.java, "default_jvm_opts"):
        jvm_flags = ctx.fragments.java.default_jvm_opts

    jvm_flags.extend(ctx.attr.jvm_flags)
    coverage_metadata = _write_launcher_action(
        ctx,
        runtime_jars,
        main_class = ctx.attr.main_class,
        jvm_flags = [
            "-ea",
            "-Dbazel.test_suite=%s" % test_class,
        ] + jvm_flags,
    )

    # adds common test variables, including TEST_WORKSPACE
    return _make_providers(
        ctx,
        providers,
        depset(
            order = "default",
            transitive = [runtime_jars, depset(coverage_runfiles), depset(coverage_metadata)],
            direct = ctx.files._java_runtime,
        )
    ) + [testing.TestEnvironment(environment = ctx.attr.env)]

_KtCompilerPluginClasspathInfo = provider(
    fields = {
        "reshaded_infos": "list reshaded JavaInfos of a compiler library",
        "infos": "list JavaInfos of a compiler library",
    },
)

def kt_compiler_deps_aspect_impl(target, ctx):
    """
    Collects and reshades (if necessary) all jars in the plugin transitive closure.

    Args:
        target: Target of the rule being inspected
        ctx: aspect ctx
    Returns:
        list of _KtCompilerPluginClasspathInfo
    """
    transitive_infos = [
        t[_KtCompilerPluginClasspathInfo]
        for d in ["deps", "runtime_deps", "exports"]
        for t in getattr(ctx.rule.attr, d, [])
        if _KtCompilerPluginClasspathInfo in t
    ]
    reshaded_infos = []
    infos = [
        i
        for t in transitive_infos
        for i in t.infos
    ]
    if JavaInfo in target:
        ji = target[JavaInfo]
        infos.append(ji)
        reshaded_infos.append(
            _reshade_embedded_kotlinc_jars(
                target = target,
                ctx = ctx,
                jars = ji.runtime_output_jars,
                deps = [
                    i
                    for t in transitive_infos
                    for i in t.reshaded_infos
                ],
            ),
        )

    return [
        _KtCompilerPluginClasspathInfo(
            reshaded_infos = reshaded_infos,
            infos = [java_common.merge(infos)],
        ),
    ]

def _reshade_embedded_kotlinc_jars(target, ctx, jars, deps):
    reshaded = [
        jarjar_action(
            actions = ctx.actions,
            jarjar = ctx.executable._jarjar,
            rules = ctx.file._kotlin_compiler_reshade_rules,
            input = jar,
            output = ctx.actions.declare_file(
                "%s_reshaded_%s" % (target.label.name, jar.basename),
            ),
        )
        for jar in jars
    ]

    # JavaInfo only takes a single jar, so create many and merge them.
    return java_common.merge(
        [
            JavaInfo(output_jar = jar, compile_jar = jar, deps = deps)
            for jar in reshaded
        ],
    )

def _resolve_plugin_options(id, string_list_dict, expand_location):
    """
    Resolves plugin options from a string dict to a dict of strings.

    Args:
        id: the plugin id
        string_list_dict: a dict of list[string].
    Returns:
        a dict of strings
    """
    options = []
    for (k, vs) in string_list_dict.items():
        for v in vs:
            if "=" in k:
                fail("kotlin compiler option keys cannot contain the = symbol")
            value = k + "=" + expand_location(v) if v else k
            options.append(KtCompilerPluginOption(id = id, value = value))
    return options

# This is naive reference implementation for resolving configurations.
# A more complicated plugin will need to provide its own implementation.
def _resolve_plugin_cfg(info, options, deps, expand_location):
    ji = java_common.merge([dep[JavaInfo] for dep in deps if JavaInfo in dep])
    classpath = depset(ji.runtime_output_jars, transitive = [ji.transitive_runtime_jars])
    return KtPluginConfiguration(
        id = info.id,
        options = _resolve_plugin_options(info.id, options, expand_location),
        classpath = classpath,
        data = depset(),
    )

def kt_compiler_plugin_impl(ctx):
    plugin_id = ctx.attr.id

    deps = ctx.attr.deps
    info = None
    if ctx.attr.target_embedded_compiler:
        info = java_common.merge([
            i
            for d in deps
            for i in d[_KtCompilerPluginClasspathInfo].reshaded_infos
        ])
    else:
        info = java_common.merge([
            i
            for d in deps
            for i in d[_KtCompilerPluginClasspathInfo].infos
        ])

    classpath = depset(info.runtime_output_jars, transitive = [info.transitive_runtime_jars])

    # TODO(1035): Migrate kt_compiler_plugin.options to string_list_dict
    options = _resolve_plugin_options(plugin_id, {k: [v] for (k, v) in ctx.attr.options.items()}, ctx.expand_location)

    return [
        DefaultInfo(files = classpath),
        _KtCompilerPluginInfo(
            id = plugin_id,
            classpath = classpath,
            options = options,
            stubs = ctx.attr.stubs_phase,
            compile = ctx.attr.compile_phase,
            resolve_cfg = _resolve_plugin_cfg,
        ),
    ]

def kt_plugin_cfg_impl(ctx):
    plugin = ctx.attr.plugin[_KtCompilerPluginInfo]
    return plugin.resolve_cfg(plugin, ctx.attr.options, ctx.attr.deps, ctx.expand_location)

def kt_ksp_plugin_impl(ctx):
    deps = ctx.attr.deps
    if ctx.attr.target_embedded_compiler:
        info = java_common.merge([
            i
            for d in deps
            for i in d[_KtCompilerPluginClasspathInfo].reshaded_infos
        ])
    else:
        info = java_common.merge([dep[JavaInfo] for dep in deps])

    classpath = depset(info.runtime_output_jars, transitive = [info.transitive_runtime_jars])

    return [
        DefaultInfo(files = classpath),
        _KspPluginInfo(
            plugins = [
                JavaPluginInfo(
                    runtime_deps = [
                        info,
                    ],
                    processor_class = ctx.attr.processor_class,
                    # rules_kotlin doesn't support stripping non-api generating annotation
                    # processors out of the public ABI.
                    generates_api = True,
                ),
            ],
            generates_java = ctx.attr.generates_java,
        ),
    ]
