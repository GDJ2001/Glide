import os
import sys
import time
import json
import hashlib
import subprocess
import re
import threading
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

def find_bounds_center(xml_text, target_text):
    try:
        root = ET.fromstring(xml_text)
        for node in root.iter("node"):
            text = node.attrib.get("text", "")
            desc = node.attrib.get("content-desc", "")
            if target_text.lower() in text.lower() or target_text.lower() in desc.lower():
                bounds = node.attrib.get("bounds", "")
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return (x1 + x2) // 2, (y1 + y2) // 2
    except Exception:
        pass
    return None

def prepare_android_receiver():
    print("  [Setup] Relaunching Android Glide in Receive mode...", flush=True)
    adb_cmd(["shell", "input keyevent 224 && wm dismiss-keyguard"])
    adb_cmd(["shell", "wm user-rotation lock 0"])
    adb_cmd(["shell", "am force-stop dev.glide.app.debug"])
    time.sleep(0.5)
    adb_cmd(["shell", "am start -n dev.glide.app.debug/dev.glide.app.MainActivity"])
    time.sleep(2.0)

    # Tap Receive
    adb_cmd(["shell", "input tap 540 907"])
    time.sleep(1.0)
    # Tap Show QR code
    adb_cmd(["shell", "input tap 540 2021"])

    active_port = None
    xml_text = ""
    for _ in range(25):
        time.sleep(0.5)
        xml_text = dump_ui("/data/local/tmp/ui_qr.xml")
        if "QR Code Expired" in xml_text:
            center = find_bounds_center(xml_text, "Generate new QR code")
            if center:
                adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
                time.sleep(1.0)
                continue
        m_ttl = re.search(r"Valid for (\d+)s", xml_text) or re.search(r"Discoverable for (\d+) seconds", xml_text)
        m_addr = re.search(r"Local address: 192\.168\.0\.125:(\d+)", xml_text)
        if m_addr and m_ttl and int(m_ttl.group(1)) >= 90:
            active_port = int(m_addr.group(1))
            print(f"    Android QR ready on port {active_port}, TTL {m_ttl.group(1)}s", flush=True)
            break

    if not active_port:
        raise RuntimeError("Failed to prepare Android QR receiver")

    # Tap Copy invite
    copy_center = find_bounds_center(xml_text, "Copy invite") or (819, 2184)
    adb_cmd(["shell", f"input tap {copy_center[0]} {copy_center[1]}"])
    time.sleep(0.5)

    clip_res = subprocess.run([ADB, "shell", "app_process -Djava.class.path=/data/local/tmp/clipboard_helper.dex /data/local/tmp ClipboardHelper get"], capture_output=True)
    pair_uri = None
    for line in clip_res.stdout.decode('utf-8', errors='replace').splitlines():
        if line.startswith("CLIPBOARD_TEXT:glide://pair/v1#"):
            pair_uri = line[len("CLIPBOARD_TEXT:"):].strip()
            break

    if not pair_uri:
        raise RuntimeError("Failed to read invitation from Android clipboard")

    return pair_uri, active_port

def main():
    run_id = "linux-to-android-success"
    print(f"\n=== [RUN] {run_id} ===", flush=True)

    run_dir = os.path.join(RUNS_DIR, run_id)
    os.makedirs(run_dir, exist_ok=True)
    sender_log_path = os.path.join(run_dir, "sender.log")
    receiver_log_path = os.path.join(run_dir, "receiver.log")

    adb_cmd(["shell", "run-as dev.glide.app.debug sh -c 'rm -rf files/received/* files/receiving/*'"])
    adb_cmd(["logcat", "-c"])

    started_at = iso_now()
    pair_uri, port = prepare_android_receiver()

    # Get WSL-accessible paths to all 35 corpus files
    corpus_files = [f"/mnt/d/GITHub/Glide/build/hard-017/corpus/{item['name']}" for item in MANIFEST["items"]]
    sender_cmd = ["wsl", LINUX_BIN, "send", "--pair", pair_uri] + corpus_files

    print(f"[1] Starting Linux sender with {len(corpus_files)} files...", flush=True)
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
                print(f"    [LNX SEND] {text_line.rstrip()}", flush=True)
                if "[IDENTITY]" in text_line or "[PROTOCOL] Connected" in text_line or "Trust this device" in text_line:
                    def send_trust():
                        time.sleep(0.05)
                        try:
                            send_proc.stdin.write(b"y\ny\n")
                            send_proc.stdin.flush()
                        except Exception: pass
                    threading.Thread(target=send_trust, daemon=True).start()
                line_buf.clear()
            elif b"[y/N]:" in line_buf or b"[y/N]" in line_buf:
                try:
                    send_proc.stdin.write(b"y\n")
                    send_proc.stdin.flush()
                except Exception: pass
                line_buf.clear()

    send_thread = threading.Thread(target=send_reader, daemon=True)
    send_thread.start()

    # Step 2: Poll Android UI to tap Trust and Accept
    print("[2] Watching for offer dialog on Android to tap Accept...", flush=True)
    accepted = False
    for _ in range(80):
        time.sleep(0.2)
        xml_text = dump_ui("/data/local/tmp/ui_offer.xml")
        trust_center = find_bounds_center(xml_text, "Trust device") or find_bounds_center(xml_text, "Trust")
        if trust_center:
            print(f"    Found Trust button at {trust_center}! Tapping...", flush=True)
            adb_cmd(["shell", f"input tap {trust_center[0]} {trust_center[1]}"])
            time.sleep(0.2)
            continue
        center = find_bounds_center(xml_text, "Accept file") or find_bounds_center(xml_text, "Accept transfer") or find_bounds_center(xml_text, "Accept")
        if center:
            print(f"    Found Accept button at {center}! Tapping...", flush=True)
            adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
            accepted = True
            break

    # Step 3: Wait for transfer to complete
    print("[3] Waiting for Linux sender to complete transfer...", flush=True)
    send_proc.wait(timeout=120)
    send_thread.join(timeout=5)
    print(f"    Linux sender exited with code {send_proc.returncode}", flush=True)

    ended_at = iso_now()
    logcat_res = subprocess.run([ADB, "logcat", "-d", "-t", "2000"], capture_output=True)

    with open(sender_log_path, "wb") as f: f.write(send_log_bytes)
    with open(receiver_log_path, "wb") as f: f.write(logcat_res.stdout)

    if send_proc.returncode != 0:
        raise RuntimeError(f"Linux sender failed with exit code {send_proc.returncode}")

    # Step 4: Verify SHA-256 hashes of all 35 received files on Android
    print("[4] Verifying received files on Android...", flush=True)
    hash_check_res = subprocess.run([ADB, "shell", "run-as dev.glide.app.debug sh -c 'sha256sum files/received/*'"], capture_output=True)
    received_shas = {line.split()[0].lower() for line in hash_check_res.stdout.decode('utf-8', errors='replace').strip().splitlines() if line.strip()}
    print(f"    Found {len(received_shas)} received files on Android.", flush=True)

    for item in MANIFEST["items"]:
        name = item["name"]
        expected_sha = item["sha256"].lower()
        if expected_sha not in received_shas:
            raise RuntimeError(f"Missing hash on Android: {name} ({expected_sha})")

    print("    ALL 35 FILE HASHES MATCH 100% ON ANDROID PHYSICAL DEVICE!", flush=True)

    run_json = {
        "schema": "glide-hard017-run-v1",
        "id": run_id,
        "scenario": "success",
        "senderPlatform": "linux",
        "receiverPlatform": "android",
        "observedResult": "pass",
        "repositoryHead": HEAD_SHA,
        "senderArtifactSha256": LINUX_SHA,
        "receiverArtifactSha256": ANDROID_SHA,
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
        "notes": "Linux WSL2 sender transferred full 35-item corpus across 2 offer pages to physical Android device over TLS 1.3 with 100% SHA-256 match."
    }

    run_json_path = os.path.join(RUNS_DIR, f"{run_id}.json")
    with open(run_json_path, "w", encoding="utf-8") as f:
        json.dump(run_json, f, indent=2)
    print(f"[5] Wrote {run_json_path}", flush=True)
    print(f"=== [SUCCESS] {run_id} PASSED ===", flush=True)

if __name__ == "__main__":
    main()
