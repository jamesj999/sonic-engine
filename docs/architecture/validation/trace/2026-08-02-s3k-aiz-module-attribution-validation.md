# S3K AIZ module attribution and fail-closed replay validation

Date: 2026-08-02
Branch: `bugfix/ai-s3k-recorder-integration`
Base: `ecf1deb30`
Implementation: `1e96d6213`

## Result

The maintained native recorder now attributes an exact final-active KosM FIFO
retirement to `post_objects` from ROM state transition evidence even when the
sampled `Level_frame_counter` is held. It does not add a callback, infer
completion from the held counter alone, or alter the frozen Lua recorder.

Replay deliberately does not execute POST on a row without the complete level
loop. A schema-2 POST edge is accepted only for `FULL_LEVEL_FRAME` and
`FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD`. `PLAYABLE_ANIMATION_ONLY`,
`ADVANCE_ONLY`, and `VBLANK_ONLY` are rejected during timing schedule
installation as `unsupported-row-POST` before `TraceReplayFixture` is queried
and therefore before gameplay, runtime-art queue, timing cursor/service,
rewind registry, or session mutation. The VBLANK diagnostic retains the
specific `unsupported-held-row-POST` reason. Supported held-row PRE continues
to install.

The native recorder also clears its logical module mirror on every observed
game-mode transition before unchanged-queue/empty-queue early returns.
Regression vectors delay the physical bulk clear until the following sample
for both an already-mirrored parent and a parent first visible on the
transition. The latter consumes its ordinal but remains reset-fenced until the
clear, which cannot emit a false retirement; the next real lifecycle keeps the
next run-wide ordinal. Separate two-entry vectors cover Level's proven
post-clear observation windows at `$0C->$8C` and `$8C->$0C`; their canonical
A/B retirements keep ordinals 0/1 and never re-ledger the surviving suffix.

This implementation task generated candidate output but installed no fixture
at the time. That historical gate was subsequently satisfied: Candidate B and
an independent repeat reproduced all 60 capture files byte-for-byte, the user
explicitly approved the exact 29-file scope frozen by manifest commit
`f7827cb1f`, and Candidate B supplied the 15 installed metadata files plus 14
changed timing files. Repeat remained validation-only.

## Route-wide candidate-capture comparison

One untruncated native capture replayed all 466,334 movie rows and produced all
15 canonical complete-run segments. The gate first attested every committed
`hardware_timing.jsonl` by byte length, line count, and SHA-256, then attested
the prospective file by the same measures. Physics and aux remained at their
committed exact length/SHA identity, and metadata differed only by the reviewed
recorder version and recording date.

Across timing, 27 of 1,087 event rows changed in 14 segments. Every changed row
kept its raw frame, kind, ordinal, fingerprint, file position, and ordering;
the sole byte-level semantic change was
`"boundary":"vint_service"` to `"boundary":"post_objects"`. The ending
segment was byte-identical. The gate pins the complete predecessor and
prospective hashes:

| segment | changed rows | published SHA-256 | prospective SHA-256 |
|---|---:|---|---|
| AIZ | 3 | `c80a9c2f…9f8d9e4` | `b8ebb466…b4df24` |
| HCZ | 2 | `a19d98bd…a34aeb` | `f055e486…02a811` |
| MGZ | 1 | `82cc794f…387562` | `e87d25f5…d1f8c` |
| CNZ | 2 | `821810c7…086c2f` | `cb0d1ddc…a91af` |
| ICZ | 2 | `bc006d24…677b92` | `4d6fd592…10ee6e` |
| LBZ | 2 | `50ced6a9…754b6` | `a1cce9f4…d9fe6b` |
| MHZ | 1 | `8741efc9…34c77` | `a015ea48…eef415` |
| FBZ | 2 | `90f519de…8997ce` | `c7513a13…1d7c2` |
| SOZ | 2 | `bd531c00…b5a78` | `c4b80609…a038a8` |
| LRZ | 2 | `5716fc06…9e685` | `6f9bf1ce…f5c6c` |
| HPZ | 4 | `8585a2e0…826a34` | `fb894bc7…fc264` |
| SSZ | 2 | `3cc18542…4a741` | `84565e6b…e0179` |
| DEZ | 1 | `de34b9fe…c815e` | `391d1ad5…a4528` |
| DDZ | 1 | `88407da5…54fd` | `56c6b5d3…989f8` |
| ending | 0 | `f414d1a7…dddec` | `f414d1a7…dddec` |

An independent Candidate B/repeat comparison found both captures
byte-identical and confirmed every row-level claim above, while exposing that
the prose had incorrectly totaled the correct per-segment counts as 25. The
cheap aggregate contract now sums all 15 `TimingCase` rows and pins 27. Its
negative vector deliberately requests 25 and requires the exact aggregate
failure, preventing the prose total from drifting away from the table again.

```text
S3K_ROM_PATH=<verified-s3k> BIZHAWK_HOME=<2.11> ./test.sh \
  --filter 'native capture matches all fifteen canonical completerun segments' \
  --jobs 1
```

The pre-publication complete capture gate passed and its scratch output was
discarded after comparison. Following exact-byte approval, the independently
reproduced Candidate B bytes were installed through the separate publication
transaction described in
`2026-08-02-s3k-module-attribution-candidate-b-repeat.md`.

## TDD evidence

Native RED, before the classifier change:

```text
BIZHAWK_HOME=<2.11> ./test.sh --filter HardwareTiming --jobs 1
```

- exact held-counter AIZ-shaped canonical head shift expected
  `post_objects`, observed `vint_service`;
- shift-plus-append expected a cardinality rejection, observed none; and
- mode/reset crossing expected no event, observed a VInt event.

The behavioral complete-run vector selected by `--filter 'held-counter AIZ'`
also expected POST and observed VInt.

Java RED, before the compiler gate:

```text
mvn -Dmse=off \
  -Dtest=TestTraceHardwareTimingScheduleCompiler,TestHardwareTimingAuthorityGuard test
```

The unsupported fixture reached `fixture.gameplayMode()` and failed with the
test mock's `NullPointerException`; it did not produce the required early
`unsupported-held-row-POST` classification.

## Green verification

```text
BIZHAWK_HOME=<2.11> ./test.sh --filter HardwareTiming --jobs 1
```

All selected native hardware-timing tests passed. The S1 and S3K ROM-span
vectors skipped because their ROM environment variables were unset.

```text
BIZHAWK_HOME=<2.11> ./test.sh --filter 'held-counter AIZ' --jobs 1
BIZHAWK_HOME=<2.11> ./test.sh --filter 'special-stage results work' --jobs 1
```

Both behavioral capture vectors passed. The results vector pins native POST
attribution while retaining frozen-Lua VInt attribution as the intended
difference.

```text
BIZHAWK_HOME=<2.11> ./test.sh --no-gates --jobs 1
```

All runnable native non-gate tests passed. ROM-backed tests whose S1, S2, or
S3K environment variable was absent skipped; no capture/differential gate was
treated as passing through a skip.

```text
mvn -Dmse=off \
  -Dtest=TestTraceHardwareTimingScheduleCompiler,TestHardwareTimingReplayPort,\
TestHardwareTimingAuthorityGuard test
```

56 tests passed, with zero failures, errors, or skips. Coverage includes:

- early install rejection with zero fixture interactions;
- both full-level POST phases, negative coverage for all three non-full
  phases, and held-row PRE installation controls;
- lower-level refusal to release an unprepared submitted parent; and
- proof that failure cannot reach the modeled runtime-art coordinator hook.

## Contract boundaries

- Native STANDARD metadata advances to `6.41-s3k`; maintained complete-run,
  bonus, special-stage, and run-manifest metadata advance to
  `6.42-s3k-completerun`.
- Frozen Lua constants remain `6.37-s3k` and
  `6.37-s3k-completerun`.
- The existing `$1B46` child-submission callback remains the only maintained
  native callback in this queue path.
- Timing authority still releases only a matching prepared production job. It
  cannot submit, prepare, select a producer, invoke the runtime-art
  coordinator, or synthesize an omitted gameplay/object/POST phase.
- The remaining CPU-prefix investigation is intentionally unresolved. A
  future executable solution requires a separately reviewed route-independent
  production scheduler design; this implementation remains honestly
  fail-closed.
