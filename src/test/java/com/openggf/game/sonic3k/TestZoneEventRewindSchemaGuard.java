package com.openggf.game.sonic3k;

import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.rewind.schema.RewindCodecs;
import com.openggf.game.rewind.schema.RewindClassSchema;
import com.openggf.game.rewind.schema.RewindSchemaRegistry;
import com.openggf.game.rewind.schema.ZoneEventSchemaSidecar;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.events.Sonic3kHCZEvents;
import com.openggf.game.sonic3k.events.Sonic3kICZEvents;
import com.openggf.game.sonic3k.events.Sonic3kMGZEvents;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards schema-converted zone-event handlers: every field is either
 * captured or explicitly @RewindTransient, and the fields the legacy
 * hand-written sidecar serialized remain covered.
 */
public class TestZoneEventRewindSchemaGuard {

    private static final List<Class<?>> CONVERTED_HANDLERS = List.of(
            Sonic3kAIZEvents.class,
            Sonic3kHCZEvents.class,
            Sonic3kCNZEvents.class,
            Sonic3kMGZEvents.class,
            Sonic3kMHZEvents.class,
            Sonic3kICZEvents.class);
    private static final Set<String> AIZ_ALLOWED_TRANSIENT_FIELDS = Set.of(
            "bootstrap",
            "vblankCounterSource",
            // Queue facades/handles are derived from the scalar ordinal codecs
            // after HardwareTimingService restores its session ledger.
            "mainLevelBlockKosQueue",
            "mainLevelBlockHandle",
            "mainLevelArtKosQueue",
            "mainLevelArtHandle",
            "battleshipKosQueue",
            "battleshipTerrainKosHandle",
            "battleshipTerrainArtHandle",
            "battleshipObjectArtHandle",
            "fireOverlayKosQueue",
            "fireOverlayKosHandle",
            "act2TerrainKosQueue",
            "act2BlockHandle",
            "act2PrimaryChunkHandle",
            "act2SecondaryChunkHandle",
            "act2ArtKosQueue",
            "act2PrimaryArtHandle",
            "act2SecondaryArtHandle");
    private static final Set<String> HCZ_ALLOWED_TRANSIENT_FIELDS = Set.of(
            "wallObject",
            // Derived from transitionKosOrdinal after the timing ledger restore.
            "transitionKosQueue",
            "transitionKosHandle",
            "transitionDirectQueue",
            "transitionChunkHandle",
            "transitionBlockHandle");
    private static final Set<String> CNZ_ALLOWED_TRANSIENT_FIELDS = Set.of();
    private static final Set<String> MGZ_ALLOWED_TRANSIENT_FIELDS = Set.of(
            "activeRobotnik",
            "cachedMgzQuakeChunkData",
            "collapseSolids",
            "transitionDirectQueue",
            "transitionModuleQueue",
            "transitionChunkHandle",
            "transitionBlockHandle",
            "transitionArtHandle");
    private static final Set<String> MHZ_ALLOWED_TRANSIENT_FIELDS = Set.of();
    private static final Set<String> ICZ_ALLOWED_TRANSIENT_FIELDS = Set.of(
            "snowboardIntro",
            "act2TransitionDirectQueue",
            "act2TransitionChunkHandle",
            "act2TransitionBlockHandle",
            "act2TransitionArtQueue",
            "act2TransitionArtHandle");

    /**
     * FIELD names (not getter names) the legacy writeAizState byte layout serialized.
     */
    private static final Set<String> AIZ_LEGACY_FIELDS = Set.of(
            "introSpawned",
            "introMinXLocked",
            "introSidekickMarkerReleased",
            "introNormalRefreshPending",
            "paletteSwapped",
            "boundariesUnlocked",
            "fireMinXLockReached",
            "minibossSpawned",
            "eventsFg4",
            "eventsFg5",
            "bossFlag",
            "battleshipAutoScrollActive",
            "battleshipSpawned",
            "endBossSpawned",
            "battleshipTerrainLoaded",
            "act2TransitionRequested",
            "fireTransitionMutationRequested",
            "postFireHazeActive",
            "fireOverlayTilesLoaded",
            "mainLevelBlockOrdinal",
            "mainLevelArtOrdinal",
            "act2WaitFireDrawActive",
            "appliedTreeRevealChunkCopiesMask",
            "aiz2ResizeRoutine",
            "battleshipWrapX",
            "screenShakeTimer",
            "levelRepeatOffset",
            "battleshipBgYOffset",
            "battleshipSmoothScrollX",
            "battleshipPostScrollCameraX",
            "screenShakeOffsetY",
            "fireBgCopyFixed",
            "fireRiseSpeed",
            "fireWavePhase",
            "fireTransitionFrames",
            "firePhaseFrames",
            "fireOverlayTileCount",
            "fireSequencePhase"
    );

    /**
     * Required HCZ event fields: the legacy sidecar state plus the native per-player
     * carrier state that replaced its synthetic frame/centre/current-Y tuple.
     */
    private static final Set<String> HCZ_REQUIRED_FIELDS = Set.of(
            "eventsFg5",
            "bossFlag",
            "transitionRequested",
            "wallMoving",
            "wallStopped",
            "wallChaseBgOverlayActive",
            "cutsceneActive",
            "fgRoutine",
            "bgRoutine",
            "act2BgRoutine",
            "wallOffsetFixed",
            "wallOffsetPixels",
            "shakeTimer",
            "carrierMovementPending",
            "carrierUpdatedBeforeDynamicObjects",
            "carrierP1Active",
            "carrierP2Active",
            "carrierP1XFixed",
            "carrierP1YFixed",
            "carrierP1XVelocity",
            "carrierP2XFixed",
            "carrierP2YFixed",
            "carrierP2XVelocity",
            "carrierP1TargetSide",
            "carrierP2TargetSide",
            "carrierP1BoundsYOffset",
            "carrierP2BoundsYOffset",
            "transitionBubbleSpawnFrames"
    );

    /**
     * FIELD names (not getter names) the legacy writeCnzState byte layout serialized.
     */
    private static final Set<String> CNZ_LEGACY_FIELDS = Set.of(
            "cameraStoredMaxXPos",
            "cameraStoredMinXPos",
            "cameraStoredMinYPos",
            "cameraStoredMaxYPos",
            "cameraClampsActive",
            "bossFlagPrev",
            "eventsFg5",
            "bossFlag",
            "wallGrabSuppressed",
            "waterButtonArmed",
            "knucklesTeleporterRouteActive",
            "teleporterBeamSpawned",
            "act2TransitionRequested",
            "arenaChunkDestructionQueued",
            "fgRoutine",
            "bgRoutine",
            "deformPhaseBgX",
            "publishedBgCameraX",
            "bossScrollOffsetY",
            "bossScrollVelocityY",
            "waterTargetY",
            "pendingZoneActWord",
            "transitionWorldOffsetX",
            "transitionWorldOffsetY",
            "cameraMinXClamp",
            "cameraMaxXClamp",
            "arenaChunkWorldX",
            "arenaChunkWorldY",
            "destroyedArenaRows",
            "bossBackgroundMode"
    );

    /**
     * FIELD names (not getter names) the legacy writeMgzState byte layout serialized.
     */
    private static final Set<String> MGZ_LEGACY_FIELDS = Set.of(
            "eventsFg5",
            "transitionRequested",
            "collapseRequested",
            "collapseInitialized",
            "collapseFinished",
            "screenShakeActive",
            "bossTransitionActive",
            "bossTransitionDeathPlaneDisabled",
            "bgRiseMotionStarted",
            "bgRiseAccelLatched",
            "bgRiseLoadStateInitialised",
            "bossSpawned",
            "appearance1Complete",
            "appearance2Complete",
            "appearance3Complete",
            "postFleeUnlockDone",
            "bgRoutine",
            "quakeEventRoutine",
            "chunkEventRoutine",
            "chunkReplaceIndex",
            "chunkEventDelay",
            "screenEventRoutine",
            "collapseMutationCount",
            "collapseFrameCounter",
            "collapseStartupShakeTimer",
            "bossBgScrollVelocity",
            "bossBgScrollOffset",
            "bossTransitionTimer",
            "bossTransitionX",
            "bossTransitionY",
            "bossTransitionCameraX",
            "bossTransitionCameraY",
            "bgRiseRoutine",
            "bgRiseOffset",
            "bgRiseSubpixelAccum",
            "bgRiseFinalShakeTimer",
            "bossArenaRoutine",
            "gradualUnlockDirection",
            "gradualUnlockAccumulator",
            "collapseScrollVelocity",
            "collapseScrollFixedPosition",
            "collapseScrollPosition"
    );

    /**
     * FIELD names (not getter names) the legacy Sonic3kMHZEvents.writeRewindState byte layout serialized.
     */
    private static final Set<String> MHZ_LEGACY_FIELDS = Set.of(
            "bossFlag",
            "actTransitionFlag",
            "seasonFlag",
            "autumnTriggerFlag",
            "shipTransitionFlag",
            "shipHIntActive",
            "shipScrollLockSet",
            "shipControllerSignalFlag",
            "endBossCustomLayoutQueued",
            "endBossArenaBackgroundActive",
            "endBossPillarArtQueued",
            "endBossArenaForegroundRefreshActive",
            "endBossArenaHScrollCleared",
            "endBossArenaSpikeDeletionFlag",
            "endBossArenaRestoreRequested",
            "leafBlowerCutsceneFlag",
            "levelRepeatOffset",
            "specialEventsRoutine",
            "eventRoutine",
            "shipRedrawPosition",
            "shipRedrawRowCount",
            "shipHIntCounter",
            "shipSecondaryBgCameraXFixed",
            "shipEffectiveBgY",
            "endBossWalkoffPrepEventFlag",
            "screenShakeFlag",
            "screenShakeOffset",
            "screenShakeLastOffset",
            "shipHScrollCameraCopy",
            "shipPrimaryHScroll",
            "shipPlayerCarryBgY",
            "shipPropellerOneX",
            "shipPropellerTwoX",
            "shipPropellerY",
            "act2BackgroundRoutine",
            "endBossArenaDrawPosition",
            "endBossArenaDrawRowCount",
            "endBossArenaScrollDataByte",
            "endBossArenaScrollDataIndex",
            "endBossArenaPillarControllerCount",
            "endBossArenaTallSupportCount",
            "seasonPaletteMode",
            "endBossArenaSpikeTiers",
            "endBossArenaSpikeAlternateSides",
            "endBossArenaSpikeActive",
            "endBossArenaSpikeY"
    );

    /**
     * FIELD names (not getter names) the legacy Sonic3kICZEvents.writeRewindState byte layout serialized.
     */
    private static final Set<String> ICZ_LEGACY_FIELDS = Set.of(
            "eventsFg5",
            "introSpawned",
            "indoorPaletteCyclingActive",
            "bigSnowPileSpawned",
            "act2TransitionRequested",
            "act2TransitionHandoffId",
            "act2TransitionDirectPublished",
            "act2TransitionArtPublished",
            "act2TransitionChunkOrdinal",
            "act2TransitionBlockOrdinal",
            "act2TransitionArtOrdinal",
            "eventRoutine",
            "backgroundRoutine",
            "bigSnowOffset",
            "bigSnowOffsetSubpixels",
            "bigSnowVelocity"
    );

    @Test
    public void convertedHandlersHaveNoUnsupportedFields() {
        for (Class<?> handler : CONVERTED_HANDLERS) {
            RewindClassSchema schema = RewindSchemaRegistry.schemaFor(handler);
            assertTrue(schema.unsupportedFields().isEmpty(),
                    handler.getSimpleName() + " has unsupported rewind fields: "
                            + schema.unsupportedFields());
        }
    }

    @Test
    public void convertedHandlersDoNotCaptureIdentityReferenceFields() {
        for (Class<?> handler : CONVERTED_HANDLERS) {
            RewindClassSchema schema = RewindSchemaRegistry.schemaFor(handler);
            List<String> identityFields = schema.capturedFields().stream()
                    .filter(plan -> RewindCodecs.requiresIdentityTable(plan.field()))
                    .map(plan -> plan.field().getDeclaringClass().getSimpleName()
                            + "." + plan.field().getName())
                    .toList();
            assertTrue(identityFields.isEmpty(),
                    handler.getSimpleName()
                            + " zone-event sidecar is scalar-only and cannot capture identity references: "
                            + identityFields);
        }
    }

    @Test
    public void aizSchemaCoversAllLegacySidecarFields() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(Sonic3kAIZEvents.class);
        Set<String> captured = schema.capturedFields().stream()
                .map(plan -> plan.field().getName())
                .collect(Collectors.toSet());
        Set<String> missing = AIZ_LEGACY_FIELDS.stream()
                .filter(name -> !captured.contains(name))
                .collect(Collectors.toSet());
        assertTrue(missing.isEmpty(),
                "schema capture lost legacy sidecar fields: " + missing);
    }

    @Test
    public void hczSchemaCoversLegacyAndNativeCarrierState() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(Sonic3kHCZEvents.class);
        Set<String> captured = schema.capturedFields().stream()
                .map(plan -> plan.field().getName())
                .collect(Collectors.toSet());
        Set<String> missing = HCZ_REQUIRED_FIELDS.stream()
                .filter(name -> !captured.contains(name))
                .collect(Collectors.toSet());
        assertTrue(missing.isEmpty(),
                "schema capture lost legacy HCZ sidecar fields: " + missing);
    }

    @Test
    public void cnzSchemaCoversAllLegacySidecarFields() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(Sonic3kCNZEvents.class);
        Set<String> captured = schema.capturedFields().stream()
                .map(plan -> plan.field().getName())
                .collect(Collectors.toSet());
        Set<String> missing = CNZ_LEGACY_FIELDS.stream()
                .filter(name -> !captured.contains(name))
                .collect(Collectors.toSet());
        assertTrue(missing.isEmpty(),
                "schema capture lost legacy CNZ sidecar fields: " + missing);
    }

    @Test
    public void mgzSchemaCoversAllLegacySidecarFields() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(Sonic3kMGZEvents.class);
        Set<String> captured = schema.capturedFields().stream()
                .map(plan -> plan.field().getName())
                .collect(Collectors.toSet());
        Set<String> missing = MGZ_LEGACY_FIELDS.stream()
                .filter(name -> !captured.contains(name))
                .collect(Collectors.toSet());
        assertTrue(missing.isEmpty(),
                "schema capture lost legacy MGZ sidecar fields: " + missing);
    }

    @Test
    public void mhzSchemaCoversAllLegacySidecarFields() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(Sonic3kMHZEvents.class);
        Set<String> captured = schema.capturedFields().stream()
                .map(plan -> plan.field().getName())
                .collect(Collectors.toSet());
        Set<String> missing = MHZ_LEGACY_FIELDS.stream()
                .filter(name -> !captured.contains(name))
                .collect(Collectors.toSet());
        assertTrue(missing.isEmpty(),
                "schema capture lost legacy MHZ sidecar fields: " + missing);
    }

    @Test
    public void iczSchemaCoversAllLegacySidecarFields() {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(Sonic3kICZEvents.class);
        Set<String> captured = schema.capturedFields().stream()
                .map(plan -> plan.field().getName())
                .collect(Collectors.toSet());
        Set<String> missing = ICZ_LEGACY_FIELDS.stream()
                .filter(name -> !captured.contains(name))
                .collect(Collectors.toSet());
        assertTrue(missing.isEmpty(),
                "schema capture lost legacy ICZ sidecar fields: " + missing);
    }

    @Test
    public void aizTransientFieldInventoryIsExplicit() {
        Set<String> transientFields = Arrays.stream(Sonic3kAIZEvents.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isTransient(field.getModifiers())
                        || field.isAnnotationPresent(RewindTransient.class))
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        assertEquals(AIZ_ALLOWED_TRANSIENT_FIELDS, transientFields,
                "AIZ transient field inventory changed; classify new fields as captured or structural");
    }

    @Test
    public void hczTransientFieldInventoryIsExplicit() {
        Set<String> transientFields = Arrays.stream(Sonic3kHCZEvents.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isTransient(field.getModifiers())
                        || field.isAnnotationPresent(RewindTransient.class))
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        assertEquals(HCZ_ALLOWED_TRANSIENT_FIELDS, transientFields,
                "HCZ transient field inventory changed; classify new fields as captured or structural");
    }

    @Test
    public void cnzTransientFieldInventoryIsExplicit() {
        Set<String> transientFields = Arrays.stream(Sonic3kCNZEvents.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isTransient(field.getModifiers())
                        || field.isAnnotationPresent(RewindTransient.class))
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        assertEquals(CNZ_ALLOWED_TRANSIENT_FIELDS, transientFields,
                "CNZ transient field inventory changed; classify new fields as captured or structural");
    }

    @Test
    public void mgzTransientFieldInventoryIsExplicit() {
        Set<String> transientFields = Arrays.stream(Sonic3kMGZEvents.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isTransient(field.getModifiers())
                        || field.isAnnotationPresent(RewindTransient.class))
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        assertEquals(MGZ_ALLOWED_TRANSIENT_FIELDS, transientFields,
                "MGZ transient field inventory changed; classify new fields as captured or structural");
    }

    @Test
    public void mhzTransientFieldInventoryIsExplicit() {
        Set<String> transientFields = Arrays.stream(Sonic3kMHZEvents.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isTransient(field.getModifiers())
                        || field.isAnnotationPresent(RewindTransient.class))
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        assertEquals(MHZ_ALLOWED_TRANSIENT_FIELDS, transientFields,
                "MHZ transient field inventory changed; classify new fields as captured or structural");
    }

    @Test
    public void iczTransientFieldInventoryIsExplicit() {
        Set<String> transientFields = Arrays.stream(Sonic3kICZEvents.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> Modifier.isTransient(field.getModifiers())
                        || field.isAnnotationPresent(RewindTransient.class))
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        assertEquals(ICZ_ALLOWED_TRANSIENT_FIELDS, transientFields,
                "ICZ transient field inventory changed; classify new fields as captured or structural");
    }

    @Test
    public void sidecarRestoreRejectsNullBytes() {
        Sonic3kAIZEvents events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        assertThrows(NullPointerException.class, () -> ZoneEventSchemaSidecar.restore(events, null));
    }

    @Test
    public void sidecarRestoreRejectsShortPayloadWithoutPartialMutation() {
        Sonic3kAIZEvents events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        byte[] full = ZoneEventSchemaSidecar.capture(events);
        byte[] shortPayload = Arrays.copyOf(full, full.length - 1);

        events.setIntroSpawned(true);

        assertThrows(IllegalArgumentException.class,
                () -> ZoneEventSchemaSidecar.restore(events, shortPayload));
        assertTrue(events.isIntroSpawned(),
                "length rejection must happen before restore mutates AIZ fields");
    }

    @Test
    public void sidecarRestoreRejectsLongPayloadWithoutPartialMutation() {
        Sonic3kAIZEvents events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        byte[] full = ZoneEventSchemaSidecar.capture(events);
        byte[] longPayload = Arrays.copyOf(full, full.length + 1);

        events.setIntroSpawned(true);

        assertThrows(IllegalArgumentException.class,
                () -> ZoneEventSchemaSidecar.restore(events, longPayload));
        assertTrue(events.isIntroSpawned(),
                "length rejection must happen before restore mutates AIZ fields");
    }

    @Test
    public void sidecarCaptureRejectsOpaqueValues() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ZoneEventSchemaSidecar.capture(new OpaqueProbe()));

        assertTrue(ex.getMessage().contains("opaque values"),
                "opaque String fields must be rejected until the sidecar has an explicit policy");
    }

    private static final class OpaqueProbe {
        @SuppressWarnings("unused")
        private String label = "opaque";
    }
}
