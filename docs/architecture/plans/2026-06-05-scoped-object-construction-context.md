# Scoped Object Construction Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw object-construction ThreadLocal set/remove paths with scoped save/restore helpers so nested child creation cannot leak or clear the wrong services context.

**Architecture:** Keep object construction ownership inside `level.objects`, but make context installation lexical and nest-safe. `ObjectConstructionContext.construct(...)` is the existing correct shape; this plan extends that pattern to every raw set/remove site in `AbstractObjectInstance` and `ObjectManager`.

**Tech Stack:** Java 21, JUnit 5, Maven, existing object-service migration guards.

---

## File Map

- Modify: `src/main/java/com/openggf/level/objects/ObjectConstructionContext.java`
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: `src/test/java/com/openggf/level/objects/TestNoServicesInObjectConstructors.java`
- Test: `src/test/java/com/openggf/level/objects/TestObjectServicesMigrationGuard.java`
- Add: `src/test/java/com/openggf/level/objects/TestObjectConstructionContextScope.java`

## Tasks

### Task 1: Add a scoped context API

- [ ] Add `ObjectConstructionContext.with(ObjectServices services, int slot, Supplier<T> supplier)` that captures previous `CONSTRUCTION_CONTEXT` and `PRE_ALLOCATED_SLOT`, installs the new values, invokes the supplier, then restores previous values in `finally`.
- [ ] Add `ObjectConstructionContext.with(ObjectServices services, Runnable action)` for call sites that do not return a value.
- [ ] Keep the existing `construct(...)` method and reimplement it through the new scoped helper.
- [ ] Run `mvn "-Dtest=com.openggf.level.objects.TestObjectServicesMigrationGuard" test`.

### Task 2: Add regression coverage for nested construction

- [ ] Create a focused test that installs outer services, enters an inner scoped context, throws from the inner supplier, and asserts the outer services and slot are restored.
- [ ] Add a second test that asserts both ThreadLocals are cleared when there was no previous value.
- [ ] Run `mvn "-Dtest=com.openggf.level.objects.TestObjectConstructionContextScope" test`.

### Task 3: Replace raw context management in object creation paths

- [ ] Replace raw `CONSTRUCTION_CONTEXT.set(...)` / `remove()` blocks in `ObjectManager` placement creation with `ObjectConstructionContext.with(...)`.
- [ ] Replace raw context blocks in dynamic object allocation and child spawn paths.
- [ ] Replace any raw ThreadLocal handling in `AbstractObjectInstance.spawnChild(...)` and related spawn helpers.
- [ ] Keep slot ownership behavior unchanged: existing remembered-spawn, dynamic-slot, and child-slot paths must still assign the same slot ids.

### Task 4: Add a guard against future raw set/remove growth

- [ ] Extend `TestObjectServicesMigrationGuard` or add a small source scanner that allows raw ThreadLocal access only inside `ObjectConstructionContext`.
- [ ] The scanner should fail on new uses of `CONSTRUCTION_CONTEXT.set(`, `CONSTRUCTION_CONTEXT.remove(`, `PRE_ALLOCATED_SLOT.set(`, or `PRE_ALLOCATED_SLOT.remove(` outside the scoped helper.
- [ ] Run `mvn "-Dtest=com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.tests.TestNoServicesInObjectConstructors" test`.

### Task 5: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.level.objects.TestObjectConstructionContextScope,com.openggf.level.objects.TestObjectServicesMigrationGuard,com.openggf.tests.TestNoServicesInObjectConstructors" test`.
- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalSourceGuard" test`.
- [ ] Commit with `fix: scope object construction context`.
