# Trace scope for release 6

## 2026-08-26 release-closure decision

0.6 no longer requires every known-red in-scope trace to become green before
release. Trace replay remains release evidence under a strict no-regression
contract:

- every trace that passes on the candidate baseline must remain passing;
- a known-red trace must not gain a new failure, regress its established
  frontier, or worsen in a way attributable to the candidate;
- trace comparison, timing authority, fixture integrity, and structural guards
  remain mandatory and are not weakened or skipped; and
- a trace finding that demonstrates a release-impacting real-play defect is a
  normal 0.6 bug fix, while parity-only frontier work is deferred to the next
  release.

This closes the open-ended trace campaign as a 0.6 scheduling gate without
discarding its evidence. Sonic 2 CPZ/WFZ and Sonic 3 & Knuckles run-chain
frontiers remain documented limitations. Casino Night is no longer on that
list: both release traces compare exactly. Final 0.6 sign-off now requires a
fresh package, ordinary suite, guard session, trace no-regression comparison,
and human gameplay/audio QA.

The historical scope and measurements below still define which fixtures belong
to each profile and how to interpret their evidence. Statements below that call
run chains "the gate" describe the earlier parity-completion policy; for 0.6
release closure, the no-regression policy above supersedes that requirement.

Release 6 targets the **Sonic path through S1, S2 and S3**. Sonic 3 & Knuckles is only
partially implemented past Launch Base, and the Knuckles routes are not a release-6
deliverable at all, so a large part of the S3K trace suite is **expected red** and always
has been. Ranking trace work by error count hides that: the biggest numbers in the suite
sit almost entirely outside the release.

This document records the split and how it is enforced. Numbers are measured on a full
`-Ptrace-replay` sweep at commit `9900b3114` (843 tests, the expected total — a smaller
total means the run truncated, a larger one means stale reports were counted).

## Fresh 2026-08-23 validation

The isolated `bugfix/ai-0.6-release-hardening` candidate was re-run with all three supplied
ROMs using the release-equivalent `-Ptrace-replay` profile. Maven completed **868 tests,
10 failures, 0 errors, and 7 skips**, with no heap exhaustion, fork crash, or truncated
session. The same ten known red classes remain; this validation did not move the trace
frontier. Human end-to-end gameplay and audio QA are still required before release.

The scope measurements below are historical measurements of earlier candidates. They are
not the result count of this fresh run.

## The two axes that put a trace out of scope

1. **Zone.** In scope is the name set **{AIZ, HCZ, MGZ, CNZ, ICZ, LBZ}**. Everything else
   is expected red.

   **Do not express this as an id range.** An earlier revision of this document said
   "ids 0-6 are the Sonic 3 half" and listed six names for seven ids; the id it silently
   swallowed is **FBZ = 4** (`Sonic3kZoneIds.java:15`), which is an S&K-half level sitting
   inside 0-6. The ROM owns this predicate exactly: `SSEntry_CheckLevel`
   (`docs/skdisasm/sonic3k.asm:128433-128443`) returns "S3 level" only when
   `Current_zone < 7` **and** `Current_zone != 4` -- an explicit `cmpi.b #4` carve-out for
   Flying Battery. So the in-scope ids are **{0,1,2,3,5,6}**, and out of scope are FBZ(4),
   7-0xC (MHZ, SOZ, LRZ, SSZ, DEZ, DDZ), the 0x0D intro/ending scene zone, and the
   0x16/0x17 boss and arena zones (HPZ is **0x16 act 1**, not 7-13).

   Note FBZ is S&K-half as a *level* while using the S3KL object table (`loc_1B6A8`,
   `sonic3k.asm:37410-37421`) -- "which half" is not one question, so cite the routine that
   owns the specific question you are asking.
2. **Character.** Knuckles routes are out of scope regardless of zone.

Bonus and special stages inherit the scope of the run that recorded them, **not** the name
of the stage: the Sonic+Tails Slots/Gumball/Pachinko replays are in scope because they come
from a Sonic+Tails movie, while the standalone `bonus_slots` / `bonus_gumball` /
`bonus_pachinko` fixtures are Knuckles recordings and are not.

## Identifying which run a fixture came from

Every fixture states it. `metadata.json` carries `characters`, `main_character` and
`source_bk2`, and segment fixtures live under `runs/<bk2-stem>/<zone>`. Four movies are
committed:

| `source_bk2` | characters |
|---|---|
| `s3k-sonic-tails-complete-emeralds.bk2` | sonic + tails |
| `s3k-complete-sonic-tails.bk2` | sonic + tails |
| `s3k-knuckles-complete-superemeralds.bk2` | knuckles |
| `s3-knux-multibonus-ss.bk2` | knuckles |

**Do not infer the character set from a class or directory name.** Two traps have already
cost rounds: the standalone `bonus_*` classes look like siblings of the Sonic+Tails bonus
replays but are Knuckles recordings from a different movie, and the `dez23*` directories
are not Death Egg — the level-size table names that zone `Special Stage Arena (HPZ)`
(`docs/skdisasm/sonic3k.asm:38144`), so those eight classes are S&K-half and out of scope.

## In scope for release 6 — S3K

13 red classes, 30,975 errors, plus the Sonic+Tails bonus and special stages reachable from
those zones.

| class | errors | first error |
|---|---:|---|
| `TestS3kSonicTailsLbzSegmentTraceReplay` | 7174 | 958, `player_animation_id` |
| `TestS3kSonicTailsCnzSegmentTraceReplay` | 6300 | 5754, `player_animation_id` |
| `TestS3kSonicTailsMgzSegmentTraceReplay` | 3446 | 10709, `x` |
| `TestS3kSonicTailsHcz2SegmentTraceReplay` | 3437 | 0, `y_speed` |
| `TestS3kSonicTailsIczSegmentTraceReplay` | 2862 | 1983, `tails_animation_id` |
| `TestS3kSonicTailsAiz5SegmentTraceReplay` | 2138 | 0, `x_speed` |
| `TestS3kSonicTailsAiz3SegmentTraceReplay` | 1567 | 0, `tails_x` (0x7F00 respawn sentinel) |
| `TestS3kSonicTailsAiz2SegmentTraceReplay` | 1034 | 0, `tails_y` |
| `TestS3kSonicTailsIcz2SegmentTraceReplay` | 965 | 0, `rings` (inventory boundary) |
| `TestS3kSonicTailsAiz4SegmentTraceReplay` | 963 | 0, `tails_x` (0x7F00 respawn sentinel) |
| `TestS3kSonicTailsHcz3SegmentTraceReplay` | 640 | 0, `rings` (inventory boundary) |
| `TestS3kSonicTailsHcz4SegmentTraceReplay` | 447 | 0, `rings` (inventory boundary) |
| `TestS3kHczCompleteRunTraceReplay` | 2 | 29095, `rings` — **deliberate**, see its javadoc |

Green and protected: `TestS3kSonicTailsAizSegmentTraceReplay`,
`TestS3kSonicTailsHczSegmentTraceReplay`.

Also in scope: `SlotsBonus`, `Pachinko`/`Pachinko2`/`Pachinko3`, `Gumball`/`Gumball2`, and
the `Ss*SpecialStage` classes — all from the Sonic+Tails run. `Ss`, `Ss2`, `Ss4`, `Ss5`
and `Ss6` are **green**.

**Correction: the nine remaining `Ss*` classes are not "structurally blocked".** They were
recorded that way here and in the frontier table on the strength of an
`unmatched recorded hardware completions` message, but they do reach frame comparison and
complete it — `Ss8` compares 5284 rows and `Ss9` 3807. The harness wrote its divergence
report *after* `closeHardwareTiming()`, which throws, so the report was discarded and the
real first error was invisible. The ordering is fixed. Each of the nine has a genuine
physics divergence first, and the unconsumed timing edge is its downstream symptom: the
stage never reaches `clearRoutine == 2`, so the emerald art module is never queued and the
recorded completion has nothing to match. **No rule-4 change is needed for any of them.**

The S1 and S2 suites are in scope in full.

## Out of scope — the `trace-replay-r7` profile

Two groups, moved out of `-Ptrace-replay` and into `-Ptrace-replay-r7`:

- **S&K-half zones, Sonic+Tails** (30 classes, 49,261 errors): `Mhz*`, `Fbz*`, `Ssz*`,
  `Soz*`, `Lrz*`, `Hpz*`, `Ddz*`, `Dez*`, `Zone0c*`, and `TestS3kMhzCompleteRunTraceReplay`.
- **Knuckles routes**: `TestS3kSlotsBonusTraceReplay` (now the `Knuckles` nested class of
  the merged stage file, gated by tag rather than by file name — see below),
  `TestS3kGumballBonusTraceReplay`,
  `TestS3kPachinkoBonusTraceReplay`, `TestS3kSpecialStageTraceReplay`,
  `TestS3kKnucklesSuperEmeraldRunChain`, `TestS3kMegaRunChain`.

### How the split is expressed

Two mechanisms, both live:

1. **File name.** The include/exclude lists in the two profiles in `pom.xml`, mirrored
   one-for-one. This gates every trace class that still has one flat class per route.
2. **JUnit tag.** A trace class restructured to one file per zone holds several
   character sets, and the release-6 split cuts *between characters inside one file* —
   a file name cannot express that. Each character-set class carries exactly one of
   `trace-scope-r6` / `trace-scope-r7`; `-Ptrace-replay` excludes the r7 tag and
   `-Ptrace-replay-r7` excludes the r6 tag. The Slots bonus stage is converted;
   `docs/architecture/plans/2026-08-15-s3k-trace-class-naming-restructure.md` has the
   pattern and the remaining stages.

Adding an out-of-scope trace class means adding it to BOTH file-name lists, or tagging
it `trace-scope-r7` if its zone has been converted.

Run them with:

```bash
mvn -Ptrace-replay-r7 test        # out-of-scope traces only
```

**Nothing is deleted, weakened or made advisory.** These classes still run, still assert
exactly what they asserted before, and still fail if they regress — they are gated out of
the release-blocking sweep, not silenced. A fix that is correct for a release-6 zone but
breaks an S&K one is still a bug, and the frontier log still records their measured state.

### When you must run the r7 profile anyway

The gate removes cross-checks that have caught real problems, so run `-Ptrace-replay-r7`
on both trees whenever a change touches **shared** runtime rather than a release-6 zone:

- the sidekick CPU/despawn path (`SidekickCpuController`) — a ROM-cited fix there moved
  both FBZ and SSZ, which are both r7;
- the bonus-stage runtime and coordinator — the three **green** Knuckles bonus classes
  (1024 / 1277 / 2900 frames compared) are the best available cross-check for slot-runtime
  changes, and they caught nothing only because the change was right;
- the hardware-timing port, PLC/Kosinski queues, camera, or anything in `AbstractTraceReplayTest`.

A green r7 class going red is exactly as informative as a release-6 one going red. The
scope split is about **what blocks a release**, not about what is worth knowing.

## Measured effect of the gate, and one thing it exposed

At `9900b3114`, before the gate: **843 tests, 64 red**. After: **806 tests, 33 red** in
`-Ptrace-replay`, and **37 tests, 32 red** in `-Ptrace-replay-r7`. The 32 gated reds are
accounted for one-for-one by name.

The arithmetic leaves one class over, and it is a real finding rather than a rounding
error. `TestTraceStructuralRowComparator` failed in one gated sweep with:

> `GameServices.hardwareTiming() requires an active gameplay mode.`

**Its result is ordering-dependent, so the red count for `-Ptrace-replay` is 32 or 33
depending on the run** — it was red in a `forkCount=4` sweep and green in two later
`forkCount=2` sweeps. The decisive measurement is that it fails **in isolation on both an
ungated and a gated tree** (3 tests, 2 errors): the class has a latent dependency on some
earlier class leaving a gameplay session open, so any green it records is an ordering
accident rather than a pass. The gate did not break it and does not reliably expose it
either. The fix is for that class to open its own session
(`TestEnvironment.activeGameplayMode()`, as `TestS3kSlotBonusStageCoordinator` does), not
to restore the ordering it happened to rely on. Until then it is red in the release-6
sweep and is **not** a trace-parity failure.


## 2026-08-17: historical parity gate refinement — run chains, not segments

**Segments are instrumentation, not evidence.** A per-zone or per-act segment *resumes* a
playthrough: it is seeded with position/zone/act from metadata but inherits progression
state -- emeralds above all -- that it has no way to earn. Its comparison can therefore only
answer *"given this boundary state, do frames 1..N evolve correctly"*. That is useful for
localisation (a chain red says "somewhere after cursor N"; a segment red names the zone) and
cheap to iterate on, but it is not a parity claim.

The **run chains** play through and earn every piece of run state. They are the gate.

### The `*CompleteRunTraceReplay` classes were badly named, and it misled this work

They are **not** complete runs. Each covers exactly one zone's two acts plus the handoff into
the next zone, and all fifteen fixtures are per-zone slices of the *same* movie:

| fixture | rows | zone-acts covered |
|---|---:|---|
| `aiz_completerun` | 26,228 | AIZ1, AIZ2, -> HCZ1 |
| `lbz_completerun` | 46,244 | LBZ1, LBZ2, -> MHZ1 |
| `soz_completerun` | 59,507 | SOZ1, SOZ2, -> LRZ1 |
| `ddz_completerun` | 719 | DDZ only |

A genuine complete run would be the ~465,000-row sum replayed continuously. The name
described the *source movie*, not the scope.

Structurally they are segments. They differ from the `sonictails/` set only in that their
source movie (`s3k-complete-sonic-tails.bk2`) **never collects emeralds**, so their giant
rings capture in both ROM and engine and agree -- they miss the inventory boundary rather
than clearing it. That is the entire reason one family looked healthier than the other, and
it is why `LbzZoneSlice` is green while `LbzSegment` is red at 7,174.

The seven S3K classes are renamed `TestS3k<Zone>ZoneSliceTraceReplay`. **Historical
references in `trace-frontier-log.md` keep the old names** -- that log is append-only with a
byte-for-byte historic-prefix guard, and a historical record should say what was true when it
was written.

### Profiles

- **`-Ptrace-replay`** -- the release gate. Chains, whole-level replays that start from a
  clean state, and the S1/S2 suites.
- **`-Ptrace-segments`** -- every per-zone and per-act segment, including the renamed zone
  slices. Instrumentation; run it while working a zone.
- **`-Ptrace-replay-r7`** -- out of release-6 scope (S&K-half zones, Knuckles routes).

### Measured effect

Gate before: 806 tests, 28 red. Gate after: **766 tests, 3 red** -- and only **two** are real:

| class | failure |
|---|---|
| `TestS3kSonicTailsCompleteEmeraldRunChain` | special stage exited with 1181 rows remaining in `ss` (segment 1 of 63) |
| `TestS2CompleteEmeraldRunChain` | 5 axes, end-of-act -> `TITLE_CARD` handoff |
| `TestTraceStructuralRowComparator` | needs a gameplay session it never opens; fails in isolation on any tree, not a parity failure |

The 40 classes that left the gate did not become green -- they became **honest**. They were
never parity evidence, and reporting their red count as the release figure overstated how
much of the remaining work was engine work and understated how far the chains are from
finished.
