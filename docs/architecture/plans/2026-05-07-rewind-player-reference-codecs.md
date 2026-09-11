# Rewind Player Reference Codecs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add compact schema support for player-reference fields and approved player-reference collections without serializing live player objects.

**Architecture:** Extend compact capture with an optional `RewindCaptureContext` that carries `RewindIdentityTable`. Field-aware codecs will encode `PlayableEntity` / `AbstractPlayableSprite` references as compact `PlayerRefId` integers and resolve them during restore. This slice intentionally leaves object child references unsupported.

**Tech Stack:** Java 21, JUnit 5, existing `com.openggf.game.rewind.schema` and `com.openggf.game.rewind.identity` packages.

---

## File Structure

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindCaptureContext.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodec.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindPlayerReferenceCodecs.java`

---

## Task 1: Context-Aware Player Reference Codecs

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindCaptureContext.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodec.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindPlayerReferenceCodecs.java`

- [x] **Step 1: Write failing tests**

Cover:
- Direct `PlayableEntity` and `AbstractPlayableSprite` fields restore through `PlayerRefId`.
- `Set<AbstractPlayableSprite>` restores the same player identities with insertion order.
- `Map<AbstractPlayableSprite, Integer>` restores player keys and value data.
- Player references require a context with an identity table.
- Unsupported object reference collections remain unsupported.

- [x] **Step 2: Run failing tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindPlayerReferenceCodecs" test
```

Expected: tests fail because player-reference fields are currently unsupported or capture live objects directly.

- [x] **Step 3: Add capture context**

Add `RewindCaptureContext.none()` and `RewindCaptureContext.withIdentityTable(...)`. Add context-aware overloads to `CompactFieldCapturer.capture(...)` and `restore(...)` while preserving existing no-context callers.

- [x] **Step 4: Add player-reference codecs**

Add codecs that write `PlayerRefId.encoded()` to scalar data and resolve through `RewindIdentityTable` on restore. The codec must throw a clear `IllegalStateException` if the identity table is missing or if a non-null live player is not registered.

- [x] **Step 5: Verify player-reference tests pass**

```powershell
mvn -Dmse=off "-Dtest=TestRewindPlayerReferenceCodecs" test
```

Expected: build success.

---

## Task 2: Rewind Regression

- [x] **Step 1: Run schema and identity tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindStateBuffer,TestRewindSchemaRegistry,TestCompactFieldCapturer,TestCompactFieldCapturerPolicy,TestRewindHelperCodecs,TestRewindCollectionCodecs,TestRewindPlayerReferenceCodecs,TestRewindIdentityTable" test
```

- [x] **Step 2: Run rewind-targeted suite**

```powershell
mvn -Dmse=off "-Dtest=*Rewind*" test
```
