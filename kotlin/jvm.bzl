load(
    "//kotlin/internal:opts.bzl",
    _kt_javac_options = "kt_javac_options",
)
load(
    "//kotlin/internal/jvm:jvm.bzl",
    _kt_jvm_binary = "kt_jvm_binary",
    _kt_jvm_import = "kt_jvm_import",
    _kt_jvm_library = "kt_jvm_library",
    _jvm_resources = "jvm_resources",
    _kt_jvm_test = "kt_jvm_test",
)

kt_javac_options = _kt_javac_options
kt_jvm_binary = _kt_jvm_binary
kt_jvm_import = _kt_jvm_import
kt_jvm_library = _kt_jvm_library
jvm_resources = _jvm_resources
kt_jvm_test = _kt_jvm_test
