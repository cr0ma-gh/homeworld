# Automated build system (GitHub Actions)

This directory contains the CI/CD pipelines for the GoK engine. They build the
project with [Meson] — the canonical build system documented in
[`Linux/BUILD.md`](../../Linux/BUILD.md) and
[`documentation/contributors/compiling-from-source.md`](../../documentation/contributors/compiling-from-source.md).

| Workflow | File | Triggers | What it does |
| --- | --- | --- | --- |
| **CI** | [`ci.yml`](ci.yml) | push to `main`, pull requests, manual | Builds on Linux with **gcc** and **clang**, in both `debug` (address+undefined sanitizers, the project default) and `release` profiles. Uploads the optimised Linux binary as an artifact. Also runs a best-effort macOS Meson build (non-blocking). |
| **WebAssembly** | [`wasm.yml`](wasm.yml) | push to `main`, tags, pull requests, manual | Cross-compiles to `wasm32` with Emscripten (`-Ddemo=true -Dmovies=false`) and deploys the playable demo to **GitHub Pages**. Mirrors the legacy GitLab `pages` job. |
| **Android** | [`android.yml`](android.yml) | push to `main`, tags, pull requests, manual | Builds the Android port (Gradle + NDK/CMake, `arm64-v8a`). Always builds the debug APK; builds the **signed release APK** when the signing secrets are present, and attaches it to the GitHub Release on tags. |
| **Release** | [`release.yml`](release.yml) | push of a `v*` tag, manual | Builds an optimised Linux binary, packages it as a `.tar.gz` with a SHA-256 checksum, and publishes a **GitHub Release** with auto-generated notes. |

## One-time repository setup

* **GitHub Pages** — to let the WebAssembly workflow publish the demo, enable
  Pages with *Build and deployment → Source → GitHub Actions*
  (Settings → Pages). No token is needed; deployment uses the built-in
  `GITHUB_TOKEN`.
* **Releases** — `release.yml` needs no extra secrets; it uses the built-in
  `GITHUB_TOKEN` (the workflow already requests `contents: write`).
* **Android signing** — the debug APK builds with no configuration. For the
  signed release APK, add these repository secrets
  (Settings → Secrets and variables → Actions):
  * `ANDROID_KEYSTORE_BASE64` — `base64 -w0 release.keystore`
  * `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`

  Without them the Android workflow still passes (debug-only). The native
  dependencies **SDL** and **gl4es** are git submodules pinned in
  [`.gitmodules`](../../.gitmodules); CI checks them out automatically.
  Clone locally with `git clone --recurse-submodules` (or
  `git submodule update --init --recursive`).

## Cutting a release

```sh
git tag v1.2.0
git push origin v1.2.0
```

This triggers both `release.yml` (binary + GitHub Release) and `wasm.yml`
(Pages deployment).

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
