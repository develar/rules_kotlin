#!/usr/bin/env bash

# add this to MODULE.bazel (assuming that the rules_kotlin project is located in the same directory as your project)
: '
local_path_override(
  module_name = "rules_kotlin",
  path = "../rules_kotlin/bazel-bin/rules_kotlin_release",
)
'

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

bazel build //:rules_kotlin_release || fail "cannot pack release"

# unpack to for repository overriding
ARCHIVE_DIR="bazel-bin/rules_kotlin_release"

echo "Unpack to $ARCHIVE_DIR"
rm -rf "$ARCHIVE_DIR"
mkdir "$ARCHIVE_DIR"
tar -C "$ARCHIVE_DIR" -xzf bazel-bin/rules_kotlin_release.tgz