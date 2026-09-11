# Error-count group coverage: extent measured from the totals

Point-in-time audit, 2026-08-21, against `origin/develop` `296423f93`. **Read-only. The
comparator was not opened and no engine code was changed.** Approached deliberately from the
report totals rather than from the comparison internals, so that agreement with the S1 lane's
independent finding would be genuine rather than a shared assumption.

Instrument: one full `-Ptrace-replay` run (800 tests), then every JSON report it produced —
**128 reports** — analysed for arithmetic consistency.

> **RESOLVED, same day — there is no counting gap.** The S1 lane probed the absorber directly
> rather than following the path by inference: all ~1200 divergences reach it and increment the
> count. Nothing is dropped anywhere in the comparator. What misled that lane was a *summary*
> field — "first non-camera mismatch" is filtered to the physics group **by design**, so an
> animation error can never populate it, and the recent-mismatch list holds only the last few
> entries. Absence there was read as a fact about the engine.
>
> **This audit therefore stands as a cross-game confirmation rather than an open question.**
> The 56/56 standalone result below independently shows S2 and S3K totals are internally
> consistent, which extends the S1 lane's single-game conclusion. Counts are correct; no
> segment called green is in doubt; no round aimed by those numbers was misaimed.
>
> The two structural observations below remain true and useful: chain reports publish no group
> breakdown, and the "physics" summary field is filtered by design — which is exactly the pair
> that made a correct instrument look broken.

## Headline as first measured: not confirmed and not refuted on S2/S3K, for a structural reason

**The totals cannot decide this question for chains, because chain reports do not expose
groups at all.** That is not a limitation of how hard I looked; it is a property of the
artifact. Stating it plainly because "no gap found" would be the wrong conclusion to draw.

## What the totals do show

### 1. Standalone reports carry two groups and their arithmetic is exact — 56/56

Every standalone report carries `verification_groups` with **`physics`** and **`animation`**,
and the invariant

```
physics.error_count + animation.error_count == error_count
```

holds in **56 of 56** reports, with **zero** exceptions.

Only one standalone report has any errors at all, and it is decisive on its own:

| report | total | physics | animation |
|---|---|---|---|
| `s3k_aiz1_report.json` | **113** | 58 | **55** |

`58 + 55 = 113`. **Animation-group errors are counted in standalone totals** — nearly half of
that report's errors are animation, and its first error is `player_animation_id`. Whatever the
S1 lane has found, it does not apply to the standalone path on this evidence.

### 2. Chain-style reports carry no group breakdown whatsoever — 67/67

| games | chain-style reports |
|---|---|
| S1 | 36 |
| S2 | 25 |
| S3K | 6 |
| **total** | **67** |

Every one carries a flat `errorCount` and `warningCount` and **no `verification_groups`
key**. So the group structure the comparator demonstrably maintains — and which the standalone
reports publish — is **invisible in every chain report in the project, across all three
games**.

That is the extent statement worth carrying: *if* group errors are dropped from chain totals,
no chain report can show it, and 67 reports would be affected.

### 3. The signature that would have proven it does not fire — but the evidence is weak

Two checks across all 67 chain reports:

- reports with `errorCount == 0` while listing mismatches: **0**
- reports whose listed mismatches are entirely animation-group fields: **0**

**This is weak evidence and must not be read as a refutation.** `recentMismatches` is a
five-entry ring buffer holding only the *last* mismatches, so a segment could count nothing
while having thousands of dropped divergences and still show a ring buffer consistent with its
total. The check was worth running; a hit would have been proof, a miss proves nothing.

### 4. A naming asymmetry, consistent with the hypothesis but not evidence for it

The chain's failing axis reads `N physics comparator errors`; the standalone's reads
`N errors, M warnings` with the group breakdown behind it. A chain quantity *named* physics is
consistent with a physics-only count — and equally consistent with a misnamed all-groups
count. Recorded so nobody mistakes the name for a measurement.

## Why the matched-pair test could not be run

The clean totals-only test would compare one trace's chain-segment total against the same
trace's standalone total. It is unavailable: **every standalone report except `s3k_aiz1` is at
zero**, so no matched pair has any errors to disagree about.

## What would decide it, without touching the comparator

Chain reports would need to publish the same `verification_groups` breakdown the standalone
reports already publish. That is a **reporting** change, not a counting change: it cannot move
any number, and it would make this question answerable from the totals forever after. If the
S1 lane's mechanism turns out to be real, this is also the artifact that would show its extent
per segment.

## Bearing on the concern that rounds have been aimed by filtered numbers

For the **standalone** suites the concern is measurably unfounded: 56/56 exact, animation
included. For the **chain** suites it cannot be settled from the reports, and every chain
number this project has steered by — including this lane's own segment-8 counts all session —
sits in that unresolved category until the S1 lane's mechanism is known.
