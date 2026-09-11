# Queue diagnostics skill refresh

## Goal

Teach future trace and PLC work to capture, validate, and interpret the new
physical queue and dynamic-player-art evidence without treating trace data as
runtime authority.

## Scope

Update all 24 project skills under both `.agents/skills` and
`.claude/skills`.

The following operational owners receive detailed, task-specific guidance:

- `plc-system`
- `s3k-plc-system`
- `trace-capture`
- `trace-green-fleet`
- `trace-replay-bug-fixing`
- `s1-trace-replay`
- `s1-retro-trace`

The remaining object, boss, disassembly, zone, and orchestration skills receive
a concise routing note. The note tells an agent that queue timing and
`dynamic_art` evidence belong to the trace/PLC skills and links to the correct
owner instead of duplicating a fast-changing report schema in every skill.

## Content contract

The skills will consistently state:

1. Native fixture captures use `--load-queue-state`; committed audited fixtures
   advertise `load_queue_state_per_frame`.
2. S1/S2 queue evidence covers physical Nemesis PLC state, while
   `dynamic_art_transfer_state_per_frame_v1` covers DPLC/player-art submission,
   completion, ordered requests, outstanding IDs, and run-gap ledger carry.
3. S3K queue evidence separates direct Kosinski work from KosM parents and uses
   hardware-timing schema 2 for both readiness domains.
4. Report fields under `queue.*` and `dynamic_art.*` are zero-tolerance,
   comparison-only frontiers. Queue failures take precedence over downstream
   physics, object, event, and audio symptoms.
5. Agents must distinguish comparator failures from hardware-timing admission
   errors, preserve the first frame/field/error count, and update
   `docs/status/trace-frontier-log.md`.
6. Legacy S1 credits/stable-retro traces do not claim PLC/DPLC audit coverage.

## Mirroring and verification

Edit `.agents` as the source copy, mirror all 24 files to `.claude`, then verify
byte identity. Validate frontmatter, referenced capability names, capture
flags, comparison-only language, frontier instructions, routing links,
Markdown formatting, and repository policy.
