package com.openggf.game.rewind;

import com.openggf.game.rewind.snapshot.GenericObjectSnapshot;
import com.openggf.game.rewind.snapshot.LevelSnapshot;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.rewind.schema.RewindObjectStateBlob;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reusable per-key diff helper for {@link CompositeSnapshot} comparisons.
 *
 * <p>Walks records, primitive arrays, object arrays, lists, and maps recursively
 * and emits path-based diff strings ("oscillation.lastFrame: A=1140 B=1200") for
 * each differing leaf. Capped at 20 diff lines per key to keep output bounded.
 *
 * <p>Custom comparators for {@code level} and {@code object-manager} keys
 * skip restore-side cosmetic divergence (epoch counter, peak slot count, dirty
 * flags) that is independent of replay correctness. Originally extracted from
 * the test-scope {@code RewindBenchmark} helpers so torture tests can reuse them.
 */
public final class RewindSnapshotDiff {

    private RewindSnapshotDiff() {}

    /**
     * Returns a list of human-readable mismatch strings for a single key, or
     * an empty list if the values agree under the key's content-equality rule.
     */
    public static List<String> diffKey(String key, Object av, Object bv) {
        List<String> diffs = new ArrayList<>();
        if (av == null && bv == null) return diffs;
        if (av == null || bv == null) {
            diffs.add(key + ": A=" + av + " B=" + bv);
            return diffs;
        }
        if (keyEquals(key, av, bv)) return diffs;
        if ("object-manager".equals(key)) {
            collectObjectManagerDiffs(key, av, bv, diffs);
            return diffs;
        }
        collectDiffs(key, av, bv, diffs);
        return diffs;
    }

    /**
     * Object-manager diff: compares slots / childSpawns / dynamicObjects by
     * slot identity rather than list position. The {@code activeObjects}
     * IdentityHashMap that backs {@code slots()} has unspecified iteration
     * order, so two captures of the same logical state may produce slot lists
     * in different order. Bucketing by slotIndex makes the diff order-stable.
     */
    private static void collectObjectManagerDiffs(String path, Object av, Object bv,
                                                    List<String> diffs) {
        if (!(av instanceof ObjectManagerSnapshot oa)
                || !(bv instanceof ObjectManagerSnapshot ob)) {
            collectDiffs(path, av, bv, diffs);
            return;
        }
        if (!Arrays.equals(oa.usedSlotsBits(), ob.usedSlotsBits())) {
            diffs.add(path + ".usedSlotsBits differs");
            collectUsedSlotBitDiffs(path + ".usedSlotsBits", oa.usedSlotsBits(), ob.usedSlotsBits(), diffs);
        }
        if (oa.frameCounter() != ob.frameCounter()) {
            diffs.add(path + ".frameCounter: A=" + oa.frameCounter() + " B=" + ob.frameCounter());
        }
        if (oa.vblaCounter() != ob.vblaCounter()) {
            diffs.add(path + ".vblaCounter: A=" + oa.vblaCounter() + " B=" + ob.vblaCounter());
        }
        Map<Integer, ObjectManagerSnapshot.PerSlotEntry> aBySlot = new HashMap<>();
        for (var e : oa.slots()) aBySlot.put(e.slotIndex(), e);
        Map<Integer, ObjectManagerSnapshot.PerSlotEntry> bBySlot = new HashMap<>();
        for (var e : ob.slots()) bBySlot.put(e.slotIndex(), e);
        if (aBySlot.size() != oa.slots().size() || bBySlot.size() != ob.slots().size()) {
            diffs.add(path + ".slots: duplicate slotIndex detected");
        }
        java.util.Set<Integer> allSlots = new java.util.TreeSet<>();
        allSlots.addAll(aBySlot.keySet());
        allSlots.addAll(bBySlot.keySet());
        for (int slotIdx : allSlots) {
            if (diffs.size() >= 20) break;
            var ea = aBySlot.get(slotIdx);
            var eb = bBySlot.get(slotIdx);
            if (ea == null) {
                diffs.add(path + ".slot[" + slotIdx + "] missing in A (B="
                        + (eb.spawn() == null ? "<null>" : eb.spawn()) + ")");
                continue;
            }
            if (eb == null) {
                diffs.add(path + ".slot[" + slotIdx + "] missing in B (A="
                        + (ea.spawn() == null ? "<null>" : ea.spawn()) + ")");
                continue;
            }
            if (ea.spawn() != eb.spawn()) {
                diffs.add(path + ".slot[" + slotIdx + "].spawn ref differs");
            }
            if (!fieldContentEqual(ea.state(), eb.state())) {
                collectDiffs(path + ".slot[" + slotIdx + "].state",
                        ea.state(), eb.state(), diffs);
            }
        }
        // Compare childSpawns by parentSpawn identity (IdentityHashMap-backed)
        Map<com.openggf.level.objects.ObjectSpawn, int[]> aChild = new java.util.IdentityHashMap<>();
        for (var ce : oa.childSpawns()) aChild.put(ce.parentSpawn(), ce.reservedSlots());
        Map<com.openggf.level.objects.ObjectSpawn, int[]> bChild = new java.util.IdentityHashMap<>();
        for (var ce : ob.childSpawns()) bChild.put(ce.parentSpawn(), ce.reservedSlots());
        if (aChild.size() != bChild.size()) {
            diffs.add(path + ".childSpawns count: A=" + aChild.size() + " B=" + bChild.size());
        }
        for (var p : aChild.keySet()) {
            int[] av2 = aChild.get(p);
            int[] bv2 = bChild.get(p);
            if (bv2 == null) {
                diffs.add(path + ".childSpawns[" + p + "] missing in B");
                continue;
            }
            if (!Arrays.equals(av2, bv2)) {
                diffs.add(path + ".childSpawns[" + p + "].reservedSlots differs");
            }
        }
        // Dynamic objects: bucket by slotIndex so insertion-order divergence
        // (e.g. Shield re-spawned by post-restore callback after generic restore
        // for non-deferred dynamics) doesn't masquerade as content divergence.
        // Both runs may legitimately end up with the same {slot -> instance}
        // set in different list positions; only true content/slot/membership
        // divergence is reported.
        if (!fieldContentEqual(oa.dynamicObjects(), ob.dynamicObjects())) {
            Map<Integer, ObjectManagerSnapshot.DynamicObjectEntry> aDyn = new HashMap<>();
            for (var de : oa.dynamicObjects()) aDyn.put(de.slotIndex(), de);
            Map<Integer, ObjectManagerSnapshot.DynamicObjectEntry> bDyn = new HashMap<>();
            for (var de : ob.dynamicObjects()) bDyn.put(de.slotIndex(), de);
            if (aDyn.size() != oa.dynamicObjects().size()
                    || bDyn.size() != ob.dynamicObjects().size()) {
                diffs.add(path + ".dynamicObjects: duplicate slotIndex detected");
            }
            java.util.Set<Integer> allDyn = new java.util.TreeSet<>();
            allDyn.addAll(aDyn.keySet());
            allDyn.addAll(bDyn.keySet());
            for (int slotIdx : allDyn) {
                if (diffs.size() >= 20) break;
                var ea = aDyn.get(slotIdx);
                var eb = bDyn.get(slotIdx);
                if (ea == null) {
                    diffs.add(path + ".dynamic[" + slotIdx + "] missing in A (B=" + eb + ")");
                    continue;
                }
                if (eb == null) {
                    diffs.add(path + ".dynamic[" + slotIdx + "] missing in B (A=" + ea + ")");
                    continue;
                }
                if (!ea.className().equals(eb.className())) {
                    diffs.add(path + ".dynamic[" + slotIdx + "].className: A="
                            + ea.className() + " B=" + eb.className());
                    continue;
                }
                if (!fieldContentEqual(ea.spawn(), eb.spawn())) {
                    collectDiffs(path + ".dynamic[" + slotIdx + "].spawn",
                            ea.spawn(), eb.spawn(), diffs);
                }
                if (!fieldContentEqual(ea.state(), eb.state())) {
                    collectDiffs(path + ".dynamic[" + slotIdx + "].state",
                            ea.state(), eb.state(), diffs);
                }
            }
        }
        if (!fieldContentEqual(oa.placement(), ob.placement())) {
            collectDiffs(path + ".placement", oa.placement(), ob.placement(), diffs);
        }
        if (!fieldContentEqual(oa.solidContactRiding(), ob.solidContactRiding())) {
            collectDiffs(path + ".solidContactRiding", oa.solidContactRiding(),
                    ob.solidContactRiding(), diffs);
        }
    }

    /**
     * Per-key equality. Custom comparators for keys whose snapshot records
     * contain non-content-equal references; default uses a record-aware deep
     * equality that handles array fields via Arrays.equals (Java records
     * auto-generate .equals() with array reference equality, which makes
     * naive .equals() report false negatives for any record with array fields).
     */
    public static boolean keyEquals(String key, Object a, Object b) {
        return switch (key) {
            case "level" -> compareLevel(a, b);
            case "object-manager" -> compareObjectManager(a, b);
            default -> recordsContentEqual(a, b);
        };
    }

    /**
     * Walks two values recursively and collects path-based diff strings for
     * each differing leaf. Caps at 20 diffs to keep output bounded.
     */
    static void collectDiffs(String path, Object av, Object bv, List<String> diffs) {
        if (diffs.size() >= 20) return;
        if (av == bv) return;
        if (av == null || bv == null) {
            diffs.add(path + ": A=" + av + " B=" + bv);
            return;
        }
        if (av.getClass() != bv.getClass()) {
            diffs.add(path + ": class A=" + av.getClass().getSimpleName()
                    + " B=" + bv.getClass().getSimpleName());
            return;
        }
        Class<?> cls = av.getClass();
        if (av instanceof GenericObjectSnapshot ga && bv instanceof GenericObjectSnapshot gb) {
            collectGenericObjectSnapshotDiffs(path, ga, gb, diffs);
            return;
        }
        if (av instanceof RewindObjectStateBlob ba && bv instanceof RewindObjectStateBlob bb) {
            collectRewindObjectStateBlobDiffs(path, ba, bb, diffs);
            return;
        }
        if (cls.isRecord()) {
            for (var c : cls.getRecordComponents()) {
                try {
                    Object aV = c.getAccessor().invoke(av);
                    Object bV = c.getAccessor().invoke(bv);
                    if (!fieldContentEqual(aV, bV)) {
                        collectDiffs(path + "." + c.getName(), aV, bV, diffs);
                    }
                } catch (ReflectiveOperationException ignored) {}
            }
            return;
        }
        if (cls.isArray()) {
            int len = java.lang.reflect.Array.getLength(av);
            int blen = java.lang.reflect.Array.getLength(bv);
            if (len != blen) {
                diffs.add(path + ": length A=" + len + " B=" + blen);
                return;
            }
            for (int i = 0; i < len; i++) {
                Object ai = java.lang.reflect.Array.get(av, i);
                Object bi = java.lang.reflect.Array.get(bv, i);
                if (!fieldContentEqual(ai, bi)) {
                    collectDiffs(path + "[" + i + "]", ai, bi, diffs);
                }
            }
            return;
        }
        if (av instanceof List<?> al && bv instanceof List<?> bl) {
            if (al.size() != bl.size()) {
                diffs.add(path + ": list-length A=" + al.size() + " B=" + bl.size());
                return;
            }
            for (int i = 0; i < al.size(); i++) {
                Object ai = al.get(i);
                Object bi = bl.get(i);
                if (!fieldContentEqual(ai, bi)) {
                    collectDiffs(path + "[" + i + "]", ai, bi, diffs);
                }
            }
            return;
        }
        if (av instanceof java.util.Map<?, ?> am && bv instanceof java.util.Map<?, ?> bm) {
            if (!am.keySet().equals(bm.keySet())) {
                diffs.add(path + ": map-keys A=" + am.keySet() + " B=" + bm.keySet());
                return;
            }
            for (Object key : am.keySet()) {
                Object ai = am.get(key);
                Object bi = bm.get(key);
                if (!fieldContentEqual(ai, bi)) {
                    collectDiffs(path + "." + key, ai, bi, diffs);
                }
            }
            return;
        }
        // Leaf scalar / other
        diffs.add(path + ": A=" + av + " B=" + bv);
    }

    /**
     * Diffs two {@link GenericObjectSnapshot} instances by reporting each
     * differing field via its declared {@code FieldKey} (class name + field
     * name) instead of a bare {@code values[i]} index. Keeps the framework
     * diff output usefully diagnostic so the next investigation step can
     * land on the actual field rather than an opaque array slot.
     */
    private static void collectGenericObjectSnapshotDiffs(String path,
                                                            GenericObjectSnapshot av,
                                                            GenericObjectSnapshot bv,
                                                            List<String> diffs) {
        if (!av.type().equals(bv.type())) {
            diffs.add(path + ".type: A=" + av.type().getName()
                    + " B=" + bv.type().getName());
            return;
        }
        if (!av.keys().equals(bv.keys())) {
            diffs.add(path + ".keys differ: A=" + av.keys() + " B=" + bv.keys());
            return;
        }
        Object[] avs = av.values();
        Object[] bvs = bv.values();
        for (int i = 0; i < avs.length && diffs.size() < 20; i++) {
            if (!fieldContentEqual(avs[i], bvs[i])) {
                String key = av.keys().get(i).toString();
                collectDiffs(path + "{" + av.type().getSimpleName() + "}." + key,
                        avs[i], bvs[i], diffs);
            }
        }
    }

    private static void collectUsedSlotBitDiffs(String path, long[] av, long[] bv, List<String> diffs) {
        java.util.BitSet aBits = java.util.BitSet.valueOf(av);
        java.util.BitSet bBits = java.util.BitSet.valueOf(bv);
        java.util.BitSet onlyA = (java.util.BitSet) aBits.clone();
        onlyA.andNot(bBits);
        java.util.BitSet onlyB = (java.util.BitSet) bBits.clone();
        onlyB.andNot(aBits);
        appendBitSetDiff(path + ".onlyA", onlyA, diffs);
        appendBitSetDiff(path + ".onlyB", onlyB, diffs);
    }

    private static void appendBitSetDiff(String path, java.util.BitSet bits, List<String> diffs) {
        if (bits.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(path).append(": ");
        int emitted = 0;
        for (int bit = bits.nextSetBit(0); bit >= 0 && emitted < 12; bit = bits.nextSetBit(bit + 1)) {
            if (emitted > 0) {
                sb.append(", ");
            }
            sb.append(bit);
            emitted++;
        }
        if (bits.cardinality() > emitted) {
            sb.append(", ... (").append(bits.cardinality()).append(" total)");
        }
        diffs.add(sb.toString());
    }

    private static void collectRewindObjectStateBlobDiffs(String path,
                                                            RewindObjectStateBlob av,
                                                            RewindObjectStateBlob bv,
                                                            List<String> diffs) {
        if (av.schemaId() != bv.schemaId()) {
            diffs.add(path + ".schemaId: A=" + av.schemaId() + " B=" + bv.schemaId());
            return;
        }
        if (av.type() != bv.type()) {
            diffs.add(path + ".type: A=" + av.type().getName() + " B=" + bv.type().getName());
            return;
        }
        byte[] scalarA = av.scalarData();
        byte[] scalarB = bv.scalarData();
        if (!Arrays.equals(scalarA, scalarB)) {
            diffs.add(path + ".type: " + av.type().getName());
            if (scalarA.length != scalarB.length) {
                diffs.add(path + ".scalarData.length: A=" + scalarA.length + " B=" + scalarB.length);
                return;
            }
            for (int i = 0; i < scalarA.length && diffs.size() < 20; i++) {
                if (scalarA[i] != scalarB[i]) {
                    diffs.add(path + ".scalarData[" + i + "]: A="
                            + unsignedHex(scalarA[i]) + " B=" + unsignedHex(scalarB[i]));
                }
            }
        }
        Object[] opaqueA = av.opaqueValues();
        Object[] opaqueB = bv.opaqueValues();
        if (!fieldContentEqual(opaqueA, opaqueB)) {
            collectDiffs(path + ".opaqueValues", opaqueA, opaqueB, diffs);
        }
    }

    private static String unsignedHex(byte value) {
        return String.format("0x%02X", value & 0xFF);
    }

    /**
     * Deep content-equality for arbitrary records, with proper handling for
     * primitive arrays, object arrays (deep-equals), and nested records.
     */
    private static boolean recordsContentEqual(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getClass() != b.getClass()) return false;
        Class<?> cls = a.getClass();
        if (!cls.isRecord()) return Objects.equals(a, b);
        for (var component : cls.getRecordComponents()) {
            try {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                Object av = accessor.invoke(a);
                Object bv = accessor.invoke(b);
                if (!fieldContentEqual(av, bv)) return false;
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(
                        "Failed to read record component " + component.getName(), e);
            }
        }
        return true;
    }

    private static boolean fieldContentEqual(Object av, Object bv) {
        if (av == bv) return true;
        if (av == null || bv == null) return false;
        Class<?> cls = av.getClass();
        if (cls.isArray()) {
            Class<?> elem = cls.getComponentType();
            if (elem == byte.class)    return Arrays.equals((byte[]) av,    (byte[]) bv);
            if (elem == short.class)   return Arrays.equals((short[]) av,   (short[]) bv);
            if (elem == int.class)     return Arrays.equals((int[]) av,     (int[]) bv);
            if (elem == long.class)    return Arrays.equals((long[]) av,    (long[]) bv);
            if (elem == float.class)   return Arrays.equals((float[]) av,   (float[]) bv);
            if (elem == double.class)  return Arrays.equals((double[]) av,  (double[]) bv);
            if (elem == char.class)    return Arrays.equals((char[]) av,    (char[]) bv);
            if (elem == boolean.class) return Arrays.equals((boolean[]) av, (boolean[]) bv);
            Object[] aa = (Object[]) av;
            Object[] ba = (Object[]) bv;
            if (aa.length != ba.length) return false;
            for (int i = 0; i < aa.length; i++) {
                if (!fieldContentEqual(aa[i], ba[i])) return false;
            }
            return true;
        }
        if (cls.isRecord()) return recordsContentEqual(av, bv);
        if (av instanceof List<?> al && bv instanceof List<?> bl) {
            if (al.size() != bl.size()) return false;
            for (int i = 0; i < al.size(); i++) {
                if (!fieldContentEqual(al.get(i), bl.get(i))) return false;
            }
            return true;
        }
        if (av instanceof java.util.Map<?, ?> am && bv instanceof java.util.Map<?, ?> bm) {
            if (!am.keySet().equals(bm.keySet())) return false;
            for (Object key : am.keySet()) {
                if (!fieldContentEqual(am.get(key), bm.get(key))) return false;
            }
            return true;
        }
        return Objects.equals(av, bv);
    }

    private static boolean compareLevel(Object a, Object b) {
        if (!(a instanceof LevelSnapshot la) || !(b instanceof LevelSnapshot lb)) {
            return false;
        }
        // Epoch is a restore-side copy-on-write generation counter. Multiple
        // seeks can legitimately advance it beyond the original forward run
        // while the level content remains identical.
        return Arrays.equals(la.blocks(), lb.blocks())
            && Arrays.equals(la.chunks(), lb.chunks())
            && Arrays.equals(la.mapData(), lb.mapData());
    }

    /**
     * Compare ObjectManager snapshots ignoring slot list ORDER. The
     * {@code activeObjects} map backing the slots list is an IdentityHashMap
     * with unspecified iteration order, so two captures of the same logical
     * state can produce slot lists in different order. We index slots,
     * childSpawns, and dynamicObjects by their identity keys before content
     * comparison.
     */
    private static boolean compareObjectManager(Object a, Object b) {
        if (!(a instanceof ObjectManagerSnapshot oa)
                || !(b instanceof ObjectManagerSnapshot ob)) {
            return false;
        }
        if (!Arrays.equals(oa.usedSlotsBits(), ob.usedSlotsBits())) return false;
        if (oa.frameCounter() != ob.frameCounter()) return false;
        if (oa.vblaCounter() != ob.vblaCounter()) return false;
        Map<Integer, ObjectManagerSnapshot.PerSlotEntry> aBySlot = new HashMap<>();
        for (var e : oa.slots()) aBySlot.put(e.slotIndex(), e);
        Map<Integer, ObjectManagerSnapshot.PerSlotEntry> bBySlot = new HashMap<>();
        for (var e : ob.slots()) bBySlot.put(e.slotIndex(), e);
        if (!aBySlot.keySet().equals(bBySlot.keySet())) return false;
        for (int slot : aBySlot.keySet()) {
            var ea = aBySlot.get(slot);
            var eb = bBySlot.get(slot);
            if (ea.spawn() != eb.spawn()) return false;
            if (!fieldContentEqual(ea.state(), eb.state())) return false;
        }
        // ChildSpawns: identity-map by parentSpawn.
        Map<com.openggf.level.objects.ObjectSpawn, int[]> aChild = new java.util.IdentityHashMap<>();
        for (var ce : oa.childSpawns()) aChild.put(ce.parentSpawn(), ce.reservedSlots());
        Map<com.openggf.level.objects.ObjectSpawn, int[]> bChild = new java.util.IdentityHashMap<>();
        for (var ce : ob.childSpawns()) bChild.put(ce.parentSpawn(), ce.reservedSlots());
        if (aChild.size() != bChild.size()) return false;
        for (var p : aChild.keySet()) {
            int[] av = aChild.get(p);
            int[] bv = bChild.get(p);
            if (!Arrays.equals(av, bv)) return false;
        }
        // DynamicObjects: bucket by slotIndex so insertion-order divergence
        // doesn't masquerade as content divergence (mirrors collectObjectManagerDiffs).
        Map<Integer, ObjectManagerSnapshot.DynamicObjectEntry> aDyn = new HashMap<>();
        for (var de : oa.dynamicObjects()) aDyn.put(de.slotIndex(), de);
        Map<Integer, ObjectManagerSnapshot.DynamicObjectEntry> bDyn = new HashMap<>();
        for (var de : ob.dynamicObjects()) bDyn.put(de.slotIndex(), de);
        if (!aDyn.keySet().equals(bDyn.keySet())) return false;
        for (int slot : aDyn.keySet()) {
            var ea = aDyn.get(slot);
            var eb = bDyn.get(slot);
            if (!ea.className().equals(eb.className())) return false;
            if (!fieldContentEqual(ea.spawn(), eb.spawn())) return false;
            if (!fieldContentEqual(ea.state(), eb.state())) return false;
        }
        return fieldContentEqual(oa.placement(), ob.placement())
                && fieldContentEqual(oa.solidContactRiding(), ob.solidContactRiding());
    }
}
