# Architecture Priorities And Roadmap Design

**Date:** 2026-04-12

## Goal

Define a canonical architectural priority order for the engine and a pragmatic refactor roadmap for the `develop` branch that improves parity confidence first, architectural elegance second, and developer velocity third.

## Context

The current codebase already has meaningful architectural improvements in place:

- explicit runtime ownership through `GameRuntime` and `RuntimeManager`
- a two-tier access model using `GameServices` and `ObjectServices`
- strong architectural guard tests that prevent regression toward unrestricted singleton use
- partial subsystem extraction from older large managers

The same audit also showed the main remaining liabilities:

- oversized central classes such as `LevelManager`, `ObjectManager`, `Engine`, `GraphicsManager`, and `Sonic2SpecialStageManager`
- heavy reliance on global static access in non-object code
- a very wide `GameModule` interface acting as a capability kitchen sink
- inconsistent lifecycle semantics across providers and per-game module implementations
- local elegance gains that are sometimes offset by subtle bridging mechanisms required to coexist with legacy structure

This design treats those liabilities as real technical debt, but it does not assume the right response is a pure cleanup sprint. The engine is still parity-driven research and preservation software, so refactoring must be subordinate to confidence in original-game accuracy.

## Canonical Priority Order

The architectural priority order for the project should be:

1. **Testability and parity confidence**
2. **Architectural elegance and extension direction**
3. **Developer velocity**

This order is intentional.

The engine exists to reproduce original-game behavior with high confidence. If a refactor makes the architecture prettier but weakens confidence in physics, timing, rendering, object ordering, or camera behavior, that refactor is wrong by default. Elegance matters because the engine needs to support future extension, including new games, variants, editor work, and possible modding-oriented seams. Velocity matters because contributors need to be able to navigate and extend the engine efficiently, but velocity should come from clearer architecture, not from bypassing it.

## Canonical Documentation Shape

The canonical home for these priorities should be:

- `docs/architecture/principles.md`

That document should become the project-level reference for architectural decision-making. `AGENTS.md` should contain a concise pointer to it rather than duplicating the full policy. The pointer should summarize the priority order and make clear that parity confidence is the first constraint on architectural work.

This keeps the decision model centralized while still making it visible to future contributors and agents.

## Architectural Principles To Formalize

The principles document should capture at least the following rules.

### 1. Confidence Before Cleanup

Architectural work must either:

- improve parity confidence directly, or
- reduce future parity risk without weakening current verification

Any refactor that changes core behavior must carry a verification plan appropriate to the risk of the subsystem being touched.

### 2. Explicit Ownership Over Ambient State

Prefer explicit ownership through runtime, session, or injected service objects over ambient global state. `GameServices` is an acceptable transitional facade, but new code should not deepen dependence on static service lookup when an explicit dependency can be passed instead.

### 3. Narrow Interfaces Over Capability Buckets

Prefer small, coherent capability interfaces to broad catch-all interfaces. Wide abstractions obscure lifecycle, increase boilerplate, and make extension more fragile.

### 4. Guard Rails Are Part Of The Architecture

Architectural rules should be enforced with tests where practical. The project already does this well for singleton closure, runtime access, and object-service migration; future architectural rules should follow the same pattern.

### 5. Refactor Toward Future Extension Without Designing The Future In Full

The engine should move toward seams that can support additional games, variants, and modding, but roadmap work should remain grounded in current needs. This means preferring generalizable boundaries over special-case hacks, while resisting speculative abstraction that has no immediate architectural payoff.

### 6. Local Simplicity Beats Clever Bridging

When two approaches are parity-safe, prefer the one that makes local ownership, lifecycle, and call flow easier to understand. Clever compatibility bridges are acceptable only when they are clearly transitional or materially reduce migration risk.

## Recommended Roadmap Strategy

The recommended strategy is a **two-speed roadmap**:

- a **Quick Wins lane** that lands continuously alongside gameplay feature work
- a **Dedicated Architecture lane** that tackles structural refactors requiring concentrated attention and temporary slowdown

This is the best fit for the agreed priority order.

Pure incrementalism leaves the largest structural liabilities in place for too long. A pure architecture-first freeze would improve elegance faster, but it risks starving active parity work and making the codebase harder to evaluate against the original games during the refactor window. The two-speed approach preserves momentum while still giving the project permission to do concentrated structural work when the payoff justifies it.

## Roadmap Lanes

## Lane A: Quick Wins

This lane should run continuously and should favor changes with low blast radius and clear immediate value.

Suitable work includes:

- adding or tightening architectural guard tests
- extracting small focused collaborators from large files without broad interface churn
- replacing new `GameServices` usage with explicit dependencies in touched code
- standardizing naming, ownership, and provider lifecycle conventions where they are currently inconsistent
- turning undocumented architectural conventions into explicit helper APIs, records, or docs

This lane should be judged by one question:

**Does this reduce parity risk or local complexity without creating broad refactor coupling?**

If yes, it can land alongside feature work.

## Lane B: Dedicated Architecture Track

This lane is for structural work that is too large or cross-cutting to be treated as opportunistic cleanup.

Suitable work includes:

- decomposing `GameModule` into smaller capability interfaces
- continuing the decomposition of `LevelManager` into focused top-level collaborators
- continuing the decomposition of `ObjectManager` into focused top-level collaborators rather than accumulating more inner subsystems
- shrinking the set of non-object subsystems that depend directly on `GameServices`
- tightening composition-root and bootstrap boundaries so legacy singleton bridges are easier to understand and eventually reduce

This lane should run in bounded waves, not indefinitely. Each wave should target one architectural seam, define a verification envelope, and avoid mixing unrelated cleanup into the same effort.

## Roadmap Classification Tags

Each roadmap item should be labeled with one primary intent:

- `confidence-first`
- `elegance-first`
- `velocity-first`

This forces explicit prioritization and helps prevent "helpful cleanup" from drifting into broad, low-confidence churn. A task may benefit all three categories, but its primary justification should still be named.

## Phase Plan

## Phase 0: Principles And Policy

**Primary tag:** `confidence-first`

Create the documentation and decision model before making major structural changes.

Deliverables:

- `docs/architecture/principles.md`
- a short pointer in `AGENTS.md`
- a lightweight refactor acceptance checklist for parity-sensitive subsystems

Why this comes first:

- it gives contributors and agents a shared decision order
- it prevents architecture work from being justified by taste alone
- it creates a durable standard for future roadmap decisions

## Phase 1: Confidence Infrastructure

**Primary tag:** `confidence-first`

Strengthen the verification net before large refactors.

Targets:

- expand guard-test coverage around runtime ownership, service access, and forbidden architectural regressions
- define verification checklists for parity-sensitive subsystems such as physics, object timing/order, camera behavior, and render order
- standardize what evidence is required to claim a refactor is safe in those subsystems

Why this is next:

- it lowers the risk of architectural work changing game behavior silently
- it supports the project mission directly
- it creates the conditions needed for more ambitious cleanup

## Phase 2: Cheap Structural Wins

**Primary tag:** `elegance-first`

Take low-risk refactors where seams already exist.

Best candidates:

- further extraction from `LevelManager` where collaborators already exist conceptually
- further extraction from `ObjectManager` where inner classes can become top-level units with clearer ownership
- standardizing per-module provider caching and lifecycle conventions across `Sonic1GameModule`, `Sonic2GameModule`, and `Sonic3kGameModule`
- reducing local `GameServices` usage in subsystem managers that can receive explicit collaborators

Why this phase matters:

- it improves readability and extension direction without demanding a full architecture freeze
- it creates better local units for testing and future work
- it pays down complexity in the classes most likely to keep growing

## Phase 3: Core Architecture Track

**Primary tag:** `elegance-first`

Run dedicated architecture waves on the largest structural liabilities.

Priority order:

1. `GameModule` decomposition into smaller capability interfaces
2. `LevelManager` decomposition beyond current partial extraction
3. `ObjectManager` decomposition beyond inner-subsystem accumulation
4. composition-root cleanup around engine services, runtime services, bootstrap, and session ownership

Why this phase is dedicated:

- these changes are too cross-cutting for opportunistic cleanup
- they affect extension seams across the engine
- they need concentrated review and stronger verification discipline

Expected output:

- better boundaries for adding new games and engine variants
- clearer lifecycle and ownership semantics
- smaller, more comprehensible units with lower hidden coupling

## Phase 4: Contributor Ergonomics

**Primary tag:** `velocity-first`

Use the cleaner architecture to improve contributor speed and direction.

Targets:

- clearer documentation for common engine extension tasks
- architectural maps for runtime ownership and main gameplay flows
- path-specific contributor guidance for objects, zone features, audio, scroll handlers, and game modules
- template-like guidance for adding new extension points while preserving architectural rules

Why this comes after the deeper cleanup:

- documentation and workflow guidance become more durable once the architecture is less ambiguous
- velocity gains are more meaningful when built on clearer real seams rather than workarounds

## Quick Wins Versus Long-Term Goals

The roadmap should explicitly distinguish between:

- **Quick wins:** changes that can merge safely beside gameplay work
- **Long-term goals:** structural goals that justify a bounded architecture wave

This distinction is important because the project should remain free to choose a dedicated architecture track when it is worth the temporary slowdown. The roadmap should not imply that every refactor must be smuggled into feature work. Instead, it should help maintainers choose intentionally between:

- opportunistic cleanup with immediate payoff
- concentrated structural work with medium-term payoff

## Architectural Acceptance Criteria

A refactor should be considered successful only if it satisfies all applicable criteria:

- parity-sensitive behavior remains verified at the appropriate confidence level
- ownership becomes clearer, not more indirect
- the resulting boundary is easier to explain than the previous one
- the refactor does not increase reliance on ambient global access unless it is part of an explicit temporary bridge
- any new architectural rule introduced by the change is documented and, where practical, guarded by tests

## Non-Goals

This roadmap does **not** commit the project to:

- a full clean-architecture rewrite
- speculative modding infrastructure now
- generic plugin abstractions without current pressure
- mass movement of code for stylistic consistency alone

The direction should support future extension, but the roadmap remains focused on current architecture quality in service of parity confidence and maintainability.

## Recommendation

Adopt the two-speed roadmap formally.

In practical terms, that means:

- establish the canonical principles doc immediately
- keep landing confidence-oriented quick wins continuously
- schedule dedicated architecture waves when a seam is important enough to justify temporary slowdown

This respects the project mission, aligns with the current state of the codebase, and gives the team a way to improve elegance and velocity without treating either of them as more important than parity confidence.
