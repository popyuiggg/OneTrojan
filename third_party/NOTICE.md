# Third-party software

## hev-socks5-tunnel 2.17.1

- Source: https://github.com/heiher/hev-socks5-tunnel
- Commit: `9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a`
- License: MIT

The complete pinned source and its transitive source submodules are vendored in
`third_party/hev-socks5-tunnel`. The Android application builds only the
`hev-socks5-tunnel` shared-library target for `arm64-v8a`.

## uTLS bridge

The ARM64 AAR is built from `utlsbridge/` with Go and gomobile. Runtime
dependencies and licenses are recorded by the pinned `go.mod`/`go.sum`:

- `github.com/refraction-networking/utls` 1.8.1 — BSD-3-Clause
- `github.com/andybalholm/brotli` 1.0.6 — MIT
- `github.com/klauspost/compress` 1.17.4 — Apache-2.0
- `golang.org/x/crypto`, `golang.org/x/mobile`, and `golang.org/x/sys` — BSD-3-Clause
