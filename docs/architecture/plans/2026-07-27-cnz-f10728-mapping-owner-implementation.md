# CNZ f10728 Mapping Owner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Advance standalone CNZ beyond f10728 by restoring the ROM's first
ordinary animation mapping publication after cylinder release.

**Architecture:** Establish the exact native release and animation execution
order before changing code. Protect the confirmed semantic boundary with a
real-behavior test, then make one minimal correction in the owning cylinder or
animation lifecycle rather than assigning a trace-derived mapping.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, S3K disassembly, compressed
trace fixtures, optional bounded BizHawk probe.

## Global Constraints

- Do not inspect, execute, modify, or use LBZ for target selection.
- Do not branch on zone, route, trace identity, frame number, or expected
  trace state.
- Do not assign `$08` as a final visual correction.
- Trace fixtures are comparison-only and remain unchanged.
- Runtime assets remain ROM-backed.
- Commit required architecture, changelog, and frontier artifacts; do not
  push.

---

### Task 1: Establish the native and engine owner sequence

**Files:**
- Inspect: `docs/skdisasm/sonic3k.asm`
- Inspect: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Inspect: `src/main/java/com/openggf/sprites/managers/PlayableSpriteAnimation.java`
- Inspect: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Inspect: `target/trace-reports/s3k_cnz1_report.json`

**Interfaces:**
- Consumes: f10727 ROM release row with mapping `$59`; f10728 ROM ordinary
  animation row with mapping `$08`.
- Produces: a written single-owner hypothesis naming the exact ROM write and
  engine lifecycle difference.

- [ ] **Step 1: Trace the ROM call order**

Read the complete cylinder rider-release branch, player routine dispatch, and
animation-script branch that spans the f10727-f10728 transition. Record which
instruction clears `object_control`, which pass retains `$59`, and which
instruction next writes `$08`.

- [ ] **Step 2: Trace the engine call order**

Follow `CnzCylinderInstance.releaseSlot`, object-control release,
`objectMappingFrameControl`, and `PlayableSpriteAnimation` timer/reset logic.
Identify why the engine's next ordinary animation pass does not publish its
script frame.

- [ ] **Step 3: Resolve ambiguity with a bounded probe only if necessary**

If disassembly plus the trace rows leave multiple possible native writers,
capture execution and `mapping_frame` writes only across f10727-f10728.
Keep the probe untracked and remove it after recording the result.

- [ ] **Step 4: State one falsifiable hypothesis**

Write the owner and expected behavioral transition in the audit draft before
creating a test. Do not propose multiple simultaneous fixes.

### Task 2: Protect the release-to-animation contract with RED/GREEN

**Files:**
- Modify: closest focused test class for the confirmed owner
- Modify: confirmed production owner only

**Interfaces:**
- Consumes: Task 1's native instruction sequence.
- Produces: a test that fails when the release row and next animation
  publication are collapsed or delayed, plus the minimal passing correction.

- [ ] **Step 1: Write one real-behavior failing test**

Use a real S3K playable sprite and cylinder/animation owner. Seed the literal
native precondition (`anim=$00`, twist mapping `$59`, relevant animation
timer/index and object ownership), execute the release row and next ordinary
animation pass, and assert that the release row preserves `$59` while the next
pass publishes `$08`.

- [ ] **Step 2: Verify RED**

Run only the new method with Maven. It must fail on the next-pass `$08`
assertion while passing the release-row `$59` assertion.

- [ ] **Step 3: Implement the minimal owner correction**

Change only the lifecycle state that Task 1 proves differs from ROM. Do not
set mapping `$08` directly and do not add game, zone, route, or frame gates.

- [ ] **Step 4: Verify GREEN and neighboring tests**

Run the new method, its complete focused class, the cylinder suite, and the
closest animation suite. Confirm zero failures and errors.

### Task 3: Verify replay movement and release evidence

**Files:**
- Create: `docs/architecture/audits/2026-07-27-cnz-f10728-mapping-owner.md`
- Modify: `docs/status/trace-frontier-log.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 2's corrected semantic owner.
- Produces: reproducible frontier, regression, and policy evidence.

- [ ] **Step 1: Run standalone CNZ frontier-only**

Use the discovered S3K ROM, `-Xmx6g`, one Surefire fork, worktree-local temp,
and the explicit `TestS3kCnzTraceReplay#replayMatchesTrace` selector. Record
the new first frame, field, expected/actual values, and bounded error count.

- [ ] **Step 2: Run standalone CNZ canonical and scenarios**

Run the same replay without frontier-only mode, then the complete CNZ scenario
class. Record canonical error count and verify that only already-documented
scenario gaps remain in addition to the new comparison frontier.

- [ ] **Step 3: Run explicit non-LBZ canaries and guards**

Run standalone AIZ and MGZ with literal selectors, plus rewind coverage if
production state changed. Record their exact retained or changed frontiers.

- [ ] **Step 4: Write release artifacts**

Document native evidence, the RED/GREEN contract, commands, old/new frontier,
canonical error count, canaries, worktree/commit context, and any known
environment limitation in the audit, frontier log, and changelog.

- [ ] **Step 5: Verify and commit**

Run focused tests again, `git diff --check`, inspect the complete diff, stage
every task artifact, and commit with all required trailers. Do not push.
