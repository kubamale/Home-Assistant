#!/bin/sh
# Builds the home agent for the Raspberry Pi without installing Go: uses the official Go image.
# Output: dist/homedisplay-agent-arm64 (64-bit Pi OS) and dist/homedisplay-agent-armv7 (32-bit Pi OS).
set -eu
cd "$(dirname "$0")"
docker run --rm -v "$PWD":/src -w /src golang:1.25 sh -c '
  set -e
  mkdir -p dist
  CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/homedisplay-agent-arm64 ./cmd/agent
  CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -trimpath -ldflags="-s -w" -o dist/homedisplay-agent-armv7 ./cmd/agent
'
ls -lh dist
