# All versions for development and release
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")

version = provider(
    fields = {
        "url_templates": "list of string templates with the placeholder {version}",
        "version": "the version in the form \\D+.\\D+.\\D+(.*)",
        "sha256": "sha256 checksum for the version being downloaded.",
        "strip_prefix_template": "string template with the placeholder {version}.",
    },
)

def _use_repository(name, version, rule, **kwargs):
    http_archive_arguments = dict(kwargs)
    http_archive_arguments["sha256"] = version.sha256
    http_archive_arguments["urls"] = [u.format(version = version.version) for u in version.url_templates]
    if (hasattr(version, "strip_prefix_template")):
        http_archive_arguments["strip_prefix"] = version.strip_prefix_template.format(version = version.version)

    maybe(rule, name = name, **http_archive_arguments)

versions = struct(
    RULES_NODEJS_VERSION = "5.5.3",
    RULES_NODEJS_SHA = "f10a3a12894fc3c9bf578ee5a5691769f6805c4be84359681a785a0c12e8d2b6",
    BAZEL_TOOLCHAINS_VERSION = "4.1.0",
    BAZEL_TOOLCHAINS_SHA = "179ec02f809e86abf56356d8898c8bd74069f1bd7c56044050c2cd3d79d0e024",
    # IMPORTANT! rules_kotlin does not use the bazel_skylib unittest in production
    # This means the bazel_skylib_workspace call is skipped, as it only registers the unittest
    # toolchains. However, if a new workspace dependency is introduced, this precondition will fail.
    # Why skip it? Because it would introduce a 3rd function call to rules kotlin setup:
    # 1. Download archive
    # 2. Download dependencies and Configure rules
    # --> 3. Configure dependencies <--
    SKYLIB_VERSION = "1.4.2",
    SKYLIB_SHA = "66ffd9315665bfaafc96b52278f57c7e2dd09f5ede279ea6d39b2be471e7e3aa",
    PROTOBUF_VERSION = "3.11.3",
    PROTOBUF_SHA = "cf754718b0aa945b00550ed7962ddc167167bd922b842199eeb6505e6f344852",
    RULES_JVM_EXTERNAL_TAG = "6.4",
    RULES_JVM_EXTERNAL_SHA = "85776be6d8fe64abf26f463a8e12cd4c15be927348397180a01693610da7ec90",
    IO_BAZEL_STARDOC = version(
        version = "0.5.6",
        sha256 = "dfbc364aaec143df5e6c52faf1f1166775a5b4408243f445f44b661cfdc3134f",
        url_templates = [
            "https://mirror.bazel.build/github.com/bazelbuild/stardoc/releases/download/{version}/stardoc-{version}.tar.gz",
            "https://github.com/bazelbuild/stardoc/releases/download/{version}/stardoc-{version}.tar.gz",
        ],
    ),
    PINTEREST_KTLINT = version(
        version = "1.3.1",
        url_templates = [
            "https://github.com/pinterest/ktlint/releases/download/{version}/ktlint",
        ],
        sha256 = "a9f923be58fbd32670a17f0b729b1df804af882fa57402165741cb26e5440ca1",
    ),
    KOTLIN_CURRENT_COMPILER_RELEASE = version(
        version = "2.1.0",
        url_templates = [
            "https://github.com/JetBrains/kotlin/releases/download/v{version}/kotlin-compiler-{version}.zip",
        ],
        sha256 = "b6698d5728ad8f9edcdd01617d638073191d8a03139cc538a391b4e3759ad297",
    ),
    KSP_CURRENT_COMPILER_PLUGIN_RELEASE = version(
        version = "2.1.0-1.0.28",
        url_templates = [
            "https://github.com/google/ksp/releases/download/{version}/artifacts.zip",
        ],
        sha256 = "fc27b08cadc061a4a989af01cbeccb613feef1995f4aad68f2be0f886a3ee251",
    ),
    # To update: https://github.com/bazelbuild/bazel-toolchains#latest-bazel-and-latest-ubuntu-1604-container
    RBE = struct(
        # This tarball intentionally does not have a SHA256 because the upstream URL can change without notice
        # For more context: https://github.com/bazelbuild/bazel-toolchains/blob/0c1f7c3c5f9e63f1e0ee91738b964937eea2d3e0/WORKSPACE#L28-L32
        URLS = ["https://storage.googleapis.com/rbe-toolchain/bazel-configs/rbe-ubuntu1604/latest/rbe_default.tar"],
    ),
    PKG = version(
        version = "0.7.0",
        url_templates = [
            "https://github.com/bazelbuild/rules_pkg/releases/download/{version}/rules_pkg-{version}.tar.gz",
        ],
        sha256 = "8a298e832762eda1830597d64fe7db58178aa84cd5926d76d5b744d6558941c2",
    ),
    # needed for rules_pkg and java
    RULES_KOTLIN = version(
        version = "2.0.0-jb.6",
        url_templates = [
            "https://github.com/develar/rules_kotlin/releases/download/v{version}/rules_kotlin-v{version}.tar.gz",
        ],
        sha256 = "de24805210baab3acb72e9c6cf9a133bb56e638abb35cd90fdcf4b1bf6f423f7",
    ),
    # needed for rules_pkg and java
    RULES_JAVA = version(
        version = "7.2.0",
        url_templates = [
            "https://github.com/bazelbuild/rules_java/releases/download/{version}/rules_java-{version}.tar.gz",
        ],
        sha256 = "eb7db63ed826567b2ceb1ec53d6b729e01636f72c9f5dfb6d2dfe55ad69d1d2a",
    ),
    RULES_LICENSE = version(
        version = "1.0.0",
        url_templates = [
            "https://mirror.bazel.build/github.com/bazelbuild/rules_license/releases/download/{version}/rules_license-{version}.tar.gz",
            "https://github.com/bazelbuild/rules_license/releases/download/{version}/rules_license-{version}.tar.gz",
        ],
        sha256 = "26d4021f6898e23b82ef953078389dd49ac2b5618ac564ade4ef87cced147b38",
    ),
    RULES_TESTING = version(
        version = "0.5.0",
        url_templates = [
            "https://github.com/bazelbuild/rules_testing/releases/download/v{version}/rules_testing-v{version}.tar.gz",
        ],
        sha256 = "b84ed8546f1969d700ead4546de9f7637e0f058d835e47e865dcbb13c4210aed",
    ),
    KOTLINX_SERIALIZATION_CORE_JVM = version(
        version = "1.7.3",
        url_templates = [
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/{version}/kotlinx-serialization-core-jvm-{version}.jar",
        ],
        sha256 = "f0adde45864144475385cf4aa7e0b7feb27f61fcf9472665ed98cc971b06b1eb",
    ),
    KOTLINX_SERIALIZATION_JSON = version(
        version = "1.7.3",
        url_templates = [
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/{version}/kotlinx-serialization-json-{version}.jar",
        ],
        sha256 = "aa93fa3c96392cb139593134430dc2d51367f59d553e5e3747ebd8007b263f1b",
    ),
    KOTLINX_SERIALIZATION_JSON_JVM = version(
        version = "1.7.3",
        url_templates = [
            "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/{version}/kotlinx-serialization-json-jvm-{version}.jar",
        ],
        sha256 = "b1e9138499ed8d20375edda3f2b1c95f3103a258eff6af9edc5ea07100f2b29c",
    ),
    use_repository = _use_repository,
)
