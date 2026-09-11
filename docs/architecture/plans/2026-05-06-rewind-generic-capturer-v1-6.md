# Generic Reflection-Based Object Capturer v1.6 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a generic reflection-based rewind capturer safely, prove it against the current manual rewind extras, and only then migrate objects/sprites/controllers whose field surfaces pass an audit.

**Architecture:** v1.6 is audit-first. A `GenericFieldCapturer` captures only explicitly supported value fields and skips fields annotated with `@RewindTransient`; an audit guard lists every unsupported real object/player/controller field before migration. Existing `PlayerRewindExtra`, `BadnikRewindExtra`, `MasherRewindExtra`, `BuzzerRewindExtra`, `CoconutsRewindExtra`, and `SidekickCpuRewindExtra` remain until a field-for-field parity oracle proves the generic snapshot reproduces them.

**Tech Stack:** Java 21, JUnit 5 / Jupiter, Maven 3.x with repo-local Maven Silent Extension. Use PowerShell-compatible commands on Windows; do not pipe Maven through `tail`/`grep` when the exit status matters.

---

## Review Findings Incorporated

This revision addresses the subagent review blockers from the first v1.6 plan:

- `AbstractPlayableSprite` does not extend `AbstractObjectInstance`; its `captureRewindState` and `restoreRewindState` methods stay in place and delegate to the new framework only after parity is proven.
- `SidekickCpuRewindExtra` is currently nested in `PerObjectRewindSnapshot`; the plan keeps it nested during migration.
- `ObjectSpawn` is not blanket auto-skipped. Structural `spawn` fields are annotated, while `dynamicSpawn` stays explicitly snapshotted as coordinates.
- The scanner roots include `level.objects`, `level.objects.boss`, `game.sonic1.objects`, `game.sonic2.objects`, `game.sonic3k.objects`, `sprites.playable`, and `sprites.AbstractSprite`.
- Scanner class names are derived from package declarations, not from a root package string.
- Collections are unsupported in v1.6 unless a field-specific codec is added. This plan does not claim generic `List`/`Map`/`Set` support.
- Final non-static fields are skipped by default unless the field is a supported immutable scalar value and is explicitly listed in a capturer policy. Restore never writes arbitrary structural finals.
- `GenericObjectSnapshot` uses a qualified field key `(declaringClass, fieldName)`, not bare field names.
- Full-suite verification compares stable failing-test identities parsed from Surefire XML, not raw XML lines.
- Generic capture is controlled by an explicit eligibility allow-list; unsupported/deferred runtime classes are not captured opportunistically.

## Hard Preconditions

- [ ] Resolve the existing unrelated merge conflict in `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java` before running Maven verification.
- [ ] Capture a clean pre-plan test baseline after the conflict is resolved:

```powershell
mvn test "-Dmse=off"
$LASTEXITCODE
$failures = foreach ($file in Get-ChildItem target/surefire-reports/TEST-*.xml) {
  [xml]$xml = Get-Content $file.FullName
  $suite = $xml.testsuite.name
  foreach ($case in $xml.testsuite.testcase) {
    if ($case.failure) {
      "$suite|$($case.classname)|$($case.name)|failure|$($case.failure.type)"
    }
    if ($case.error) {
      "$suite|$($case.classname)|$($case.name)|error|$($case.error.type)"
    }
  }
}
$failures | Sort-Object | Set-Content target/rewind-v1.6-preplan-failing-tests.txt
```

Expected: Maven may exit non-zero because this branch already has known failures, but `target/rewind-v1.6-preplan-failing-tests.txt` records the exact failing/error test identities used for comparison.

## Field Policy

`GenericFieldCapturer` v1.6 may capture:

- primitive fields and primitive wrapper values
- `String`
- enums
- records whose components recursively satisfy this policy
- arrays whose component type is primitive, `String`, enum, or supported record
- `BitSet`, cloned via `BitSet.clone()`

`GenericFieldCapturer` v1.6 must reject unless annotated, explicitly snapshotted outside the generic payload, or handled by a named codec:

- `List`, `Map`, `Set`, `Queue`, `Deque`, and concrete collection classes
- arbitrary mutable POJOs
- object graph references such as `AbstractPlayableSprite`, `PlayableEntity`, `ObjectInstance`, `ObjectSpawn`, `ObjectServices`, renderers, strategies, listeners, callbacks, and parent/child object references
- arrays of mutable object types such as `Sensor[]`
- non-static final fields by default

Structural references must use:

```java
@RewindTransient(reason = "structural runtime reference; restored by live object graph")
```

Gameplay state that is currently stored in mutable helpers must not be annotated just to satisfy the audit. Either add a field-specific codec or keep the existing manual extra until a later plan.

Final-field rule: unannotated non-static final fields are audit failures. The capturer may skip `@RewindTransient` finals, and it may capture immutable scalar finals only when a named policy lists the exact `(declaringClass, fieldName)` key. Restore never writes arbitrary final fields.

Explicit-snapshot rule: fields whose state is already represented by a sibling snapshot must be marked with `@RewindTransient(reason = "captured explicitly as ...")` and covered by a test for that explicit path. `AbstractObjectInstance.dynamicSpawn` remains explicit via `hasDynamicSpawn`, `dynamicSpawnX`, and `dynamicSpawnY`. `AbstractBossInstance.dynamicSpawn` and `AbstractBossChild.dynamicSpawn` are deferred in v1.6 unless a dedicated coordinate snapshot is added for them.

## File Structure

| Path | Responsibility |
|---|---|
| `src/main/java/com/openggf/game/rewind/RewindTransient.java` | Required reason-bearing field annotation for non-captured fields. |
| `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java` | Reflection capture/restore engine with qualified field keys and strict supported-type policy. |
| `src/main/java/com/openggf/game/rewind/FieldKey.java` | Record key: declaring class name + field name. |
| `src/main/java/com/openggf/game/rewind/EngineRefTypes.java` | Assignability/prefix helper used by guards to identify fields that require annotation. |
| `src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java` | Explicit allow-list for runtime classes whose generic sidecar may be captured. |
| `src/main/java/com/openggf/game/rewind/snapshot/GenericObjectSnapshot.java` | Immutable snapshot wrapper: concrete type + ordered keys + deep-cloned values. |
| `src/main/java/com/openggf/game/rewind/RewindScanSupport.java` | Shared scanner that discovers source classes by parsing `package` declarations. |
| `src/test/java/com/openggf/game/rewind/TestGenericFieldCapturer.java` | Unit tests for capture, restore, deep clone, field keys, final policy, and unsupported-type rejection. |
| `src/main/java/com/openggf/tools/rewind/RewindFieldInventoryTool.java` | Command-line full inventory scan for all runtime owner classes; used to produce the migration worklist. |
| `src/test/java/com/openggf/game/rewind/TestRewindFieldAudit.java` | Enabled CI audit for classes listed in `GenericRewindEligibility`. |
| `src/test/java/com/openggf/game/rewind/TestRewindTransientGuard.java` | CI guard requiring engine-ref fields to be `@RewindTransient` or Java `transient`. |
| `src/test/java/com/openggf/game/rewind/TestNoCustomCaptureOverrides.java` | Guard forbidding new custom overrides outside an allow-list, enabled only after equivalent generic migration is complete for a package. |
| `src/test/java/com/openggf/game/rewind/TestGenericRewindParityOracle.java` | Field-for-field oracle comparing generic snapshots to current manual extras for existing migrated targets. |
| `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java` | Keeps existing extras during v1.6; may gain optional `GenericObjectSnapshot genericState` but does not delete legacy fields in this plan. |
| `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java` | Adds generic capture sidecar only after field audit passes for selected object classes; keeps explicit dynamic-spawn fields. |
| `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` | Keeps public capture/restore methods; may delegate to generic state after parity. |
| `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` | Keeps nested `SidekickCpuRewindExtra`; may wrap a generic sidecar after parity. |
| `.agents/skills/s*-implement-object/SKILL.md`, `.agents/skills/s*-implement-boss/SKILL.md`, `.claude/skills/s*-implement-object/*`, `.claude/skills/s*-implement-boss/*` | Skill guidance requiring new objects/badniks/bosses to classify rewind synchronization fields and avoid transient annotations on gameplay state. |

---

## Track A - Framework Foundation

### Task A.1 - Add `@RewindTransient`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/RewindTransient.java`

- [ ] **Step 1: Add the annotation.**

```java
package com.openggf.game.rewind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RewindTransient {
    String reason();
}
```

- [ ] **Step 2: Commit.**

```powershell
git add src/main/java/com/openggf/game/rewind/RewindTransient.java
git commit -m "feat(rewind): add @RewindTransient annotation"
```

Use the repository trailer block required by `AGENTS.md`.

### Task A.2 - Add Qualified `FieldKey`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/FieldKey.java`
- Test: `src/test/java/com/openggf/game/rewind/TestGenericFieldCapturer.java`

- [ ] **Step 1: Add `FieldKey`.**

```java
package com.openggf.game.rewind;

import java.lang.reflect.Field;
import java.util.Objects;

public record FieldKey(String declaringClassName, String fieldName) {
    public FieldKey {
        Objects.requireNonNull(declaringClassName, "declaringClassName");
        Objects.requireNonNull(fieldName, "fieldName");
    }

    public static FieldKey of(Field field) {
        return new FieldKey(field.getDeclaringClass().getName(), field.getName());
    }

    @Override
    public String toString() {
        return declaringClassName + "#" + fieldName;
    }
}
```

- [ ] **Step 2: Add a test for duplicate inherited names.**

```java
@Test
void qualifiedFieldKeysDistinguishDuplicateInheritedNames() {
    class Parent { int value = 1; }
    class Child extends Parent { int value = 2; }

    GenericObjectSnapshot snap = GenericFieldCapturer.capture(new Child());

    assertEquals(1, snap.value(new FieldKey(Parent.class.getName(), "value")));
    assertEquals(2, snap.value(new FieldKey(Child.class.getName(), "value")));
}
```

- [ ] **Step 3: Run the test class after Task A.4 implements the capturer.**

```powershell
mvn test "-Dtest=TestGenericFieldCapturer"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

### Task A.3 - Add `GenericObjectSnapshot`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/GenericObjectSnapshot.java`

- [ ] **Step 1: Add the record.**

```java
package com.openggf.game.rewind.snapshot;

import com.openggf.game.rewind.FieldKey;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record GenericObjectSnapshot(Class<?> type, List<FieldKey> keys, Object[] values) {
    public GenericObjectSnapshot {
        Objects.requireNonNull(type, "type");
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        values = Objects.requireNonNull(values, "values").clone();
        if (keys.size() != values.length) {
            throw new IllegalArgumentException("keys/values length mismatch");
        }
    }

    @Override
    public Object[] values() {
        return values.clone();
    }

    public Object value(FieldKey key) {
        int idx = keys.indexOf(key);
        return idx < 0 ? null : values[idx];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GenericObjectSnapshot other
                && type.equals(other.type)
                && keys.equals(other.keys)
                && Arrays.deepEquals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(type, keys) + Arrays.deepHashCode(values);
    }
}
```

- [ ] **Step 2: Commit with Task A.4 after the tests pass.**

### Task A.4 - Add Strict `GenericFieldCapturer`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java`
- Test: `src/test/java/com/openggf/game/rewind/TestGenericFieldCapturer.java`

- [ ] **Step 1: Write tests covering supported and rejected types.**

```java
package com.openggf.game.rewind;

import com.openggf.game.rewind.snapshot.GenericObjectSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class TestGenericFieldCapturer {
    static class Plain {
        int counter;
        boolean flag;
        String name;
    }

    static class WithTransient {
        int captured;
        @RewindTransient(reason = "test-only derived value")
        int skipped;
    }

    static class WithUnannotatedFinalRef {
        final String structuralName = "kept-live";
        int gameplay = 7;
    }

    static class WithAnnotatedFinalRef {
        @RewindTransient(reason = "structural final fixture")
        final Object structuralRef = new Object();
        int gameplay = 7;
    }

    static class WithUnsupportedCollection {
        HashMap<String, Integer> map = new HashMap<>();
    }

    record Inner(int x, String y) {}

    static class WithRecordAndArray {
        Inner inner = new Inner(3, "a");
        int[] values = {1, 2, 3};
    }

    @Test
    void roundTripsSupportedFields() {
        Plain p = new Plain();
        p.counter = 42;
        p.flag = true;
        p.name = "sonic";

        GenericObjectSnapshot snap = GenericFieldCapturer.capture(p);
        Plain restored = new Plain();
        GenericFieldCapturer.restore(restored, snap);

        assertEquals(42, restored.counter);
        assertTrue(restored.flag);
        assertEquals("sonic", restored.name);
    }

    @Test
    void skipsAnnotatedFields() {
        WithTransient w = new WithTransient();
        w.captured = 1;
        w.skipped = 99;

        GenericObjectSnapshot snap = GenericFieldCapturer.capture(w);
        WithTransient restored = new WithTransient();
        restored.skipped = 5;
        GenericFieldCapturer.restore(restored, snap);

        assertEquals(1, restored.captured);
        assertEquals(5, restored.skipped);
    }

    @Test
    void rejectsUnannotatedFinalFieldsByDefault() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GenericFieldCapturer.capture(new WithUnannotatedFinalRef()));
        assertTrue(ex.getMessage().contains("structuralName"));
        assertTrue(ex.getMessage().contains("final"));
    }

    @Test
    void skipsAnnotatedFinalFields() {
        GenericObjectSnapshot snap = GenericFieldCapturer.capture(new WithAnnotatedFinalRef());
        assertNull(snap.value(new FieldKey(WithAnnotatedFinalRef.class.getName(), "structuralRef")));
        assertEquals(7, snap.value(new FieldKey(WithAnnotatedFinalRef.class.getName(), "gameplay")));
    }

    @Test
    void deepClonesRecordsAndArrays() {
        WithRecordAndArray w = new WithRecordAndArray();
        GenericObjectSnapshot snap = GenericFieldCapturer.capture(w);
        w.values[0] = 99;

        WithRecordAndArray restored = new WithRecordAndArray();
        GenericFieldCapturer.restore(restored, snap);

        assertEquals(new Inner(3, "a"), restored.inner);
        assertEquals(1, restored.values[0]);
    }

    @Test
    void rejectsCollectionsInV16() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> GenericFieldCapturer.capture(new WithUnsupportedCollection()));
        assertTrue(ex.getMessage().contains("map"));
        assertTrue(ex.getMessage().contains("@RewindTransient"));
    }
}
```

- [ ] **Step 2: Implement the capturer with strict policy.**

Implementation requirements:

- Walk inheritance chain from superclass to subclass so restore order is stable.
- Skip `static`, Java `transient`, and `@RewindTransient`.
- Throw for unannotated non-static `final` fields unless the exact `FieldKey` is present in a named final-field capture policy.
- Do not silently skip engine refs. The audit guard requires engine refs to be annotated.
- Throw `IllegalStateException` for unsupported declared field types.
- Restore only fields present in the snapshot key list.
- Deep-clone values on capture and restore.

- [ ] **Step 3: Run tests.**

```powershell
mvn test "-Dtest=TestGenericFieldCapturer"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

- [ ] **Step 4: Commit.**

```powershell
git add src/main/java/com/openggf/game/rewind/FieldKey.java `
        src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java `
        src/main/java/com/openggf/game/rewind/snapshot/GenericObjectSnapshot.java `
        src/test/java/com/openggf/game/rewind/TestGenericFieldCapturer.java
git commit -m "feat(rewind): add strict generic field capturer"
```

Use the required trailer block.

---

## Track B - Scan and Audit Guards

### Task B.1 - Add Source Scanner Helper

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/RewindScanSupport.java`

- [ ] **Step 1: Add the scanner helper.**

```java
package com.openggf.game.rewind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class RewindScanSupport {
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");

    static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src/main/java/com/openggf/level/objects"),
            Path.of("src/main/java/com/openggf/game/sonic1/objects"),
            Path.of("src/main/java/com/openggf/game/sonic2/objects"),
            Path.of("src/main/java/com/openggf/game/sonic3k/objects"),
            Path.of("src/main/java/com/openggf/sprites/playable")
    );

    static List<Class<?>> discoverRuntimeOwnerClasses() throws IOException {
        List<Class<?>> classes = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Optional<String> className = classNameFor(file);
                    if (className.isEmpty()) continue;
                    try {
                        Class<?> cls = Class.forName(
                                className.get(),
                                false,
                                Thread.currentThread().getContextClassLoader());
                        if (isRuntimeOwner(cls)) {
                            classes.add(cls);
                        }
                    } catch (ClassNotFoundException e) {
                        unresolved.add(file + " -> " + className.get());
                    }
                }
            }
        }
        classes.add(load("com.openggf.sprites.AbstractSprite"));
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Unresolved source classes:\n"
                    + String.join("\n", unresolved));
        }
        return classes;
    }

    static List<Class<?>> withNestedRuntimeOwnerClasses(Class<?> cls) {
        List<Class<?>> result = new ArrayList<>();
        result.add(cls);
        for (Class<?> nested : cls.getDeclaredClasses()) {
            if (isRuntimeOwner(nested)) {
                result.addAll(withNestedRuntimeOwnerClasses(nested));
            }
        }
        return result;
    }

    static boolean isRuntimeOwner(Class<?> cls) {
        return com.openggf.level.objects.AbstractObjectInstance.class.isAssignableFrom(cls)
                || com.openggf.sprites.playable.AbstractPlayableSprite.class.isAssignableFrom(cls)
                || cls == com.openggf.sprites.playable.SidekickCpuController.class
                || cls == com.openggf.sprites.AbstractSprite.class;
    }

    private static Optional<String> classNameFor(Path file) throws IOException {
        String packageName = null;
        for (String line : Files.readAllLines(file)) {
            Matcher m = PACKAGE_PATTERN.matcher(line);
            if (m.find()) {
                packageName = m.group(1);
                break;
            }
        }
        if (packageName == null) return Optional.empty();
        String simpleName = file.getFileName().toString().replaceFirst("\\.java$", "");
        return Optional.of(packageName + "." + simpleName);
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(
                    className,
                    false,
                    Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing expected class " + className, e);
        }
    }

    private RewindScanSupport() {}
}
```

- [ ] **Step 2: Commit with Task B.2.**

### Task B.2 - Add Engine Ref Guard

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/EngineRefTypes.java`
- Create: `src/test/java/com/openggf/game/rewind/TestRewindTransientGuard.java`

- [ ] **Step 1: Add `EngineRefTypes` with assignable checks.**

The implementation must treat a field as an engine ref when its declared type is assignable to known interfaces/classes such as:

- `ObjectServices`
- `ObjectManager`
- `ObjectSpawn`
- `ObjectInstance`
- `PlayableEntity`
- `AbstractPlayableSprite`
- `SidekickRespawnStrategy`
- `SidekickCarryTrigger`
- `PatternSpriteRenderer`
- `ObjectRenderManager`
- managers and session types listed in `AGENTS.md`

- [ ] **Step 2: Add the guard.**

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

class TestRewindTransientGuard {
    @Test
    void engineReferenceFieldsAreAnnotatedOrJavaTransient() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                for (Field field : cls.getDeclaredFields()) {
                    int mods = field.getModifiers();
                    if (Modifier.isStatic(mods)) continue;
                    if (Modifier.isTransient(mods)) continue;
                    if (field.isAnnotationPresent(RewindTransient.class)) continue;
                    if (!EngineRefTypes.isEngineRef(field.getType())) continue;
                    violations.add(cls.getName() + "#" + field.getName()
                            + " : " + field.getType().getName());
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("Engine-ref fields need @RewindTransient(reason=...) or Java transient:\n"
                    + String.join("\n", violations));
        }
    }
}
```

- [ ] **Step 3: Run the guard and save the violation inventory.**

```powershell
mvn test "-Dtest=TestRewindTransientGuard"
if ($LASTEXITCODE -eq 0) { Write-Output "guard already clean" }
Copy-Item target/surefire-reports/com.openggf.game.rewind.TestRewindTransientGuard.txt `
          target/rewind-v1.6-engine-ref-violations.txt `
          -ErrorAction SilentlyContinue
```

Expected before migration: failures listing engine refs that need annotation.

### Task B.3 - Add Unsupported Field Inventory and Eligibility Audit

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java`
- Test: `src/test/java/com/openggf/game/rewind/TestGenericRewindEligibility.java`
- Create: `src/main/java/com/openggf/tools/rewind/RewindFieldInventoryTool.java`
- Create: `src/test/java/com/openggf/game/rewind/TestRewindFieldAudit.java`

- [ ] **Step 1: Add `GenericRewindEligibility` before compiling the audit.**

```java
package com.openggf.game.rewind;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class GenericRewindEligibility {
    private static final Set<Class<?>> ELIGIBLE = ConcurrentHashMap.newKeySet();

    public static boolean isEligible(Class<?> type) {
        return ELIGIBLE.contains(type);
    }

    public static Set<Class<?>> eligibleClassesForAudit() {
        return Set.copyOf(ELIGIBLE);
    }

    public static void registerForTestOrMigration(Class<?> type) {
        ELIGIBLE.add(type);
    }

    public static void clearForTest() {
        ELIGIBLE.clear();
    }

    private GenericRewindEligibility() {}
}
```

- [ ] **Step 2: Add the full inventory tool.**

```java
package com.openggf.tools.rewind;

import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.RewindScanSupport;
import com.openggf.game.rewind.RewindTransient;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public final class RewindFieldInventoryTool {
    public static void main(String[] args) throws Exception {
        List<String> unsupported = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                for (Field field : cls.getDeclaredFields()) {
                    int mods = field.getModifiers();
                    if (Modifier.isStatic(mods)) continue;
                    if (Modifier.isTransient(mods)) continue;
                    if (field.isAnnotationPresent(RewindTransient.class)) continue;
                    if (!GenericFieldCapturer.isSupportedDeclaredTypeForAudit(field)) {
                        unsupported.add(cls.getName() + "#" + field.getName()
                                + " : " + field.getType().getName());
                    }
                }
            }
        }
        if (!unsupported.isEmpty()) {
            System.err.println("Unsupported rewind fields:");
            unsupported.forEach(System.err::println);
            System.exit(1);
        }
    }
}
```

- [ ] **Step 3: Add the enabled eligibility audit.**

```java
package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

class TestRewindFieldAudit {
    @Test
    void eligibleClassesHaveOnlySupportedGenericFields() {
        List<String> unsupported = new ArrayList<>();
        for (Class<?> cls : GenericRewindEligibility.eligibleClassesForAudit()) {
            for (Class<?> owner : RewindScanSupport.withNestedRuntimeOwnerClasses(cls)) {
                for (Field field : owner.getDeclaredFields()) {
                    int mods = field.getModifiers();
                    if (Modifier.isStatic(mods)) continue;
                    if (Modifier.isTransient(mods)) continue;
                    if (field.isAnnotationPresent(RewindTransient.class)) continue;
                    if (!GenericFieldCapturer.isSupportedDeclaredTypeForAudit(field)) {
                        unsupported.add(owner.getName() + "#" + field.getName()
                                + " : " + field.getType().getName());
                    }
                }
            }
        }
        if (!unsupported.isEmpty()) {
            fail("Generic-eligible classes contain unsupported fields:\n"
                    + String.join("\n", unsupported));
        }
    }
}
```

- [ ] **Step 4: Run the full inventory manually and save the migration worklist.**

```powershell
mvn -Dmse=off -DskipTests test-compile exec:java `
    "-Dexec.mainClass=com.openggf.tools.rewind.RewindFieldInventoryTool" `
    *> target/rewind-v1.6-unsupported-field-inventory.txt
```

Expected before migration: the inventory command exits non-zero and writes the migration worklist.

- [ ] **Step 5: Run the enabled audit.**

```powershell
mvn test "-Dtest=TestRewindFieldAudit"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Expected initially: pass with an empty eligibility set. As classes are added to `GenericRewindEligibility`, this audit becomes their green gate.

- [ ] **Step 6: Add tests proving the audit catches final fields.**

The test fixture must include:

```java
static class WithUnannotatedFinal {
    final Object value = new Object();
}

static class WithAnnotatedFinal {
    @RewindTransient(reason = "structural final fixture")
    final Object value = new Object();
}
```

Expected: `WithUnannotatedFinal.value` is unsupported when its class is eligible, `WithAnnotatedFinal.value` is skipped.

---

## Track C - Annotation and Field Policy Sweep

### Task C.1 - Annotate Structural References

**Files:**
- Modify: classes reported by `target/rewind-v1.6-engine-ref-violations.txt`

- [ ] **Step 1: For each engine-ref violation, decide if the field is structural.**

Use `@RewindTransient` only when the field is restored by the live runtime graph, immutable placement identity, renderer cache, listener, service, or parent/child relationship.

Examples:

```java
@RewindTransient(reason = "structural spawn identity; ObjectManagerSnapshot stores slot spawn separately")
protected final ObjectSpawn spawn;

@RewindTransient(reason = "object services are runtime-owned and reattached by ObjectManager")
private ObjectServices services;

@RewindTransient(reason = "sidekick structural link; live daisy-chain graph persists across rewind")
private AbstractPlayableSprite leader;
```

- [ ] **Step 2: Do not annotate mutable gameplay state.**

Fields like `SubpixelMotion.State`, `ObjectAnimationState`, rider state maps, timers, movement helpers, or per-player state containers must either remain on legacy extras or get an explicit value codec in a later task.

- [ ] **Step 3: Re-run the engine ref guard.**

```powershell
mvn test "-Dtest=TestRewindTransientGuard"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

- [ ] **Step 4: Commit annotation batches by package.**

```powershell
git add src/main/java/com/openggf
git commit -m "feat(rewind): annotate structural rewind-transient references"
```

Use required trailers.

### Task C.2 - Categorize Unsupported Field Inventory

**Files:**
- Create: `docs/architecture/plans/2026-05-06-rewind-generic-capturer-v1-6-field-inventory.md`

- [ ] **Step 1: Convert the audit output into this table.**

```markdown
# Rewind v1.6 Unsupported Field Inventory

| Class | Field | Type | Decision |
|---|---|---|---|
| com.openggf.level.objects.AbstractObjectInstance | dynamicSpawn | ObjectSpawn | annotate transient; captured explicitly as dynamicSpawnX/Y |
| com.openggf.level.objects.boss.AbstractBossInstance | dynamicSpawn | ObjectSpawn | defer boss classes in v1.6 unless dedicated coordinate snapshot is added |
| com.openggf.level.objects.boss.AbstractBossChild | dynamicSpawn | ObjectSpawn | defer boss-child classes in v1.6 unless dedicated coordinate snapshot is added |
| com.openggf.sprites.AbstractSprite | groundSensors | Sensor[] | annotate transient; derived collision probes rebuilt by constructor |
| com.openggf.level.objects.SubpixelMotion.State | n/a | mutable helper | add value codec before migrating owning classes |
| com.openggf.level.objects.ObjectAnimationState | n/a | mutable helper | add value codec before migrating owning classes |
```

- [ ] **Step 2: For every row choose one of four decisions:**

- `annotate transient`
- `explicit snapshot field`
- `value codec`
- `defer class on legacy extra`

### Task C.3 - Use Generic Rewind Eligibility Allow-List

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java`
- Test: `src/test/java/com/openggf/game/rewind/TestGenericRewindEligibility.java`

- [ ] **Step 1: Add tests proving unsupported/deferred classes are not captured.**

```java
@Test
void classMustBeExplicitlyEligibleForSidecarCapture() {
    GenericRewindEligibility.clearForTest();
    assertFalse(GenericRewindEligibility.isEligible(MasherBadnikInstance.class));
}
```

- [ ] **Step 2: Use this allow-list for sidecar capture.**

No production code may call `GenericFieldCapturer.capture(this)` for arbitrary object/player instances unless `GenericRewindEligibility.isEligible(getClass())` is true.

- [ ] **Step 3: Commit the inventory and eligibility helper.**

```powershell
git add src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java `
        src/test/java/com/openggf/game/rewind/TestGenericRewindEligibility.java `
        docs/architecture/plans/2026-05-06-rewind-generic-capturer-v1-6-field-inventory.md
git commit -m "docs(rewind): inventory generic capturer field policy decisions"
```

Trailer note: `Guide: n/a`, because this is a plan artifact, not `docs/guide/`.

---

## Track D - Sidecars and Parity Oracles Before Migration

### Task D.1 - Add Generic Sidecar Without Deleting Extras

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`

- [ ] **Step 1: Add optional sidecar field after existing extras.**

```java
com.openggf.game.rewind.snapshot.GenericObjectSnapshot genericState
```

Keep every existing constructor argument and existing extra record. Add wither:

Add an old-signature compact overload so existing constructor call sites still compile:

```java
public PerObjectRewindSnapshot(
        boolean destroyed,
        boolean destroyedRespawnable,
        boolean hasDynamicSpawn,
        int dynamicSpawnX, int dynamicSpawnY,
        int preUpdateX, int preUpdateY,
        boolean preUpdateValid, int preUpdateCollisionFlags,
        boolean skipTouchThisFrame, boolean solidContactFirstFrame,
        int slotIndex, int respawnStateIndex,
        BadnikRewindExtra badnikExtra,
        BadnikSubclassRewindExtra badnikSubclassExtra,
        PlayerRewindExtra playerExtra) {
    this(destroyed, destroyedRespawnable,
            hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
            preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
            skipTouchThisFrame, solidContactFirstFrame,
            slotIndex, respawnStateIndex,
            badnikExtra,
            badnikSubclassExtra,
            playerExtra,
            null);
}
```

```java
public PerObjectRewindSnapshot withGenericState(
        com.openggf.game.rewind.snapshot.GenericObjectSnapshot genericState) {
    return new PerObjectRewindSnapshot(
            destroyed, destroyedRespawnable,
            hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
            preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
            skipTouchThisFrame, solidContactFirstFrame,
            slotIndex, respawnStateIndex,
            badnikExtra,
            badnikSubclassExtra,
            playerExtra,
            genericState
    );
}
```

Update existing `withPlayerExtra(...)` and `withBadnikSubclassExtra(...)` to pass through the current `genericState`.

Add or update a `withBadnikExtra(...)` path before the object parity oracle. `AbstractBadnikInstance.captureRewindState()` currently rebuilds `PerObjectRewindSnapshot` after calling `super.captureRewindState()`. That rebuild must preserve the generic sidecar:

```java
public PerObjectRewindSnapshot withBadnikExtra(BadnikRewindExtra extra) {
    return new PerObjectRewindSnapshot(
            destroyed, destroyedRespawnable,
            hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY,
            preUpdateX, preUpdateY, preUpdateValid, preUpdateCollisionFlags,
            skipTouchThisFrame, solidContactFirstFrame,
            slotIndex, respawnStateIndex,
            extra,
            badnikSubclassExtra,
            playerExtra,
            genericState
    );
}
```

Then change `AbstractBadnikInstance.captureRewindState()` to return `base.withBadnikExtra(badnikExtra)` instead of calling an old-signature constructor that would set `genericState` to `null`.

- [ ] **Step 2: In `AbstractObjectInstance.captureRewindState`, attach generic state only when the runtime class is eligible.**

Do not short-circuit `restoreRewindState` yet. Existing explicit restore remains authoritative.

- [ ] **Step 3: Keep dynamic spawn explicit.**

`hasDynamicSpawn`, `dynamicSpawnX`, and `dynamicSpawnY` remain record fields and continue restoring through `updateDynamicSpawn(...)`.

- [ ] **Step 4: Run targeted tests.**

```powershell
mvn test "-Dtest=TestAbstractObjectInstanceRewindCapture,TestObjectManagerRewindSnapshot"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

### Task D.2 - Add Object Manual Extra Parity Oracle

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/TestGenericRewindParityOracle.java`

- [ ] **Step 1: Add assertions comparing object-side existing extras to generic keys.**

The first oracle covers only object classes whose generic sidecar is added by Track D.1:

- `AbstractBadnikInstance` fields currently in `BadnikRewindExtra`
- any concrete badnik subclass explicitly registered in `GenericRewindEligibility`

Use `FieldKey` with declaring class names for each assertion. For example:

```java
private static Object generic(PerObjectRewindSnapshot snap, Class<?> owner, String field) {
    return snap.genericState().value(new FieldKey(owner.getName(), field));
}
```

- [ ] **Step 2: Run the oracle.**

```powershell
mvn test "-Dtest=TestGenericRewindParityOracle"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

Expected: pass only for classes whose field audit is clean and whose sidecar is attached. If a class is not audit-clean, keep it excluded from the oracle and document it in the inventory as `defer class on legacy extra`.

### Task D.3 - Add Playable Sprite and Sidekick CPU Sidecar Oracles Separately

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestGenericRewindParityOracle.java`

- [ ] **Step 1: Add player generic sidecar only when `AbstractPlayableSprite` and inherited `AbstractSprite` fields are audit-clean.**

Keep `PlayerRewindExtra` authoritative.

- [ ] **Step 2: Add sidekick CPU generic sidecar inside the nested `SidekickCpuRewindExtra` only after `SidekickCpuController` is audit-clean.**

Keep every existing nested record component and add the optional sidecar as the final component with an old-signature overloaded constructor.

- [ ] **Step 3: Add separate oracle tests.**

Use separate test methods:

- `badnikGenericSidecarMatchesBadnikExtra`
- `playerGenericSidecarMatchesPlayerExtra`
- `sidekickCpuGenericSidecarMatchesSidekickCpuExtra`

Each Track E migration is gated only by the matching oracle.

- [ ] **Step 3: Commit.**

```powershell
git add src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java `
        src/main/java/com/openggf/level/objects/AbstractObjectInstance.java `
        src/test/java/com/openggf/game/rewind/TestGenericRewindParityOracle.java
git commit -m "test(rewind): add generic snapshot parity oracle"
```

Use required trailers.

---

## Track E - Controlled Migration

### Task E.1 - Migrate `AbstractBadnikInstance` Only After Oracle Passes

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`

- [ ] **Step 1: Register `AbstractBadnikInstance` only after the inventory decision says its field surface is clean, then confirm eligible-class audit and oracle are green.**

```powershell
mvn test "-Dtest=TestRewindFieldAudit,TestGenericRewindParityOracle"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

- [ ] **Step 2: Replace badnik restore with generic restore for fields covered by the oracle.**

Keep `BadnikRewindExtra` until the full test suite and long-tail benchmark show no regressions.

- [ ] **Step 3: Run focused tests.**

```powershell
mvn test "-Dtest=TestAbstractBadnikInstanceRewindCapture,TestRewindParityAgainstTrace"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

### Task E.2 - Keep Masher/Buzzer/Coconuts Legacy Overrides in v1.6

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/badniks/MasherBadnikInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/badniks/BuzzerBadnikInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/badniks/CoconutsBadnikInstance.java`

- [ ] **Step 1: Confirm class-specific audit status.**

Do not migrate any class still listed in `target/rewind-v1.6-unsupported-field-inventory.txt`.

- [ ] **Step 2: Do not delete overrides or extras in v1.6.**

The override may attach generic sidecar state after class-specific audit and oracle pass, but the existing manual `captureRewindState` and `restoreRewindState` paths remain the fallback until Track G passes. Deleting overrides and extras moves to a later cleanup plan.

After each sidecar addition:

```powershell
mvn test "-Dtest=TestRewindParityAgainstTrace"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

- [ ] **Step 3: Commit each class separately.**

```powershell
git add src/main/java/com/openggf/game/sonic2/objects/badniks/MasherBadnikInstance.java
git commit -m "test(rewind): add Masher generic sidecar parity coverage"
```

Use required trailers.

### Task E.3 - Migrate Playable Sprite Without Deleting Public Methods

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`

- [ ] **Step 1: Keep these methods present.**

```java
public PerObjectRewindSnapshot captureRewindState()
public void restoreRewindState(PerObjectRewindSnapshot s)
```

- [ ] **Step 2: Add generic sidecar capture inside `captureRewindState` only after `AbstractSprite` inherited fields are audit-clean or annotated.**

Do not delete `PlayerRewindExtra` in v1.6. A later cleanup plan may delete it only after:

- `TestGenericRewindParityOracle` covers every `PlayerRewindExtra` field
- `TestAbstractPlayableSpriteRewindCapture` passes
- `TestSidekickCpuControllerRewindCapture` passes
- long-tail benchmark does not regress

- [ ] **Step 3: Run focused tests.**

```powershell
mvn test "-Dtest=TestAbstractPlayableSpriteRewindCapture,TestSidekickCpuControllerRewindCapture,TestRewindParityAgainstTrace"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

### Task E.4 - Migrate Sidekick CPU Keeping Nested Wrapper

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`

- [ ] **Step 1: Keep `PerObjectRewindSnapshot.SidekickCpuRewindExtra` nested.**

If a generic sidecar is added, add it as a new final component inside that nested record without moving the type. Add an old-signature overloaded constructor that supplies `genericState = null`.

- [ ] **Step 2: Annotate structural refs.**

Expected structural refs:

```java
@RewindTransient(reason = "owning sidekick structural reference")
private final AbstractPlayableSprite sidekick;

@RewindTransient(reason = "leader structural reference; daisy-chain graph persists live")
private AbstractPlayableSprite leader;

@RewindTransient(reason = "strategy object; selected by live sidekick character")
private SidekickRespawnStrategy respawnStrategy;
```

- [ ] **Step 3: Run focused tests.**

```powershell
mvn test "-Dtest=TestSidekickCpuControllerRewindCapture,TestRewindParityAgainstTrace"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

---

## Track F - Guards Enforcement

### Task F.1 - Enable Engine Ref Guard

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestRewindTransientGuard.java`

- [ ] **Step 1: Remove `@Disabled` if present.**
- [ ] **Step 2: Run guard.**

```powershell
mvn test "-Dtest=TestRewindTransientGuard"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

- [ ] **Step 3: Commit.**

```powershell
git add src/test/java/com/openggf/game/rewind/TestRewindTransientGuard.java
git commit -m "test(rewind): enforce transient annotation for engine refs"
```

### Task F.2 - Enable Unsupported Field Audit for Migrated Classes

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestRewindFieldAudit.java`

- [ ] **Step 1: Restrict enabled audit to classes migrated in Track E.**

Do not require every object in the engine to be clean before this branch ships. The enabled audit must cover only classes in `GenericRewindEligibility`; the full inventory file tracks remaining classes.

- [ ] **Step 2: Run audit.**

```powershell
mvn test "-Dtest=TestRewindFieldAudit"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

### Task F.3 - Enable Override Guard Only for Migrated Packages

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/TestNoCustomCaptureOverrides.java`

- [ ] **Step 1: Add allow-list based guard.**

The first enabled version should only forbid new overrides in packages/classes that have been migrated and passed the oracle. It must not fail on classes intentionally deferred in the inventory.

- [ ] **Step 2: Run guard.**

```powershell
mvn test "-Dtest=TestNoCustomCaptureOverrides"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

---

## Track G - Verification

### Task G.1 - Targeted Regression Suite

- [ ] **Step 1: Run targeted rewind tests.**

```powershell
mvn test "-Dtest=TestGenericFieldCapturer,TestRewindTransientGuard,TestRewindFieldAudit,TestGenericRewindParityOracle,TestAbstractObjectInstanceRewindCapture,TestAbstractBadnikInstanceRewindCapture,TestAbstractPlayableSpriteRewindCapture,TestSidekickCpuControllerRewindCapture,TestObjectManagerRewindSnapshot,TestRewindParityAgainstTrace"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

### Task G.2 - Long-Tail Benchmark

- [ ] **Step 1: Run benchmark without hiding exit status.**

```powershell
mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Dmse=off"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Select-String -Path target/surefire-reports/*.txt -Pattern "Long-tail|clean rewind|differs at|first diverge"
```

- [ ] **Step 2: Record the result in the final status and inventory doc.**

If clean rewind regresses compared with the pre-plan baseline, revert the last migration commit and keep that class on legacy extras.

### Task G.3 - Full Suite Baseline Comparison

- [ ] **Step 1: Run full suite.**

```powershell
mvn test "-Dmse=off"
$exit = $LASTEXITCODE
$failures = foreach ($file in Get-ChildItem target/surefire-reports/TEST-*.xml) {
  [xml]$xml = Get-Content $file.FullName
  $suite = $xml.testsuite.name
  foreach ($case in $xml.testsuite.testcase) {
    if ($case.failure) {
      "$suite|$($case.classname)|$($case.name)|failure|$($case.failure.type)"
    }
    if ($case.error) {
      "$suite|$($case.classname)|$($case.name)|error|$($case.error.type)"
    }
  }
}
$failures | Sort-Object | Set-Content target/rewind-v1.6-postplan-failing-tests.txt
exit $exit
```

- [ ] **Step 2: Compare summaries.**

```powershell
Compare-Object `
  (Get-Content target/rewind-v1.6-preplan-failing-tests.txt) `
  (Get-Content target/rewind-v1.6-postplan-failing-tests.txt)
```

Expected: no new failing test identities attributable to v1.6.

---

## Track H - Documentation

### Task H.1 - Document v1.6 Audit-First Architecture

**Files:**
- Modify: `docs/architecture/plans/2026-05-04-rewind-framework-design.md` if present
- Modify: `docs/architecture/plans/2026-05-06-rewind-generic-capturer-v1-6-field-inventory.md`
- Modify: `.agents/skills/s1-implement-object/SKILL.md`
- Modify: `.agents/skills/s2-implement-object/SKILL.md`
- Modify: `.agents/skills/s3k-implement-object/SKILL.md`
- Modify: `.agents/skills/s1-implement-boss/SKILL.md`
- Modify: `.agents/skills/s2-implement-boss/SKILL.md`
- Modify: `.agents/skills/s3k-implement-boss/SKILL.md`
- Modify: `.claude/skills/s1-implement-object/skill.md`
- Modify: `.claude/skills/s2-implement-object/SKILL.md`
- Modify: `.claude/skills/s3k-implement-object/skill.md`
- Modify: `.claude/skills/s1-implement-boss/skill.md`
- Modify: `.claude/skills/s2-implement-boss/skill.md`
- Modify: `.claude/skills/s3k-implement-boss/skill.md`

- [ ] **Step 1: Document these rules.**

- v1.6 generic capture is enabled only for classes that pass audit and parity oracle.
- Manual extras remain valid for deferred classes.
- Collections and mutable POJOs are not generically supported.
- Structural refs require `@RewindTransient(reason = "...")`.
- Dynamic spawn remains explicit.
- `AbstractPlayableSprite` keeps its public capture/restore API.
- Object/badnik/boss implementation skills require field classification before finalization: synchronization-relevant fields must remain captured, and `@RewindTransient` is reserved for structural/derived fields with a reason.

- [ ] **Step 2: Commit docs.**

```powershell
git add docs/architecture/plans/2026-05-04-rewind-framework-design.md `
        docs/architecture/plans/2026-05-06-rewind-generic-capturer-v1-6-field-inventory.md `
        .agents/skills `
        .claude/skills
git commit -m "docs(rewind): document v1.6 audit-first generic capturer"
```

Use required trailers.

---

## Final Definition of Done

- `GenericFieldCapturer` exists with qualified field keys and strict supported-type policy.
- Engine-ref annotation guard scans the real target package set recursively.
- Unsupported-field audit exists and is enabled for migrated classes.
- Existing manual extras and overrides are not deleted in v1.6; they remain authoritative fallback until targeted, long-tail, and full-suite comparisons pass.
- `AbstractPlayableSprite.captureRewindState` and `restoreRewindState` still exist.
- `SidekickCpuRewindExtra` remains nested unless a later plan moves it deliberately.
- `dynamicSpawn` continues to restore through explicit snapshot fields.
- Targeted rewind tests pass.
- Long-tail benchmark is no worse than the pre-plan baseline.
- Full-suite post-plan failure identities match the pre-plan baseline except for documented unrelated failures.

## Follow-Up Plans

| Plan | Scope | Trigger |
|---|---|---|
| v1.6.1 | Add codecs for common mutable value holders such as `SubpixelMotion.State` and `ObjectAnimationState`. | Audit inventory shows these block broad object migration. |
| v1.6.2 | Carefully support selected keyed collections with value-only entries. | A migrated class needs collection state and identity semantics are documented. |
| v1.7 | Manager-level rewind coverage. | Long-tail divergence remains after migrated object/sprite classes pass parity. |
| v1.8 | Rewind UX controls and playback integration. | Coverage is stable enough for live gameplay use. |
