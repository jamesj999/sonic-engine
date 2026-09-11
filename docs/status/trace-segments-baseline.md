# `-Ptrace-segments` baseline

Measured at develop `887320904`, all three ROMs, `-Dmse=off`, JDK 21. Two consecutive runs
in the same worktree gave an identical partition and identical totals (70 tests, 52 failures,
8 errors). A third run in a *different* worktree, freshly created off the same commit, produced the
identical sixty-one-class red set and identical totals. That closes the caveat this file
carried on first writing: Surefire's filesystem run order varies between checkouts in this
repo and can select order-dependent victims, so cross-checkout agreement was the evidence
needed before anything could be gated on this partition. It holds.

**This file is a decision input, not a wired gate.** Nothing consumes it yet. It exists so a
choice about putting this profile in CI can be made against the actual partition rather than
against the headline count, and so that the green set below can be protected cheaply whichever
way that choice goes.

## Why most of the red is not a defect

Of the 52 physics failures, 29 are continuation segments failing at their first compared row
on carried run-level state — ring counts, camera, sidekick, speeds. Those are the documented
start-position bootstrap debt: seeding that state from the fixture would be trace hydration,
which hard rule 4 forbids. The correlation is total — every continuation segment fails at
frame 0 and none fails later. A plain pass/fail gate over this profile would therefore be
reporting accepted debt as failure, and the pressure it created would point at the one thing
the rules forbid.

## Green (9)

These are the classes a regression can actually be detected against. The cheap option is to
fold exactly this set into the keep-green selection of the profile CI already runs, which
captures most of the regression-detection value with no baseline file to maintain.

- `TestS3kSonicTailsAizSegmentTraceReplay`
- `TestS3kSonicTailsPachinko3BonusTraceReplay`
- `TestS3kSonicTailsSs2SpecialStageTraceReplay`
- `TestS3kSonicTailsSs3SpecialStageTraceReplay`
- `TestS3kSonicTailsSs4SpecialStageTraceReplay`
- `TestS3kSonicTailsSs5SpecialStageTraceReplay`
- `TestS3kSonicTailsSs6SpecialStageTraceReplay`
- `TestS3kSonicTailsSs7SpecialStageTraceReplay`
- `TestS3kSonicTailsSsSpecialStageTraceReplay`

## Red (61)

Red at the measured commit. This set includes both the accepted debt above and genuine
frontiers; it is not a defect list. One member, `TestS3kSonicTailsHczSegmentTraceReplay`, is a
confirmed regression — recorded green on 2026-08-15 with 3519 frames compared, and red since,
unobserved because no CI job runs this profile.

- `Tests`
- `TestS3kAizZoneSliceTraceReplay`
- `TestS3kCnzZoneSliceTraceReplay`
- `TestS3kHczZoneSliceTraceReplay`
- `TestS3kIczZoneSliceTraceReplay`
- `TestS3kLbzZoneSliceTraceReplay`
- `TestS3kMgzZoneSliceTraceReplay`
- `TestS3kMhzZoneSliceTraceReplay`
- `TestS3kSonicTailsAiz2SegmentTraceReplay`
- `TestS3kSonicTailsAiz3SegmentTraceReplay`
- `TestS3kSonicTailsAiz4SegmentTraceReplay`
- `TestS3kSonicTailsAiz5SegmentTraceReplay`
- `TestS3kSonicTailsCnzSegmentTraceReplay`
- `TestS3kSonicTailsDdzSegmentTraceReplay`
- `TestS3kSonicTailsDez232SegmentTraceReplay`
- `TestS3kSonicTailsDez233SegmentTraceReplay`
- `TestS3kSonicTailsDez234SegmentTraceReplay`
- `TestS3kSonicTailsDez235SegmentTraceReplay`
- `TestS3kSonicTailsDez236SegmentTraceReplay`
- `TestS3kSonicTailsDez237SegmentTraceReplay`
- `TestS3kSonicTailsDez238SegmentTraceReplay`
- `TestS3kSonicTailsDez23SegmentTraceReplay`
- `TestS3kSonicTailsFbzSegmentTraceReplay`
- `TestS3kSonicTailsGumball2BonusTraceReplay`
- `TestS3kSonicTailsGumballBonusTraceReplay`
- `TestS3kSonicTailsHcz2SegmentTraceReplay`
- `TestS3kSonicTailsHcz3SegmentTraceReplay`
- `TestS3kSonicTailsHcz4SegmentTraceReplay`
- `TestS3kSonicTailsHczSegmentTraceReplay`
- `TestS3kSonicTailsHpz222SegmentTraceReplay`
- `TestS3kSonicTailsHpz22SegmentTraceReplay`
- `TestS3kSonicTailsHpz2SegmentTraceReplay`
- `TestS3kSonicTailsHpz3SegmentTraceReplay`
- `TestS3kSonicTailsHpzSegmentTraceReplay`
- `TestS3kSonicTailsIcz2SegmentTraceReplay`
- `TestS3kSonicTailsIczSegmentTraceReplay`
- `TestS3kSonicTailsLbzSegmentTraceReplay`
- `TestS3kSonicTailsLrzSegmentTraceReplay`
- `TestS3kSonicTailsMgzSegmentTraceReplay`
- `TestS3kSonicTailsMhz2SegmentTraceReplay`
- `TestS3kSonicTailsMhz3SegmentTraceReplay`
- `TestS3kSonicTailsMhz4SegmentTraceReplay`
- `TestS3kSonicTailsMhz5SegmentTraceReplay`
- `TestS3kSonicTailsMhz6SegmentTraceReplay`
- `TestS3kSonicTailsMhz7SegmentTraceReplay`
- `TestS3kSonicTailsMhz8SegmentTraceReplay`
- `TestS3kSonicTailsMhz9SegmentTraceReplay`
- `TestS3kSonicTailsMhzSegmentTraceReplay`
- `TestS3kSonicTailsPachinko2BonusTraceReplay`
- `TestS3kSonicTailsPachinkoBonusTraceReplay`
- `TestS3kSonicTailsSoz2SegmentTraceReplay`
- `TestS3kSonicTailsSozSegmentTraceReplay`
- `TestS3kSonicTailsSs10SpecialStageTraceReplay`
- `TestS3kSonicTailsSs11SpecialStageTraceReplay`
- `TestS3kSonicTailsSs12SpecialStageTraceReplay`
- `TestS3kSonicTailsSs13SpecialStageTraceReplay`
- `TestS3kSonicTailsSs14SpecialStageTraceReplay`
- `TestS3kSonicTailsSs8SpecialStageTraceReplay`
- `TestS3kSonicTailsSs9SpecialStageTraceReplay`
- `TestS3kSonicTailsSszSegmentTraceReplay`
- `TestS3kSonicTailsZone0cSegmentTraceReplay`
