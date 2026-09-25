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
    except Exception as e:
        pass
    return None

def main():
    run_id = "windows-to-android-success"
    print(f"\n=== [RUN] {run_id} ===", flush=True)

    run_dir = os.path.join(RUNS_DIR, run_id)
    os.makedirs(run_dir, exist_ok=True)
    sender_log_path = os.path.join(run_dir, "sender.log")
    receiver_log_path = os.path.join(run_dir, "receiver.log")

    started_at = iso_now()

    # Step 1: Clean Android received directory and logcat
    print("[1] Cleaning Android received files, old dumps, and logcat...", flush=True)
    adb_cmd(["shell", "run-as dev.glide.app.debug rm -rf files/received/* files/receiving/*"])
    adb_cmd(["shell", "rm -f /data/local/tmp/*.xml /data/local/tmp/*.hprof"])
    adb_cmd(["logcat", "-c"])

    # Step 2: Ensure portrait and launch Glide
    print("[2] Launching Glide MainActivity on phone...", flush=True)
    adb_cmd(["shell", "input keyevent 224 && wm dismiss-keyguard"])
    adb_cmd(["shell", "wm user-rotation lock 0"])
    adb_cmd(["shell", "am force-stop dev.glide.app.debug"])
    time.sleep(0.5)
    adb_cmd(["shell", "am start -n dev.glide.app.debug/dev.glide.app.MainActivity"])
    time.sleep(2.0)

    # Step 3: Tap Receive button
    print("[3] Tapping Receive files button on phone...", flush=True)
    adb_cmd(["shell", "input tap 540 907"])
    time.sleep(1.0)

    # Step 4: Tap Show QR code
    print("[4] Tapping Show QR code button...", flush=True)
    adb_cmd(["shell", "input tap 540 2021"])

    # Step 5: Wait for fresh QR code readiness and active port
    print("[5] Waiting for fresh QR code readiness...", flush=True)
    active_port = None
    for attempt in range(25):
        time.sleep(0.5)
        xml_text = dump_ui("/data/local/tmp/ui_qr.xml")
        if "QR Code Expired" in xml_text:
            center = find_bounds_center(xml_text, "Generate new QR code")
            if center:
                print(f"    Tapping Generate new QR code at {center}...", flush=True)
                adb_cmd(["shell", f"input tap {center[0]} {center[1]}"])
                time.sleep(1.0)
                continue

        m_ttl = re.search(r"Valid for (\d+)s", xml_text) or re.search(r"Discoverable for (\d+) seconds", xml_text)
        m_addr = re.search(r"Local address: 192\.168\.0\.125:(\d+)", xml_text)
        if m_addr and m_ttl:
            ttl_val = int(m_ttl.group(1))
            if ttl_val >= 90:
                active_port = int(m_addr.group(1))
                print(f"    Fresh QR Ready! Endpoint: 192.168.0.125:{active_port}, TTL: {ttl_val}s", flush=True)
                break

    if not active_port:
        raise RuntimeError("Failed to detect fresh active QR endpoint on Android")

    # Step 6: Extract LAN invitation (via Copy invite button or heap dump fallback)
    print("[6] Extracting LAN invitation...", flush=True)
    pair_uri = None
    copy_center = find_bounds_center(xml_text, "Copy invite") or find_bounds_center(xml_text, "Copy invitation")
    if not copy_center:
        copy_center = (783, 2178)
    print(f"    Tapping Copy invite at {copy_center}...", flush=True)
    adb_cmd(["shell", f"input tap {copy_center[0]} {copy_center[1]}"])
    time.sleep(0.5)

    clip_res = subprocess.run([ADB, "shell", "app_process -Djava.class.path=/data/local/tmp/clipboard_helper.dex /data/local/tmp ClipboardHelper get"], capture_output=True)
    clip_out = clip_res.stdout.decode('utf-8', errors='replace').strip()
    for line in clip_out.splitlines():
        if line.startswith("CLIPBOARD_TEXT:glide://pair/v1#"):
            pair_uri = line[len("CLIPBOARD_TEXT:"):].strip()
            print(f"    Obtained invitation URI from Android clipboard!", flush=True)
            break

    if not pair_uri:
        print("    Clipboard empty, falling back to heap extraction...", flush=True)
        hprof_phone = "/data/local/tmp/heap_w2a.hprof"
        hprof_local = os.path.join(BASE_DIR, "tools", "heap_w2a.hprof")
        adb_cmd(["shell", f"am dumpheap dev.glide.app.debug {hprof_phone}"])

        last_size = -1
        for _ in range(20):
            time.sleep(0.5)
            res = subprocess.run([ADB, "shell", f"ls -l {hprof_phone}"], capture_output=True)
            out = res.stdout.decode('utf-8', errors='replace')
            parts = out.strip().split()
            if len(parts) >= 5 and parts[4].isdigit():
                size = int(parts[4])
                if size > 30_000_000 and size == last_size:
                    break
                last_size = size

        adb_cmd(["pull", hprof_phone, hprof_local])

        sys.path.append(os.path.join(BASE_DIR, "tools"))
        from extract_lan_invitation import extract_invitation_from_hprof
        pair_uri, payload = extract_invitation_from_hprof(hprof_local, target_port=active_port)

    print(f"    Pair URI: {pair_uri[:50]}...", flush=True)

    # Step 7: Start Windows sender
    corpus_files = sorted([os.path.join(CORPUS_DIR, item["name"]) for item in MANIFEST["items"]])
    sender_cmd = [WIN_BIN, "send", "--pair", pair_uri] + corpus_files

    print(f"[7] Starting Windows sender with {len(corpus_files)} files...", flush=True)
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

    # Step 8: Poll Android UI to tap Trust and Accept
    print("[8] Watching for incoming offer on Android to tap Accept...", flush=True)
    accepted = False
    for attempt in range(80):
        time.sleep(0.2)
        xml_text = dump_ui("/data/local/tmp/ui_offer.xml")
        trust_center = find_bounds_center(xml_text, "Trust device") or find_bounds_center(xml_text, "Trust")
        if trust_center:
            tx, ty = trust_center
            print(f"    Found Trust button at ({tx}, {ty})! Tapping...", flush=True)
            adb_cmd(["shell", f"input tap {tx} {ty}"])
            time.sleep(0.2)
            continue
        center = find_bounds_center(xml_text, "Accept file") or find_bounds_center(xml_text, "Accept transfer") or find_bounds_center(xml_text, "Accept")
        if center:
            cx, cy = center
            print(f"    Found Accept button at ({cx}, {cy})! Tapping...", flush=True)
            adb_cmd(["shell", f"input tap {cx} {cy}"])
            accepted = True
            break

    if not accepted:
        print("    [INFO] Accept button not found in UI dump (may have auto-progressed).", flush=True)

    # Step 9: Wait for Windows sender to complete transfer
    print("[9] Waiting for transfer to complete...", flush=True)
    send_proc.wait()
    send_thread.join(timeout=5)
    print(f"    Windows sender exited with code {send_proc.returncode}", flush=True)

    # Always capture logs
    time.sleep(2.0)
    ended_at = iso_now()

    logcat_res = subprocess.run([ADB, "logcat", "-d", "-t", "1000"], capture_output=True)
    recv_log_text = logcat_res.stdout.decode('utf-8', errors='replace')

    with open(sender_log_path, "wb") as f:
        f.write(send_log_bytes)
    with open(receiver_log_path, "w", encoding="utf-8") as f:
        f.write(recv_log_text)

    if send_proc.returncode != 0:
        raise RuntimeError(f"Windows sender failed with exit code {send_proc.returncode}")

    # Step 10: Verify SHA-256 hashes of all 35 received files on Android
    print("[10] Verifying received files on Android...", flush=True)
    hash_check_res = subprocess.run([ADB, "shell", "run-as dev.glide.app.debug sh -c 'sha256sum files/received/*'"],
                                    capture_output=True)
    hash_out = hash_check_res.stdout.decode('utf-8', errors='replace')

    received_shas = set()
    for line in hash_out.strip().splitlines():
        parts = line.strip().split()
        if len(parts) >= 2:
            received_shas.add(parts[0].lower())

    print(f"    Found {len(received_shas)} files in Android received directory.", flush=True)
    all_match = True
    for item in MANIFEST["items"]:
        name = item["name"]
        expected_sha = item["sha256"].lower()
        if expected_sha not in received_shas:
            print(f"    MISMATCH for {name}: expected {expected_sha} not found in received files!", flush=True)
            all_match = False

    if not all_match or len(received_shas) < len(MANIFEST["items"]):
        raise RuntimeError(f"Hash verification failed on Android! (Count: {len(received_shas)}/35)")

    print("    ALL 35 FILE HASHES MATCH 100% ON ANDROID PHYSICAL DEVICE!", flush=True)

    run_json = {
        "schema": "glide-hard017-run-v1",
        "id": run_id,
        "scenario": "success",
        "senderPlatform": "windows",
        "receiverPlatform": "android",
        "observedResult": "pass",
        "repositoryHead": HEAD_SHA,
        "senderArtifactSha256": WIN_SHA,
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
        "notes": "Transferred full 35-item corpus across 2 offer pages from Windows host to physical Android device (OPPO CPH2371) with 100% SHA-256 match."
    }

    run_json_path = os.path.join(RUNS_DIR, f"{run_id}.json")
    with open(run_json_path, "w", encoding="utf-8") as f:
        json.dump(run_json, f, indent=2)

    print(f"[11] Wrote {run_json_path}", flush=True)
    print(f"=== [SUCCESS] {run_id} PASSED ===", flush=True)

if __name__ == "__main__":
    main()
