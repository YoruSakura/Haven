# Haven fork project memory

Last updated: 2026-08-31

This file is the durable project memory for the modified Haven build. Read it
before rebasing, changing release configuration, or producing APKs.

## Identity and versioning

- Upstream baseline: Haven `5.87.70`, commit `533e3fe5`.
- Android application ID: `sh.haven.app.fork`. Do not change it; keeping this
  stable preserves upgrades and the fork's private app data while allowing the
  upstream `sh.haven.app` package to remain installed.
- Display name: `Haven修改版`.
- Version name: `<upstream-version>-fork-<revision>`, currently
  `5.87.70-fork-1`.
- Version code: `(upstreamVersionCode * 100 + forkRevision) * 10 + abiOffset`.
  ARM64 uses offset 1 and ARM32 uses offset 3. For another fork update on the
  same upstream version, increment `forkRevision` in `app/build.gradle.kts`.
  When adopting a new upstream version, update both upstream values and reset
  the revision to 1.

## Deliberate fork changes

1. `90f11e56` (`mosh: use upstream native client for direct sessions`)
   integrates the upstream native Mosh client and PTY bridge for direct Mosh
   sessions, with Android-native build and runtime handling.
2. `34a1dbb5` (`build: ship Android ARM splits only`) removes Android x86/x64
   app variants and release jobs. Supported APK ABIs are only `arm64-v8a` and
   `armeabi-v7a`; desktop and emulator x86 packages are unsupported.
3. `89afc790` (`app: give fork an independent Android identity`) separates the
   application ID, DocumentsProvider/FileProvider/Shizuku/Startup authorities,
   debug broadcast actions, APK identity, and app data from upstream Haven.

## Packaging constraints

- Produce separate `full` APKs for ARM64 and ARM32. Do not add a universal or
  x86 APK.
- ARM64 carries the native Wayland desktop stack. ARM32 has never had those
  upstream native Wayland libraries, but retains SSH, Mosh, RDP, SPICE, VNC,
  PRoot, rclone, PRNS, and FFmpeg functionality.
- APKs must contain exactly one native ABI, pass APK signature verification,
  and pass 16 KiB zip alignment verification.
- Local debug outputs are installable test builds. GitHub release builds use
  the configured release keystore and the same identity/version rules.

## Current release

- Application ID: `sh.haven.app.fork`
- Display name: `Haven修改版`
- Version: `5.87.70-fork-1`
- ARM64 version code: `833011`
- ARM32 version code: `833013`
