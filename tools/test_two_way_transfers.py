import os
import sys
import time
import json
import re
import threading
import hashlib
import subprocess
import urllib.request

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

VM_AGENT = "http://127.0.0.1:9000"

def log(msg):
    print(msg, flush=True)

def vm_exec(cmd):
    req = urllib.request.Request(
        f"{VM_AGENT}/exec",
        data=json.dumps({"cmd": cmd}).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()

def run_test_windows_to_linux():
    log("\n=======================================================")
    log("TEST A: Windows Sender -> Linux UI Receiver")
    log("=======================================================")
    
    # 1. Clean Linux destination
    log("1. Cleaning Linux destination directory...")
    vm_exec("killall -9 Glide.Linux.UI || true; rm -rf /home/gdj/Downloads/Glide/* /home/gdj/Downloads/win_test_file.txt")

    # 2. Launch Glide.Linux.UI with --listen and --auto-confirm
    log("2. Launching Glide.Linux.UI on VM (Listening mode)...")
    launch_cmd = (
        "export DISPLAY=:0; export WAYLAND_DISPLAY=wayland-0; export XDG_RUNTIME_DIR=/run/user/1000; "
        "rm -f /tmp/glide_active_invitation.uri; "
        "nohup /home/gdj/glide-bin/Glide.Linux.UI --listen --auto-confirm > /tmp/glide_recv_ui.log 2>&1 &"
    )
    vm_exec(launch_cmd)
    time.sleep(3)

    # 3. Read active invitation URI from VM
    inv_uri = None
    for _ in range(10):
        res = vm_exec("cat /tmp/glide_active_invitation.uri")
        stdout = res.get("stdout", "").strip()
        if stdout.startswith("glide://pair/v1#"):
            inv_uri = stdout
            break
        time.sleep(1)

    if not inv_uri:
        log("[FAIL] Could not read active invitation URI from Linux VM.")
        return False

    log(f"3. Active Linux UI Invitation URI:\n   {inv_uri}")

    test_file = r"C:\Users\GDJ\Downloads\Shared\win_test_file.txt"
    with open(test_file, "w", encoding="utf-8") as f:
        f.write("Hello from Windows Glide sender to Linux UI receiver! SHA256 test payload string.")
    
    expected_sha = sha256_file(test_file)
    log(f"4. Source file SHA-256: {expected_sha}")

    log("5. Executing Windows Glide.App send with --connect 127.0.0.1:42424...")
    send_proc = subprocess.Popen(
        [r"build\hard-017\artifacts\windows\glide.app.exe", "send", "--pair", inv_uri, "--connect", "127.0.0.1:42424", test_file],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        cwd=r"d:\GITHub\Glide"
    )

    stdout, _ = send_proc.communicate(timeout=45)
    log("--- Windows Send Output ---")
    log(stdout.strip())
    log(f"Send Process Exit Code: {send_proc.returncode}")

    if send_proc.returncode != 0:
        log("[FAIL] Windows Send failed.")
        return False

    # Verify on Linux VM
    log("6. Verifying received file on Linux VM...")
    check_res = vm_exec("sha256sum /home/gdj/Downloads/Glide/* 2>/dev/null")
    log("Linux destination hashes:\n" + check_res.get("stdout", ""))

    if expected_sha in check_res.get("stdout", ""):
        log("[PASS] TEST A PASSED: Windows -> Linux UI transfer completed and SHA-256 verified!")
        return True
    else:
        log("[FAIL] Hash verification failed on Linux VM!")
        return False

def run_test_linux_to_windows():
    log("\n=======================================================")
    log("TEST B: Linux UI Sender -> Windows Receiver")
    log("=======================================================")

    win_dest = os.path.join(os.environ["USERPROFILE"], "Downloads")
    # Clean previous files if any
    for name in ["test_payload.txt", "test_sample2.txt"]:
        p = os.path.join(win_dest, name)
        if os.path.exists(p):
            os.remove(p)

    log("1. Starting Windows Receiver on port 43101...")
    recv_proc = subprocess.Popen(
        [r"build\hard-017\artifacts\windows\glide.app.exe", "receive", "--port", "43101"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        cwd=r"d:\GITHub\Glide"
    )

    pair_uri = None
    uri_event = threading.Event()
    transfer_event = threading.Event()

    def recv_worker():
        nonlocal pair_uri
        line_buf = bytearray()
        while True:
            b = recv_proc.stdout.read(1)
            if not b:
                break
            line_buf.extend(b)
            if b == b'\n':
                text_line = line_buf.decode('utf-8', errors='replace').strip()
                if not text_line.startswith("█") and not text_line.startswith("▀") and not text_line.startswith("▄") and text_line:
                    log(f"[WinRecv] {text_line}")
                m = re.search(r"(glide://pair/v1#[A-Za-z0-9_-]+)", text_line)
                if m and not pair_uri:
                    pair_uri = m.group(1)
                    uri_event.set()
                if "Transfer complete" in text_line or "Received" in text_line or "[VERIFIED]" in text_line:
                    transfer_event.set()
                line_buf.clear()
            elif b"[y/N]:" in line_buf or b"[y/N]" in line_buf:
                prompt_text = line_buf.decode('utf-8', errors='replace')
                log(f"[WinRecv Prompt] {prompt_text} -> responding 'y'")
                try:
                    recv_proc.stdin.write(b"y\n")
                    recv_proc.stdin.flush()
                except Exception:
                    pass
                line_buf.clear()

    recv_thread = threading.Thread(target=recv_worker, daemon=True)
    recv_thread.start()

    log("2. Waiting for Windows pairing URI...")
    if not uri_event.wait(timeout=20):
        log("[FAIL] Timed out waiting for pairing URI from Windows receiver.")
        recv_proc.kill()
        return False

    log(f"   Windows Receiver Invitation URI:\n   {pair_uri}")

    # Launch Linux UI sender
    log("3. Launching Linux UI on VM to send staged files...")
    send_cmd = (
        "killall -9 Glide.Linux.UI || true; sleep 1; "
        "export DISPLAY=:0; export WAYLAND_DISPLAY=wayland-0; export XDG_RUNTIME_DIR=/run/user/1000; "
        f"nohup /home/gdj/glide-bin/Glide.Linux.UI --pair \"{pair_uri}\" --auto-confirm --send "
        "/home/gdj/test_payload.txt /home/gdj/test_sample2.txt > /tmp/glide_send_ui.log 2>&1 &"
    )
    vm_exec(send_cmd)

    log("4. Waiting for transfer completion...")
    transfer_event.wait(timeout=25)
    time.sleep(2)

    recv_proc.terminate()
    try:
        recv_proc.wait(timeout=3)
    except Exception:
        recv_proc.kill()

    # Get source hashes from VM
    src_hashes = vm_exec("sha256sum /home/gdj/test_payload.txt /home/gdj/test_sample2.txt")["stdout"]
    log("5. Source files on Linux VM:\n" + src_hashes)

    f1 = os.path.join(win_dest, "test_payload.txt")
    f2 = os.path.join(win_dest, "test_sample2.txt")
    log(f"6. Checking destination files in {win_dest}...")
    
    if os.path.exists(f1) and os.path.exists(f2):
        h1 = sha256_file(f1)
        h2 = sha256_file(f2)
        log(f"   Received test_payload.txt:  {h1}")
        log(f"   Received test_sample2.txt:  {h2}")
        if h1 in src_hashes and h2 in src_hashes:
            log("[PASS] TEST B PASSED: Linux UI -> Windows transfer completed and SHA-256 verified!")
            return True
        else:
            log("[FAIL] Received file hashes do not match source files!")
            return False
    else:
        log(f"[FAIL] Transferred files not found in {win_dest}!")
        return False

def main():
    log("#######################################################")
    log("Glide Cross-Platform Linux UI Transfer Verification")
    log("#######################################################")

    pass_a = run_test_windows_to_linux()
    pass_b = run_test_linux_to_windows()

    log("\n=======================================================")
    log("FINAL TRANSFER VERIFICATION RESULTS:")
    log(f"  Test A (Windows -> Linux UI): {'PASS' if pass_a else 'FAIL'}")
    log(f"  Test B (Linux UI -> Windows): {'PASS' if pass_b else 'FAIL'}")
    log("=======================================================")

    return 0 if (pass_a and pass_b) else 1

if __name__ == "__main__":
    sys.exit(main())
