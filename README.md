# LocalLlm

Android local-LLM application with Vulkan/GPU support.

## Source layout

The Android project is currently distributed as `LocalLlm.zip` in this repository. CI extracts it into a temporary directory before building. The archive should contain a `LocalLlm/` project directory with the Gradle wrapper, Android app, native build setup, and host regression tests.

## Build and test

The GitHub Actions workflow runs on pushes to `main`, pushes to `fix-mobile-vulkan-stability`, pull requests targeting `main`, and manual dispatches.

The workflow installs the pinned Android SDK/NDK/CMake versions, checks shell and Python syntax, runs host regression tests, prepares pinned native dependencies, validates the host GLSL compiler, runs Android unit tests, and assembles a debug APK.

To download the APK, open the latest successful **Android build and tests** run under the repository's **Actions** tab and download the `LocalLlm-debug-apk` artifact. Artifacts are temporary CI outputs, not signed production releases.

## Current validation scope

A green CI run confirms the automated checks and debug APK assembly passed on the GitHub-hosted Linux runner. It does not by itself establish runtime compatibility across Android devices or Vulkan drivers; test on target hardware before treating a build as release-ready.

## Contributing

Keep the project archive and the workflow's expected internal path in sync. When changing the archive, verify that `unzip -l LocalLlm.zip` shows `LocalLlm/` at its root and that the Gradle wrapper is executable after extraction.
