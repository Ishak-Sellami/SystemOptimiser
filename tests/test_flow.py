"""Flow + contract tests for the AndroidOS controller/target project.

Since a real Android emulator is not available in this environment, the harness
verifies the parts that CAN be executed here:

  1. Parser / dispatch contract mirrors of the Android BackgroundService
     handlers (network_deg, play_sound, play_annoying_sound). These mirror the
     exact validation order/logic of the Java code so the parsing regression
     (network_deg with 5 fields was previously rejected as a format error) is
     covered.
  2. The real controller.py menu driven over a real TCP socket with patched
     input: verifies that menu option 5/8/15/16 build exactly the commands the
     Android side expects, and that invalid inputs never leave the controller.
  3. The res/raw sound library files exist and are valid WAV/MP3.
  4. No stale Wi-Fi / mobile-data toggle references remain in the Android code.

Run with:  python tests/test_flow.py
"""

import base64
import builtins
import io
import os
import queue
import socket
import struct
import sys
import threading
import contextlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from controller import (  # noqa: E402
    PhoneController,
    MESSAGE_DASHBOARD,
    SOUND_LIBRARY,
    receive_message,
    send_message,
)

RAW_DIR = os.path.join(ROOT, "app", "src", "main", "res", "raw")
SERVICE_PATH = os.path.join(
    ROOT, "app", "src", "main", "java",
    "com", "example", "systemoptimizer", "BackgroundService.java",
)

SOUND_COUNT = 4  # alarm, siren, warning, notification


# ---------------------------------------------------------------------------
# 1) Contract mirrors of the Android parsing/dispatch logic
# ---------------------------------------------------------------------------

def _safe_int_or_neg(s):
    try:
        return int(s.strip())
    except Exception:
        return -1


def android_parse_network_deg(command):
    """Mirror of BackgroundService.handleNetworkDegradation (post-fix).

    Returns (values_tuple, "OK") or (None, error_reason).
    """
    parts = command.split(":")
    if len(parts) < 5:
        return (None, "FORMAT_ERROR")
    latency = _safe_int_or_neg(parts[1])
    if latency < 0:
        return (None, "INVALID_LATENCY")
    loss = _safe_int_or_neg(parts[2])
    if loss < 0:
        return (None, "INVALID_LOSS")
    bandwidth = _safe_int_or_neg(parts[3])
    if bandwidth < 0:
        return (None, "INVALID_BANDWIDTH")
    duration = _safe_int_or_neg(parts[4])
    if duration < 0:
        return (None, "INVALID_DURATION")
    if latency == 0 and loss == 0 and bandwidth == 0:
        return (None, "ALL_ZERO")
    if duration <= 0 or duration > 86400:
        return (None, "DURATION_RANGE")
    if latency > 5000 or loss > 100 or bandwidth > 10000000:
        return (None, "RANGE")
    return ((latency, loss, bandwidth, duration), "OK")


def android_parse_play_sound(command):
    """Mirror of BackgroundService.handlePlaySound validation."""
    parts = command.split(":")
    if len(parts) < 3:
        return (None, "FORMAT_ERROR")
    sound_id = _safe_int_or_neg(parts[1])
    if sound_id < 1 or sound_id > SOUND_COUNT:
        return (None, "INVALID_ID")
    duration = _safe_int_or_neg(parts[2])
    if duration <= 0 or duration > 600:
        return (None, "INVALID_DURATION")
    return ((sound_id, duration), "OK")


def android_parse_annoying_sound(command):
    """Mirror of BackgroundService.handlePlayAnnoyingSound validation."""
    parts = command.split(":")
    if len(parts) < 2:
        return (None, "FORMAT_ERROR")
    duration = _safe_int_or_neg(parts[1])
    if duration <= 0 or duration > 600:
        return (None, "INVALID_DURATION")
    return ((duration,), "OK")


def test_network_deg_contract():
    # REGRESSION: the exact 5-field command that used to be rejected.
    vals, status = android_parse_network_deg("network_deg:2000:4:300:1")
    assert status == "OK", status
    assert vals == (2000, 4, 300, 1), vals

    _, status = android_parse_network_deg("network_deg:2000:4:300")          # 4 fields
    assert status == "FORMAT_ERROR", status

    for raw, expected in [
        ("network_deg:abc:4:300:1", "INVALID_LATENCY"),
        ("network_deg:-1:4:300:1", "INVALID_LATENCY"),
        ("network_deg:2000:abc:300:1", "INVALID_LOSS"),
        ("network_deg:2000:-5:300:1", "INVALID_LOSS"),
        ("network_deg:2000:4:abc:1", "INVALID_BANDWIDTH"),
        ("network_deg:2000:4:-10:1", "INVALID_BANDWIDTH"),
        ("network_deg:2000:4:300:1x", "INVALID_DURATION"),
        ("network_deg:2000:4:300:", "INVALID_DURATION"),                     # empty field
        ("network_deg:0:0:0:30", "ALL_ZERO"),
        ("network_deg:2000:4:300:0", "DURATION_RANGE"),
        ("network_deg:2000:4:300:86401", "DURATION_RANGE"),
        ("network_deg:6000:4:300:30", "RANGE"),                              # latency > 5000
        ("network_deg:2000:101:300:30", "RANGE"),                            # loss > 100
        ("network_deg:2000:4:10000001:30", "RANGE"),                         # bw > 10M
        ("network_deg:2000:4:10000000:30", None),                            # exact upper bound
    ]:
        _, status = android_parse_network_deg(raw)
        if expected is None:
            assert status == "OK", (raw, status)
        else:
            assert status == expected, (raw, status, expected)


def test_play_sound_contract():
    for raw, expected in [
        ("play_sound:1:10", "OK"),
        ("play_sound:4:600", "OK"),
        ("play_sound:1:1", "OK"),
        ("play_sound:0:10", "INVALID_ID"),
        ("play_sound:5:10", "INVALID_ID"),
        ("play_sound:9:10", "INVALID_ID"),
        ("play_sound:abc:10", "INVALID_ID"),
        ("play_sound:1:0", "INVALID_DURATION"),
        ("play_sound:1:601", "INVALID_DURATION"),
        ("play_sound:1:abc", "INVALID_DURATION"),
        ("play_sound:1", "FORMAT_ERROR"),
        ("play_sound:", "FORMAT_ERROR"),
    ]:
        _, status = android_parse_play_sound(raw)
        assert status == expected, (raw, status, expected)

    vals, status = android_parse_play_sound("play_sound:1:10")
    assert vals == (1, 10), vals

    # legacy command still parses
    vals, status = android_parse_annoying_sound("play_annoying_sound:10")
    assert status == "OK" and vals == (10,), (vals, status)
    _, status = android_parse_annoying_sound("play_annoying_sound:0")
    assert status == "INVALID_DURATION", status


def test_android_dispatch_branches_present():
    """The Java dispatcher must route the exact prefixes/commands we test."""
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["command.startsWith(\"network_deg:\")",
                  "command.startsWith(\"play_sound:\")",
                  "command.startsWith(\"play_annoying_sound:\")",
                  "command.equals(\"reboot_device\")",
                  "command.equals(\"shutdown_device\")"]:
        assert token in src, token


# ---------------------------------------------------------------------------
# 2) Real controller.py driven over a real TCP socket
# ---------------------------------------------------------------------------

def _run_controller_scenario(inputs):
    """Drive the real PhoneController.show_menu() with the given inputs.

    Returns (captured_commands, menu_stdout). An automatic "0" (exit) is not
    appended: callers are expected to include it as the last input.
    """
    captured = []
    out_buf = io.StringIO()

    request_q = queue.Queue()
    for item in inputs:
        request_q.put(item)

    real_input = builtins.input

    def fake_input(prompt=""):
        sys.stdout.write(prompt)
        try:
            return request_q.get(timeout=30)
        except queue.Empty:
            raise RuntimeError("scenario ran out of inputs: %r" % (inputs,))

    def remember_command(sock):
        while True:
            try:
                msg = receive_message(sock)
            except Exception:
                return
            captured.append(msg)
            try:
                send_message(sock, "ack:" + msg)
            except Exception:
                return

    # A connected socket pair: one end is the controller's device socket, the
    # other is the fake target. Each side owns its own fd, so recv never races.
    peer, device = socket.socketpair()
    ctrl = PhoneController(host="127.0.0.1", port=0)
    ctrl.client_socket = peer
    ctrl.connected = True
    threading.Thread(target=remember_command, args=(device,), daemon=True).start()

    builtins.input = fake_input
    controller_thread = threading.Thread(target=ctrl.show_menu, daemon=True)
    with contextlib.redirect_stdout(out_buf):
        try:
            controller_thread.start()
            controller_thread.join(timeout=60)
        finally:
            builtins.input = real_input

    try:
        device.close()
    except OSError:
        pass
    ctrl.close()
    return captured, out_buf.getvalue()


def test_scenario_network_deg_valid_exact():
    # Menu 8 > the exact param set that was historically broken by the
    # controller-building / Android-parsing mismatch.
    captured, out = _run_controller_scenario(["8", "2000", "4", "300", "1", "0"])
    assert captured == ["network_deg:2000:4:300:1", "exit"], captured
    assert "--- Control Panel ---" in out


def test_scenario_network_deg_empty_bandwidth():
    captured, _ = _run_controller_scenario(["8", "100", "0", "", "30", "0"])
    assert captured == ["network_deg:100:0:0:30", "exit"], captured


def test_scenario_network_deg_invalid_rejected():
    for bad_inputs in [
        ["8", "2000", "4", "abc", "30", "0"],
        ["8", "2000", "101", "300", "30", "0"],
        ["8", "2000", "4", "300", "0", "0"],
        ["8", "2000", "4", "300", "5x", "0"],
        ["8", "-1", "4", "300", "30", "0"],
        ["8", "6000", "4", "300", "30", "0"],
    ]:
        captured, _ = _run_controller_scenario(bad_inputs)
        assert captured == ["exit"], (bad_inputs, captured)


def test_scenario_network_deg_all_zero_rejected():
    captured, _ = _run_controller_scenario(["8", "0", "0", "", "30", "0"])
    assert captured == ["exit"], captured


def test_scenario_sound_valid():
    captured, out = _run_controller_scenario(["5", "1", "10", "0"])
    assert captured == ["play_sound:1:10", "exit"], captured
    assert "--- Available Sounds ---" in out
    assert "alarm.wav" in out


def test_scenario_sound_last_entry():
    captured, _ = _run_controller_scenario(["5", "4", "600", "0"])
    assert captured == ["play_sound:4:600", "exit"], captured


def test_scenario_sound_back_and_invalid():
    captured, _ = _run_controller_scenario(["5", "0", "0"])                   # back, then exit
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["5", "9", "0"])                   # invalid id
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["5", "1", "abc", "0"])            # invalid duration
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["5", "1", "0", "0"])              # 0 seconds
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["5", "1", "700", "0"])            # > 600 seconds
    assert captured == ["exit"], captured


def test_scenario_reboot_shutdown():
    captured, _ = _run_controller_scenario(["15", "0"])
    assert captured == ["reboot_device", "exit"], captured

    captured, _ = _run_controller_scenario(["16", "0"])
    assert captured == ["shutdown_device", "exit"], captured


def test_scenario_script_base64():
    # Menu 11 > a temporary script is read, base64-encoded with the chosen
    # delay, and sent as `script_base64:<delay>:<base64>`.
    script_path = os.path.join(HERE, "_tmp_script_test.sh")
    content = "echo hello\n"
    with open(script_path, "w", encoding="utf-8") as f:
        f.write(content)
    try:
        captured, _ = _run_controller_scenario(["11", script_path, "0", "0"])
    finally:
        try:
            os.remove(script_path)
        except OSError:
            pass

    assert len(captured) == 2 and captured[1] == "exit", captured
    prefix = "script_base64:0:"
    assert captured[0].startswith(prefix), captured
    decoded = base64.b64decode(captured[0][len(prefix):]).decode("utf-8")
    assert decoded == content, (decoded, content)


def test_scenario_message_dashboard():
    # Menu 1 > predefined message (index).
    captured, _ = _run_controller_scenario(["1", "3", "0"])
    assert captured == ["show_message:idx:3", "exit"], captured

    # Menu 1 > custom message (text).
    custom = len(MESSAGE_DASHBOARD) + 1
    captured, _ = _run_controller_scenario(["1", str(custom), "intercepted", "0"])
    assert captured == ["show_message:txt:intercepted", "exit"], captured

    # Menu 1 > invalid selection is rejected without sending a command.
    captured, _ = _run_controller_scenario(["1", "99", "0"])
    assert captured == ["exit"], captured


def test_sound_library_definition_order():
    # The controller library order must match the Android SOUND_LIBRARY order.
    assert len(SOUND_LIBRARY) == 4
    names = [name for name, _fn in SOUND_LIBRARY]
    assert names == ["Alarm", "Siren", "Warning", "Notification"], names


# ---------------------------------------------------------------------------
# 3) Sound library files
# ---------------------------------------------------------------------------

def test_sound_library_files():
    expected_names = ["alarm.wav", "siren.wav", "warning.wav", "notification.wav"]
    for name in expected_names:
        path = os.path.join(RAW_DIR, name)
        assert os.path.isfile(path), name
        with open(path, "rb") as f:
            head = f.read(12)
        assert head[:4] == b"RIFF" and head[8:12] == b"WAVE", (name, head)
        assert os.path.getsize(path) > 1000, name

    # legacy sound must not have been deleted
    legacy = os.path.join(RAW_DIR, "annoying_sound.mp3")
    assert os.path.isfile(legacy), "annoying_sound.mp3 was deleted"
    assert os.path.getsize(legacy) > 1000


# ---------------------------------------------------------------------------
# 4) No stale Wi-Fi / mobile-data toggle references
# ---------------------------------------------------------------------------

def test_no_stale_toggle_references():
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["toggle_wifi", "toggle_mobile_data", "WifiManager", "setWifiEnabled",
                  "toggle_network_data", "handleToggleMobileData"]:
        assert token not in src, token


# ---------------------------------------------------------------------------

TESTS = [
    ("network_deg parser contract", test_network_deg_contract),
    ("play_sound parser contract", test_play_sound_contract),
    ("android dispatch branches present", test_android_dispatch_branches_present),
    ("scenario: network_deg valid exact command", test_scenario_network_deg_valid_exact),
    ("scenario: network_deg empty bandwidth", test_scenario_network_deg_empty_bandwidth),
    ("scenario: network_deg invalid inputs rejected", test_scenario_network_deg_invalid_rejected),
    ("scenario: network_deg all-zero rejected", test_scenario_network_deg_all_zero_rejected),
    ("scenario: play sound valid", test_scenario_sound_valid),
    ("scenario: play sound last entry", test_scenario_sound_last_entry),
    ("scenario: play sound back/invalid", test_scenario_sound_back_and_invalid),
    ("scenario: reboot/shutdown commands", test_scenario_reboot_shutdown),
    ("scenario: run script base64 generation", test_scenario_script_base64),
    ("scenario: message dashboard commands", test_scenario_message_dashboard),
    ("sound library definition order", test_sound_library_definition_order),
    ("sound library files valid", test_sound_library_files),
    ("no stale wifi/mobile-data toggles", test_no_stale_toggle_references),
]


def main():
    _configure_utf8()
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


def _configure_utf8():
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            try:
                reconfigure(encoding="utf-8")
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())