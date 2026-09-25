import sys
import struct
import base64
import json
import re
import time

def extract_invitation_from_hprof(hprof_path, target_port=None):
    with open(hprof_path, "rb") as f:
        data = f.read()

    # 1. First, find all strings matching required field formats
    spkis = list(re.findall(rb'[0-9a-f]{64}', data))
    presences = list(re.findall(rb'[0-9a-f]{32}', data))
    uuids = list(re.findall(rb'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', data))
    tokens = list(re.findall(rb'[A-Za-z0-9_-]{43}', data))
    endpoints_raw = list(re.findall(rb'(192\.168\.[0-9]+\.[0-9]+):([0-9]{4,5})', data))

    # Let's find the LanInvitation class dump
    class_name = b"dev.glide.core.pairing.LanInvitation"
    cname_idx = data.find(class_name)
    if cname_idx == -1:
        raise ValueError("LanInvitation class not found in heap")

    # Find string record for class name
    str_rec_start = cname_idx - 13
    str_id = struct.unpack(">I", data[str_rec_start+9:str_rec_start+13])[0]

    # Find LOAD_CLASS record
    null_idx = data.find(b"\x00")
    pos = null_idx + 13
    class_obj_id = None
    while pos < len(data):
        tag = data[pos]
        record_time, record_len = struct.unpack(">II", data[pos+1:pos+9])
        if tag == 0x02: # LOAD_CLASS
            class_serial, c_id, stack_serial, name_id = struct.unpack(">IIII", data[pos+9:pos+9+16])
            if name_id == str_id:
                class_obj_id = c_id
                break
        pos += 9 + record_len

    if not class_obj_id:
        raise ValueError("LanInvitation class_obj_id not found")

    # Find all INSTANCE DUMPS of this class
    class_bytes = struct.pack(">I", class_obj_id)
    instances = []
    idx = 0
    while True:
        idx = data.find(b"\x21", idx)
        if idx == -1:
            break
        if idx + 17 <= len(data) and data[idx+9:idx+13] == class_bytes:
            obj_id = struct.unpack(">I", data[idx+1:idx+5])[0]
            field_len = struct.unpack(">I", data[idx+13:idx+17])[0]
            fields = data[idx+17:idx+17+field_len]
            instances.append((idx, obj_id, fields))
        idx += 1

    if not instances:
        raise ValueError("No LanInvitation instances found in heap dump")

    def get_string_at(ptr):
        p_bytes = struct.pack(">I", ptr)
        s_idx = data.find(b"\x21" + p_bytes)
        if s_idx != -1:
            a_idx = data.find(b"\x23", s_idx, s_idx+60)
            if a_idx != -1:
                arr_len = struct.unpack(">I", data[a_idx+9:a_idx+13])[0]
                return data[a_idx+14:a_idx+14+arr_len].decode("utf-8", errors="replace")
        return None

    # Scan instances from latest to oldest
    now_ts = int(time.time())
    chosen_fields = None
    for _, _, inst_fields in reversed(instances):
        ptrs = [struct.unpack(">I", inst_fields[i:i+4])[0] for i in range(0, len(inst_fields), 4)]
        resolved = [get_string_at(p) for p in ptrs]
        for s in resolved:
            if s and re.match(r'^[1-9][0-9]{8,11}$', s):
                if int(s) > now_ts - 60:
                    chosen_fields = resolved
                    break
        if chosen_fields:
            break

    if not chosen_fields:
        # Fall back to latest instance
        _, _, inst_fields = instances[-1]
        ptrs = [struct.unpack(">I", inst_fields[i:i+4])[0] for i in range(0, len(inst_fields), 4)]
        chosen_fields = [get_string_at(p) for p in ptrs]

    invitationId = None
    presence = None
    name = "Glide Device"
    platform = "android"
    spkiSha256 = None
    token = None
    expiresAt = None
    transport = "lan-tls"
    v = 1
    profile = 1
    protocol = 1
    ttlSeconds = 120

    for s in chosen_fields:
        if not s: continue
        if re.match(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', s):
            invitationId = s
        elif re.match(r'^[0-9a-f]{64}$', s):
            spkiSha256 = s
        elif re.match(r'^[0-9a-f]{32}$', s):
            presence = s
        elif re.match(r'^[A-Za-z0-9_-]{43}$', s):
            token = s
        elif re.match(r'^[1-9][0-9]{8,11}$', s):
            expiresAt = s
        elif s == "Glide Device":
            name = s
        elif s in ["android", "windows", "macos", "ios", "linux"]:
            platform = s
        elif s == "lan-tls":
            transport = s

    if not invitationId and uuids: invitationId = uuids[-1].decode()
    if not spkiSha256 and spkis: spkiSha256 = spkis[-1].decode()
    if not presence and presences: presence = presences[-1].decode()
    if not token and tokens: token = tokens[-1].decode()

    # Endpoints
    if target_port is not None:
        endpoint_list = [{"address": "192.168.0.125", "port": int(target_port)}]
    elif endpoints_raw:
        endpoint_list = []
        for ep_ip, ep_port in set(endpoints_raw):
            endpoint_list.append({
                "address": ep_ip.decode(),
                "port": int(ep_port.decode())
            })
    else:
        raise ValueError("No endpoints found")

    payload = {
        "v": v,
        "transport": transport,
        "profile": profile,
        "protocol": protocol,
        "invitationId": invitationId,
        "presence": presence,
        "name": name,
        "platform": platform,
        "endpoints": endpoint_list,
        "spkiSha256": spkiSha256,
        "token": token,
        "expiresAt": expiresAt,
        "ttlSeconds": ttlSeconds
    }

    json_bytes = json.dumps(payload, separators=(',', ':')).encode('utf-8')
    b64 = base64.urlsafe_b64encode(json_bytes).decode('ascii').rstrip('=')
    uri = f"glide://pair/v1#{b64}"
    return uri, payload

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: extract_lan_invitation.py <hprof_file> [target_port]")
        sys.exit(1)
    port = int(sys.argv[2]) if len(sys.argv) > 2 else None
    uri, payload = extract_invitation_from_hprof(sys.argv[1], target_port=port)
    print("EXTRACTED_URI:" + uri)
    print("PAYLOAD:" + json.dumps(payload))
