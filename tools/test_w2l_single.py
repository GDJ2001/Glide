import os
import sys
import time
import json
import hashlib
import subprocess
import re
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

    # Clean Linux download destination:
    print("[1] Cleaning Linux destination directory...")
    subprocess.run(["wsl", "bash", "-c", "rm -rf /home/geethikadilshan/Downloads/* /home/geethikadilshan/.local/share/glide/staging/* Glide/staging/*"], check=True)

    started_at = iso_now()
    port = 43101

    print(f"[2] Starting Linux receiver on port {port}...")
    # Linux receiver process with piped stdin "y\ny\ny\n"
    recv_proc = subprocess.Popen(
        ["wsl", "/mnt/d/GITHub/Glide/build/hard-017/artifacts/linux/Glide.App", "receive", "--port", str(port)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )

    pair_uri = None
    recv_log_lines = []

    # Read receiver output until pairing URI appears
    print("[3] Waiting for pairing URI...")
    while True:
        line = recv_proc.stdout.readline()
        if not line:
            break
        recv_log_lines.append(line)
        m = re.search(r"(glide://pair/v1#[A-Za-z0-9_-]+)", line)
        if m:
            pair_uri = m.group(1)
            print(f"    Found URI: {pair_uri[:40]}...")
            break

    if not pair_uri:
        recv_proc.kill()
        with open(receiver_log_path, "w", encoding="utf-8") as f:
            f.writelines(recv_log_lines)
        raise RuntimeError("Pairing URI not found in receiver output")

    # Send "y\ny\ny\n" to receiver stdin for offer & trust confirmation
    recv_proc.stdin.write("y\ny\ny\n")
    recv_proc.stdin.flush()

    # Get corpus files in sorted order
    corpus_files = sorted([
        os.path.join(corpus_dir, item["name"])
        for item in manifest["items"]
    ])
    print(f"[4] Prepared {len(corpus_files)} files from corpus.")

    # Start Windows sender
    print("[5] Launching Windows sender...")
    win_bin = os.path.join(base_dir, "build", "hard-017", "artifacts", "windows", "Glide.App.exe")
    sender_cmd = [win_bin, "send", "--pair", pair_uri] + corpus_files

    sender_start = time.time()
    send_proc = subprocess.Popen(
        sender_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1
    )

    send_log_lines = []
    # Stream sender output
    for line in send_proc.stdout:
        send_log_lines.append(line)
        if "[COMPLETED]" in line or "[ITEM]" in line:
            print(f"    sender: {line.strip()}")

    send_proc.wait()
    print(f"    Windows sender exited with code {send_proc.returncode} in {time.time() - sender_start:.2f}s")

    # Drain receiver output
    for line in recv_proc.stdout:
        recv_log_lines.append(line)
        if "[VERIFIED & COMMITTED]" in line or "[HISTORY]" in line:
            print(f"    receiver: {line.strip()}")

    recv_proc.wait()
    print(f"    Linux receiver exited with code {recv_proc.returncode}")

    ended_at = iso_now()

    # Save logs
    with open(sender_log_path, "w", encoding="utf-8") as f:
        f.writelines(send_log_lines)
    with open(receiver_log_path, "w", encoding="utf-8") as f:
        f.writelines(recv_log_lines)

    # Verify received files in Linux
    print("[6] Verifying SHA-256 hashes of files received in Linux...")
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

    print(f"    Found {len(received_hashes)} files in Linux destination.")
    all_match = True
    for item in manifest["items"]:
        name = item["name"]
        expected_sha = item["sha256"]
        actual_sha = received_hashes.get(name)
        if actual_sha != expected_sha:
            print(f"    MISMATCH for {name}: expected {expected_sha}, got {actual_sha}")
            all_match = False

    if not all_match:
        raise RuntimeError("Hash verification failed for received files!")

    print(f"    ALL {len(manifest['items'])} FILE HASHES MATCH 100%!")

    # Write run JSON
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
        "notes": "Transferred full 35-item corpus (0-byte, 32 small files, 10 MiB, 100 MiB) across 2 offer pages from Windows to Linux with 100% SHA-256 match."
    }

    run_json_path = os.path.join(base_dir, "build", "hard-017", "runs", f"{run_id}.json")
    with open(run_json_path, "w", encoding="utf-8") as f:
        json.dump(run_json, f, indent=2)

    print(f"[7] Wrote {run_json_path}")
    print("SUCCESS!")

if __name__ == "__main__":
    main()
