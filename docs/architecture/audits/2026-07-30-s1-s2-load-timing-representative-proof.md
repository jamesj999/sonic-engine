# S1/S2 load-timing representative proof

Date: 2026-07-30

Context: `feature/ai-trace-fleet-regeneration`, after Task 8 recovery commit
`53de63da2`. Canonical fixtures were not modified. All captures used BizHawk
2.11 and the freshly rebuilt native headless harness with verified World REV01
ROMs:

- S1 SHA-1 `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`
- S2 SHA-1 `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`

## Representative captures

Scratch root:
`.scratch/task9-representative-20260730/`.

| Case | Rows | PLC rows | DPLC rows | Edges | Submit/complete | Aux SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| S1 GHZ1 | 3905 | 3905 | 3905 | 2962 | 1481/1481 | `d0aa928a346eb4f5c0917cac26aff11363f00b3b35b4402e82fddf8b404925e3` |
| S1 run special stage | 3091 | 0 | 3091 | 1624 | 812/812 | `91faeaeea00a2c8fc4e7d26f83726a87deb981f876bf81bac71e9256695bd17f` |
| S2 ARZ segment 0 | 5073 | 5073 | 5073 | 8854 | 4427/4427 | `937fe943e550d06038668d59cb7ac697cc458365f4fe80f0c511da374111c380` |
| S2 standalone special stage | 5299 | 0 | 5299 | 5916 | 2958/2958 | `d86ad76fbac0c6e7dcd79458595a0682edec29e0e520d42c57233f9626e13a98` |

Every case had zero missing/duplicate DPLC heartbeats, zero frame-order
violations, zero non-monotonic edge ordinals, and zero unpaired transfer IDs.
Level captures had exactly one physical PLC state per stored row. Special
stages correctly advertised only the DPLC capability.

## ROM-observed staging preparation

The first production capture exposed an unpublished S1 staging preparation
across the SS-to-GHZ2 arm. Movie frame 8048 prepares owner `sonic`, mapping
frame 48, ROM source `163216`, source tile `700`, VRAM `61440`, 512 bytes.
GHZ2 arms at frame 8049 with an empty submitted ledger. Row 0's verified
VBlank probe promotes the final preparation, allocates its transfer id, and
the matching `$1060` callback completes the physical staging-buffer DMA.
Neither the run manifest nor a trace ledger carries the unpublished
preparation.

A later full-run observation proved why preparation cannot be called a
submission early. At movie/logical frame 136632, while the pending flag was
one, mapping `$09` had prepared ROM `$23090`, tile 84, 192 bytes for VRAM
`$F000`. A new `$1436A` decision prepared mapping `$01`, ROM `$22610`, tile 0,
96 bytes for the same staging buffer and VRAM destination. The S1 disassembly
unconditionally rewrites `v_sgfx_buffer`; only the final payload reaches
VBlank. The observer and production lifecycle therefore replace unpublished
preparations without allocating an id or edge. A latest-build proof then
captured all 19 complete-run segments through Final Zone without the former
overlap abort.

S2 supplied a second interrupt boundary. In the halfpipe run, BK2 frame 15078
(`ss_2` logical frame 2473) executes `ProcessDMAQueue` at `$14AC` between the
`ss-tails-tails` gated entry `$34AB0` and its mapping probe `$34AC4`, with
prior accepted transfer 9419 pending. No matching `$34AC4` or `$34B1A`
follows; the next identical entry occurs at frame 15079. The observer marks
that zero-work gate interrupted, permits a real matching resume, or replaces
it only at the identical pinned entry. Accepted current-decision DMA still
fails closed. The five-segment halfpipe replay completes with this rule.

The same latest halfpipe proof pins accepted FIFO continuity across a named
arm. At BK2 frame 12605, `ss_2` arms with exact initial descriptor 8078:
owner `tails-tails`, mapping 13, ROM `$650E0`, tile 110, VRAM `$F600`, 384
bytes, descriptor fingerprint
`sha256:7e597c1e80064c07e4930745ffe8bd0ecf6862829866a8ce34bc7abc82bce1c3`.
Its initial-ledger fingerprint is
`sha256:6efdb4c1d7b9a7afddc612f535f1bbc77e1e3ad83cb76bc7768300ceb86f75b0`.
The matching production FIFO item remains queued until `ss_2` row 126, where
`ProcessDMAQueue` at `$14AC` completes the same id and empties the ledger.
Only S2 named runs serialize this comparison continuity; S1 preparations are
unpublished and standalone arms remain empty.

## First genuine Java DPLC frontiers

Representative replays used the scratch-directory override and ROM input only.
No trace field was written into gameplay state.

| Replay | First DPLC frontier |
|---|---|
| S1 GHZ1 | frame 9, `dynamic_art.outstanding_transfer_ids`, expected `[1]`, actual `[]` |
| S1 special stage | frame 98, `dynamic_art.outstanding_transfer_ids`, expected `[1521]`, actual `[]` |
| S2 ARZ | frame 0, `dynamic_art.outstanding_transfer_ids`, expected `[24]`, actual `[2]` |
| S2 special stage | frame 136, `dynamic_art.edges`, expected ordinals `[40,41,42]`, actual `[]` |

These were recorded as frontiers without normalization or trace authority.

## Verification

- Native focused observers: S1 13 passed and S2 21 passed, 0 failed.
- Java production lifecycle service: 20 passed, 0 failed, 0 skipped.
- Java lifecycle/parser/launcher matrix: 82 passed, 0 failed, 0 skipped.
- Rewind and static-state guards plus lifecycle service: 22 passed, 0 failed.
- The isolated S2 complete-emeralds run completed all 35 segments and 34
  transitions in 21:56.50 at 99% CPU, with 243,744 KiB maximum RSS. The
  previous 600-second generic differential timeout was stale; the measured
  route alone now receives a 2,400,000 ms bound. Deterministic tests prove
  other routes keep the old bound and a killed child cannot promote a staged
  `.tmp` manifest to the final name.
- Native no-gates: 488 passed, 0 failed; ROM-tagged cases were not part of
  that selector/environment run.
- The first all-ROM attempt was invalidated by obsolete uncompressed fixture
  shadows before recorder comparison. Those files are quarantined
  recoverably. The later scope again includes all three games, so this report
  does not qualify Task 9: all-ROM regeneration, differential comparison,
  full frontier sweeps, and canonical publication remain pending.
