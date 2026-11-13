#!/usr/bin/env python3
"""UDP fault-injection relay for testing Haven's mosh client (#421).

Sits between the phone and a local mosh-server and can drop traffic on demand,
which reproduces the one condition that is otherwise hard to manufacture: the
session goes silent while the device still has a perfectly good network. That is
what separates "roaming, keep waiting" (mosh's whole point) from "this session
is never coming back", and it is the case Haven used to sit in forever behind a
"retrying" banner.

Blackholing the relay is deliberately NOT the same as turning the phone's Wi-Fi
off: the phone stays online, so the client's connectivity check still says
"network is up" and the #421 escalation is allowed to fire. Turning Wi-Fi off
instead exercises the opposite path — the one that must never give up.

Usage:
    # 1. start a mosh-server and the relay in front of it
    ./mosh-fault-rig.py start

    # it prints the MOSH_KEY + relay port to use from the phone, then:
    ./mosh-fault-rig.py blackhole    # silence the session (device stays online)
    ./mosh-fault-rig.py pass         # resume forwarding
    ./mosh-fault-rig.py status
    ./mosh-fault-rig.py stop

Control is a tiny file the relay polls, so the toggles work from any shell
(or over SSH from a test script) without signals or a control socket.
"""
import os
import selectors
import socket
import subprocess
import sys
import time

STATE_DIR = os.environ.get("MOSH_RIG_DIR", "/tmp/mosh-fault-rig")
MODE_FILE = os.path.join(STATE_DIR, "mode")       # "pass" | "blackhole"
INFO_FILE = os.path.join(STATE_DIR, "info")       # human-readable connect info
PID_FILE = os.path.join(STATE_DIR, "relay.pid")
LOG_FILE = os.path.join(STATE_DIR, "relay.log")   # forwarded/dropped counters
RELAY_PORT = int(os.environ.get("MOSH_RIG_PORT", "60999"))


def _read_mode() -> str:
    try:
        with open(MODE_FILE) as f:
            return f.read().strip() or "pass"
    except FileNotFoundError:
        return "pass"


def relay(upstream_port: int) -> None:
    """Forward UDP both ways between the phone and mosh-server, unless blackholed.

    Single client is enough for this rig. The client's source address is learned
    from its first packet and refreshed on every packet, so the relay keeps
    working across the client's own socket rebinds — which matters, because
    rebinding is precisely what Haven does while trying to recover, and the rig
    must not be what breaks it.
    """
    listen = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    listen.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listen.bind(("0.0.0.0", RELAY_PORT))

    upstream = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    upstream.connect(("127.0.0.1", upstream_port))

    sel = selectors.DefaultSelector()
    sel.register(listen, selectors.EVENT_READ, "client")
    sel.register(upstream, selectors.EVENT_READ, "server")

    client_addr = None
    dropped = 0
    forwarded = 0
    last_log = 0.0

    while True:
        for key, _ in sel.select(timeout=1.0):
            blackholed = _read_mode() == "blackhole"
            if key.data == "client":
                data, addr = listen.recvfrom(65535)
                client_addr = addr  # refresh: survives client socket rebinds
                if blackholed:
                    dropped += 1
                    continue
                upstream.send(data)
                forwarded += 1
            else:
                try:
                    data = upstream.recv(65535)
                except ConnectionRefusedError:
                    # A connected UDP socket reports ICMP port-unreachable here,
                    # i.e. mosh-server is gone (it exits ~60s after its last
                    # client contact). Say so rather than dying: a rig that
                    # crashes looks exactly like a client bug in the logs.
                    print("[rig] upstream mosh-server is gone", flush=True)
                    continue
                if blackholed or client_addr is None:
                    dropped += 1
                    continue
                listen.sendto(data, client_addr)
                forwarded += 1

        now = time.time()
        if now - last_log > 5:
            last_log = now
            print(
                f"[rig] mode={_read_mode()} client={client_addr} "
                f"forwarded={forwarded} dropped={dropped}",
                flush=True,
            )


def start(quiet: bool = False, keep_mode: bool = False) -> int:
    os.makedirs(STATE_DIR, exist_ok=True)
    # keep_mode matters for `bootstrap`: Haven re-runs the bootstrap command on
    # every (re)connect, so resetting to "pass" there would let the client clear
    # the very fault it is being tested against — the reconnect silently
    # un-blackholes itself and the run looks like a pass. Explicit `start`
    # still resets, so a fresh rig begins in a known state.
    if not (keep_mode and os.path.exists(MODE_FILE)):
        with open(MODE_FILE, "w") as f:
            f.write("pass")

    # mosh-server prints: MOSH CONNECT <port> <key>
    # Bind mosh-server to loopback explicitly. With `-s` it binds the address
    # from SSH_CONNECTION — i.e. the LAN IP of whoever is SSH'd in — and the
    # relay's upstream socket then gets ICMP port-unreachable dialling
    # 127.0.0.1. Loopback also keeps the real server unreachable from the
    # network, so the phone can only ever reach it through the relay, which is
    # what makes the blackhole authoritative.
    out = subprocess.run(
        ["mosh-server", "new", "-i", "127.0.0.1", "-c", "256"],
        capture_output=True,
        text=True,
        env={**os.environ, "LC_ALL": "C.UTF-8"},
    )
    line = next(
        (ln for ln in out.stdout.splitlines() if ln.startswith("MOSH CONNECT")),
        None,
    )
    if not line:
        print("mosh-server did not report MOSH CONNECT:", out.stdout, out.stderr)
        return 1
    _, _, upstream_port, key = line.split()

    info = (
        f"mosh-server port : {upstream_port}\n"
        f"relay port       : {RELAY_PORT}   <-- point the phone at THIS\n"
        f"MOSH_KEY         : {key}\n"
    )
    with open(INFO_FILE, "w") as f:
        f.write(info)
    if not quiet:
        print(info)
        print("Connect from the phone (mosh profile / manual):")
        print(f"  MOSH_KEY={key} mosh-client <this-host-ip> {RELAY_PORT}")
        print("Then: mosh-fault-rig.py blackhole   # silence it, device stays online")

    pid = os.fork()
    if pid == 0:
        os.setsid()
        # Re-point stdio at a log file: the parent exits immediately, so the
        # relay's counters (forwarded/dropped) would otherwise go nowhere — and
        # those counters are how you tell "the blackhole is actually dropping"
        # from "the client stopped sending".
        log = os.open(LOG_FILE, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
        os.dup2(log, 1)
        os.dup2(log, 2)
        relay(int(upstream_port))
        os._exit(0)
    with open(PID_FILE, "w") as f:
        f.write(str(pid))
    if not quiet:
        print(f"[rig] relay pid {pid} listening on udp/{RELAY_PORT}")
    return 0


def _relay_alive() -> bool:
    try:
        with open(PID_FILE) as f:
            os.kill(int(f.read().strip()), 0)
        return True
    except Exception:
        return False


def bootstrap() -> int:
    """Start server+relay and print ONLY the MOSH CONNECT line Haven parses.

    Point a profile's `moshServerCommand` at `mosh-fault-rig.py bootstrap` and
    Haven's ordinary mosh flow connects *through* the relay without knowing it:
    it SSHes in, runs this, reads `MOSH CONNECT <port> <key>`, and dials that
    port — which is the relay, not mosh-server. Everything after that is a real
    Haven mosh session, so blackholing the relay silences a genuine session
    rather than a mock.
    """
    # Reuse the running rig on a reconnect. Starting a second mosh-server and
    # racing another relay onto the same port would leave the client talking to
    # one server with the other's key — a rig artefact that reads as a client
    # failure.
    if not _relay_alive():
        rc = start(quiet=True, keep_mode=True)
        if rc != 0:
            return rc
    with open(INFO_FILE) as f:
        info = dict(
            (k.strip(), v.strip())
            for k, v in (ln.split(":", 1) for ln in f if ":" in ln)
        )
    print(f"MOSH CONNECT {RELAY_PORT} {info['MOSH_KEY']}")
    return 0


def stop() -> int:
    try:
        with open(PID_FILE) as f:
            pid = int(f.read().strip())
        os.kill(pid, 15)
        print(f"[rig] stopped relay {pid}")
    except (FileNotFoundError, ProcessLookupError, ValueError):
        print("[rig] no relay running")
    return 0


def main() -> int:
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "start":
        return start()
    if cmd == "bootstrap":
        return bootstrap()
    if cmd == "stop":
        return stop()
    if cmd in ("blackhole", "pass"):
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(MODE_FILE, "w") as f:
            f.write(cmd)
        print(f"[rig] mode -> {cmd}")
        return 0
    if cmd == "status":
        print(f"[rig] mode = {_read_mode()}")
        try:
            with open(INFO_FILE) as f:
                print(f.read())
        except FileNotFoundError:
            print("[rig] not started")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
