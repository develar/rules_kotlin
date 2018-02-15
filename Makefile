test.smoke:
	python -B -m unittest discover -s tests/smoke/ -p '*_tests.py'
	bazel test //kotlin/workers:unittests

reformat:
	buildifier -mode=fix -v kotlin/*.bzl
	buildifier -mode=fix -v kotlin/rules/*.bzl

docs.regen:
	bazel build //docs
	unzip -o bazel-bin/docs/docs-skydoc.zip -d docs

docs.preview_local:
	bazel build //docs --define local=1
	unzip -o bazel-bin/docs/docs-skydoc.zip -d /tmp/rules_kotlin
	open /tmp/rules_kotlin/kotlin/kotlin.html
