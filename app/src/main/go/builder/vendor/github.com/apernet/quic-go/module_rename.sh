#!/usr/bin/env sh
# Rename the Go module path between upstream quic-go and the apernet fork.
#
#   ./module_rename.sh        upstream -> fork
#   ./module_rename.sh -r     fork -> upstream
#
# The module directive of the root go.mod is changed with the canonical
# `go mod edit -module`. Everything else (import paths, docs, scripts and
# nested go.mod require/replace directives) is rewritten textually.
# Hidden directories (.git, .github, .clusterfuzzlite, ...) are skipped.
set -eu

upstream="github.com/quic-go/quic-go"
fork="github.com/apernet/quic-go"

from="$upstream"
to="$fork"
if [ "${1:-}" = "-r" ] || [ "${1:-}" = "--reverse" ]; then
	from="$fork"
	to="$upstream"
fi

# Escape dots so the path is matched literally by sed.
from_re=$(printf '%s' "$from" | sed 's/\./\\./g')

# 1. Root module directive: use the canonical tool.
go mod edit -module="$to"

# 2. References everywhere else: imports, docs, scripts and nested go.mod
#    require/replace directives. This script is skipped so it doesn't rewrite
#    its own upstream/fork path literals.
find . -type d -name '.?*' -prune -o \
	-type f \( -name '*.go' -o -name '*.md' -o -name '*.sh' -o -name '*.mod' \) \
	! -name module_rename.sh \
	-exec sed -i "s,${from_re},${to},g" {} +
