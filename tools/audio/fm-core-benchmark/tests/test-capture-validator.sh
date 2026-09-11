#!/usr/bin/env bash
set -euo pipefail

tool_root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)
test_root="$repo_root/target/fm-core-capture-validator-test"
mkdir -p "$test_root"

valid="$test_root/valid.jsonl"
cat > "$valid" <<'EOF'
{"type":"header","format":"openggf-physical-chip-bus-v1","initial_state":"constructor_reset","ym_core":"nuked-opn2","ym_core_mode":3,"ym_chip_type":"YM2612","ym_domain":"YM2612_INTERNAL_CYCLE","capture_capacity":8,"events":5,"overflow":false,"dropped":0,"rendered_output_frames":1,"ym_replay_start_ordinal":1,"terminal_ym_cycle":72}
{"type":"boundary","ordinal":0,"domain":"YM2612_INTERNAL_CYCLE","clock":0,"boundary":"RESET"}
{"type":"ym","ordinal":1,"cycle":0,"bus_port":0,"value":34,"origin":"EXTERNAL_BUS"}
{"type":"ym","ordinal":2,"cycle":24,"bus_port":1,"value":8,"origin":"EXTERNAL_BUS"}
{"type":"boundary","ordinal":3,"domain":"YM2612_INTERNAL_CYCLE","clock":48,"boundary":"OUTPUT_GATE_CHANGE"}
{"type":"psg","ordinal":4,"tick":3,"value":144}
EOF

python3 "$tool_root/validate-capture.py" --input "$valid" \
  --events-output "$test_root/events.tsv" --metadata-output "$test_root/metadata.json"
diff -u <(printf '0\t0\t34\n24\t1\t8\n') "$test_root/events.tsv"
python3 - "$test_root/metadata.json" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
assert value == {"terminal_ym_cycle": 72, "ym_events": 2,
                 "ignored_output_gate_boundaries": 1,
                 "ignored_psg_events": 1,
                 "ym_origin_counts": {"DAC_STREAM": 0, "EXTERNAL_BUS": 2}}
PY

expect_reject() {
  local name=$1 pattern=$2
  sed "$pattern" "$valid" > "$test_root/$name.jsonl"
  if python3 "$tool_root/validate-capture.py" --input "$test_root/$name.jsonl" \
      --events-output "$test_root/$name.tsv" \
      --metadata-output "$test_root/$name.json" >/dev/null 2>&1; then
    echo "capture validator accepted $name" >&2
    exit 1
  fi
}

expect_reject overflow 's/"overflow":false/"overflow":true/'
expect_reject old_endpoint 's/"terminal_ym_cycle":72/"terminal_ym_cycle":null/'
expect_reject bad_origin 's/"EXTERNAL_BUS"/"DAC_INTERPOLATION"/'
expect_reject bad_ordinal 's/"ordinal":2/"ordinal":7/'
expect_reject bad_port 's/"bus_port":1/"bus_port":4/'
expect_reject late_cycle 's/"cycle":24/"cycle":73/'
expect_reject mutation 's/"OUTPUT_GATE_CHANGE"/"MODEL_MUTATION"/'
expect_reject prefix_ym 's/"ym_replay_start_ordinal":1/"ym_replay_start_ordinal":2/'
expect_reject prefix_clock 's/"clock":0/"clock":1/'
expect_reject wrong_count 's/"events":5/"events":4/'

echo 'fm-core capture validator tests: PASS'
