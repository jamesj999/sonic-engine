# Signpost Integration Regression Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the ROM-backed S3K signpost production behavior lost during
integration so tests compile and fresh trace discovery can resume.

**Architecture:** Reapply the minimal production semantics from ancestor
commits `ed113599f` and `07b866ced` to the current signpost/result-screen
interfaces. Preserve the newer independent short-retire-tail behavior and keep
all predicates object-state based.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, Git worktrees.

## Global Constraints

- Trace data remains comparison-only.
- No zone, route, frame, trace, or `gameId` carve-outs.
- Preserve `usesShortResultsChildRetireTail` independently of the restored
  results-child timing adjustment.
- Use `sonic1.rom.path`, `sonic2.rom.path`, and `s3k.rom.path`.
- Stage only task-owned files; never use `git add -A`, `git stash`, or
  `--no-verify`.

---

### Task 1: Restore the signpost production contract

**Files:**

- Modify:
  `src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java`
- Modify:
  `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java`
- Test:
  `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSignpostInstance.java`
- Test:
  `src/test/java/com/openggf/game/sonic3k/objects/TestS3kResultsScreenObjectInstance.java`

**Interfaces:**

- Produces:
  `S3kSignpostInstance.ResultsChildTimingAdjustment`,
  `resultsChildTimingAdjustment(boolean, boolean, boolean)`,
  `romVelocityAfterGravity(int)`, and
  `romBumpCheckAvailableAfterCooldownEntry(int)`.
- Preserves: the results-screen constructor's
  `boolean usesShortResultsChildRetireTail` argument.

- [ ] **Step 1: Capture the failing red state**

Run:

```bash
mvn -Dmse=relaxed \
  "-Dtest=com.openggf.game.sonic3k.objects.TestS3kSignpostInstance,com.openggf.game.sonic3k.objects.TestS3kResultsScreenObjectInstance" \
  test
```

Expected: test compilation fails with the twelve recorded missing-symbol
errors in `TestS3kSignpostInstance`.

- [ ] **Step 2: Restore ROM bump bounds and falling dispatch**

Before production edits, add a production-path test to
`TestS3kSignpostInstance` that places a signpost in `FALLING`, supplies an
eligible upward-moving player through `TestObjectServices`, invokes
`update(...)`, and asserts:

- an entry cooldown of `1` becomes `0` without applying the bump;
- an entry cooldown of `0` applies the bump before gravity, publishing
  `yVel == -0x1F4` after the dispatch rather than `-0x200` or a pre-bump
  gravity result.

Run the focused class and confirm this new test fails against current
production ordering before editing `S3kSignpostInstance`.

In `S3kSignpostInstance`:

```java
private static final int BUMP_RIGHT = 0x20;
private static final int BUMP_BOTTOM = 0x18;

static int romVelocityAfterGravity(int velocity) {
    return (short) (velocity + GRAVITY);
}

static boolean romBumpCheckAvailableAfterCooldownEntry(int cooldown) {
    return (cooldown & 0xFF) == 0;
}
```

Order `updateFalling(...)` as:

1. sparkle;
2. bump check when cooldown is zero, otherwise decrement and skip the check;
3. signed-word gravity through `romVelocityAfterGravity`;
4. movement;
5. wall/floor handling.

Use the citations already present in the focused tests:
`sonic3k.asm:176149-176160,176347-176405`.

- [ ] **Step 3: Restore the separate results-child timing adjustment**

Reconstruct `ResultsChildTimingAdjustment` and
`resultsChildTimingAdjustment(boolean waitedForPlayerLanding,
boolean preservesPostObjectBoundary, boolean preservesGroundedOwnerBoundary)`
exactly from `07b866ced`. Do not add the short-tail flag to this selector.

Before production edits, add a production-path test to
`TestS3kResultsScreenObjectInstance` that constructs otherwise-identical
results objects with `NONE` and
`UNSUPPORTED_GROUNDED_COMPENSATION`, invokes `updateCreateGate()` through
reflection, and inspects `createGateFrames`. Assert that the compensation
instance starts one dispatch closer to readiness while both retain the same
`usesShortResultsChildRetireTail` value. Confirm the test fails before
production edits.

Add this exact terminal constructor shape:

```java
S3kResultsScreenObjectInstance(
        PlayerCharacter character,
        int act,
        int waitDurationAdjustment,
        int postControlHandoffDelayEntries,
        int carriedResultsRetireDispatches,
        S3kSignpostInstance.ResultsChildTimingAdjustment timingAdjustment,
        boolean usesShortResultsChildRetireTail)
```

Preserve the current six-argument boolean overload and delegate it to the new
constructor with
`S3kSignpostInstance.ResultsChildTimingAdjustment.NONE`. Store
`resultsChildTimingAdjustment` as a non-final field initialized to `NONE`, so
generic rewind scalar capture restores it safely. The signpost passes the
selected adjustment and `usesShortResultsChildRetireTail` as separate final
arguments.

In `updateCreateGate()`, initialize:

```java
createGateFrames =
        S3kTransitionWriteSupport.resultsCreateGateDispatches(services())
                - resultsChildTimingAdjustment.catchUpEntries();
```

- [ ] **Step 4: Run focused tests**

Run:

```bash
mvn -Dmse=relaxed \
  "-Dtest=com.openggf.game.sonic3k.objects.TestS3kSignpostInstance,com.openggf.game.sonic3k.objects.TestS3kResultsScreenObjectInstance" \
  test
```

Expected: both classes compile and pass.

- [ ] **Step 5: Update task documentation**

Add one concise `CHANGELOG.md` entry describing restoration of the lost
signpost ROM behavior and the trace-discovery build unblock.

Copy and stage:

- `docs/architecture/designs/2026-07-28-signpost-integration-regression-repair.md`
- `docs/architecture/plans/2026-07-28-signpost-integration-regression-repair.md`

- [ ] **Step 6: Commit the repair branch**

Stage only the two production files, both focused test files,
`CHANGELOG.md`, and the two architecture artifacts. Commit with all required
trailers and without `--no-verify`.

---

### Task 2: Verify, integrate, and resume discovery

**Files:**

- Modify when discovery selects a frontier:
  `docs/status/trace-frontier-log.md`
- Modify for the required non-master integration:
  `README.md`

**Interfaces:**

- Consumes: the committed Task 1 repair branch.
- Produces: pushed `develop` with compiling tests and a fresh trace-failure
  queue.

- [ ] **Step 1: Run the full suite in the repair worktree**

Run under JDK 21:

```bash
mvn -Dmse=relaxed \
  "-Dsonic1.rom.path=<discovered-s1-rom>" \
  "-Dsonic2.rom.path=<discovered-s2-rom>" \
  "-Ds3k.rom.path=<discovered-s3k-rom>" \
  test
```

Record every remaining failure. Expected: the twelve signpost compile errors
are absent and no new failure is attributable to the repair.

- [ ] **Step 2: Independently review the implementation**

Review the exact diff against `ed113599f`, `07b866ced`, the current constructor
contracts, the disassembly citations, and the focused/full test results. Fix
every valid issue and repeat until no blocking issue remains.

- [ ] **Step 3: Refresh the integration baseline**

Fetch origin and fast-forward the main-workspace `develop` without disturbing
unrelated working-tree changes. Rerun the full suite and record its exact
baseline result.

- [ ] **Step 4: Merge into develop**

Add and stage the required `README.md` release/change-log summary in the main
workspace, run `git merge --no-commit <repair-branch>`, reconcile additive
`CHANGELOG.md` or trace-frontier conflicts, stage only resolved files, and
complete the merge commit with policy trailers.

- [ ] **Step 5: Run post-merge verification**

Repeat the full suite and compare with Step 3. Reject any newly failing test or
worsened baseline failure attributable to the repair.

- [ ] **Step 6: Push and clean up**

Push only `develop`. Verify the worktree contains no unknown or unmerged
changes, remove it, delete the fully merged local branch, and prune worktree
metadata.

- [ ] **Step 7: Rediscover trace failures**

Enumerate executable replay classes first:

```bash
rg -l 'extends .*TraceReplay' src/test/java \
  | sed 's#src/test/java/##; s#/#.#g; s#\.java$##' \
  | sort
```

Remove abstract bases, guards, and any frozen exclusions, print the final
comma-separated allowlist, then run:

```bash
mvn -q -Dmse=relaxed \
  "-Dsonic1.rom.path=<discovered-s1-rom>" \
  "-Dsonic2.rom.path=<discovered-s2-rom>" \
  "-Ds3k.rom.path=<discovered-s3k-rom>" \
  "-Dtest=<printed-concrete-allowlist>" \
  test
```

Update `docs/status/trace-frontier-log.md` with the exact command, commit
context, results, and selected next target. Commit and push that documentation
when the frontier ledger changes, then continue the green-fleet queue.
