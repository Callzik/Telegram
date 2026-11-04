#!/bin/sh
# Rebuilds the bundled tunnel core TMessagesProj/src/main/jniLibs/arm64-v8a/libsingbox.so
# from the official sing-box sources (Go toolchain required, no NDK needed).
set -e
VERSION=${1:-v1.14.4}
OUT="$(cd "$(dirname "$0")/.." && pwd)/TMessagesProj/src/main/jniLibs/arm64-v8a/libsingbox.so"
WORK=$(mktemp -d)
export GOTOOLCHAIN=auto
cd "$WORK"
go mod init tmp >/dev/null 2>&1
go mod download -json "github.com/sagernet/sing-box@$VERSION" >/dev/null
SRC=$(go env GOMODCACHE)/github.com/sagernet/sing-box@$VERSION
cd "$SRC"
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath \
  -tags "with_quic,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0" \
  -ldflags "-X 'github.com/sagernet/sing-box/constant.Version=${VERSION#v}' $(cat release/LDFLAGS) -s -w -buildid=" \
  -o "$OUT" ./cmd/sing-box
echo "built $OUT"
