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
WIN_BIN = os.path.join(BASE_DIR, "build", "hard-017", "artifacts", "windows", "Glide.App.exe")
ADB = r"C:\Users\GDJ\AppData\Local\Android\Sdk\platform-tools\adb.exe"

WIN_SHA = "9d582a8fef63bfe5e0ad0e660a462eed2396e04e2d3cfe1c41a7784fbf4d86fb"
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

def main():
    run_id = "android-to-windows-success"
    print(f"\n=== [RUN] {run_id} ===", flush=True)

    run_dir = os.path.join(RUNS_DIR, run_id)
    os.makedirs(run_dir, exist_ok=True)
    sender_log_path = os.path.join(run_dir, "sender.log")
    receiver_log_path = os.path.join(run_dir, "receiver.log")

    # Clean Windows Downloads directory for test
    win_downloads = os.path.join(os.environ["USERPROFILE"], "Downloads")
    # Clean previous .bin files
    for fname in os.listdir(win_downloads):
        if fname.endswith(".bin"):
            try: os.remove(os.path.join(win_downloads, fname))
            except Exception: pass

    adb_cmd(["logcat", "-c"])

    started_at = iso_now()
    port = 43201

    # Step 1: Start Windows receiver
    print(f"[1] Starting Windows receiver on port {port}...", flush=True)
    recv_proc = subprocess.Popen(
        [WIN_BIN, "receive", "--port", str(port)],
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
                    print(f"    [WIN RECV] {text_line.rstrip()}", flush=True)
                m = re.search(r"(glide://pair/v1#[A-Za-z0-9_-]+)", text_line)
                if m and not pair_uri:
                    pair_uri = m.group(1)
                    uri_event.set()
                if "[BOOTSTRAP ACCEPTED]" in text_line or "[PROTOCOL] Connected" in text_line or "[IDENTITY]" in text_line:
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
        raise RuntimeError("Windows receiver failed to produce pairing URI")

    print(f"    Windows pairing URI: {pair_uri[:50]}...", flush=True)

    # Step 2: Put pairing URI into Android clipboard
    print("[2] Setting Android clipboard with pairing URI...", flush=True)
    adb_cmd(["shell", "input keyevent 224 && wm dismiss-keyguard"])
    adb_cmd(["shell", "wm user-rotation lock 0"])
    adb_cmd(["shell", "am force-stop dev.glide.app.debug"])
    time.sleep(0.5)

    clip_res = subprocess.run([ADB, "shell", f"app_process -Djava.class.path=/data/local/tmp/clipboard_helper.dex /data/local/tmp ClipboardHelper set '{pair_uri}'"], capture_output=True, text=True)
    print(f"    Clipboard set result: {clip_res.stdout.strip()}", flush=True)

    # Step 3: Launch SendCorpusHelper
    print("[3] Launching SendCorpusHelper on Android for 35 corpus files...", flush=True)
    send_intent_res = subprocess.run([ADB, "shell", "app_process -Djava.class.path=/data/local/tmp/send_corpus.dex /data/local/tmp SendCorpusHelper"], capture_output=True, text=True)
    print(f"    SendCorpusHelper: {send_intent_res.stdout.strip()}", flush=True)
    time.sleep(2.0)

    # Step 4: Flow through Android UI to connect
    print("[4] Stepping through Android UI: Proceed -> Paste -> Verify & connect...", flush=True)
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
        center = find_bounds_center(xml_text, "Trust device") or find_bounds_center(xml_text, "Trust") or find_bounds_center(xml_text, "Connect")
        if center:
            print(f"    Tapping confirmation at {center}...", flush=True)
            adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
            time.sleep(0.5)
            break
        time.sleep(0.5)

    # Step 5: Wait for Windows receiver to complete
    print("[5] Waiting for transfer to complete on Windows receiver...", flush=True)
    recv_proc.wait(timeout=120)
    recv_thread.join(timeout=5)
    print(f"    Windows receiver exited with code {recv_proc.returncode}", flush=True)

    ended_at = iso_now()
    logcat_res = subprocess.run([ADB, "logcat", "-d", "-t", "2000"], capture_output=True)

    with open(sender_log_path, "wb") as f: f.write(logcat_res.stdout)
    with open(receiver_log_path, "wb") as f: f.write(recv_log_bytes)

    if recv_proc.returncode != 0:
        raise RuntimeError(f"Windows receiver failed with code {recv_proc.returncode}")

    # Step 6: Verify SHA-256 hashes of all received files in Windows Downloads
    print("[6] Verifying received files in Windows Downloads...", flush=True)
    all_match = True
    received_count = 0
    for item in MANIFEST["items"]:
        name = item["name"]
        expected_sha = item["sha256"].lower()
        target_path = os.path.join(win_downloads, name)
        if not os.path.exists(target_path):
            print(f"    MISSING: {target_path}", flush=True)
            all_match = False
            continue
        actual_sha = sha256_file(target_path)
        if actual_sha != expected_sha:
            print(f"    MISMATCH for {name}: expected {expected_sha}, got {actual_sha}", flush=True)
            all_match = False
        else:
            received_count += 1

    print(f"    Verified {received_count}/35 files in Windows Downloads.", flush=True)
    if not all_match or received_count != 35:
        raise RuntimeError("Hash verification failed for received files on Windows!")

    print("    ALL 35 FILE HASHES MATCH 100% ON WINDOWS RECEIVER!", flush=True)

    run_json = {
        "schema": "glide-hard017-run-v1",
        "id": run_id,
        "scenario": "success",
        "senderPlatform": "android",
        "receiverPlatform": "windows",
        "observedResult": "pass",
        "repositoryHead": HEAD_SHA,
        "senderArtifactSha256": ANDROID_SHA,
        "receiverArtifactSha256": WIN_SHA,
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
        "notes": "Physical Android device sent all 35 corpus files across 2 offer pages to Windows receiver; all hashes verified 100% on Windows."
    }

    run_json_path = os.path.join(RUNS_DIR, f"{run_id}.json")
    with open(run_json_path, "w", encoding="utf-8") as f:
        json.dump(run_json, f, indent=2)
    print(f"[7] Wrote {run_json_path}", flush=True)
    print(f"=== [SUCCESS] {run_id} PASSED ===", flush=True)

if __name__ == "__main__":
    main()
