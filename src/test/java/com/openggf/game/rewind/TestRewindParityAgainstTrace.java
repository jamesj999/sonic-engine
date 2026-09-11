package com.openggf.game.rewind;

import com.openggf.game.OscillationSnapshot;
import com.openggf.game.rewind.snapshot.AdvancedRenderModeSnapshot;
import com.openggf.game.rewind.snapshot.AnimatedTileChannelSnapshot;
import com.openggf.game.rewind.snapshot.CameraSnapshot;
import com.openggf.graphics.FadeManagerSnapshot;
import com.openggf.game.rewind.snapshot.GameRngSnapshot;
import com.openggf.game.rewind.snapshot.GameStateSnapshot;
import com.openggf.game.rewind.snapshot.LevelEventSnapshot;
import com.openggf.game.rewind.snapshot.LevelSnapshot;
import com.openggf.game.rewind.snapshot.MutationPipelineSnapshot;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.rewind.snapshot.PaletteOwnershipSnapshot;
import com.openggf.game.rewind.snapshot.ParallaxSnapshot;
import com.openggf.game.rewind.snapshot.PlcProgressSnapshot;
import com.openggf.game.rewind.snapshot.RingSnapshot;
import com.openggf.game.rewind.snapshot.SolidExecutionSnapshot;
import com.openggf.game.rewind.snapshot.SpecialRenderEffectSnapshot;
import com.openggf.game.rewind.snapshot.TimerManagerSnapshot;
import com.openggf.game.rewind.snapshot.WaterSystemSnapshot;
import com.openggf.game.rewind.snapshot.ZoneRuntimeSnapshot;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keystone parity test for the v1.5 rewind framework (Track J.1).
 *
 * <p>Strategy: boot S2 EHZ1 via BK2 trace, step forward N frames to produce
 * non-trivial engine state, capture a CompositeSnapshot, step forward M more
 * frames to diverge, restore the snapshot, capture again, then assert that
 * all covered keys round-trip cleanly.
 *
 * <p>Covered keys (Plan 2 atomics + composites + Plan 3 Track A–F additions):
 * <ul>
 *   <li>{@code camera}               — CameraSnapshot</li>
 *   <li>{@code gamestate}            — GameStateSnapshot (array fields)</li>
 *   <li>{@code gamerng}              — GameRngSnapshot</li>
 *   <li>{@code timermanager}         — TimerManagerSnapshot</li>
 *   <li>{@code fademanager}          — FadeManagerSnapshot</li>
 *   <li>{@code oscillation}          — OscillationSnapshot (array fields)</li>
 *   <li>{@code level}                — LevelSnapshot (epoch + arrays + CoW mapData)</li>
 *   <li>{@code object-manager}       — ObjectManagerSnapshot</li>
 *   <li>{@code parallax}             — ParallaxSnapshot (array fields)</li>
 *   <li>{@code water}                — WaterSystemSnapshot</li>
 *   <li>{@code zone-runtime}         — ZoneRuntimeSnapshot (byte[] stateBytes)</li>
 *   <li>{@code palette-ownership}    — PaletteOwnershipSnapshot (compact owner ids)</li>
 *   <li>{@code animated-tile-channels} — AnimatedTileChannelSnapshot</li>
 *   <li>{@code special-render}       — SpecialRenderEffectSnapshot</li>
 *   <li>{@code advanced-render-mode} — AdvancedRenderModeSnapshot</li>
 *   <li>{@code mutation-pipeline}    — MutationPipelineSnapshot</li>
 *   <li>{@code solid-execution}      — SolidExecutionSnapshot (empty sentinel)</li>
 *   <li>{@code level-event}          — LevelEventSnapshot (array fields)</li>
 *   <li>{@code rings}                — RingSnapshot (BitSet + arrays)</li>
 *   <li>{@code s2-plc-art}           — PlcProgressSnapshot</li>
 * </ul>
 *
 * <p>Per-game animator keys ({@code sonic1-pattern-animator},
 * {@code sonic3k-pattern-animator}, {@code aniplc-script-state}) are NOT
 * asserted here — the S2 trace does not activate them.
 *
 * <p><strong>G.2 deferred:</strong> Act-boundary parity test is deferred to a
 * follow-up. Without Track F's PlaybackController integration, replaying forward
 * THROUGH a rewind is not straightforward from a test. The G.1 property (snapshot
 * / restore round-trips for covered subsystems) exercises the same invariant.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestRewindParityAgainstTrace {

    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s2/ehz1_fullrun");

    private static final int ADVANCE_BEFORE_CAPTURE = 200;
    private static final int ADVANCE_AFTER_CAPTURE = 100;

    private static final Set<String> EXPECTED_KEYS = Set.of(
            // Plan 2 atomics
            "camera", "gamestate", "gamerng", "timermanager",
            "fademanager", "oscillation",
            // Plan 2 composites
            "level", "object-manager",
            // Plan 3 Track A — 9 runtime-framework keys
            "parallax", "water", "zone-runtime", "palette-ownership",
            "animated-tile-channels", "special-render", "advanced-render-mode",
            "mutation-pipeline", "solid-execution",
            // Plan 3 Track C — level-event
            "level-event",
            // Plan 3 Track D — ring manager
            "rings",
            // Plan 3 Track F.1 — S2 PLC art
            "s2-plc-art");

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void snapshotRestoreRoundTripsAllCoveredKeys() throws Exception {
        // 0. Skip if trace directory or BK2 missing
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);
        Path bk2Path = findBk2(TRACE_DIR);
        Assumptions.assumeTrue(bk2Path != null,
                "No .bk2 file found in " + TRACE_DIR);

        // 1. Boot S2 EHZ1 with BK2 recording (zone=0, act=0)
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2Path)
                .withZoneAndAct(0, 0)
                .build();

        // 2. Get GameplayModeContext and its RewindRegistry
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        assertNotNull(gameplayMode, "GameplayModeContext must be available after fixture build");
        RewindRegistry registry = gameplayMode.getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be non-null after attachGameplayManagers");

        // 3. Advance engine to a non-trivial state
        for (int i = 0; i < ADVANCE_BEFORE_CAPTURE; i++) {
            fixture.stepFrameFromRecording();
        }

        // 4. Capture snapshot A
        CompositeSnapshot snapA = registry.capture();
        assertNotNull(snapA, "capture() must return a non-null CompositeSnapshot");

        // 5. Assert all expected keys are present — surface as real bug if missing
        List<String> missingKeys = new ArrayList<>(EXPECTED_KEYS);
        missingKeys.removeAll(snapA.entries().keySet());
        if (!missingKeys.isEmpty()) {
            fail("RewindRegistry is missing expected keys after level load. " +
                    "Missing: " + missingKeys +
                    ", present: " + snapA.entries().keySet());
        }

        // 6. Diverge: advance M more frames so state is meaningfully different
        for (int i = 0; i < ADVANCE_AFTER_CAPTURE; i++) {
            fixture.stepFrameFromRecording();
        }

        // 7. Restore snapshot A
        registry.restore(snapA);

        // 8. Capture snapshot B (state should match A's covered content)
        CompositeSnapshot snapB = registry.capture();

        // 9. Compare all covered keys key-by-key with appropriate equality strategies
        List<String> failures = compareSnapshots(snapA, snapB);
        if (!failures.isEmpty()) {
            fail("Snapshot/restore round-trip diverged for covered keys:\n" +
                    String.join("\n", failures) +
                    "\n\nThis indicates a real coverage gap that must be " +
                    "fixed before v1.5 ships. Investigate which subsystem's restore() " +
                    "does not fully reconstruct captured state.");
        }
    }

    // -------------------------------------------------------------------------
    // Comparison helpers
    // -------------------------------------------------------------------------

    /**
     * Compares snapA and snapB key-by-key. Returns a list of human-readable
     * failure strings (empty means all round-tripped cleanly).
     */
    private static List<String> compareSnapshots(CompositeSnapshot snapA,
                                                  CompositeSnapshot snapB) {
        List<String> failures = new ArrayList<>();

        // --- Atomic records: direct .equals() is reliable for all primitive/immutable fields ---
        compareAtomic(failures, "camera", snapA, snapB, CameraSnapshot.class);
        compareAtomic(failures, "gamerng", snapA, snapB, GameRngSnapshot.class);
        compareAtomic(failures, "fademanager", snapA, snapB, FadeManagerSnapshot.class);

        // GameStateSnapshot has array fields but overrides equals via record (arrays use
        // reference equality in Java records). Use field-level comparison.
        compareGameState(failures, snapA, snapB);

        // TimerManagerSnapshot: defensive-copied map, record equality checks map equality
        compareAtomic(failures, "timermanager", snapA, snapB, TimerManagerSnapshot.class);

        // OscillationSnapshot: arrays with defensive copies; need array-equals
        compareOscillation(failures, snapA, snapB);

        // LevelSnapshot: epoch diverges by +1 after restore (bumpEpoch); arrays are shallow
        compareLevel(failures, snapA, snapB);

        // ObjectManagerSnapshot: slot inventory bits + scalar counters + per-slot entries
        compareObjectManager(failures, snapA, snapB);

        // --- Plan 3 Track A: runtime-framework keys ---

        // ParallaxSnapshot: has int[] and short[] fields — need array-equals
        compareParallax(failures, snapA, snapB);

        // WaterSystemSnapshot: Map<String, DynamicWaterEntry> — record equality works
        // (DynamicWaterEntry is a record with all-primitive fields)
        compareAtomic(failures, "water", snapA, snapB, WaterSystemSnapshot.class);

        // ZoneRuntimeSnapshot: has byte[] stateBytes
        compareZoneRuntime(failures, snapA, snapB);

        // PaletteOwnershipSnapshot: compact ids plus owner table
        comparePaletteOwnership(failures, snapA, snapB);

        // AnimatedTileChannelSnapshot: Map<String, Integer> — copyOf, equals works
        compareAtomic(failures, "animated-tile-channels", snapA, snapB,
                AnimatedTileChannelSnapshot.class);

        // SpecialRenderEffectSnapshot: captures effect refs by identity; list equality works
        compareAtomic(failures, "special-render", snapA, snapB,
                SpecialRenderEffectSnapshot.class);

        // AdvancedRenderModeSnapshot: captures mode refs by identity; list equality works
        compareAtomic(failures, "advanced-render-mode", snapA, snapB,
                AdvancedRenderModeSnapshot.class);

        // MutationPipelineSnapshot: List<LayoutMutationIntent> — queue is empty at frame boundary
        compareAtomic(failures, "mutation-pipeline", snapA, snapB,
                MutationPipelineSnapshot.class);

        // SolidExecutionSnapshot: empty sentinel record — equality always true
        compareAtomic(failures, "solid-execution", snapA, snapB, SolidExecutionSnapshot.class);

        // --- Plan 3 Track C: level-event ---
        compareLevelEvent(failures, snapA, snapB);

        // --- Plan 3 Track D: ring manager ---
        compareRings(failures, snapA, snapB);

        // --- Plan 3 Track F.1: S2 PLC art (load-epoch sentinel) ---
        // PlcProgressSnapshot is a record with a single int field — equals works
        compareAtomic(failures, "s2-plc-art", snapA, snapB, PlcProgressSnapshot.class);

        return failures;
    }

    private static <T> void compareAtomic(List<String> failures, String key,
                                           CompositeSnapshot a, CompositeSnapshot b,
                                           Class<T> type) {
        Object va = a.get(key);
        Object vb = b.get(key);
        if (va == null) { failures.add("[" + key + "] missing from snapA"); return; }
        if (vb == null) { failures.add("[" + key + "] missing from snapB"); return; }
        if (!type.isInstance(va)) {
            failures.add("[" + key + "] snapA type " + va.getClass().getSimpleName()
                    + " unexpected (expected " + type.getSimpleName() + ")");
            return;
        }
        if (!va.equals(vb)) {
            failures.add("[" + key + "] mismatch:\n  A=" + va + "\n  B=" + vb);
        }
    }

    private static void compareGameState(List<String> failures,
                                          CompositeSnapshot a, CompositeSnapshot b) {
        GameStateSnapshot ga = (GameStateSnapshot) a.get("gamestate");
        GameStateSnapshot gb = (GameStateSnapshot) b.get("gamestate");
        if (ga == null) { failures.add("[gamestate] missing from snapA"); return; }
        if (gb == null) { failures.add("[gamestate] missing from snapB"); return; }
        if (ga.score() != gb.score())
            failures.add("[gamestate] score: " + ga.score() + " vs " + gb.score());
        if (ga.lives() != gb.lives())
            failures.add("[gamestate] lives: " + ga.lives() + " vs " + gb.lives());
        if (ga.continues() != gb.continues())
            failures.add("[gamestate] continues: " + ga.continues() + " vs " + gb.continues());
        if (ga.currentSpecialStageIndex() != gb.currentSpecialStageIndex())
            failures.add("[gamestate] currentSpecialStageIndex");
        if (ga.emeraldCount() != gb.emeraldCount())
            failures.add("[gamestate] emeraldCount: " + ga.emeraldCount() + " vs " + gb.emeraldCount());
        if (!Arrays.equals(ga.gotEmeralds(), gb.gotEmeralds()))
            failures.add("[gamestate] gotEmeralds mismatch");
        if (!Arrays.equals(ga.gotSuperEmeralds(), gb.gotSuperEmeralds()))
            failures.add("[gamestate] gotSuperEmeralds mismatch");
        if (ga.currentBossId() != gb.currentBossId())
            failures.add("[gamestate] currentBossId");
        if (ga.screenShakeActive() != gb.screenShakeActive())
            failures.add("[gamestate] screenShakeActive");
        if (ga.backgroundCollisionFlag() != gb.backgroundCollisionFlag())
            failures.add("[gamestate] backgroundCollisionFlag");
        if (ga.bigRingCollected() != gb.bigRingCollected())
            failures.add("[gamestate] bigRingCollected");
        if (ga.wfzFireToggle() != gb.wfzFireToggle())
            failures.add("[gamestate] wfzFireToggle");
        if (ga.itemBonus() != gb.itemBonus())
            failures.add("[gamestate] itemBonus: " + ga.itemBonus() + " vs " + gb.itemBonus());
        if (ga.reverseGravityActive() != gb.reverseGravityActive())
            failures.add("[gamestate] reverseGravityActive");
        if (ga.collectedSpecialRings() != gb.collectedSpecialRings())
            failures.add("[gamestate] collectedSpecialRings");
        if (ga.endOfLevelActive() != gb.endOfLevelActive())
            failures.add("[gamestate] endOfLevelActive");
        if (ga.endOfLevelFlag() != gb.endOfLevelFlag())
            failures.add("[gamestate] endOfLevelFlag");
    }

    private static void compareOscillation(List<String> failures,
                                            CompositeSnapshot a, CompositeSnapshot b) {
        OscillationSnapshot oa = (OscillationSnapshot) a.get("oscillation");
        OscillationSnapshot ob = (OscillationSnapshot) b.get("oscillation");
        if (oa == null) { failures.add("[oscillation] missing from snapA"); return; }
        if (ob == null) { failures.add("[oscillation] missing from snapB"); return; }
        if (!Arrays.equals(oa.values(), ob.values()))
            failures.add("[oscillation] values mismatch");
        if (!Arrays.equals(oa.deltas(), ob.deltas()))
            failures.add("[oscillation] deltas mismatch");
        if (!Arrays.equals(oa.activeSpeeds(), ob.activeSpeeds()))
            failures.add("[oscillation] activeSpeeds mismatch");
        if (!Arrays.equals(oa.activeLimits(), ob.activeLimits()))
            failures.add("[oscillation] activeLimits mismatch");
        if (oa.control() != ob.control())
            failures.add("[oscillation] control: " + oa.control() + " vs " + ob.control());
        if (oa.lastFrame() != ob.lastFrame())
            failures.add("[oscillation] lastFrame: " + oa.lastFrame() + " vs " + ob.lastFrame());
        if (oa.suppressedUpdates() != ob.suppressedUpdates())
            failures.add("[oscillation] suppressedUpdates");
    }

    private static void compareLevel(List<String> failures,
                                      CompositeSnapshot a, CompositeSnapshot b) {
        LevelSnapshot la = (LevelSnapshot) a.get("level");
        LevelSnapshot lb = (LevelSnapshot) b.get("level");
        if (la == null) { failures.add("[level] missing from snapA"); return; }
        if (lb == null) { failures.add("[level] missing from snapB"); return; }

        // Capture establishes a COW boundary and restore establishes a fresh
        // post-restore boundary. snapA already includes the first capture bump;
        // snapB is therefore exactly two epochs later after restore + recapture.
        long expectedEpochB = la.epochAtCapture() + 2;
        if (lb.epochAtCapture() != expectedEpochB) {
            failures.add("[level] epochAtCapture: expected A+2=" + expectedEpochB
                    + " but got " + lb.epochAtCapture()
                    + " (A=" + la.epochAtCapture() + ")");
        }

        // Shallow-cloned block/chunk arrays: same Block/Chunk instances after restore
        if (!Arrays.equals(la.blocks(), lb.blocks()))
            failures.add("[level] blocks array mismatch (element-wise reference comparison)");
        if (!Arrays.equals(la.chunks(), lb.chunks()))
            failures.add("[level] chunks array mismatch (element-wise reference comparison)");

        // mapData: byte-level equality
        if (!Arrays.equals(la.mapData(), lb.mapData()))
            failures.add("[level] mapData byte mismatch (length A="
                    + la.mapData().length + " B=" + lb.mapData().length + ")");
    }

    private static void compareObjectManager(List<String> failures,
                                              CompositeSnapshot a, CompositeSnapshot b) {
        ObjectManagerSnapshot oa = (ObjectManagerSnapshot) a.get("object-manager");
        ObjectManagerSnapshot ob = (ObjectManagerSnapshot) b.get("object-manager");
        if (oa == null) { failures.add("[object-manager] missing from snapA"); return; }
        if (ob == null) { failures.add("[object-manager] missing from snapB"); return; }

        if (!Arrays.equals(oa.usedSlotsBits(), ob.usedSlotsBits()))
            failures.add("[object-manager] usedSlotsBits mismatch");
        if (oa.frameCounter() != ob.frameCounter())
            failures.add("[object-manager] frameCounter: " + oa.frameCounter() + " vs " + ob.frameCounter());
        if (oa.vblaCounter() != ob.vblaCounter())
            failures.add("[object-manager] vblaCounter: " + oa.vblaCounter() + " vs " + ob.vblaCounter());
        if (oa.currentExecSlot() != ob.currentExecSlot())
            failures.add("[object-manager] currentExecSlot: " + oa.currentExecSlot() + " vs " + ob.currentExecSlot());
        if (oa.peakSlotCount() != ob.peakSlotCount())
            failures.add("[object-manager] peakSlotCount: " + oa.peakSlotCount() + " vs " + ob.peakSlotCount());
        if (oa.bucketsDirty() != ob.bucketsDirty())
            failures.add("[object-manager] bucketsDirty: " + oa.bucketsDirty() + " vs " + ob.bucketsDirty());

        if (oa.slots().size() != ob.slots().size()) {
            failures.add("[object-manager] slot count: A=" + oa.slots().size()
                    + " B=" + ob.slots().size());
        } else {
            for (int i = 0; i < oa.slots().size(); i++) {
                ObjectManagerSnapshot.PerSlotEntry ea = oa.slots().get(i);
                ObjectManagerSnapshot.PerSlotEntry eb = ob.slots().get(i);
                if (ea.slotIndex() != eb.slotIndex()) {
                    failures.add("[object-manager] slot[" + i + "] slotIndex: "
                            + ea.slotIndex() + " vs " + eb.slotIndex());
                }
                if (ea.spawn() != eb.spawn()) {
                    failures.add("[object-manager] slot[" + i + "] spawn ref differs "
                            + "(expected same ObjectSpawn instance after in-memory restore)");
                }
                if (!ea.state().equals(eb.state())) {
                    failures.add("[object-manager] slot[" + i + "] state (slotIndex="
                            + ea.slotIndex() + "): A=" + ea.state() + " B=" + eb.state());
                }
            }
        }

        if (oa.childSpawns().size() != ob.childSpawns().size()) {
            failures.add("[object-manager] childSpawns count: A=" + oa.childSpawns().size()
                    + " B=" + ob.childSpawns().size());
        } else {
            for (int i = 0; i < oa.childSpawns().size(); i++) {
                ObjectManagerSnapshot.ChildSpawnEntry ca = oa.childSpawns().get(i);
                ObjectManagerSnapshot.ChildSpawnEntry cb = ob.childSpawns().get(i);
                if (ca.parentSpawn() != cb.parentSpawn()) {
                    failures.add("[object-manager] childSpawns[" + i + "] parentSpawn ref differs");
                }
                if (!Arrays.equals(ca.reservedSlots(), cb.reservedSlots())) {
                    failures.add("[object-manager] childSpawns[" + i + "] reservedSlots mismatch");
                }
            }
        }
    }

    private static void compareParallax(List<String> failures,
                                         CompositeSnapshot a, CompositeSnapshot b) {
        ParallaxSnapshot pa = (ParallaxSnapshot) a.get("parallax");
        ParallaxSnapshot pb = (ParallaxSnapshot) b.get("parallax");
        if (pa == null) { failures.add("[parallax] missing from snapA"); return; }
        if (pb == null) { failures.add("[parallax] missing from snapB"); return; }
    }

    private static void compareZoneRuntime(List<String> failures,
                                            CompositeSnapshot a, CompositeSnapshot b) {
        ZoneRuntimeSnapshot za = (ZoneRuntimeSnapshot) a.get("zone-runtime");
        ZoneRuntimeSnapshot zb = (ZoneRuntimeSnapshot) b.get("zone-runtime");
        if (za == null) { failures.add("[zone-runtime] missing from snapA"); return; }
        if (zb == null) { failures.add("[zone-runtime] missing from snapB"); return; }
        if (!Arrays.equals(za.stateBytes(), zb.stateBytes()))
            failures.add("[zone-runtime] stateBytes mismatch (length A="
                    + za.stateBytes().length + " B=" + zb.stateBytes().length + ")");
    }

    private static void comparePaletteOwnership(List<String> failures,
                                                 CompositeSnapshot a, CompositeSnapshot b) {
        PaletteOwnershipSnapshot pa = (PaletteOwnershipSnapshot) a.get("palette-ownership");
        PaletteOwnershipSnapshot pb = (PaletteOwnershipSnapshot) b.get("palette-ownership");
        if (pa == null) { failures.add("[palette-ownership] missing from snapA"); return; }
        if (pb == null) { failures.add("[palette-ownership] missing from snapB"); return; }
        if (!Arrays.equals(pa.ownerIds(), pb.ownerIds()))
            failures.add("[palette-ownership] ownerIds mismatch");
        if (!Arrays.equals(pa.ownerTable(), pb.ownerTable()))
            failures.add("[palette-ownership] ownerTable mismatch");
    }

    private static void compareLevelEvent(List<String> failures,
                                           CompositeSnapshot a, CompositeSnapshot b) {
        LevelEventSnapshot la = (LevelEventSnapshot) a.get("level-event");
        LevelEventSnapshot lb = (LevelEventSnapshot) b.get("level-event");
        if (la == null) { failures.add("[level-event] missing from snapA"); return; }
        if (lb == null) { failures.add("[level-event] missing from snapB"); return; }
        if (la.currentZone() != lb.currentZone())
            failures.add("[level-event] currentZone: " + la.currentZone() + " vs " + lb.currentZone());
        if (la.currentAct() != lb.currentAct())
            failures.add("[level-event] currentAct: " + la.currentAct() + " vs " + lb.currentAct());
        if (la.eventRoutineFg() != lb.eventRoutineFg())
            failures.add("[level-event] eventRoutineFg: " + la.eventRoutineFg()
                    + " vs " + lb.eventRoutineFg());
        if (la.eventRoutineBg() != lb.eventRoutineBg())
            failures.add("[level-event] eventRoutineBg: " + la.eventRoutineBg()
                    + " vs " + lb.eventRoutineBg());
        if (la.frameCounter() != lb.frameCounter())
            failures.add("[level-event] frameCounter: " + la.frameCounter()
                    + " vs " + lb.frameCounter());
        if (la.timerFrames() != lb.timerFrames())
            failures.add("[level-event] timerFrames: " + la.timerFrames()
                    + " vs " + lb.timerFrames());
        if (la.bossActive() != lb.bossActive())
            failures.add("[level-event] bossActive: " + la.bossActive() + " vs " + lb.bossActive());
        if (!Arrays.equals(la.eventDataFg(), lb.eventDataFg()))
            failures.add("[level-event] eventDataFg mismatch");
        if (!Arrays.equals(la.eventDataBg(), lb.eventDataBg()))
            failures.add("[level-event] eventDataBg mismatch");
        if (!Arrays.equals(la.extra(), lb.extra()))
            failures.add("[level-event] extra mismatch");
    }

    private static void compareRings(List<String> failures,
                                      CompositeSnapshot a, CompositeSnapshot b) {
        RingSnapshot ra = (RingSnapshot) a.get("rings");
        RingSnapshot rb = (RingSnapshot) b.get("rings");
        if (ra == null) { failures.add("[rings] missing from snapA"); return; }
        if (rb == null) { failures.add("[rings] missing from snapB"); return; }

        // BitSet equality
        if (!ra.collected().equals(rb.collected()))
            failures.add("[rings] collected BitSet mismatch");

        if (!Arrays.equals(ra.sparkleTimers(), rb.sparkleTimers()))
            failures.add("[rings] sparkleTimers mismatch");
        if (ra.placementCursorIndex() != rb.placementCursorIndex())
            failures.add("[rings] placementCursorIndex: " + ra.placementCursorIndex()
                    + " vs " + rb.placementCursorIndex());
        if (ra.placementLastCameraX() != rb.placementLastCameraX())
            failures.add("[rings] placementLastCameraX: " + ra.placementLastCameraX()
                    + " vs " + rb.placementLastCameraX());
        if (!Arrays.equals(ra.activeSpawnIndices(), rb.activeSpawnIndices()))
            failures.add("[rings] activeSpawnIndices mismatch");
        if (ra.lostRingActiveCount() != rb.lostRingActiveCount())
            failures.add("[rings] lostRingActiveCount: " + ra.lostRingActiveCount()
                    + " vs " + rb.lostRingActiveCount());
        if (ra.spillAnimCounter() != rb.spillAnimCounter())
            failures.add("[rings] spillAnimCounter: " + ra.spillAnimCounter()
                    + " vs " + rb.spillAnimCounter());
        if (ra.spillAnimAccum() != rb.spillAnimAccum())
            failures.add("[rings] spillAnimAccum: " + ra.spillAnimAccum()
                    + " vs " + rb.spillAnimAccum());
        if (ra.spillAnimFrame() != rb.spillAnimFrame())
            failures.add("[rings] spillAnimFrame: " + ra.spillAnimFrame()
                    + " vs " + rb.spillAnimFrame());
        if (ra.lostRingFrameCounter() != rb.lostRingFrameCounter())
            failures.add("[rings] lostRingFrameCounter: " + ra.lostRingFrameCounter()
                    + " vs " + rb.lostRingFrameCounter());

        // LostRingEntry[] — records with all-primitive fields, equals works per-element
        if (ra.lostRings().length != rb.lostRings().length) {
            failures.add("[rings] lostRings length: A=" + ra.lostRings().length
                    + " B=" + rb.lostRings().length);
        } else {
            for (int i = 0; i < ra.lostRings().length; i++) {
                if (!ra.lostRings()[i].equals(rb.lostRings()[i])) {
                    failures.add("[rings] lostRings[" + i + "] mismatch");
                }
            }
        }

        // AttractedRingEntry[] — records with all-primitive fields, equals works per-element
        if (ra.attractedRings().length != rb.attractedRings().length) {
            failures.add("[rings] attractedRings length: A=" + ra.attractedRings().length
                    + " B=" + rb.attractedRings().length);
        } else {
            for (int i = 0; i < ra.attractedRings().length; i++) {
                if (!ra.attractedRings()[i].equals(rb.attractedRings()[i])) {
                    failures.add("[rings] attractedRings[" + i + "] mismatch");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static Path findBk2(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
