#!/bin/sh
# Development smoke only. Never prints or persists a pairing invitation.
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: dev-smoke-linux-listener-lock.sh <published-linux-cli>" >&2
    exit 2
fi

exe="$1"
first_output="$(mktemp)"
second_output="$(mktemp)"
first_pid=""
cleanup() {
    if [ -n "$first_pid" ]; then
        kill "$first_pid" 2>/dev/null || true
        wait "$first_pid" 2>/dev/null || true
    fi
    rm -f -- "$first_output" "$second_output"
}
trap cleanup EXIT INT TERM
umask 077

"$exe" receive >"$first_output" 2>&1 &
first_pid=$!
sleep 2
if ! kill -0 "$first_pid" 2>/dev/null; then
    echo "First listener exited before the ownership check; this host cannot qualify it."
    exit 1
fi

set +e
timeout 5 "$exe" receive >"$second_output" 2>&1
second_result=$?
set -e
if [ "$second_result" -eq 1 ] &&
   grep -q "Another Glide process already owns the receiving listener" "$second_output"; then
    echo "PASS: the second Linux receiver was refused by the per-user listener lock."
else
    echo "FAIL: second receiver result was $second_result; no ownership refusal observed."
    exit 1
fi
