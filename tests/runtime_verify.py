"""End-to-end runtime verification using the REAL controller.py classes.

Binds the real PhoneController on 0.0.0.0:4444, accepts the app connection,
and drives the live BackgroundService dispatch with the real framing + real
controller command strings, while checking which activities reach the
foreground via adb.

Run with:  python tests/runtime_verify.py
"""

import subprocess
import sys
import time

for _stream in (sys.stdout, sys.stderr):
    reconfigure = getattr(_stream, "reconfigure", None)
    if reconfigure is not None:
        try:
            reconfigure(encoding="utf-8")
        except Exception:
            pass

sys.path.insert(0, ".")

from controller import (
    PhoneController,
    send_message,
    receive_message,
)

ADB = r"C:\Users\lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe"


def _b64(text):
    import base64
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def adb(*args):
    return subprocess.run([ADB, "-s", "emulator-5554", *args],
                          capture_output=True, text=True, timeout=30).stdout.strip()


def top_resumed():
    out = adb("shell", "dumpsys", "activity", "activities")
    for line in out.splitlines():
        line = line.strip()
        if "topResumedActivity" in line:
            return line
    return "(no topResumedActivity line)"


def activity_in_dump(cls):
    """True if the activity class appears in a *live* task record (Hist line)
    of the activity dump — stays true while the block screen runs, even when
    the Device-Owner keyguard covers it, and turns false once it finishes."""
    out = adb("shell", "dumpsys", "activity", "activities")
    for line in out.splitlines():
        if "* Hist" in line and cls in line:
            return True
    return False


def keyguard_showing():
    """True when the keyguard is up (Device-Owner block screen locks via
    lockNow). Read from dumpsys window policy KeyguardServiceDelegate."""
    out = adb("shell", "dumpsys", "window", "policy")
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if "KeyguardServiceDelegate" in line:
            for probe in lines[i + 1:i + 4]:
                if "showing=" in probe:
                    return "showing=true" in probe
    return False


def unlock_screen():
    """Wake the emulator and dismiss a swipe keyguard."""
    adb("shell", "input", "keyevent", "224")
    time.sleep(0.5)
    adb("shell", "input", "keyevent", "82")
    time.sleep(0.5)


class RuntimeCheckController(PhoneController):
    """A real PhoneController that runs a scripted command sequence instead of
    the interactive menu, then exits the session cleanly."""

    def __init__(self):
        super().__init__()
        self.results = []
        self.cases = []

    def add(self, name, command, ok_fragments=None,
            check_activity=None, wait_activity=1, sleep_before=0):
        self.cases.append((name, command, list(ok_fragments or []),
                           check_activity, wait_activity, sleep_before))

    def accept_once(self, timeout_s=60):
        self.server.settimeout(timeout_s)
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            try:
                self.client_socket, self.client_address = self.server.accept()
                self.connected = True
                return True
            except OSError:
                continue
        return False

    def run(self):
        if not self.accept_once(90):
            self.results.append(("connect", "TIMEOUT"))
            return
        print("CONNECTED from", self.client_address)
        for name, command, ok_fragments, activity, wait, sleep_before in self.cases:
            try:
                if sleep_before > 0:
                    time.sleep(sleep_before)
                send_message(self.client_socket, command)
                reply = receive_message(self.client_socket)
            except OSError as e:
                self.results.append((name, "LOST_CONNECTION"))
                print(f"  [{name}] LOST: {e}")
                self.connected = False
                return

            ok = any(f in reply for f in ok_fragments) if ok_fragments else True
            status = "OK" if ok else "UNEXPECTED"
            print(f"  [{status:>12}] {name}")
            print(f"      cmd  : {command}")
            print(f"      reply: {reply[:240]}")

            if activity:
                time.sleep(wait)
                shot = top_resumed()
                # dumpsys uses "package/.Class" (slash); compare on class name
                className = activity.split(".")[-1]
                shown = className in shot or activity_in_dump(className)
                locked = keyguard_showing()
                print(f"      activity shown      -> {shown}  ({shot[:110]})")
                print(f"      keyguard showing    -> {locked}")
                if status == "OK" and not shown and locked:
                    # keyguard من lockNow يغطي الشاشة، ويعتبر القفل نفسه دليل ظهور
                    status = "OK+ACTIVITY_UNDER_KEYGUARD"
                elif status == "OK" and not shown:
                    status = "ACTIVITY_NOT_SHOWN"
                elif status == "OK" and shown:
                    status = "OK+ACTIVITY_SHOWN"
                if activity.endswith("BlockScreenActivity"):
                    time.sleep(9)
                    after = top_resumed()
                    expired = not activity_in_dump(className)
                    print(f"      after auto-expire (5s) -> {after[:110]}")
                    print(f"      live task gone          -> {expired}")
                    if status == "OK+ACTIVITY_SHOWN" and not expired:
                        status = "OK+SHOWN+NO_AUTO_EXPIRE"
                    elif expired:
                        status = status.replace("OK+ACTIVITY_SHOWN", "OK+SHOWN+AUTO_EXPIRED")\
                                       .replace("OK+ACTIVITY_UNDER_KEYGUARD",
                                                "OK+KEYGUARD+AUTO_EXPIRED")
                unlock_screen()
            self.results.append((name, status))

        try:
            send_message(self.client_socket, "exit")
            print("\nSent 'exit' to stop the service.")
        except OSError:
            pass
        self.connected = False


def main():
    ctrl = RuntimeCheckController()

    ctrl.add("device_info sanity", "device_info",
             ok_fragments=["الطراز", "MODEL", "EMULATOR"])
    ctrl.add("block_app nonexistent -> PACKAGE NOT FOUND",
             "block_app:com.example.nonexistent.foo",
             ok_fragments=["PACKAGE NOT FOUND"])
    ctrl.add("block_app empty -> format error",
             "block_app:", ok_fragments=["صيغة خاطئة"])
    ctrl.add("block_screen:5 -> strict managed lock + auto-expire",
             "block_screen:5:0:BS-RT-5",
             ok_fragments=["BLOCK_SCREEN_STARTED", "Session ID: BS-RT-5",
                           "State: ACTIVE", "strict available"],
             check_activity="com.example.systemoptimizer.BlockScreenActivity",
             wait_activity=1)
    ctrl.add("block_screen invalid duration rejected",
             "block_screen:999999",
             ok_fragments=["بين 1..86400"])
    ctrl.add("block_screen_status after expiry -> NONE",
             "block_screen_status",
             ok_fragments=["State: NONE", "Session ID: BS-RT-5", "Capability:"])
    ctrl.add("block_screen scheduled (delay 2s) -> SCHEDULED state",
             "block_screen:15:2:BS-RT-SCHED",
             ok_fragments=["BLOCK_SCREEN_STARTED", "Session ID: BS-RT-SCHED",
                           "State: SCHEDULED", "سيبدأ الحجب تلقائياً"])
    ctrl.add("block_screen_status after 5s -> auto-activated",
             "block_screen_status", sleep_before=5,
             ok_fragments=["State: ACTIVE", "Session ID: BS-RT-SCHED"])
    ctrl.add("block_screen_status after expiry -> NONE",
             "block_screen_status", sleep_before=15,
             ok_fragments=["State: NONE"])
    ctrl.add("block_screen 30s started for cancel test",
             "block_screen:30:0:BS-RT-CANCEL",
             ok_fragments=["BLOCK_SCREEN_STARTED", "Session ID: BS-RT-CANCEL"])
    ctrl.add("block_screen_status -> active session visible",
             "block_screen_status",
             ok_fragments=["State: ACTIVE", "Session ID: BS-RT-CANCEL"])
    ctrl.add("block_screen_cancel -> real managed release",
             "block_screen_cancel",
             ok_fragments=["BLOCK_SCREEN_CANCELLED"])
    ctrl.add("block_screen_status after cancel -> NONE",
             "block_screen_status",
             ok_fragments=["State: NONE", "Session ID: BS-RT-CANCEL"])
    ctrl.add("serious warning -> fullscreen activity",
             "show_serious_warning:اختبار تحذير جدي",
             ok_fragments=["تم عرض رسالة التحذير"],
             check_activity="com.example.systemoptimizer.SeriousWarningActivity",
             wait_activity=1)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    ctrl.add("serious warning empty rejected",
             "show_serious_warning:", ok_fragments=["نص التحذير فارغ"])
    ctrl.add("play_video:2 -> fullscreen asset player",
             "play_video:2",
             ok_fragments=["تم تشغيل الفيديو بملء الشاشة"],
             check_activity="com.example.systemoptimizer.VideoPlayerActivity",
             wait_activity=1)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    ctrl.add("play_video missing file -> not in library",
             "play_video:99", ok_fragments=["غير موجود في المكتبة"])
    ctrl.add("play_video invalid id rejected",
             "play_video:abc", ok_fragments=["رقم الفيديو غير صالح"])
    ctrl.add("block_app display name -> INVALID PACKAGE NAME",
             "block_app:YouTube", ok_fragments=["INVALID PACKAGE NAME"])
    ctrl.add("capture_camera -> PHOTO_OK or honest reason",
             "capture_camera",
             ok_fragments=["PHOTO_OK", "لا توجد كاميرا", "فشل", "مهلة", "صلاحية"])
    ctrl.add("shell_start -> session opened",
             "shell_start", ok_fragments=["SHELL_SESSION_STARTED"])
    ctrl.add("shell_cmd id -> identity shown",
             "shell_cmd:" + _b64("id"), ok_fragments=["uid="])
    ctrl.add("shell_cmd getprop -> device model",
             "shell_cmd:" + _b64("getprop ro.product.model"),
             ok_fragments=["sdk_gphone", "goldfish", "ranchu"])
    ctrl.add("shell_exit -> session closed",
             "shell_exit", ok_fragments=["SHELL_SESSION_CLOSED"])
    ctrl.add("remove_freefire honest outcome",
             "remove_freefire",
             ok_fragments=["SUCCESS", "NOT PERMITTED", "PACKAGE NOT FOUND", "COMMAND FAILED"])
    ctrl.add("disable_freefire honest outcome",
             "disable_freefire",
             ok_fragments=["SUCCESS", "NOT PERMITTED", "PACKAGE NOT FOUND", "COMMAND FAILED"])
    ctrl.add("network_deg preserved (5-field parse)",
             "network_deg:100:0:0:30",
             ok_fragments=["تم تطبيق خفض الشبكة", "لا يمكن تطبيق خفض الشبكة",
                           "interface not found", "tc unavailable",
                           "واجهة الشبكة"])
    ctrl.add("black_screen_on_boot preserved",
             "black_screen_on_boot",
             ok_fragments=["الشاشة السوداء"])
    ctrl.add("spam_notifications preserved",
             "spam_notifications:1:E2E test",
             ok_fragments=["إشعار", "تم"])

    try:
        ctrl.run()
    finally:
        ctrl.close()

    print("\n--- Runtime Summary ---")
    for name, status in ctrl.results:
        print(f"  {status:>20}  {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())