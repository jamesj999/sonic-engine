# Briefing a trace round

Distilled from ~25 consecutive S3K/S2 trace rounds on 2026-08-14/15, in which **the
orchestrator's candidate causes were wrong eight rounds running** and every round that ignored
them and instrumented the decision site was right. This is not a confession; it is a measured
result about what makes these rounds succeed. Brief this way.

Companion to [trace-replay-bug-fixing](../../.agents/skills/trace-replay-bug-fixing) — that
skill is the procedure, this is how to hand the work over.

## Index

One hundred and twenty-six rules and several worked sections, accumulated across many rounds. The narrative
below is the argument for each; this table is for finding one mid-round. **The measurement
hazards are the ones to re-read before reporting a number** — every single one produces output
that looks like a real result.

### Briefing and framing

| Rule | In one line |
|---|---|
| The one that matters most | Supply the measured symptom, never your hypothesis |
| 1 | Supply the measured symptom, never your hypothesis |
| 2 | The first-reported field is alphabetical, not causal |
| 9 | A latent correction can be worth more as evidence than as a fix |
| 17 | A compensation stack is load-bearing — remove it in one move |
| 3 | Name the fitted-constant trap explicitly, in the brief |
| 4 | Make "found-not-fixed" a full success, in writing |
| 6 | Stamp every number with the commit it was measured at |
| 7 | Tell the round you are probably wrong |
| 31 | "No field reported" states which check fired, not what is wrong |

### Evidence and inference

| Rule | In one line |
|---|---|
| 5 | Verification instructions that have actually caught things |
| 24 | Diff failure *messages*, never class names |
| 28 | Check whether an absence is in the data or only in your view |
| 29 | Check a suspicious delta against both endpoints |
| 33 | BizHawk reports the PC *after* the storing instruction |
| 32 | A row's emulator frame is `bk2_frame_offset + row + 1` |
| 40 | A comment reasoning about which engine hook runs first — in either direction — is the tell for a fitted constant; they come in families |
| 10 | The symptom's axis is not evidence about the cause's axis |
| 11 | Build the disconfirming case by hand before trusting a ratio |
| 12 | Some divergences are not frame-derivable; hunting a predicate is itself the error |
| 13 | "A did not match B" has a third answer — the index is broken |
| 14 | Closure ownership is the row after, never the row before |
| 18 | An identical error fingerprint means the same lever, whatever the site |
| 19 | Port the READ, not the event that last wrote the field |
| 23 | The headline field is not the earliest divergence — read the histogram |
| 47 | Measure the quantity, do not reconstruct it |
| 48 | Object execution order is observable behaviour |
| 53 | A sign-flipping single pixel is phase — a creation frame early or late — not a wrong constant |
| 56 | `loc_XXXX` is a ROM address; citing it as a line number lands on plausible unrelated code |
| 57 | A cited frame number can itself be an artefact of the defect it justifies |
| 58 | The count of cancelling errors is usually understated; two is rarely the whole set |
| 59 | When the engine looks right and the recording looks broken, the engine is probably missing a ROM bug |
| 60 | Right family, wrong mechanics is still wrong — test the mechanism's own prediction |
| 61 | An absence in a routine is not a positive fact about it; read one level deeper |
| 63 | Resolve a divergence's values to their disassembly names before hypothesising |
| 64 | A profile no matrix runs is covered by nothing; know which ones yours omits |
| 65 | A shared exception type is not a shared defect; instrument the throw's contents |
| 66 | A substring match is not a value match; `0 errors` matches inside `5840 errors` |
| 67 | A ratchet only checked at raise time will always be raised; ask for its whole history |
| 68 | An in-place routine change is not a spawn; look for a routine byte changing on an occupied slot |
| 69 | Check that your two numbers are the same kind of measurement before differencing them |
| 70 | Review the artifact, not the account of it; a rejected candidate never rides a merged branch |
| 71 | Validate a classifier against a hand-verified control, or discard its output |
| 72 | Prove a new assertion fires; never place one where a `finally` can swallow the real failure |

### Measurement hazards — all produce plausible output

| Rule | Signature | What it looks like |
|---|---|---|
| 25 | `-Dmse=off` missing | CLI `-D` properties silently never reach the fork |
| 26 | Self-report about process state | A suite *is* running when you were told none was |
| 27 | `git checkout -- <path>` | Restores from the **index**, not HEAD |
| 30 | Mass errors | An environment artefact until proven otherwise |
| 34 | Two Maven runs in one worktree | `target/test-classes` clobbered; "No tests matching pattern" reads as a bad filter |
| 8 | A check blind on the files at risk | A guard that passes because it never looked |
| 15 | A profile's includes bounding a sweep | Classes silently outside the run, read as absent |
| 16 | A control pinned to a moving ref | `origin/develop` shifts under the arm mid-round |
| 20 | An unstable class used to attribute | Flapping read as the change's effect |
| 21 | A cross-tree revert | The checkout's own differences attributed to the diff |
| 22 | Rebasing a stacked branch | Append-only files duplicated silently |
| 45 | A null from an instrument nobody installed | Absence of output read as absence of the thing |
| 46 | A silent probe | A build that never compiled, reported as a clean run |
| 49 | An edit assumed to be on the path | A null result from code that never ran |
| 51 | A parked measurement quoted for a diff | A verdict about a whole tree, attributed to one patch |
| 52 | A caveat written down but never checked | A limitation that reads as handled |
| 73 | A tree reset or rebase with a live `target/` | Stale reports that read as current — right names, plausible numbers, another commit |
| 74 | A timing fix validated on one recording | ROM-derived, cited, improves its fixture, and one frame wrong |
| 75 | "Nothing touches your path" vouched for comparability | Totals shift because the shared reporting surface moved |
| 76 | A candidate judged on a derived column | A correct fix rejected by a fixture already failing the axis in bulk |
| 77 | A target downstream of the segment's first error | Rounds spent on inherited lateness that no local fix can reach |
| 78 | A probe aligned end-to-end on two monotone series | "N of N rows agree" — one comparison restated, reversing under a real reference |
| 79 | A citation whose numbers were refreshed but whose claim was not re-read | A false statement wearing a freshly-audited look |
| 80 | A derivation that explains everything | One unchecked premise, fitted so tightly it convinces — and points the wrong way |
| 81 | A new comparison that has never been seen to fail | Green from a code path the suite never enters |
| 82 | A stream whose every byte is comparable | Blocked anyway — rows not attributable to an engine object |
| 83 | A stream promoted from untyped to typed | Untyped-keyed formatters and probes stop firing, silently |
| 84 | A duration in frames from an on-change stream | The sampler's period reported as the defect's duration |
| 85 | An object aligned to the recording by its own coordinate | Perfect agreement that survives inserting an idle frame |
| 86 | A ranking whose metric embeds a parsing choice | Relocation counted as absence; the wrong object at the top of the list |
| 87 | A delta read at the wrong intra-frame write of a folded object | A plausible defect, and a guard that would make the fixture pass wrongly |
| 88 | A probe's text report parsed as data | Correct code at the top of a defect ranking, twice recommended |
| 89 | A folded class documenting N ROM slots and reserving none | Every later object's slot index shifted; a deficit that is 100% structural |
| 90 | "Not observable" asserted for a whole run | Rounds scoped around a gap that does not cover the rows in question |
| 91 | Two threads merged because their symptoms match | An inherited framing that already cost the first thread its rounds |
| 92 | A greppable tell quoted as a defect count | Fifteen candidates, one confirmed, four matching exactly with zero divergence |
| 93 | A correction that changes no value anywhere | An unverifiable edit to verified code, indistinguishable from a wrong one |
| 94 | A one-directional deficit metric | An over-count larger than the shortfall, invisible; the wrong defect named |
| 95 | A `FixBugs` block read as a gate | Correct retail code reported as a defect; both arms did the work |
| 96 | A reading that lets the round end | Findings and blockers both stop the check that would overturn them |
| 97 | A control arm pinned to `origin/develop` | Two arms built from different trees, reported as reach-proven-equal |
| 98 | A class measured with `-Dtest=` under a profile that would not select it | A green read as matrix coverage, from an arm that never ran the class |
| 99 | A `jmp` target read as a one-line helper | A fall-through past an end-of-function banner, hiding the write everyone was hunting |
| 100 | A directory sized by grepping one idiom's name | Five idioms, one greppable; the correct group understated by half |
| 101 | A clock conversion calibrated at one point | A one-row error in a nine-link causal chain, invisible and load-bearing |
| 102 | A cross-game ranking grouped by raw object id | Two names for one defect, in two tables, as both a shortfall and an over-count |
| 103 | Artefacts analysed after a build that never ran | The previous run's files, right names, right count, wrong commit |
| 104 | A true invariant enforced in the shared helper | Live-but-unwired objects at the callers that keep the reference |
| 105 | A comment citing a ROM line and a number the code never produces | The defect, documented by its own author, with its acceptance test attached |
| 106 | A green matrix quoted for a change nothing compares | Inertness inferred from a suite that measures no field the change touches |
| 107 | A routine installed mid-frame and run in the same frame | One tick early, structurally; the fitted fix is a skipped tick |
| 108 | The symptom is one frame; the class is one intra-frame slot | Q1 cuts most false positives by reading; survey by role |
| 109 | On a moving object, `spawn` is not an identity | Re-key on slot; an inverted verdict looks clean |
| 110 | A batch instrument needs a positive control | "Nothing moved" also means the arm never ran |
| 111 | `-Dtest=` overrides patterns, not tag filters | A tag-excluded class runs nothing and still says BUILD SUCCESS |
| 112 | Adjacent SST bytes hide word writes from a byte survey | Check neighbours; "invisible at frame granularity" may be a missing column |
| 113 | A citation can be wrong and still agree with the right answer | Reach the row through the object that loads it, not the first matching grep |
| 114 | A true general principle can explain away the key evidence | "Downstream by construction" dismissed the job naming the same subsystem |
| 115 | A proxy that survives one question silently answers a different one | Three inversions in one family; measure the quantity directly |
| 116 | A classification keyed on one game's vocabulary misses the others | S1 spells it obRender; 2 sites became 6 |
| 117 | A constant in a shared class is not a constant of every game | 0x3FF was Sonic 1's; check the callers before picking a disassembly |
| 118 | A stale native temp dir reports as a catastrophic regression | rm -rf target/test-tmp; grep for UnsatisfiedLinkError before quoting Errors |
| 118a | Clearing `target/test-tmp` costs the NEXT run | The first classes to run in the cleared tree fail to extract the natives and report mass `UnsatisfiedLinkError`; discard that run and rerun with the natives present rather than quoting it. Three separate runs today. Rule 118 is still right -- the cost is one throwaway run, not a reason to keep a stale dir |
| 119 | "No arithmetic exists between them" is an argument, not a measurement | Measure both ends first; a chain read bounds only that chain |
| 120 | Model coverage is a per-branch question, not a per-routine one | Two +4 writes on two arms; the engine implemented one and cited the routine |
| 121 | A probe log without a frame delimiter fits two readings | Print the driver row index per frame; both groupings match the bytes |
| 122 | A CLI `-DargLine` never reaches the surefire fork | A silent probe, indistinguishable from a branch that never ran; gate on an env var |
| 123 | Run a survey so the known member must appear in its output | An empty result and a broken matcher look identical |
| 124 | A stall in the reference series makes two offsets match identically | Classify only where it is strictly monotonic; walk, do not sample |
| 125 | A probe that never compiled looks exactly like a predicate that never fires | No Tests-run line and no probe file; ask why the file is absent |
| 126 | A uniquely-fitting event is not evidence it is *your* event | Rarity is not identity; put a clock on it before believing it |
| 127 | A probe can fire in the right class and the wrong harness | Plausible output from a path the test does not use |
| 128 | `stand_on_obj` is a slot index, not an object id | Both readings name a real object; resolve through slot_dump |
| 129 | The origin may be in a field nobody walked | Carry neighbouring fields; print velocity beside position |
| 130 | Read the candidate mechanism's constant before probing for it | An empty probe on the wrong path reads as "not modelled" |
| 131 | A red unit test beside an already-landed fix | It may be encoding the old behaviour, or it may simply never supply the per-frame state the new code reads (`snapshotPreUpdatePosition`); both look like a stale assertion. Complete the harness before inverting anything, and run the revert-first proof -- a repaired test that passes against BOTH trees is not evidence for the fix |
| 132 | A gate probe must cover the early returns you were not thinking about | The suspected branches log; the deciding one has no line at all |
| 133 | A field can be the origin though a walk shows it not propagating | Propagation is a property of a path; ask what reads it |
| 134 | A defect's own consequence contaminates its own count | 154 of 253 rows were the post-restart death animation |
| 135 | Brief HOW to measure, not WHAT to expect | Four forks, four "neithers"; the measurement instructions changed every answer |
| 54 | A probe read mid-frame | A clean, consistent, plausible offset that does not exist |
| 62 | A probe anchored by row arithmetic | Stable self-consistent state on the wrong rows entirely |
| 55 | An error count compared across different depths | A count that rises on a fix, or falls on a truncation |
| 35 | Failed compile | A small log that reads as a short test run; also `-Ptrace-replay` silently ignores a CLI `-Dsurefire.argLine=` |
| 37 | Run-order swap | Equal totals, different members, all passing alone |
| 38 | Backgrounded `mvn` dies with its shell | The wrapper's exit 0 with a log that just stops |
| 41 | Truncated arm | Prove completeness by class-name set and `Tests run: 0,` lines, not by totals |
| 42 | Truncated message diff | Two different failures compare equal on a shared prefix |
| 50 | A failing-set diff that drops nested `$` class names | Two different failures collapsing into one row |
| 43 | Too many concurrent rounds | OOMs, GLFW init failures and contended arms that report *fewer* red |
| 44 | An axis count across arms of different depth | A count comparison that was never like-for-like |

### Operational

| Rule | In one line |
|---|---|
| 36 | The branch is the artifact, the worktree is scratch — disposal is the lead's job, persistence is the round's |
| 39 | Create round worktrees copy-on-write (`cow-git-worktree` skill) |

Two attribution hazards that are not rules but bite the same way: `target/trace-reports/<runId>_seg<N>_report.json`
is keyed on run id and re-based segment index only, so under `forkCount=4` two classes replaying
one run overwrite each other's reports — only single-class runs are safe to quote from report
files. And the default suite carries a large pre-existing red set plus order-dependent churn of
about three classes between two runs of the same tree, so a small delta is noise unless you can
attribute it.

## The one rule that matters most

**Supply the measured symptom. Do not supply your hypothesis.**

If you must mention a candidate cause, label it explicitly as a candidate and tell the round to
rule it out rather than confirm it. Better: tell the round to derive its own list from the ROM.

Rounds that were handed hypotheses tested the hypotheses. Rounds that were handed symptoms
found the causes. Every time.

Worked examples from one session:

| briefed as | actually was |
|---|---|
| "plane-drop landing, 5px low" | the AIZ1 intro **spin dash** — the +5 is the release's `addq.w #5,y_pos` |
| "half-pixel loss with identical `x`" | an artefact of **field sort order**; six fields diverged together |
| "engine still moving where the ROM stopped" | the engine stopped correctly and **resumed too early** |
| "the arena's level load is missing" | the load ran correctly two frames before the failure |
| "eight completions the engine never submitted" | submitted, with a **different sha256** — different art, not absent art |
| "`TOTAL_ZONE_COUNT` is the off-by-one" | that constant has **no reader anywhere** in `src/` |

Four candidate causes offered on the camera round: none was involved.

## Second rule: the first-reported field is alphabetical, not causal

**Tell every round to dump every field that differs on the first failing frame before forming a
hypothesis.** In one session this misled four times, and twice the decisive clue was a column
nobody was looking at:

- `rings` expected 3, actual 53 → the engine had *gained* 50 rings → named the exact ROM branch
  in one step, where the reported `player_animation_id` never would have.
- `x_sub` sorting ahead of `x` produced an entire "half-pixel rounding" theory for what was a
  slope-table hand-off.

## Third rule: name the fitted-constant trap explicitly, in the brief

When a delta is small and constant, the fix that closes it is visible from the outset and is
almost always a rule-3 violation. Name the specific number and forbid it:

> *"Do not add `0x8000`, or half a pixel, anywhere, because the delta equals it. The fix must be
> 'the engine now performs (or omits) the ROM operation at this cited line', never 'the engine
> adds the observed difference'."*

This works. Rounds declined `+6` on a camera, `0x8000` on a sub-pixel, a zeroed speed, and a
one-frame scroll count — each time explaining *why* the ROM produces its value instead.

**The trap wears disguises.** It is not always a physics constant:

- a **zone count** picked to stop an `IndexOutOfBounds` — plumbing is where rule 3 gets waived
  by accident;
- a **frame count** ("scroll for exactly one frame") with no ported ROM owner for the stop;
- an **engine stamp** asserting work it did not do, to match a recorder.

## The discriminator for "my fix reds a green class"

This comes up constantly and the two cases look identical in a test report. They are not.

> **Red with an unlocated owner: hold. Red with a fully-traced propagation chain from a cited
> fix: merge and document.**

Both arose in one session and the rule decides both correctly:

- **Hold.** Removing a camera clamp produced 12 newly-red classes and zero greens. A mechanism
  had been taken out and *nobody could say what performed its job* — the red was a symptom of an
  incomplete model. (It later turned out the removal was simply wrong: a generic setup routine
  reinstated the value ten lines downstream.)
- **Merge.** A ROM-cited badnik fix produced two errors 27,600 frames later, and every link was
  traced and cited: spawn-gate fix → one-pixel position → slot-occupancy permutation → a ROM gate
  keyed on the slot index → a ring collected four frames early. The fixture's own aux confirmed
  the *fixed* side matched the ROM at the divergence point.

The trap to name explicitly: **"the green was thin" is not the argument** — it's what people say
right before landing something wrong. The argument is that the green was **accidental**, and you
have to show that. In the merge case, occupancy already diverged from ROM on 2387 of 2387 sampled
frames *on both trees*, and the class compared no object identity at all: the green was a
slot-phase lottery, not a baseline the fix broke.

**When you do land a knowingly-red class**, use the deliberate-red convention rather than an
inverted assertion pinning the expected failure. A pinned failure fails loudly on *any* change —
including improvements — and teaches the suite that the red is a contract rather than a frontier.
Put the marker in the **class's own javadoc**, so the explanation is one hop from the failure;
the frontier log carries the measured first error and count (its job, updated as things move), and
`known-discrepancies` carries the mechanism and the removal condition.

## A collision to avoid: "don't undo landed fixes" vs "don't fit constants"

Standing briefs accumulate a *do not undo* list — the fixes already landed this session. They
also forbid fitted constants. **Those two collide when a landed "fix" is itself a fitted
constant**, and a round that obeys both literally is stuck.

This happened: a per-slot table of 13 hardcoded byte angles, added by two earlier commits, turned
out to be the proximate cause of the frontier under investigation. The round did the right thing —
it did not undo the fix, and it **flagged the conflict explicitly** — but the brief should not
have put it in that position.

**Write the exception in:** *do not undo a landed fix, **unless it is itself a fitted constant or
compensation**, in which case say so prominently and treat removing it as the finding rather than
the fix.* And when a round reports that shape, the right response is usually to document the
mechanism and a removal condition rather than to delete the table — because deleting it may red a
class where the fitted value happens to be right.

## Fourth rule: make "found-not-fixed" a full success, in writing

State it. Rounds will otherwise optimise for a landed diff.

- A round that measured a change as **12 newly red / 0 green** and discarded it produced more
  value than a landed fix would have.
- A round that scoped a cross-cutting change as `larger-than-one-round`, with a four-stage
  decomposition and the note that stage C is not landable without stage A, saved a half-landed
  migration.
- A round that found a mechanism but declined to model it — because doing so required a fitted
  frame count — left the next implementer a clean target instead of a wrong fix.

For changes to **shared machinery**, say outright that a well-scoped `found-not-fixed` beats a
half-landed change, and require the blast radius to be measured before anything is written.

## Fifth rule: verification instructions that have actually caught things

- **Diff the red-class set by name, both directions.** A falling failure count with classes
  vanishing is *comparator starvation*, not progress. This session produced it twice.
  `comm` is **unsafe** here — these class names are not in C collation order and it silently
  mis-reports; use an explicit set difference.
- **Report `framesCompared` for anything that greens.** A green at fewer rows is starvation.
- **Clear `target/surefire-reports` before every run.** A stale XML counts as a pass; a
  truncated run reports *fewer* red. Give the expected total and say that below it is truncated
  and above it is stale.
- **Wait on a terminal marker the run emits, never on the absence of a process.** Polling for
  "no surefire process running" fires within seconds, because Maven spends the first minutes in
  `testCompile` before any surefire process exists — so "not started yet" is indistinguishable
  from "finished". A round that trusted it would have set-diffed a directory of stale XML from
  the previous run and reported it as a clean result. Poll the build log for
  `BUILD SUCCESS|BUILD FAILURE`, which cannot appear before the run starts. This is the
  stale-XML trap above arriving from a different direction, and clearing
  `target/surefire-reports` does not protect against it — an early-firing wait reads the
  *current* run's partial output as final.
- **For the run chains, a control in a *different worktree* is not sufficient — use the same
  worktree.** `TestS3kSonicTailsCompleteEmeraldRunChain` has been observed failing with **two
  different exceptions in two worktrees at the same commit with no local changes** (an
  `awaitBoundary` step-cap walk-failure in one, `IllegalStateException: non-exportable pending
  hardware submission` in the other), and it survives `mvn clean`. A round spent a bisect cycle
  attributing that to its own change before an empty-diff control refuted it. The known mechanism
  is Surefire's filesystem `runOrder` differing per worktree, which changes which order-dependent
  class runs first; `-Dsurefire.runOrder=alphabetical` reduces but has not eliminated it. When a
  chain class changes its *failure mode* rather than its pass/fail, verify by stashing nothing and
  re-running the **same** worktree with the change reverted, before attributing anything.
- **"Gate-clean" is not evidence when nothing observes the changed behaviour.** Before treating an
  empty both-way set-diff as approval to land, ask which committed trace actually compares the
  field the change moves. A round found that routing S1's results mode through the hardware-timed
  scan was gate-clean at 790/4 — and withheld it, because **no committed trace compares S1/S2
  results-screen queue state** except two chains that are already red, so the suite could not have
  seen the one-row preparation shift the change introduces. Where a change alters shared behaviour
  in a span no fixture covers, say so and treat the clean gate as *absence of evidence*, not
  evidence of absence. The honest options are to extend coverage first, or to land it explicitly
  flagged as unobserved — not to quote the green.
- **NEVER conclude *absence* of divergence from a non-cascading filter.** Scanning for
  non-cascading rows is the right way to *find* a defect and the wrong way to *rule one out*. A
  round claimed "no non-cascading error on any physics field, therefore the engine's inertia
  matches" and built four subsequent rounds on it; the physics divergences were present and all
  flagged `cascading: true`. The lane that discovered this blind spot then walked into it twice, so
  treat it as a hard rule rather than an awareness point: to establish that a field does *not*
  diverge, read that field's rows directly.
- **The comparator's `cascading` flag suppresses cluster ONSETS, not just repeats.** A 2422-error
  `dynamic_art` cluster sat in the first report a round read this session and was passed over
  because it was almost entirely `cascading: true` — only three rows were non-cascading. Scanning
  for non-cascading rows is the right first filter and it will hide any defect whose own onset the
  flag classifies as cascading. Before concluding a report holds nothing, check the cascading rows'
  *distribution* as well as their count.
- **For `dynamic_art`, count drift, not errors.** One wrong edge shifts every later `edge_ordinal`
  and `transfer_id`, so the error count is a multiplier on a small number of real events — 2422
  errors resolved to a handful of discrete timing events plus two bursts. Measure cumulative engine
  edges minus ROM edges and read the *regimes*: a run-ahead followed by a catch-up is a service-rate
  signature, not spurious generation. And do not assume a steady reference rate; the ROM's own edge
  rate across one window ran from 32 to 363 per hundred frames, and the divergence ramp began
  exactly at the burst.
- **An empty probe output proves nothing until the build is confirmed.** Two rounds produced no
  probe output and nearly read it as "this code path never runs"; both were compilation failures.
  Before drawing any conclusion from absence, show the probe compiled and ran — for Lua, `luac -p`
  plus a hit on a hook you know fires; for engine instrumentation, a line that prints
  unconditionally. This is the same shape as the recorded-stream trap: **absence is only evidence
  once you have shown the thing that would have reported presence was working.**
- **A tidy number is not evidence for the model that produced it.** A round measured an object
  pass ending 21 slots early, observed that `21 x $40 = $540` exactly, and concluded the loop
  counter had been "consumed a whole number of extra times" rather than overwritten. The arithmetic
  was true and the mechanism was wrong: the counter is written absolutely with the constant `$5D`,
  which merely happens to look like a plausible count. The clean factorisation made the wrong model
  feel confirmed, and it was adopted into the record and into the reviewer's vocabulary before the
  disassembly was read. When a number factors neatly, check whether a different mechanism produces
  the same number before treating the factorisation as support.
- **An exception thrown while handling a failure replaces the failure.** A round's real diagnostic
  -- a coordinator invariant violation -- never reached surefire, because `failRun`'s own cleanup
  threw `PendingRecordedSubmissionsException` over one leftover submission and that was reported
  instead. The round spent time on the wrong blocker. Anything routing through a failure handler
  must log its diagnostic **before** cleanup runs, and when a reported error looks like it belongs
  to teardown rather than to the assertion, suspect that it does. Same family as the empty-probe and
  stale-stream traps above: **the reporting channel misleading you about the thing being reported.**
- **When a metric is cumulative, its inflections are not events.** A brief asked what happens at the
  frame where an edge-drift curve resets, on the reasoning that a catch-up is the most informative
  thing in the window. Nothing happens there. Drift is the integral of a per-frame difference, and a
  *constant* engine error against a ROM rate swinging 13 to 105 submissions per fifty rows produces
  ramps where the rate is high and reversals where it is low. One persistent error explained a ramp,
  a reset and a second ramp, none of which is a happening. Before hunting a cause at an inflection,
  check whether the reference rate alone produces it — and prefer the rate profile, which is
  background, over the inflection, which is an artefact.
- **When a defect has a sharp onset after a matching run, ask what made the code path observable,
  not what changed in it.** A one-step animation slip began at row 6601 after hundreds of matching
  rows, which looked like proof that the code was correct until something changed. It was not:
  `g_speed` collapsed to zero at 6600, taking the computed frame duration from 0 to 4, and **while
  the duration is zero the ordering under suspicion cannot be observed at all** — a decrement on
  zero is immediately negative, so every frame advances regardless of how decrement, reload and
  advance are sequenced. The defect had been present throughout and only became expressible at 6601.
  This costs one fixture column and can retire an apparent contradiction outright, so ask it before
  concluding that a matching prefix exonerates the mechanism.
- **Diff a rebase against its new base on non-subject paths before trusting it.** Rebasing a trace
  fixture onto current `develop` produced a commit that silently reverted 20 lines of `README.md`
  and 7 of `docs/agent-workflow/briefing-trace-rounds.md`, neither touched by the original commit;
  the conflict markers showed only one file. Check with
  `git diff <new-base>..<rebased> -- ':!<subject-paths>'` and expect it to be empty. This is the
  same hazard as the silent-revert merge already recorded in the frontier log, arriving through
  rebase rather than merge.
- **Never `git stash` in this repository**, including `--keep-index` for a throwaway control. There
  are ~26 stale collaborator stashes and a failed push/pop is destructive. To take a control with a
  change removed, remove the files in place instead. A round that reached for it caught itself, but
  note the measurement would have been false anyway: the run under the stash still had the fixture
  present, so it was a control in name only.
- **A clean break beats stable-or-growing as a first discriminator.** Asking whether a delta is
  stable or growing distinguishes a one-shot discrepancy from a rate error, and both are useful. But
  the sharper question is whether the two sides are *bit-identical* up to the divergence frame: a
  gradual drift shows small disagreements earlier, while a clean break at a single frame says a
  discrete event fired on one side only. On one round that took the lane to an input lock in one
  step rather than into physics, because every position, velocity and status field matched exactly
  through the preceding frame.
- **On an accumulating defect the reported delta is a lower bound, not the target.** The magnitude
  rule below assumes the divergence you can see is the size of the effect. That holds for a
  one-shot discrepancy and inverts for a rate error: a sidekick reported two pixels out turned out to
  be stepping at double the ROM's rate, with the comparator's first hit merely the row where the
  accumulation crossed the threshold. Before sizing a mechanism against a reported delta, check
  whether the delta is *stable* across the window or *growing* — sizing a rate error against its
  first observation aims at the wrong number entirely.
- **Check the magnitude before accepting a signature match.** A documented pattern fitted a
  divergence qualitatively -- rolling-air sliding into a flush wall -- and was proposed as the prime
  candidate. Its arithmetic never fitted: the mechanism offers a 3-pixel shortfall (rolling shrinks
  `x_radius` 9 to 7) against a divergence of eight rows at ~8 px/frame, about 64 pixels. The
  mismatch was computable at the moment the candidate was proposed. A skill that documents a
  signature tells you the shape and not that your instance matches it, so before adopting one, check
  that the effect it predicts is the size of the effect you have.
- **A model must predict outside the case that motivated it.** A round derived a structural rule
  from one segment, where all six of its later gaps matched exactly, and was one step from reporting
  it as general. That segment was a back-to-back drain of a freshly filled queue, which most
  segments are not; the rule survived only after testing on nine continuous-drain segments across a
  28-segment stream, and after restricting the comparison to cases where the quantity means
  anything. **One segment agreeing with a model is the segment-level form of "a green fixture proves
  the fixture."** When setting a falsifiability test, require the prediction to hold somewhere other
  than the case it was derived from — otherwise a model can pass the stated test and still be fitted
  to its origin.
- **The directional check also scopes: use it to exclude families, not just candidates.** Before
  investigating a proposed cause, ask whether it predicts the observed sign — and whether it predicts
  the observed *extent*. "Mode is wrong across twelve rows" cannot be produced by any single-frame
  mechanism, which excludes the entire one-frame class before any member of it is examined, including
  one the same lane had derived an hour earlier. One question, applied to the shape of the symptom
  rather than to a candidate, can retire a family.
- **A chain of true links can still support a false conclusion, if the effect is absorbed.** A round
  established that a hold exists, that its change causes the hold, and concluded the downstream phase
  shifts later. All three statements were true and the conclusion was wrong: the 16-frame hold sits
  inside a 757-row screen whose length is set elsewhere, so it changes nothing measurable. Measure the
  span you claim moved, at both endpoints, with one variable — do not infer that a real delay
  propagates. The most convincing wrong stories are the ones where every individual link checks out.
- **A same-value/different-outcome question needs both sides instrumented, not either.** Offering
  "instrument the engine or probe the ROM, whichever is cheaper" is a false economy when the question
  is why two implementations reach opposite conclusions from the same inputs: the engine side alone
  yields a value with nothing to compare against, and the ROM side alone yields the same. The cost is
  both, structurally, and a brief that presents it as a choice under-budgets the round.
- **Test the premise that admits the cheapest disproof, before elaborating a mechanism.** A thread
  inferred "a character stopped dead at a fixed x means a wall" and spent **nine rounds** eliminating
  probe offsets, plane selection, solidity bits, quadrant dispatch and collision-data layers — every
  elimination correct, and all of them inside a subsystem that was never involved, because the
  layout word at that position is zero and there is no wall. One probe on the layout would have
  settled it at any point. When a chain of eliminations keeps coming back faithful, suspect the
  founding inference rather than the next layer: a subsystem that is faithful everywhere you look may
  be faithful because it is not the one at fault.
- **A layer list must include the layer above it.** Asking "which Chunk, which Block, which solidity
  attribute, which height mapping" presupposes something is there. None of those questions can return
  "the layout is empty", which was the answer. Before enumerating layers, ask whether the thing being
  layered exists at all.
- **Before recording a consequence, ask whether one search settles it.** A round drew a substantial
  conclusion -- "the capture is correct and insufficient" -- and the next round refuted it with a
  single grep of the fixture for a fingerprint, which appears zero times in 242 edges. The expensive
  speculation was disproved by the cheapest possible check, and that check was available before the
  paragraph was written. Pair this with the empty-probe rule: cheap searches belong *before*
  conclusions, not after them.
- **A kill condition that only pays out when it fires is half-used.** A surviving kill condition is
  not a null result: it forced the next measurement rather than ending the line, and that measurement
  is what overturned the round's own consequence. State kill conditions so that surviving tells you
  where to look next, not merely that you may continue.
- **Re-read a source you have already used, when it is the source of a surprising absence.** Four
  rounds turned on a recorded arm that was missing from a stream. The explanation was a comment in
  the recorder, forty lines from the fingerprint function the same lane had used to build its reverse
  map: level-load arms are discarded by design and never reach a trace file. Having read a file once
  makes it *less* likely to be re-read, not more. When an absence is doing load-bearing work, go back
  to the thing that produces the data before reasoning about what the absence means.
- **Ask for an inconvenient result to be explained, not accepted.** A brief pre-committed a round to
  explaining an outcome that would otherwise have looked like confirmation — "if the ROM does use
  AddPLC then the entry should appear in the recording and it does not, so that outcome would need
  explaining". Without it the round would have read `AddPLC`, seen it agree with the engine, and
  stopped. This does work no falsifiability test does: it binds the *agreeable* branch as well as the
  disagreeable one.
- **Survey the mechanism, not just the thing you plan to change.** A brief asked whether a counter
  had a second instance elsewhere -- a sensible pre-implementation check. Asking it surfaced
  something larger: an owner for adjusting that counter already existed, handling the mirror-image
  case, with a guard stating the very invariant the new work needed to preserve. The planned design
  would have added a second axis beside the mechanism built to make the axes meet. "What else does
  this?" is a narrower question than "what already owns this?", and only the second finds a
  collaborator you were about to duplicate.
- **A partial run is not a result, however suggestive.** If a gate is killed or interrupted,
  say so and quote no numbers from it. "465 of 790 with 4 red, consistent with the two known
  regressions" is a reasonable thing to *notice* and an unreasonable thing to *report*.
- **Trace-report filenames collide** between standalone and per-segment classes, so a per-class
  `errorCount` read from a full sweep is not attributable. A class's own surefire assertion
  message is immune — prefer it.
- **Re-run any newly-red class in isolation on *both* trees before attributing it.** Reused-fork
  ambient flakes move counts run to run.
- **Tell the round to establish its own control** rather than diffing against numbers in the
  brief.
- Name a **cross-check**: a class covering the same content by a different route. *"If your fix
  greens the segment and reds the complete-run, the fix is wrong"* is the any-BK2 bar with a
  concrete witness attached.

## What a productive round actually looks like

A lane that took one S1 defect from "the chain is blocked" to a landed fix corrected the summary
written about it, and the correction is worth keeping as calibration:

> Eleven rounds, one code change of about forty lines, three retracted claims of my own — two of
> which had already been merged. Most of what I produced was finding out that six earlier
> explanations were wrong, four of them mine, and the method rules came out of those rather than out
> of the fix.

That is a reasonable ratio for a defect nobody understood at the outset, and it is **not** the ratio
a summary implies. Two other threads the same day ran to ten and fifteen rounds with comparable
shapes — one spent nine correct eliminations inside a subsystem that was never involved, because the
founding inference went untested.

Expect this. A round ending in *"I measured X, it is not the cause, here is what that rules out"* is
a good round. A round ending in a landed fix resting on an unmeasured step is not, however green the
gate — and several such fixes were caught here only because someone went back to ground that had
already been accepted.

## A briefing failure mode: the false dichotomy

The general form, arrived at after four instances: **when both offered options presume the current
behaviour is correct-as-specified, neither can express "the spec was already being violated".** That
was the answer twice — an invariant and a traversal both correct with the defect outside the pair,
and a port whose documented contract the implementation already contradicted, so closing the gap
claimed no new authority. Before offering two options, check whether both assume the existing
behaviour matches its own stated contract, and if so add the third: that it does not.


Over one session a reviewer posed four discriminators as exclusive choices and was wrong every
time, in three different ways:

- *"Does recorded admission span a whole visual run, or is it re-activated per prepared level?"* —
  **neither.** It begins once, at the live-to-recorded conversion after control release.
- *"Is the twin-tails DPLC driven by the body's animation state, or does it have its own trigger?"*
  — **both.** The body selects the script on change; the tails then advance on their own timer.
- *"Is the divergence at a selection, or mid-script?"* — **both**, and answering only the first half
  would have produced a partially-effective fix that invited stopping.
- *"Is the invariant wrong, or is the traversal wrong?"* — **neither.** Both are right, and the
  defect is one frame of ROM phase that the traversal newly exposes because before it the wait never
  happened at all.

Each framing pointed at a fix that would look partially effective. Write discriminators as open
questions unless you can state why the alternatives cannot both hold — and expect a third case where
neither holds and the mechanism sits outside the pair. Where a hedge is needed, phrase each
proposition separately: *"if the sequence matches and the divergence is upstream"* is one conditional
bundling two independent claims, and it cannot express the case that occurred, where the sequence did
not match and the upstream was correct.

## Sixth rule: stamp every number with the commit it was measured at

Measured facts go stale at the next landing — that is what a fix is *for*. Two rounds in one
session were briefed against a frontier that had been fixed commits earlier, because the
measurement was carried forward without re-checking. Re-measure the frontier as the **first act**
of briefing, not as something the round confirms in passing.

## Seventh rule: tell the round you are probably wrong

Every brief in the productive stretch ended with a count of the orchestrator's refuted claims and
an instruction to treat the framing as unverified. The rounds took it literally, and it is why
the errors were caught:

> *"Twenty-one of my claims have been factually wrong this session — including, twice, in this
> exact area. Assume the structure above is wrong somewhere and check it. If it does not
> reproduce, that IS the finding and I want it reported, not worked around."*

Rounds then reported corrections as findings rather than working around them silently — including
correcting *each other*, and correcting a previous round's conclusion that had itself been
correct in its measurements and wrong in its scope.

## What to keep out of the brief

- Anything that lets the round satisfy a comparison without modelling the ROM. Say plainly which
  mechanisms are forbidden (trace hydration, the timing port creating work, tolerance changes)
  and *why*, not just that they are.
- Zone/act/route/frame predicates. Say "if zone `$17` needs different treatment it must fall out
  of ROM-derived data or an existing provider" — and point at a precedent. The best fix of one
  session ported the ROM's own literal comparison into a per-game hook that **already carried
  another arm of the same ROM branch**.
- Instructions to make a specific test pass. Name the frontier and the acceptance shape instead;
  several of the most valuable rounds ended with the target class unchanged.

## Eighth rule: a check that is blind on the files at risk is worse than no check

The prescribed merge check used to be "diff the rebased commit against its new base,
restricted to paths the change should not touch, and expect empty". A round reproduced a
known-bad rebase and showed that check **passes on the bad resolution**, because the files
that actually lose content — `README.md` and this document — are paths the change
legitimately *does* touch. The merge policy requires the README entry, so they can never be
on the "should not touch" list.

The real hazard is not a missing conflict marker. Git flags every conflicted file. It is the
**asymmetry of the hunks**: develop's side can be one to two orders of magnitude larger than
the incoming side, and in the worst case the incoming side is **empty**, so a reflexive "take
theirs" deletes content and adds nothing.

So: resolve conflicts **append-both, never take-theirs**, then assert that **no line present
in the base is missing from the result** (multiset difference per file). Treat that check as
a discovery tool, not a verdict — it correctly flags intentional deletions too, and those need
adjudicating rather than suppressing. A merge in which it fires and every hit is a line the
change deliberately replaces is a clean merge; one in which it fires and nobody looked is not.

A check that cannot fail on the case it exists to catch does not produce safety. It produces
confidence, which is worse.

**Amendment: conflict markers are not the trigger — the file being rebased is.** A later round
rebased a branch onto develop and silently lost a 32-line section of the frontier log. Git
raised **no conflict** in that region; the section was simply present in neither develop nor
the rebased result, and it was the round's pre-registered predictions, the thing that made its
whole result legible. It was caught only because the missing-line assertion was run anyway,
after a rebase that reported clean. So run the assertion after **any** rebase or merge that
touches `trace-frontier-log.md`, `briefing-trace-rounds.md`, `README.md` or `CHANGELOG.md` —
not only when markers appear. Silent loss is the failure mode the check exists for, and it
leaves no signal to prompt you.

## Ninth rule: a latent correction can be worth more as evidence than as a fix

The usual value of landing a latent fix is hygiene — the engine was on the wrong branch, so
correct it and note that nothing measured moved. Occasionally the value inverts.

One round modelled a `FixBugs = 0` path and, in doing so, established from the ROM bytes that
a surviving wild read is **inert** rather than merely unlikely: the score cap bounds the
swapped word, odd addresses return before any word access, and every remaining case is
rejected by the following test. The fix itself was latent and could never have moved the
frontier — it only ever *removes* an interaction the engine already had too few of. But
proving inertness **eliminated the site from the candidate list**, which is what the frontier
actually needed.

Ask of a latent correction not only "does this make the engine right" but "does the analysis
that produced it close a candidate". When it does, say so in the report — that is the part
the next round consumes.

## Tenth rule: the symptom's axis is not evidence about the cause's axis

A round spent eleven rounds on a signpost that landed 1,200 frames early. It eliminated six
things about the signpost's vertical behaviour — both timers, the bounce, the cooldown phase,
the bump window, a velocity shift order — and never once diffed the signpost's **horizontal**
position, because the symptom was a landing and landings are about Y. The cause was four
`x_vel` / sub-pixel assignments the ROM does not make, visible as a one-frame divergence on
the axis nobody had looked at.

"Instrument the writer instead of inferring from motion" is the version of this rule that gets
quoted, and it is right, but it is not the whole move. The move that worked was instrumenting
the writer of the variable that was **not** suspected. A symptom tells you where the error
became visible; it is silent about where it was introduced, and an axis is one of the cheapest
things to check and one of the easiest to assume.

When a candidate list has grown long and specific, that is evidence the list is being drawn
from the wrong axis — a correct axis usually yields the cause before six eliminations, not
after. Treat a long ruled-out list as a prompt to widen the *dimension*, not to keep narrowing
within it.

A detector built for the suspected axis inherits the same blind spot. The same round's
kick counter reported fifteen against the ROM's seventeen because it inferred kicks from
direction changes; the honest fix came from the fixture's own structure — `Obj_EndSignFall`
adds `#$C` to `y_vel` every frame, so recorded deltas are monotonic and any upward jump of
two or more is a fresh kick. State a detector's floor when you report its count.

## Eleventh rule: build the disconfirming case by hand before trusting a ratio

A round supported its model with "8 of 8 promotions are visible on their own row". The
detector defined a promotion frame as the frame where the larger `remaining_work` first
appears — so a promotion that was *deferred* past its own row got recounted as a promotion on
the later row, and then tested for a lag one row further on, where there was none. Every
counterexample the hypothesis had was silently reclassified into a confirmation. "8 of 8" was
the definition restated, not evidence, and a change was built on it.

A ratio near unity is the signature of this failure at least as often as it is the signature of
a real effect. Before quoting one, **construct the disconfirming case by hand and check the
detector reports it as disconfirming.** A detector that cannot express the counterexample is
not measuring the hypothesis; it is measuring its own definition.

The same round is also the source of the companion rule: when a fix moved one segment green
and a different one red, leaving the red count unchanged, the round reverted rather than
reporting the count. **A count that holds while its membership moves is not a null result** —
it is two results cancelling, and the one that reads as "no change" is the one hiding a
regression.

## Twelfth rule: some divergences are not frame-derivable, and hunting a predicate is the error

Frame-granularity state cannot always decide a question. One round established, from
`Level_MainLoop` and `VBlank_Lag`, that whether a main-loop tail has run when a lag V-blank
lands depends only on **where in the body the 68000 was interrupted** — and produced two rows
in committed runs with identical frame-visible engine state and opposite outcomes. No
predicate over frame state can separate them, so under hard rule 3 there is nothing legitimate
to write: any discriminator would be fitted by construction.

When a round reaches that point, the finding is the *impossibility*, and it belongs in the log
in those words so the next round does not spend itself re-deriving it. The sanctioned route for
genuine sub-frame hardware timing is the per-movie sidecar of hard rule 4 — not a cleverer
predicate.

## Thirteenth rule: "A did not match B" has a third answer — the index is broken

When a symptom is phrased as "A did not match B", carry three candidates, not two: A is absent,
A is wrong, or **the index they are paired on is not advancing**. The third is invisible from
either side's data, because A and B are each individually correct — nothing about the
submission looks wrong and nothing about the recorded edge looks wrong. It is also cheap to
test directly: read the pairing coordinate and check it moves.

**The round that produced this rule then retracted its own example.** It reported the replay
port's row latch frozen at zero; re-probed, the latch advances 30,667 times, and the reading
had come from the *tail* of a probe log after the port had finished. The real defect was the
first candidate after all — the engine had no matching work pending when the edge arrived.
The frame is kept because it is sound and it is a gap real briefs have; the example is kept
because a rule whose exemplar was withdrawn should say so rather than quietly acquire a new one.

Which is the wider lesson here: **an elegant reframing is not evidence that it describes the
case in front of you.** A frame that would have been right about a different defect is still
wrong about this one.

## Fourteenth rule: closure ownership is the row after, never the row before

Four times in one session a round mis-assigned engine work to the wrong comparison row, and
each time the error survived because the mistaken reading was *coherent*. A closure sits
between two cursor advances and produces the state sampled by the **next** row. So work
observed after the advance for row N belongs to row N+1's sample, not row N's.

The most expensive instance: a round reported a genuine-looking, ROM-citable defect — the
engine servicing PLC patterns on a recorder-lagged row where `VBlank_Lag` services none — and
built a two-compensating-defects model on it, including a retrospective explanation of an
earlier regression. Re-measured, the service belonged to the following row and the engine's
lag handling had been correct all along. The whole model went with it.

Two things make this worth its own rule. First, the retrospective explanation is what bought
the model belief; a wrong model that explains a past surprise is more persuasive than a right
one that does not, so treat "and it explains that regression too" as a reason to re-measure,
not as corroboration. Second, the fourth instance happened *after* the round built the probe
that exists to prevent it — because it read the result back off a quoted excerpt instead of
re-running the probe. An instrument only helps on the runs you actually use it for.

## Fifteenth rule: a profile's includes bound your sweep, not its excludes

`-Ptrace-replay` does not merely exclude a few classes. It carries an `<includes>` block of
`**/tests/trace/**` **and nothing else** (`pom.xml`, the profile block). It runs roughly 792
tests over 156 classes. The default suite runs roughly **15,176 tests over 1,919 classes**.

So a "clean trace sweep" says nothing whatever about the object suite, the unit suite, the
rewind guards, or any test of a shared accessor. For a change confined to trace comparison
logic that is fine. For a change to a shared runtime accessor — a width, a sensor, a lifecycle
predicate — the trace profile is close to no coverage at all, and reporting it as an empty
both-way diff overstates the evidence by two orders of magnitude in class count.

Before treating a sweep as bounding a change's blast radius, **read the profile's `<includes>`
and count what it actually ran**. Ask which suite exercises the thing you edited, and run that
one too. A round that changed an object's on-screen cull width found the entire unit suite
invisible to the profile it had been told to sweep with.

Corollary, learned the same day: **do not re-run a single class inside the worktree whose
surefire XML is your evidence.** A solo `-Dtest=` run regenerates `target/surefire-reports` and
overwrites the suite run's result, and the surviving XML then reports the solo outcome. An
isolated run cannot settle an order-dependent failure anyway, so the re-run destroys the record
without answering the question. Copy the reports out first, or run the check in a separate
worktree.

## Sixteenth rule: pin a control by commit hash — `origin/develop` moves under you

A round created its control worktree with `git worktree add … origin/develop` *after* cutting
its branch, and develop advanced in between. The control then showed a different S1 chain error
count from the branch — which looked exactly like a cross-game regression caused by an
S3K-only object change. It was **reproducible**, and it **survived isolated `-Dtest=` re-runs
in both worktrees**, so every flakiness check passed it through. Re-detaching the control to the
branch's actual base made the discrepancy vanish.

The failure is silent, reproducible, and points at the change under test. On a busy day develop
can move several times an hour, so `origin/develop` is not a base — it is whatever the last
fetch happened to see.

**`git rev-parse` the base once, and create both worktrees from that hash.** Quote the hash in
the report. A control that is not provably at the branch's base measures two changes at once
and attributes both to yours.

## Seventeenth rule: a compensation stack is load-bearing — remove it in one move

When a defect has been absorbed elsewhere, fixing the real cause alone makes things dramatically
worse before better. One round fixed a one-dispatch trigger latency and watched a segment go
from 292 errors to 50,060, because three separate compensations had been tuned around the
original defect and now over-corrected: a one-frame deferral, a `+ 1` on a timer, and a
re-acquisition hatch for a lost state bit. All three had to come out with the fix, in the same
change.

Two things follow. **Expect a real fix to look catastrophic mid-flight**, and do not revert on
the first number — find what was absorbing the defect. The compensations name themselves if you
look: one of them carried a comment describing the very behaviour it was working around.

And **a green test may be green for the wrong reason**. Removing the compensation stack turned
a passing trace red, and the reason was that the compensation had been supplying a status bit
the ROM supplies by a different route. That trace had been passing on a coincidence; the fix
that reddened it is what made it pass for the right reason.

## Eighteenth rule: an identical error fingerprint means the same lever, whatever the site

Two attempts at the S2 title-card seam were implemented at different sites, one deliberately
chosen to differ *in kind* from the other, with a design note arguing exactly that. They
produced **byte-identical** failures: same error count, same frame, same field, same two values,
same three failing axes. The site changed; the lever did not.

An error profile that reproduces to the value is a fingerprint of the *mechanism being moved*,
not of the code that moved it. When a new approach reproduces a rejected approach's numbers
exactly, the new approach is the old one wearing different clothes — stop and find what both
are actually perturbing, rather than looking for a third site.

The same round adds the negative form: a mitigation that changes **nothing** — not the count,
frame, field or values — was not insufficient, it was irrelevant. Its hypothesis was already
refutable from code the round had read, since the locked title-card rows take the same bare
return every frame without harm. Before adding compensating work to a production path on a
hypothesis, check whether an existing path already does the thing you fear.

Corollary for briefs: when a round proposes a new site for a previously rejected change,
require it to predict whether the error profile will change. A prediction of "same numbers,
different site" is a prediction that the site is not the lever — and it is cheap to check
before the code is written.

## Nineteenth rule: port the READ, not the event that last wrote the field

A routine that reads a field at its own entry is not the same as a callback that fires when
something writes that field. They differ exactly when **something else writes in between** — and
that is the case a trace catches and code review does not.

One round chased a missing ride transfer, a missing bit-clear and a pass-ordering defect, and
killed all three with probes. The engine had every piece of state right — the bits, the clears,
the interleaving — and set its trigger from an `onSolidContact` callback where the ROM re-reads
`status(a0) & standing_mask` at every dispatch entry. A latch taken at contact time cannot see a
clear that arrives afterwards; a fresh read at the ROM's moment can.

So when porting, ask *when the ROM looks*, not *when the value last changed*. A sticky flag set
at event time is the natural-feeling translation and it is wrong whenever a second writer exists.
This is invisible to any probe that only checks state **values** — the values were all correct.

## Twentieth rule: an unstable test class is not an attribution instrument

The same round's first default-suite read was control 15,111/56 against fix 15,034/54 — a 77-test
gap with two classes apparently *fixed* by the change. All of it was instability: the entire 77
was one class reporting 83 tests in one run and 6 in another, **the same fix tree run twice** gave
15,034/54 then 15,111/55, and every implicated class passed in isolation in both worktrees.

A class whose *test count* varies between runs cannot support any claim about a change, in either
direction — including a flattering one. Compare runs of equal size, name the unstable classes, and
say plainly that nothing outside the measured area is attributable. Banking two accidental green
classes would have been the easiest thing in the world and would have poisoned the next round's
baseline.

## Twenty-first rule: only a same-tree revert separates the change from the checkout

Every red-set comparison in this project has compared a fix worktree against a control
worktree. That comparison carries a **per-run noise term of one to two classes**, drawn from a
pool of at least four known-flaky classes, emitted independently of the code under test.

A round measured a class red twice on the fix side and zero times on control, which is exactly
the pattern the two-runs-per-tree protocol exists to catch — and held the fix rather than
explain it away. It then ran the decisive control: **the same worktree with the change reverted
in place**. The reverted run produced *two extras of its own*, sharing none with the applied
runs, while the 53-class core stayed identical across all three. So a two-of-two appearance on
one side is a draw from the pool, not evidence.

Two-runs-per-tree catches a *single* flake. It does **not** catch this, because the same flake
can land on the same side twice by chance. Only a same-tree revert holds the checkout, the
symlinked resources and the machine's neighbours constant while varying the code — and it costs
one suite run.

So: for any single-class difference that survives the two-runs protocol, **revert in place in
the fix tree and re-run there** before believing it. Copy the file aside; never `git stash` in
this repo. And note what this implies retroactively — a "both directions empty" cross-worktree
diff is evidence about the core, not about the one or two classes drifting around it.

## Twenty-second rule: rebasing a stacked branch duplicates append-only files

When a branch is stacked on another that has since been merged **under a rebased hash**,
rebasing the child replays the parent's commits — because the hashes differ, git cannot tell
they are already applied. Code hunks resolve cleanly as already-applied. **Append-only files do
not**: a CHANGELOG entry or a frontier-log append is a pure insertion at a moving offset, so it
is reapplied and the file ends up with the same block twice, a few lines below the original.

A round caught this in its own rebase — a duplicated "### Fixed" block in `CHANGELOG.md` — and
fixed it by resetting and cherry-picking only its own commit rather than rebasing the stack.

So: after rebasing a stacked branch onto a develop that already contains its parent's content,
**diff the result against develop and check the file list before pushing**. A clean rebase is
not evidence; the duplication produces no conflict. The tell is a file appearing in the diff
that your change never touched.

This applies to whoever merges as much as to whoever rebases — when merges rewrite hashes,
every stacked child inherits this hazard.

## Twenty-third rule: the headline field is not the earliest divergence — read the histogram

A chain segment's report gives `errorCount` and a **five-entry ring buffer of recent
mismatches**. There is no full `errors` list; parsing it as one reports zero. So the field named
in "first non-camera mismatch at frame N" is whatever the comparator happened to hit first on
the *physics* axis, and it is routinely the least informative field in the set.

At one frontier the headline was a one-pixel `sidekick_y`. Instrumenting
`LiveTraceComparator.absorbDivergentFields` to build a real per-field histogram showed the same
frame also carried `g_speed` `0x24` against `0x800`, `rolling` 0 against 1, `Status_Roll` set,
and a different animation id — the engine was performing a **whole spindash the ROM never
performs**, and the pixel was just the roll-height adjustment on the way out.

The same histogram showed two animation axes first diverging **thousands of frames earlier**
than the physics frontier everyone was watching.

**Split the histogram by frame range before reading it.** A round applied this rule, aggregated
the value pairs across a whole segment, saw a two-way mixture and called it a cascade — then
found that rows 800-900 carry exactly one pair in one direction, and the mixture belonged
entirely to rows after a *later* frame where position genuinely diverges. That is the same
error as trusting a headline field, one level up: an aggregate over the wrong window answers a
question you did not ask. A distribution is only evidence about the window it was taken over.

So: before briefing or chasing a frontier, build the per-field histogram and read the *earliest*
divergence per field, not the reported one. The headline tells you where the comparator stopped,
not where the engine went wrong. And a report format that looks like a full error list but is a
ring buffer will quietly answer "no errors" — check what the file actually contains before
trusting a parse of it.

## Twenty-fourth rule: diff failure messages, not class names

A red-set diff by class name **cannot detect a change that alters why an already-red class
fails**. A lane's own clean sweep contained the evidence that its assertion broke another game's
chain — that chain class was already red in both arms, so the diff reported "identical red sets"
while the change had silently replaced the failure underneath it. The regression reached develop
and blocked an entire game's measurement for an hour.

This is strictly worse than the count-holds-while-membership-moves trap, because here the
membership did not change either. Nothing about the shape of the result was wrong; the shape was
simply not a fine enough instrument.

**For any change to shared harness or comparator code, diff the first failure *message* per
class, not the class list.** Two arms can agree on every class and disagree on every reason.

Companion trap from the same session: `grep "Tests run:.*(Fail|Err)"` matches every line, because
`Failures: 0` contains `Fail`. It reported 156 red classes out of 162. The correct filter is
`Failures: [1-9]|Errors: [1-9]`, which gives 4. A filter that matches everything looks like a
catastrophic regression and is indistinguishable, at a glance, from one.

## Twenty-fifth rule: `-Dmse=off` or your `-D` properties never arrive

Maven Silent Extension is enabled by default in this repo (`-Dmse=relaxed` via
`.mvn/maven.config`), and it **silently swallows CLI `-D` properties**. A round found
`-Dtest=X` running the entire 15,000-test suite because the filter never reached surefire, and
the ROM-path properties never arriving either — producing 3,861 errors and 663 apparently-red
classes that were pure artefact.

This is the truncated-run trap in reverse: instead of measuring less than you think and reading
it as a fix, you measure *everything* and read it as a catastrophe. Both mislead, and this one
also silently ignores the class filter you believed you were running.

**Always pass `-Dmse=off` when measuring.** Any round that quotes a red count from a run without
it — with `-Dtest=` or with ROM paths — is quoting a number about a different command than the
one it thinks it ran. The tell is a red count in the hundreds, or a `-Dtest=` run whose duration
matches a full suite.

## Twenty-sixth rule: never accept a self-report about process state

Machine state is directly observable. `pgrep -af` plus `readlink /proc/<pid>/cwd` answers "who is
running what, in which worktree" in one line, and it is never in doubt.

Two coordination failures in one session came from a lane's self-report standing in for that
check. A lane reported no suite running while a full suite was forking in its worktree; the
report was relayed as fact, two suites contended through the symlinked shared resources, and the
contended run died at 1,657 of ~1,919 classes. Its terminal line read **12,971 tests / 46
failures** against a complete run's **15,185 / 55**, and a grep for the one class under
investigation returned zero — which at face value reads as *"nine fewer red classes and the
regression is gone"*. A truncated run masquerading as an improvement, for the second time that
night.

Two consequences:
- **Before assigning or standing down a lane, check the machine yourself.** Never run two suites
  concurrently, and confirm that before starting one, not after.
- **A timer left armed after its owner stands down is the same hazard as a stale branch left on
  the remote**: it fires into whatever is running next. Kill watchers explicitly and verify the
  script is gone, rather than assuming the process exited with its round.

Companion trap, from the same lane checking its own disarm: a `pgrep` for its watcher matched
**its own shell command containing the grep string**, reporting STILL ARMED when nothing was.
The same shape as `Failures: 0` matching a grep for `Fail` — a filter that matches the searcher.
Exclude your own command line, or match on the parent process rather than the pattern.

## Twenty-seventh rule: `git checkout -- <path>` restores from the INDEX, not from HEAD

A round mid-sweep restored its experiment tree with `git checkout -- src/`. The index still held
the reverted control files, so the "experiment" arm started against **control code**. It caught
this within seconds because `git status` showed staged `M` entries — but the failure is silent by
construction, and it would have produced a perfectly plausible, entirely worthless result: two
arms agreeing on everything, because they were the same code.

That is the most dangerous shape a measurement can take. An empty both-way diff is exactly what a
clean change looks like, so nothing about the output would have prompted a second look.

When switching a worktree between control and experiment arms, use `git reset --hard <hash>` or
`git restore --source=<hash>`, and check `git status` is clean **before** launching each arm. If
you have staged anything during a revert, `git checkout --` will hand it straight back to you.

The general form: a restore command that reads from a mutable intermediate (the index) rather
than a named commit will silently return whatever you last put there. Name the source.

## Twenty-eighth rule: check whether the absence is in the data or only in your view

Four times in one session a round nearly concluded something from a *missing* entry, and each
time the entry was missing from the **view** rather than from the data: an aggregated histogram
that hid a single-direction origin, a class-name diff that could not see a changed failure
reason, a grep of one skill mirror that concluded a rule was unrecorded when it lived in another
file, and an axis list truncated by the round's own `head -20` — where the item was *also* never
in that list by design.

The last one is the sharpest. A round read a cold-start axis list, saw no segment 0, and drafted
"segment 22 does not reproduce from a cold start, therefore it is carry-in" — **the exact
opposite of the truth**. Reading the report file settled it. A false attribution presented as a
measured fact is worse than an open question, because it looks settled and nobody re-checks it.

So before concluding anything from an absence, ask: could this be absent because of how I
looked? Check the pipeline (`head`, `grep`, a filter), check whether the thing is excluded by
design, and check a second source that would show it if it were there. **A negative result is
scoped to where you looked**, and the cost of confirming the scope is one command.

## Twenty-ninth rule: check a suspicious delta against both endpoints

A round measured that a cursor entered a segment correct and arrived short at its exit, and read
that as "the segment consumed fewer frames than its rows". It hadn't: the segment consumed every
row. The shortfall was the **untraversed gap between that segment's last row and the next
segment's offset** — the movie frames a level load occupies between them.

One subtraction would have caught it: the segment's `offset + rowCount` against the reported
cursor. They matched exactly. The delta was real and the interpretation was wrong, in the
direction that sends the next reader hunting inside the segment for a leak that does not exist.

So when a delta appears between two points, compute **both** endpoints from first principles
before deciding which end moved. "Correct here, wrong there" has at least three readings — this
end is right, that end is wrong, or the span between them is not what you think it is — and the
third is the one that gets skipped.

The tell that was available and missed: the round's own story left a loose thread unexplained
(why a cold start behaved differently), and it did not pull it. **An explanation that leaves one
of your own observations unaccounted for is not finished**, however well it fits the rest.

## Thirtieth rule: a mass-error run is an environment artefact until proven otherwise

A default-suite run reported 13,834 tests and 3,745 errors. The cause was
`UnsatisfiedLinkError: Failed to locate library: libglfw.so` — a race on the shared
`/tmp/lwjgl_farrell` native-extraction directory against a concurrent run in another worktree.
The clean re-run gave 15,193. The round discarded the first rather than reporting it.

Two tells, and they are cheap: the failures are `NoClassDefFoundError` / `UnsatisfiedLinkError`
on natives rather than assertions, and the test *count* is far below the known total. Grep the
log for `libglfw` before reading anything into the numbers.

Note this is a **different** artefact from a self-collision between two Maven runs in one
worktree, and it does not require that: two runs in *different* worktrees are enough, because the
native extraction directory is shared across the machine. So "I only have one run in my tree" is
not protection.

The general form, which now has three instances this session: **before believing a number that
would be a big result, check whether the run that produced it was healthy.** A truncated run
reports fewer red and reads as an improvement; a native-extraction race reports thousands of
errors and reads as a catastrophe; both are the environment, not the change.

## Thirty-first rule: "no field reported" is a statement about which check fired

A segment was described as **camera-only** in every brief for a full day. It wasn't: 205 of its
250 errors were animation fields on both characters from row 0, with camera a separate cluster
7,700 rows later. The chain prints the first non-camera **PHYSICS** mismatch, and animation
fields belong to the ANIMATION group — so "no field printed" meant "no *physics* error", and was
read as "only camera errors".

Nothing was wrong with the report. The phrasing described *which check fired*, and it was
inherited as a description of *what is wrong with the segment* — through several rounds and
several briefs, mine included.

This is the same family as diffing class names instead of failure messages, and as reading an
absence off a truncated view: **an instrument's silence is scoped to what that instrument
examines.** Before accepting a characterisation like "camera-only", "queue-only" or "no physics
divergence", build the per-field histogram and confirm the claim covers every verification group,
not just the one the headline is drawn from.

The tell is a phrase that has been repeated across rounds without anyone re-measuring it. Those
are exactly the claims that stop being checked.

## Thirty-second rule: a row's emulator frame is `bk2_frame_offset + row + 1`

Six rounds on one S3K segment ran aground on an apparent impossibility: the trace's row 0
carried an animation value and a frame counter that, according to every probe, never held those
values at the same time. Rounds proposed a mislabelled fixture, a composite row sampled at
different instants, and a recorder that read some other byte. All three were wrong.

The row was simply one frame later than everyone was looking. The recorder's arm gate calls
`start_new_segment` — which stamps `bk2_frame_offset = emu.framecount()` — and then **returns
without recording that frame**. So the arm frame carries no row, and a row's emulator frame is
`offset + row + 1`. Every contiguous segment succession in the run satisfies it.

The trap is that `offset + row` is also correct — for a *different quantity*. It is the 0-based
**BK2 input index**, which is what the input-mask helper computes, and its own source comment
warns that `emu.framecount()` runs one ahead of it in the recorder loop. One publication
sentence states both relations, and both are true. Conflating them is what manufactured the
contradiction.

**When you write a probe, compare `emu.framecount()` to `bk2_frame_offset + row + 1`.** Comparing
to `offset + row` silently lands on the arm frame — which is the *pre-settle* frame, still
carrying the load's lag count with status and animation not yet written — and that frame looks
exactly like a genuine engine divergence at row 0. It cost this segment several rounds, and it
retracted a writer list that, re-indexed, turned out to describe events fifty rows downstream.

The general form: when a measurement and a fixture disagree about *when*, suspect the index
convention before suspecting either instrument. Two off-by-one-related quantities with similar
names, both documented, is the setup.

## Thirty-third rule: BizHawk reports the PC *after* the storing instruction

A probe hooking a memory write logs `M68K PC`, and that value is the address of the
instruction **following** the one that performed the store. Read literally, every write-site
result in a chain of rounds points one instruction downstream of the code that actually wrote
the field.

This was found only because a lane built an exact address-to-label mapping and noticed the
field BizHawk named did not belong to the instruction at the reported PC — twice, independently,
in the same capture. Without a real mapping the discrepancy is invisible: a PC a few bytes off
still lands inside the right routine most of the time, so the label looks plausible and the
error survives. It is the small, consistent kind of wrongness that a plausibility check cannot
catch.

**Build the mapping, don't bracket it.** For S3K, `docs/skdisasm/skbuilt.bin` is byte-identical
to the locked-on ROM's entire S&K half, so `docs/skdisasm/sonic3k.lst` — the AS listing with an
address column — gives an exact address→label lookup rather than a guess between two known
labels. Verify the byte-identity yourself; it is what licenses the lookup. (This is label and
offset discovery, which the disassembly tree is *for*; it is not a hard-rule-1 asset read.)

Corollary, and the reason this rule is worth its length: a writer list derived from unadjusted
PCs was published, briefed, and built on for two rounds before being retracted wholesale. State
your PC→label method and your confidence whenever you report writers, so the next round can
check the derivation instead of inheriting the conclusion.

## Thirty-fourth rule: two Maven invocations in one worktree corrupt each other

A lane ran a second Maven invocation in a worktree that already had one running. The second
clobbered `target/test-classes` while the first was reading it, and the first reported **"No
tests matching pattern"** — a message that reads as "your `-Dtest` filter is wrong" or "that
class does not exist", not as "another process deleted the class files underneath me".

It joins the family this document keeps returning to: `liblwjgl` extraction failures that
surface as ~100 errors at 0.002s, contended suites that die partway and report *fewer* red than
the baseline, and truncated runs that look like progress. Every one of them produces output
that is structurally indistinguishable from a real result unless you know the signature.

**One Maven invocation per worktree, and run arms serially.** If you need parallelism, use
separate worktrees — they are cheap, and the control-arm discipline wants a separate tree
anyway. When a run reports something about *tests not existing* rather than tests failing,
check for a concurrent build before believing it.

The general rule, which now has four instances behind it: **before reporting a number, ask what
else could have produced this output.** A measurement you cannot distinguish from an
environment artefact is not evidence, and the artefacts here are not rare — four separate ones
turned up in a single day.

## Thirty-fifth rule: a failed compile looks like a short test run

A lane lost four probe iterations to an uncaught `cannot find symbol`. A Maven run whose
compilation fails produces a log of a few kilobytes that, skimmed, looks like a test run that
simply finished quickly — and if you are grepping for `Tests run:` or for your probe's output,
the *absence* of both reads as "the probe did not fire", which is a plausible experimental
result rather than a build failure.

**Grep every run for `COMPILATION ERROR` and `BUILD FAILURE` before interpreting anything
else.** A probe that produced no output because it never compiled is not evidence about the
engine.

**The nastier variant: a failed compile leaves the *previous* run's reports in place.** A round
today hit a main-source compile failure and then grepped `target/surefire-reports/TEST-*.xml`,
which still held the prior run's results — a complete, well-formed, entirely stale answer to a
question the build never asked. It caught the problem only because its new probe strings were
absent from the output. So the check is not merely "did this run fail"; it is **"is this report
from this run"**. Confirm the build succeeded before reading any report file, and if you are
probing, confirm your own probe's output is present rather than inferring from what is missing.

Same family: `-Ptrace-replay` sets its own `<surefire.argLine>` in the profile's
`<properties>`, so a CLI `-Dsurefire.argLine=...` is **silently ignored** — no warning, and the
run proceeds looking exactly like one that honoured the flag. Pass probe switches by environment
variable instead. (This is a second instance of the `-Dmse=off` lesson: a property that never
reaches the fork produces a confident, wrong measurement rather than an error.)

Both belong to the rule this document keeps re-deriving: **an experiment that could not have
worked returns the same shape of nothing as an experiment that worked and found nothing.**
Before believing a negative result, prove the instrument ran.

## Thirty-sixth rule: the branch is the artifact, the worktree is scratch

Rounds accumulate worktrees. In one day a session left 47GB across 60 of them, most belonging
to rounds that had finished hours earlier — including several whose code was deliberately *not*
merged, which is the case that makes naive cleanup feel unsafe.

It isn't unsafe, because of where the work actually lives. **Once a round commits to a branch,
its worktree holds nothing the branch does not.** Removing it is lossless and reversible: the
branch keeps every commit, including work that was correctly refused a merge. So the disposal
decision belongs to the lead, and it is cheap — no judgement about whether the work was any good
is required, only whether it is committed.

**The worker's obligation is therefore to persist, never to discard.** A round must not be asked
to delete its own work as a condition of finishing — whether unmerged work is worth keeping is
not the worker's call, and a round that reverted a candidate or had its premise retracted has
often produced the most valuable output of the day. What a round owes at stand-down is:

- every change committed to its branch, including candidates that were built and rejected —
  commit them with the measurement that rejected them, so the next round does not rebuild them;
- the branch name and commit hash in its report;
- probes and temporary instrumentation reverted, so the branch shows the round's actual position;
- nothing of value living only in the working tree.

**The lead's obligation is to sweep after standing a round down**: confirm the branch carries
the commits, then remove the worktree. Skip any tree with uncommitted changes — that is the one
state where the worktree holds something unique, and it means the round did not finish cleanly.
Ask it to commit; do not resolve it by deleting.

Two adjacent habits worth keeping: stop a round when you stand it down rather than letting
finished rounds pool idle, and send the closing message *before* stopping it — messaging a
stopped round resumes it.

## Thirty-seventh rule: equalise run order before comparing failure sets across worktrees

Two arms of a comparison were each run in their own worktree and both reported **5 failures**.
The sets were not the same: the control failed one S1 trace class, the candidate failed a
different one. Read as a count, nothing moved; read as a set, it looked exactly like "my change
broke a test and fixed another". Both classes passed in isolation.

The cause is already recorded elsewhere in this project: Surefire's default filesystem run order
varies per worktree and survives `mvn clean`, so **which** order-dependent test fails is a
property of the checkout, not of the change. `-Dsurefire.runOrder=alphabetical` on *both* arms
produced identical sets.

**The signature is a swap** — equal totals, different members, all of them passing alone. Note
that isolated runs cannot settle it: an order-dependent failure passes in isolation by
definition, so "it passes alone" is evidence of nothing. Equalise the run order and re-measure.

Corollary for any cross-worktree comparison: the two trees differ in more than your diff.

## Thirty-eighth rule: a backgrounded Maven that dies with its shell reports success

A round backgrounded a `mvn` invocation whose launching shell then exited. The build died with
it, leaving a 33-line log that ends mid-`testCompile` — no `Tests run:` line, no failure, no
stack — while the harness reported the *shell's* exit status of 0. Skimmed, that is a pass.

This is the same family as the failed-compile hazard (thirty-fifth rule) and it fails the same
way: **the absence of test output reads as "nothing went wrong" rather than "nothing ran".**

Detach properly (`setsid ... </dev/null`) and poll for an explicit `BUILD SUCCESS` or
`BUILD FAILURE` line before reading any total. An exit code from a wrapper is not the build's
verdict, and a log that simply stops is not a result.

## Thirty-ninth rule: create round worktrees copy-on-write

Rounds want their own worktree, and often a second one for a same-tree control arm. A full
checkout of this repository is roughly 800MB, so a day of parallel rounds accumulates tens of
gigabytes of near-identical trees.

On a filesystem with native reflink support (btrfs, XFS with `reflink=1`, APFS), that cost is
almost entirely avoidable: the **`cow-git-worktree` skill** creates a worktree by reflinking
unchanged tracked files from an existing clean worktree instead of checking them out afresh.
Measured here: a new worktree reported ~810MB apparent size and **zero exclusive bytes** — every
block shared with its source until something writes to it. Creation took about 20 seconds and
the resulting tree's `git status --short` was empty.

Reflinks are copy-on-write, not hard links: writing to a file in either tree diverges it
normally, so the two worktrees stay fully independent. **Never** substitute hard links here —
they would make an edit in one round silently corrupt another.

Use the skill for creating a round's worktree and its control arm. Use ordinary git for
`list`, `move`, `remove` and everything else. The helper falls back to a normal checkout when
the source and target are on different filesystems or the platform lacks reflinks, and reports
which path it took — check that report rather than assuming the optimisation applied.

**Pass `--from` an explicitly clean worktree.** Only clean, committed content is eligible to be
shared, so a source with local modifications degrades toward a full checkout. A round reported
`fallback=yes reason=copy-on-write-unavailable` on the same filesystem where an explicit clean
source gave `reflinked=7770 backend=ficlone fallback=no` — same machine, same btrfs volume,
minutes apart. If you see `fallback=yes` on a filesystem you expect to support reflinks, suspect
the source before the platform.

This pairs with the thirty-sixth rule: worktrees become cheap to create *and* the branch remains
the artifact, so there is no reason either to hoard finished trees or to skimp on a proper
control arm.

## Fortieth rule: a comment narrating engine bookkeeping is the tell for a fitted constant

Three compensations in one S3K camera controller — a pre-charged accumulator, a bare `delta += 2`,
and an airborne first-dispatch early return — were all fitted, all wrong, and all had **passed
review**. Together they were the entire remaining error count on a chain segment; removing them
took it to zero.

They survived because each carried a comment. The comments explained the number by narrating the
*engine's* bookkeeping — a phrase of the shape "has already crossed the native creation-pass
carry" — rather than citing a ROM routine. That reads as diligence. It is the opposite: a value
justified by how the engine happens to arrive at a moment is a value fitted to the engine's
current behaviour, and it will desync the first recording that arrives at that moment differently.

**A constant is justified by a ROM citation or it is not justified.** When reviewing or writing
one, ask what the comment cites. "Because `CreateChild1_Normal` allocates via
`AllocateObjectAfterCurrent`, which by construction returns a slot after the creating object, so
`Process_Sprites` always reaches the worker in the creating pass" is an argument. "Because the
engine has already crossed the carry by this point" is a description of a symptom.

Two corollaries earned alongside it:

- **Compensations come in families.** Removing two of the three took the target segment to zero
  and put a previously-green fixture at 2 errors, because a second route enters the same handoff
  airborne and the third compensation then fired. Stopping at the first green would have shipped a
  45→0 / 0→2 trade as a win. When you remove a fitted constant, look for its siblings before
  measuring.
- **The pattern propagates.** The same accumulator is ported at six other sites in this codebase,
  at least one carrying a comment with the same premise. A single ROM argument usually settles
  every instance in one direction — so when you kill one, audit the family.

**Widened after the audit: the tell is directional, and the mirror image is just as common.**
Enumerating all thirteen sites turned up pre-charging once more, and its opposite — a *skipped*
creation dispatch — twice. One site ran its gradual update before the switch that arms it, so the
creation-frame dispatch never happened and every later one ran a frame late. Another held a flag
whose comment explained why the creation dispatch should be skipped, and which was **never set to
true**: dead code that changed nothing and would have re-taught the defect to the next reader.

So the review tell is not "a comment justifying a seeded constant". It is **any comment reasoning
about which engine hook runs first**, in either direction. If a comment's argument is about
engine ordering rather than about a ROM routine, the code under it is guessing at a question the
ROM answers by construction.

A corollary from the same audit, worth copying: where a reserved slot genuinely *is* behind the
live cursor, the honest fix is to expose an explicit method that runs the allocation-frame
dispatch — not to pre-charge an accumulator to fake having run it.

## Forty-first rule: prove an arm was not truncated, don't infer it from its total

Two arms reporting the same total is weak evidence that both ran completely. A contended or
memory-starved arm dies partway, and if the classes it lost happened to be green, the total is
unchanged. Every hazard in rules 30-38 shares this shape: the output of a run that did less looks
like the output of a run that found less.

**Know your suite's full denominator and refuse any total below it.** A round today saw a
candidate default run report `13986 / 41F / 3139E`; the same tree re-run gave `15196 / 53F /
71E`. The complete default suite is ~15,196 tests — a run reporting materially fewer did not
finish, and its failure counts mean nothing. The same discipline applies per profile: know the
number, and treat anything short of it as a dead run rather than a result.

Three checks that actually establish completeness, in increasing order of strength:

1. **Compare the set of test classes run, by name.** A truncated arm shows a *short class list*,
   not merely a lower failure count. `diff` the `-- in <class>` lines between arms; an empty diff
   is worth more than matching totals.
2. **Compare the `Tests run: 0,` lines themselves, not their count.** Equal counts can hide a
   silent victim that happened to land symmetrically with a pre-existing empty class. Identical
   *classes* reporting zero means those are genuinely empty or skipped, not casualties.
3. **Grep for `forked VM terminated` and `Corrupted STDOUT`.** These name a fork death directly
   and appear in neither a healthy run nor, importantly, in the totals.

A round today ran its two default-suite arms concurrently — the most contended thing it did — and
established soundness this way rather than by hoping: identical class sets, identical zero-run
classes, no fork deaths. That is a defensible measurement taken under bad conditions. Matching
totals alone would not have been.

## Forty-second rule: diff the whole failure message, not a truncated prefix

A round diffing its two arms truncated each failure message to 200 characters to keep the output
readable. Two genuinely different S3K failures compared **equal** under that truncation — they
shared a prefix and differed only in the cursor value near the end.

This is the same family as diffing class names instead of messages (the twenty-fourth rule), and
it fails the same way: the comparison silently loses the distinguishing part and reports
"identical", which is exactly the answer a round wants to hear about its control arm.

Truncate for *display* if you must. Never truncate the thing you compare.

The same round caught two contaminated arms in the same session by comparing the **set of class
names** — a forked-VM crash that cost one arm a whole chain class, and a default-suite run that
lost three forks. Both had plausible-looking totals. That is the forty-first rule paying for
itself twice in one round.

## Forty-third rule: concurrent rounds are a resource budget, not free parallelism

Five rounds ran Maven suites concurrently on a 32-core machine with 30GB of RAM. Load average
reached 34 and free memory reached 4GB, and the consequences were not slowness — they were
measurement failures of exactly the kind this document keeps cataloguing:

- the default profile OOMs on some chain classes even with memory to spare; under pressure it
  can hit anything, and an OOM produces **"Java heap space", 0 tests run, no report**, which
  reads as a test that silently did nothing rather than a build that died;
- GLFW headless-init failures (`Failed to initialize`) appear under contention and land
  unevenly across arms — one round had to reclassify a three-error delta that made its own change
  look better, because the *control* arm was the contaminated one;
- a contended suite that dies partway reports **fewer** failures than the baseline, which reads
  as improvement.

**Two to three concurrent suite-running rounds is the honest ceiling on a machine like this.**
Beyond that you are not parallelising, you are queueing with extra steps, and degrading the one
thing every round depends on.

Practical scheduling, learned the same day:

- Much of a round's work is **static** — reading the disassembly, enumerating sites, tracing an
  engine path. That part never contends. Sequence a round so the static work happens first and
  the sweep happens once, at the end, for whatever actually changed.
- A round with an empty runtime diff **owes no sweep at all**: both arms are the same tree, so
  the measurement can only re-measure the baseline. Say so rather than spending an hour on it.
- Prefer the cheapest harness that reproduces the defect. A single chain class runs in 11-50
  seconds where a full profile run costs minutes, and per-class runs are also the only ones safe
  to quote report files from.
- When the box is loaded, do not kill a round mid-measurement to reclaim it. That wastes the work
  *and* produces the truncated logs you then have to tell apart from real results.

## Forty-fourth rule: an axis count is only comparable when both arms reach the same depth

Two chain arms produced 22 axis lines and 9. The 9 was not better: the candidate's walk **aborted
at segment 27** where the control reached segment 33, so four segments and seven gap axes were
never asserted at all. Fewer reported failures, strictly less evidence.

The same day produced the exact inverse. A fix took a chain from 2 axes to 3 — and that *was*
progress: one axis was removed, and two appeared, one of which only became **evaluable** because
the walk now completed far enough to evaluate it.

So the count moves for three unrelated reasons — defects fixed, defects introduced, and depth
changed — and a bare number cannot distinguish them. **Report reach and the axis list, not the
count.** Name the deepest segment each arm asserts, and diff the lists by message. If the arms
stop at different depths, say so in the same breath as any count you quote, because an
unqualified number reads as progress to whoever finds it later.

The same trap applies one level down: two arms that both assert a segment can still differ inside
it. A round compared reach and axis lists carefully, and separately found the candidate made an
already-red segment **worse by 901 errors** — a widening invisible to both the count and the list,
caught only by diffing per-segment error counts across the segments both arms actually assert.

## Forty-fifth rule: before believing a null result, verify the instrument is installed

A round needed a held branch's fixture applied to make a defect observable. `git apply --3way`
reported a conflict on one already-modified file and, in the same invocation, **silently created
none of the 28 new files**. Two full arms were then measured with no fixture present. They came
out byte-identical to base — which reads exactly like *"the defect does not bite"*, and the round
nearly reported that as overturning the premise it had been sent to test.

It was caught by counting the fixture files on disk and noticing the defect's signature string
appeared nowhere in either log.

This is the sharpest form of a pattern this document keeps returning to: **an experiment that
could not have worked produces the same output as one that worked and found nothing.** The
earlier instances were about builds that never ran; this one is about a build that ran perfectly
on the wrong tree.

**A null result is a claim about the instrument as much as about the subject.** Before reporting
one, prove the instrument was live: count the files, grep for the signature the defect would
produce, or run the arm you expect to *fail* and confirm it does. A partial `git apply` is
especially dangerous because it does part of its job and reports a conflict you may reasonably
attribute to a file you did not care about.

## Forty-sixth rule: a silent probe may be a build that never compiled

A probe inserted between an `@Override` annotation and its method made the build fail, and
`mvn -q` printed **nothing at all**. The empty output read exactly like "this code path never
runs", and briefly retired a hypothesis that was correct.

This is the failed-compile hazard again with the loudest signal removed: `-q` suppresses the very
line that would have told you. **Re-run without `-q` before believing a silent probe**, and treat
"my probe produced no output" as a claim about the build until proven otherwise — the same way a
null result is a claim about the instrument.

A companion from the same round: **`DynamicArtLifecycleService.movieLogicalFrame` is not a live
movie-row clock across a special stage.** It sat at the destination's `bk2_frame_offset` for all
4673 special-stage iterations and every gap iteration. It cannot be used to time a gap, and a
quantity that looks like a frame counter is worth checking against a second source before you
build on it.

## Forty-seventh rule: measure the quantity, do not reconstruct it

One round produced both halves of this lesson within an hour.

**Reconstructing when you could measure.** Investigating a one-pixel touch-box miss, the round
derived the player's box edge as `centreX − 8` instead of reading the value its own probe had
already captured. The derived number produced an overlap where there was none, and the correct
explanation was written off as non-causal. The probe's actual box restored it.

**Trusting a derived number that nearly fits.** The round before, a rider placement looked like a
character-radius mixup: the two radii differ by 4 and the observed step was 5. Taking the 4 as
confirmation would have produced a plausible fix changing the wrong constant. The 5 turned out to
be the ROM's own roll adjustment, so the step was evidence a code path *ran*, not evidence of a
wrong value — and the real fix changed no constant at all.

The two look opposite and are the same rule. In both, a reconstructed quantity was allowed to
compete with a measured one, and in both the reconstruction was wrong in a way that fitted the
story. **If the instrument already carries the number, read it. If it does not, get it before
building on a derivation** — especially when your derived value is close to the observation
without matching it, which is the case rule 3 already warns absorbs an error somewhere else.

## Forty-eighth rule: object execution order is observable behaviour

Three separate rounds in one day, across two games and three subsystems, traced a divergence to
**which slot an object occupies relative to another**:

- An S1 capsule burst: the ROM's first animal landed in a slot *below* its spawner, so it executed
  the following frame and took the last RNG draw instead of the first. The species assignment
  rotated by one, an animal already among the last released became the slowest in the game, and
  twelve frames later an art arm was late — ultimately ~74,000 comparator errors.
- An S3K battleship: the ROM spawner uses plain `AllocateObject` rather than the after-current
  form, so the new slot need not be reached by the current pass. The engine executed it one frame
  earlier, leading a fractional accumulator by one update.
- An S1 monitor and fan: the fan writes position **directly** rather than through velocity, so
  whichever runs first decides whether the monitor ever sees a one-pixel penetration. The engine
  runs them in the opposite order to the ROM.

The general statement: **an object's slot determines whether it runs before or after its
neighbours in the same frame, and that ordering is part of observable behaviour** — it decides RNG
draw order among siblings, whether one object sees another's position write, and whether a
spawned object gets an update on its creation frame. It is not an implementation detail, and a
slot permutation is not cosmetic even when the same objects are present.

Practical consequences:

- When a divergence involves two objects that interact, **check execution order before checking
  either object's logic**. In all three cases the individual routines were faithful.
- `FindFreeObj`-style allocation makes slot assignment depend on the whole run's spawn and despawn
  history, so an ordering defect can originate thousands of frames earlier than it surfaces.
- **Do not reorder a pair by anything measured off a fixture.** The ordering must follow from the
  ROM's own allocation, or it is a fitted model that will desync the first different recording.

**Sharpened by two later rounds.** The allocator names the guarantee, and the init's shape names
how much happens on the creation frame:

- `AllocateObjectAfterCurrent` returns a slot *ahead* of the current pass, so the object runs on
  its creation frame — and if its init falls through into its first routine rather than returning,
  that frame includes a routine step as well.
- plain `AllocateObject` scans from the bottom and **may or may not** return a slot ahead of the
  pass. This is the caveat that matters: the allocator tells you the placement is not guaranteed,
  and only the recording tells you which way it went in a given run. One round settled it by
  finding the object's code pointer still on its init routine at the end of its creation frame.

Two engine defects at one seam cancelled because of this pair — an object started a frame early
because its slot was assumed ahead of the pass, and a second started a frame late because its
init's fall-through was not modelled. Fixing either alone moved every downstream event by a row.

**The fixtures can answer these directly.** `aux_state.jsonl` carries `slot_dump`,
`object_appeared` and `object_removed` events — the ROM's own slot table and allocation history.
Slot-ordering questions are measurable against the recording rather than inferable, and a
frame-by-frame diff of the engine's dynamic slot table against that stream is the instrument these
questions want.

## Forty-ninth rule: prove your edit is on the path before believing a null

A round retracted a hypothesis on two null results — the change made no difference, twice, on two
different fixtures. Both nulls were real measurements of **dead code**. The expression under test
existed in two places: a private helper in one class, and the same arithmetic written out at seven
call sites in the harness that actually runs. The edits went to the helper. The harness never
called it.

The retraction was accepted, briefed onward, and cost two further rounds before someone
instrumented the live path and found the original hypothesis correct.

This is the instrument-not-installed rule (forty-fifth) in its most deceptive form: the build
succeeds, the tests run, the numbers are real, and the code you changed was never executed. A null
from an edit is a claim about **reachability** first and behaviour second.

**Before reporting that a change made no difference, prove the change ran.** Add a throwaway
assertion, a log line, or a deliberate breakage to the edited path and confirm the run notices. If
the same expression appears in more than one place — a helper and its inlined copies, a base class
and an override, a production path and a test harness's own arithmetic — establish which one the
failing scenario uses before touching either.

Corollary: duplicated logic makes every null result ambiguous. Where a round finds two copies of
the same expression, that is worth reporting even when it is not the defect.

## Fiftieth rule: preserve nested class names when diffing failure sets

A round reported four newly-red classes. There were five. The fifth was a nested test class, and
the round's class-name extraction mangled the `$` separator so the row arrived as a **bare package
name** — which it read as parsing noise and dismissed. It had the entry in its own output.

The same round chased an unexplained two-test shortfall between arms and found it was Surefire
rerun accounting: one test attempted three times, reported as `Run 1:`/`Run 2:`/`Run 3:`, with
per-class counts identical class-for-class and summing to the same total in both arms. Nothing was
skipped — but chasing the number is what surfaced the missing class.

Two habits follow:

- **Extract failing classes in a way that preserves nested `$` names**, and treat a bare-package
  row in a class diff as a **mangled nested class** rather than as noise. A tooling artefact that
  looks like debris is the easiest kind of evidence to discard.
- **Reconcile a failure listing against the summary before trusting either.** Counting the
  listing's lines, subtracting rerun continuations, and confirming the result equals
  failures-plus-errors is a cheap check that catches both over- and under-counting.

The general form, which this document keeps meeting: an unexplained number is worth chasing even
when the likely explanation is benign, because what it sits next to may not be.

## Fifty-first rule: a parked measurement measures the tree, not the diff

A candidate was parked twice on the strength of a wall it hit. A later round re-measured it as an
isolated diff on top of develop and the wall **did not occur** — the branch carrying the candidate
also carried an unrelated engine commit, and the parked runs had measured their tree rather than
their change. The candidate landed with no reach cost at all.

This is easy to do and expensive to undo. A round accumulates commits — probes, reverted
candidates, a fix for something adjacent — and its final measurement is taken where it stands. If
the branch has drifted from the base in ways nobody enumerated, every conclusion drawn from that
arm is about the union.

**Measure a candidate as the diff you intend to land, applied to the base you intend to land it
on.** When parking a candidate for a later round, record *what was in the tree when it was
measured*, not just the branch and commit — the next round should be able to tell whether it is
inheriting a measurement or a coincidence.

The round that caught this also did the right thing with its two suspects: it **tested** each as a
one-variable run and eliminated both, rather than reporting a plausible closer. Two negatives cost
minutes; a wrong attribution would have cost the next round its premise.

## Fifty-second rule: writing down a caveat is not checking it

A round built a whole-pixel anomaly detector, **stated its validity condition in its own report**
— that it is only trustworthy where neighbouring rows are exact — and then, two rounds later, used
it on rows where a second condition it had not thought to state was violated. The detector models
an **airborne** character; the rows it flagged were grounded, where position comes from ground
velocity through the terrain angle and the recorded horizontal speed is a derived copy that
*equals* it on flat ground. That equality is exactly what makes the model look applicable.

The result was a confident "six hundred rows early" that reached the frontier log and a status
report. The real number is on the order of thirteen, and the one row that survives is the only
airborne row in the set.

Two things to take:

- **A caveat you wrote is not a check you ran.** When you state a validity condition for an
  instrument, write it into the instrument if you can — an assertion, a filter, a refusal to emit
  — because the round where you are most confident is the round where you will skip re-reading
  your own warning.
- **Necessary is not sufficient.** "Exact on neighbouring rows" was a real condition and a true
  one, and it was not the whole set. When an instrument has one stated precondition, ask what the
  *other* ones are before trusting a result that depends on them.

The round caught this itself, retracted a headline that had already been repeated onward, and
asked for the log entry to be pulled. That is the outcome to aim for — but the cheaper outcome is
the assertion inside the tool.

## Fifty-third rule: a sign-flipping single pixel is phase, not a constant

**Signature.** A position or value differs from the recording by exactly one, and the
sign of the difference *changes* across the run — one high early, one low later — while
every constant in the owning routine has been read out of the disassembly and matches.

**What it means.** A fixed offset is a wrong constant. A difference that flips sign over
a slow accumulation is a *phase* error: the object started its motion a frame early or
late, or began from a different fractional position, and a sub-pixel-per-frame step turns
that head start into a rounding difference that lands on different frames at different
points in the motion.

**Why grep does not find it.** Nothing at any write site is wrong. Every constant is
correct, the comparison sense is correct, the ordering of the steps is correct. It is
only visible by comparing the object's *first executed frame* against the recording's
first sighting of that object's code — and note that an appearance event may fire on a
code-pointer change rather than only on creation, so the recording's first sighting needs
its own care.

**Why it is worth chasing.** A range test with an exclusive bound converts one pixel into
many frames of timing error. One investigation hit the same bound twice, excluding a
different actor on each of two rows by a single pixel.

**Before concluding "carried state".** Establish which routine actually runs. An index
table costs one command to read, and a routine that looks like the owner may never be
reached. Check also whether the allocator hands out zeroed slots and whether deletion
clears the object — if both hold, an "uninitialised field carrying prior state"
explanation is dead on arrival.

## Fifty-fourth rule: sample where the comparator samples

**Signature.** A probe shows a clean, consistent, plausible discrepancy — a fixed pixel
gap, or a state that changes a frame before it should.

**The trap.** A value read from inside an object's update pass, or from a controller
whose own frame counter advances at a different point in the loop, is being sampled at a
different instant from the comparator's. The result is uniformly wrong, internally
consistent, and reads exactly like a finding.

**How to kill it.** Anchor against something independent whose correspondence is already
known — a position the recording also records, or a frame whose value both sides agree
on — and check whether the offset applies to that too. A whole-run offset is also
impossible when a long stretch of the same run compares clean, which is itself a cheap
disproof.

Two rounds in one hour produced two of these, both caught before landing. Either sample
at the comparator's point, or state in the report that you did not.

## Fifty-fifth rule: an error count can read backwards

**Signature.** A change makes a segment's raw error count go *up*, and it is an
improvement; or the count goes down because less was compared.

**Why.** A segment that aborts early compares few rows. Fix the abort and it runs to the
end, comparing many more rows and reporting more errors while behaving better. The
converse also happens: a regression that truncates a run reports fewer errors.

**What to check before claiming either direction.** Whether the report says the run
completed, and where the last compared frame is. A count is only a comparison between two
runs that reached the same depth — the same requirement as an axis count, applied to a
single segment.

## Fifty-sixth rule: a label is an address, not a line number

A disassembly label of the form `loc_7870` names ROM address `$7870`. Citing it as
`file.asm:7870` lands on whatever happens to be at that *line*, which in a large
disassembly is unrelated code that looks plausible — the two ranges overlap, so nothing
about the citation looks wrong.

Verify a citation by reading what is at the line, not by checking that the number matches
the label. The same pass that caught this also caught an inherited off-by-one line
citation, so citations copied from existing comments deserve the same check as new ones.

## Fifty-seventh rule: a cited frame number can be an artefact of the defect

A comment justifying a branch by naming a specific frame is evidence that someone observed
something at that frame. It is not evidence that the branch is right, and the frame itself
may only ever have been reached *because* of a defect elsewhere.

One round found a compensating call whose comment cited a frame that moved by one as soon
as an unrelated one-frame error was fixed — the cited observation was real and the
conclusion drawn from it was wrong. Treat a frame-justified branch as suspect on sight,
and re-derive what the ROM does at that point rather than trusting the citation's framing.

## Fifty-eighth rule: the count of cancelling errors is usually understated

Two known cancelling errors are rarely the whole set. One round established a pair,
landed neither half alone because each turned green fixtures red, and then found a
*third* one-frame error compensating for the first — with a fourth requirement surfacing
only once the second was extended, found by measuring the intermediate broken state
rather than reasoning past it.

**Practically.** When you establish that two errors cancel, do not assume the pair is
closed. Land them together, verify against every anchor, and expect the intermediate
states between fixes to be informative rather than merely broken. Compensating code reads
as cited and faithful, because it was written to make a real observation come out right.

## Fifty-ninth rule: the engine can be missing a bug, not making one

A divergence where the engine's behaviour is *reasonable* and the recording's is not
usually means the ROM has a side effect the engine has no counterpart for — and the
shipped ROM's bugs are part of the specification, because all three disassemblies build
with their bug-fix conditionals off.

One round found an object borrowing the object loop's own slot counter as scratch and
leaving a constant in it, truncating the update pass so that an unrelated object in a high
slot did not run for eleven frames. The engine modelled that object's *intended* behaviour
faithfully and was wrong for exactly that reason. The disassembly's own comment warned
about the site.

**Practically.** When the engine looks right and the recording looks broken, search the
owning routine for writes to anything shared — a register the caller relies on, a global
the loop reads, a field another object owns. And read the disassembly's comments: the
un-fixed path is often annotated as a known bug by the people who disassembled it.

**When you model one,** name the flag and the branch in a comment as CLAUDE.md requires.
Once the side effect is implemented it becomes invisible again, and the comment is the
only thing that will make a future bug-fixed-revision effort tractable.

**Expect collateral, and do not dodge it.** A bug that truncates or reorders shared work
will change other objects' behaviour too. Those changes are ROM behaviour; a test that was
green without them was green on behaviour the ROM does not have. Establish that per case
rather than assuming it.

## Sixtieth rule: right family, wrong mechanics is still wrong

An inference that names the correct *kind* of cause — "something is truncating the object
loop" — can still be wrong about the mechanism in a way that changes the entire fix. One
round proposed a bound that shrinks as slots are freed; the actual cause was a constant
written over the counter. The shrinking variant was testable and false: the objects below
the supposed truncation kept updating every frame.

Test the mechanism, not the family. A family-level guess that survives because nobody
checked its specific prediction will be carried into the fix, and the fix will be built
around a mechanism that does not exist.

## Sixty-first rule: an absence in a routine is not a positive fact about it

Reasoning from what a routine *does not do* is the most reliable way to produce a
confident wrong answer. One investigation produced three refutations of the same shape in
a row:

- A phase entry did not write the shared timer, so the phase was concluded to be
  terminated physically. Reading the phase routine showed it is animation-driven like the
  engine's and does not terminate itself at all — the code that looked like a termination
  test is a flashing effect.
- An object's field was not written by the routine that sets its position, so the value
  was concluded to be inherited from an earlier motion. That earlier routine is never
  reached, and the allocator hands out zeroed slots anyway.
- An input value stayed constant across a window, which was consistent with a lock being
  taken at one row — and equally consistent with a later lock and no release, so it
  discriminated nothing.

**The pattern.** "X does not do Y, therefore Z" is only sound when Z is the *only*
alternative, and it almost never is. The fix is cheap: read one level deeper — the routine
the absence points at, the index table that says which routine runs, the script the
handler dispatches — before building on it.

**Related.** An empty grep is a fact about the grep (rule 56's neighbour), and a caveat
written down is not a caveat checked (rule 52). All three are the same failure: treating
the boundary of what you looked at as the boundary of what exists.

## Sixty-second rule: anchor a probe on a landmark, not on row arithmetic

A movie's logical frame is not the segment frame plus the manifest's offset. Computing a
site that way lands on the wrong rows — and the wrong rows do not look wrong. One round
spent a stretch reading state that was stable and self-consistent across the window it
had computed, with no staged work and no flushes, which reads exactly like a clean
negative result. It was simply somewhere else.

**Anchor on a gameplay landmark instead** — the player's position on the row you care
about, an object appearing, a state transition the recording also records — and confirm
the probe fires there before believing anything it says. This is the same failure as an
uninstalled instrument (rule 45) and a partially-instrumented path: the null result looks
like an answer.

**Related, and the reason this rule is separate:** rule 54 covers sampling at the wrong
*point in a frame*; this one covers sampling on the wrong *frame entirely*. Both produce
internally consistent output. A probe that reports the same value across a whole window is
evidence you may be looking at the wrong window, not evidence the value is stable.

## Sixty-third rule: resolve a divergence's values to their disassembly names

A mismatch reported as `rom=0x0008 eng=0x0055` carries no information about what either
side was doing. Resolved through the disassembly's own equates it becomes `fr_Walk13`
against `fr_Injury`, and the divergence explains itself: the recording published a walk
frame where the engine held the outgoing animation's.

**Why it matters beyond readability.** Three sites in one chain looked like three separate
defects while their values were hex. Named, all three showed the same recording-side value
and the same engine-side behaviour — one trigger with three occurrences, one round's work
instead of three. The unification was invisible until the numbers had names.

The equates are free to read (`_anim/*.asm`, `*.constants.asm`, the object id lists), and
they also make an impossible combination obvious: an animation id from one script paired
with a frame that appears only in another is a two-writes-in-one-frame signature, which no
amount of staring at hex will suggest.

**Do this before forming any hypothesis about a divergence**, not while writing it up.

## Sixty-fourth rule: a profile no matrix runs is not covered by any matrix

Every arm this project reports — the trace profile, the guard profile, the default suite —
omits at least one Maven profile. Tests in an omitted profile are green in nobody's
measurement: they can regress silently for weeks, and a report recording them as passing
stays on the record as the last thing anyone knew.

One sweep of such a profile found **sixty of seventy red**, including a class recorded as
passing six days earlier with thousands of frames compared.

**Practically.** Know which profiles your matrix does *not* run, and say so when you report
one. "Identical on every arm" means identical on the arms you ran. Before treating an
earlier pass as a baseline, check that the profile it came from is one anybody has run
since.

**And do not call an unmeasured profile's red a regression.** Without a baseline in that
profile there is nothing to difference against; most may be long-standing frontiers. The
honest report is a census — how many, of what kind — plus the specific cases where a prior
pass is on the record.

## Sixty-fifth rule: a shared exception type is not a shared defect

Two failures throwing the same exception, in the same zone, from the same subsystem, are
still two failures. One round recommended a target on exactly that resemblance, and
instrumenting the throw with its own state showed a different thrower, a different owner,
and a different cause.

**The instrument is the throw's contents, not its type.** Print what the failing structure
actually held — the occupants, the ordinals, the fingerprints — and compare those. A type
match narrows nothing: the same guard fires for every way of exceeding it.

## Sixty-sixth rule: a substring match is not a value match

Scraping logs and reports for a numeric result is a standard way to build a census, and it
is a standard way to build a wrong one. `0 errors` matches inside `5840 errors`. `frame=3`
matches inside `frame=3187`. A pattern that looks specific because it contains a number is
usually the least specific pattern in the file.

One census computed **twelve** prior passes this way; anchored properly the twelve became
three, and two of those three were a table row where the `PASS` belonged to the adjacent
class. **One survived.** The number that would have gone into the report was twelve, and it
would have turned fifty-nine unmeasured classes into eleven fabricated regressions.

**Practically.** Anchor numeric scrapes on both sides — a digit boundary before, a word
boundary after — and check a sample by eye against the source. If a count comes out
suspiciously convenient for the story you are telling, that is the moment to re-check the
pattern rather than the moment to report it.

## Sixty-seventh rule: a ratchet only checked at raise time will always be raised

A source-size budget, a maximum-error count, an allowed-failure list — any number that a
change must satisfy — constrains nothing if the only occasion anyone looks at it is the
moment it blocks someone. The person who meets it is always mid-task, always has a
justification, and always raises it. The number then re-sets to whatever the code happened
to be.

Measured on one such budget: **seven raises in fifty-five days, +11.6%, never once
lowered**, with the largest single raise explicitly recording that it was absorbing two
months of drift accumulated while no job ran the guard.

**The tell is the pattern, not any single raise.** Every individual raise looks
proportionate — an extraction behind it, a reason at the constant, a small delta. Ask for
the constant's whole history before approving one, and treat "this is the seventh" as the
finding rather than "this is a small one".

**The structural fix, when it is available, is to assert the property rather than the
proxy.** A line budget usually stands in for something real — that a particular kind of
logic stays out of a particular class — and that property can be asserted directly, at
which point it neither drifts nor needs raising. A proxy that can only be discovered at
raise time is a proxy that will be discovered at raise time.

## Sixty-eighth rule: an in-place routine change is not a spawn

A ROM object can rewrite its own routine byte and become a different object in the same
slot, keeping its identity, its position and its neighbours' view of it. The dispatcher
re-reads that byte on the next pass, so the new behaviour's first frame is the *following*
one — a deferral that involves no allocation at all.

Engines that model this as "the old object spawns a new child" inherit two problems. The
child lands in a different slot, which moves everything that depends on slot order; and the
spawn call decides allocation and same-frame execution together, so there is no way to
express "defer execution without touching allocation". A one-line override of the
same-frame flag is ROM-correct in isolation and can be badly wrong in aggregate: one such
override fixed its target frame and cost a thousand errors elsewhere, resurrecting an
unrelated cluster eighty frames earlier because a neighbouring object's slot moved.

**Recognising it.** Look for a routine or state byte changing on a slot that has no
creation event in the recording — the slot was already occupied, so nothing appears, and a
search for a spawn finds nothing while the transition is plainly visible in the slot's own
columns.

**Fixing it** means converting in place, reusing the slot and identity, rather than
spawning. That is a structural change; do not attempt it as a side effect of a frame-timing
round.

## Sixty-ninth rule: check that your two numbers are the same kind of measurement

The most productive error of one long session was comparing two quantities that were never
comparable, and it happened three times in a day:

- A queue's **occupants at the moment it overflowed** were compared against a recorded
  stream's **opening row**, and the difference was reported as an eleven-ordinal base
  skew. Both sides were in fact identical; the same art was legitimately submitted three
  times, so a later batch's ordinals looked like a shifted earlier batch's.
- A **whole-report error count** was compared against a segment's **physics-filtered
  headline field**, which cannot contain animation errors by design.
- A first-lap and a second-lap run were nearly differenced against each other while their
  **layouts differed**, which would have produced a confident answer about nothing.

Each comparison was arithmetically fine and each produced a plausible number.

**Before differencing two numbers, state what each one is.** Same quantity, same scope,
same instant, same filter. If you cannot say that sentence about both, the difference
between them is not a finding. This applies hardest to numbers you did not measure
yourself — a count quoted from a census or an earlier round carries its own scope, and it
is usually not written down next to it.

## Seventieth rule: review the artifact, not the account of it

Two failures on one night, both from accepting a description in place of the thing:

- A branch whose write-up **rejected** a candidate also **contained** it. The write-up was
  read, the rejection agreed with, and the branch merged — putting a change known to cost a
  thousand errors onto the mainline, where it also turned an unrelated green trace red with
  a sign-flipped velocity several thousand frames away. Reading a commit's prose instead of
  its diff is the same error as trusting a report instead of a probe.
- A round was told its measurements were outside a known-bad window. They were not; the
  claim was asserted without checking. The round re-measured anyway and its numbers held,
  but the assurance had consumed someone else's correctness rather than the giver's.

**Practically.** Before merging, diff the source portion of the change and read it — the
commit message describes intent, not content. After merging anything that touches runtime,
check a test the change does not claim to affect. And never tell someone their base is
clean without looking; "I checked and it is" and "I expect it is" are different sentences
and only one of them is worth anything.

**Corollary for lanes.** A rejected candidate never goes on a branch that will be merged.
Land the write-up; leave the code on a branch nobody takes, and cite its hash if provenance
matters.

## Seventy-first rule: validate a classifier against a case you already know

A sweep that pairs engine sites to ROM instructions automatically is a classifier, and a
classifier's output is worthless until it has been checked against an answer established by
hand. One built for a countdown sweep classified a fifth of its population, **missed the
site that motivated the sweep entirely**, and — on a second attempt — confidently
mislabelled the one case that had been hand-verified the round before, because it took the
first label in a comment window rather than the routine the code belonged to.

That error was only visible because a known answer existed to check against. With no
control case, the output would have been a ranked list: plausible, specific, partly wrong,
and impossible to distinguish from a correct one.

**Practically.** Before running a sweep, hand-verify one or two members and hold them back
as controls. If the classifier disagrees with a control, discard its output rather than
patching the heuristic — a heuristic that needed patching once will be wrong somewhere you
have no control for. And say plainly when a population is sized but unclassified: "621
sites, 223 candidates, 2 confirmed, the rest needs reading" is a result; a ranked list you
cannot vouch for is not.

## Seventy-second rule: prove an assertion fires before trusting it

An accounting assertion that has never failed is indistinguishable from one that cannot
fail. Before relying on a new invariant, break it deliberately — invert the comparison,
add one to a term — and confirm it fires on a real run. One round did this and found its
assertion live; without the check it would have been an instrument that reads green and
tests nothing, which is the same failure as an uninstalled probe.

**Where the assertion runs matters as much as what it says.** The tidy site for a check is
often a `writeReport`-style method called from a `finally`. If that `finally` catches only
`RuntimeException`, an `AssertionError` thrown there **replaces the exception from the try
block** — so a broken invariant would delete the real failure and report itself instead.
Put the check where it cannot displace the diagnosis it is meant to support, and say why
in a comment.

**Share the arithmetic, not a second copy of it.** An invariant that recomputes what a
report publishes can drift from it; one that reads exactly the published values cannot.

## Seventy-third rule: resetting the tree does not reset `target/`

`git reset --hard`, `git checkout`, and rebasing all leave `target/` exactly as it was.
A worktree that is now at a different commit still holds the previous commit's
`surefire-reports/` and `trace-reports/`, and every one of those files reads as current:
right paths, right names, plausible numbers, no marker of which tree produced them.

This has bitten in three dresses so far — a guard tally read from a full default-suite run
left behind by an earlier arm, a trace report quoted after a rebase, and a report directory
that survived a reset between measurement arms. In each case the stale numbers were
internally consistent and simply belonged to another commit.

**Practically.** Delete the report directory before a measurement, not after; quote numbers
only from a run you started in the tree you are in; and when a round reports a figure,
state the commit it was measured at (rule 6) so a stale one is at least visible as stale.

## Seventy-fourth rule: a second recording is what kills a timing story

A one-frame or two-frame adjustment can be ROM-derived, cite real instructions, improve its
fixture, and still be wrong. Resisting the temptation to fit is not what catches those —
the fix does not feel fitted, because it is not. What catches them is a **second recording
of the same object doing the same thing**.

One round found a genuine ROM-derived defect (an object sampling a render flag inside its
own update, where the ROM's flag is computed from the previous frame's position), measured
it as worth one frame, and watched it clear a stall in one fixture while moving another
fixture's first divergence thousands of frames earlier onto a recorded hardware edge. The
two fixtures pin the same producer to incompatible timings, so **no uniform shift fits
both** — which means the surviving explanation is not phase at all.

**Practically.** Before landing any change whose content is *when* something happens, find
a second fixture that exercises the same code and measure it there. If only one recording
exercises it, say so explicitly and treat the result as provisional; a change validated on
a single recording is validated against a movie nobody else will ever play.

When the two disagree, do not average them and do not pick the one that improves. Ask what
differs between the recordings other than time — occupancy, ordering, state the object
lands in — because a disagreement between two recordings of the same behaviour is evidence
that the axis you are adjusting is the wrong axis.

## Seventy-fifth rule: "nothing touches your path" is not "your numbers are comparable"

Two different assurances, and only one of them is usually true. A change to a subsystem you
are not measuring can still move every number you quote, because the *reporting* surface —
the comparator, the report writer, the fields a segment publishes — is shared by everything.

One round was told nothing in a window touched its path, checked, and found that true: six
source files had changed and none was in its subsystem. Three of them were the comparator's
reporting surface. So absolute totals measured before that window might not reproduce on the
newer base for reasons unrelated to anyone's fix.

**Practically.** When you vouch for someone's base, say which claim you are making. When you
inherit numbers measured on an older base, re-measure the control rather than carrying the
figure — a control is cheap and a mis-attributed regression costs a round. And when the
reporting surface itself has changed, say so in the report, because the next person will
difference your number against one taken on the other side of it.

## Seventy-sixth rule: judge a change on the contract it touches, not on a derived column

A candidate that changes hardware-timing behaviour should be measured against the
hardware-timing contract — the count of unmatched recorded edges — not against a physics
column that happens to move. One round rejected a genuine candidate because a derived
`busy` column shifted by a frame in a second fixture; measuring the contract itself showed
the candidate eliminated **every** unmatched edge in the first fixture and changed the
second's not at all.

**And check the gate's own standing before granting it authority.** That second fixture was
already unmatched on twenty-nine hardware edges, three of them submissions the engine never
makes at all. A fixture that fails a contract in bulk cannot arbitrate a one-frame change to
that contract on the strength of one derived reading. "This fixture is the harder gate" can
be true on one axis and meaningless on another.

**Practically.** Name the axis a change acts on, measure that axis on every fixture that
exercises it, and when a gate rejects a candidate, ask what that gate's own baseline looks
like before accepting the verdict. Rule 74 still holds — a second recording is what kills a
timing story — but only when the second recording is sound on the axis in question.

## Seventy-seventh rule: check whether your target is downstream of the segment's first error

Before spending a round on a divergence, find the segment's *earliest* error and ask whether
your target could be a consequence of it. A defect fifteen hundred frames earlier can be
load-bearing for the one in front of you while looking like unrelated background — and a
frontier log will happily record both, in separate entries, without anyone noticing the
dependency.

One investigation spent several rounds on an object's queue timing, produced four
characterisations, two retractions and two rejected candidates, and then found that the
object's controlling input was the camera, that the camera was seven pixels low, and that
the camera's divergence traced to the segment's own first error more than a thousand frames
earlier — an error every entry in the thread, including the lead's, had recorded and treated
as background. The object's lateness was inherited, not generated.

**Practically.** When a round's target is not the segment's first error, say so explicitly
and state why the earlier one cannot be its cause. If you cannot state that, fix the earlier
one first. And when a fix's own gate cannot be satisfied until an upstream defect is
corrected, record that as a fact rather than leaving it for the next owner to discover.

**Related trap, same round.** Phase lengths counted in dispatches and gates measured in raw
frames are different clocks; forty dispatches spanned sixty raw frames here, and mixing them
makes a ROM-exact phase look twenty frames wrong.

## Seventy-eighth rule: a probe that cannot disagree is not evidence

The strongest-looking measurement of one session was tautological. A probe compared an engine
counter against a recording's counter column and reported the control arm off by one on
**9469 of 9469 rows** and the candidate exact on 9469 of 9469. That reads as overwhelming, and
it carries no information at all: the probe aligned the two series from their last row, and
both series are arithmetic progressions of step one, so once the ends are anchored every row
agrees by construction. The 9469 comparisons are one comparison restated, and what it measures
is the difference between the two arms — which was the thing nobody disputed. The recorded
column enters only as an additive constant; any monotone sequence would produce the same two
numbers.

**The tell is the cleanliness.** A real measurement against a real reference does not come out
as "-1 on every row" and "0 on every row" on two arms. Perfect symmetry means the reference is
not constraining anything.

**What replaced it** is the shape to copy: the recording carries the ROM's own RAM for that
subsystem on every row, and the ROM derives its values from the counter by pure byte
arithmetic, so the recorded values **invert** to the seed the ROM used. Six inversions across
two fixtures, each exact at one candidate value and wrong at the other. That is arithmetic on
recorded ROM state at events identified by content, with no row alignment anywhere — which is
exactly why it *can* disagree, and did: it reversed the verdict the tautological probe had
produced.

**Before quoting a probe, ask what result would have falsified it.** If you cannot describe
the input that makes it report the other answer, it is not measuring what you think.

## Seventy-ninth rule: fixing a citation's line numbers is not checking its claim

A source comment asserted that a routine ran before the object pass, citing two line numbers.
The numbers had drifted, so a round replaced them with correct ones — and left the assertion
standing. The assertion was false: that routine is not in the main loop at all, it is reached
from a background-deform call *after* the object pass. Correcting the numbers made a wrong
statement look freshly verified, and the next three rounds — including the lead's briefs —
carried it as established.

**A citation has two parts and they fail independently.** The pointer can rot while the claim
stays true, and the claim can be false while the pointer is exact. When you touch a citation,
read what is actually at the target and check that it supports the sentence, not just that the
line number resolves.

**The compounding version is worse.** A freshly corrected citation reads as recently audited,
so it attracts more trust than the stale one it replaced. If you fix a pointer without
verifying the claim, say so explicitly in the commit — "line numbers refreshed, claim not
re-checked" — so the next reader knows which half was done.

## Eightieth rule: one false premise inside a tight derivation points it confidently wrong

The most persuasive argument of one session was five independently measured facts and a single
hypothesis explaining all of them at once — including *why* an existing behaviour was
accidentally right, which is normally the mark of a real model. It predicted a result, the
prediction was tested, and the two fixtures it targeted went from tens of thousands of errors
to green and ten. It was also wrong: one of the five facts was an unchecked ordering claim, and
the same hypothesis turned thirty-nine classes red across three games with none newly green.

**Elegance is not evidence, and tightness is not either.** A derivation that fits every
observation is more convincing when it contains a false premise, not less — the fit is achieved
by the error. Treat "this explains everything" as a prompt to identify which premise has never
been read at the source, not as a reason to land.

**When a direct measurement and a model disagree, the measurement is telling you which premise
to go and read.** In that round, six inversions of recorded ROM RAM contradicted a probe, and
were right; later the same inversions contradicted the round's own ordering story, and were
right again. Arithmetic on recorded state at events identified by content depends on no
ordering model, which is exactly why it survives when the model does not.

**Do not preserve a refuted derivation as narrative.** Record it as the retraction it is. A
persuasive wrong argument left in the place people look first will be re-adopted, and it will
be re-adopted *because* it reads well.

**Footnote on control-arm discriminators.** "Does the control's own error array already contain
this divergence?" separates a fix that revealed a frontier from one that caused it — but it
degenerates when the control is green outright. An empty array is then the answer, not a failed
search: nothing buried means the candidate caused it.

## Eighty-first rule: break every new comparison on purpose before trusting its green

A new comparison that has never been seen to fail is worth nothing, and there is no way to
tell a passing comparison from an unreached one by reading it.

One round wired a new per-row comparison into the obvious home — the live comparator — and
both affected classes stayed green. The green was worthless: the replay tests drive the
binder's compare directly from their own base class, so the live comparator's copy never
executed. What exposed it was deliberately corrupting the engine-side value and re-running:
the test still passed, and an unconditional print then confirmed the enclosing method was
never entered.

**Do this to every new comparison, instrument and assertion.** Corrupt one side, watch it go
red, then restore. If it does not fail, you have not written a comparison — you have written a
line of code.

**And check where the code under test actually calls from.** The tidiest home for a check is
often not on the path a given suite takes; two harness entry points that look equivalent can
differ in whether they run your code at all.

This is the third door into the same room as the tautological probe and the uninstalled
instrument: output that looks like a result and carries no information. The other two were
caught by asking what would falsify them; this one was caught by making it fail on purpose,
which is the cheaper check.

## Eighty-second rule: the oracle test needs an attribution clause

Deciding whether a recorded stream can be compared has three parts, not two. The values must
be ROM state the engine also holds; the arithmetic must be invertible so a recorded byte pins
an engine quantity; **and the row must be attributable to a specific engine object.**

The third clause is the one that blocks, and it blocks streams whose every individual byte is
perfect. One per-object stream decomposes cleanly, every field is individually comparable, and
none of it means anything until engine and ROM agree on which object occupies a slot — the
recorded identity is a live dispatch pointer that objects overwrite with their own internal
addresses at seventeen hundred sites, and only a handful of distinct recorded values are table
entries at all. A global block passes attribution trivially because there is no identity
question; a single-instance object passes it by content, because exactly one instance exists.

**Check attribution first.** It is the cheapest of the three and the only one that can make the
other two irrelevant.

## Eighty-third rule: promoting a stream to typed silently unhooks its untyped consumers

Recorded events that arrive through an untyped generic path can be read by formatters, report
writers and debug probes that switch on a key in the untyped map. The moment the stream is
promoted to a typed event, every one of those special cases stops firing — and it fails
silently in formatting rather than loudly in parsing, so the first symptom is an unrelated test
going red for a reason that looks nothing like the change.

Before promoting a stream, grep for consumers keyed on its untyped name and give each a typed
branch producing byte-identical output. Say in the write-up which consumers you checked.

**Related counting trap, same round.** A warning count in this harness counts *spans*, not
rows: a deliberately corrupted run diverging on nearly every row of a fixture reported three
warnings. Do not read such a count as a row count when sizing a divergence.

## Eighty-fourth rule: a duration measured in frames from a sparsely sampled stream measures the sampler

Some recorded streams are emitted **on change**, not every frame. A statistic computed in
frames over such a stream describes the sampling interval, not the phenomenon.

One round reported that 87% of divergence episodes lasted exactly one frame, and had begun
building a mechanism on it — "the engine spawns one frame late" — before withdrawing the
statistic outright. The stream's median spacing is fourteen frames, so almost every episode is
a single isolated sample and any frame-length rule reports one. Recomputed in *sample* units it
is about half, with the rest spanning up to sixty-two consecutive dumps, and the true durations
are unknown, bounded only by the gap to the next sample.

The same round's episode counts were overstated about 2.6x by a grouping rule that started a new
episode after a two-frame gap — which, against fourteen-frame spacing, shattered single episodes
into dozens.

**Practically.** Establish a stream's emission policy before computing anything over its time
axis, and express results in the stream's own units. Where the answer needs real durations, say
that the stream cannot supply them rather than reporting the sampler's period as the
phenomenon's.

**And the phase corollary.** With sparse sampling you also cannot rule out that the engine holds
a value at a different point in the frame than the recorder samples it: shifting by one sample
compares two moments a sampling-interval apart, which tests nothing. Anyone acting on such a
divergence must establish phase for their own object first.

## Eighty-fifth rule: an object that tracks the camera cannot be clocked by its own position

Aligning an engine object's rows to recorded rows by that object's own coordinate is circular,
and it fails hardest exactly where it looks most convincing.

One round aligned a descending object by its x, found the x matching the recording on every
row, and read that as confirmation the object's timing was right. It is not: during that phase
the camera advances one pixel per frame and the object's own step is one pixel per frame, so
the two move in lockstep and **a one-frame creation shift is invisible in x**. The round
confirmed it by inserting an idle frame — the x still matched on every row. Any later round
reading that coordinate as a clock gets a false green.

**Align on something the object does not control.** A frame counter the recording carries, or
a quantity driven by an independent system. Then re-test whatever you concluded: the same round
re-aligned on the run counter and found its earlier "the delta flips sign" was two intra-frame
sample points read as one series — the delta oscillates rather than flipping, which is a
different defect with a different owner.

**Corollary.** When a candidate fix moves a knife-edge predicate the right way while making the
underlying quantity *worse*, it is not the fix. A genuinely missing setup frame improves the
motion; only a coincidence improves the count.

## Eighty-sixth rule: the parsing choice is part of the measurement

When a statistic is derived from a stream, the grouping and parsing decisions behind it are
not implementation detail — they are the measurement, and a different reasonable choice gives
a different headline.

One analysis produced four such errors, every one self-caught and none raising any error at
all: episodes grouped by a frame gap against a sampler whose median spacing is fourteen frames
(2.6x inflation); a duration statistic computed in frames over that same sparse sampler
(withdrawn outright); a head-of-bucket ranking built on per-slot presence, which counts an
object that merely *moved slot* as absent; and a whole-map parse that read an occupant's
annotation fields as slot entries, briefly showing the engine holding two to three times the
ROM's objects.

**Three of the four pointed toward a more tractable defect than the truth**, which is the
direction that gets a lane commissioned on a false premise — and it is the second round in a
row where the errors leaned the same way.

**Practically.** State the parsing and grouping choices beside any derived number. Where a
choice exists, prefer the metric that does not depend on it: in that round, a per-type *count
deficit* inside the ROM's own slot range is placement-invariant, where per-slot presence is
not, and the two produce different top-of-list objects entirely. And when a ranking decides
what gets commissioned, re-derive it under a second metric before anyone acts on it.

## Eighty-seventh rule: fix the sample point before reading a delta on a folded parent/child object

An object the engine folds from a ROM parent and its children is written at several points
inside one frame. One capsule is written after its approach step, again after its swing step,
and again at the child's own dispatch — three positions per frame, against a recording that
samples once, at end of frame.

Compare the wrong one and you get a delta series that looks like a defect and is not. That
cost two consecutive entries here: one reported a sign-flipping delta, the next reported an
intra-frame ordering error and proposed a guard. At the *matching* sample point the engine's
value equals the recording's on every row with a clean one-frame lead, every other shift
strictly worse — a completely different defect with a different owner, upstream of the object
entirely.

**Establish which write the recording sees before reading any delta.** Where the ROM keeps the
child in its own slot, the slot index settles it: a child at a higher slot dispatches after the
parent and therefore sees the post-motion position, so the engine reading post-motion is
correct and a guard modelling "read the earlier value" would make the fixture pass and be
wrong.

**And re-test rejected candidates after fixing the sample point.** The same round had rejected
an inserted-frame candidate for "making the motion worse"; at the right sample point that
candidate matches on every row and fires the right actor on the right frame. The rejection was
an artefact of the measurement, not a property of the candidate.

## Eighty-eighth rule: a probe's report is a rendering, not data

Instrument output is formatted for a human. Parsing it as though it were the underlying data
means your analysis inherits every choice the renderer made — and the places where its
vocabulary is wider than your parser expects become silent holes in your results.

Five parsing errors across three rounds of one investigation shared this single root, unseen
until the last of them. The probe renders an occupied slot as a hex id, a *reserved* slot as
`RESERVED`, and an unattributed one as `UNATTRIB`; a parser matching hex values silently
dropped exactly the slots that model a composite object's children — and put a correctly
modelled, faithfully folded object at the top of a defect ranking, where it survived two
recommendations and a commission. Crediting the reserved pool removed 1,950 of its 1,969
"missing" entries and cut the game's whole deficit by 47%.

**Read the renderer before parsing its output.** Enumerate every value domain it can emit,
including the ones that mean "known but not an id".

**And reconcile any derived total against something independent.** Nineteen unexplained
entries against the engine's own reservation count would have exposed this immediately; that
cross-check was never computed until the round that found the error.

**Better still, do not parse a rendering at all** where the instrument can emit structured
output or where the quantity can be recomputed from the source data.

## Eighty-ninth rule: a class that documents N ROM slots and reserves none is the searchable defect

Where the engine folds a ROM parent and its children into one instance, the fold is only
faithful if it still **reserves** the children's slots — because slot numbers feed cadence
gates elsewhere, and a fold that quietly compacts them shifts every later object's index.

The axis that matters is therefore not composite-versus-burst but **folded-and-reserved
versus folded-and-not-reserved**, and the second has a tell you can grep for: a class whose
comments describe the ROM's slot count while the file never calls the reservation helper. One
such class documented "parent plus three children allocated after it" in *three separate
comments* and reserved nothing. Its per-frame deficit was three on two hundred and forty
frames and six on forty-one — one staircase and two — accounting for one hundred per cent of
its raw deficit with no behavioural component at all.

**Sweep for the shape before working any single member.** It is findable by reading a class's
own documentation against its own code, which makes it cheap, and a correct precedent already
exists to copy.

**And check the reservation question before commissioning any occupancy target.** Two
candidates in this investigation reached the top of a defect ranking while being correct code;
a third was fully explained by this defect and was not the object anyone had commissioned.

## Ninetieth rule: "not observable" is a claim about specific rows, not about the run

A quantity absent from the recording for part of a run may be present exactly where the
question lives. One investigation parked a boss's phase timing as a capture question — the
body never enters the recorded near-list — and three rounds were scoped around that
limitation. It is carried per frame at the rows that mattered: the fatal hit and the
transition into the wait are both visible as routine-pointer values, and only the interval
between them is a gap. The gap was irrelevant, because that interval's length is a ROM
literal rather than something to measure.

**Before declaring a quantity unobservable, check the specific rows your question needs.**
Presence is per row, and a stream that is silent across a window can still pin both of its
ends.

**And prefer a derivation with no shared input to a second opinion.** In that round the
transition row plus a ROM literal produced the same frame as an independent positional match
across sixty-two rows. Two derivations sharing no term is the strongest confirmation available
without new data — much stronger than the same measurement repeated.

**Corollary — a total can be right while both its parts are wrong.** The same object's two
wait stages are individually wrong and sum to the correct total, so no error count in the
fixture can see it; the first stage is not a constant in the ROM at all but inherited state,
so a different fight length breaks the cancellation. A sum that matches is not evidence its
terms do.

## Ninety-first rule: "a dispatch early" is a symptom class, not a diagnosis

Two objects in the same game process a hit one dispatch before the recording does, and they
have different causes. One skips the ROM's routine-zero dispatch because the engine models no
routine counter for it; the other has a correct routine machine and arrives at the boundary
early for its own reasons. Naming the shape narrows nothing.

**So do not merge two threads because their symptoms match**, and do not inherit the earlier
thread's framing with them. In this case the inherited framing — "the consumer is faithful, so
the question is when the flag was written" — was itself the thing that cost the first thread
several rounds: the write and the read are inherently same-frame, because the response list is
cleared between them by a dedicated object, so there was never any latency to collapse.

**Test the object's own phase against the recording instead.** And beware the corollary that
hid it the first time: where a fixture compares no object identity or position, "the player
matches the recording exactly" is fully consistent with the object leading by a frame. An
irregular multi-pixel step in the object's own position series is the fingerprint that
distinguishes them.

## Ninety-second rule: a textual tell generates candidates; only a measurement confirms one

A greppable signature is a way to build a candidate list, never a defect count. One tell —
a class documenting the ROM's slot count while never reserving those slots — matched thirty
classes. Six already called the helper. Nine created real child instances, which occupy slots
themselves and need no reservation. Of the fifteen that survived, cross-referencing against
measured deficits gave **one confirmed** structural defect, one plausible, one negligible, and
**four with exactly zero measured divergence** despite matching the tell precisely.

Left unmeasured, that tell would have justified fifteen rounds. That is the same
"more, smaller, more tractable defects" bias this investigation has met five times, arriving
in a new costume: a pattern that looks like evidence because it is objective and repeatable.

**So pair every tell with a measurement before commissioning anything from it**, and report the
candidate count and the confirmed count as different numbers.

**A related discipline for predictions.** State the units. One round predicted a deficit would
fall to zero, measured a residual of twelve, and the residual turned out to be zero in the
units the log reports deficits in — the raw metric counts typed slots and cannot credit a
reserved one. The prediction was right and stated in the wrong units, on an investigation where
several conclusions had already turned on exactly that.

**And be explicit when a clean gate is not evidence.** Where no committed comparison covers the
quantity a change improves, the suite cannot see the improvement in either direction; the clean
arms buy a non-regression check, not a confirmation. Say which one you have.

## Ninety-third rule: an edit that cannot change any value is not a safe edit

A correction that provably changes no value on any frame of any fixture looks free. It is not:
it is an unverifiable edit to trace-verified code. Nothing can confirm it and nothing would
catch it if the reasoning behind it were wrong.

One round was sent to apply a water-level correction to a second game's bubble objects, having
just landed the same correction in the first. It stopped at the zone question and found that
the object exists only in the one zone the ROM excludes from the sway, so the two accessors
return the same number there by construction. It made no change and said why.

**Ask what the edit would change before making it.** If the answer is "nothing on any recording
we have", the honest outcome is a documented no-op, not a commit.

**And close the sweep in the same breath.** That round then checked every site in all three
games against the same tell — nine in the first game, all but one already correct, two
deliberately reading the base value for reasons that survive reading (a checkpoint saving a
height must not bake in a frame's sway); the second game's only such object is in the excluded
zone; and the third game has no sway term at all, so its reads are exact by construction. One
defect in three games, and the population is now known rather than suspected.

## Ninety-fourth rule: measure both directions, or the metric names the wrong defect

A one-directional metric answers a question you did not ask. One investigation ranked objects
by how many the recording held and the engine did not — and when a candidate was finally
measured both ways, it was **340 short on 305 frames and 373 over on 124**. The over-count was
larger than the shortfall, so "a 340 deficit" was wrong in kind, not degree: the engine holds
*more* of that object than the ROM, and what looked like an allocation shortfall is a two-way
timing divergence.

Measured both ways, the population split cleanly and **replaced two earlier family claims at
once**: some objects are never made, others are made at the wrong time, and the two need
different questions. The families that had been proposed — composite versus burst-spawned —
cut across that line, with one burst effect genuinely absent and another merely mistimed.

**So report both directions from the first measurement**, and treat a one-way total as a lower
bound on nothing in particular until you have the other.

**And rank by short *plus* over, not by shortfall.** Ranking by deficit is structurally
incapable of showing an object the engine holds *too many* of. Sorting the same data by the sum
surfaced three objects with **no shortfall at all** — five hundred and twenty-three, four
hundred and fifty-seven, and a hundred and eighty-one instances the engine holds and the
recording does not — one of which would have ranked third overall. Every deficit-ranked list
produced before that point could not have shown them, whatever its other corrections.

**And re-check your own filters after they have been wrong once.** The same round's second
filter produced a false positive — a class excluded from "creates real children" because its
name matched none of the expected child-name patterns — one round after the first filter's
false-positive rate had been reported. A filter that has failed once is not more trustworthy
for having been noticed.

## Ninety-fifth rule: read both arms of a `FixBugs` conditional — it may only reorder

The bug-fix conditionals in these disassemblies do not always gate *whether* something happens.
Sometimes both arms do the same work and the conditional only changes the **order**, with the
retail arm displaying before deleting and the fixed arm deleting first — the comment on such a
site often says so outright ("moved to prevent a display-and-delete bug").

One round read a delete inside such a block, took it for a fixed-branch-only behaviour, and had
written it up as a third defect in the object. Both arms delete; the engine's margin already
matched the ROM's exactly. Correct code, nearly reported as defective because "if FixBugs" was
read as a gate rather than as a fork.

**Read both arms before concluding anything about a conditional.** The engine models the retail
path, so what matters is what the retail arm does, not that a conditional exists.

**Two related tells from the same round, both worth carrying.** An uncited deletion commented as
"general cleanup" is almost always invented — a cleanup with no cited ROM line is the tell. And
where the ROM *converts an object in place* (changing its id and keeping its slot) while the
engine deletes and spawns a replacement, the freed-then-retaken slot shows up as a **two-way**
divergence in the replacement's type and a one-way shortfall in the original's: two numbers that
are one defect seen from both ends, and only the ROM's convert-in-place line connects them.

## Ninety-sixth rule: the bias is toward whichever conclusion ends the round

Earlier entries in this document describe a tendency for measurement errors to lean toward
"more, smaller, more tractable defects". That characterisation was too flattering. Across one
long investigation the same lane recorded seven wrong-leaning readings, and the seventh leaned
the *other* way: a misread of a class's name led it to rule out the only workable mechanism and
it nearly closed the round as impossible.

**The bias is toward whichever conclusion terminates the work.** A finding ends a round; so
does a blocker. Both feel like progress, both are reportable, and both stop the reading that
would have overturned them. That is a sharper and less comfortable statement than "we tend to
find defects", and it predicts the failure in both directions.

**Practically.** When a reading would let you stop — either "here is the defect" or "this cannot
be done" — that is the moment to spend ten more minutes, not fewer. Check the name you inferred
a capability from. Read the other arm. Ask what the opposite conclusion would require and
whether you have ruled it out or merely not looked.

**And beware a blocker that is really a scope discovery.** In that round the seam did work; what
was actually true is that using it would have defeated an engine invariant deliberately built to
match the ROM's allocation order — the very property the investigation exists to measure. That
is not a blocker, it is the shape of the correct fix arriving in a form nobody had scoped.

## Ninety-seventh rule: pin both arms to a SHA, because the ref moves under you

`origin/develop` is not a fixed point on this checkout. A lane pinning its control arm to that
ref had it move **twice mid-run** while the lead merged other lanes' work, so the two arms were
built from different trees while the report was about to claim reach proven equal. The added
commits happened to be documentation-only, so no verdict was actually wrong — that is luck, not
method.

**Pin both arms to an explicit SHA and quote it in the report.** On a checkout with concurrent
sessions this is the default, not a precaution for long runs.

**The lead's merging is the mover.** Landing lanes' work promptly is right, and it is precisely
what makes the ref unstable for everyone still measuring — so the fix belongs on the measuring
side, not in slowing merges.

## Ninety-eighth rule: `-Dtest=` silently overrides profile selection

Running a single class with `-Dtest=` does not respect the profile's includes: the class runs
even when the profile you passed would never have selected it. So a class can be measured green
"under `-Ptrace-replay`" while living in a profile that arm never runs, and the result reads as
covered when it is not.

One round measured a landmark class that way and only discovered afterwards that the class
belongs to a profile its matrix omitted entirely. Same family as the two-clocks trap: a selector
that quietly widens what you think you measured.

**Check which profile owns a class before quoting a per-class result as matrix coverage**, and
name the profile you actually ran. If a landmark lives outside your matrix, either add its
profile to the matrix or state plainly that the landmark is unmeasured by it.

**And scope the claim to what moved.** In the same round the landmark closed in its standalone
class while the chain that contains that zone never reaches it — the chain is blocked upstream
by an entry-state divergence at its own frame zero. "The landmark is closed" is true of one
class and false of the chain, and the distinction has to be in the sentence.

## Ninety-ninth rule: a jump target may fall through past an "End of function" banner

A `jmp` to a label that looks like a one-line helper can fall straight into the next routine.
One such target was a single instruction clearing an unrelated timer, and it fell through — past
a comment banner marking the end of the function — into a routine whose first instruction writes
the very field three rounds had been trying to account for.

**Read past the end of the routine you jumped to.** Banners and blank lines are formatting, not
control flow, and a disassembly's own section markers do not stop execution.

**And a figure "declared dead" may be hidden rather than fitted.** That value had been ruled a
fitted constant precisely because nobody could find a write site for it, so every appearance
looked invented. It is a ROM literal with a real write, and the rounds that killed it were
right about the evidence they had and wrong about the conclusion. When a constant is declared
dead for want of a source, record *that* as the reason — "no write site found" is a different
claim from "not a ROM value", and only the first survives someone finding the site.

**Corollary, from the same round.** The claim that two objects were in different phases at a
transition was retracted by the same discovery: the inherited value was never load-bearing,
because the routine overwrites it. A round scoped on that claim would have been chasing
something that does not exist.

## One hundredth rule: a survey by name finds one idiom out of five

Sizing a directory by grepping for a named construct measures how many classes use *that name*,
and this codebase names the same contract five different ways. A survey of one directory found
eleven classes modelling a ROM dispatch through a declared constant, an enum case named for the
ROM routine, a pending-flag guard, a plain boolean latch, and an unnamed branch in an else-if
chain. Only the first is greppable — which is why a name-based sizing reported five.

That understated the **correct** group by more than half, so the danger runs in both directions:
quoting such a ratio as a defect count overstates the work, and quoting it as coverage
understates the code that is already right.

**Classify by reading the mechanism, and say which idioms you looked for.** Almost the entire
cost of that survey was discovering that four of the five are invisible to a search for the
fifth.

**And the best statement of a contract is often a comment, not a name.** The clearest expression
of that dispatch rule in the whole tree is a sentence in one class's comment describing what the
ROM installs and when it next runs — unreachable by any search for the concept.

**Leave the unread as candidates.** Four classes in that survey have a zero state that is a wait
or a gate — the shape a folded setup frame leaves behind — and were recorded as unclassified
rather than as findings, because the engine lacking an init state is not evidence the ROM has
one until the ROM's own table is read.

## One hundred and first rule: a single-point clock conversion is not a measurement

Converting a value in one clock to a row in another — an engine counter to a trace row, a
dispatch index to a frame — by observing the two together **once** and subtracting assumes the
two advance in lockstep everywhere between. Over hundreds of frames that assumption is doing all
the work, and a single extra or missing tick anywhere in the span produces exactly the one-row
error you are trying to resolve.

One lane's defeat-frame reading rested on such a conversion, calibrated at a single point four
hundred and sixty frames away from the measurement, and validated only inside a sixty-frame
window — by an alignment that lane had itself already shown to be degenerate. A second lane
measured the same event with no conversion at all: contact separation against the ROM's own
threshold, plus seven earlier events matched to the recording's own edges, entirely in the
recording's coordinate system. The second is strictly better evidence, and the first lane
withdrew its number rather than defend it.

**Prefer a conversion-free method.** Where the recording carries the quantity, compare in the
recording's coordinates. Where it does not, validate the conversion **across the whole span you
are using it over**, not at one point — and say in the report where it was validated.

**And notice when a quantity you trust is conversion-dependent.** In the same thread a wait
measured as a difference of counter values was being compared against a ROM wait measured in
rows; those are the same number only if the two clocks run one to one, which was the assumption
under dispute. A drift between them would displace two downstream events by different amounts.

## One hundred and second rule: a recorded object id is not a global identifier

Object ids are per-game. Grouping a cross-game measurement by raw recorded id silently merges
different objects: one id ranked as the largest remaining target showed three and a half
thousand short lines across seventeen fixtures until it was filtered to the game where that id
actually names that object, which reproduced the six-fixture count the summary already carried.
The filter is load-bearing, not a detail.

**And the same slots can appear twice under two names.** In that measurement, the lines where
the recording holds one id and the engine holds another number **exactly** as many as the lines
where the engine holds the second id and the recording does not — five hundred and eleven each
way. One defect, counted once as a shortfall of the first object and once as an over-count of
the second, sitting in two different tables of the same summary as a one-way target and a
two-way target.

**The tell is symmetry.** When a shortfall in one type and an over-count in another match to the
row, suspect a single object being misidentified rather than two populations being mismanaged —
here the ROM rewrites a live object's id in place while the engine leaves it under its original
id, so it reads as both at once.

**Practically.** Filter to the game before ranking; cross-tabulate the top shortfalls against the
top over-counts before commissioning either; and treat an exact match between two such counts as
a single finding until shown otherwise.

## One hundred and third rule: a failed run leaves the previous run's output in place

A build that fails before executing anything writes nothing — and deletes nothing. The
artefacts from whatever ran there last are still on disk, with the right names, in the right
directory, in the expected quantity.

One sweep used an invalid separator in its per-class selector, so the build failed with no
tests executed. The round then analysed ninety-four probe files that had been written two and a
half hours earlier by a run of unknown provenance, and the fact that the file count matched
expectation read as confirmation. Every figure it derived — including an exact numeric pairing
that made its conclusion persuasive — is unattested.

**Before analysing any artefact, prove the run that was supposed to produce it actually ran.**
Check the build's exit status, check for the tests-run lines, and check the artefacts' modification
times against the run you just started. A count that matches expectation is not evidence of
freshness.

**And a missing artefact is not a feature failure.** The same round concluded the probe's full
mode did not reach the forked process; it emits normally, and the absence was the same
non-existent run. Absence of output has two causes and the boring one is far more common.

## One hundred and fourth rule: a true general claim can still produce a wrong general change

"A reserved slot holds exactly one object, in every game" is true, and putting the guard that
enforces it in the shared helper was wrong. Four of the five callers hand the created object
back to a caller that keeps and uses the reference, so suppressing the add leaves a live but
unwired instance — the matrix returned errors and a guard failure. The fifth discards its
return value, which is the only reason the narrow fix is safe there.

**What varies between call sites is the contract, not the invariant.** Before generalising a
correction, check what each caller does with the result, not just whether the rule applies to
it. The round that hit this had established the ROM's write semantics for exactly one of the
five sites and generalised from that one.

**And a general change carries a general cost.** The same attempt pushed a facade past its
source-size budget, which was the second signal that the predicate did not belong there.

**Corollary, from the same round:** when a masking defect is removed, an underlying one can get
*worse* on the count. Suppressing the duplicate object raised one cluster's shortfall by two
frames, because on those frames the duplicate had been the only live object. That is the
masked defect showing through, not a regression, and the report should say which.

## One hundred and fifth rule: a class that documents the behaviour it does not implement

Five times in one investigation, the clearest statement of a defect was a comment in the very
class carrying it. A staircase documented the ROM's four slots in three separate comments and
reserved none. A cannonball's javadoc quoted the ROM's convert-in-place while the code deleted
and respawned. A boss's init profile named the ROM's last load phase while running it first. And
a Super-state controller documented the transformation frame's acceleration change — with the
ROM line cited and the exact velocity transition spelled out — while producing a different
number.

**So grep the comments, not just the code.** A comment that states a ROM behaviour precisely,
including a numeric transition, is a claim that can be checked against what the code does; where
they disagree, the comment is usually right and was written by someone who read the disassembly
and then implemented something else.

**The strongest form of the tell is a cited number.** A comment naming a value the code cannot
produce is a defect with its own acceptance test attached — the value is the fingerprint, and it
cannot be satisfied by tuning something else.

## One hundred and sixth rule: count the conversions, and prove the change was exercised

**When two measurements disagree, count the conversions between raw fixture and claim before
arguing the substance.** Two disputes today were settled that way, and in both cases the losing
claim had exactly one conversion buried in it — a frame number derived from a counter running
twenty-three behind, and a counter value attributed to the row just compared rather than to the
row its update produces. Neither lane was careless; the conversion was invisible in the report
and load-bearing in the conclusion. Prefer the measurement that compares in the recording's own
coordinates, and when you cannot, say where the conversion is.

**And a clean matrix and an unexercised change look identical from outside.** A round landing a
one-line behavioural fix measured which it had: a constructor probe counted sixty spawns of the
affected object in a single fixture, so the change is exercised heavily and the comparison still
does not move — because those classes compare player and sidekick physics and no object identity,
slot or position at all. It was written up as **"no observable effect on compared fields", not
"no effect"**, and landed on ROM fidelity rather than on the strength of a green matrix.

That distinction matters later: an inert-looking change cited as evidence of inertness is how a
real regression gets attributed to something else months afterwards.

## One hundred and seventh rule: installing a routine costs the rest of that dispatch

In the ROM, writing a new handler into an object's own slot does not make it run sooner. The
dispatcher has already called the old handler for that slot this frame, so the newly installed
routine first executes on the **following** frame. That single invariant is behind the
offscreen-wait release pass, behind every routine-zero setup that advances and returns, and
behind a boss whose post-defeat countdown starts a frame early in the engine.

The engine's equivalent sites often change an object's routine during the player's touch scan
and then dispatch the new routine later in the *same* frame, so its body runs on the frame that
installed it — one tick early, every time, structurally rather than arithmetically.

**Do not fix it with a skipped tick or an offset.** That is the fitted form. Model the ROM's
rule: the new routine does not execute on the dispatch that installed it.

**And the class is not confined to setup routines.** Two surveys in one investigation covered
routine-zero init only; the same defect appears wherever a routine is installed mid-frame and
run in the same frame, so the population is larger than either survey measured. That is the
frontier, not the individual object it surfaced through.

## One hundred and eighth rule: the symptom is one frame; the class is one intra-frame slot

Rule 107's class has a boundary, and finding it by symptom will not locate it. Creation lag,
clear-ownership errors, allocator-class errors and slot mismatch **all emit an identical
one-frame signature**, so symptom-matching produces false positives indefinitely — two arrived
in one day, each costing a full investigation to exclude.

Three questions, in order; a "no" at any one ends it:

1. Is a **handler/selector for the object's own slot** being installed — a value the dispatcher
   will jump through — as opposed to a state byte, a flag, or an allocation? If the dispatcher
   re-reads the byte fresh every frame and the argument is about who *writes or clears* it, it
   is not a member.
2. In the ROM, is the write site reached **after** that object's dispatch already read its
   selector this frame? **This reduces to a single lookup, with no reasoning about slot order.**
   `TouchResponse` runs from the *player's own* control routine — `Sonic_Control` /
   `Tails_Control` / `Knuckles_Control` (`sonic3k.asm:21947, :26159, :30389`) and from Sonic's
   and Tails' object code in S2 (`s2.asm:38998`) — and the players run before the object slots,
   so a `Touch_Response` write **always** lands before the touched object's own dispatch, in all
   three games. So: find where the ROM writes the selector. Inside `Touch_Response` /
   `Touch_Enemy` → **not a member**, the ROM runs it same-frame too. Inside the object's own
   routine or its tail → **member**. Every confirmed member writes from its own tail:
   `bsr.w sub_73FE2` (LBZ), `bra.w ObjC5_HandleHits` (WFZ), `AIZEndBoss_CheckHitOrDefeat` (AIZ2).
   That is also why ordinary badniks are not members and never will be.
3. In the engine, does the write happen **before** that object's own `update()` — touch scan,
   solid pass, an earlier object? If engine and ROM write from the same relative position, no
   member.

**Q2 does not transfer to cross-object installs, and it fails in the dangerous direction.** Q2
treats "not inside `Touch_Response`" as meaning the target's dispatch already happened. For a
*same-object* install those are the same fact — a routine writing into its own `(a0)` is
necessarily downstream of its own head read. For a *cross-object* install they come apart: a
parent's write is never in `Touch_Response`, so Q2 answers "member" every time, while whether the
child already ran is not a property of the write site at all.

4. **Cross-object only: is the target's slot before or after the writer's?** After → the ROM sees
   the install same-frame, and the engine updating children inside the parent agrees by
   construction, so **not a member**. Before → **member**.

**And Q4 is not always answerable by reading.** Slot order is the allocator's:
`AllocateObjectAfterCurrent` (`s2.asm:33705-33724`, `sonic3k.asm:37917-37930`) scans forward from
`a0` and is therefore *always after* the parent — settled by reading. Plain `AllocateObject`
(`s2.asm:33681-33695`, `sonic3k.asm:37911-37914`) scans from the start of the dynamic table for
the first free slot, so the child may land before *or* after depending on live occupancy.
For those children **membership is a runtime property that reading cannot settle**, and it can
differ between two runs of the same object. That is strictly harder than the same-object
population, where reading always settles it.

**The cross-object direction is otherwise structurally empty.** Swept by role: 42 raw sites → one
true cross-object install, which resolves to a non-member by reading (S2's `LoadChildObject` calls
`AllocateObjectAfterCurrent`, `s2.asm:73012-73014`). No level-event manager writes an object's
selector. Nine of S3K's ten `CreateChild*` helpers use `AllocateObjectAfterCurrent`; the exception
is `CreateChild7_Normal2` (`sonic3k.asm:177145-177175`), plain `AllocateObject`, whose four call
sites include `HCZEndBossBomb_ResetOrSpawn` (`:141453`) — inside the AIZ→HCZ slice and **not yet
implemented**. That is a constraint to hand the implementer, not a defect to fix.

**A by-role sweep has its own failure mode when the "role" is a bare method name.** Expanding by
method name matched anything sharing it — a map's `clear()` among them — and produced 1366 sites
across 362 files with no signal. Filtering by the *receiver's declared type* is what made it
tractable.

**Q1 is the cheap cut** and disposes of most false positives by reading alone. An S2 Super Sonic
freeze release (a state byte read at the top of the dispatch; the defect is which subsystem
clears it) and an S1 air-bubble creation lag (an allocation-ordering error) both fail Q1 —
neither is a member, and neither needed a measurement to exclude.

**Survey this class by role, not by name, and expand one level through same-class calls.** Five
distinct idioms model the invariant correctly with no shared searchable name, and one confirmed
member is neither a boss nor a touch callback. A name grep reports a class that does not exist.

**Confirming a member does not make it landable.** A member whose correction moves a countdown
moves everything downstream of it. WFZ passes all three questions, has both halves measured, and
still cannot be landed alone: correcting the engine's 238 to the ROM's 239 puts a new
`camera_y` error into a trace that had none, because the engine was previously *in phase* with
the recording while holding a value the ROM does not hold. If a covering trace goes red on a fix
whose ROM half is solid, suspect a compensation the defect was cancelling, and take the stack out
in one move (rule 17) rather than reverting the correct half.

**Measurement hazard, from the same case.** `TestS2WfzLevelSelectTraceReplay` fails in *both*
arms — control `0 errors, 3 warnings` on a pre-existing `tornado.status_byte` warning gate, fixed
arm `1 error, 3 warnings`. A pass/fail comparison of that class reads "red before, red after, no
effect" and lands a regression. Only the message separates them (rule 24). A class red in the
control **for an unrelated reason** is the case that defeats arm-vs-arm status comparison.

## One hundred and ninth rule: on a moving object, `spawn` is not an identity

Keying a persistence measurement on `(slot, spawn)` reported **713 of 714 objects living exactly
one sample** — apparent instant death, i.e. wild over-creation. Re-keyed on **slot alone**, the
verdict inverted completely: 36 distinct objects against the ROM's 64, each held roughly eight
times longer. Under-deletion, the opposite owner.

The `spawn` record is **rebuilt as the object moves**, so the key changes every frame and every
sample looks like a new object. Any metric keyed on it measures motion, not lifetime.

**This is the second inversion of its kind.** A latch keyed on a moving spawn rather than the
instance produced the same class of error in an S1 Labyrinth block. Whenever a measurement or a
latch needs object identity, ask what the key does when the object moves — and prefer the slot,
the instance, or an explicit id.

The tell is a persistence or occupancy result that is *implausibly* one-sided (every object dies
instantly; nothing is ever reused). Before reporting the owner it implies, re-key and re-measure:
an inverted verdict arrives with a clean-looking number behind it and nothing else marks it wrong.

## One hundred and tenth rule: a batch instrument needs a positive control, or it converts unreachable code into exclusions

A detector that reports "nothing moved on the install frame" reads exactly like *models the
invariant correctly*. It is equally the signature of **the arm never having run at all**. One
candidate in a batch of nine looked clean for the second reason: `updateBossLogic` early-returns
before its routine switch while an arena gate is false, so the defeated arm never executed.
Nothing moved because nothing ran.

**The control: dispatch a second frame as well.** If the arm is live, a countdown moves on at
least one of the two. If neither moves, the arm never ran and the verdict is **inconclusive, not
negative**.

Without that control, a batch instrument silently converts unreachable code into exclusions — and
does so under the authority of a method already treated as reliable, which is what makes it worse
than an obviously broken probe. It was caught only by going to read *why* a result looked clean
instead of banking it.

**Related, on scope:** the same batch settled its ROM half from **one routine per game**
(`Touch_Enemy`'s boss path, `sonic3k.asm:20908-20925`; `Touch_Enemy_Part2`, `s2.asm:85373-85382`)
rather than nine readings — neither writes a routine, so the install is in each boss's own
dispatch. Same fingerprint, same lever, one reading. But the engine half reached only 1 of 9: the
rest are blocked on **fixture depth, not analysis** — objects no trace reaches, needing real level
context. "Measure eight candidates" and "build real level fixtures for eight bosses" are different
commissions, and a batch that starts as the first can quietly become the second.

**And a confirmed member with no covering trace should not be landed.** The one confirmed class
sits far past the current chain frontier. A correct member fix can be load-bearing (see WFZ); with
no covering trace there is nothing to reveal a compensation if one exists, so a green unit test is
not proof. Confirmed-and-unlanded is an honest state, not a stalled one.

## One hundred and eleventh rule: `-Dtest=` overrides a profile's PATTERNS, not its tag filter

Rule 98 says `-Dtest=` silently overrides profile selection. That is true of a profile's
include/exclude **patterns** and false of its `<excludedGroups>` **tag** filter.

Measured against the same profile in one round: `TestS3kHczZoneSliceTraceReplay` is *pattern*-
excluded from `trace-replay` and `-Dtest=` **ran it anyway** (2 tests); a chain class dropped by
`@Tag("trace-scope-r7")` against that profile's `<excludedGroups>` (`pom.xml:259`) ran **nothing**
under the identical flag shape. Same invocation, opposite outcomes, and the only difference is the
kind of exclusion.

**From outside, both look like `BUILD SUCCESS`.** A class that never ran and a class that ran
clean are indistinguishable in the exit status — so check `Tests run:` for the class you targeted,
by name, before quoting any result from a `-Dtest=` invocation.

The tag mechanism is also easy to misdiagnose: a class can be present in a profile's include
patterns, absent from its exclude patterns, and still never run. Read the `<excludedGroups>` and
the class's `@Tag`s before concluding a profile covers it.

## One hundred and twelfth rule: adjacent SST bytes make a word write invisible to a byte-field survey

`anim_frame = $1B`, `anim = $1C`, `prev_anim = $1D` (`s2.constants.asm:32-35`) are **adjacent
bytes**. So `move.w #(AniIDSonAni_Walk<<8)|(AniIDSonAni_Run<<0),anim(a1)` writes `anim` *and*
`prev_anim` in one instruction — and since `Sonic_Animate` resets `anim_frame` whenever those two
differ, that word write resets the animation frame without ever naming it. The disassembly states
the intent outright (`s2.asm:35481`, "use walking animation (and force it to restart)"), and about
twenty sites use the idiom.

A survey for `anim_frame(aN)` sees **none of them** — nor writes by absolute address
(`move.b #AniIDSonAni_Run,(MainCharacter+prev_anim).w`, `:26033`), of which two hit the player.
An "exhaustive" survey on that grep missed roughly two thirds of the real population and produced
a confident false constraint: *"`prev_anim` is written in exactly two places"* — it is three.

**Two consequences worth carrying separately:**

1. **A field's population is every instruction that changes its bytes, not every instruction that
   names it.** Before calling a field survey exhaustive, check the field's neighbours in the SST
   and search for word and longword writes at the enclosing offsets, plus absolute-address forms.
2. **"Invisible at frame granularity" is a strong claim; check the column list first.** The same
   round concluded a within-frame transient must be responsible and that only a sub-frame probe
   could resolve it. False: a single word write leaves `anim` reading `02` on both sides while the
   pair is unequal, fully visible in one frame. It looked invisible only because **`prev_anim` is
   not a recorded column** — a gap in what the fixture carries, not a limit of the sampling rate.
   The fix is a column, not a recorder redesign.

## One hundred and thirteenth rule: a citation can be wrong *and* agree with the right answer

The worst citation is not one that is visibly wrong. It is one that yields the correct number by
coincidence, because then no output can ever reveal it.

Deriving an object's culling margins, the first `subObjData` hit matching the shape was **`ObjA6`,
the CPZ Spiny** — which also uses `Obj98` and shares a mappings label. Its row
(`on_screen|level_fg, 5, 4, $98`) carries the **same `width_pixels` 4 and the same
`collision_flags` $98** as the intended object's, differing only in priority. Citing the Spiny for
the wall turret shot would have produced the right margins with a wrong citation, and every number
in the output would have agreed.

**The only defence is reaching the row through the object that actually loads it** — follow the
`Init` routine to its `LoadSubObject` call and read *that* row — never the first grep hit whose
shape matches.

**Two corollaries from the same derivation:**

1. **Do not take a ROM fact from an engine javadoc.** The margin rested on `explicit_height` being
   clear, taken from the engine class's own javadoc rather than the disassembly — the exact
   failure the surrounding audit was written about, committed while writing it. Re-derived at
   source: `LoadSubObject_Part3` (`s2.asm:72715-72726`) applies mappings, art_tile, render_flags,
   priority, width_pixels and collision_flags, and **never writes `y_radius`**.
2. **`ApproxYCheck`'s 32 generalises within its class; the x margin never does.** A second row on
   the same object (`ObjB8`, `s2.asm:80297`) is also `explicit_height`-clear — so also 32 — but
   carries `width_pixels` `$10` rather than 4. Per-object width, shared approximate height.

## One hundred and fourteenth rule: a true general principle can explain away the one piece of evidence pointing at the answer

A chain stall reported a suppressed `PendingRecordedSubmissions [kind=KOS_MODULE_QUEUE...]`
alongside its primary failure. It was dismissed — twice, and with an explicit warning not to send
anyone to the timing port for it — on the reasoning that **a stalled run leaves submissions
pending by construction**. That reasoning is *true in general*. It was also wrong here: the stall
turned out to be a title card blocked on queued-art readiness, and the pending job was the queued
art. Same defect, or directly implicated.

**The failure mode is a correct principle used to discard the single most informative signal.**
Before dismissing a secondary symptom as downstream, ask whether it names the same subsystem as
anything in the primary failure — and if it does, the general argument does not apply until the
primary is attributed.

**Two related corrections from the same round, both worth their own habit:**

1. **"Hangs at X" is not "X is broken".** The boundary the stall reported was a special-stage
   exit; the exit had already completed correctly, three clean mode transitions, and the hang was
   in the *title card after it*. Locating a stall at a boundary says where execution stopped, not
   which side owns it.
2. **Grepping for your probe's tag hides the compile error that says the probe is not there.**
   A first probe failed to compile (a field placed between an `@Override` and its method); Maven
   reported it, but the run was filtered to `grep PROBE_TC`, so it printed nothing — indis-
   tinguishable from "this method is never called", and nearly recorded as a finding about the
   code. Read the build result before reading the probe's output.

## One hundred and fifteenth rule: a proxy that survives one question will silently answer a different one

Three separate inversions in one object family, all from the same cause: a metric keyed on
something that stands in for the quantity of interest.

- Persistence keyed on `(slot, spawn)` reported 713 of 714 objects living one sample — apparent
  wild over-creation. Re-keyed on slot: 36 objects held eight times too long. **Opposite owner.**
- Run-count keyed on slot answered "how long does each live" correctly and was then read as
  "how many are created" — which it cannot answer at all. The object turned out to have *both* a
  lifetime defect and a creation deficit (ROM 77 shots, engine 48), and the long lifetimes were
  masking the deficit.

**The useful form is not "pick a better key".** It is that a proxy validated for one question
carries no warranty for the next one, and the failure is silent — the number is well-formed either
way. Each time, the fix was an instrument measuring the quantity **directly**: constructor calls
counted against recorded `object_appeared` events, rather than runs inferred from occupancy.

Before reading an existing metric to answer a new question, state what the metric actually counts
and check that it is the thing the new question asks about.

## One hundred and sixteenth rule: a classification keyed on one game's vocabulary under-reports every other game

A sweep counted **2** render-flag deletion sites. Re-reading the same population site by site found
at least **6**. The miss was vocabulary: the search used S2's spelling — `render_flags` /
`on_screen` — and **S1 spells the same field `obRender`**. A site citing the *routine* rather than
the flag test (`Obj28_Main` / `Obj28_ChkDel` reaching the flag indirectly) matched neither
spelling and was invisible to both.

This is rule 102's failure in a different coat: a category defined by one game's names silently
becomes a category of *that game's sites*. Before reporting a cross-game population, name the
field in each game's own disassembly and search all of them — and include the indirect form, where
a site cites the routine that performs the test rather than the test itself.

**Corollary, from the same re-classification: a failing citation usually fails on the QUANTITY,
not the value.** Of 18 classifiable sites, zero were faithful — and none failed merely by having
the wrong number. They failed because the cited routine computes a *different quantity* or *no
quantity at all*:

- a margin of 480 citing a routine that computes 640;
- a margin of 128 citing a bottom-of-level camera test — different quantity **and** different axis;
- a margin of 64 citing `Obj_DeleteBehindScreen`, which is a `bmi` on a coarse difference — a
  **sign test containing no constant whatsoever**, and one that deletes only *behind* the camera
  where the engine also deletes ahead of it.

That last shape passes any review that asks "does the cited routine exist, and is it reached?"
Both answers are yes. The number still cannot come from there.

**Addendum: an identical spelling with different semantics is worse than a different spelling.**
S1 spells S2's `render_flags` as `obRender` — a different name, which at least prompts a second
look. **S3K spells it `render_flags`, the same token as S2, and the bits underneath differ**: bit 7
is set by the render pass *before* the sprite-budget check (so it means "passed the geometry test",
not "was drawn"), the vertical wrap is a **variable** `Screen_Y_wrap_value` rather than S2's fixed
11-bit mask, there is no `explicit_height` branch at all because S3K carries a real
`height_pixels`, and the comparison is against the VBlank-latched camera copies. A search keyed on
S2's vocabulary *finds* these sites and reports them as the same thing. Nothing prompts the check.

Confirm the semantics per game even when the name matches — especially then.

## One hundred and seventeenth rule: a constant in a shared class is not a constant of every game

A hardcoded `VERTICAL_WRAP_BG_MASK = 0x3FF` in the engine's shared `Camera` class was reported as
a candidate invented constant: no `$3FF` write to any BG wrap variable exists in `sonic3k.asm`, and
the only `$3FF` in that family is a *foreground* value for an unrelated bonus stage.

It is genuine and ROM-owned — **Sonic 1's**: `andi.w #$3FF,(v_bgscreenposy).w ; wrap background
Y-position` (`docs/s1disasm/_inc/ScrollHoriz & ScrollVertical.asm:241`, and again at `:266`).
Correct value, accurate comment; only the *citation* was missing. It has exactly one caller, and
that caller is Sonic 1 Labyrinth Zone code.

**The constant lived in a shared class, so it was searched for in the wrong game's disassembly.**
Check the callers before deciding which ROM a shared-class constant belongs to — the enclosing
class says nothing about it. This is the empty-grep failure in a new place: absence in one game's
disassembly is a fact about that disassembly.

**And the retraction shrank a pending fix.** The same constant had been reported as a third
independent derivation of the S3K wrap period. It is not — it is S1's, reached only from S1 code,
and does not participate in the S3K period at all. The S3K period is modelled **twice**, not three
times. The ownership argument survived; the scope of the work did not.

## One hundred and eighteenth rule: a stale native temp directory reports as a catastrophic regression

A segments sweep in a reused worktree reported **`Tests run: 69, Failures: 3, Errors: 66`** — 66
of 69 classes poisoned by a stale `target/test-tmp/lwjgl_*`. That is indistinguishable from a
catastrophic regression until you read a stack trace, and it has now bitten three times in one
session, twice mid-sweep and once as a single `Errors: 1` on a *freshly created* worktree's first
run of a class touching the key-name resolver.

**Before any sweep: `rm -rf target/test-tmp` alongside `target/surefire-reports`.** Before quoting
any `Errors:` count: `grep` the log for `UnsatisfiedLinkError`. An errors count that includes
native-load failures is a fact about the environment, not about the code — and unlike a stale
report it appears in a run that genuinely executed, so the usual "did it actually run" check
passes.

## One hundred and nineteenth rule: "no arithmetic exists between them" is an argument, not a measurement

A round traced a 4px divergence through five steps — layout record, a verbatim copy, a save, a
restore, the observed value — found **no arithmetic anywhere in the chain**, and concluded the
value at the far end must therefore be wrong at the near end. Every individual reading was
correct. The conclusion was false: measuring the near end directly showed it matched the layout
record after all, and the write that produces the +4 lives in a window **covered by no recorded
rows**, which is precisely why reading the code could not find it.

**An exhaustive read of a chain bounds where a change can be *in that chain*. It says nothing
about surfaces the chain does not pass through** — and the one surface nobody enumerated was the
one with no rows to enumerate it from. The inference then sent the next round after a shared
object loader that had nothing to do with it.

**The fix is to measure both ends before reasoning about the middle.** Here the near end was
directly available: a per-frame `object_state` stream carried the object's live position under its
own ROM code pointer, and reading it killed three hypotheses at once — loader arithmetic, an
alternative placement source, and the object's own code moving itself.

**Corollary: an exclusion is only as good as the argument that produced it.** A coincidence
dismissed earlier on the grounds that "no radius arithmetic exists on the restore path" is no
longer excluded once a discrete write is known to exist somewhere in that window. The dismissal's
premise was the same false generalisation. Revisit what an argument ruled out when the argument
itself is corrected — without treating the revival as evidence.

## One hundred and twentieth rule: "does the engine model this ROM behaviour" is a per-branch question

`SpawnLevelMainSprites_SpawnPlayers` contains **two** `addi.w #4,y_pos` writes on **two branches**
— one on the Player_2 arm (Tails as sidekick, `sonic3k.asm:8367`) and one on the Player_mode == 2
arm (Tails as Player 1, `:8388`). The engine implemented the first, and its comment cites the
routine by name.

So a survey asking *"does the engine model this?"* answers **yes**, points at a correct
implementation with an accurate citation, and is wrong — the ROM's other branch is unmodelled. The
citation is not sloppy: it names the right routine and is correct for the arm it describes. It is
**silently incomplete**, which no review of the cited line can detect.

**Ask per branch.** When a ROM routine selects between arms, model coverage is a property of each
arm, not of the routine — and a comment naming the routine will read as covering all of them.

**The corollary that found this one:** before believing a candidate write, enumerate every routine
in the call chain and check each for writes to the field. Here that established there was no
second candidate rather than assuming it — and the same sweep is what proves an unmodelled arm
exists, because an arm nobody enumerated cannot be ruled out by reading the arm that was.

## One hundred and twenty-first rule: a probe log without a frame delimiter is ambiguous, and both readings fit the bytes

A round concluded the engine's touch pass ran *after* the object pass — a shared-path ordering
defect, the largest blast radius proposed that day — from a probe log reading
`MOVE / SNAP / SCAN / BOUNCE`. That sequence groups equally well as **`[MOVE][SNAP][SCAN]`**, the
object moving before its own scan, or as **`[MOVE] | [SNAP][SCAN]`** with a frame boundary in the
middle. Both groupings fit the bytes exactly. The conclusion was wrong: the engine's phase is
ROM-correct.

The tie was broken by reasoning backwards from the failing comparison — *the value diverges at row
411, so the bounce must be inside frame 411* — which **silently assumed engine frame == trace
row**. It is off by one, and the entire conclusion turned on that. A one-point clock conversion
(rule 101) hidden inside what looked like direct observation rather than inference.

**Print an explicit per-frame marker beside the driver's own row index**, so the clock is converted
by measurement rather than assumed. With that in place the same log is unambiguous.

**Corollary, from the same round: two value-identical sequences offset by one row cannot be told
apart by comparing values.** An earlier elimination — "the trajectory is in phase, so this is not a
creation-frame error" — rested on exactly that comparison, and was structurally incapable of
returning the answer it was asked for. When checking for a phase offset, anchor on a landmark that
is measured, never on one assumed to line up.


### 2026-08-21 -- a third way a probe fails silently: `-DargLine` never reaches the fork

The pom pins surefire's `<argLine>` to `${surefire.argLine}`, so a CLI
`-DargLine=-Dmyprobe=1` is discarded and the property never reaches the forked
JVM. A `System.getProperty`-gated probe then prints nothing, which is
indistinguishable from a branch that never ran -- the same failure mode as a
probe that did not compile behind a filtered grep, and as an `echo COMPILE_OK`
printed unconditionally above the real `ERROR` lines. All three happened on one
day. Gate diagnostic probes on an environment variable instead: forks inherit the
environment, and `ORDINAL_PROBE=1 mvn ...` works under every profile. Whichever
gate is used, count the probe's own output lines before reading the result.

## One hundred and twenty-third rule: run a class survey so the known member must appear in its output

A survey intersecting 316 ROM install sites against 50 candidate routines returned **EMPTY** — no
members at all, *including the one already fixed and known to be a member*. The cause was line
endings: the disassembly has CRLF, so `comm` was matching `Obj_Flybot767\r` against
`Obj_Flybot767`. **An empty survey result and a survey that cannot match anything look identical.**

What made it distinguishable was that a member was already known, so "no members" was *checkable*
rather than merely plausible.

**And a MIXED-ending file is worse than a uniformly wrong one.** `docs/skdisasm/sonic3k.asm` is
202,729 CRLF lines to 993 bare LF, so a `$`-anchored matcher over it returns roughly **0.5% of the
file — non-empty**. An empty result at least announces itself; a partial one reads exactly like a
complete one and passes every sanity check you would think to apply. The S1 and S2 trees are LF,
skdisasm and `s3.asm` are not: check `file` on the tree you are sweeping before trusting any count
or any zero from it. Construct every defect-class survey so the known member **must**
appear in its output; if it does not, the survey is broken, not the class empty. This is rule 110's
positive control applied to a survey rather than a probe.

**Two further hazards from the same round:**

1. **A truncated result reads as a complete one.** "The six other callers" came from a
   `grep | head` whose ten-line cap was read as the whole result. There were **fifty**. The six
   named were simply the first six in file order, and a survey was scoped on that number. The count
   was a fact about the pager.
2. **Counts derived from identifier names need one read each before they are trusted.** A class was
   filed as gating on the published render flag because it names a *local variable* after it —
   which it computes live. Three further cross-references were substring matches, a comment naming
   a test, and a nested record.

**And the useful distinction the survey produced: latent-safe is not correct.** Of 33 engine sites
modelling the wait, 8 gate on the published flag and 25 on a live bounds test. Those 25 are safe
only because nothing spawns them dynamically — not because their gate models the ROM's phase. Any
one of them acquires the defect the day it gains a dynamic spawn route. That is worth more than a
list of sites to change, because it names the condition rather than the instances.

## One hundred and twenty-fourth rule: a stall in the reference series makes two different offsets match identically

A lead was reported as **two rows** and is **one**. The measurement sampled the offset at rows
adjacent to a point where the ROM series *stalls* — a lag row, where the gameplay frame counter does
not advance and the recorded position repeats. Where the reference repeats a value,
`eng(N) == rom(N+1)` and `eng(N) == rom(N+2)` are **both true**, and the larger was reported.

**Two sequences compared by value cannot be disambiguated across a stall.** This is the sibling of
"two value-identical sequences offset by one row cannot be told apart" (rule 121's corollary): there
the values were identical along the whole series, here they repeat at a single point, and both
defeat value comparison in the same way.

**Classify an offset only where the reference series is locally strictly monotonic, print the
ambiguity rather than choosing from it, and walk the whole covered stretch rather than sampling
it.** Sampling is how the wrong number was produced; the full walk is what corrected it — the lead
was one row at *every* covered row from first coverage to the divergence.

**And the acquisition point may sit in a window nothing recorded.** Here `object_state` emits no
rows for that slot across the forty rows where the offset must be gained, because the stream only
records objects near the player. Rule 119 in its exact form: the walk bounds only what the rows
cover, and a coverage gap by construction is not a measurement anyone skipped. Name it as unnamed
rather than reaching for a candidate to fill the slot.


## One hundred and twenty-fifth rule: a probe that never compiled looks exactly like a predicate that never fires

A probe was added to an object's collect predicate to establish whether it was evaluated at
the divergence frame. It referenced a method that does not exist on that class, so the build
failed at `compile` and surefire never ran. The result: **no probe file, and no `Tests run`
line anywhere in the log.**

That is bit-for-bit the signature of the thing being investigated — a predicate the engine
never reaches. The natural next sentence writes itself ("the check never fires, so the
object is never eligible") and it would have been fiction.

This is the **fifth** distinct way a probe goes silent while looking like a measurement, and
the previous four all had the probe *running*: `-DargLine` not reaching the fork (rule 122),
a tag filter excluding the class (rule 111), MSE swallowing `System.err`, and a stale temp
directory. This one never got as far as running, which makes it the cheapest to detect and
the easiest to miss, because a failed Maven build still prints hundreds of lines and exits
with the same code a failing test does.

**The check is one question, asked before reading anything into the silence: *why* is the
file absent?** `grep` the build log for `COMPILATION ERROR` and for the `Tests run:` line of
the class you targeted. An absent probe file is a fact about the file, never yet a fact
about the code — the same shape as rule 123's empty survey and rule 28's absence that is
only in your view.

Corollary for briefs: **"the probe printed nothing" is not a finding and must never be
reported as one** without the positive control that rule 110 already requires. If the run
that produced the silence cannot also show you something it *did* print, it has told you
nothing at all.

## One hundred and twenty-sixth rule: a uniquely-fitting event is not evidence that it is *your* event

A probe over one segment logged 1366 evaluations of a collect predicate and found **exactly
one** row where the ROM's bounds accept and the engine's reject — and it sat on the exact
boundary value, `dy` equal to the bound. One candidate, in the right mechanism, with the
right shape, at the arithmetic edge that the ROM listing had just been shown to include.

It was a different frame from the divergence, and landing the correction left the first
error frame exactly where it was.

**Rarity felt like identity, and they are not the same thing.** "Only one event in the whole
segment could produce this symptom" establishes that the event is rare; it says nothing about
*when* it happened. The inference silently assumed the probe's one hit and the report's one
frame were the same moment, which is precisely the assumption rules 6 and the "name the
clock" section exist to forbid — and it is easier to make when the candidate is unique,
because uniqueness supplies the feeling of proof that a clock would otherwise have to earn.

**Put the clock in the probe, and anchor it before believing it.** Adding the object-visible
frame counter and checking it against the fixture's `vblank_counter` column — matching
`player_y` value-for-value across the five rows before the divergence — turned the story
over in one run: the engine's player geometry matched the ROM *exactly* at the deciding
frame, and the thing that differed was a second object's position, a completely different
owner.

The wider form, worth carrying beyond probes: **a hypothesis that explains the symptom and
has no competitors is still only a hypothesis about mechanism, never about incidence.**
Mechanism and incidence are separate measurements, and the second one is usually the cheap
one.

## One hundred and twenty-seventh rule: a probe can fire in the right class and still be in the wrong harness

Two measurements from one round disagreed: an observation reported a load generation of 24 against
a coordinator holding 23, while instrumentation on the session launcher showed its generation
**never changing** across thirty entries. Both cannot hold if the coordinator's observations come
from that launcher.

They don't. **The run-chain test builds its own coordinator and driver loop beside the launcher** —
the same property that lets a visual-only defect stay green there. The probe compiled, ran, and
printed; it was simply in a path that test does not use.

**This is a sixth silent-probe mode and the least visible of them.** The other five end in no
output (never compiled, never reached the fork, filtered away, stale natives, a branch that never
runs). This one produces *plausible output from the wrong place*, so it fails no sanity check and
positively supports a wrong conclusion.

**Before trusting a probe's numbers, establish that the harness under test executes the path the
probe sits in.** A probe at a site the failure message itself names is self-validating — its
reachability is guaranteed by the error you are chasing. Prefer those.

**And a controlled zero needs a witness in the same run.** The same round proved a death path was
never taken by showing its probe wrote nothing *while another probe fired in the same run and the
same directory* — which makes the zero a measurement rather than a sixth mode.

## One hundred and twenty-eighth rule: `stand_on_obj` is a slot index, not an object id

The S1 SST byte a lane read as an object id is a **slot index**. Taken as an id, `0x2A` names the
SBZ Small Door — a real object, in a real file, and completely wrong. Resolved through the recorded
`slot_dump`, slot 42 holds object `0x84`: the Final Zone cylinder.

**The trap is that the wrong answer is plausible.** Both readings produce a valid object id, so
nothing in the number announces the error, and a citation to the wrong object's file would have
survived review — the shape of rule 113, arriving through a field's units rather than through a
grep. Resolve any slot-valued field through `slot_dump` before naming an object from it.

**Two further habits from the same round, both of which changed the answer:**

1. **The comparator's first failing frame is not the origin.** The reported first error was an
   animation mismatch ~800 rows before the divergence that mattered; `y` matched exactly until row
   1161. Walking every row's comparison — matches as well as mismatches — is what separated the
   lowest failing frame from the cause. A sample around the observed failure would have found
   neither.
2. **State what an unmeasured item actually is.** Three further deaths were reported as
   "coordinates and nothing else, segments unestablished, shared cause unestablished" rather than
   as a guess either way — so the next lane neither re-opens it blind nor inherits a conclusion
   nobody reached.

## One hundred and twenty-ninth rule: the origin may be in a field nobody walked

A round walked `y` across a whole segment, found its genuine first divergence at row 1161, and
reported it as the origin. It was not. The same probe carried `x`, and the engine was already ~20px
right of the ROM by row 1153, with the **first `x` divergence 320 rows earlier at row 841** — a
horizontal collision response taken mid-jump, with `y` and `y_speed` **identical on both sides**.

This is "the first reported error is not the cause" one level down: the walk was correct, the
series was correct, and **the origin was in a different field nobody had walked**. A `y`-only walk
cannot see a divergence that touches no vertical motion, however carefully it is done.

**Carry the neighbouring fields in the same probe.** The cost is one column; the alternative is a
second run you will not know you need.

**And print the VELOCITY beside the POSITION.** From position alone, the row-1161 divergence reads
as "the engine moved twice as far", which suggests doubled gravity immediately — the obvious shape,
teed up. The velocity shows the engine *gaining* upward speed, which gravity cannot do, and that
single line refuted the candidate before any work went into it. Position says what happened;
velocity says what did it.

**Corollary — read the routine before assigning a role.** The same round had the launch backwards:
the impulse it attributed to the boss platform is S1's own jump velocity, so the event is the player
jumping off, not the object launching him. Reading the routine corrected an actor, not just a value.

## One hundred and thirtieth rule: read the candidate mechanism's constant before probing for it

A round had the right actor and the wrong mechanism. Probing the mechanism first would have come
back **empty** — a true statement about that code path, and a false conclusion about the defect
("the engine models no boss contact here"). Reading the constant first is what separated them.

The suspected path computes its rebound as `neg`/`asr` on the incoming velocity, giving roughly a
seventh of the observed magnitude on X **and a downward write on Y**. The recorded `y_speed` runs
continuous gravity — differing by exactly the gravity constant per row — straight through the
event, **so the vertical write provably never happened**.

**That one column eliminated a whole class rather than one candidate.** Every hurt, bounce and
boss-touch path in that game writes vertical velocity; vertical velocity is untouched; so all of
them die together, and whatever acted writes **x only**. The real owner turned out to be a
different routine on the same object — same half-width, x-only write, gated on the exact player
state the row records.

**Two habits worth keeping:**

1. **Check the untouched fields, not only the diverging one.** A field that provably did *not*
   change is often a stronger discriminator than the one that did, because it rules out every
   mechanism that would have written it.
2. **Absent and wrong are different defects, and a probe must distinguish them.** Here the class
   has *no* x-velocity write on side contact at all, while carrying a separate mechanism gated on
   the same player state — so it detects the case and does something else with it. "No rebound
   observed" would have reported neither.

## One hundred and thirty-first rule: a conversion onto manager-supplied state silently breaks every direct-drive unit test

Moving a decision onto per-frame state the manager supplies — a frame-start position snapshot, a
published render flag — breaks every unit test that calls `update()` directly, because those tests
never receive that state. The helper's `preUpdateValid &&` prefix then returns false, the object
takes the negative branch unconditionally whatever its position, and **the failure reads exactly
like a test encoding the old behaviour**.

Two of three reds beside an already-landed conversion were this and nothing else: adding
`snapshotPreUpdatePosition()` made both pass **with their original assertions unchanged**. Nothing
needed inverting. **Complete the harness before inverting anything.**

**And run the revert-first proof, because a repaired test can pass against both trees.** Reverting
the class to its pre-conversion form and re-running gave three different answers:

| test | vs revert | vs current | verdict |
|---|---|---|---|
| deletes using the falling parent's y | **red** | green | discriminates |
| uses the approximate render height | green | green | **proves nothing about the conversion** |
| keeps a vertical-only offscreen parent | green | **red** | tests removed behaviour |

The middle row is the one to look for. A repaired test that passes against *both* trees is not
evidence for the fix, and claiming the proof for it is exactly what the proof exists to prevent —
keep it if its assertion is true, but record that it is not evidence.

**A test of behaviour that no longer exists cannot be repaired, and should stay red with its reason
in the test.** An attempt to rewrite the third to the new contract failed on its own first tick —
the tell that the expectation was being invented rather than derived. Deleting it would discard the
only record of what the removed grace period was for.

**Related, and the second time this prefix produced a misleading signal in one session:** the same
`preUpdateValid` guard silently reads "off screen" in one caller and "not drawn" in another. Both
times the tell was the same helper.

## One hundred and thirty-second rule: a gate probe must cover the early returns you were not thinking about

A round instrumented four rejection gates in a contact routine and produced a clean table — 25
rejected on one gate, 18 on an invented one, 1 on a third, 0 applications in the whole run. It
still could not answer the question, because **the row that starts the whole chain logged nothing
at all**: the method's *first* early return, a state check before any gate, was uninstrumented.

So the origin row is either "no contact detected" or "the object was in the wrong state" — two
different defects with two different fixes, and the probe distinguishes neither. **The gates
instrumented were the ones already under suspicion; the one that mattered was the one nobody was
thinking about.**

**Enumerate every exit from the routine before reading the table.** A probe that covers the
branches you suspect produces a plausible, complete-looking distribution while the real answer sits
in a path with no line in it — the same family as a probe in the wrong harness (rule 127), reached
by a different route.

**And fixing the named blocker first would have been fitting.** The invented guard blocks the
bounce in a window ~300 rows *downstream* of the origin, and that window is a *consequence* of the
origin. Removing it might make the consequence behave while leaving the cause wrong — a green-ish
result attributable to nothing. Fix the origin, then re-measure the consequence.

## One hundred and thirty-third rule: a field can be the origin even when a walk of another field shows it not propagating

A round dismissed the segment's lowest failing frame — an animation-id mismatch ~800 rows before
the divergence under investigation — as "a separate, non-propagating divergence", because the
position series matched exactly for those 800 rows. **True of position, and false as a conclusion.**
That same field is what fails a gate at the origin row.

The coupling ran through a predicate that reads **neither** the walked field **nor** the status bit
everyone was watching: the ROM gates on the *animation id*, and the engine's `rolling` and `air`
bits matched the ROM throughout. So a divergence that provably did not propagate through position
still decided the outcome, by being read directly.

**Propagation is a property of a path, not of a field.** Before dismissing an early mismatch as
non-propagating, ask what reads it — not whether the series you happen to be walking moves.

**Corollary: the same field diverging in BOTH directions is a selection defect, not a blip.**
Engine `0x02` where the ROM has `0x00` at one row, engine `0x00` where the ROM has `0x02` at
another, is a state machine choosing wrongly in both directions — which is a different and larger
thing than a single stuck value.

**And a predicate can be faithfully implemented and still reject wrongly, because its INPUT is
wrong.** That is a third diagnosis distinct from an invented guard and from a missing mechanism,
and it moves the owner upstream — here, out of a boss object entirely and into shared player code,
inverting the blast radius the round had been scoped for.

## One hundred and thirty-fourth rule: a defect's own consequence contaminates its own count

Walking a field across a whole chain gave **253 mismatches** in one segment. **154 of them are the
engine playing its death animation after a restart** — the restart being the end of the very chain
under investigation. Counting them as defect rows would have inflated the population **fourfold**
and made a 67-row selector disagreement look like a systemic failure.

**Split a population by cause before sizing work from it**, and say which rows are the defect and
which are its downstream. Any future count in that segment needs the same split — the contamination
does not go away until the defect does.

**Two further things the same scoping produced:**

1. **A narrow population changes the landing calculus in both directions.** 67 rows across one
   segment, with nineteen other segments clean over ~154,000 rows, means the fix has *no
   independent adjudication* — whoever lands it lands against one segment's evidence. That is worth
   knowing before commissioning, not after. It also means the rows can be enumerated individually,
   so the fix can be adjudicated row by row rather than by a count.
2. **Read the ROM's selector before believing the obvious mechanism — it may be that the ROM does
   not select at all.** Here a jump taken while already rolling branches away and *never writes the
   animation id*: the value is **carried**, not re-derived. So an engine that re-derives from
   current state disagrees in **both directions** depending on the prior value — which is exactly
   the shape of a split population, and the opposite of the obvious "rolling should set the roll
   animation".

## One hundred and thirty-fifth rule: brief HOW to measure, not WHAT to expect

Over one investigation a lead offered four two-way forks — wrong-state or no-contact, gravity twice
or impulse twice or a carry release, absent or wrong, one branch or two. **Every one was answered
"neither."** The forks were well-posed against the evidence available when each was written; they
failed because the investigation kept moving *owner* between rounds — boss touch, then boss state,
then a gate, then player animation. **A fork drawn in one subsystem cannot anticipate a move to the
next.**

What changed answers, every time, was the constraint that said **how to measure**:

- *print the velocity beside the position* — refuted doubled gravity from a series that invited it;
- *read the candidate mechanism's constant first* — killed the mechanism and kept the actor, where a
  probe would have returned a clean-looking empty;
- *log every exit, not the gates you suspect* — found that the deciding row had no line at all;
- *check the neighbourhood before settling on the obvious object* — produced a better fit than the
  obvious one;
- *walk, do not sample* — separated a first failing frame from an origin 800 rows earlier.

**Offering branches is cheap and rarely wrong; it is also rarely useful.** Spend the brief on the
instrument and the discipline, and let the measurement name the branch. A lane that is told what to
expect will find it; a lane that is told how to look will find what is there.

