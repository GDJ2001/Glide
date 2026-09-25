import os
import sys
import time
import json
import hashlib
import subprocess
import re
import threading
import base64
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE_DIR = r"D:\GITHub\Glide"
CORPUS_DIR = os.path.join(BASE_DIR, "build", "hard-017", "corpus")
MANIFEST_PATH = os.path.join(CORPUS_DIR, "corpus-manifest.json")
RUNS_DIR = os.path.join(BASE_DIR, "build", "hard-017", "runs")
LINUX_BIN = "/mnt/d/GITHub/Glide/build/hard-017/artifacts/linux/Glide.App"
ADB = r"C:\Users\GDJ\AppData\Local\Android\Sdk\platform-tools\adb.exe"
JAVA_EXE = r"C:\users\gdj\.gradle\jdks\jetbrains_s_r_o_-25-amd64-windows.2\bin\java.exe"

LINUX_SHA = "77e8940c23433838c4a8c869d88ff03351050ffbfa1e9927e36059ea49f3daab"
ANDROID_SHA = "c1c0cf0debc724640b4c3216697aadf00ade70b5522d72828ea4a1e8a7f0b30c"
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

def adb_cmd(args):
    return subprocess.run([ADB] + args, capture_output=True, check=True)

def dump_ui(remote_path="/data/local/tmp/dump.xml"):
    subprocess.run([ADB, "shell", f"rm -f {remote_path} && uiautomator dump {remote_path}"], capture_output=True)
    res = subprocess.run([ADB, "shell", f"cat {remote_path}"], capture_output=True)
    return res.stdout.decode('utf-8', errors='replace')

def find_bounds_center(xml_text, target_text, exact=False):
    try:
        root = ET.fromstring(xml_text)
        for node in root.iter("node"):
            text = node.attrib.get("text", "")
            desc = node.attrib.get("content-desc", "")
            matched = False
            if exact:
                matched = (text.strip() == target_text or desc.strip() == target_text)
            else:
                matched = (target_text.lower() in text.lower() or target_text.lower() in desc.lower())
            if matched:
                bounds = node.attrib.get("bounds", "")
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return (x1 + x2) // 2, (y1 + y2) // 2
    except Exception:
        pass
    return None

def get_invitation_port(uri):
    payload_part = uri.split("#")[1]
    padding = 4 - (len(payload_part) % 4)
    if padding != 4:
        payload_part += "=" * padding
    data = base64.urlsafe_b64decode(payload_part)
    inv = json.loads(data)
    return inv["endpoints"][0]["port"]

def main():
    run_id = "android-to-linux-success"
    print(f"\n=== [RUN] {run_id} ===", flush=True)

    run_dir = os.path.join(RUNS_DIR, run_id)
    os.makedirs(run_dir, exist_ok=True)
    sender_log_path = os.path.join(run_dir, "sender.log")
    receiver_log_path = os.path.join(run_dir, "receiver.log")

    # Clean Linux Downloads directory and kill old processes
    print("[0] Cleaning Linux Downloads, staging, and old processes...", flush=True)
    subprocess.run(["wsl", "sh", "-c", "killall -9 Glide.App 2>/dev/null; rm -f /home/geethikadilshan/Downloads/*.bin; rm -rf /home/geethikadilshan/Glide/staging/* /home/geethikadilshan/.local/share/glide/staging/*"], capture_output=True)

    # Ensure /etc/hosts has LAN IP
    hosts_res = subprocess.run(["wsl", "cat", "/etc/hosts"], capture_output=True, text=True)
    if "192.168.0.224" not in hosts_res.stdout:
        print("[0] Updating /etc/hosts in WSL to advertise 192.168.0.224...", flush=True)
        subprocess.run(["wsl", "-u", "root", "sed", "-i", "s/172.26.129.45/192.168.0.224/", "/etc/hosts"], check=True)

    adb_cmd(["logcat", "-c"])

    started_at = iso_now()
    requested_port = 43301

    # Step 1: Start Linux receiver
    print(f"[1] Starting Linux receiver on port {requested_port}...", flush=True)
    recv_proc = subprocess.Popen(
        ["wsl", "-e", LINUX_BIN, "receive", "--port", str(requested_port)],
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
                if "[BOOTSTRAP ACCEPTED]" in text_line or "[PROTOCOL] Connected" in text_line or "[IDENTITY]" in text_line or "Trust this device" in text_line:
                    def send_accept():
                        time.sleep(0.05)
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

    actual_port = get_invitation_port(pair_uri)
    print(f"    Linux receiver active port: {actual_port}", flush=True)
    print(f"    Linux pairing URI: {pair_uri[:50]}...", flush=True)

    # Step 2: Start Java TcpForwarder on Windows host (192.168.0.224:actual_port -> 127.0.0.1:actual_port)
    print(f"[2] Starting Java TcpForwarder: 192.168.0.224:{actual_port} -> 127.0.0.1:{actual_port}...", flush=True)
    fwd_proc = subprocess.Popen(
        [JAVA_EXE, "tools/TcpForwarder.java", "192.168.0.224", str(actual_port), "127.0.0.1", str(actual_port)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT
    )
    time.sleep(1.0)

    # Step 3: Put pairing URI into Android clipboard
    print("[3] Setting Android clipboard with pairing URI...", flush=True)
    adb_cmd(["shell", "input keyevent 224 && wm dismiss-keyguard"])
    adb_cmd(["shell", "wm user-rotation lock 0"])
    adb_cmd(["shell", "am force-stop dev.glide.app.debug"])
    time.sleep(0.5)

    clip_res = subprocess.run([ADB, "shell", f"app_process -Djava.class.path=/data/local/tmp/clipboard_helper.dex /data/local/tmp ClipboardHelper set '{pair_uri}'"], capture_output=True, text=True)
    print(f"    Clipboard set result: {clip_res.stdout.strip()}", flush=True)

    # Step 4: Launch SendCorpusHelper
    print("[4] Launching SendCorpusHelper on Android for 35 corpus files...", flush=True)
    send_intent_res = subprocess.run([ADB, "shell", "app_process -Djava.class.path=/data/local/tmp/send_corpus.dex /data/local/tmp SendCorpusHelper"], capture_output=True, text=True)
    print(f"    SendCorpusHelper: {send_intent_res.stdout.strip()}", flush=True)
    time.sleep(2.0)

    # Step 5: Flow through Android UI to connect
    print("[5] Stepping through Android UI: Proceed -> Paste -> Verify & connect...", flush=True)
    # Tap Proceed to send (35)
    for _ in range(20):
        xml_text = dump_ui("/data/local/tmp/ui_step.xml")
        center = find_bounds_center(xml_text, "Proceed to send")
        if center:
            print(f"    Tapping Proceed to send at {center}...", flush=True)
            adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
            time.sleep(1.0)
            break
        time.sleep(0.5)

    # Tap Scan QR or paste invite
    for _ in range(20):
        xml_text = dump_ui("/data/local/tmp/ui_step.xml")
        center = find_bounds_center(xml_text, "paste invite") or find_bounds_center(xml_text, "Scan QR")
        if center:
            print(f"    Tapping Scan QR or paste invite at {center}...", flush=True)
            adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
            time.sleep(1.0)
            break
        time.sleep(0.5)

    # Tap Paste invitation
    for _ in range(20):
        xml_text = dump_ui("/data/local/tmp/ui_step.xml")
        center = find_bounds_center(xml_text, "Paste invitation")
        if center:
            print(f"    Tapping Paste invitation button at {center}...", flush=True)
            adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
            time.sleep(1.0)
            break
        time.sleep(0.5)

    # Tap Paste from clipboard
    for _ in range(20):
        xml_text = dump_ui("/data/local/tmp/ui_step.xml")
        center = find_bounds_center(xml_text, "Paste from clipboard")
        if center:
            print(f"    Tapping Paste from clipboard at {center}...", flush=True)
            adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
            time.sleep(0.5)
            break
        time.sleep(0.5)

    # Tap Verify & connect
    for _ in range(20):
        xml_text = dump_ui("/data/local/tmp/ui_step.xml")
        center = find_bounds_center(xml_text, "Verify & connect")
        if center:
            print(f"    Tapping Verify & connect at {center}...", flush=True)
            adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
            time.sleep(1.0)
            break
        time.sleep(0.5)

    # Check for confirmation dialog ("Connect" / "Trust")
    for _ in range(20):
        xml_text = dump_ui("/data/local/tmp/ui_step.xml")
        center = find_bounds_center(xml_text, "Trust device") or find_bounds_center(xml_text, "Trust", exact=True) or find_bounds_center(xml_text, "Connect", exact=True)
        if center:
            print(f"    Tapping confirmation at {center}...", flush=True)
            adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
            time.sleep(0.5)
            break
        time.sleep(0.5)

    # Step 6: Wait for Linux receiver to complete
    print("[6] Waiting for transfer to complete on Linux receiver...", flush=True)
    recv_proc.wait(timeout=120)
    recv_thread.join(timeout=5)
    print(f"    Linux receiver exited with code {recv_proc.returncode}", flush=True)

    ended_at = iso_now()
    logcat_res = subprocess.run([ADB, "logcat", "-d", "-t", "2000"], capture_output=True)

    with open(sender_log_path, "wb") as f: f.write(logcat_res.stdout)
    with open(receiver_log_path, "wb") as f: f.write(recv_log_bytes)

    try:
        fwd_proc.kill()
    except Exception:
        pass

    if recv_proc.returncode != 0:
        raise RuntimeError(f"Linux receiver failed with code {recv_proc.returncode}")

    # Step 7: Verify SHA-256 hashes of all received files in Linux Downloads
    print("[7] Verifying received files in Linux Downloads...", flush=True)
    hash_res = subprocess.run(["wsl", "sh", "-c", "sha256sum /home/geethikadilshan/Downloads/*.bin"], capture_output=True, text=True)
    print("    sha256sum output lines:", len(hash_res.stdout.strip().splitlines()))
    
    file_hashes = {}
    for line in hash_res.stdout.strip().splitlines():
        parts = line.strip().split()
        if len(parts) >= 2:
            fname = os.path.basename(parts[1])
            file_hashes[fname] = parts[0].lower()

    all_match = True
    received_count = 0
    for item in MANIFEST["items"]:
        name = item["name"]
        expected_sha = item["sha256"].lower()
        if name not in file_hashes:
            print(f"    MISSING: {name}", flush=True)
            all_match = False
            continue
        actual_sha = file_hashes[name]
        if actual_sha != expected_sha:
            print(f"    MISMATCH for {name}: expected {expected_sha}, got {actual_sha}", flush=True)
            all_match = False
        else:
            received_count += 1

    print(f"    Verified {received_count}/35 files in Linux Downloads.", flush=True)
    if not all_match or received_count != 35:
        raise RuntimeError("Hash verification failed for received files on Linux!")

    print("    ALL 35 FILE HASHES MATCH 100% ON LINUX RECEIVER!", flush=True)

    run_json = {
        "schema": "glide-hard017-run-v1",
        "id": run_id,
        "scenario": "success",
        "senderPlatform": "android",
        "receiverPlatform": "linux",
        "observedResult": "pass",
        "repositoryHead": HEAD_SHA,
        "senderArtifactSha256": ANDROID_SHA,
        "receiverArtifactSha256": LINUX_SHA,
        "startedAtUtc": started_at,
        "endedAtUtc": ended_at,
        "corpusManifestSha256": MANIFEST_SHA,
        "itemCount": len(MANIFEST["items"]),
        "offerPageCount": MANIFEST["offerPageCount"],
        "allItemHashesMatch": True,
        "terminalBatchStatus": "success",
        "logs": [
            f"runs/{run_id}/sender.log",
            f"runs/{run_id}/receiver.log"
        ],
        "notes": "Physical Android device sent all 35 corpus files across 2 offer pages to Linux receiver in WSL2; all hashes verified 100% on Linux."
    }

    run_json_path = os.path.join(RUNS_DIR, f"{run_id}.json")
    with open(run_json_path, "w", encoding="utf-8") as f:
        json.dump(run_json, f, indent=2)
    print(f"[8] Wrote {run_json_path}", flush=True)
    print(f"=== [SUCCESS] {run_id} PASSED ===", flush=True)

if __name__ == "__main__":
    main()
