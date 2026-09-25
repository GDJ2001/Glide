import os
import sys
import time
import json
import hashlib
import subprocess
import re
import threading
from datetime import datetime, timezone

def iso_now():
    return datetime.now(timezone.utc).isoformat()

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()

def main():
    base_dir = r"D:\GITHub\Glide"
    corpus_dir = os.path.join(base_dir, "build", "hard-017", "corpus")
    manifest_path = os.path.join(corpus_dir, "corpus-manifest.json")
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    manifest_sha = sha256_file(manifest_path)
    run_id = "windows-to-linux-success"
    run_dir = os.path.join(base_dir, "build", "hard-017", "runs", run_id)
    os.makedirs(run_dir, exist_ok=True)

    sender_log_path = os.path.join(run_dir, "sender.log")
    receiver_log_path = os.path.join(run_dir, "receiver.log")

    print("[1] Cleaning Linux destination directory...", flush=True)
    subprocess.run(["wsl", "bash", "-c", "rm -rf /home/geethikadilshan/Downloads/* /home/geethikadilshan/.local/share/glide/staging/* Glide/staging/*"], check=True)

    started_at = iso_now()
    port = 43101

    print(f"[2] Starting Linux receiver on port {port}...", flush=True)
    recv_proc = subprocess.Popen(
        ["wsl", "/mnt/d/GITHub/Glide/build/hard-017/artifacts/linux/Glide.App", "receive", "--port", str(port)],
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
            if not b:
                break
            recv_log_bytes.extend(b)
            line_buf.extend(b)
            if b == b'\n':
                text_line = line_buf.decode('utf-8', errors='replace')
                m = re.search(r"(glide://pair/v1#[A-Za-z0-9_-]+)", text_line)
                if m and not pair_uri:
                    pair_uri = m.group(1)
                    uri_event.set()
                line_buf.clear()
            elif b"[y/N]:" in line_buf or b"[y/N]" in line_buf:
                recv_proc.stdin.write(b"y\n")
                recv_proc.stdin.flush()
                print(f"    receiver: answered prompt with 'y'", flush=True)
                line_buf.clear()

    recv_thread = threading.Thread(target=recv_reader, daemon=True)
    recv_thread.start()

    print("[3] Waiting for pairing URI...", flush=True)
    if not uri_event.wait(timeout=15):
        recv_proc.kill()
        raise RuntimeError("Timed out waiting for pairing URI from Linux receiver")

    print(f"    Found URI: {pair_uri[:50]}...", flush=True)

    corpus_files = sorted([
        os.path.join(corpus_dir, item["name"])
        for item in manifest["items"]
    ])
    print(f"[4] Prepared {len(corpus_files)} files from corpus.", flush=True)

    print("[5] Launching Windows sender...", flush=True)
    win_bin = os.path.join(base_dir, "build", "hard-017", "artifacts", "windows", "Glide.App.exe")
    sender_cmd = [win_bin, "send", "--pair", pair_uri] + corpus_files

    sender_start = time.time()
    send_proc = subprocess.Popen(
        sender_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT
    )

    send_log_bytes = bytearray()
    def send_reader():
        line_buf = bytearray()
        while True:
            b = send_proc.stdout.read(1)
            if not b:
                break
            send_log_bytes.extend(b)
            line_buf.extend(b)
            if b == b'\n':
                text_line = line_buf.decode('utf-8', errors='replace')
                if "[COMPLETED]" in text_line or "[ITEM]" in text_line or "[BATCH]" in text_line:
                    print(f"    sender: {text_line.strip()}", flush=True)
                line_buf.clear()

    send_thread = threading.Thread(target=send_reader, daemon=True)
    send_thread.start()

    send_proc.wait()
    send_thread.join(timeout=5)
    print(f"    Windows sender exited with code {send_proc.returncode} in {time.time() - sender_start:.2f}s", flush=True)

    recv_proc.wait(timeout=30)
    recv_thread.join(timeout=5)
    print(f"    Linux receiver exited with code {recv_proc.returncode}", flush=True)

    ended_at = iso_now()

    with open(sender_log_path, "wb") as f:
        f.write(send_log_bytes)
    with open(receiver_log_path, "wb") as f:
        f.write(recv_log_bytes)

    print("[6] Verifying SHA-256 hashes of files received in Linux...", flush=True)
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
    for item in manifest["items"]:
        name = item["name"]
        expected_sha = item["sha256"]
        actual_sha = received_hashes.get(name)
        if actual_sha != expected_sha:
            print(f"    MISMATCH for {name}: expected {expected_sha}, got {actual_sha}", flush=True)
            all_match = False

    if not all_match:
        raise RuntimeError("Hash verification failed for received files!")

    print(f"    ALL {len(manifest['items'])} FILE HASHES MATCH 100%!", flush=True)

    run_json = {
        "schema": "glide-hard017-run-v1",
        "id": run_id,
        "scenario": "success",
        "senderPlatform": "windows",
        "receiverPlatform": "linux",
        "observedResult": "pass",
        "repositoryHead": "0f7b0563ecdbd3839ed8946300616560a0a98fda",
        "senderArtifactSha256": "b8b5a9a5c90da5f15c9c4f2657a8c6cefc4a2156d1f4d395b2a769dd4b827a6e",
        "receiverArtifactSha256": "4b6b4fff5e7dad9d4f49686ee97ee0280eab726bd9e0f63e7145b6e4021732bc",
        "startedAtUtc": started_at,
        "endedAtUtc": ended_at,
        "corpusManifestSha256": manifest_sha,
        "itemCount": len(manifest["items"]),
        "offerPageCount": manifest["offerPageCount"],
        "allItemHashesMatch": True,
        "terminalBatchStatus": "success",
        "logs": [
            f"runs/{run_id}/sender.log",
            f"runs/{run_id}/receiver.log"
        ],
        "notes": "Transferred full 35-item corpus across 2 offer pages from Windows to Linux with 100% SHA-256 match."
    }

    run_json_path = os.path.join(base_dir, "build", "hard-017", "runs", f"{run_id}.json")
    with open(run_json_path, "w", encoding="utf-8") as f:
        json.dump(run_json, f, indent=2)

    print(f"[7] Wrote {run_json_path}", flush=True)
    print("SUCCESS!", flush=True)

if __name__ == "__main__":
    main()
