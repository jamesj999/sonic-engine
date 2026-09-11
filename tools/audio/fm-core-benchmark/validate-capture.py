#!/usr/bin/env python3
"""Fail-closed validator for a complete raw-YM segment in capture JSONL."""

import argparse
import json
from pathlib import Path


def strict_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key: {key}")
        value[key] = item
    return value


def integer(value, name, minimum=0):
    if type(value) is not int or value < minimum:
        raise ValueError(f"{name} must be an integer >= {minimum}")
    return value


def exact(value, expected, name):
    if value != expected or type(value) is not type(expected):
        raise ValueError(f"{name} must be {expected!r}")


def output_path(raw, repo, name):
    target = (repo / "target").resolve()
    result = Path(raw).resolve(strict=False)
    if target not in result.parents:
        raise ValueError(f"{name} must resolve below {target}")
    if result.exists() or result.is_symlink():
        raise ValueError(f"{name} already exists: {result}")
    return result


def load(path):
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    if not lines:
        raise ValueError("capture is empty")
    documents = []
    for number, line in enumerate(lines, 1):
        try:
            document = json.loads(line, object_pairs_hook=strict_object,
                                  parse_constant=lambda value: (_ for _ in ()).throw(
                                      ValueError(f"invalid number: {value}")))
        except (json.JSONDecodeError, ValueError) as error:
            raise ValueError(f"invalid JSON on line {number}: {error}") from error
        if type(document) is not dict:
            raise ValueError(f"line {number} must be a JSON object")
        documents.append(document)
    return documents


def validate(documents):
    header = documents[0]
    exact(header.get("type"), "header", "header.type")
    exact(header.get("format"), "openggf-physical-chip-bus-v1", "header.format")
    exact(header.get("initial_state"), "constructor_reset", "header.initial_state")
    exact(header.get("ym_core"), "nuked-opn2", "header.ym_core")
    integer(header.get("ym_core_mode"), "header.ym_core_mode")
    exact(header.get("ym_core_mode"), 3, "header.ym_core_mode")
    exact(header.get("ym_chip_type"), "YM2612", "header.ym_chip_type")
    exact(header.get("ym_domain"), "YM2612_INTERNAL_CYCLE", "header.ym_domain")
    capacity = integer(header.get("capture_capacity"), "header.capture_capacity", 1)
    count = integer(header.get("events"), "header.events")
    exact(header.get("overflow"), False, "header.overflow")
    exact(header.get("dropped"), 0, "header.dropped")
    integer(header.get("rendered_output_frames"), "header.rendered_output_frames")
    start = integer(header.get("ym_replay_start_ordinal"),
                    "header.ym_replay_start_ordinal")
    terminal = integer(header.get("terminal_ym_cycle"), "header.terminal_ym_cycle")
    if count != len(documents) - 1 or count > capacity or start > count:
        raise ValueError("header event counts or replay start are inconsistent")

    ym_events = []
    output_gates = 0
    ignored_psg = 0
    origin_counts = {"DAC_STREAM": 0, "EXTERNAL_BUS": 0}
    previous_ym_cycle = 0
    for ordinal, event in enumerate(documents[1:]):
        integer(event.get("ordinal"), f"event {ordinal} ordinal")
        if event["ordinal"] != ordinal:
            raise ValueError(f"event ordinal is not contiguous at {ordinal}")
        event_type = event.get("type")
        if event_type == "ym":
            if ordinal < start:
                raise ValueError("YM strobe exists before declared replay start")
            cycle = integer(event.get("cycle"), f"YM event {ordinal} cycle")
            port = integer(event.get("bus_port"), f"YM event {ordinal} bus_port")
            value = integer(event.get("value"), f"YM event {ordinal} value")
            if port > 3 or value > 255 or cycle < previous_ym_cycle or cycle >= terminal:
                raise ValueError(f"invalid YM strobe at ordinal {ordinal}")
            origin = event.get("origin")
            if origin not in origin_counts:
                raise ValueError(f"unsupported YM origin at ordinal {ordinal}")
            origin_counts[origin] += 1
            previous_ym_cycle = cycle
            ym_events.append((cycle, port, value))
        elif event_type == "psg":
            integer(event.get("tick"), f"PSG event {ordinal} tick")
            value = integer(event.get("value"), f"PSG event {ordinal} value")
            if value > 255:
                raise ValueError(f"invalid PSG value at ordinal {ordinal}")
            ignored_psg += 1
        elif event_type == "boundary":
            domain = event.get("domain")
            if domain not in {"YM2612_INTERNAL_CYCLE", "PSG_GENERATOR_TICK"}:
                raise ValueError(f"unknown clock domain at ordinal {ordinal}")
            clock = integer(event.get("clock"), f"boundary {ordinal} clock")
            boundary = event.get("boundary")
            if boundary not in {"RESET", "SNAPSHOT_RESTORE", "MODEL_MUTATION",
                                "TRANSACTION_ROLLBACK", "OUTPUT_GATE_CHANGE"}:
                raise ValueError(f"unknown timeline boundary at ordinal {ordinal}")
            if ordinal < start:
                if domain == "YM2612_INTERNAL_CYCLE" and clock != 0:
                    raise ValueError("pre-segment YM boundary is not at reset clock zero")
            elif domain == "YM2612_INTERNAL_CYCLE":
                if boundary != "OUTPUT_GATE_CHANGE" or clock > terminal:
                    raise ValueError(f"state-changing YM boundary at ordinal {ordinal}")
                output_gates += 1
        else:
            raise ValueError(f"unknown event type at ordinal {ordinal}")

    return ym_events, {
        "terminal_ym_cycle": terminal,
        "ym_events": len(ym_events),
        "ignored_output_gate_boundaries": output_gates,
        "ignored_psg_events": ignored_psg,
        "ym_origin_counts": origin_counts,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--events-output", required=True)
    parser.add_argument("--metadata-output", required=True)
    arguments = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    events_output = output_path(arguments.events_output, repo, "--events-output")
    metadata_output = output_path(arguments.metadata_output, repo, "--metadata-output")
    events, metadata = validate(load(arguments.input))
    events_output.parent.mkdir(parents=True, exist_ok=True)
    metadata_output.parent.mkdir(parents=True, exist_ok=True)
    events_output.write_text("".join(f"{cycle}\t{port}\t{value}\n"
                                     for cycle, port, value in events), encoding="utf-8")
    metadata_output.write_text(json.dumps(metadata, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
