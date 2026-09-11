# HCZ Bubbler allocation-epoch audit

Date: 2026-07-26
Base: `5222ff8da9b1ffaf3b1f79bdb477deb57b47c6c6`
Worktree: `.worktrees/integration-hcz-allocation-epoch`
Scope: HCZ complete-run only. LBZ was neither inspected nor changed.

## Result

The source-proven `AllocateObject` slot rule is real, but applying it to Bubbler in
isolation is not safe on this base. The candidate changed the HCZ replay from 2,411
errors with first error at frame 6292 to 4,794 errors with first error at frame
1824. The candidate was therefore rejected and completely removed. No production
or trace-fixture change remains from this investigation.

The deciding missing evidence is the first earlier divergence in the shared RNG and
object-occupancy timeline before frame 1824. A future attempt must explain that
timeline before changing Bubbler's allocation or RNG order.

## Reproduction

Baseline:

```text
mvn -q -Dsurefire.argLine='-Xshare:off -Xmx6g' -Dsurefire.forkCount=1 \
  -DreuseForks=true \
  "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  -Dtest=com.openggf.tests.trace.s3k.TestS3kHczCompleteRunTraceReplay#replayMatchesTrace test
```

Result: 2,411 errors; first error frame 6292,
`tails_x_speed` expected `0x0100`, actual `0x0000`.

Rejected candidate:

```text
mvn -q -Dsurefire.argLine='-Xshare:off -Xmx6g' -Dsurefire.forkCount=1 \
  -DreuseForks=true \
  "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  -Dtest=com.openggf.tests.trace.s3k.TestS3kHczCompleteRunTraceReplay#replayMatchesTrace test
```

Result: 4,794 errors; first error frame 1824, `y` expected `0x07F9`,
actual `0x07F4`.

The rejected experiment added three tests: lowest-free reservation behind the
live cursor (next-pass execution), reservation ahead of the cursor (same-pass
execution), and allocation failure before the child factory can consume RNG or
perform construction side effects. The exact candidate XML counts were 8/8 for
the focused allocation suite (five base tests plus three candidate tests) and
14/14 for the combined suite (those eight, four Bubbler tests, and two rewind
tests). Those local results did not
override the complete-run regression.

Exact focused commands:

```text
mvn -q -Dtest=com.openggf.level.objects.TestObjectManagerChildSlotAllocation test
# Requested-class XML: 8 tests passed

mvn -q \
  -Dtest=com.openggf.level.objects.TestObjectManagerChildSlotAllocation,com.openggf.game.sonic3k.objects.TestBubblerObjectInstance,com.openggf.game.sonic3k.objects.TestS3kUtilityMotionRewind \
  test
# Requested-class XML: 14 tests passed
```

The exact reconstructed production-and-test diff that reproduces the regression is retained
at
`docs/architecture/audits/evidence/2026-07-26-hcz-bubbler-rejected.patch`
(SHA-256
`be3eed6c0cc57f6bb436953cbe4e7c6961aed60bcd7e496d0b0110d43c4f0fb5`).
It is a 249-line, four-file patch generated mechanically with
`git diff --binary`: three production files plus
`TestObjectManagerChildSlotAllocation`.

Apply verification was performed in a second detached worktree at the exact base:

```text
git apply --check \
  docs/architecture/audits/evidence/2026-07-26-hcz-bubbler-rejected.patch
git apply \
  docs/architecture/audits/evidence/2026-07-26-hcz-bubbler-rejected.patch
git diff --check
```

All three commands succeeded. The applied diff was 172 insertions and 16
deletions across the same four files. After applying it, run the two focused
commands and rejected-candidate trace command above.

The retained Surefire text from the original rejected run is
`docs/architecture/audits/evidence/2026-07-26-hcz-bubbler-rejected-surefire.txt`
(repository artifact SHA-256
`d4a62f5326106478b8bc3bfdb28e4578cf234263e77240405afb3400062f36ad`).
It preserves the 4,794-error/frame-1824 assertion. The clean reconstruction rerun
produced the same assertion; its directly generated, unretained
`target/trace-reports/s3k_hcz1_report.json` had SHA-256
`c7481d1f5ca6e1e2de76f92d30e556b503fa090423e00147558a5209cc124e74`.

## Native evidence

The probe is `tools/bizhawk/probes/hcz_allocation_epoch_probe.lua`. It uses the
canonical `ProbeRuntime`, including 6400% speed, invisible emulation, disabled
sound, cleanup, and emulator exit. Its PC hooks are not installed until game mode
is gameplay, HCZ act 1 is active, and the requested narrow evidence window begins.

Capture commands:

```text
env BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  DISPLAY=:0 OGGF_START=28940 OGGF_STOP=29010 \
  OGGF_OUT=/tmp/hcz-allocation-epoch-1770-1840.log \
  tools/bizhawk/run_bizhawk_lua.sh \
  tools/bizhawk/probes/hcz_allocation_epoch_probe.lua \
  src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2 \
  "Sonic and Knuckles & Sonic 3 (W) [!].gen"

env BIZHAWK_HOME=/home/farrell/code/projects/OpenGGF/docs/BizHawk-2.11-linux-x64 \
  DISPLAY=:0 OGGF_START=33430 OGGF_STOP=33490 \
  OGGF_OUT=/tmp/hcz-allocation-epoch-6260-6320.log \
  tools/bizhawk/run_bizhawk_lua.sh \
  tools/bizhawk/probes/hcz_allocation_epoch_probe.lua \
  src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2 \
  "Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

SHA-256:

```text
084b4c8e689551f9c65d00e63b98f82da9046bae7ea1be047062a906a1967392  hcz_allocation_epoch_probe.lua
8e62fd62a202540676b4cde0855b7096b696400e4062c1a879b50298c4507614  hcz-allocation-epoch-1770-1840.log
9ab4b515b68dcadcfe21054793f8b4f71180f61736fddf3783af92a35a8e4806  hcz-allocation-epoch-6260-6320.log
fba0677fde9f76df93f3e98d6310d8af68b9847bde16e253d73cd4dd8134ed23  ROM
82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf  s3k-complete-sonic-tails.bk2
```

Relevant ROM PCs:

- `Process_Sprites` entry `0x1AADA`, slot dispatch `0x1AAFC`.
- `AllocateObject` entry `0x1BAF2`; it scans dynamic SSTs in ascending order.
- AirCountdown allocation returns at `0x185BA`.
- Bubbler allocation returns at `0x2FACE`.

Observed ordering:

- Trace frame 1781: fixed AirCountdown slot 94 reserves lowest-free slot 42.
  Slot 42 first dispatches at frame 1782.
- Trace frame 1795: fixed AirCountdown slot 95 reserves slot 43. Slot 43 first
  dispatches at frame 1796.
- Trace frame 6292: fixed AirCountdown slot 95 reserves slot 6. Slot 6 first
  dispatches at frame 6293.
- Trace frame 6294: Bubbler maker slot 25 reserves lowest-free slot 7. Since
  Process_Sprites already passed slot 7, the child first dispatches at frame
  6295.

Therefore allocation reserves a slot immediately, while first execution depends
on the live Process_Sprites cursor: an ahead slot can execute in the same pass; an
already-passed slot waits for the next pass. Baseline engine diagnostics showed
the fixed AirCountdown children already have the correct next-pass cadence.

## Why the isolated source fix failed

`Obj_Bubbler` calls `AllocateObject`, while the engine Bubbler currently uses the
after-parent child helper. The ROM also performs delay RNG, allocation, successful
child X RNG, table subtype selection, and optional large-bubble RNG in that order.
Those facts justify the rejected candidate locally.

They do not justify landing it globally or alone. Changing the shared slot and RNG
history alters the earlier HCZ interaction at frame 1824, long before the current
frame-6292 frontier. The complete-run trace demonstrates that at least one
additional semantic difference participates in the earlier state. Treating the
disassembly snippet as an isolated patch would trade a later frontier for an
earlier regression.

## Next evidence required

1. Find the first frame before 1824 where native and engine Bubbler maker fields,
   shared RNG seed, or dynamic-slot occupancy differ.
2. Include all RNG consumers between the maker dispatch and AirCountdown child
   initialization, not only the two object families.
3. Explain why the baseline's after-parent allocation currently compensates for
   that difference.
4. Re-attempt the source-correct Bubbler order only with a semantic fix that keeps
   frame 1824 green and advances frame 6292.

The trace workflow skill should make the canonical probe template, invisible/fast
emulation, and stage-delayed hook registration mandatory review items so a new
diagnostic cannot accidentally run expensive hooks through the preceding stage.

## 2026-07-27 follow-up: initialization dispatch was the missing semantic

An expanded probe window showed the `$3270,$07F8` maker entering
`Obj_Bubbler` at trace frame 5480. The ROM's init path writes
`render_flags=$84` in `SetUp_ObjAttributes` and branches directly to
`loc_2FA50`; consequently the first maker dispatch passes the render-bit test
even though `Render_Sprites` has not yet refreshed visibility. The engine
recomputed camera visibility during that same dispatch and skipped production.

Preserving only that initialization-dispatch eligibility keeps the earlier
frame-1824 interaction green and restores the maker's production epoch. The
canonical complete-run report moves from 2,411 errors at frame 6292 to 1,206
errors at frame 9047. The previously proposed lowest-free allocation change
remains rejected: this follow-up does not alter the child allocator.
