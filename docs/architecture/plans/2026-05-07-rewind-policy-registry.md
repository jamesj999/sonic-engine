# Rewind Policy Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a central type/package policy registry so repeated rewind field decisions are represented once instead of as per-object annotations.

**Architecture:** `RewindSchemaRegistry` will consult `RewindPolicyRegistry` after Java/rewind annotations and before default codec inference. Registry rules support exact field keys, exact declared types, assignable type hierarchies, and package prefixes. This infrastructure slice adds the mechanism and tests; populating large production policy sets remains part of annotation-density cleanup and object rollout.

**Tech Stack:** Java 21, JUnit 5, existing `com.openggf.game.rewind.schema` package.

---

## File Structure

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindPolicyRegistry.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindSchemaRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindPolicyRegistry.java`

---

## Task 1: Central Policy Registry

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindPolicyRegistry.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindSchemaRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindPolicyRegistry.java`

- [x] **Step 1: Write failing policy registry tests**

Cover:
- Exact field policy takes precedence over type/package rules.
- Exact declared type policy can mark unsupported mutable types as structural.
- Assignable type policy applies to subclasses.
- Package prefix policy applies to fields by declared type package.
- Annotations remain stronger than registry rules.
- Forced captured policy requires an available codec.

- [x] **Step 2: Run failing tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindPolicyRegistry" test
```

Expected: compilation or test failure because `RewindPolicyRegistry` does not exist.

- [x] **Step 3: Implement registry and schema integration**

Add static registration methods for test and rollout use. `RewindSchemaRegistry.clearForTest()` should also clear custom registry rules so test schemas stay isolated.

- [x] **Step 4: Verify policy registry tests pass**

```powershell
mvn -Dmse=off "-Dtest=TestRewindPolicyRegistry" test
```

Expected: build success.

---

## Task 2: Rewind Regression

- [x] **Step 1: Run schema and identity tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindStateBuffer,TestRewindSchemaRegistry,TestCompactFieldCapturer,TestCompactFieldCapturerPolicy,TestRewindHelperCodecs,TestRewindCollectionCodecs,TestRewindPlayerReferenceCodecs,TestRewindObjectReferenceCodecs,TestRewindPolicyRegistry,TestRewindIdentityTable" test
```

- [x] **Step 2: Run rewind-targeted suite**

```powershell
mvn -Dmse=off "-Dtest=*Rewind*" test
```
