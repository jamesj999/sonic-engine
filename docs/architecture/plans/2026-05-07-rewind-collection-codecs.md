# Rewind Collection Codecs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add compact schema codecs for simple value collections without introducing object graph or player-reference capture yet.

**Architecture:** Schema planning becomes field-aware so collection codecs can inspect generic element types. This slice supports value-only `List`, `Set`, and `Map` fields whose generic arguments are small immutable values or enums; object/player reference collections remain unsupported until identity codecs are integrated.

**Tech Stack:** Java 21, JUnit 5, existing `com.openggf.game.rewind.schema` package.

---

## File Structure

- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindSchemaRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindCollectionCodecs.java`

---

## Task 1: Value Collection Codecs

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindSchemaRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindCollectionCodecs.java`

- [x] **Step 1: Write failing collection codec tests**

Cover:
- non-final `List<Integer>` restores order and null entries
- non-final `LinkedHashSet<Mode>` restores iteration order and null entries
- non-final `Map<String, Integer>` restores insertion order and null values
- final `List<Integer>` restores in place, preserving the collection instance
- `List<Object>` remains unsupported

- [x] **Step 2: Run failing tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindCollectionCodecs" test
```

Expected: tests fail because collection fields are currently unsupported.

- [x] **Step 3: Add field-aware codec lookup**

Add `RewindCodecs.codecFor(Field field)` and have `RewindSchemaRegistry` call it. Keep `codecFor(Class<?>)` for non-generic callers.

- [x] **Step 4: Implement value collection codecs**

Support only generic arguments that are small immutable values or enums. Restore final collection/map fields in place by clearing and repopulating the existing instance.

- [x] **Step 5: Verify collection tests pass**

```powershell
mvn -Dmse=off "-Dtest=TestRewindCollectionCodecs" test
```

Expected: build success.

---

## Task 2: Rewind Regression

- [x] **Step 1: Run schema and identity tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindStateBuffer,TestRewindSchemaRegistry,TestCompactFieldCapturer,TestCompactFieldCapturerPolicy,TestRewindHelperCodecs,TestRewindCollectionCodecs,TestRewindIdentityTable" test
```

- [x] **Step 2: Run rewind-targeted suite**

```powershell
mvn -Dmse=off "-Dtest=*Rewind*" test
```

---

## Follow-Up Task: Default-Policy Annotation Cleanup

This plan does not remove object annotations directly. After this codec slice
lands, run the inventory/audit and remove annotations that are only compensating
for missing value collection support. Keep annotations where they document
object/player references, structural child graph ownership, renderer/art caches,
or known deferred gaps.
