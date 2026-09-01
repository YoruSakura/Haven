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
  `5.87.70-fork-6`.
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
4. Fork update 2 fixes Native X11 soft-keyboard events being left in the
   pressed state. IME-originated keys are bounded evdev taps, printable
   commit/raw-key echoes are suppressed within the same Looper turn, and the
   physical-keyboard path is handled separately.
5. Fork update 3 fixes tablet physical keyboards whose Android/View path drops
   `ACTION_UP` for Space or horizontal arrow keys. Normal hardware-key DOWN
   events are bounded evdev taps (Android repeat DOWN events preserve deliberate
   long-press repeat). Its stateful Ctrl/Alt/Shift handling is superseded by
   update 4.
6. Fork update 4 prevents physical modifiers from leaking into later terminal
   input when their `ACTION_UP` is dropped. Standalone Ctrl/Alt/Shift events are
   never forwarded as persistent Wayland state; each normal key DOWN becomes a
   self-contained chord from that event's Android `metaState`, with every
   modifier released before returning. This preserves shortcuts such as Ctrl+C
   while preventing Ctrl+J/newline, Ctrl+I/Tab completion, and Ctrl+arrow jumps
   from appearing after the modifier was physically released.
7. Fork update 5 fixes Native X11 clipboard truncation and a repeating final
   character. The native JNI input ring has 255 usable slots and silently drops
   overflow, while the old Paste callback synchronously queued 2–4 events per
   character. Wayland paste now runs off the UI callback, serializes concurrent
   pastes, sends at most 32 evdev events per burst, and pauses between bursts so
   a key DOWN/UP pair cannot be split at the queue boundary.
8. Fork update 6 gives the Native X11 xterm conventional physical-keyboard
   clipboard bindings. Every launch injects `Ctrl+Shift+C` as
   `copy-selection(CLIPBOARD)` and `Ctrl+Shift+V` as
   `insert-selection(CLIPBOARD)`, and enables xterm's CLIPBOARD selection mode.
   Launch-time resources apply to existing root filesystems without reinstalling
   the desktop packages or requiring `xrdb`.

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
- Version: `5.87.70-fork-6`
- ARM64 version code: `833061`
- ARM32 version code: `833063`
