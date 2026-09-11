# Object Manager Collaborator Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce `ObjectManager` risk by extracting `Placement`, `TouchResponses`, and `SolidContacts` into focused package-private collaborators without changing object ordering, slot ownership, or collision parity.

**Architecture:** Preserve `ObjectManager` as the public facade and owner of object collections. Move inner-class implementations into package-private classes in `com.openggf.level.objects`, passing only the state they need through narrow constructor arguments.

**Tech Stack:** Java 21, JUnit 5, Maven.

---

## File Map

- Add: `src/main/java/com/openggf/level/objects/ObjectPlacementController.java`
- Add: `src/main/java/com/openggf/level/objects/ObjectTouchResponseController.java`
- Add: `src/main/java/com/openggf/level/objects/ObjectSolidContactController.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: existing object, collision, touch-response, and architecture tests

## Tasks

### Task 1: Establish a behavior baseline

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.level.objects.*Test*,com.openggf.tests.TestArchitecturalSourceGuard" test`.
- [ ] If the wildcard selects too much or no tests on PowerShell, replace it with concrete classes reported by `rg "class Test.*Object" src/test/java/com/openggf`.
- [ ] Record any pre-existing failures before extraction.

### Task 2: Extract placement first

- [ ] Move the `ObjectManager.Placement` inner class body into `ObjectPlacementController`.
- [ ] Keep method names and visibility package-private where possible.
- [ ] Pass `ObjectManager` state through explicit constructor dependencies instead of accessing outer-class fields implicitly.
- [ ] Keep `ObjectManager` delegating through a field named `placement`.
- [ ] Run placement/windowing tests after this extraction.

### Task 3: Extract touch responses

- [ ] Move `ObjectManager.TouchResponses` into `ObjectTouchResponseController`.
- [ ] Preserve the per-frame enemy polling contract and edge-triggered special/monitor behavior.
- [ ] Add a focused regression test if no test currently asserts continuous ENEMY contact callbacks.
- [ ] Run object touch-response tests.

### Task 4: Extract solid contacts

- [ ] Move `ObjectManager.SolidContacts` into `ObjectSolidContactController`.
- [ ] Preserve riding, landing, ceiling, side collision, and plane-switch interactions.
- [ ] Do not change `CollisionSystem` API in this plan unless required by compilation.
- [ ] Run headless wall/platform collision tests.

### Task 5: Guard file-size regression

- [ ] Extend existing architecture review guards to record a new `ObjectManager.java` size budget after extraction.
- [ ] The budget should be lower than the current file length and include a short message naming the extracted collaborators.

### Task 6: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.level.objects.TestObjectServicesMigrationGuard" test`.
- [ ] Run the focused object/collision suite used in Task 1.
- [ ] Commit with `refactor: extract object manager collaborators`.

