#!/usr/bin/env bash
set -euo pipefail

output_dir="${1:?usage: ci/build-release.sh <output-dir>}"
helper="${PROJECT_BUNDLE_HELPER:-/opt/jenkins-scripts/build_legacy_project_bundle.sh}"
flatten="${PROJECT_FLATTEN_HELPER:-/opt/jenkins-scripts/flatten_project_bundle.sh}"
legacy_dir="$(mktemp -d)"
trap 'rm -rf "$legacy_dir"' EXIT
bash "$helper" "AISocialGame" "$legacy_dir"
bash "$flatten" "AISocialGame" "$legacy_dir" "$output_dir"
