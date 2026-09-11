# Object Art Data Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `ObjectArtData` into a game-agnostic common bundle plus provider-owned registries so shared code stops accumulating game/zone-specific art fields.

**Architecture:** Keep `ObjectArtProvider` as the game boundary. Shared object rendering should ask for art by key and receive common renderable data; game-specific loaders own mappings, PLC plans, dynamic level-art registrations, and zone-specific conditions.

**Tech Stack:** Java 21, Maven, JUnit 5, ROM-backed art loaders.

---

## File Map

- Modify: `src/main/java/com/openggf/level/objects/art/ObjectArtData.java`
- Add: `src/main/java/com/openggf/level/objects/art/ObjectArtBundle.java`
- Add: `src/main/java/com/openggf/level/objects/art/ObjectArtRegistration.java`
- Modify: `src/main/java/com/openggf/level/objects/art/ObjectArtProvider.java`
- Modify game providers under `src/main/java/com/openggf/game/sonic1`, `sonic2`, and `sonic3k`
- Test: `src/test/java/com/openggf/tests/architecture/TestArchitecturalSourceGuard.java`

## Tasks

### Task 1: Inventory `ObjectArtData`

- [ ] Run `rg "ObjectArtData" src/main/java src/test/java`.
- [ ] Categorize fields into common render data, animation data, DPLC/PLC data, and game/zone-specific compatibility data.
- [ ] Do not remove fields before each usage has a target home.

### Task 2: Add common bundle types

- [ ] Create `ObjectArtBundle` for game-agnostic object art consumed by shared rendering.
- [ ] Create `ObjectArtRegistration` for provider-owned registration metadata such as art key, mapping source, pattern base, palette line, and PLC requirements.
- [ ] Add tests or compile-time usage proving shared object render code can consume the bundle without game-specific casts.

### Task 3: Move Sonic 3K PLC/art metadata first

- [ ] Move S3K-specific dynamic art and PLC registration metadata from `ObjectArtData` usage into `Sonic3kPlcArtRegistry` or a new S3K registration type.
- [ ] Keep runtime bytes ROM-only; do not read asset bytes from docs/disassembly paths.
- [ ] Run S3K art/provider tests and architecture source guards.

### Task 4: Move Sonic 2 conditional/eager art metadata

- [ ] Convert Sonic 2 PLC and object-art registration decisions into `ObjectArtRegistration` instances owned by `Sonic2ObjectArtProvider`.
- [ ] Keep current eager load behavior where parity or tests depend on it.
- [ ] Add a test that `ObjectArtData` does not gain Sonic 2 field names.

### Task 5: Move Sonic 1 legacy mapping metadata

- [ ] Move Sonic 1 zone-specific mapping and sheet flags into provider-owned registrations.
- [ ] Keep hardcoded mappings only where ROM macro parsing is not yet available.
- [ ] Document any remaining hardcoded mapping exception in architecture docs if a guard needs an allowlist.

### Task 6: Strengthen the growth guard

- [ ] Extend `TestArchitecturalSourceGuard` so new game names, zone ids, or provider-specific field names in `ObjectArtData` fail.
- [ ] Keep the guard focused on shared-art data only; game provider classes may keep game-specific names.

### Task 7: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.tests.TestArchUnitRules" test`.
- [ ] Run focused art/provider tests discovered by `rg "ObjectArt|PatternAtlas|PLC" src/test/java`.
- [ ] Commit with `refactor: split shared object art data`.

