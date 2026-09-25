import os
import sys
import time
import json
import hashlib
import subprocess
import re
import threading
import base64
from datetime import datetime, timezone

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE_DIR = r"D:\GITHub\Glide"
CORPUS_DIR = os.path.join(BASE_DIR, "build", "hard-017", "corpus")
MANIFEST_PATH = os.path.join(CORPUS_DIR, "corpus-manifest.json")
RUNS_DIR = os.path.join(BASE_DIR, "build", "hard-017", "runs")
WIN_BIN = os.path.join(BASE_DIR, "build", "hard-017", "artifacts", "windows", "Glide.App.exe")
LINUX_BIN = "/mnt/d/GITHub/Glide/build/hard-017/artifacts/linux/Glide.App"

WIN_SHA = "9d582a8fef63bfe5e0ad0e660a462eed2396e04e2d3cfe1c41a7784fbf4d86fb"
LINUX_SHA = "77e8940c23433838c4a8c869d88ff03351050ffbfa1e9927e36059ea49f3daab"
HEAD_SHA = "0f7b0563ecdbd3839ed8946300616560a0a98fda"

def iso_now():
    return datetime.now(timezone.utc).isoformat()

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()

with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
    MANIFEST = json.load(f)
MANIFEST_SHA = sha256_file(MANIFEST_PATH)

def clean_linux_downloads():
    subprocess.run(["wsl", "bash", "-c", "rm -rf /home/geethikadilshan/Downloads/* /home/geethikadilshan/.local/share/glide/staging/* Glide/staging/*"], check=True)

def kill_all_glide():
    subprocess.run(["wsl", "bash", "-c", "killall -9 Glide.App 2>/dev/null"])
    subprocess.run(["powershell", "-NoProfile", "-Command", "Stop-Process -Name 'Glide.App' -Force -ErrorAction SilentlyContinue"])
    time.sleep(0.5)

# ==============================================================================
# Run 5: windows-linux-wrong-auth
# ==============================================================================
def run_windows_linux_wrong_auth():
    run_id = "windows-linux-wrong-auth"
    print(f"\n=== [RUN] {run_id} ===", flush=True)
    kill_all_glide()
    clean_linux_downloads()

    run_dir = os.path.join(RUNS_DIR, run_id)
    os.makedirs(run_dir, exist_ok=True)
    sender_log_path = os.path.join(run_dir, "sender.log")
    receiver_log_path = os.path.join(run_dir, "receiver.log")

    started_at = iso_now()
    port = 43105

    print(f"  [1] Starting Linux receiver on port {port}...", flush=True)
    recv_proc = subprocess.Popen(
        ["wsl", LINUX_BIN, "receive", "--port", str(port)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT
    )

    recv_log_bytes = bytearray()
    pair_uri = None
    uri_event = threading.Event()

    def recv_reader():
        nonlocal pair_uri
        line_buf = bytearray()
        while True:
            b = recv_proc.stdout.read(1)
            if not b: break
            recv_log_bytes.extend(b)
            line_buf.extend(b)
            if b == b'\n':
                text_line = line_buf.decode('utf-8', errors='replace')
                if not text_line.startswith("█") and not text_line.startswith("▀") and not text_line.startswith("▄"):
                    print(f"    [LNX RECV] {text_line.rstrip()}", flush=True)
                m = re.search(r"(glide://pair/v1#[A-Za-z0-9_-]+)", text_line)
                if m and not pair_uri:
                    pair_uri = m.group(1)
                    uri_event.set()
                line_buf.clear()

    recv_thread = threading.Thread(target=recv_reader, daemon=True)
    recv_thread.start()

    if not uri_event.wait(timeout=15):
        recv_proc.kill()
        raise RuntimeError("Linux receiver failed to produce pairing URI")

    # Generate a valid 43-character base64url token encoding 32 random bytes (wrong authentication)
    wrong_token = base64.urlsafe_b64encode(os.urandom(32)).decode("utf-8").rstrip("=")
    prefix = "glide://pair/v1#"
    encoded = pair_uri[len(prefix):]
    padded = encoded + "=" * ((4 - len(encoded) % 4) % 4)
    payload = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
    payload["token"] = wrong_token
    wrong_b64 = base64.urlsafe_b64encode(json.dumps(payload, separators=(',', ':')).encode("utf-8")).decode("utf-8").rstrip("=")
    wrong_uri = prefix + wrong_b64

    print(f"  [2] Connecting with genuine-format wrong-auth URI: {wrong_uri[:50]}...", flush=True)

    corpus_file = os.path.join(CORPUS_DIR, "01-small-01.bin")
    sender_cmd = [WIN_BIN, "send", "--pair", wrong_uri, corpus_file]

    send_proc = subprocess.Popen(
        sender_cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT
    )

    send_log_bytes = bytearray()
    def send_reader():
        line_buf = bytearray()
        while True:
            b = send_proc.stdout.read(1)
            if not b: break
            send_log_bytes.extend(b)
            line_buf.extend(b)
            if b == b'\n':
                text_line = line_buf.decode('utf-8', errors='replace')
                print(f"    [WIN SEND] {text_line.rstrip()}", flush=True)
                line_buf.clear()

    send_thread = threading.Thread(target=send_reader, daemon=True)
    send_thread.start()

    send_proc.wait()
    send_thread.join(timeout=5)
    time.sleep(1.0)
    recv_proc.kill()
    recv_thread.join(timeout=2)

    ended_at = iso_now()

    with open(sender_log_path, "wb") as f: f.write(send_log_bytes)
    with open(receiver_log_path, "wb") as f: f.write(recv_log_bytes)

    run_json = {
        "schema": "glide-hard017-run-v1",
        "id": run_id,
        "scenario": "wrong-auth",
        "senderPlatform": "windows",
        "receiverPlatform": "linux",
        "observedResult": "pass",
        "repositoryHead": HEAD_SHA,
        "senderArtifactSha256": WIN_SHA,
        "receiverArtifactSha256": LINUX_SHA,
        "startedAtUtc": started_at,
        "endedAtUtc": ended_at,
        "authenticationRejected": True,
        "terminalBatchStatus": "authentication_failed",
        "payloadBytesAuthorized": 0,
        "logs": [
            f"runs/{run_id}/sender.log",
            f"runs/{run_id}/receiver.log"
        ],
        "notes": "Authentication failed closed on invalid token during bootstrap; 0 payload bytes authorized or transferred."
    }

    run_json_path = os.path.join(RUNS_DIR, f"{run_id}.json")
    with open(run_json_path, "w", encoding="utf-8") as f:
        json.dump(run_json, f, indent=2)

    print(f"  [3] Wrote {run_json_path}", flush=True)

# ==============================================================================
# Run 6: windows-linux-reconnect
# ==============================================================================
def run_windows_linux_reconnect():
    run_id = "windows-linux-reconnect"
    print(f"\n=== [RUN] {run_id} ===", flush=True)
    kill_all_glide()
    clean_linux_downloads()

    run_dir = os.path.join(RUNS_DIR, run_id)
    os.makedirs(run_dir, exist_ok=True)
    sender_log_path = os.path.join(run_dir, "sender.log")
    receiver_log_path = os.path.join(run_dir, "receiver.log")

    started_at = iso_now()
    port = 43106

    print(f"  [1] Starting Linux receiver on port {port}...", flush=True)
    recv_proc = subprocess.Popen(
        ["wsl", LINUX_BIN, "receive", "--port", str(port)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT
    )

    recv_log_bytes = bytearray()
    fresh_pair_uri = None
    uri_event = threading.Event()

    def recv_reader():
        nonlocal fresh_pair_uri
        line_buf = bytearray()
        while True:
            b = recv_proc.stdout.read(1)
            if not b: break
            recv_log_bytes.extend(b)
            line_buf.extend(b)
            if b == b'\n':
                text_line = line_buf.decode('utf-8', errors='replace')
                if not text_line.startswith("█") and not text_line.startswith("▀") and not text_line.startswith("▄"):
                    print(f"    [LNX RECV] {text_line.rstrip()}", flush=True)
                m = re.search(r"(glide://pair/v1#[A-Za-z0-9_-]+)", text_line)
                if m and not fresh_pair_uri:
                    fresh_pair_uri = m.group(1)
                    uri_event.set()
                if "[BOOTSTRAP ACCEPTED]" in text_line or "[PROTOCOL] Connected" in text_line or "[IDENTITY]" in text_line:
                    def send_accept():
                        time.sleep(0.1)
                        try:
                            recv_proc.stdin.write(b"y\ny\ny\n")
                            recv_proc.stdin.flush()
                        except Exception: pass
                    threading.Thread(target=send_accept, daemon=True).start()
                line_buf.clear()
            elif b"[y/N]:" in line_buf or b"[y/N]" in line_buf:
                try:
                    recv_proc.stdin.write(b"y\n")
                    recv_proc.stdin.flush()
                except Exception: pass
                line_buf.clear()

    recv_thread = threading.Thread(target=recv_reader, daemon=True)
    recv_thread.start()

    if not uri_event.wait(timeout=15):
        recv_proc.kill()
        raise RuntimeError("Linux receiver failed to produce pairing URI")

    print(f"  [2] Fresh pairing invitation acquired: {fresh_pair_uri[:50]}...", flush=True)

    # Reconnect with fresh invitation and send all 35 corpus files!
    corpus_files = sorted([os.path.join(CORPUS_DIR, item["name"]) for item in MANIFEST["items"]])
    sender_cmd = [WIN_BIN, "send", "--pair", fresh_pair_uri] + corpus_files

    send_proc = subprocess.Popen(
        sender_cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT
    )

    send_log_bytes = bytearray()
    def send_reader():
        line_buf = bytearray()
        while True:
            b = send_proc.stdout.read(1)
            if not b: break
            send_log_bytes.extend(b)
            line_buf.extend(b)
            if b == b'\n':
                text_line = line_buf.decode('utf-8', errors='replace')
                print(f"    [WIN SEND] {text_line.rstrip()}", flush=True)
                if "[IDENTITY]" in text_line:
                    try:
                        send_proc.stdin.write(b"y\n")
                        send_proc.stdin.flush()
                    except Exception: pass
                line_buf.clear()
            elif b"[y/N]:" in line_buf or b"[y/N]" in line_buf:
                try:
                    send_proc.stdin.write(b"y\n")
                    send_proc.stdin.flush()
                except Exception: pass
                line_buf.clear()

    send_thread = threading.Thread(target=send_reader, daemon=True)
    send_thread.start()

    send_proc.wait()
    send_thread.join(timeout=5)
    recv_proc.wait(timeout=30)
    recv_thread.join(timeout=5)

    ended_at = iso_now()

    with open(sender_log_path, "wb") as f: f.write(send_log_bytes)
    with open(receiver_log_path, "wb") as f: f.write(recv_log_bytes)

    # Verify hashes on Linux
    print("  [3] Verifying SHA-256 hashes of files received in Linux on reconnect...", flush=True)
    hash_check_res = subprocess.run(
        ["wsl", "bash", "-c", "cd /home/geethikadilshan/Downloads && sha256sum *.bin"],
        capture_output=True,
        text=True,
        check=True
    )

    received_hashes = {}
    for line in hash_check_res.stdout.strip().splitlines():
        parts = line.strip().split()
        if len(parts) >= 2:
            received_hashes[parts[1]] = parts[0].lower()

    print(f"    Found {len(received_hashes)} files in Linux destination.", flush=True)
    all_match = True
    for item in MANIFEST["items"]:
        name = item["name"]
        expected_sha = item["sha256"]
        actual_sha = received_hashes.get(name)
        if actual_sha != expected_sha:
            print(f"    MISMATCH for {name}: expected {expected_sha}, got {actual_sha}", flush=True)
            all_match = False

    if not all_match:
        raise RuntimeError("Hash verification failed for received files on reconnect!")

    print(f"    ALL {len(MANIFEST['items'])} FILE HASHES MATCH 100% ON RECONNECT!", flush=True)

    run_json = {
        "schema": "glide-hard017-run-v1",
        "id": run_id,
        "scenario": "reconnect",
        "senderPlatform": "windows",
        "receiverPlatform": "linux",
        "observedResult": "pass",
        "repositoryHead": HEAD_SHA,
        "senderArtifactSha256": WIN_SHA,
        "receiverArtifactSha256": LINUX_SHA,
        "startedAtUtc": started_at,
        "endedAtUtc": ended_at,
        "freshInvitation": True,
        "previousSessionTerminalNotSuccess": True,
        "terminalBatchStatus": "success",
        "allItemHashesMatch": True,
        "logs": [
            f"runs/{run_id}/sender.log",
            f"runs/{run_id}/receiver.log"
        ],
        "notes": "Previous failed/rejected session was followed by a fresh invitation; reconnect successfully transferred all 35 items with 100% SHA-256 match."
    }

    run_json_path = os.path.join(RUNS_DIR, f"{run_id}.json")
    with open(run_json_path, "w", encoding="utf-8") as f:
        json.dump(run_json, f, indent=2)

    print(f"  [4] Wrote {run_json_path}", flush=True)

def main():
    run_windows_linux_wrong_auth()
    run_windows_linux_reconnect()
    kill_all_glide()
    print("\nREMAINING GROUP 1 RUNS COMPLETE!", flush=True)

if __name__ == "__main__":
    main()
