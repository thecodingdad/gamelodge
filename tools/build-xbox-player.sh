#!/usr/bin/env bash
# Builds the Xbox player page (xbox-player/dist/xbox-player/: index.html, player.js, NOTICE.txt).
# The result is committed, so Gradle builds stay offline; run this after changing xbox-player/src or
# updating xstreaming-player (pinned in xbox-player/package.json, used unchanged).
set -euo pipefail
cd "$(dirname "$0")/../xbox-player"
rm -rf dist
npm ci --ignore-scripts --no-audit --no-fund
npm run -s build
ls -la dist/xbox-player
