#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ $# != 1 ]]; then
    printf 'Usage: %s <revision> > ui-kit.tar.gz\n' "$0" >&2
    exit 2
fi

revision=$(git rev-parse --verify --end-of-options "$1^{commit}")
git archive --format=tar.gz --prefix="heterodyne-fm-ui-kit-$revision/" "$revision" -- \
    README.ui-kit.md LICENSE NOTICE CONTRIBUTING.md .gitignore \
    docs/architecture/ui-contract-spec.md docs/adr/0006-project-license.md docs/adr/0010-ui-core-separation.md \
    docs/design/v1/tokens.json docs/design/v1/tokens.md \
    gradlew gradlew.bat gradle/libs.versions.toml gradle/wrapper \
    build-support/ui-compose-standalone ui-contract ui-compose ui-sandbox
