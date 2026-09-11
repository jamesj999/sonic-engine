# Rewind Object Reference Codecs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add compact schema support for object-reference fields and approved object-reference collections using stable `ObjectRefId` identity.

**Architecture:** Reuse `RewindCaptureContext` and `RewindIdentityTable` introduced for player references. Object fields encode `ObjectRefId` components into scalar data and resolve through the active identity table during restore. This is reference rebinding only; parent-owned child lifecycle recreation and deterministic respawn policy remain separate rollout work.

**Tech Stack:** Java 21, JUnit 5, existing `com.openggf.game.rewind.schema` and `com.openggf.game.rewind.identity` packages.

---

## File Structure

- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindObjectReferenceCodecs.java`
- Modify: `src/test/java/com/openggf/game/rewind/schema/TestRewindPlayerReferenceCodecs.java`

---

## Task 1: Context-Aware Object Reference Codecs

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindObjectReferenceCodecs.java`
- Modify: `src/test/java/com/openggf/game/rewind/schema/TestRewindPlayerReferenceCodecs.java`

- [x] **Step 1: Write failing tests**

Cover:
- Direct `ObjectInstance` fields restore through `ObjectRefId`.
- `List<ObjectInstance>` restores object identities and null entries.
- `Map<ObjectInstance, Integer>` restores object keys and value data.
- Object references require a context with an identity table.
- Existing player-reference tests no longer assert object collections are unsupported.

- [x] **Step 2: Run failing tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindObjectReferenceCodecs,TestRewindPlayerReferenceCodecs" test
```

Expected: object-reference tests fail because object references are currently unsupported.

- [x] **Step 3: Add object-reference codec support**

Add direct and collection/map object-reference support to `RewindCodecs`. Encode null refs as a boolean false; encode non-null refs as kind ordinal plus slot, generation, spawn id, and dynamic id.

- [x] **Step 4: Verify object-reference tests pass**

```powershell
mvn -Dmse=off "-Dtest=TestRewindObjectReferenceCodecs,TestRewindPlayerReferenceCodecs" test
```

Expected: build success.

---

## Task 2: Rewind Regression

- [x] **Step 1: Run schema and identity tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindStateBuffer,TestRewindSchemaRegistry,TestCompactFieldCapturer,TestCompactFieldCapturerPolicy,TestRewindHelperCodecs,TestRewindCollectionCodecs,TestRewindPlayerReferenceCodecs,TestRewindObjectReferenceCodecs,TestRewindIdentityTable" test
```

- [x] **Step 2: Run rewind-targeted suite**

```powershell
mvn -Dmse=off "-Dtest=*Rewind*" test
```
