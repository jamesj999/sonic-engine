# Cross-game complete-run audio parity design

## Status

Approved for implementation on the isolated
`bugfix/ai-s1-audio-parity-frontier` branch. The branch is for human audio
testing and must not be merged into `develop` without explicit human approval.

Amended after the shared native-host capability audit: stock BizHawk 2.11 has
no Genesis Z80 callback scope. The earlier proposed managed `Z80 BUS` callback
extension is superseded by the exact-2.11 buffered native observer specified
below. No S2/S3K reference implementation may rely on the superseded premise.

Amended again after the first Task 8 S2 bootstrap probe: the unshipped ABI v1
candidate eagerly proofed Z80 hooks before the ROM had uploaded the sound
driver. ABI v1 keeps the same packed layouts and event vocabulary, but gains
the source-verified native proof-arming rule below. Task 7's observer patch,
core, source bundle, BuildID, recipe, identity, and every downstream copy of
their hashes must be rebuilt and rolled before Task 8 continues.

## Requirements

### Goals

1. Replay the committed Sonic 1 all-emeralds movie naturally through OpenGGF
   and compare its complete gameplay audio trace with the shipped REV01 ROM.
2. Include every movie row from the first gameplay segment through the end of
   the movie. Segment-transition gaps, special stages, endings, and terminal
   tails are part of the comparison interval.
3. Compare raw requests, driver admissions and rejections, priorities,
   channel ownership, temporary-music save/restore behavior, normalized driver
   state, and ordered YM2612/PSG transactions without event realignment.
4. Prove Sonic 1's extra-life behavior in particular: the six normal-SFX
   tracks (FM3/FM4/FM5/PSG1/PSG2/PSG3) are removed while the two special-SFX
   tracks remain live and are re-applied as FM4/PSG3 overrides, later normal
   SFX requests are blocked, the jingle owns the applicable channels, and the
   saved music state is restored exactly.
5. Provide the same complete-run capture and comparison functionality for the
   committed Sonic 2 all-emeralds and Sonic 3 & Knuckles all-super-emeralds
   movies, preserving each shipped sound driver's semantics.
6. Capture each producer twice and require byte-identical duplicate output
   before cross-producer comparison.
7. Produce a bounded first-mismatch report and retain large detailed artifacts
   only beneath ignored `target/` directories.

### Meaning of byte parity

The parity boundary is the canonical audio trace, not host PCM. It includes:

- native sound requests and their within-frame order;
- queue consumption, resolved sound identity, admission or rejection, and the
  reason and priority transition for that decision;
- every complete sound-driver service in source order, including zero or
  multiple services in one video frame;
- the normalized live and saved driver state after each service; and
- every decoded YM2612 `(port, register, value)` transaction and every raw PSG
  byte in issue order. YM2612 register `$2A` DAC data is not excluded.

The host mixer, resampler, audio device, and final interleaved stereo PCM are
outside this contract. They can receive a later independent PCM oracle; they
must not weaken or redefine this driver/chip-transaction contract.

### Non-goals

- Do not re-open the completed S1/S2/S3K FM operator-order work.
- Do not tune chip ports, reorder writes, or reset chip state to conceal a
  driver-state mismatch.
- Do not feed recorded admissions, priorities, owners, state, or writes into
  OpenGGF.
- Do not treat an isolated sound-test or a single level as complete-run proof.
- Do not commit ROMs, detailed captures, or reconstructive trace payloads.
- Do not merge or push the work to `develop` before human listening and review.

### Project constraints

- Build and test with JDK 21.
- Verify the exact ROM, BK2, run-manifest, BizHawk 2.11 managed assemblies,
  stock or observer-patched GPGX core, native observer ABI, and harness
  identities before capture. The S2/S3K observer core is a separately installed
  exact-2.11 derivative; it never replaces the stock distribution in place.
- Model the shipped `FixBugs = 0`, `fixBugs = 0`, and `fix_sndbugs = 0` paths.
- Runtime audio assets come from the user-supplied ROM. Disassemblies are
  research sources, never runtime fallbacks.
- Trace data is comparison-only. Production packages must not import capture
  schemas, readers, or reference artifacts.
- Every output is create-new, strictly validated before publication, and
  published atomically from a sibling staging location.

### Acceptance criteria

For each of S1, S2, and S3K:

1. Metadata pins the correct ROM SHA-1/CRC32, BK2 SHA-256 and row count,
   manifest SHA-256 and segment inventory, emulator/core identities, observer
   profile and ABI, native source/toolchain/patch/artifact identities when the
   patched observer is used, schema, and exact half-open comparison interval.
2. The reference capture contains every absolute BK2 row in the interval once,
   including lag rows and rows outside manifest segments.
3. The OpenGGF capture contains the same frame coordinates naturally, with one
   outer presentation per consumed BK2 row and no fast-forward.
4. Driver-service and chip-event ordinals are contiguous. Zero-service and
   multi-service frames are legal and retained without folding or alignment.
5. Two reference captures are byte-identical and two OpenGGF captures are
   byte-identical.
6. Cross-producer comparison is byte-identical and reports `MATCH`.
7. Game-specific priority, 1-up, restoration, tempo, SFX-contention, DAC, and
   special-stage assertions listed below are observed in the real run.
8. The complete-run replay reaches its terminal record. A trace/gameplay abort
   is a capture failure, not a partial success.

## Exploration synthesis

### Existing S1 lanes

The sound-test lane under `tools/audio/run_s1_audio_parity.sh` proved a complete
14,690-service GHZ cycle. Its schema, recurrence proof, movie identity, state
normalizer, and engine producer are all GHZ-specific. It does not exercise
gameplay requests, SFX, transitions, or temporary music.

The semantic lane under
`tools/audio/run_s1_ghz1_gameplay_audio_timeline.sh` covers only BK2 frames
`[860,4975)`. Schema v2 correctly separates raw requests from later admissions,
but it has no chip transactions and infers some backend outcomes from submitted
commands and frame-final snapshots.

The deterministic GHZ1 captures in
`target/audio-parity/s1-ghz1-gameplay/run.avvaEipc/` expose the mandatory S1
extra-life oracle:

- frame 3698 queues music `$88` while `$87` and two SFX are active;
- frame 3699 gives FM3/FM4/FM5/PSG1/PSG2/PSG3 to `$88` in the ROM;
- raw SFX requests during the jingle receive no ROM admission; and
- frame 3910 restores `$87` to all six roles.

The existing OpenGGF reducer reports an SFX admission during this interval and
retains SFX owners at restore. `AudioCommandTimeline.PlaySfx` records submission
before `AbstractSmpsAudioBackend` can reject it through `sfxBlocked`, while
music-triggered SFX removals are not visible at the current contention seam.
The observer is therefore insufficient even before runtime behavior is judged.

More deeply, shipped S1 copies `$220` bytes of driver/music state while keeping
one physical YM2612 and PSG alive. OpenGGF currently saves a whole old
`SmpsDriver` and chip, creates a fresh driver/chip for the jingle, then restores
the frozen chip and refreshes voices. Exact transaction parity requires one
continuous chip and source-accurate state save/restore; refresh writes cannot be
used as a compensating approximation.

The S1 movie has 225,101 rows and 34 manifest segments. Its segments account
for 208,586 rows; 15,655 rows after the first gameplay row are transition gaps
or terminal tail. Existing visual-run proof reaches only segment 11 near frame
46,806, so no current test proves the complete route.

### S2 findings

The committed S2 movie has SHA-256
`e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5`,
259,590 input rows, 35 segments, and seven special stages. Its comparison epoch
starts at frame 769; the last segment ends at 245,021 and the movie continues
through a terminal tail.

S2's Z80 driver uses one global SFX priority before channel allocation. A
lower-priority request is rejected as a whole and an equal-priority request
replaces the old request. The current `SmpsDriver` adds a sequencer first and
arbitrates per hardware role, so a request can partially occupy free roles even
when the ROM rejects it. This must become a game-profile-owned request-admission
policy before per-role locking.

The shipped 68K-to-Z80 bridge also loops over four SFX bytes although only
three queue slots exist, overwriting the first voice-table byte. S2's 1-up
backs up stale global priority, blocks SFX through its 40-step restore fade,
and deliberately does not restore PSG noise type with `fixBugs=0`. Ring speaker
alternation, gloop suppression, and spindash retrigger state are observable
identity transformations.

S2 native music IDs and current engine API IDs differ. Comparison uses a
profile-provided native ID/content key at the producer boundary; engine-only API
IDs remain diagnostic. It never declares intentionally different numbers equal
without resolving both to the same ROM-backed content identity.

### S3K findings

The canonical Knuckles all-super-emeralds BK2 has SHA-256
`aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc`,
434,417 input rows and 67 manifest segments. Its epoch starts at frame 810 and
the final segment ends at 433,942. The exact BK2 currently lives under
`src/test/resources/traces/s3k/_movies/`, while the run manifest expects a
run-local sibling. Fixture validation must fail until that exact tracked movie
is available through the manifest's canonical path.

S3K has no S1/S2 priority table. The 68K bridge accepts up to two distinct SFX
requests per frame, ignores the slot-0 duplicate case, and lets Z80 source order
determine contention. Continuous SFX retrigger their existing state. The 1-up
path clears queues, saves music tracks/bank/voice/tempo/speed state, suppresses
later requests, and permits new SFX on the driver cycle after restore rather
than throughout the fade. Speed-up may execute extra music services in one
video frame. DPCM and SEGA PCM write `$2A` outside ordinary generic writers;
SEGA PCM pauses normal driver services.

### Observation boundary

S1's driver is 68K-resident and the current verified Lua probe can observe it.
S2 and S3K drivers are Z80-resident. The exact installed BizHawk 2.11 GPGX core
advertises an 8 KiB `Z80 RAM` memory domain but **no Genesis `Z80 BUS` callback
scope**. Its only callback scope is `M68K BUS`; the managed debugger initializes
that single scope and the native `bk_cpu_hook` routes only M68K
execute/read/write hook types. `gpgx_peek_z80_bus` and
`gpgx_write_z80_bus` are SMS paths and are explicitly not valid for Mega Drive
emulation.

The observed Genesis domain inventory is `68K RAM` 65,536, `Z80 RAM` 8,192,
`CRAM` 128, `VSRAM` 128, `VRAM` 65,536, `M68K BUS` 16,777,216, and
`Waterbox PageData` 13,567 bytes. `MD CART` is 1,048,576 bytes for S2 and
4,194,304 for S3K; S3K additionally exposes 16,384-byte SRAM. These are read
domains and identity checks, not evidence of callback support.

The negative conclusion rests on the exact runtime scope inventory plus the
pinned managed IL, native source, and native disassembly: there is no Genesis
Z80 callback route or export to invoke. Against the exact S2 REV01 and
locked-on S3K ROMs, 1,000-frame probes that registered the only available
`M68K BUS` scope at Z80 service PC `$0038` and chip addresses `$4000`-`$4003`
and `$7F11` returned zero execute and write callbacks. Those zero counts are a
useful wrong-scope negative control, not the primary proof. Frame-end reads of
`Z80 pc`, RAM, and chip-facing addresses are snapshots; they cannot distinguish
zero, one, or multiple services/writes or preserve within-frame order.

The first real S2 Task 8 probe exposed a separate bootstrap fact. On movie
row/frame 3, before the M68K reaches the source-cited `SoundDriverLoad` entry at
`$0EC000`, Z80 execution diagnostically visits future driver PCs `$0038`,
`$00E7`, `$010F`, `$0178`, and `$01B0` while ZRAM still contains reset zeros.
That probe recorded `$0178`, not `$017A`; this is preserved as bootstrap
diagnostic evidence rather than relabelled as the canonical watch mask. Eager
per-visit opcode proof therefore rejected valid pre-upload execution and made
`end_frame` return `-3`. Delaying managed `configure` until after upload is not
acceptable: it would discard earlier reset/upload chip writes and both YM
address-latch histories, and would not solve a later Reset/Power followed by a
new driver upload. Proof readiness is consequently native, source-triggered,
and independent of movie row number.

The selected correction is a minimal observation-only patch to the
exact-BizHawk-2.11 `gpgx.wbx.zst`. Native code uses a profile-supplied
65,536-bit Z80 PC watch mask plus bounded fixed-width kind/hook/range manifests. At
the Z80 interpreter boundary after interrupt admission and before opcode fetch,
and at the existing pinned M68K execute boundary, a verified hook action pushes,
pops, or atomically tail-pops-and-pushes a native service stack. The stack has
an exact maximum depth of eight. Each push allocates a nonzero 16-bit token
unique within that video frame and among carried active tokens, records parent
token, depth, profile service kind, hook token,
issuing CPU, PC, and global event ordinal, then appends `SERVICE_BEGIN`. Each
pop checks the expected active kind, synchronously copies that completion
hook's configured Z80 RAM ranges, appends `SERVICE_END`, and removes the token.
Multiple exit PCs and a fallthrough hook action are explicit manifest entries;
no return address or call shape is inferred. Atomic tail composition orders the
old snapshot/end before the new begin at that same PC.

Every logical Mega Drive FM (`address & 3`, data) and PSG write is appended to
the same native array before device mutation and carries exactly the innermost
active service token. A nested child owns its writes exclusively; its parent
retains only writes issued before the child begins or after it ends. An orphan
write, token zero, stack underflow, kind mismatch, depth/token overflow, or
unmatched tail action is fatal. Direct DAC/DPCM/SEGA-PCM paths that are not
nested under the ordinary update stack require a profile-declared, bounded
typed asynchronous service for each inner sample/loop iteration. That
iteration may nest under VInt/update, but one service may not span the whole
long-lived playback routine or loop: doing so would make continuation
unbounded and erase the real ownership of individual writes. Every iteration
has verified CPU-PC begin/end hooks and a synchronous snapshot boundary. There
is no generic orphan exception.

For S2 DPCM specifically, `$0178` is the persistent `djnz $` busy-wait
instruction (`10 FE`), revisited roughly twenty times during an already-open
iteration. It is excluded from the watch mask because a strict begin there
would immediately hit the zero-match-fatal stack rule on the second visit. The
source-correct one-shot iteration begin is the fallthrough at `$017A` (`DI`,
byte `F3`), immediately before `$2A` selection/data work; completion remains
`$01B0` (`JP zWaitLoop`). The canonical manifest and mask therefore contain
`$017A` and `$01B0`, never `$0178`.

Each game/revision manifest assigns stable hook tokens and profile service
kinds to all verified entry/completion PCs/actions, including multiple exits,
fallthroughs, tail composition, and typed asynchronous paths. Alternative
actions may share a CPU/PC and are stored in ascending hook-token order. On
each instruction visit, native code snapshots the pre-action active kind
(zero means empty), selects exactly one action whose `expected_active_kind`
matches it, and applies that action atomically. A newly pushed kind can never
satisfy another alternative at the same PC on the same visit. Zero matches or
multiple matches are sticky-fatal. Configuration rejects duplicate
`(cpu, pc, expected_active_kind)` entries, making selection unambiguous. This
models, for example, S3K `$0121` as `UpdateEverything -> Music` on the normal
tail path and `Music -> Music` on the later speedup revisit without inventing
a zero-length nested child. Every hook carries
the exact expected opcode bytes plus disassembly source/label proof, and every
completion action indexes one or more source-cited, half-open Z80 RAM snapshot
ranges. The 8,192-byte watch mask equals the exact union of Z80 hook PCs; M68K
hooks use the existing execute path. Configuration rejects a missing/extra bit.
It permits 1-512 hooks, 1-128 ranges, at most 8,192 bytes per completion, and at
most 65,535 allocated service tokens per frame. Ranges for a completion are
nonempty, within the 8 KiB Z80 RAM, and nonoverlapping. For a completion with
ranges `i`, the exact checked reservation is
`1 + 2 * range_count + sum(ceil(length_i / 8))`: one `SERVICE_END`, one
`SNAPSHOT_BEGIN`/`SNAPSHOT_END` pair per range, and its chunks. A single 8 KiB
range therefore needs 1,027 records. The bounded global worst case is 1,393
records for 128 nonempty ranges totaling 8,192 bytes (1 end, 256 range markers,
and at most 1,136 chunks after per-range rounding). The writer reserves that
hook's exact entire snapshot/end group before copying, so it never emits
partial state. A reset service reserves the same exact range-marker/chunk sum
plus its separate `RESET_END` rather than `SERVICE_END`; that group is in
addition to `RESET_BEGIN` and every pre-reset token's independently reserved
cancellation completion group. Configuration precomputes and stores this exact
completion reservation for every kind from its canonical cancellation slice.
At reset entry, before emitting anything, native code overflow-checks
`current_event_count + 1 + sum(open_kind_completion_reserve) +
reset_kind_snapshot_end_reserve <= 65,536`.
At depth eight the structural upper bound is
`1 + 8 * 1,393 + 1,393 = 12,538` events. The calculation uses the actual kind
slices and current stack, not that coarse bound.

At `end_frame`, the native stack must be empty by default. A profile may set the
continuation flag only on named kinds and must set `max_continuation_frames` in
the range 1-255. Every remaining stack entry must allow continuation and retain
the same token/parent/depth next frame. The bound measures execution exposure,
not wall time: at `begin_frame`, only the token that was top of stack across the
frame boundary increments its counter; ancestors suspended beneath a bounded
child do not age. Every open token is validated at `end_frame`, and an exposed
top token that exceeds its kind bound fails capture. Child completion exposes
its parent for the next boundary; Reset/Power cancels the full stack without
aging any token. The host delays canonical publication behind the earliest open
token while the currently executing chain remains bounded. A carried token's
stable identity is its `(begin BK2 row, token)` pair; the next frame's allocator
skips every carried token. No S2/S3K profile may enable continuation
unless pinned-source evidence proves that a real service crosses a video-frame
boundary.

The ABI layout is frozen only after a Task 7 feasibility audit proves the
pinned Waterbox compiler's packing/alignment and `.invis` placement. The
reviewed candidate, which becomes ABI v1 only if that proof passes, is:

```c
uint32_t gpgx_audio_trace_abi_version(void);       /* 1 after freeze */
uint32_t gpgx_audio_trace_event_size(void);        /* candidate: 32 */
uint32_t gpgx_audio_trace_capacity(void);          /* 65536 */
int32_t gpgx_audio_trace_configure(
    const struct gpgx_audio_trace_config_v1 *config,
    const uint8_t *z80_pc_mask,
    const struct gpgx_audio_service_kind_v1 *kinds,
    const struct gpgx_audio_service_hook_v1 *hooks,
    const struct gpgx_audio_snapshot_range_v1 *ranges);
int32_t gpgx_audio_trace_begin_frame(void);
int32_t gpgx_audio_trace_end_frame(void);
int32_t gpgx_audio_trace_event_count(
    uint32_t *required_count, uint32_t *overflow_count);
int32_t gpgx_audio_trace_drain(
    struct gpgx_audio_trace_event *out, uint32_t out_capacity,
    uint32_t *out_count);
int32_t gpgx_audio_trace_begin_publication_epoch(void); /* ABI v2 */
int32_t gpgx_audio_trace_abort_frame(void);
int32_t gpgx_audio_trace_disable(void);
```

After the pinned feasibility probe, ABI v1 freezes these packed little-endian
layouts and compile-time assertions:

- `AudioTraceConfigV1` is 64 bytes: `magic` (`uint32`, offset 0,
  `0x31544147`), `abi_version` (`uint16`, 4), `struct_size` (`uint16`, 6),
  `hook_size` (`uint16`, 8), `range_size` (`uint16`, 10), `event_size`
  (`uint16`, 12), `max_depth` (`uint8`, 14), `max_opcode_bytes` (`uint8`,
  15), `reset_service_kind` (`uint16`, 16), `max_continuation_frames`
  (`uint16`, 18), `flags` (`uint32`, 20), `watch_mask_bytes` (`uint32`, 24),
  `hook_count` (`uint32`, 28), `range_count` (`uint32`, 32),
  `snapshot_bytes_total` (`uint32`, 36), `event_capacity` (`uint32`, 40),
  `max_service_tokens_per_frame` (`uint32`, 44), `kind_size` (`uint16`, 48,
  value 16), `kind_count` (`uint16`, 50, range 1-255), and three zero `uint32`
  reserved words at 52-63. This replaces the provisional reset-range fields
  before ABI v1 is frozen; the reset kind's table entry owns that slice.
- `ServiceKindV1` is 16 bytes: nonzero `kind_id` (`uint8`, 0), `flags`
  (`uint8`, 1), `cancellation_range_first` (`uint16`, 2), nonzero
  `cancellation_range_count` (`uint16`, 4), `continuation_frame_limit`
  (`uint8`, 6), one zero reserved byte at 7, and two zero `uint32` words at 8
  and 12. Kind flags are `0x01=TYPED_ASYNC`,
  `0x02=ALLOW_FRAME_CONTINUATION`, and `0x04=ALLOW_CHILD_SERVICES`.
- `ServiceHookV1` is 32 bytes: `hook_token` (`uint16`, 0), `action`
  (`uint8`, 2), `cpu` (`uint8`, 3), full `pc` (`uint32`, 4),
  `service_kind` (`uint8`, 8), `expected_active_kind` (`uint8`, 9), `flags`
  (`uint8`, 10), `opcode_length` (`uint8`, 11), `range_first` (`uint16`,
  12), `range_count` (`uint16`, 14), exact `opcode[8]` at 16, and eight zero
  reserved bytes at 24. Z80 hook PCs must be at most `$FFFF`; M68K hooks use
  the full 24-bit PC, require the high byte zero, and may not make
  `pc + opcode_length` exceed `$1000000`. Actions are `PUSH_BEGIN`, `POP_END_AT_PC`,
  `POP_END_FALLTHROUGH`, and atomic `TAIL_POP_PUSH`. Hook flag bit
  `0x01=ARM_Z80_PROOFS_ON_COMPLETION` is the sole nonzero hook flag; it is a
  one-time proof-boundary action, not service-kind policy. Every other hook
  flag bit is reserved and zero.
- `SnapshotRangeV1` is 16 bytes: nonzero `range_id` (`uint16`, 0), `start`
  (`uint16`, 2), `length` (`uint16`, 4), zero `flags` (`uint16`, 6), and two
  zero `uint32` reserved words at 8 and 12.
- `AudioTraceEventV1` is 32 bytes: `ordinal` (`uint32`, 0), nonzero
  `service_token` (`uint16`, 4), `parent_token` (`uint16`, 6), full `pc`
  (`uint32`, 8), `subject` (`uint16`, 12), `offset` (`uint16`, 14), `kind`
  (`uint8`, 16), `service_kind` (`uint8`, 17), `depth` (`uint8`, 18),
  `source_cpu` (`uint8`, 19), `payload_length` (`uint8`, 20), `value`
  (`uint8`, 21), `flags` (`uint8`, 22), one zero reserved byte at 23,
  and `payload[8]` at 24.

Static assertions fix every size and offset of all five structs, `CHAR_BIT==8`, fixed integer
widths, and little-endian compilation. `opcode_length` is 1-8 and unused opcode
bytes are zero. Hook tokens and range IDs are unique; service kinds are nonzero
profile IDs intentionally shared by matching begin/end hooks. Kind entries are
sorted by unique `kind_id`; configuration builds an internal 256-entry lookup
and every push/tail-created token copies a valid kind ID. Range indices are
contiguous and in bounds. All
unknown flags/actions/CPU values and nonzero reserved bytes fail configuration.
`configure` copies the header, 8,192-byte mask, kinds, hooks, and ranges immediately
into `.invis`; it retains no host pointer or interior pointer. Counts, products,
sums, and range ends are checked without overflow, including the declared total
snapshot bytes, which is at most 1,048,576 and includes every hook completion
reference plus every kind-owned cancellation reference. Every kind's
cancellation slice is nonempty, contiguous, in bounds, nonoverlapping, and at
most 8,192 bytes. `reset_service_kind` is 1-255, resolves to a kind entry, and
uses that entry's cancellation slice as its post-reset snapshot.
`max_continuation_frames` is zero if no kind allows continuation and otherwise
equals the maximum kind limit, which is 1-255 only when that kind has
`ALLOW_FRAME_CONTINUATION`. The byte counter saturates and fails on the next
frame at 255 rather than wrapping. Existing S2/S3K kinds remain pinned to their
reviewed limits of at most four; S1's synchronous outer service uses the
protocol-safe 255 bound because REV01 `PlaySegaSound` may remain suspended by
Z80/BUSREQ scheduling for the duration of a sample, which is not a fixed movie
row count. A parent may receive a child push only when its kind has
`ALLOW_CHILD_SERVICES`.
Configuration validates opcode proofs structurally but does not compare live
Z80 RAM: pinned `gen_reset` zeroes ZRAM and the game uploads its driver during
later `FrameAdvance` calls. For Z80 hooks it requires
`pc + opcode_length <= 0x2000`. M68K hooks always apply their bounded 24-bit
proof at execution using only side-effect-free bytes from a direct mapped page.
A custom-handler or null map page is not proof-capable and fails capture; the
observer never performs an extra `M68K BUS` read at the execute boundary.

Configuration accepts either zero or exactly one
`ARM_Z80_PROOFS_ON_COMPLETION` hook. With zero, `configure` preserves the
immediate-armed behavior used by synthetic and legacy manifests. With one,
`configure` begins Z80-unarmed. The flagged hook must be an M68K
`POP_END_AT_PC` or `POP_END_FALLTHROUGH`, never a push, tail, or Z80 hook. It
has `service_kind=0`, names the upload kind in `expected_active_kind`, and must
end a source-cited `SoundDriverLoad` kind which has exactly one matching
M68K root `PUSH_BEGIN` (`expected_active_kind=0`), cannot be entered by a tail
or nested push, and must be the sole stack entry when its flagged completion
fires. All upload paths must converge on that one completion. The kind's
canonical completion/cancellation range union is exactly final ZRAM
`[0x0000,0x2000)`, with no gap or overlap, so the ordinary synchronous
completion snapshot is also byte-complete upload evidence. Any second arm
flag, flag on the wrong CPU/action, non-root upload kind, completion bypass, or
incomplete ZRAM slice fails configuration or capture as appropriate.

While such a manifest is unarmed, visits to watched Z80 PCs are ignored before
opcode comparison: they perform no stack action and append no service event.
The same profile-gated bootstrap rule ignores coincidental M68K hook PCs and
orphan FM/PSG writes outside the manifest's explicitly flagged arm-service
chain. Only that exact root begin, its owned writes, and its flagged completion
are observable before proof-arm; malformed ownership within the chain remains
fatal. Zero-arm legacy manifests retain their immediate-armed behavior. At the
flagged root completion, native
code first proves the M68K hook and emits its normal complete ZRAM snapshot and
`SERVICE_END`, then synchronously compares every configured Z80 hook's exact
opcode bytes against final ZRAM before any later CPU instruction may execute.
Only a complete successful scan atomically sets the native armed bit. A
mismatch is sticky, leaves it unarmed, and makes `end_frame` fail closed; the
unpublished upload group is diagnostic only. Thereafter every watched Z80
visit revalidates its selected proof synchronously before applying its action.
An unarmed frame with no other error ends, counts, and drains normally.
For `PUSH_BEGIN`, `expected_active_kind` names the required parent kind (zero
for a root), not an unused field. Pop/tail actions name the kind being ended.
Push hooks have zero range indices. Every normal pop/tail completion for the
ended kind must use exactly that kind entry's canonical cancellation range
first/count; alternate exits may have distinct hook PC/opcode/action but not a
different state slice. This makes reset cancellation independent of which exit
would eventually have executed.

The numeric constants are frozen: hook actions 1-4 in the order listed; hook
CPU `1=Z80`, `2=M68K`; hook flag
`0x01=ARM_Z80_PROOFS_ON_COMPLETION`; kind flag bits are as listed above;
event source `0=NONE`, `1=Z80`, `2=M68K`,
`3=RESET`; event kinds `1=SERVICE_BEGIN`, `2=SERVICE_END`, `3=FM_WRITE`,
`4=PSG_WRITE`, `5=SNAPSHOT_BEGIN`, `6=SNAPSHOT_CHUNK`, `7=SNAPSHOT_END`,
`8=RESET_BEGIN`, and `9=RESET_END`. Reset event flag `0x01` means Power and
zero means Reset; `SERVICE_END` flag `0x02` means reset-cancelled. Flags are
kind-specific and no other event flag is valid. Every event's `ordinal` is its
zero-based array insertion index for the current frame; the bounded capacity
prevents wrap. The remaining fields are exact:

- normal `SERVICE_BEGIN`/`SERVICE_END` use the new/ending token and its
  parent/kind/depth, the hook CPU/PC, and `subject=hook_token`; all other fields
  are zero. A reset-cancelled `SERVICE_END` instead has `subject=0`, `pc=0`,
  `source_cpu=RESET`, and flag `0x02`;
- `FM_WRITE` repeats the innermost token's parent/kind/depth, records the issue
  source/PC, stores raw `address & 3` in `subject` and data in `value`, and
  zeros every other field. `PSG_WRITE` has the same ownership/source fields,
  `subject=0`, and raw data in `value`;
- each `SNAPSHOT_BEGIN` repeats the completed token's ownership fields and
  completion CPU/PC, puts `range_id` in `subject`, and has zero offset/payload.
  `SNAPSHOT_CHUNK` repeats those fields, uses a gap-free byte offset relative to
  the configured range, sets `payload_length` to 1-8, places bytes in
  `payload`, and zeros unused payload tail bytes. `SNAPSHOT_END` repeats the
  fields with `offset=range.length` and no payload. Reset/cancellation snapshots
  use `source_cpu=RESET` and `pc=0`; and
- `RESET_BEGIN` has the new root reset token, parent/depth and PC zero,
  `service_kind=reset_service_kind`, `source_cpu=RESET`,
  `subject=cancelled_pre_reset_depth`, and the Reset/Power flag. `RESET_END`
  repeats token/kind/source/flag after the configured reset snapshot with
  `subject=0`.

Ranges appear in manifest order. Fields not assigned above, including
`payload_length` outside chunks and every reserved byte, are zero. No
incomparable native-cycle field exists.

The 65,536-event/2 MiB array, watch mask, copied kind/hook/range manifests,
256-entry kind lookup, per-kind reservations, proof-armed bit, counters, phase,
and configuration are fixed static objects annotated `ECL_INVISIBLE` in the
Waterbox ELF `.invis` section, not allocations from `alloc_invisible` or the
existing bitmap/temporary-SRAM heap. Section-offset, alignment, exact size, and
savestate-exclusion tests are release gates. The objects have no emulation
authority. Overflow is sticky, its omitted-event count saturates at
`UINT32_MAX`, and a frame with overflow yields no semantic records.

The ABI phase machine is `DISABLED -> CONFIGURED -> RECORDING -> READY ->
CONFIGURED`. `configure` is legal only in `DISABLED`, requires non-null config,
mask, kind, hook, and range pointers, copies them, and enters `CONFIGURED`.
`begin_frame` is legal only in `CONFIGURED`, resets per-frame counters while
retaining permitted carried tokens, and enters `RECORDING`. `end_frame` is
legal only in `RECORDING`, validates the service stack/continuation policy, and
enters `READY` even when returning a fail-closed runtime error. `event_count`
is legal only in `READY`, requires non-null outputs, and reports the exact
retained count plus sticky saturated omitted-event count without copying. On
overflow the retained count is the fixed-capacity prefix and is never semantic
output. `drain` is legal only in `READY`, requires non-null `out_count`, and
copies all-or-nothing into a reusable managed buffer.
For count zero, `(out=NULL, capacity=0)` succeeds and returns to `CONFIGURED`.
For nonzero count, output must be non-null; too-small capacity returns `-4`,
sets `out_count` to required count, copies nothing, and remains `READY` for one
or more bounded retries. Overflow makes `end_frame`/`event_count` report `-5`; no drain is
attempted, and the host calls `abort_frame` from `READY` to clear it. The host allocates or
grows only to the queried bounded count—there is no unconditional 2 MiB copy.
`abort_frame` is legal only in `RECORDING` or `READY`, discards that frame only
for host/capture failure, and returns to `CONFIGURED`. `disable` is legal from
any phase, clears copied observer state including proof readiness, enters
`DISABLED`, and is idempotent there. Proof readiness is an orthogonal native
substate, not a new ABI phase: an unarmed `begin_frame`/`end_frame`/drain cycle
is legal, emits any M68K/reset/chip records it actually observed, and does not
invent an arming event. The successfully drained flagged upload
`SERVICE_END` is the sole semantic evidence that the transition occurred.
Return codes remain `0`, `-1` invalid argument, `-2`
invalid phase, `-3` ABI/config limit, `-4` capacity, and `-5` overflow.
Runtime stack/continuation mismatch makes `end_frame` return `-3` in `READY`;
the host records diagnostics and uses `abort_frame`, never drains semantic data.

The pinned scheduler sets an explicit native issue-source enum around Z80 and
M68K execution and restores it on exit. At each CPU's prefetch boundary it also
latches that instruction's start PC: for Z80, after IRQ admission and before
opcode fetch; for M68K, at the existing execute/prefetch boundary. The common
FM/PSG dispatch records `Z80`, `M68K`, or `RESET`; it never infers source from a
plausible Z80 PC. Chip-event `pc` is the latched 16-bit instruction-start PC for
`source_cpu=Z80`, the latched full 24-bit instruction-start PC for
`source_cpu=M68K`, and zero for reset. Reading the CPU's current PC at chip
dispatch is forbidden because operand fetch has already advanced it.
Every supported chip mode and M68K/Z80 issue path must prove valid source and
nonzero service token. The host groups stack/snapshot records by token, rejects
parent/depth/kind/order mismatch or duplicate attribution, and reconstructs one
completed service per begin/end pair. Canonical `DriverService` order is the
global `(begin frame, begin ordinal)` order, even when nested children complete
first; each service contains only its token's chip writes. A separate raw chip
stream retains global native ordinals and exact bus order, so flattening never
duplicates or reorders writes. Separate port-0/port-1 YM latches follow the raw
stream, preserving every `$2A` byte.

The dedicated observer distribution may include a minimal first-class managed
BizHawk adapter rather than depending permanently on private reflection. Task 7
chooses it only if a pinned managed toolchain first builds the unmodified 2.11
managed components twice and byte-compares them to the installed stock DLLs,
and the adapter exposes only typed departure calls for this ABI without a fake
generic `Z80 BUS`. Its managed patch/source/toolchain/DLL hashes join the native
identity and its disabled/enabled parity gates remain identical. Stock BizHawk
is never overwritten and remains the control distribution.

If byte-exact managed reproduction cannot be established, the already-proven
fallback is a harness-local departure-only `BizInvoker` proxy over private
GPGX `_elf`, whose runtime type must be `WaterboxHost`, `IImportResolver`, and
`ICallbackAdjuster`; every call is inside `_elf.EnterExit()`. Reflection-shape
tests and exact stock managed hashes are mandatory. Either adapter allocates no
per-event managed callback, retains the existing M68K callbacks for S1, and is
recorded as a stable adapter kind plus content hashes in canonical metadata.

The reflection variant has an intentionally narrow frozen source boundary.
`GpgxHost` becomes a partial class: the stable Waterbox/reflection bridge lives
only in `src/Core/GpgxHost.AudioObserver.cs`, while ordinary core lifecycle,
game selection, and Task 8 orchestration remain in `src/Core/GpgxHost.cs`.
Task 7's managed-reflection identity hashes the dedicated partial source and
`src/Core/GpgxAudioObserverAdapter.cs`, not the whole mutable `GpgxHost.cs`.
The Task 7 installation and corresponding-source bundle carry those two exact
sources and their individual hashes. Task 8 then hashes the complete compiled
harness executable, which transitively covers both partials and all later host
logic. Thus a Task 8 change to game-name or audio-trace orchestration rolls the
harness hash without recursively invalidating the Task 7 observer artifact.
Changing either frozen bridge source is instead an explicit Task 7 reopen.
The proof-arming rebuild performs this source split and one managed-reflection
identity reroll together; there is no second Task 7 reroll after ordinary Task
8 implementation.

`configure` runs before the first `FrameAdvance`. For every bootstrap/capture
row the host calls `begin_frame`, one `FrameAdvance`, `end_frame`, `event_count`,
then one successful drain. Bootstrap updates tokens and both YM latches; only
semantic publication is suppressed. Draining native records is not polling.
The host does not delay configuration until `SoundDriverLoad`, arm by movie
row, sample ZRAM at frame end, or arm lazily when one watched PC happens to
match. For an arming manifest, pre-upload rows drain normally; the host mirrors
readiness only from the flagged root upload completion in that ordered stream.
Its mirror includes a monotonic arm epoch advanced on each drained
`RESET_BEGIN` disarm and each drained flagged completion rearm; it is host
checkpoint state, not canonical audio semantics or a native ABI field.
Those two drained event types are the only epoch inputs; the host never reads
or synthesizes a native readiness epoch.

Reset and Power inputs occurring inside that `FrameAdvance` do **not** abort or
split the frame. The patch instruments exact `gpgx_reset` wrapper entry while
phase remains `RECORDING`: `RESET_BEGIN` records reset-vs-power, current depth,
and a new reset token allocated through the same nonzero uniqueness/wrap checks
as a service push. Before appending it reserves the complete checked reset
boundary inventory above. It then appends each open pre-reset token's exact
kind-owned cancellation slice plus reset-cancelled `SERVICE_END` in
deterministic innermost-to-outermost order, independent of the token's
unselected candidate exit hooks, so those services retain their attributed
writes and exact cancellation-boundary state. The reset snapshot/`RESET_END`
reservation remains protected while reset chip writes append: a chip write may
use only capacity beyond that tail reservation, and an excess increments the
sticky omitted count rather than consuming it. It clears native stack/source diagnostics, causes the host to clear both
YM latches, then pushes the root reset service. The wrapper sets source `RESET`,
attributes every reset FM/PSG write to that token, appends the configured
synchronous post-reset slice from the reset kind entry, consumes the protected
reservation, pops it, and appends `RESET_END`. Failure to reserve the entire
boundary inventory sets overflow before any reset-boundary record is emitted;
capture fails closed while emulation still resets normally. It preserves
the existing event array/ordinals/phase, returns normally, and the same
`gpgx_advance` call continues appending later events. `RESET_BEGIN` carries the
canceled depth; the host reconstructs those services with completion status
`RESET_CANCELLED`, not ordinary completion.
The host emits one reset/power lifecycle marker referencing the canonical reset
service ordinal and reconstructs `RESET_BEGIN`/`RESET_END` as one `COMPLETED` canonical
reset `DriverService`; it does not duplicate that service's chip writes. It never calls a
second begin/end or abort for that row.
Before configuration, reset is stock behavior with no observer event. After
configuration, supported `GpgxHost` Reset/Power is legal only while
`RECORDING`, between that row's `begin_frame` and `end_frame`. A native reset
entered in `CONFIGURED` or `READY` cannot emit an ordered `RESET_BEGIN`, so it
sets a sticky invalid-observer condition and emits nothing. Every subsequent
status-returning departure except `disable`—`configure`, `begin_frame`,
`end_frame`, `event_count`, `drain`, and `abort_frame`—returns `-3`, and
publication fails closed; `disable` clears the condition. The unsigned
`abi_version`, `event_size`, and `capacity` capability queries retain their
constant values. This prevents an invisible disarm from
desynchronizing the host-only arm epoch. For a manifest with the arm flag, a
legal in-frame reset entry atomically clears proof readiness before any
post-reset Z80 instruction, without dropping the reset/cancellation/FM/PSG
records described above. M68K hooks continue to operate while unarmed. The next
flagged ROM upload completion scans the complete proof set and rearms in that
same frame or a later one. A zero-arm-flag synthetic/legacy manifest remains
immediate-armed across reset because it has no legal rearm boundary.

The observer is excluded from savestates. Each core saves/loads only its own
state at a drained boundary, together with a host-side decoder checkpoint of
both YM latches and the host-only proof-arm epoch. A load at an empty drained
`CONFIGURED` boundary must target the same configured core/profile and the
saved host epoch must equal the current host epoch before core state is
mutated; otherwise capture fails closed. There is no native epoch query. At
that boundary the host leaves the invisible native configuration/readiness intact
and restores its saved latch checkpoint. It must not disable/reconfigure after
load, because doing so would turn an already-uploaded loaded state back into an
unarmed bootstrap with no forthcoming upload completion. This restriction is
safe for the required same-core continuation proof and rejects cross-epoch or
cross-core loads rather than guessing. Disposal or a real host/capture failure
uses `abort_frame`/`disable`; reset never does.

Stock-frame polling, opcode `LD` interposition, a managed fake `Z80 BUS`, and
M68K-mapped Z80 callbacks are rejected because they lose or misidentify native
event order. `LD_PRELOAD` is impossible because the Waterbox ELF is static, has
no dynamic section, and its Z80/FM/PSG symbols are local. Binary trampoline
rewriting is rejected because LTO and Waterbox's absolute layout make it
brittle and unauditable. Per-instruction or per-write managed callbacks are
also rejected for long-run cost and callback-slot coupling. An opcode-verified
PC manifest remains useful for selecting the native watch mask, but it is not a
substitute for the native ordered write log.

### Explored approaches

1. **Stretch the two S1 schemas.** This is superficially quick but keeps GHZ
   recurrence assumptions, GHZ1 frame bounds, S1 roles, and 68K hook semantics
   in a purported cross-game contract. Rejected.
2. **Replay reference requests into an isolated OpenGGF audio runner.** This is
   useful for source-derived unit scenarios, but a recorded request sidecar can
   conceal gameplay scheduling errors and violates the acceptance requirement
   that OpenGGF emit the route naturally. Rejected as an acceptance lane.
3. **Natural full-run replay with a cross-game envelope and driver profiles.**
   This preserves request causality, supports different driver domains and
   state inventories, and makes gameplay/audio scheduling mismatches visible.
   Selected.

## Architecture decision

### Ownership and boundaries

`CompleteRunAudioProfile` is a tooling-side immutable profile selected by the
validated game/run identity. It owns:

- comparison epoch and fixture identities;
- native request classes and content-key resolution;
- canonical hardware-role inventory;
- global and track-state field inventory;
- priority, queue, 1-up, fade, tempo, and PCM observation semantics; and
- the reference observer implementation/version.

It does not own production behavior. Runtime differences remain in existing
game audio profiles, sequencer configs, loaders, and the smallest driver-owned
policy interface needed to reproduce the ROM.

The production observation seams remain disabled no-ops by default. Capture
installs an immutable append-only observer before the first driver or chip is
constructed. Observers report decisions already made; they never choose an
outcome or mutate playback.

### Capture data flow

```text
Pinned BK2 + ROM
        |
        +--> BizHawk/GPGX + game reference observer
        |       |
        |       +--> raw staging records
        |
        +--> natural OpenGGF visual-run replay
                |
                +--> request + backend decision + driver/chip observers
                        |
                        +--> raw staging records

raw staging -> strict validator -> fixed-row deterministic chunks
            -> atomic create-new capture directory

reference capture x2 --byte identity--+
                                      +--> streaming no-realignment comparator
OpenGGF capture x2 ----byte identity--+
```

The reference capture is never opened by the OpenGGF producer. A static guard
forbids production imports of complete-run schema/readers and a behavioral test
proves the OpenGGF runner accepts only the ROM, BK2/run manifest, output path,
and profile identity.

### Comparison epoch

Power-on SEGA logos, title menus, and demos are deliberately excluded because
the existing complete-run manifests begin at the first gameplay segment and
OpenGGF's headless visual-run harness skips the master title. The comparable
half-open intervals are:

| Game | First row | Exclusive end |
|---|---:|---:|
| S1 | 860 | 225101 |
| S2 | 769 | 259590 |
| S3K | 810 | 434417 |

The observer is configured and drained from power-on. An ABI-v2 profile may
declare a prepublication session in the fixed config. During that phase every
frame is still drained and every configured hook, opcode, ownership edge,
ordering rule, capacity bound, and arm proof is validated, but continuation
ages do not advance and no event is published. At an empty, successfully
drained READY boundary the host invokes the one-shot
`gpgx_audio_trace_begin_publication_epoch` departure. It is rejected before
configuration, in-frame, before the preceding drain, after a sticky fault, or
on a duplicate call. The transition preserves the active stack, token
allocator, arm state, and YM latches, resets each carried token's continuation
age and the host publication coordinates/inventories, and enables ordinary
strict continuation aging. Zero-flag legacy configs begin in immediate
publication mode and keep their existing behavior.

Before the comparison epoch, the host retains only bounded service-stack,
pending-descendant, YM-latch, and arm-proof state; those rows are never published. The first comparison row
includes a profile-validated baseline sampled immediately before its input is
consumed. Its mandatory boundary frontier declares any open service as
`CARRIED_IN_OPEN`, allowing the first published chip write to remain owned by
the real service that began before the epoch. Publication coordinates and chip
inventories restart at the boundary. Every later row is retained, whether or
not it belongs to a manifest segment. The terminal record proves the exclusive
end and all record counts.

### Canonical record model

The envelope schema is `complete_run_audio.v1`:

- `metadata`: all identities, comparison interval, profile and field
  inventories, typed observer runtime identity, observer/callback proof, chunk
  policy, and producer kind;
- `baseline`: absolute frame, complete normalized audio state, and a mandatory
  baseline `BoundaryFrontier` (empty when no service crosses the epoch);
- `frame`: absolute BK2 row, segment coordinate if applicable, lag flag,
  ordered raw requests, and ordered driver-service records;
- `service`: global service ordinal, game-local service kind, `COMPLETED` or
  `RESET_CANCELLED` completion status, ordered decisions, normalized boundary
  state, and ordered chip events;
- `lifecycle`: reset, pause, stop-all, save, restore, SEGA-PCM enter/leave, or
  other profile-declared boundary that occurs outside a normal service;
- `cutoff_frontier`: the immutable terminal `BoundaryFrontier` at the exclusive movie cutoff before
  observer cleanup. Its producer-neutral portion carries the outer-to-inner
  active service stack and completed descendants in global semantic-begin
  order, full parent coordinate/depth/kind/completion state, an exact
  duplicate-free cutoff-local chip projection and ownership partition, YM
  address latches, and normalized terminal state using the profile inventory.
  Buffered reference captures additionally carry a native-only diagnostics
  sidecar with GPGX tokens, hooks, PCs, CPU sources, native callback ordinals,
  raw bus inventory and range snapshots, host arm epoch/status, and the full
  terminal-Z80 digest. Callback/OpenGGF captures must omit that sidecar. Empty
  semantic frontiers are serialized too. Baseline and cutoff use the same
  bounded frontier shape; only baseline permits `CARRIED_IN_OPEN`, whose
  canonical begin coordinate is a boundary-local rank at the first comparison
  row while the buffered-native sidecar retains the true pre-epoch token,
  begin coordinate, hook, PC, source, and kind proof.
- `terminal`: frame, request, service, decision, YM, PSG, lifecycle, and cutoff
  frontier counts plus the canonical root digest.

The top-level record order is exactly `metadata`, `baseline`, zero or more
`frame`/`lifecycle` records in native order, exactly one `cutoff_frontier`, then
exactly one `terminal`. The frontier is present even when both service lists are
empty, is immediately adjacent to `terminal`, and is included in the terminal
root digest. No record may follow `terminal`.

Storage integrity and cross-producer equality use distinct digests. The raw
storage root hashes every exact serialized record, including callback PCs,
tokens, parentage, raw cutoff inventories, arm proof, raw snapshots and the
terminal-Z80 digest. The semantic root hashes the profile-normalized projection
and excludes that buffered-native sidecar. Both are
mandatory terminal fields; the store validates both, while the comparator uses
the semantic root for cross-producer equality.

For buffered Z80 capture, `service.ordinal` is assigned by the token's global
begin coordinate, not completion order. Parent token and depth remain strict
diagnostics. Each service's `chipEvents` contains only writes whose native token
equals that service; a raw ordered-chip diagnostic inventory preserves global
native ordinals so validation proves flattened services are a duplicate-free
partition of bus writes.

An arbitrary BK2 cutoff may occur inside a source-owned service. Such an open
service and its withheld descendants are not discarded or promoted to ordinary
completed services merely to close the file. The store and comparator include
the `cutoff_frontier` in the canonical root digest and compare it exactly.
Native disable/managed cleanup occurs only after the immutable frontier has
been serialized and hashed; cleanup is not semantic publication.

A request has a stable ordinal, native ID, canonical ROM-backed content key,
class, queue source/slot, and submission order. A decision references that
ordinal and records resolved native ID/content key, accepted/rejected status,
reason, priority before/after when applicable, requested roles, and ordered
per-role displaced/final owners.

An owner carries class, native content identity, and originating request
ordinal. It therefore distinguishes same-ID retriggers.

Driver state uses a strict profile-versioned inventory. Pointer-like fields are
normalized as ROM-backed asset key plus relative byte cursor. State includes
inactive-role markers without stale bytes and explicitly represents saved
temporary-music state rather than hiding it in diagnostics.

Chip events are:

- `ym`: monotonically ordered `(port, register, value)` unsigned bytes;
- `psg`: one unsigned byte; or
- a profile-declared lifecycle marker where hardware writes are deliberately
  paused.

Raw callback arguments and PCs are diagnostic fields excluded from semantic
equality but validated strictly.

Cutoff chip ordinals are a canonical cutoff-local sequence assigned from the
global native callback-coordinate order. Native per-frame event ordinals remain
diagnostic because the Waterbox buffer resets them on every frame.
Cutoff service begin/end ordinals are likewise producer-neutral per-frame
service-boundary ordinals: merge only retained service begin/end boundaries in
their strict native order, reset the semantic ordinal at each movie frame, and
exclude interspersed chip/snapshot callbacks. Native service tokens and raw
begin/end ordinals remain in the buffered diagnostics sidecar.

Observer runtime identity is a sealed callback-only or buffered-native value,
never an overloaded label. The buffered value carries ABI name/version,
event size, capacity, core BuildID, PC-watch-mask and service-manifest SHA-256,
enabled state, maximum per-frame occupancy, and overflow count. Runtime artifact
hashes have distinct typed entries for the managed assemblies, Waterbox host,
compressed and uncompressed core, patch, complete source bundle, toolchain,
build recipe, and harness executable. Profiles pin the exact allowed value for
each producer. Canonical metadata stores a stable logical installation ID such
as `bizhawk-2.11-gpgx-audio-observer-v1` and stable core ID plus content hashes.
The fresh absolute staging/install path is checked locally but is never
serialized or included in a canonical digest.

Producer dispatch is a closed `S1`/`S2`/`S3K` registry with fixed profile IDs
and fixed implementation class IDs. Task 9 reserves those entries but returns
typed `PRODUCER_UNAVAILABLE` without publishing until the owning game task
installs its built-in profile and producer classes. There is no path,
environment, command, or runtime registration seam. Each game task must fill
only its reserved entry and add a successful end-to-end publication test.

The registry is also the private snapshot-binding boundary for direct Java capture.
After availability succeeds, it authenticates the caller's complete reference
installation with the same pinned tree and artifact identities as the shell
preflight, then copies the ROM, BK2, run manifest, and whole reference tree into
an owner-only private directory. Only completed private copies are hashed and
passed onward; producer validation, the TraceChaser child, raw adaptation,
projection, and store finalization all remain inside that snapshot lifetime.
Every copied leaf is an ordinary singly-linked file reached through stable
non-symlink ancestors, with type and filesystem identity checked before and
after copying. TraceChaser receives a private working directory and `HOME` plus
only fixed `PATH`, `LC_ALL`, and `LANG` values, never the caller's environment.

### Storage and publication

Captures are directories partitioned into deterministic 4,096-BK2-row chunks.
Each chunk is canonical JSONL compressed with a deterministic gzip header and
has both compressed and canonical-uncompressed SHA-256 digests. A canonical
manifest lists chunks, counts, bounds, and a root digest over uncompressed
records.

The producer writes a sibling staging directory, closes it, validates every
record and digest in bounded memory, and returns only after subprocess/raw
cleanup. The registry then removes the private input snapshot and atomically
renames the completed store to the caller's fresh destination. Unsupported
atomic directory publication fails closed. Validation, producer, projector, or
snapshot-cleanup failure removes only that invocation's unpublished store and
never replaces an existing capture.

The comparator validates both captures fully, binds a digest to each source,
then streams them again without realignment. It retains at most eight complete
records before and after the first mismatch. A source change between passes is
a capture failure.

### Migration and rollback

The GHZ sound-test and GHZ1 semantic commands remain available as focused
regressions until each corresponding complete-run assertion is green. The new
schema does not silently accept old metadata. Production policy seams default
to current behavior until selected by the existing per-game audio profile.

Each game lands as a separate commit series. If a game implementation is
reverted, shared validated captures and other game profiles remain usable.
No migration writes user configuration or save data.

## Feature design

### Shared APIs

The shared implementation will expose these tooling contracts:

```java
public interface CompleteRunAudioProfile {
    String id();
    CompleteRunFixture fixture();
    List<HardwareRole> hardwareRoles();
    NativeSoundIdentity resolveRequest(RawAudioRequest request);
    StateInventory stateInventory();
    Map<ProducerKind, ObserverRuntimeIdentity> observerRuntimeIdentities();
}

public interface CompleteRunAudioRecordSink extends AutoCloseable {
    void baseline(AudioBaseline baseline) throws IOException;
    void frame(AudioFrame frame) throws IOException;
    void terminal(AudioTerminal terminal) throws IOException;
}

public record DriverService(
        long ordinal,
        String kind,
        ServiceCompletion completion,
        List<AudioDecision> decisions,
        NormalizedAudioState state,
        List<ChipEvent> chipEvents) { }

public enum ServiceCompletion { COMPLETED, RESET_CANCELLED }
```

The exact sealed record types, JSON field names, unsigned bounds, role order,
and allowed state inventories are defined once in the shared schema tests.

Production behavior gets only narrowly owned observer/policy seams:

```java
public interface AudioAdmissionObserver {
    void onDecision(AudioAdmissionDecision decision);
}

public interface SmpsRequestAdmissionPolicy {
    AdmissionResult evaluate(SmpsAdmissionContext context);
}
```

The observer is append-only and defaults to `NONE`. The policy is selected by
the existing game audio profile; shared driver code contains no game-name
checks.

### Natural OpenGGF capture

`VisualRunReplayHarness.replayAudio` gains a complete-run mode that:

- uses an explicit row budget derived from the validated BK2, never the 60,000
  default;
- calls one outer presentation and `audio.update()` for every consumed BK2 row;
- rejects cursor jumps and retains lag rows;
- reports transition-gap rows with `segment = null` rather than dropping them;
- installs observers before bootstrap audio construction;
- records baseline immediately before the epoch's first input row; and
- requires coordinator completion plus the exact terminal movie cursor.

The ordinary trace comparator remains active. A gameplay divergence, pause,
softlock, missing transition, or premature movie end fails audio capture. This
is intentional: a naturally wrong route cannot prove naturally correct audio.

### Reference observation

S1 retains the proven memory-callback decoder and opcode-verified fallback,
extended from GHZ-only lifecycle sites to the full S1 sound driver.

S2 and S3K use the buffered native ABI above; the harness does not add a
declarative `Z80 BUS` callback domain. Game/revision profiles provide the
verified PC mask, begin/end manifest, completion snapshot ranges, and opcode
proof. S2 anchors include `zVInt=$0038`, `zUpdateEverything=$0051`, and
`zUpdateMusic=$0110`; S3K anchors include `zVInt=$0038`,
`zUpdateEverything=$011B`, and `zUpdateMusic=$0121`. The exact disassemblies and
pinned ROM bytes must identify every entry and every possible completion PC,
including early/multiple exits, and cite why state is final at the completion
instruction. The host validates proof structure/source citations before
configuration; native code verifies Z80 proofs against the uploaded 8 KiB ZRAM
at the upload completion and again on every armed hook visit; it verifies M68K
proofs against side-effect-free bytes from a direct mapped page synchronously
whenever each M68K hook fires. Custom-handler/null M68K pages fail proof; the
hook never adds a `M68K BUS` read.
Each real S2/S3K manifest also defines a source-cited M68K root
`SoundDriverLoad` service whose entry precedes the first upload write and whose
single common completion occurs only after final ZRAM is visible. S2's entry is
`$0EC000`; the S3K address and both completion PCs/opcodes must come from their
exact disassemblies and locked ROM bytes, not inference from the S2 layout.
The completion owns the sole `ARM_Z80_PROOFS_ON_COMPLETION` flag and its
canonical snapshot ranges cover exactly `[0x0000,0x2000)`. No Z80 hook may
stand in for this M68K upload boundary.
Each manifest also defines one `ServiceKindV1` entry per referenced kind. Its
canonical cancellation slice is source-cited and identical to every normal
completion hook's slice for that kind, including alternate exits; the reset
kind uses its slice for post-reset state.

Before choosing the FM/PSG hook, a pinned-source call-path audit enumerates all
five accepted `GenesisFMSoundChipType` values (`MAME_YM2612`,
`MAME_ASIC_YM3438`, `MAME_Enhanced_YM3438`, `Nuked_YM2612`, and
`Nuked_YM3438`) and every Genesis issue path. Both the Z80 `memz80.c` and M68K
`mem68k.c` paths call the selected `fm_write` function pointer, while PSG is
issued from the applicable Z80, banked-Z80, and M68K dispatch paths. The patch
must observe at a common logical call boundary before the selectable chip core,
or patch and test every audited issue site; it must not hook only
`YM2612_Write`. Tests cover both M68K- and Z80-issued selectors 0/1/2/3, PSG,
and address-latch `$2A` followed by every DAC data byte. If complete path
coverage cannot be proven, capture is narrowed to and metadata-pins
`MAME_YM2612`; it may not claim chip-core-independent observation.

The exact native source base is BizHawk tag `2.11`, commit
`427556b5ef3ac437eba754d90c5e7e9096c9a8df`, with GPGX submodule
`051d430d3d1b54625f9900c8f152d7f232e06daf` and musl submodule
`2063abc4e16c84218757b1db10d3cdf9f36ef3f8`. BizHawk 2.11.1 and the local
2.11.1 source cache are not substitutes. The tracked source lock also pins the
2.11 Waterbox `emulibc`, `common.mak`, and `linkscript.T` bytes; a separately
tracked toolchain lock pins every compiler/sysroot input and binary hash used by
`waterbox/sysroot/bin/musl-clang` or `musl-gcc`.

The historical producer is Ubuntu Mantic clang `16.0.6-15`, LLD `16.0.6`, GNU
Make `4.4.1`, binutils/ar `2.47`, and Zstandard `1.5.5`. The exact embedded ELF
`STOCK_INTERPRETER_PATH` is reconstructed from pinned UTF-8 hex
`2f686f6d652f66656f732f7368617265732f73686172652f42697a4861776b2f7761746572626f782f737973726f6f742f7379736c69622f6c642d6d75736c2d7761746572626f782e736f2e31`;
fresh roots are mounted at the `STOCK_BUILD_ROOT` reconstructed from hex
`2f686f6d652f66656f732f7368617265732f73686172652f42697a4861776b` to reproduce it.
The build uses the pinned 2.11 musl scripts, then `waterbox/emulibc` and
`waterbox/gpgx` with the stock static `-mcmodel=large -fno-pic -fno-pie -O3
-flto` recipe and custom linkscript. Compression is exactly zstd 1.5.5
`--ultra -22 --threads=0`. Locale, timezone, umask, `SOURCE_DATE_EPOCH`, and
ambient flags are fixed.

Before applying the observer patch, Task 6 builds the **unmodified** pinned
core twice. Each decompressed output must be 39,558,192 bytes, compare byte for
byte to stock, hash to
`b4cc6dabc069a6f1b87790212d80f665d216e603aa4990955cc816d5bf98d218`, and
have BuildID xxHash `7696adca7ad14b79`; each compressed output must be
400,161 bytes, compare byte for byte to stock, and hash to
`c4231296ec5ba59b431df22b68e234ae7bfbbfc87b6e72fa471234ac1b220d12`.
Only this stock-reproduction gate licenses patching. Clang 18 is an explicit
negative control: its decompressed result is 4,026,152 bytes with SHA-256
`fa05369287a490b19a9a13e74aff69e3223c091adf320fb7fe1895abe6908269`.
Zstd 1.5.7 is also rejected; it produces a 399,100-byte stream.

The canonical source bundle is produced from the patched detached tree by
sorting repository-relative POSIX paths bytewise, normalizing regular files to
mode `0644`, executable scripts to `0755`, directories to `0755`, uid/gid to
zero, uname/gname to empty, mtime to `SOURCE_DATE_EPOCH`, and tracked text to LF
line endings. It excludes `.git`, build/sysroot/output directories, caches,
logs, and generated binaries. From the root of that normalized staging tree,
the lock pins the GNU tar executable/version/hash and runs, under
`LC_ALL=C TZ=UTC`, exactly
`tar --format=posix --sort=name --mtime=@$SOURCE_DATE_EPOCH --owner=0
--group=0 --numeric-owner --pax-option=delete=atime,delete=ctime
--no-recursion --verbatim-files-from --files-from=source-bundle.paths -cf
source-bundle.tar`, where the manifest has one normalized relative path per line
and rejects absolute, `..`, newline, or control-character paths. It then
runs the locked zstd 1.5.5 exactly as
`zstd --ultra -22 --threads=0 --no-progress --force source-bundle.tar -o
source-bundle.tar.zst`. Both archive digests and the sorted path/mode manifest
digest are published.

The installed stock identities are:

- `BizHawk.Emulation.Cores.dll` SHA-256
  `0144e6e236be68ce126eb771dcb5a9ae7c153a083fa0333f345ac37b4a60acf7`;
- `BizHawk.Emulation.Common.dll` SHA-256
  `f20cd009f6f5b0a95bd47b66c48dc8de85afcd7ae0cc6aab3486baf55f501fb4`;
- `libwaterboxhost.so` SHA-256
  `d2367818aafb4e520ad5ab005b5762c61506b0c819c4d79687235acfb0fc0c78`;
- stock `gpgx.wbx.zst` SHA-256
  `c4231296ec5ba59b431df22b68e234ae7bfbbfc87b6e72fa471234ac1b220d12`;
  and
- stock decompressed `gpgx.wbx` SHA-256
  `b4cc6dabc069a6f1b87790212d80f665d216e603aa4990955cc816d5bf98d218`,
  Waterbox BuildID xxHash `7696adca7ad14b79`.

The stock-reproduction tool lock contains these literal SHA-256 values; package
names also pin Ubuntu Mantic version `16.0.6-15` where applicable:

| Input | SHA-256 |
|---|---|
| `clang-16` executable | `bb6556bdcdeb00dca0c758da9966a9982542a23ddcaffa784a2de9344ede3fc0` |
| `ld.lld-16` executable | `f8d0601bf957a1b063e29c3c43613a5b76482f6c14664b9fcac4d596871e14df` |
| `libLLVM-16.so.1` | `55f9e1b3c3b98853fc31787414064de36a22cc23f870962b45832fc904c498a2` |
| `libclang-cpp.so.16` | `f9bf97848329b4d444c8c8791b9f8a584b58016852a6ba4b55db164726623ac7` |
| compiler-rt builtins | `2f257b223dbee10ea0415e5f95385a71dc05bb94505a21a4be1d22ce733e624d` |
| built zstd 1.5.5 executable | `7bc75866617449d384679bd29298a222a458ff0daea0fc4c221122b5513cf307` |
| `clang-16_16.0.6-15_amd64.deb` | `b9cd4d27a5d1b6c429fccf56a4ac1c4ac5baf2cb9b5a53e2a20fcd6593153e5a` |
| `libclang-cpp16_16.0.6-15_amd64.deb` | `39eb3e73119ef0180489c7e594d29398152b3a2d7eec2361cf87d367032f466a` |
| `libllvm16_16.0.6-15_amd64.deb` | `3353bbe1910cfc99a8ef96e1cd7df45c65e2aaebefcfc801bcb7587bab819a15` |
| `llvm-16-linker-tools_16.0.6-15_amd64.deb` | `39f6c47b5ecc04c064899a99d224650b2d932e7f27ac02246073395fc8bd1300` |
| `libclang-common-16-dev_16.0.6-15_all.deb` | `ada57e3ac045bb324397c6d269dbad56a0b0f3608c89d321d1fed38206570ff5` |
| `lld-16_16.0.6-15_amd64.deb` | `e75a2e784d2da2e3d90a31d7b8002892ac58b90e53073a14c7db1a8d80172204` |
| `libclang-rt-16-dev_16.0.6-15_amd64.deb` | `20f3b1a105d5b8fba261a03bd6ad531e09a87c929f33f54e5dd4db78f980dda2` |
| `libedit2` package | `d1c26768f5e108c97d9520c8a19356ddf5a1967222af4f38efb1f5af21da46b5` |
| `libxml2` package | `7c4d4ec04145f854bb824cb72fb34233c99f7db3eaafaa3d2049bd82800c0f85` |
| `libicu72` package | `3db0831a7a8da3c8d878fdbc4644d4131ed914b22c8a0cffbcabe68a2c3f6ec4` |
| `zstd-1.5.5.tar.gz` | `9c4396cc829cfae319a6e2615202e82aad41372073482fce286fac78646d3ee4` |

The observer build is installed into a fresh staging directory beneath ignored
`target/audio-parity/native/`, alongside the complete patched source bundle,
patch, licenses, build logs, and identity manifest. Publication is create-new
and no-replace. It must not write, hard-link, or symlink its patched core over
`docs/BizHawk-2.11-linux-x64`; the stock hashes are rechecked after build,
installation, and capture. This also satisfies the GPGX modified-binary source
distribution obligation: a patched binary is never published without the full
pinned corresponding source and notices. License guards compare and publish the
exact pinned BizHawk root `LICENSE`, Genesis Plus GX `LICENSE.txt`, musl
`COPYRIGHT`, Zstandard `LICENSE`, LLVM/Debian copyright notices, and every
license file named by the GPGX source tree. The component-to-license notice
must state verbatim that GPGX redistributions may not be sold or used in a
commercial product or activity, that a modified binary requires complete
corresponding source for all binary components subject to its license, and must
include the full GPGX warranty/liability disclaimer. Missing or summarized
license text blocks publication.

Reference metadata identifies `observer-patched-gpgx`, not stock GPGX, and
contains the stable logical installation/core IDs and literal
BizHawk/GPGX/musl/Waterbox/toolchain/patch hashes, patched compressed and
uncompressed core hashes and BuildID, ABI/layout/capacity, managed assembly and
selected adapter kind and managed patch/DLL hash when applicable. For the
reflection variant, its Task 7 identity names and hashes only
`GpgxHost.AudioObserver.cs` and `GpgxAudioObserverAdapter.cs`; a whole-source
`GpgxHost.cs` hash is forbidden there. Task 8 separately supplies the complete
compiled harness executable hash. Reference metadata also contains the
watch-mask/service-manifest digest, enabled state, maximum
per-frame occupancy, overflow count, and exact S2/S3K event-count vectors. Each
vector pins the canonical BK2 logical name, SHA-256, complete input-row count,
and exact observed row window; a different movie fails identity before frame
zero even when it targets the same ROM and sync settings. The
already-hashed service manifest is the sole authority for the arm hook's
token/kind/policy; those fields are not duplicated in the Java capture schema
or immutable identity. The capability artifact records only observed
upload/arm service evidence keyed through that manifest digest. It
never contains the staging or installation absolute path. Counts include
service begins/ends, snapshot groups/chunks/bytes, each raw FM selector, PSG
writes, `$2A` register data writes, and total ordered events. The `$2A` count is
derived by replaying the ordered raw stream through the independent port-0 and
port-1 YM address latches and counting a data-port write only while its paired
latch selects `$2A`; a data byte whose literal value is `$2A` is not such a
transaction. Zero capability counts, unreviewed self-derived expectations, or
an overflow are capture failures.

Before a real capture is accepted, short S2 and S3K runs must freeze positive
literal count vectors in independent tests and exercise generic music/SFX, S2
DAC, and S3K DPCM/SEGA PCM. A synthetic fixture proves depth-eight nesting,
tail composition, exclusive write ownership, and failure at depth nine. A
synthetic multi-byte Z80 and M68K instruction fixture proves each chip event
contains the literal instruction-start PC latched before opcode/operand fetch,
not the advanced dispatch-time PC, while reset writes contain zero. A real
S2 bootstrap regression runs from movie row zero through and beyond the first
`SoundDriverLoad`: on row 3 it preserves the diagnostic zero-filled visit set
`$0038`, `$00E7`, `$010F`, `$0178`, and `$01B0`. The canonical watched members
of that set produce no Z80 proof/action/event while unarmed, `$0178` produces
none because it is intentionally not watched, and `end_frame` succeeds. The
test does not claim that row 3 visited canonical DPCM begin `$017A`; a later
armed DPCM iteration must prove exactly one `$017A` begin before its `$2A`
writes and one `$01B0` completion. The `$0EC000` M68K root upload service, its
chip writes, complete-ZRAM snapshot, and flagged `SERVICE_END` are retained;
and the first
later armed Z80 service is proofed and emitted. The equivalent source-cited
S3K bootstrap must prove the same sequence. Both tests assert that every
bootstrap FM/PSG write is token-owned and advances the correct port-0/port-1
YM latch even while Z80 proofs are unarmed. A mismatching byte anywhere in the
complete configured Z80 hook set at upload completion fails the frame and does
not partially arm. A zero-arm-flag synthetic manifest proves unchanged
immediate-armed semantics. Frame-number arming, first-matching-hook arming, and
managed post-upload reconfiguration are negative tests.

A real S2 slice proves its source-correct
`zVInt -> zUpdateEverything -> zUpdateMusic` nesting, including repeated
`zUpdateMusic` children: outer queue/SFX writes keep the appropriate outer
token, while each repeated music service owns only its writes and synchronous
state. S3K uses a different source-correct decomposition. Its `$011B`
`zUpdateEverything` phase owns pause/SFX work, then falls through and atomically
tails at `$0121` into a sibling `zUpdateMusic` phase under the same VInt parent.
The ordered tail emits the UpdateEverything snapshot/end before the Music begin;
there is no retained UpdateEverything parent token. A later speedup revisit of
`$0121` atomically tails `Music -> Music`, selected from the pre-action top kind.
The real S3K slice proves both alternatives, attributes every pre-tail queue/SFX
write only to UpdateEverything and every post-tail or repeated-update write only
to Music, and preserves the shared VInt parent and raw global chip order. Its
DAC/DPCM/SEGA-PCM coverage proves a distinct bounded typed service for every
inner sample-loop iteration and real VInt/typed-async nesting only where the
pinned source permits interrupts, with no whole-routine service, orphan, or
overlong continuation. Canonical services sort by begin ordinal while the raw
chip stream retains native bus ordinals.
Frame-end RAM reads are forbidden as the expected value.
Fixed Reset and Power BK2 rows each prove one-row cadence and one uninterrupted
native frame containing pre-reset events, typed begin/action/cancellation,
reset-token writes and synchronous state, typed end, and post-reset advance
events with no loss, split, or duplication. At least one row resets while a
nested stack is active with multiple still-possible exit hooks and proves the
exact kind-owned snapshot bytes and deterministic innermost-to-outermost order,
exclusive pre-reset write ownership, and `RESET_CANCELLED` canonical
completion independent of which exit remained possible. A depth-eight fixture
also proves the checked actual reservation sum, 12,538-event structural bound,
protected reset tail, and fail-closed overflow without a partial group.
For each real game, Reset and Power also prove the armed-to-unarmed transition,
continued ownership/latch processing for reset and M68K upload writes, a
source-verified reupload completion in the same or a later row, complete-set
revalidation, and capture of the first post-rearm Z80 service. No stale Z80
hook may fire between reset and reupload. Capability metadata freezes upload
service counts, successful arm/rearm completion counts, pre-arm Z80 service
count zero, bootstrap/reset FM/PSG selectors, latch outcomes, and the row/
ordinal of each source-derived completion without using those coordinates to
control capture.
The observer distribution disabled—including its selected managed adapter—must
match stock, and enabled must match that disabled distribution,
for deterministic video and PCM hashes, lag flags, RAM/register checkpoints,
reset, and post-save/load continuation. Each lane saves and loads its own state;
raw savestate bytes are never compared or cross-loaded. A forced small-buffer
build must prove overflow fails closed.

Performance uses the same otherwise-idle host, ROM, movie prefix, core settings,
and harness process shape for patched-disabled and enabled lanes, with one
unmeasured warmup followed by at least three measured repetitions per lane.
The median enabled slowdown must be at most 10% and the worst repetition at
most 15% relative to patched-disabled. Full-run probes additionally require
zero overflow and at least four times the observed maximum-frame event
occupancy in fixed capacity. They also prove each frame copies exactly its
queried count times 32 bytes into a reused buffer, with no copy/allocation for
zero events and no unconditional 2 MiB transfer. Failure of either threshold fails the capability
gate rather than silently weakening capture or increasing managed work.
Measured prefixes include initial upload and at least one Reset/Power reupload
when the reviewed movie supplies it, and otherwise pair the full prefix with a
fixed reset/reupload fixture. The production ABI intentionally has
no counters for ignored visits or scan timing. Real results therefore record
only observable wall time, event occupancy/copy metrics, zero Z80 service
events before arm, and the flagged upload `SERVICE_END` evidence for each arm
and rearm; they do not publish or infer native visit counts or scan duration.
A test-only native selftest build, with instrumentation unavailable through the
production ABI and absent from the installed core, proves the exact ignored
pre-arm visit count, one complete atomic proof-set scan per flagged completion,
and no partial arm on mismatch. The pre-arm path may test only the native armed
bit and mask membership; it may not compare opcode bytes lazily on each
zero-filled visit.

### S1 behavior

- Requests enter the three-slot mailbox in source order and are consumed only
  at the ROM-equivalent service boundary. This must eliminate the known
  same-frame OpenGGF admission at GHZ1 frame 958.
- Admission observation occurs after the backend actually accepts or rejects a
  request. Submission is never relabeled admission.
- `$88` uses one live chip and source-accurate saved driver/music state. The
  six normal-SFX tracks are stopped while both special-SFX tracks persist and
  are applied as FM4/PSG3 overrides to the jingle at `$72182/$7218E`;
  subsequent normal SFX are rejected while the jingle owns the driver, and
  `$87` plus its channel state restore at the ROM boundary. Because `$71FE6`
  clears music override bits before saving, restoration does not synthesize
  those bits on `$87`; later updates and `cfStopSpecial` retain ownership.
- Both normal and special SFX requests are blocked during `$88` and the 40-step
  fade, but preserve their distinct shipped side effects: the normal path
  converges through `$722C6` and clears global priority, while the special path
  returns through `$723C6` without clearing it.
- `FixBugs=0` FM6/DAC restore behavior is preserved and named in source. The
  real `$87 -> $88 -> $87` oracle proves the DAC/fade behavior, while a
  separate 7-FM/DAC `$89` or `$93` restoration vector proves the omitted
  bug-fixed `$2B=0` FM6 write; `$87` itself has no FM6 track.
- Music ownership comes from the real `$72098` FM/DAC and `$72126` PSG loader
  loops and each song header, not a six-role GHZ constant: `$89/$93` have the
  seventh FM/DAC track and `$92` has no PSG tracks.
- The real frames 3698, 3699, 3702-through-jingle, and 3910 are mandatory
  semantic and chip-transaction regressions.

### S2 behavior

- Evaluate the global SFX priority before constructing or inserting a
  sequencer. Reject lower priority as a whole; replace equal priority; preserve
  jump's transient signed priority behavior.
- Preserve the shipped fourth queue-transfer overwrite.
- Ordinary music stops SFX through the shipped path.
- The 1-up kills six SFX tracks, saves the correct stale global priority,
  blocks SFX through the 40-step fade, restores DAC/music state, and does not
  restore PSG noise type with `fixBugs=0`.
- Resolve native music content keys, alternating ring speaker IDs, gloop
  suppression, and continuous spindash retriggers before equality.
- Record DAC/FM6 state and every `$2A` data write.

### S3K behavior

- Admit at most two different SFX IDs per 68K frame using the two-slot source
  order. Preserve duplicate-slot behavior without adding a priority table.
- Continuous SFX retrigger/extend their existing identity and state.
- The 1-up clears input/internal queues, saves track/bank/voice/tempo/speed
  state, suppresses later requests, preserves the shipped save-loop bug, and
  permits SFX on the first eligible post-restore service.
- Speed-up records multiple music services in one video frame rather than
  folding them.
- DPCM and SEGA PCM direct writes are captured; normal service pauses during
  SEGA PCM are represented explicitly.
- Use locked-on S&K-half addresses and the exact `fix_sndbugs=0` driver.

### Failure modes

The CLI exit contract is common to all games:

- `0`: duplicate determinism gates and cross-producer parity all match;
- `2`: invalid arguments or fixture identity;
- `3`: valid deterministic captures with a first parity mismatch; and
- `4`: capture, validation, native build/identity, publication, observer-proof,
  replay, or tooling
  failure.

Partial capture, missing terminal, incomplete chunk publication, callback
fallback without proof, native ABI/hash mismatch, observer overflow, gameplay
replay abort, absent contention/1-up evidence, or a changed source between
comparator passes is exit 4.

### Acceptance tests by game

S1 must demonstrate:

- GHZ jump and ring request/admission cadence;
- lower/equal priority rejection/replacement;
- `$88` takeover of all applicable roles during active music and SFX;
- SFX rejection throughout the 1-up;
- exact `$87` restore and fade progression;
- all six special stages and the ending/credits tail; and
- exact ordered state/chip bytes for every service.

S2 must demonstrate:

- whole-request lower-priority rejection and equal-priority replacement;
- the queue-transfer overwrite and music-stops-SFX path;
- a real 1-up save/block/40-step restore;
- ring alternation, gloop suppression, and spindash retrigger;
- all seven special stages and terminal tail; and
- exact Z80 state, non-DAC writes, and DAC `$2A` bytes.

S3K must demonstrate:

- two-slot unique/duplicate SFX behavior and overlapping SFX contention;
- continuous-SFX retrigger;
- 1-up save/suppression/restore and immediate eligible post-restore SFX;
- speed-up frames with multiple services;
- DPCM, SEGA-PCM pause/resume, all fourteen special stages, bonus stages, and
  terminal tail; and
- exact Z80 state and every chip transaction.

## Implementation plan

Execution is split into four test-first plans under
`docs/architecture/plans/audio/`:

1. shared complete-run schema, chunk storage, comparator, authority guards,
   exact-BizHawk-2.11 native observer source/build/install, and reference-host
   observation capability;
2. Sonic 1 complete-run producer and source-accurate mailbox/1-up behavior;
3. Sonic 2 complete-run producer and global-priority/temporary-music behavior;
4. Sonic 3K complete-run producer and two-slot/tempo/PCM behavior.

Each plan ends with duplicate real captures, cross-producer comparison, compact
validation evidence, and an independent review gate. The S1 plan executes
first; S2 and S3K may begin only after the shared patched-core build,
stock/enabled/disabled identity, real capability-count, overflow, and
performance gates are green. Performance proof warms both orders and then uses
same-host matched pairs restored from one initial savestate, alternating
disabled/enabled and enabled/disabled order. Median and worst limits apply to
the per-pair ratios; separately batched lane medians are diagnostic only.

## Human review and integration

Completion produces an integration report and end-to-end review under the
matching architecture validation directory. Human listening should cover at
least normal music, FM and PSG SFX, 1-up takeover/restore, speed shoes, and
high-contention scenes in all three games. The branch remains unmerged until a
human explicitly approves integration.
