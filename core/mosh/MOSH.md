# Mosh Backends

Haven uses two Mosh data-plane implementations behind the same SSH bootstrap
and terminal UI.

## Backend selection

| Profile/runtime | Backend | Why |
|---|---|---|
| Direct UDP and `libmosh_client.so` packaged for the device ABI | upstream `mosh-client` 1.4.0 | Full terminal state model, adaptive speculative local echo, rollback, RTT handling, retransmission and roaming |
| WireGuard/Tailscale injected UDP socket | Kotlin SSP | Native mosh cannot consume Haven's `UdpSocketProvider` abstraction |
| Source/test build with no native executable | Kotlin SSP | Keeps Mosh available and makes partial/offline builds degrade safely |

The selection is made once in `MoshSession.start()`. A native startup failure
before the child PTY is established also falls back to Kotlin rather than
removing Mosh from the app.

## Native direct path

```text
ConnectionsViewModel
  └─ SSH exec: mosh-server new
       └─ MOSH CONNECT <port> <key>
            └─ MoshSession
                 └─ forkpty + execve(libmosh_client.so)
                      ├─ upstream mosh UDP/state/prediction engine
                      └─ PTY bytes ↔ Haven termlib
```

`libmosh_client.so` is an Android PIE executable with a `.so` suffix solely so
the package installer extracts it into `applicationInfo.nativeLibraryDir`. It
is a standalone GPLv3 process, not a JNI library and not linked into Haven.
Only PTY bytes and terminal-size ioctls cross the process boundary.

Android does not provide a terminfo database. `compileMoshTerminfo` compiles
the pinned, self-contained `xterm-256color.src` into the AAR; the runtime copies
that single entry to the app-private files directory and sets `TERMINFO` for
the child. The AES session key is passed only in the child's `MOSH_KEY`
environment, matching upstream mosh's launch contract.

### Reproducible native build

```bash
ANDROID_SDK_ROOT=/path/to/android-sdk ./scripts/build-mosh-native.sh
```

The build supports `arm64-v8a`, `x86_64`, and `armeabi-v7a`. Outputs go to the
gitignored `core/mosh/src/main/jniLibs/<abi>/libmosh_client.so` directories and
are then picked up by the normal Android source set.

Pinned inputs:

- upstream `mobile-shell/mosh` 1.4.0 commit
  `bc73a26316ede2a79259d859f8ee309b32412420`;
- `rjyo/mosh-android` v1.0.0 static-library bundle, verified before extraction
  with SHA-256
  `8a6c88d9d7646d796db0a7f58571564d59b8dcdc7836b0dbf679318a23141005`;
- Android NDK r29 (`29.0.14206865`), API 26, and 16 KiB ELF page alignment.

The rjyo x86_64/armv7 ncurses archives reference but omit ncurses' optional
compiled-in `_nc_fallback2` lookup. For only those archives, the build links a
small null fallback from `scripts/native/mosh-ncurses-fallback.c`; Haven always
supplies an external terminfo DB, so no built-in entry should be selected.

## Kotlin tunnel/fallback path

The existing `ssp-transport` implementation remains in process:

```text
MoshTransport
  ├─ MoshCrypto       AES-128-OCB via Bouncy Castle
  ├─ MoshConnection   UDP, timestamps, zlib and fragments
  ├─ WireFormat       protobuf-compatible SSP messages
  ├─ UserStream       keystrokes and resizes
  └─ UdpSocketProvider (raw Android UDP or Haven tunnel adapter)
```

It preserves Haven's tunnel support, automatic reconnect escalation and
verbose transport diagnostics. It does not implement upstream Mosh's terminal
prediction model, so tunneled and no-native builds retain the previous
round-trip-bound input feel. Replacing that fallback requires a native socket
adapter or a complete prediction/state model; a simple local echo is not a
correct substitute.

### Kotlin SSP invariants

- Every proto2 transport field, including zero-valued `old_num`, must be sent.
- `throwawayNum` belongs to the `UserStream` state space, not the remote
  terminal-state space.
- Android UDP sockets remain unconnected so stale-session ICMP errors do not
  terminate roaming sessions.
- Direct keystrokes/acks target 20 ms sends; retransmits back off from 100 ms;
  keepalives run every three seconds.
