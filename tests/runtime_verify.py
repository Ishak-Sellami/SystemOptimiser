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


class RuntimeCheckController(PhoneController):
    """A real PhoneController that runs a scripted command sequence instead of
    the interactive menu, then exits the session cleanly."""

    def __init__(self):
        super().__init__()
        self.results = []
        self.cases = []

    def add(self, name, command, ok_fragments=None,
            check_activity=None, wait_activity=1):
        self.cases.append((name, command, list(ok_fragments or []),
                           check_activity, wait_activity))

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
        for name, command, ok_fragments, activity, wait in self.cases:
            try:
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
                shown = className in shot
                print(f"      activity shown -> {shown}  ({shot[:110]})")
                if status == "OK" and not shown:
                    status = "ACTIVITY_NOT_SHOWN"
                elif status == "OK" and shown:
                    status = "OK+ACTIVITY_SHOWN"
                if activity.endswith("BlockScreenActivity"):
                    time.sleep(7)
                    after = top_resumed()
                    expired = className not in after
                    print(f"      after auto-expire (5s) -> {after[:110]}")
                    print(f"      auto-expired            -> {expired}")
                    if status == "OK+ACTIVITY_SHOWN" and not expired:
                        status = "OK+SHOWN+NO_AUTO_EXPIRE"
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
    ctrl.add("block_screen:5 -> fullscreen activity + auto-expire",
             "block_screen:5",
             ok_fragments=["تم تشغيل حجب الشاشة"],
             check_activity="com.example.systemoptimizer.BlockScreenActivity",
             wait_activity=1)
    ctrl.add("block_screen invalid duration rejected",
             "block_screen:999999",
             ok_fragments=["بين 1 و 86400"])
    ctrl.add("serious warning -> fullscreen activity",
             "show_serious_warning:اختبار تحذير جدي",
             ok_fragments=["تم عرض رسالة التحذير"],
             check_activity="com.example.systemoptimizer.SeriousWarningActivity",
             wait_activity=1)
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    ctrl.add("serious warning empty rejected",
             "show_serious_warning:", ok_fragments=["نص التحذير فارغ"])
    ctrl.add("play_video missing resource (empty library)",
             "play_video:1", ok_fragments=["غير موجود في المكتبة"])
    ctrl.add("play_video invalid id rejected",
             "play_video:abc", ok_fragments=["رقم الفيديو غير صالح"])
    ctrl.add("remove_freefire honest outcome",
             "remove_freefire",
             ok_fragments=["SUCCESS", "NOT PERMITTED", "PACKAGE NOT FOUND", "COMMAND FAILED"])
    ctrl.add("disable_freefire honest outcome",
             "disable_freefire",
             ok_fragments=["SUCCESS", "NOT PERMITTED", "PACKAGE NOT FOUND", "COMMAND FAILED"])
    ctrl.add("network_deg preserved (5-field parse)",
             "network_deg:100:0:0:30",
             ok_fragments=["تم تطبيق خفض الشبكة", "لا يمكن تطبيق خفض الشبكة",
                           "تطبيق خفض الشبكة", "واجهة الشبكة"])
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