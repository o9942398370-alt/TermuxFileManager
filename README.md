# Termux File Manager

A monochrome Android file-manager project for the private directory tree owned by Termux. The default page starts at `.../files/home`, and the navigation boundary is `.../files`. Hidden files are shown by default. The app uses the system locale; English is the fallback and Persian is included.

## Features in this starter build

- Detects whether Termux is installed. If missing, shows official Termux release and F-Droid pages.
- Uses Termux's documented `RUN_COMMAND` service to execute operations under Termux's own UID, instead of trying to access `/data/data/com.termux` directly from the app's UID.
- Browses `home`, `usr`, and other items below Termux's private `files` directory; symbolic links are identified and are not followed into other locations.
- Shows dotfiles by default; supports name/size/modified sorting and in-folder name search.
- Create folder/file, rename, copy, delete with confirmation, multi-select delete, ZIP selected items, and extract ZIP archives.
- Offers optional one-tap installation of Termux `zip`/`unzip` packages after user confirmation.
- Includes Root status check and Shizuku install/open controls in Settings. Normal browsing stays inside Termux's private `files` directory; Shizuku's ordinary shell mode is not root, and this starter build does not browse outside the Termux files boundary.
- Includes a GitHub Actions workflow that builds and uploads a debug-signed APK artifact.

## Important first-run setup

Termux intentionally blocks third-party apps from running commands by default. The user must enable this explicitly:

1. Install and open Termux at least once.
2. In Android Settings, grant this app **Run commands in Termux environment** under its additional permissions.
3. In Termux's `~/.termux/termux.properties`, add `allow-external-apps=true`. The app has a Copy button for this setting, but it does not silently change Termux's security preference.
4. Return to the app and tap Connect / retry.

Termux's `RUN_COMMAND` API is documented at https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent.

## Build APK using GitHub Actions

The present authoring container does not have Android SDK/Gradle installed, and outbound DNS is unavailable, so it cannot compile an APK locally. The source includes a remote build workflow to do that using GitHub-hosted Android build tools.

1. Create a repository on GitHub.
2. Upload the **contents** of this folder to the repository root (including `.github/workflows/build-apk.yml`).
3. Open the repository's **Actions** tab and run **Build Android APK** (or push a commit to `main`/`master`).
4. When the workflow succeeds, download the `Termux-File-Manager-debug-APK` artifact. It contains `app-debug.apk`.

The debug APK is signed with a temporary debug key for testing. Before redistributing, use your own signing key. No OpenAI API key is used or required.

## App settings and architecture

- Namespace/application ID: `dev.termux.filemanager` (debug package uses `.debug` suffix).
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`.
- The app is Java-based and does not package native libraries; its bytecode supports 64-bit arm64 Android devices as well as other supported Android architectures.
- UI resources follow Android's locale resolution: English default, Persian translations in `values-fa`.
