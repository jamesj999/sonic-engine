# S3K trace class restructure: one file per zone, nested by character set

## Why

S3K trace class names do not say which recording they come from, and the character set
is the single most important classification fact about a trace. Four movies are
committed:

| `source_bk2` | characters |
|---|---|
| `s3k-sonic-tails-complete-emeralds.bk2` | sonic + tails |
| `s3k-complete-sonic-tails.bk2` | sonic + tails |
| `s3k-knuckles-complete-superemeralds.bk2` | knuckles |
| `s3-knux-multibonus-ss.bk2` | knuckles |

`TestS3kSlotsBonusTraceReplay` and `TestS3kSonicTailsSlotsBonusTraceReplay` read as
siblings but are different characters from different movies, and a round built a wrong
discriminator on the assumption they differed only by sidekick. Some directory labels
lie outright: the `dez23*` fixtures are not Death Egg — the level-size table names that
zone `Special Stage Arena (HPZ)` (`docs/skdisasm/sonic3k.asm:38144`).

## Target shape

One file per zone/stage. Nested class per character set. Nested class per segment.

```
TestS3kAizTraceReplay
  @Nested @Tag("trace-scope-r6") class SonicAndTails
      @Nested class Segment1 extends AbstractTraceReplayTest
      @Nested class Segment2
  @Nested @Tag("trace-scope-r7") class Knuckles
      @Nested class Segment1
```

Reported surefire class name: `TestS3kAizTraceReplay$SonicAndTails$Segment2`. Zone and
character set are both unambiguous without opening a file.

Rules:

- The segment level is always present and always `Segment1..N` in run order, even for a
  single-segment stage, so one rule covers every zone. Its javadoc names the source
  `.bk2`, the fixture directory and the old flat class name.
- The `@RequiresRom` annotation sits on the outer class; JUnit extensions are inherited
  by nested classes.
- A zone whose fixture directory label lies is named for what it is, with the directory
  kept as the fixture path and the discrepancy noted in the javadoc.

## Release scope: tags, not file names

`-Ptrace-replay` and `-Ptrace-replay-r7` selected by file-name pattern
(`*Mhz*`, `*Dez*`, the Knuckles class names). One file per zone cannot survive that,
because the release-6 split cuts **between characters inside one file**.

Landed mechanism: every converted character-set class carries exactly one of
`trace-scope-r6` / `trace-scope-r7`.

- `-Ptrace-replay` → `<excludedGroups>performance-measurement,trace-scope-r7</excludedGroups>`
- `-Ptrace-replay-r7` → `<excludedGroups>performance-measurement,trace-scope-r6</excludedGroups>`

The existing file-name include/exclude lists stay untouched and keep gating the classes
that have not been converted yet, so the migration composes stage by stage with no
big-bang moment. When a zone is converted, its file-name entries are deleted from both
lists and the tags take over.

## Stage 1 — LANDED (this change)

Converted the Slots bonus stage, deliberately chosen because it is the case that breaks
file-name selection: its two replays are different characters from different movies and
sit on opposite sides of the release-6 gate.

- `TestS3kSlotsBonusTraceReplay` (Knuckles, `s3-knux-multibonus-ss`, green, r7)
- `TestS3kSonicTailsSlotsBonusTraceReplay` (Sonic+Tails, `s3k-sonic-tails-complete-emeralds`, red, r6)

merged into `src/test/java/com/openggf/tests/trace/s3k/TestS3kSlotsBonusTraceReplay.java`.

Measured, base `ad4156ca8`, all three ROM paths passed:

| | before | after |
|---|---|---|
| `-Ptrace-replay` selects | `TestS3kSonicTailsSlotsBonusTraceReplay` | `…$SonicAndTails$Segment1` only |
| its result | 541 errors, first error frame 2587 `y_speed` exp `0x01D4` act `0x0383` | identical |
| `-Ptrace-replay-r7` selects | `TestS3kSlotsBonusTraceReplay` | `…$Knuckles$Segment1` only |
| its result | green | green |

Two leaf tests before, two leaf tests after. The `@Nested` container classes emit
surefire XML files with `tests="0"` and do not change any count.

## Stages 2-4 — not landed

2. Convert the release-6 zones: AIZ, HCZ, MGZ, CNZ, ICZ, LBZ, plus the Sonic+Tails
   bonus and `Ss*` special stages. Every class moved here is tagged `trace-scope-r6`;
   no file-name list entry changes, because none of these are gated by name today.
   `TestS3kHczCompleteRunTraceReplay` is deliberately red at exactly 2 errors, frame
   29095 `rings`, and its javadoc must move with it intact — likewise the 63
   `"New frontier harness: expected RED"` markers.
3. Convert the r7 zones: MHZ, FBZ, SSZ, SOZ, LRZ, HPZ, DDZ, `Zone0c`, and the `dez23*`
   fixtures, which are renamed to HPZ Special Stage Arena with the directory kept as
   the fixture path. As each zone lands, delete its `*Zone*` entry from the
   `-Ptrace-replay` excludes and the `-Ptrace-replay-r7` includes, and add the merged
   file to the r7 includes.
4. Delete the now-empty `com.openggf.tests.trace.s3k.sonictails` package and re-measure
   both profiles end to end.

Stage 3 cannot land before stage 1's tag mechanism, which is why stage 1 is the tag
mechanism rather than a large zone. Stages 2 and 3 are independent of each other.

### Open question for stage 3

The run-chain classes (`TestS3kKnucklesSuperEmeraldRunChain`, `TestS3kMegaRunChain`)
are per-run, not per-zone, so they do not fit zone-per-file. They should keep their
current shape and simply gain a `trace-scope-r7` tag when their file-name entries go.
