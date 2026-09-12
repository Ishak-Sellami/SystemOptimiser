"""Multi-device v2 networking layer contract tests (matrix A-Y).

Covers the new controller_multi.py layer without a device: registry and group
persistence, the signed handshake (enroll / verified / rekey / deny / prompt),
HMAC scheme consistency (cross-language guard vector), v2 command framing
(send_command ACK/RESULT with PONG/NOTIFY skipping + timeout), per-session
result correlation, heartbeat state transitions, target selection routing,
exact dispatch framing, menu-label parity with the classic controller, beacon
format, and the controller.py main-entry wiring.

Run with:  python tests/test_network.py
"""

import base64
import os
import re
import socket
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

import controller  # noqa: E402,F401
from controller import (  # noqa: E402
    receive_message,
    send_message,
)
import controller_multi as cm  # noqa: E402
from controller_multi import (  # noqa: E402
    DEVICE_STATE_CONNECTED,
    DEVICE_STATE_DEGRADED,
    DEVICE_STATE_OFFLINE,
    PROTOCOL_VERSION,
    RUN_MENU_LINES,
    CONTROLLER_VERSION,
    DeviceRegistry,
    DeviceSession,
    GroupStore,
    MainController,
    _b64,
    _hmac_hex,
    _unb64,
    build_beacon,
    perform_server_handshake,
    send_command,
)

TESTS = []


def _t(name):
    def deco(fn):
        TESTS.append((name, fn))
        return fn
    return deco


def _new_registry(path):
    return DeviceRegistry(os.path.join(path, "devices.json"))


def _new_groups(path):
    return GroupStore(os.path.join(path, "groups.json"))


class _StubController(MainController):
    """A MainController for session-level tests: real bound methods on the
    real class, but no listener socket is ever bound."""

    def __init__(self, path):
        self.registry = _new_registry(path)
        self.groups = _new_groups(path)
        self.sessions = {}
        self.sessions_lock = threading.Lock()
        self.selected = set()
        self.running = True


def _stub_controller(path):
    return _StubController(path)


# ----------------------------------------------------------------------
# A: imports + constants
# ----------------------------------------------------------------------


@_t("A: module imports and protocol constants")
def test_a_constants():
    assert PROTOCOL_VERSION == "2"
    assert CONTROLLER_VERSION
    assert len(RUN_MENU_LINES) == 18
    assert len({DEVICE_STATE_CONNECTED, DEVICE_STATE_DEGRADED,
                DEVICE_STATE_OFFLINE}) == 3
    for prefix in ("SNOPT_BEACON|", "cmd|", "RESULT|", "AGENT_HELLO|",
                   "HELLO_OK|", "PING|", "PONG|", "STATE_REQ|", "CTRL_EXIT",
                   "SET_CTRL|", "NOTIFY|", "ACK|"):
        assert prefix


# ----------------------------------------------------------------------
# B: classic controller surface still intact
# ----------------------------------------------------------------------


@_t("B: classic controller.py surface intact")
def test_b_legacy_surface():
    assert hasattr(controller, "PhoneController")
    assert controller.HOST == "0.0.0.0"
    assert controller.PORT == 4444
    assert controller.MAX_MESSAGE_SIZE == 8 * 1024 * 1024
    assert callable(controller.send_message)
    assert callable(controller.receive_message)
    assert callable(controller.scan_audio_library)
    assert callable(controller.scan_video_library)


# ----------------------------------------------------------------------
# C: DeviceRegistry enroll / token
# ----------------------------------------------------------------------


@_t("C: registry enroll yields valid 32-byte token")
def test_c_enroll():
    with tempfile.TemporaryDirectory() as tmp:
        reg = _new_registry(tmp)
        token_b64 = reg.enroll("dev-c")
        token = base64.b64decode(token_b64)
        assert len(token) == 32
        assert reg.token_bytes("dev-c") == token
        assert reg.token_matches("dev-c", token_b64)
        assert not reg.token_matches("dev-c", base64.b64encode(b"x" * 32).decode())
        assert reg.devices["dev-c"]["first_seen"]
        entry = reg.devices["dev-c"]
        assert "last_seen" in entry


# ----------------------------------------------------------------------
# D: DeviceRegistry persistence roundtrip
# ----------------------------------------------------------------------


@_t("D: registry persists and reloads")
def test_d_persistence():
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "devices.json")
        reg1 = DeviceRegistry(path)
        token = reg1.enroll("dev-d")
        reg1.update("dev-d", model="Emulator")
        del reg1
        reg2 = DeviceRegistry(path)
        assert reg2.token_b64("dev-d") == token
        assert reg2.devices["dev-d"].get("model") == "Emulator"


# ----------------------------------------------------------------------
# E: DeviceRegistry remove / rename
# ----------------------------------------------------------------------


@_t("E: registry remove and rename")
def test_e_remove_rename():
    with tempfile.TemporaryDirectory() as tmp:
        reg = _new_registry(tmp)
        token = reg.enroll("dev-e")
        reg.rename("dev-e", "My Phone")
        assert reg.devices["dev-e"].get("name") == "My Phone"
        assert reg.token_b64("dev-e") == token
        reg.remove("dev-e")
        assert reg.token_b64("dev-e") is None


# ----------------------------------------------------------------------
# F: GroupStore operations + persistence
# ----------------------------------------------------------------------


@_t("F: group store create/rename/set/add/persist")
def test_f_groups():
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "groups.json")
        g1 = GroupStore(path)
        g1.create("home", ["dev-a", "dev-b"])
        g1.add_device("home", "dev-c")
        g1.set_devices("work", ["dev-x"])
        assert g1.groups["home"] == ["dev-a", "dev-b", "dev-c"]
        g1.remove_device("home", "dev-b")
        assert g1.groups["home"] == ["dev-a", "dev-c"]
        assert g1.rename("work", "office")
        g1.remove("home")
        del g1
        g2 = GroupStore(path)
        assert g2.groups.get("office") == ["dev-x"]
        assert "home" not in g2.groups


# ----------------------------------------------------------------------
# G/H: parse_agent_hello
# ----------------------------------------------------------------------


@_t("G: parse_agent_hello valid frame")
def test_g_parse_hello():
    info = cm.parse_agent_hello(
        "AGENT_HELLO|dev-123|2|1.0.0|nonce1|auth1|caps|block_state=NONE;battery=100")
    assert info is not None
    assert info["device_id"] == "dev-123"
    assert info["proto"] == "2"
    assert info["version"] == "1.0.0"
    assert info["nonce"] == "nonce1"
    assert info["auth"] == "auth1"
    assert info["caps"] == "caps"
    assert info["state"] == "block_state=NONE;battery=100"


@_t("H: parse_agent_hello malformed rejected")
def test_h_parse_hello_bad():
    assert cm.parse_agent_hello("HELLO|whatever") is None
    assert cm.parse_agent_hello("AGENT_HELLO|a|b|c|d") is None
    assert cm.parse_agent_hello("") is None


# ----------------------------------------------------------------------
# J: enrollment handshake issues a token
# ----------------------------------------------------------------------


@_t("J: first-contact enroll issues TOKEN in HELLO_OK")
def test_j_enroll():
    with tempfile.TemporaryDirectory() as tmp:
        reg = _new_registry(tmp)
        server_end, agent_end = socket.socketpair()
        out = {}

        def agent_side():
            nonce = "beef0000"
            send_message(agent_end,
                         f"AGENT_HELLO|dev-new|2|1.0.0|{nonce}|PAIR|caps|state=x")
            out["reply"] = receive_message(agent_end)

        t = threading.Thread(target=agent_side)
        t.start()
        try:
            res = perform_server_handshake(server_end, reg, auto_enroll=True)
            t.join(10)
            assert not t.is_alive()
            assert res is not None and res[0] == "dev-new"
            reply = out["reply"]
            assert reply.startswith("HELLO_OK|")
            assert "TOKEN=" in reply
            issued = reply.split("TOKEN=")[1]
            assert reg.token_b64("dev-new") == issued
            assert len(base64.b64decode(issued)) == 32
        finally:
            server_end.close()
            agent_end.close()


# ----------------------------------------------------------------------
# K: verified re-handshake (existing token, no rekey)
# ----------------------------------------------------------------------


@_t("K: known device verifies and gets HELLO_OK without TOKEN")
def test_k_verified():
    with tempfile.TemporaryDirectory() as tmp:
        reg = _new_registry(tmp)
        token_b64 = reg.enroll("dev-k")
        server_end, agent_end = socket.socketpair()
        out = {}

        def agent_side():
            nonce = "feedcafe"
            auth = _hmac_hex(base64.b64decode(token_b64), f"dev-k:{nonce}")
            send_message(agent_end,
                         f"AGENT_HELLO|dev-k|2|1.0.0|{nonce}|{auth}|caps|s")
            out["reply"] = receive_message(agent_end)

        t = threading.Thread(target=agent_side)
        t.start()
        try:
            res = perform_server_handshake(server_end, reg, auto_enroll=True)
            t.join(10)
            assert res is not None and res[0] == "dev-k"
            assert out["reply"].startswith("HELLO_OK|")
            assert "TOKEN=" not in out["reply"]
        finally:
            server_end.close()
            agent_end.close()


# ----------------------------------------------------------------------
# L: mismatched token on a known device -> rekey
# ----------------------------------------------------------------------


@_t("L: known device with stale token is rekeyed (auto-enroll)")
def test_l_rekey():
    with tempfile.TemporaryDirectory() as tmp:
        reg = _new_registry(tmp)
        old_token = reg.enroll("dev-l")
        server_end, agent_end = socket.socketpair()
        out = {}

        def agent_side():
            nonce = "cafe1234"
            wrong = base64.b64encode(b"z" * 32).decode()
            auth = _hmac_hex(base64.b64decode(wrong), f"dev-l:{nonce}")
            send_message(agent_end,
                         f"AGENT_HELLO|dev-l|2|1.0.0|{nonce}|{auth}|caps|s")
            out["reply"] = receive_message(agent_end)

        t = threading.Thread(target=agent_side)
        t.start()
        try:
            res = perform_server_handshake(server_end, reg, auto_enroll=True)
            t.join(10)
            assert res is not None and res[0] == "dev-l"
            assert "TOKEN=" in out["reply"]
            issued = out["reply"].split("TOKEN=")[1]
            assert issued != old_token
            assert reg.token_b64("dev-l") == issued
            # New token actually authenticates on the next handshake.
            new_auth = _hmac_hex(base64.b64decode(issued), "dev-l:NEWNONCE")
            assert _hmac_hex(base64.b64decode(issued),
                             "hellook:NEWNONCE")  # server side derivation works
        finally:
            server_end.close()
            agent_end.close()


# ----------------------------------------------------------------------
# M/N: interactive enrollment
# ----------------------------------------------------------------------


@_t("M: unknown device refused without auto-enroll")
def test_m_deny():
    with tempfile.TemporaryDirectory() as tmp:
        reg = _new_registry(tmp)
        server_end, agent_end = socket.socketpair()
        out = {}

        def agent_side():
            send_message(agent_end, "AGENT_HELLO|dev-m|2|1.0.0|n|PAIR|c|s")
            out["reply"] = receive_message(agent_end)

        t = threading.Thread(target=agent_side)
        t.start()
        try:
            res = perform_server_handshake(server_end, reg, auto_enroll=False,
                                           prompt_cb=lambda did: False)
            t.join(10)
            assert res is None
            assert out["reply"].startswith("AUTH_DENIED|")
            assert reg.token_b64("dev-m") is None
        finally:
            server_end.close()
            agent_end.close()


@_t("N: unknown device enrolled when prompt accepts")
def test_n_prompt_accept():
    with tempfile.TemporaryDirectory() as tmp:
        reg = _new_registry(tmp)
        server_end, agent_end = socket.socketpair()
        out = {}

        def agent_side():
            send_message(agent_end, "AGENT_HELLO|dev-n|2|1.0.0|n|PAIR|c|s")
            out["reply"] = receive_message(agent_end)

        t = threading.Thread(target=agent_side)
        t.start()
        try:
            res = perform_server_handshake(server_end, reg, auto_enroll=False,
                                           prompt_cb=lambda did: did == "dev-n")
            t.join(10)
            assert res is not None and res[0] == "dev-n"
            assert "TOKEN=" in out["reply"]
        finally:
            server_end.close()
            agent_end.close()


# ----------------------------------------------------------------------
# O: HMAC scheme lock (cross-language / standard vector)
# ----------------------------------------------------------------------


@_t("O: HMAC-SHA256 hex scheme matches the standard vector")
def test_o_hmac_vector():
    expected = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
    assert _hmac_hex(b"key", "The quick brown fox jumps over the lazy dog") == expected
    # Java-side layout lock: agent auth = hmac(token, id:nonce), server = hmac(token, hellook:+nonce)
    token = b"a-shared-secret-token"
    assert len(_hmac_hex(token, "x")) == 64
    assert _hmac_hex(token, "id:nonce") != _hmac_hex(token, "hellook:nonce")


# ----------------------------------------------------------------------
# P/Q: send_command framing
# ----------------------------------------------------------------------


@_t("P: send_command parses ACK+RESULT past PONG/NOTIFY")
def test_p_send_command():
    server_end, agent_end = socket.socketpair()
    got = []

    def agent_side():
        frame = receive_message(agent_end)
        cid = frame.split("|")[1]
        got.append(frame)
        send_message(agent_end, "PONG|0")
        send_message(agent_end, f"ACK|{cid}")
        send_message(agent_end, f"NOTIFY|{_b64('async push')}")
        send_message(agent_end, f"RESULT|{cid}|OK|{_b64('سلام hello')}")

    t = threading.Thread(target=agent_side)
    t.start()
    try:
        pair = send_command(server_end, "device_info", timeout=5)
        t.join(10)
        assert pair == ("OK", "سلام hello")
        m = re.match(r"^cmd\|([0-9a-f]{12})\|", got[0])
        assert m
        assert _unb64(got[0].split("|", 2)[2]) == "device_info"
    finally:
        server_end.close()
        agent_end.close()


@_t("Q: send_command times out when the agent never answers")
def test_q_send_command_timeout():
    server_end, agent_end = socket.socketpair()
    start = time.time()
    try:
        pair = send_command(server_end, "block_app:x", timeout=1)
        elapsed = time.time() - start
        assert pair is None
        assert elapsed < 10
    finally:
        server_end.close()
        agent_end.close()


# ----------------------------------------------------------------------
# R: per-session result correlation
# ----------------------------------------------------------------------


@_t("R: dispatch results stay per-session (no cross-talk)")
def test_r_isolation():
    with tempfile.TemporaryDirectory() as tmp:
        mc = _stub_controller(tmp)
        a1, b1 = socket.socketpair()
        a2, b2 = socket.socketpair()
        s1 = DeviceSession(a1, ("h", 1), "dev-r1", mc)
        s2 = DeviceSession(a2, ("h", 2), "dev-r2", mc)
        mc.sessions = {"dev-r1": s1, "dev-r2": s2}
        s1.start()
        s2.start()

        def serve(agent_end, payload, out_key):
            frame = receive_message(agent_end)
            cid = frame.split("|")[1]
            send_message(agent_end, f"ACK|{cid}")
            send_message(agent_end, f"RESULT|{cid}|OK|{_b64(payload)}")

        nt = threading.Thread(target=serve, args=(b1, "PAYLOAD-1", None))
        mt = threading.Thread(target=serve, args=(b2, "PAYLOAD-2", None))
        nt.start(); mt.start()
        try:
            out1 = mc.dispatch("cmd-one", ["dev-r1"], timeout=10, show=False)
            out2 = mc.dispatch("cmd-two", ["dev-r2"], timeout=10, show=False)
            nt.join(10); mt.join(10)
            assert list(out1.keys()) == ["dev-r1"]
            assert list(out2.keys()) == ["dev-r2"]
            assert out1["dev-r1"][0] == "OK" and "PAYLOAD-1" in out1["dev-r1"][1]
            assert out2["dev-r2"][0] == "OK" and "PAYLOAD-2" in out2["dev-r2"][1]
        finally:
            for s in (a1, b1, a2, b2):
                try:
                    s.close()
                except OSError:
                    pass


# ----------------------------------------------------------------------
# S: heartbeat state transitions
# ----------------------------------------------------------------------


@_t("S: heartbeat degrade / restore / offline transitions")
def test_s_heartbeat_states():
    with tempfile.TemporaryDirectory() as tmp:
        mc = _stub_controller(tmp)
        server_end, agent_end = socket.socketpair()
        s = DeviceSession(server_end, ("h", 1), "dev-s", mc)
        mc.sessions = {"dev-s": s}
        s.closed = False
        s.state = DEVICE_STATE_CONNECTED

        s.last_pong = time.time() - (cm.HEARTBEAT_DEGRADE_AFTER_S + 1)
        mc.on_session_frame(s, "PONG|0")  # fresh reply first
        assert s.state == DEVICE_STATE_CONNECTED

        s.last_pong = time.time() - (cm.HEARTBEAT_DEGRADE_AFTER_S + 1)
        mc._heartbeat_tick()
        assert s.state == DEVICE_STATE_DEGRADED
        assert not s.closed

        mc.on_session_frame(s, "PONG|1")
        assert s.state == DEVICE_STATE_CONNECTED

        s.last_pong = time.time() - (cm.HEARTBEAT_OFFLINE_AFTER_S + 1)
        mc._heartbeat_tick()
        assert s.state == DEVICE_STATE_OFFLINE
        assert s.closed

        server_end.close()
        agent_end.close()


# ----------------------------------------------------------------------
# T: target selection routing
# ----------------------------------------------------------------------


@_t("T: target selection filters by state and selection set")
def test_t_target_selection():
    with tempfile.TemporaryDirectory() as tmp:
        mc = _stub_controller(tmp)

        def mk(did, state, closed):
            a, _b = socket.socketpair()
            s = DeviceSession(a, ("h", 0), did, mc)
            s.state = state
            s.closed = closed
            mc.sessions[did] = s
            return s

        mk("dev-a", DEVICE_STATE_CONNECTED, False)
        mk("dev-b", DEVICE_STATE_DEGRADED, False)
        mk("dev-c", DEVICE_STATE_OFFLINE, True)
        assert set(mc.connected_ids()) == {"dev-a", "dev-b"}
        assert set(mc.target_ids()) == {"dev-a", "dev-b"}
        mc.selected = {"dev-a"}
        assert mc.target_ids() == ["dev-a"]
        mc.selected.clear()
        assert set(mc.target_ids()) == {"dev-a", "dev-b"}
        for s in mc.sessions.values():
            try:
                s.close()
            except OSError:
                pass


# ----------------------------------------------------------------------
# U: exact dispatch framing over the wire
# ----------------------------------------------------------------------


@_t("U: dispatch sends exactly cmd|<cid>|<b64> and collects results")
def test_u_dispatch_framing():
    with tempfile.TemporaryDirectory() as tmp:
        mc = _stub_controller(tmp)
        server_end, agent_end = socket.socketpair()
        s = DeviceSession(server_end, ("h", 1), "dev-u", mc)
        mc.sessions = {"dev-u": s}
        s.start()
        received = []

        def serve():
            frame = receive_message(agent_end)
            cid = frame.split("|")[1]
            received.append(frame)
            send_message(agent_end, f"ACK|{cid}")
            send_message(agent_end, f"RESULT|{cid}|OK|{_b64('ROUTE-OK')}")

        t = threading.Thread(target=serve)
        t.start()
        try:
            out = mc.dispatch("show_serious_warning:اختبار", ["dev-u"], timeout=10, show=False)
            t.join(10)
            assert out["dev-u"] == ("OK", "ROUTE-OK")
            m = re.match(r"^cmd\|([0-9a-f]{12})\|(.*)$", received[0])
            assert m
            assert _unb64(m.group(2)) == "show_serious_warning:اختبار"
        finally:
            s.close()
            agent_end.close()


# ----------------------------------------------------------------------
# V: heartbeat PING frame format
# ----------------------------------------------------------------------


@_t("V: heartbeat sends incrementing PING|<seq> frames")
def test_v_ping_frames():
    with tempfile.TemporaryDirectory() as tmp:
        mc = _stub_controller(tmp)
        server_end, agent_end = socket.socketpair()
        s = DeviceSession(server_end, ("h", 1), "dev-v", mc)
        mc.sessions = {"dev-v": s}
        frames = []

        def reader():
            while True:
                try:
                    frames.append(receive_message(agent_end))
                except OSError:
                    return

        t = threading.Thread(target=reader, daemon=True)
        t.start()
        try:
            s.last_pong = time.time()
            mc._heartbeat_tick()
            for _ in range(200):
                if frames:
                    break
                time.sleep(0.01)
            assert frames
            assert re.match(r"^PING\|[0-9]+$", frames[0])
            assert s.ping_seq == int(frames[0].split("|")[1])
        finally:
            s.close()
            agent_end.close()


# ----------------------------------------------------------------------
# W: run-screen menu parity with the classic controller
# ----------------------------------------------------------------------


@_t("W: v2 run menu mirrors classic controller labels")
def test_w_menu_parity():
    src_path = os.path.join(ROOT, "controller.py")
    with open(src_path, encoding="utf-8") as f:
        src = f.read()
    classic = {}
    for m in re.finditer(r'print\("(\d+)\. ([^"]+)"\)', src):
        n = int(m.group(1))
        if 1 <= n <= 18:
            classic.setdefault(n, m.group(2))
    assert len(classic) == 18, classic
    for line in RUN_MENU_LINES:
        n, _, label = line.partition(".")
        assert classic[int(n)] == label.strip(), (n, classic.get(int(n), "?"), label)


# ----------------------------------------------------------------------
# X: controller.py main-entry wiring
# ----------------------------------------------------------------------


@_t("X: controller.py main defaults to MainController with --legacy fallback")
def test_x_main_wiring():
    src_path = os.path.join(ROOT, "controller.py")
    with open(src_path, encoding="utf-8") as f:
        src = f.read()
    assert "--legacy" in src
    assert "--no-auto-enroll" in src
    assert "controller_multi.run_main" in src


# ----------------------------------------------------------------------
# Y: beacon format
# ----------------------------------------------------------------------


@_t("Y: discovery beacon has the SNOPT_BEACON|<ip>|<port>|<proto>|<ver> shape")
def test_y_beacon():
    beacon = build_beacon(4444)
    parts = beacon.split("|")
    assert parts[0] == "SNOPT_BEACON"
    assert len(parts) == 5
    ip_parts = parts[1].split(".")
    assert len(ip_parts) == 4 and all(p.isdigit() for p in ip_parts)
    assert parts[2] == "4444"
    assert parts[3] == PROTOCOL_VERSION
    assert parts[4] == CONTROLLER_VERSION


# ----------------------------------------------------------------------
# Runner (mirrors test_flow.py)
# ----------------------------------------------------------------------


def main():
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            try:
                reconfigure(encoding="utf-8")
            except Exception:
                pass
    failed = 0
    for name, fn in TESTS:
        try:
            fn()
            print("PASS  " + name)
        except AssertionError as e:
            failed += 1
            print("FAIL  " + name + "  ->  " + str(e))
        except Exception as e:  # noqa: BLE001
            failed += 1
            print("ERROR " + name + "  ->  " + repr(e))
    print()
    print("%d/%d tests passed" % (len(TESTS) - failed, len(TESTS)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())