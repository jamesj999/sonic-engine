package com.openggf.game.rewind;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3A — in-place object restore tests.
 *
 * <p>{@code ObjectManager}'s rewind restore reuses the live instance for a
 * snapshot slot entry when it matches on (spawn identity, slot index, class),
 * the class passes {@link ObjectManager#isRewindInPlaceReuseSafeClass}, and one
 * in-restore reconstruction of the class has been observed without construction
 * side effects (child spawns / child-slot reservations). Everything else —
 * missing entries, extra live objects, class or slot mismatches, audited-unsafe
 * classes — keeps the destroy/recreate path.
 *
 * <h2>Step 3 audit of non-captured fields (recorded outcome)</h2>
 *
 * <p>The destroy/recreate path resets every non-captured field (including
 * {@code @RewindTransient} ones) to its constructor value on each restore;
 * in-place reuse keeps the live value. The audit therefore classifies every
 * field declared below {@link AbstractObjectInstance} that the default capture
 * path does not restore:
 *
 * <ul>
 *   <li><b>(a) derivable / self-correcting:</b>
 *       {@code AbstractBadnikInstance.cachedDebugLabel} /
 *       {@code cachedDebugAnimFrame} / {@code cachedDebugFacingLeft} — pure
 *       debug-render caches; the label regenerates whenever
 *       {@code animFrame}/{@code facingLeft} change, so a kept cache is only
 *       kept when it is already correct. These are the only hand-allowed
 *       mutable non-captured fields and are excluded from the reflective
 *       equivalence comparison below.</li>
 *   <li><b>(b) construction-constant:</b> {@code final} fields holding
 *       immutable values (primitives, wrappers, {@code String}, enums) or
 *       structural references the constructor derives deterministically
 *       (renderer handles, sheet/mapping data, services, spawn). Reuse keeps
 *       the identical value a reconstruction would compute. Allowed.</li>
 *   <li><b>(c) frame-coupled mutable state:</b> any non-final non-captured
 *       field (e.g. {@code LbzCupElevatorInstance.attachChild}/{@code baseChild},
 *       {@code Mhz1CutsceneKnucklesInstance.savedPaletteLine1}), plus
 *       non-captured {@code final} collections/maps/arrays and
 *       {@code final} {@code ObjectInstance}/{@code PlayableEntity} references
 *       (cross-instance identity that restore may recreate). Per the task's
 *       allowance these classes are <b>forced onto the recreate fallback</b>
 *       rather than given bespoke reset hooks. Constructors with global side
 *       effects invisible to the field audit (HCZWaterRush writes
 *       {@code HCZBreakableBarState}) are pinned via an explicit denylist in
 *       {@code ObjectManager}.</li>
 * </ul>
 *
 * <p>Classes with concrete {@code captureRewindState}/{@code restoreRewindState}
 * overrides (all bespoke badnik/boss extras) are also forced to recreate:
 * their hand-written capture coverage is not reflectively provable. The
 * resulting audited-safe population is the default-capture object/badnik
 * subclasses with only captured + final construction-constant state —
 * 431 reuse-safe vs 345 recreate-fallback concrete classes at audit time
 * (2026-06-11); {@link #auditSummaryAndKnownClassifications()} prints the live
 * counts for the loaded classpath and pins known classifications. In EHZ1 the
 * safe set includes springs, spikes, bridges/stakes, checkpoints, waterfalls,
 * animals, and explosions, while the legacy-extra badniks (Masher, Buzzer,
 * Coconuts), monitors, and ARZ platforms recreate. A class additionally only
 * reuses after one observed in-restore reconstruction without construction
 * side effects, so the first restore of a session always recreates.
 *
 * <p>Equivalence is verified end-to-end by
 * {@link #reusedInstancesMatchFreshConstructionFieldForField()}: the same
 * snapshot is restored once with in-place reuse and once via the legacy
 * destroy/recreate path (test hook
 * {@link ObjectManager#setRewindInPlaceRestoreEnabledForTest}), then every
 * instance pair is compared reflectively over its complete field surface —
 * including fields the snapshot does not capture.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestRewindInPlaceObjectRestore {

    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s2/ehz1_fullrun");

    /**
     * BK2 emulation frame where the EHZ1 fullrun trace recording begins
     * ({@code bk2_frame_offset} in the trace's metadata.json).
     */
    private static final int BK2_FRAME_OFFSET = 899;

    private static final int ADVANCE_BEFORE_CAPTURE = 300;
    private static final int ADVANCE_AFTER_CAPTURE = 60;
    private static final int ADVANCE_FOR_MEMBERSHIP_CHANGE = 600;
    private static final int CAPTURE_PROBE_INTERVAL = 30;
    private static final int MAX_ADVANCE_FOR_SAFE_POPULATION = 1200;

    /**
     * Mutable non-captured fields hand-audited as category (a)
     * (self-correcting derived caches); excluded from the reflective
     * equivalence comparison. See the class Javadoc.
     */
    private static final Set<String> EQUIVALENCE_EXEMPT_FIELDS = Set.of(
            "com.openggf.level.objects.AbstractBadnikInstance#cachedDebugLabel",
            "com.openggf.level.objects.AbstractBadnikInstance#cachedDebugAnimFrame",
            "com.openggf.level.objects.AbstractBadnikInstance#cachedDebugFacingLeft");

    /**
     * Pinned allowlist of declared types of final non-captured reference
     * fields across all reuse-approved classes (the population the audit's
     * final-structural fallthrough approves). Each entry was classified as
     * construction-constant: shared managers/renderers/services, immutable
     * config records, structural art/mapping data, stateless functional
     * handles, or write-before-use scratch holders. A new type appearing here
     * fails {@link #auditSummaryAndKnownClassifications()} and must be
     * deliberately classified before the pin is updated.
     */
    private static final Set<String> APPROVED_FINAL_REFERENCE_FIELD_TYPES = Set.of(
            // Immutable per-zone config records resolved from the spawn/zone.
            "com.openggf.game.sonic2.objects.CollapsingPlatformObjectInstance$ZoneConfig",
            "com.openggf.game.sonic3k.objects.BreakableWallObjectInstance$ZoneConfig",
            "com.openggf.game.sonic3k.objects.FloatingPlatformObjectInstance$ZoneConfig",
            "com.openggf.game.sonic3k.objects.HCZTwistingLoopObjectInstance$LoopDef",
            "com.openggf.game.sonic3k.objects.Sonic3kCollapsingPlatformObjectInstance$ZoneConfig",
            "com.openggf.level.objects.SolidObjectParams",
            // Shared runtime-owned managers/controllers/workspaces: both paths
            // resolve the same live instance (a == b short-circuit applies).
            "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController",
            "com.openggf.game.sonic3k.events.Sonic3kICZEvents",
            "com.openggf.game.sonic3k.events.Sonic3kMHZEvents",
            "com.openggf.level.objects.ObjectRenderManager",
            // Structural art/render data derived deterministically from ROM.
            "com.openggf.level.Pattern",
            "com.openggf.level.render.PatternSpriteRenderer",
            "com.openggf.level.render.SpriteMappingPiece",
            "com.openggf.sprites.render.PlayerSpriteRenderer",
            // Write-before-use scratch holders rebuilt each frame before any read.
            "com.openggf.level.PatternDesc",
            "com.openggf.level.objects.SubpixelMotion$State",
            // Stateless functional handles installed at construction.
            "com.openggf.level.objects.DestructionEffects$AnimalFactory",
            "com.openggf.level.objects.DestructionEffects$PointsFactory",
            "java.util.function.BooleanSupplier",
            "java.util.function.IntSupplier");

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    /**
     * Step 1: identity reuse on matching restore + full-state equivalence
     * against the fresh-construction path.
     */
    @Test
    void reusedInstancesMatchFreshConstructionFieldForField() throws Exception {
        HeadlessTestFixture fixture = bootEhz1();
        ObjectManager om = GameServices.level().getObjectManager();
        RewindRegistry registry = rewindRegistry();

        // Advance until the active population contains at least one entry of an
        // audited reuse-safe class (EHZ1 mixes legacy-override badniks, which
        // always recreate, with default-capture objects such as springs,
        // spikes, bridges, and checkpoints).
        CompositeSnapshot snapA = null;
        ObjectManagerSnapshot omSnapA = null;
        for (int stepped = 0; stepped < MAX_ADVANCE_FOR_SAFE_POPULATION; stepped += CAPTURE_PROBE_INTERVAL) {
            for (int i = 0; i < CAPTURE_PROBE_INTERVAL; i++) {
                fixture.stepFrameFromRecording();
            }
            CompositeSnapshot candidate = registry.capture();
            ObjectManagerSnapshot omCandidate = (ObjectManagerSnapshot) candidate.get("object-manager");
            if (omCandidate.slots().size() >= 3 && hasReuseSafeEntry(om, omCandidate)) {
                snapA = candidate;
                omSnapA = omCandidate;
                break;
            }
        }
        Assumptions.assumeTrue(omSnapA != null,
                "Trace never produced an active population containing a reuse-safe class");
        for (ObjectManagerSnapshot.PerSlotEntry entry : omSnapA.slots()) {
            System.out.println("[equivalence population] slot=" + entry.slotIndex()
                    + " " + entry.className());
        }

        // Restore #1: observation pass — every class is reconstructed once so the
        // manager can latch construction side effects before allowing reuse.
        registry.restore(snapA);

        // Accumulate live mutation on the restored instances BEFORE the compared
        // restores. A stale non-captured field on a reused instance can only
        // diverge from a fresh construction if gameplay actually mutated it
        // after the restore point; without these frames every non-captured
        // field would still sit at its constructor value and the comparison
        // below would be vacuous (it could never fail).
        for (int i = 0; i < ADVANCE_AFTER_CAPTURE; i++) {
            fixture.stepFrameFromRecording();
        }
        Map<ObjectSpawn, ObjectInstance> beforeSecond = instancesForEntries(om, omSnapA);

        // Restore #2: matching entries of audited-safe classes must now be reused
        // in place (identical Java instance).
        registry.restore(snapA);
        Map<ObjectSpawn, ObjectInstance> afterSecond = instancesForEntries(om, omSnapA);

        int reused = 0;
        for (ObjectManagerSnapshot.PerSlotEntry entry : omSnapA.slots()) {
            ObjectInstance live = beforeSecond.get(entry.spawn());
            ObjectInstance second = afterSecond.get(entry.spawn());
            assertNotNull(second, "Every snapshot entry must be live after restore: "
                    + entry.className());
            assertEquals(entry.className(), second.getClass().getName(),
                    "Restored instance class must match the captured class");
            if (live == second) {
                reused++;
                assertTrue(ObjectManager.isRewindInPlaceReuseSafeClass(second.getClass()),
                        "Reused an instance of a class the audit did not approve: "
                                + second.getClass().getName());
            }
        }
        assertTrue(reused > 0,
                "Expected at least one in-place reuse on a same-snapshot second restore; "
                        + "reuse gating appears to reject everything");

        // Restore #3: legacy destroy/recreate reference path.
        om.setRewindInPlaceRestoreEnabledForTest(false);
        try {
            registry.restore(snapA);
        } finally {
            om.setRewindInPlaceRestoreEnabledForTest(true);
        }
        Map<ObjectSpawn, ObjectInstance> afterRecreate = instancesForEntries(om, omSnapA);

        List<String> mismatches = new ArrayList<>();
        for (ObjectManagerSnapshot.PerSlotEntry entry : omSnapA.slots()) {
            ObjectInstance reusedInst = afterSecond.get(entry.spawn());
            ObjectInstance freshInst = afterRecreate.get(entry.spawn());
            assertNotNull(freshInst, "Recreate path must restore every entry");
            assertNotSame(reusedInst, freshInst,
                    "Disabling the reuse hook must force fresh construction");
            compareCompleteFieldState(reusedInst, freshInst, mismatches);
        }
        assertTrue(mismatches.isEmpty(),
                "In-place restored instances diverge from fresh construction on "
                        + mismatches.size() + " field(s):\n  "
                        + String.join("\n  ", mismatches));
    }

    /**
     * Step 2: despawn/spawn boundary — membership and class mismatches must
     * fall back to construction, and extra live objects must be dropped
     * exactly as the recreate path dropped them.
     */
    @Test
    void membershipMismatchFallsBackToRecreateAndDropsExtras() throws Exception {
        HeadlessTestFixture fixture = bootEhz1();
        for (int i = 0; i < ADVANCE_BEFORE_CAPTURE; i++) {
            fixture.stepFrameFromRecording();
        }

        ObjectManager om = GameServices.level().getObjectManager();
        RewindRegistry registry = rewindRegistry();
        CompositeSnapshot snapA = registry.capture();
        ObjectManagerSnapshot omSnapA = (ObjectManagerSnapshot) snapA.get("object-manager");
        assertNotNull(omSnapA);

        for (int i = 0; i < ADVANCE_FOR_MEMBERSHIP_CHANGE; i++) {
            fixture.stepFrameFromRecording();
        }

        ObjectManagerSnapshot omSnapLive =
                (ObjectManagerSnapshot) registry.capture().get("object-manager");
        Set<ObjectSpawn> snapshotSpawns = identitySet();
        for (ObjectManagerSnapshot.PerSlotEntry entry : omSnapA.slots()) {
            snapshotSpawns.add(entry.spawn());
        }
        Set<ObjectSpawn> liveSpawns = identitySet();
        for (ObjectManagerSnapshot.PerSlotEntry entry : omSnapLive.slots()) {
            liveSpawns.add(entry.spawn());
        }
        boolean hasMissing = snapshotSpawns.stream().anyMatch(spawn -> !liveSpawns.contains(spawn));
        boolean hasExtra = liveSpawns.stream().anyMatch(spawn -> !snapshotSpawns.contains(spawn));
        Assumptions.assumeTrue(hasMissing && hasExtra,
                "Trace advance did not change object membership; cannot exercise the boundary");

        Set<ObjectInstance> liveInstances = identitySet();
        liveInstances.addAll(om.getActiveObjects());

        registry.restore(snapA);

        // Every snapshot entry is live again; entries absent from the live set
        // were freshly constructed.
        for (ObjectManagerSnapshot.PerSlotEntry entry : omSnapA.slots()) {
            ObjectInstance restored = om.getActiveObjectForRewind(entry.spawn());
            assertNotNull(restored,
                    "Missing snapshot entry must be reconstructed: " + entry.className());
            assertEquals(entry.className(), restored.getClass().getName());
            if (!liveSpawns.contains(entry.spawn())) {
                assertFalse(liveInstances.contains(restored),
                        "A snapshot entry not present pre-restore must be a new instance: "
                                + entry.className());
            }
            if (restored instanceof AbstractObjectInstance aoi) {
                assertEquals(entry.slotIndex(), aoi.getSlotIndex(),
                        "Restored slot index must match the snapshot entry");
            }
        }

        // Extra live objects (present pre-restore, absent from the snapshot) are
        // dropped from the active table, matching the legacy clear semantics.
        for (ObjectManagerSnapshot.PerSlotEntry entry : omSnapLive.slots()) {
            if (!snapshotSpawns.contains(entry.spawn())) {
                assertNull(om.getActiveObjectForRewind(entry.spawn()),
                        "Extra live object must be dropped on restore: " + entry.className());
            }
        }
    }

    /**
     * Step 3 audit summary: prints the reuse-safe vs recreate-fallback class
     * counts for the full discovered object population and pins known
     * classifications so the gating cannot silently widen.
     */
    @Test
    void auditSummaryAndKnownClassifications() throws Exception {
        int safe = 0;
        List<String> safeClasses = new ArrayList<>();
        List<String> recreateClasses = new ArrayList<>();
        for (Class<?> top : RewindScanSupport.discoverRuntimeOwnerClasses()) {
            for (Class<?> cls : RewindScanSupport.withNestedRuntimeOwnerClasses(top)) {
                if (!AbstractObjectInstance.class.isAssignableFrom(cls)
                        || Modifier.isAbstract(cls.getModifiers())) {
                    continue;
                }
                if (ObjectManager.isRewindInPlaceReuseSafeClass(cls)) {
                    safe++;
                    safeClasses.add(cls.getName());
                } else {
                    recreateClasses.add(cls.getName());
                }
            }
        }
        System.out.println("[in-place-restore audit] reuse-safe classes: " + safe
                + ", recreate-fallback classes: " + recreateClasses.size());

        assertTrue(safe > 0, "Audit must approve at least part of the object population");

        // Category (c) representatives must stay on the recreate fallback.
        assertFalse(ObjectManager.isRewindInPlaceReuseSafeClass(
                        Class.forName("com.openggf.game.sonic3k.objects.LbzCupElevatorInstance")),
                "LbzCupElevatorInstance holds mutable non-captured child refs (category c)");
        assertFalse(ObjectManager.isRewindInPlaceReuseSafeClass(
                        Class.forName("com.openggf.level.objects.ShieldObjectInstance")),
                "ShieldObjectInstance holds a non-captured player reference");
        // Legacy-override classes (bespoke extras) are not reflectively provable.
        assertFalse(ObjectManager.isRewindInPlaceReuseSafeClass(
                        Class.forName("com.openggf.game.sonic2.objects.badniks.MasherBadnikInstance")),
                "Masher has a hand-written rewind override and must recreate");
        // Constructor writes global zone state (HCZBreakableBarState.setState(3))
        // that the reflective audit cannot see; pinned via @RewindRecreateOnRestore
        // so a refactor of its child spawn cannot silently make it reuse-eligible.
        Class<?> hczWaterRush =
                Class.forName("com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance");
        assertFalse(ObjectManager.isRewindInPlaceReuseSafeClass(hczWaterRush),
                "HCZWaterRushObjectInstance constructor mutates global zone state and must recreate");
        // Pin the mechanism, not just the outcome: the class must carry the
        // annotation so an accidental removal fails here even if the class
        // would fail the audit for some other incidental reason.
        assertTrue(hczWaterRush.isAnnotationPresent(RewindRecreateOnRestore.class),
                "HCZWaterRushObjectInstance must stay pinned via @RewindRecreateOnRestore");

        assertEquals(APPROVED_FINAL_REFERENCE_FIELD_TYPES,
                finalNonCapturedReferenceFieldTypes(safeClasses),
                "The set of final non-captured reference field types across reuse-approved "
                        + "classes changed. The audit's structural fallthrough auto-approves "
                        + "final references, so every NEW type here must be deliberately "
                        + "classified (construction-constant => add to the pinned set; "
                        + "mutable frame-coupled helper => extend the audit/denylist) "
                        + "before updating this pin.");
    }

    /**
     * Enumerates the declared types of every final non-captured reference
     * field across the given reuse-approved classes — the exact population the
     * audit's final-structural fallthrough approves on faith.
     */
    private static Set<String> finalNonCapturedReferenceFieldTypes(List<String> safeClassNames)
            throws Exception {
        Set<String> types = new java.util.TreeSet<>();
        for (String name : safeClassNames) {
            Class<?> cls = Class.forName(name);
            Set<Field> captured = new java.util.HashSet<>(
                    GenericFieldCapturer.defaultObjectSubclassCapturedFieldsForAudit(cls));
            for (Class<?> c = cls;
                    c != null && c != AbstractObjectInstance.class;
                    c = c.getSuperclass()) {
                if (c == com.openggf.level.objects.AbstractBadnikInstance.class) {
                    continue;
                }
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || field.isSynthetic()
                            || captured.contains(field)) {
                        continue;
                    }
                    Class<?> type = field.getType();
                    if (type.isPrimitive() || type.isEnum()
                            || type == String.class
                            || type == Boolean.class || type == Byte.class
                            || type == Character.class || type == Short.class
                            || type == Integer.class || type == Long.class
                            || type == Float.class || type == Double.class) {
                        continue;
                    }
                    types.add(type.getName());
                }
            }
        }
        return types;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static HeadlessTestFixture bootEhz1() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);
        Path bk2Path = findBk2(TRACE_DIR);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + TRACE_DIR);
        return HeadlessTestFixture.builder()
                .withRecording(bk2Path)
                .withRecordingStartFrame(BK2_FRAME_OFFSET)
                .withZoneAndAct(0, 0)
                .build();
    }

    private static RewindRegistry rewindRegistry() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        assertNotNull(gameplayMode, "GameplayModeContext must be available");
        RewindRegistry registry = gameplayMode.getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be non-null");
        return registry;
    }

    private static boolean hasReuseSafeEntry(ObjectManager om, ObjectManagerSnapshot snapshot) {
        for (ObjectManagerSnapshot.PerSlotEntry entry : snapshot.slots()) {
            ObjectInstance inst = om.getActiveObjectForRewind(entry.spawn());
            if (inst != null && ObjectManager.isRewindInPlaceReuseSafeClass(inst.getClass())) {
                return true;
            }
        }
        return false;
    }

    private static Map<ObjectSpawn, ObjectInstance> instancesForEntries(
            ObjectManager om, ObjectManagerSnapshot snapshot) {
        Map<ObjectSpawn, ObjectInstance> result = new IdentityHashMap<>();
        for (ObjectManagerSnapshot.PerSlotEntry entry : snapshot.slots()) {
            ObjectInstance inst = om.getActiveObjectForRewind(entry.spawn());
            if (inst != null) {
                result.put(entry.spawn(), inst);
            }
        }
        return result;
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * Compares the complete instance field state of two objects of the same
     * class — including fields the rewind snapshot does not capture. Value
     * types (primitives, wrappers, {@code String}, enums) and primitive arrays
     * compare by value. Reference fields are compared by nullness and runtime
     * class, then recursed <em>one level</em>: the referenced objects' own
     * value-typed fields must match, so stale internal state of a kept helper
     * (the audit's final-structural leap of faith) is detected, while
     * cross-instance identity (which legitimately differs between the reuse
     * and recreate paths) is not required. Shared references (services,
     * renderers, players) short-circuit on {@code a == b}. Category (a)
     * exempt fields are skipped.
     */
    private static void compareCompleteFieldState(Object reusedInst, Object freshInst,
            List<String> mismatches) throws Exception {
        assertSame(reusedInst.getClass(), freshInst.getClass(),
                "Equivalence comparison requires identical classes");
        for (Class<?> cls = reusedInst.getClass(); cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String key = cls.getName() + "#" + field.getName();
                if (EQUIVALENCE_EXEMPT_FIELDS.contains(key)) {
                    continue;
                }
                field.setAccessible(true);
                Object a = field.get(reusedInst);
                Object b = field.get(freshInst);
                String label = reusedInst.getClass().getSimpleName() + "." + key;
                compareValues(label, field.getType(), a, b, mismatches, true);
            }
        }
    }

    private static void compareValues(String label, Class<?> declaredType,
            Object a, Object b, List<String> mismatches, boolean recurseIntoReferences)
            throws Exception {
        if (a == b) {
            return;
        }
        if (a == null || b == null) {
            mismatches.add(label + ": reused=" + describe(a) + " fresh=" + describe(b));
            return;
        }
        if (isValueLike(declaredType, a)) {
            if (!Objects.equals(a, b)) {
                mismatches.add(label + ": reused=" + describe(a) + " fresh=" + describe(b));
            }
            return;
        }
        if (a.getClass().isArray() && a.getClass().getComponentType().isPrimitive()) {
            if (!primitiveArraysEqual(a, b)) {
                mismatches.add(label + ": reused=" + describe(a) + " fresh=" + describe(b));
            }
            return;
        }
        if (a.getClass() != b.getClass()) {
            mismatches.add(label + ": class reused=" + a.getClass().getName()
                    + " fresh=" + b.getClass().getName());
            return;
        }
        if (a.getClass().isArray()) {
            if (java.lang.reflect.Array.getLength(a) != java.lang.reflect.Array.getLength(b)) {
                mismatches.add(label + ": reused=" + describe(a) + " fresh=" + describe(b));
            }
            return;
        }
        if (!recurseIntoReferences) {
            return;
        }
        // One-level recursion into the referenced objects' own field state.
        for (Class<?> cls = a.getClass(); cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                compareValues(label + "." + field.getName(), field.getType(),
                        field.get(a), field.get(b), mismatches, false);
            }
        }
    }

    private static boolean isValueLike(Class<?> declaredType, Object a) {
        return declaredType.isPrimitive() || declaredType.isEnum()
                || a instanceof Boolean || a instanceof Byte || a instanceof Character
                || a instanceof Short || a instanceof Integer || a instanceof Long
                || a instanceof Float || a instanceof Double || a instanceof String
                || a instanceof Enum<?>;
    }

    private static boolean primitiveArraysEqual(Object a, Object b) {
        if (a instanceof boolean[] x && b instanceof boolean[] y) return Arrays.equals(x, y);
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        if (a instanceof char[] x && b instanceof char[] y) return Arrays.equals(x, y);
        if (a instanceof short[] x && b instanceof short[] y) return Arrays.equals(x, y);
        if (a instanceof int[] x && b instanceof int[] y) return Arrays.equals(x, y);
        if (a instanceof long[] x && b instanceof long[] y) return Arrays.equals(x, y);
        if (a instanceof float[] x && b instanceof float[] y) return Arrays.equals(x, y);
        if (a instanceof double[] x && b instanceof double[] y) return Arrays.equals(x, y);
        return false;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value.getClass().isArray()) {
            return value.getClass().getSimpleName() + "[len=" + java.lang.reflect.Array.getLength(value) + "]";
        }
        String text = String.valueOf(value);
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private static Path findBk2(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
