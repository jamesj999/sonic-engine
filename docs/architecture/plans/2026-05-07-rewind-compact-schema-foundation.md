# Rewind Compact Schema Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a side-by-side compact schema capture path for automatic rewind state, without replacing the existing `GenericFieldCapturer` behavior yet.

**Architecture:** Build immutable per-class schemas once, then capture/restore supported fields through precomputed codecs into compact blobs. The first milestone proves primitive/value storage and schema caching while leaving identity rebinding, helper codecs, collection codecs, and object rollout as follow-up plans.

**Tech Stack:** Java 21, JUnit 5, existing `com.openggf.game.rewind` package, Maven Surefire.

---

## Context

The current generic path stores per-capture `FieldKey` lists and boxed `Object[]` values in `GenericObjectSnapshot`. That is correct enough for migration, but too allocation-heavy for broad object coverage. This plan adds a parallel package under `com.openggf.game.rewind.schema` so the existing tests and gameplay behavior remain stable while compact capture is proven.

Primary reference: `REWIND_SUPPORT_BLUEPRINT.tmp.txt`.

Do not modify object snapshots or `AbstractObjectInstance` in this plan. Integration comes after this foundation has tests.

---

## File Structure

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindFieldPolicy.java`
  - Enum for `CAPTURED`, `STRUCTURAL`, `TRANSIENT`, `DEFERRED`, `UNSUPPORTED`.

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindObjectStateBlob.java`
  - Immutable compact capture result: schema id, type, scalar data, opaque values.

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java`
  - Growable byte buffer with explicit little-endian primitive reads/writes.

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindCodec.java`
  - Codec interface for one field shape.

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
  - Codec factory for primitive, wrapper, `String`, enum, primitive arrays, enum arrays, `BitSet`, and supported records.

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindFieldPlan.java`
  - Precomputed field metadata: key, field, codec, policy.

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindClassSchema.java`
  - Immutable class capture plan and capture/restore execution.

- Create: `src/main/java/com/openggf/game/rewind/schema/RewindSchemaRegistry.java`
  - Thread-safe schema cache and monotonically assigned schema ids.

- Create: `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java`
  - Public adapter: `capture(Object)` and `restore(Object, RewindObjectStateBlob)`.

- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindStateBuffer.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindSchemaRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestCompactFieldCapturer.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestCompactFieldCapturerPolicy.java`

---

## Task 1: Compact Buffer

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindStateBuffer.java`

- [x] **Step 1: Write buffer tests**

Create `TestRewindStateBuffer` with tests for:

```java
@Test
void writesAndReadsLittleEndianPrimitives() {
    RewindStateBuffer buffer = new RewindStateBuffer();
    buffer.writeByte(0x12);
    buffer.writeBoolean(true);
    buffer.writeShort(0x3456);
    buffer.writeInt(0x789ABCDE);
    buffer.writeLong(0x0102030405060708L);
    buffer.writeFloat(Float.intBitsToFloat(0x3F800000));
    buffer.writeDouble(Double.longBitsToDouble(0x3FF0000000000000L));

    RewindStateBuffer.Reader reader = buffer.reader();
    assertEquals(0x12, reader.readByte() & 0xFF);
    assertTrue(reader.readBoolean());
    assertEquals(0x3456, reader.readShort() & 0xFFFF);
    assertEquals(0x789ABCDE, reader.readInt());
    assertEquals(0x0102030405060708L, reader.readLong());
    assertEquals(1.0f, reader.readFloat());
    assertEquals(1.0d, reader.readDouble());
}
```

Also test `toByteArray()` returns a defensive copy.

- [x] **Step 2: Run the failing buffer test**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestRewindStateBuffer" test
```

Expected: compile failure because `RewindStateBuffer` does not exist.

- [x] **Step 3: Implement `RewindStateBuffer`**

Implement a final class with:

```java
public final class RewindStateBuffer {
    public void writeByte(int value)
    public void writeBoolean(boolean value)
    public void writeShort(int value)
    public void writeInt(int value)
    public void writeLong(long value)
    public void writeFloat(float value)
    public void writeDouble(double value)
    public void writeBytes(byte[] values)
    public byte[] toByteArray()
    public Reader reader()
    public static Reader reader(byte[] data)

    public static final class Reader {
        public byte readByte()
        public boolean readBoolean()
        public short readShort()
        public int readInt()
        public long readLong()
        public float readFloat()
        public double readDouble()
        public byte[] readBytes(int length)
    }
}
```

Use a growable `byte[]`; write little-endian explicitly; do not use `ByteBuffer`.

- [x] **Step 4: Verify buffer test passes**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestRewindStateBuffer" test
```

Expected: build success.

---

## Task 2: Schema Registry And Field Planning

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindFieldPolicy.java`
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindFieldPlan.java`
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindClassSchema.java`
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindSchemaRegistry.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestRewindSchemaRegistry.java`

- [x] **Step 1: Write schema registry tests**

Create fixtures with inherited fields, static fields, Java `transient`, and `@RewindTransient`.

Required tests:

- same class returns same schema instance and id.
- fields are ordered superclass first, then declared field name.
- static/transient/annotated fields are skipped or classified non-captured.
- unsupported fields appear in `unsupportedFields()` with `FieldKey` names.

- [x] **Step 2: Run failing schema tests**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestRewindSchemaRegistry" test
```

Expected: compile failure until schema classes exist.

- [x] **Step 3: Implement schema classes**

Implement:

```java
public enum RewindFieldPolicy {
    CAPTURED,
    STRUCTURAL,
    TRANSIENT,
    DEFERRED,
    UNSUPPORTED
}
```

`RewindFieldPlan` should expose `FieldKey key()`, `Field field()`, `RewindFieldPolicy policy()`, and `boolean captured()`.

`RewindClassSchema` should expose `int schemaId()`, `Class<?> type()`, `List<RewindFieldPlan> fields()`, `List<RewindFieldPlan> capturedFields()`, and `List<RewindFieldPlan> unsupportedFields()`.

`RewindSchemaRegistry` should cache schemas in a `ConcurrentHashMap<Class<?>, RewindClassSchema>` and assign ids with an `AtomicInteger`.

- [x] **Step 4: Verify schema tests pass**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestRewindSchemaRegistry" test
```

Expected: build success.

---

## Task 3: Value Codecs And Compact Capturer

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindCodec.java`
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Create: `src/main/java/com/openggf/game/rewind/schema/RewindObjectStateBlob.java`
- Create: `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java`
- Test: `src/test/java/com/openggf/game/rewind/schema/TestCompactFieldCapturer.java`

- [x] **Step 1: Write compact round-trip tests**

Create fixtures covering:

- `int`
- `boolean`
- `long`
- `float`
- `double`
- `String`
- enum
- primitive arrays
- enum arrays
- `BitSet`
- simple record of supported primitives

Test pattern:

```java
Fixture fixture = new Fixture();
RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture);
fixture.mutate();
CompactFieldCapturer.restore(fixture, blob);
assertEquals(expectedValue, fixture.value);
```

- [x] **Step 2: Run failing compact tests**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestCompactFieldCapturer" test
```

Expected: compile failure until codecs/capturer exist.

- [x] **Step 3: Implement codecs**

`RewindCodec` should capture and restore one field:

```java
interface RewindCodec {
    void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues);
    void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex);
}
```

Use scalar bytes for primitives, wrappers, enums, primitive arrays, enum arrays, and `BitSet`. Use `opaqueValues` only for `String` and supported records in this first milestone.

- [x] **Step 4: Implement blob and capturer**

`RewindObjectStateBlob` should defensively copy `scalarData` and `opaqueValues`.

`CompactFieldCapturer.capture` should:

1. Resolve schema.
2. Fail if schema has unsupported captured fields.
3. Iterate captured field plans.
4. Return a blob with schema id, type, scalar bytes, and opaque values.

`CompactFieldCapturer.restore` should:

1. Validate target type.
2. Resolve schema.
3. Iterate captured field plans in the same order.
4. Restore values.

- [x] **Step 5: Verify compact tests pass**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestCompactFieldCapturer" test
```

Expected: build success.

---

## Task 4: Policy Rejection Tests

**Files:**
- Test: `src/test/java/com/openggf/game/rewind/schema/TestCompactFieldCapturerPolicy.java`
- Modify: schema classes as needed.

- [x] **Step 1: Write policy tests**

Required cases:

- final primitive/String/enum fields are structural and do not block capture.
- mutable unsupported POJO field is rejected with class and field name.
- `List`, `Map`, `Set`, `Queue`, and `Deque` are rejected in this milestone.
- `@RewindDeferred` fields are not captured and are reported as deferred, not unsupported.
- `@RewindTransient` fields are skipped.

- [x] **Step 2: Run policy tests**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestCompactFieldCapturerPolicy" test
```

Expected: failures until policy behavior is complete.

- [x] **Step 3: Implement policy behavior**

Schema planning rules for this milestone:

- `static`, Java `transient`, synthetic, and `@RewindTransient`: `TRANSIENT`
- `@RewindDeferred`: `DEFERRED`
- final primitive/String/enum: `STRUCTURAL`
- non-final supported value type: `CAPTURED`
- unsupported mutable field: `UNSUPPORTED`

- [x] **Step 4: Verify policy tests pass**

Run:

```powershell
mvn -Dmse=off "-Dtest=TestCompactFieldCapturerPolicy" test
```

Expected: build success.

---

## Task 5: Focused Regression Run

**Files:**
- No new files.

- [x] **Step 1: Run all schema tests**

```powershell
mvn -Dmse=off "-Dtest=TestRewindStateBuffer,TestRewindSchemaRegistry,TestCompactFieldCapturer,TestCompactFieldCapturerPolicy" test
```

Expected: build success.

- [x] **Step 2: Run rewind suite**

```powershell
mvn -Dmse=off "-Dtest=*Rewind*" test
```

Expected: build success. Existing disabled torture/benchmark skips remain expected.

- [x] **Step 3: Update blueprint if implementation differs**

If class names, storage format, or policy behavior changed during implementation, update:

```text
REWIND_SUPPORT_BLUEPRINT.tmp.txt
```

Do not expand this plan into reference rebinding or object rollout.
