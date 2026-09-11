# Rewind Identity Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add stable rewind identity value types and a per-capture identity table for player, object, and spawn references.

**Architecture:** This is a side-by-side foundation only. It does not convert `ObjectManagerSnapshot`, `SpriteManagerSnapshot`, or automatic schema reference codecs yet. The table uses identity maps during capture/restore, but stored ids are compact value records.

**Tech Stack:** Java 21, JUnit 5, existing rewind packages.

---

## File Structure

- Create: `src/main/java/com/openggf/game/rewind/identity/PlayerRefId.java`
  - Encodes null/main/sidekick references.

- Create: `src/main/java/com/openggf/game/rewind/identity/ObjectRefKind.java`
  - Enum for `LAYOUT`, `DYNAMIC`, `CHILD`.

- Create: `src/main/java/com/openggf/game/rewind/identity/ObjectRefId.java`
  - Encodes object slot, generation, spawn id, dynamic id, and kind.

- Create: `src/main/java/com/openggf/game/rewind/identity/SpawnRefId.java`
  - Encodes `ObjectSpawn.layoutIndex()`.

- Create: `src/main/java/com/openggf/game/rewind/identity/RewindIdentityTable.java`
  - Registration and encode/resolve table.

- Test: `src/test/java/com/openggf/game/rewind/identity/TestRewindIdentityTable.java`

---

## Task 1: Identity Value Types And Table

**Files:**
- Create all identity files listed above.
- Test: `src/test/java/com/openggf/game/rewind/identity/TestRewindIdentityTable.java`

- [x] **Step 1: Write failing identity tests**

Test these behaviors:

- `PlayerRefId.nullRef()` encodes `0`.
- `PlayerRefId.mainPlayer()` encodes `1`.
- `PlayerRefId.sidekick(0)` encodes `2`; `sidekick(2)` encodes `4`.
- Negative sidekick indexes throw.
- `ObjectRefId.layout(slot, generation, spawnId)` preserves fields and kind.
- `ObjectRefId.dynamic(slot, generation, dynamicId)` preserves fields and kind.
- `ObjectRefId.child(slot, generation, spawnId, dynamicId)` preserves fields and kind.
- `SpawnRefId.fromSpawn(spawn)` uses `ObjectSpawn.layoutIndex()`.
- `RewindIdentityTable` can register/encode/resolve players.
- `RewindIdentityTable` can register/encode/resolve objects.
- required missing refs throw; optional missing refs return `null`.
- duplicate registration for the same player/object with a different id throws.
- resolving a generation-mismatched object id fails because ids are exact values.

- [x] **Step 2: Run failing identity tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindIdentityTable" test
```

Expected: compile failure because identity package does not exist.

- [x] **Step 3: Implement value records**

Implement:

```java
public record PlayerRefId(int encoded) {
    public static PlayerRefId nullRef()
    public static PlayerRefId mainPlayer()
    public static PlayerRefId sidekick(int sidekickIndex)
    public boolean isNull()
}
```

```java
public enum ObjectRefKind {
    LAYOUT,
    DYNAMIC,
    CHILD
}
```

```java
public record ObjectRefId(int slotIndex, int generation, int spawnId, int dynamicId, ObjectRefKind kind) {
    public static ObjectRefId layout(int slotIndex, int generation, int spawnId)
    public static ObjectRefId dynamic(int slotIndex, int generation, int dynamicId)
    public static ObjectRefId child(int slotIndex, int generation, int spawnId, int dynamicId)
}
```

```java
public record SpawnRefId(int layoutIndex) {
    public static SpawnRefId fromSpawn(ObjectSpawn spawn)
}
```

- [x] **Step 4: Implement `RewindIdentityTable`**

Use `IdentityHashMap` for reverse capture lookup and `HashMap` for id-to-live-object lookup.

Public API:

```java
public void registerPlayer(PlayableEntity player, PlayerRefId id)
public PlayerRefId encodePlayer(PlayableEntity player)
public PlayableEntity resolvePlayer(PlayerRefId id, boolean required)

public void registerObject(ObjectInstance object, ObjectRefId id)
public ObjectRefId encodeObject(ObjectInstance object)
public ObjectInstance resolveObject(ObjectRefId id, boolean required)

public void registerSpawn(ObjectSpawn spawn)
public SpawnRefId encodeSpawn(ObjectSpawn spawn)
public ObjectSpawn resolveSpawn(SpawnRefId id, boolean required)
```

Null encode should return null ids for players, and `null` for optional objects/spawns until a null object id policy is added. Do not invent `ObjectRefId.nullRef()` in this slice.

- [x] **Step 5: Verify identity tests pass**

```powershell
mvn -Dmse=off "-Dtest=TestRewindIdentityTable" test
```

Expected: build success.

---

## Task 2: Focused Regression Run

**Files:**
- No new files.

- [x] **Step 1: Run identity and schema tests together**

```powershell
mvn -Dmse=off "-Dtest=TestRewindIdentityTable,TestRewindStateBuffer,TestRewindSchemaRegistry,TestCompactFieldCapturer,TestCompactFieldCapturerPolicy" test
```

Expected: build success.

- [x] **Step 2: Run rewind-targeted suite**

```powershell
mvn -Dmse=off "-Dtest=*Rewind*" test
```

Expected: build success. Existing benchmark/torture skips remain expected.
