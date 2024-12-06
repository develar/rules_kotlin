load("@rules_java//java:defs.bzl", "java_library", "java_proto_library")
load("@rules_proto//proto:defs.bzl", "proto_library")

package(default_visibility = ["//visibility:public"])

alias(
    name = "deps_java_proto",
    actual = "@bazel_tools//src/main/protobuf:deps_java_proto",
)

java_library(
    name = "protobuf",
    exports = [
        ":deps_java_proto",
    ],
)
