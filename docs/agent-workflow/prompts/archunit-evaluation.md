# ArchUnit Evaluation Prompt

Prompt for `feature-dev:code-architect` (or `general-purpose`) to evaluate
adopting ArchUnit in place of, or alongside, the substring-based architectural
guard tests in `src/test/java/com/openggf/tests/TestArchitecturalReviewGuard.java`.

The agent should produce an evaluation report only — no production code changes.

---

You are evaluating whether ArchUnit should replace/augment the substring-based
architectural guard tests in the OpenGGF Java game engine codebase.

# Repo at a glance

- Java 21 / Maven build (see `pom.xml` at repo root)
- JUnit 5 / Jupiter is the only allowed test framework (CLAUDE.md enforces this)
- Surefire 3.2.5, ~4870 tests, runs in 4 parallel forks
- Architecture is documented in `CLAUDE.md` (read this first — the relevant
  sections are "Two-Tier Service Architecture", "Session Ownership",
  "Multi-Game Support Architecture", and "Runtime-Shared Framework Stack")

# Current guard mechanism

`src/test/java/com/openggf/tests/TestArchitecturalReviewGuard.java` enforces
8 architectural invariants today using `Files.readString` + `source.contains(...)`.
Read this file. Each test asserts the absence (or presence) of a substring in a
specific source file. Examples:

- `ObjectServices.java` must not contain `GameServices.`
- `LevelManager.java` and `LevelRenderer.java` must not contain `Sonic3kZoneIds`
- `CrossGameDataSelectPresentations.java` must not contain `S3kDataSelectManager`
  or any `import com.openggf.game.sonic3k`
- `pom.xml` must not contain JUnit 4 / vintage engine groupId

Known weaknesses of the current approach: false positives on comments and string
literals; defeated by `Class.forName(...)`; file-path-coupled; requires manual
broadening to each new peer file.

# What I want from you

A focused evaluation report. Do NOT write any production code. Read files,
survey package structure, and answer the questions below.

## 1. Feasibility check
- Look up the current ArchUnit coordinate (`com.tngtech.archunit:archunit-junit5`)
  and verify it supports Java 21 and JUnit Jupiter 5.x as used in this pom.
- Identify the transitive dependency footprint (ASM + others). Sizes in MB.
- Note any classpath conflict risk vs. existing test deps (Mockito agent, etc.).

## 2. Rule translation
For each of the 8 existing substring rules in `TestArchitecturalReviewGuard`,
write the equivalent ArchUnit rule (as a code snippet using ArchUnit's
DSL). Identify rules where ArchUnit is strictly stronger (catches things the
substring check misses) versus rules where the translation is roughly
equivalent.

## 3. Gap analysis — what could ArchUnit enforce that we currently can't?
Survey `CLAUDE.md` and the codebase package structure for invariants that are
*documented* but *not enforced*. Strong candidates (validate against the
codebase before proposing):

- "Object code must not call `GameServices.*`" — should be enforceable as a
  package rule on `com.openggf.level.objects..` and game-specific object
  packages.
- "Shared `com.openggf.level..` and `com.openggf.game..` (excluding game-id
  subpackages) must not depend on `com.openggf.game.sonic1..`,
  `com.openggf.game.sonic2..`, or `com.openggf.game.sonic3k..`."
- "Per-game packages must not depend on each other" (e.g. sonic2 ↛ sonic3k).
- "Tests must not reference `org.junit.*` (JUnit 4)" — beyond just pom check.
- "Non-test code must not call `getInstance()` style singletons" (verify
  whether this pattern still exists post runtime-ownership migration).

For each proposed rule, give:
- The ArchUnit DSL
- A confidence level that it's actually a desired invariant
- A guess at how many current violations exist (sample with grep)

## 4. Build-time cost
ArchUnit loads all analyzed classes via ASM on first use. Estimate the impact
on the surefire run given ~4870 tests / 4 parallel forks. Quote any per-fork
caching options ArchUnit provides.

## 5. PR shape recommendation
Should this be:
- **(a)** one PR that adopts ArchUnit AND translates all 8 rules AND adds new ones?
- **(b)** two PRs — adoption + translation first, expansion later?
- **(c)** ArchUnit only as ADDITION, keep the substring guards for the rules they
  cover well?

Justify with the tradeoffs you actually found in (1)-(4), not generic advice.

# Output format

```
## Feasibility
<verdict + dep footprint + Java 21 / JUnit 5 compatibility>

## Rule translations (current 8)
<one per row: rule | ArchUnit DSL | stronger/equivalent/weaker>

## Proposed new rules
<one per row: rule | ArchUnit DSL | confidence | current violation count>

## Build-time impact
<numbers, not adjectives>

## Recommended PR shape
<a/b/c with reasoning>

## Risks / open questions
<things I should know before committing>
```

Keep under 1000 words total. Quote `file:line` for any specific finding.
