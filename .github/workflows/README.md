# Automated build system (GitHub Actions)

This directory contains the CI/CD pipelines for the GoK engine. They build the
project with [Meson] — the canonical build system documented in
[`Linux/BUILD.md`](../../Linux/BUILD.md) and
[`documentation/contributors/compiling-from-source.md`](../../documentation/contributors/compiling-from-source.md).

| Workflow | File | Triggers | What it does |
| --- | --- | --- | --- |
| **CI** | [`ci.yml`](ci.yml) | push to `main`, pull requests, manual | Builds on Linux with **gcc** and **clang**, in both `debug` (address+undefined sanitizers, the project default) and `release` profiles. Uploads the optimised Linux binary as an artifact. (No macOS job: the Meson build's darwin path needs an X11-enabled SDL2 that hosted runners don't provide — the supported macOS build is the Xcode project in `Mac/BUILD.md`.) |
| **WebAssembly** | [`wasm.yml`](wasm.yml) | push to `main`, tags, pull requests, manual | Cross-compiles to `wasm32` with Emscripten (`-Ddemo=true -Dmovies=false`) and deploys the playable demo to **GitHub Pages**. Mirrors the legacy GitLab `pages` job. |
| **Android** | [`android.yml`](android.yml) | push to `main`, tags, pull requests, manual | Builds the Android port (Gradle + NDK/CMake, `arm64-v8a`). Always builds the debug APK; on a `v*` tag it builds the **signed release APK** and publishes a **GitHub Release** whose only asset is that APK (`Homeworld-<tag>.apk`, with auto-generated notes). This is the release workflow. |

## One-time repository setup

* **GitHub Pages** — to let the WebAssembly workflow publish the demo, enable
  Pages with *Build and deployment → Source → GitHub Actions*
  (Settings → Pages). No token is needed; deployment uses the built-in
  `GITHUB_TOKEN`.
* **Android signing (required for releases)** — a release publishes only the
  *signed* APK, so the signing secrets must be set or the tag produces no
  release. Add these repository secrets
  (Settings → Secrets and variables → Actions):
  * `ANDROID_KEYSTORE_BASE64` — `base64 -w0 release.keystore`
  * `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`

  Without them the Android workflow still passes on normal pushes (debug-only).
  The native
  dependencies **SDL** and **gl4es** are git submodules pinned in
  [`.gitmodules`](../../.gitmodules); CI checks them out automatically.
  Clone locally with `git clone --recurse-submodules` (or
  `git submodule update --init --recursive`).

## Cutting a release

Make sure the Android signing secrets are set (see above), then push a `v*` tag:

```sh
git tag v1.2.0
git push origin v1.2.0
```

`android.yml` builds the signed APK and publishes a GitHub Release containing
`Homeworld-v1.2.0.apk`. The same tag also triggers `wasm.yml` (Pages
deployment, if Pages is enabled).

## Building locally

The workflows run the same commands a contributor would run by hand:

```sh
meson setup build            # debug build with sanitizers (default)
meson compile -C build
./build/homeworld
```

See [`Linux/BUILD.md`](../../Linux/BUILD.md) for the full instructions,
including the Nix dev shell and the Emscripten cross build.

[Meson]: https://mesonbuild.com/
