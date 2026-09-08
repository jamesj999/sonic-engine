package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.CheckpointState;
import com.openggf.game.GameMode;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.PowerUpObject;
import com.openggf.game.ShieldType;
import com.openggf.game.rewind.LiveRewindManager;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.rewind.RewindController;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.Sonic3kFBZEvents;
import com.openggf.game.sonic3k.runtime.FbzZoneRuntimeState;
import com.openggf.game.sonic3k.objects.FbzMinibossInstance;
import com.openggf.game.sonic3k.objects.FbzOutdoorBgMotionObjectInstance;
import com.openggf.game.sonic3k.objects.S3kHiddenMonitorInstance;
import com.openggf.game.sonic3k.objects.S3kResultsElementObjectInstance;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DefaultPowerUpSpawner;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.Palette;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Full real-runtime FBZ1BGE_Normal reload contract. */
@RequiresRom(SonicGame.SONIC_3K)
class TestFbzActTransitionHeadless {
    @Test
    void synchronousReloadCarriesRomGlobalMagneticStateAndConsumesOneShotContext()
            throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider();
        Sonic3kFBZEvents act1Events = manager.getFbzEvents();
        FbzZoneRuntimeState act1 = assertInstanceOf(
                FbzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        act1.restoreMagneticTransitionState(new FbzZoneRuntimeState.MagneticTransitionState(
                Sonic3kFBZEvents.MagneticPolarity.ACTIVE, 0xFE, true, 0x1200));

        act1Events.setEventsFg5(true);
        act1Events.updateAct1BackgroundEvent(fixture.sprite().getCentreX(),
                fixture.sprite().getCentreY(), false);

        assertEquals(1, GameServices.level().getCurrentAct());
        assertNotSame(act1Events, manager.getFbzEvents());
        FbzZoneRuntimeState act2 = assertInstanceOf(
                FbzZoneRuntimeState.class, GameServices.zoneRuntimeRegistry().current());
        assertNotSame(act1, act2);
        assertTrue(act2.isBackedBy(manager.getFbzEvents()));
        assertEquals(Sonic3kFBZEvents.MagneticPolarity.ACTIVE, act2.magneticPolarity());
        assertEquals(0xFE, act2.magneticTimerPhase());
        assertTrue(act2.magneticEdgeObserved());
        assertEquals(0x1200, act2.magneticLastEdgeFrame());

        act2.advanceMagneticPhase(0x12FF);
        assertEquals(Sonic3kFBZEvents.MagneticPolarity.ACTIVE, act2.magneticPolarity());
        assertEquals(0xFF, act2.magneticTimerPhase());
        act2.advanceMagneticPhase(0x1300);
        assertEquals(Sonic3kFBZEvents.MagneticPolarity.INACTIVE, act2.magneticPolarity());
        assertEquals(0, act2.magneticTimerPhase());
        assertEquals(0x1300, act2.magneticLastEdgeFrame());
        act2.advanceMagneticPhase(0x1300);
        assertEquals(Sonic3kFBZEvents.MagneticPolarity.INACTIVE, act2.magneticPolarity(),
                "the same AnPal frame must not toggle the carried byte twice");

        assertFalse(GameServices.level().isApplyingSynchronousScreenEventTransition(
                        Sonic3kZoneIds.ZONE_FBZ, 0, Sonic3kZoneIds.ZONE_FBZ, 1),
                "the one-shot transition context must be cleared before control returns");
    }

    @Test
    void synchronousReloadCarriesAndReadoptsTheExactOutdoorMotionSlot() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
        ObjectManager act1Objects = GameServices.level().getObjectManager();
        FbzOutdoorBgMotionObjectInstance act1Motion = act1Objects.getActiveObjects().stream()
                .filter(FbzOutdoorBgMotionObjectInstance.class::isInstance)
                .map(FbzOutdoorBgMotionObjectInstance.class::cast)
                .findFirst().orElseThrow();
        assertEquals(4, act1Motion.getSlotIndex());

        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider();
        Sonic3kFBZEvents act1Events = manager.getFbzEvents();
        act1Events.setEventsFg5(true);
        act1Events.updateAct1BackgroundEvent(fixture.sprite().getCentreX(),
                fixture.sprite().getCentreY(), false);

        ObjectManager act2Objects = GameServices.level().getObjectManager();
        var act2Motions = act2Objects.getActiveObjects().stream()
                .filter(FbzOutdoorBgMotionObjectInstance.class::isInstance)
                .map(FbzOutdoorBgMotionObjectInstance.class::cast)
                .toList();
        assertEquals(1, act2Motions.size(),
                "FBZ2 runtime initialization must adopt the carried controller without allocating a duplicate");
        assertSame(act1Motion, act2Motions.getFirst());
        assertEquals(4, act2Motions.getFirst().getSlotIndex());
        assertTrue(manager.getFbzEvents().isOutdoorMotionSpawned(),
                "the replacement typed runtime must own the carried slot-4 controller");
    }

    @Test
    void allLiveReloadRegistersOwnerCreatedInstaShieldThatWasNotInCarrySnapshot()
            throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
        ObjectManager act1Objects = GameServices.level().getObjectManager();
        var instaShield = fixture.sprite().getInstaShieldObject();
        assertNotNull(instaShield);
        assertEquals(0, act1Objects.getActiveObjects().stream()
                .filter(candidate -> candidate == instaShield).count());
        assertEquals(0, act1Objects.snapshotAllLiveSstObjectsForTransition().stream()
                .filter(occupant -> occupant.identity() == instaShield).count());

        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider();
        Sonic3kFBZEvents events = manager.getFbzEvents();
        events.setEventsFg5(true);
        events.updateAct1BackgroundEvent(fixture.sprite().getCentreX(),
                fixture.sprite().getCentreY(), false);

        ObjectManager act2Objects = GameServices.level().getObjectManager();
        assertSame(instaShield, fixture.sprite().getInstaShieldObject());
        assertEquals(1, act2Objects.getActiveObjects().stream()
                .filter(candidate -> candidate == instaShield).count());
        assertEquals(1, act2Objects.snapshotAllLiveSstObjectsForTransition().stream()
                .filter(occupant -> occupant.identity() == instaShield).count());
    }

    @Test
    void productionReloadPreservesConcreteSstFamiliesAtExactBoundarySlotsAndLinks()
            throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
        // Finish the fresh initial Process_Sprites before installing synthetic
        // transition occupants, including its normally empty absolute slot 3.
        fixture.stepIdleFrames(1);
        ObjectManager act1 = GameServices.level().getObjectManager();

        FbzOutdoorBgMotionObjectInstance fixedSlot3 = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(), FbzOutdoorBgMotionObjectInstance::new);
        Sonic3kMonitorObjectInstance firstOffsetSlot4 = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new Sonic3kMonitorObjectInstance(
                        new ObjectSpawn(0x2F20, 0x0540, 1, 3, 0, false, 0)));
        S3kHiddenMonitorInstance lastOffsetSlot93 = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new S3kHiddenMonitorInstance(
                        new ObjectSpawn(0x2F30, 0x0540, 0x80, 3, 0, false, 0)));
        Sonic3kMonitorObjectInstance firstExcludedSlot94 = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new Sonic3kMonitorObjectInstance(
                        new ObjectSpawn(0x2F40, 0x0540, 1, 3, 0, false, 0)));
        act1.addDynamicObjectAtSlot(firstOffsetSlot4, 4);
        act1.addDynamicObjectAtSlot(lastOffsetSlot93, 93);
        act1.addDynamicObjectAtSlot(firstExcludedSlot94, 94);
        List<AbstractObjectInstance> preexistingSlot3 = act1.getActiveObjects().stream()
                .filter(candidate -> candidate instanceof AbstractObjectInstance object
                        && object.getSlotIndex() == 3 && !candidate.isDestroyed())
                .map(AbstractObjectInstance.class::cast)
                .toList();
        assertTrue(preexistingSlot3.isEmpty(),
                "the fixed slot-3 boundary probe must not overwrite a live SST: "
                        + preexistingSlot3);
        act1.addDynamicObjectAtSlot(fixedSlot3, 3);
        assertExactIdentityAndSlot(act1, fixedSlot3, 3);

        SubpixelMotion.State slot4Motion = privateField(firstOffsetSlot4, "motion");
        slot4Motion.xSub = 0x5A;
        slot4Motion.ySub = 0xC3;
        SubpixelMotion.State slot94Motion = privateField(firstExcludedSlot94, "motion");
        slot94Motion.xSub = 0x7B;
        slot94Motion.ySub = 0x2D;

        fixture.sprite().giveShield(ShieldType.FIRE);
        fixture.sprite().giveInvincibility();
        PowerUpObject shield = fixture.sprite().getShieldObject();
        PowerUpObject invincibility = fixture.sprite().getInvincibilityObject();
        assertNotNull(shield);
        assertNotNull(invincibility);
        int shieldSlot = ((AbstractObjectInstance) shield).getSlotIndex();
        int invincibilitySlot = ((AbstractObjectInstance) invincibility).getSlotIndex();

        fixture.stepFrame(false, false, false, false, false);
        FbzMinibossInstance root = act1.activeObjectsOfType(FbzMinibossInstance.class)
                .stream().findFirst().orElseThrow();
        AbstractObjectInstance leftArm = privateField(root, "leftArm");
        AbstractObjectInstance rightArm = privateField(root, "rightArm");
        assertNotNull(leftArm);
        assertNotNull(rightArm);
        AbstractObjectInstance plunger = act1.getActiveObjects().stream()
                .filter(candidate -> candidate.getClass().getSimpleName()
                        .equals("FbzMinibossPlungerChild"))
                .map(AbstractObjectInstance.class::cast)
                .findFirst().orElseThrow();
        fixture.sprite().setCentreX((short) plunger.getX());
        fixture.sprite().setCentreY((short) (plunger.getY() - 8
                - fixture.sprite().getYRadius()));
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0x100);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setAir(true);
        for (int frame = 0; frame < 16 && !root.isPlungerStarted(); frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertTrue(root.isPlungerStarted(),
                "the production P1 standing contact must publish the native plunger start bit");
        AbstractObjectInstance leftFirst = privateField(leftArm, "next");
        AbstractObjectInstance leftTerminal = privateField(leftArm, "terminal");
        for (int frame = 0; frame < 4 && (leftFirst == null || leftTerminal == null); frame++) {
            fixture.stepFrame(false, false, false, false, false);
            leftFirst = privateField(leftArm, "next");
            leftTerminal = privateField(leftArm, "terminal");
        }
        assertNotNull(leftFirst);
        assertNotNull(leftTerminal);
        int rootSlot = root.getSlotIndex();
        int leftArmSlot = leftArm.getSlotIndex();
        int rightArmSlot = rightArm.getSlotIndex();
        int leftFirstSlot = leftFirst.getSlotIndex();
        int leftTerminalSlot = leftTerminal.getSlotIndex();
        int rootX = root.getX() & 0xFFFF;

        assertExactIdentityAndSlot(act1, fixedSlot3, 3);
        S3kResultsScreenObjectInstance resultsOwner = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new S3kResultsScreenObjectInstance(PlayerCharacter.SONIC_ALONE, 0));
        act1.addDynamicObjectAtSlot(resultsOwner, 70);
        Map<S3kResultsElementObjectInstance, int[]> resultsWordsAtPublication =
                new IdentityHashMap<>();
        Map<S3kResultsElementObjectInstance, int[]> resultsWordsAfterReloadTail =
                new IdentityHashMap<>();
        fixture.gameplayMode().setRewindBoundaryReporter(boundary -> {
            if (boundary != RewindBoundary.SEAMLESS_LEVEL_TRANSITION) {
                return;
            }
            Map<S3kResultsElementObjectInstance, int[]> destination;
            ObjectManager boundaryManager;
            if (GameServices.level().getCurrentAct() == 0
                    && resultsWordsAtPublication.isEmpty()) {
                destination = resultsWordsAtPublication;
                boundaryManager = act1;
            } else if (GameServices.level().getCurrentAct() == 1
                    && resultsWordsAfterReloadTail.isEmpty()) {
                destination = resultsWordsAfterReloadTail;
                boundaryManager = GameServices.level().getObjectManager();
            } else {
                return;
            }
            for (S3kResultsElementObjectInstance child
                    : boundaryManager.activeObjectsOfType(S3kResultsElementObjectInstance.class)) {
                if (child.parentResults() == resultsOwner) {
                    destination.put(child,
                            new int[]{child.getSlotIndex(), child.getX(), child.getY()});
                }
            }
        });

        for (int frame = 0; frame < 2_500 && GameServices.level().getCurrentAct() == 0; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertEquals(1, GameServices.level().getCurrentAct(),
                "the real results owner must publish through LevelFrameStep");
        ObjectManager act2 = GameServices.level().getObjectManager();
        assertNotSame(act1, act2);
        assertExactIdentityAndSlot(act2, fixedSlot3, 3);
        assertExactIdentityAndSlot(act2, firstOffsetSlot4, 4);
        assertExactIdentityAndSlot(act2, resultsOwner, 70);
        assertExactIdentityAndSlot(act2, lastOffsetSlot93, 93);
        assertExactIdentityAndSlot(act2, firstExcludedSlot94, 94);
        assertExactIdentityAndSlot(act2, root, rootSlot);
        assertExactIdentityAndSlot(act2, leftArm, leftArmSlot);
        assertExactIdentityAndSlot(act2, rightArm, rightArmSlot);
        assertExactIdentityAndSlot(act2, leftFirst, leftFirstSlot);
        assertExactIdentityAndSlot(act2, leftTerminal, leftTerminalSlot);
        assertExactIdentityAndSlot(act2, (AbstractObjectInstance) shield, shieldSlot);
        assertExactIdentityAndSlot(act2, (AbstractObjectInstance) invincibility,
                invincibilitySlot);

        assertNull(resultsOwner.getSpawn(),
                "the slot-70 results owner has no native x_pos/y_pos storage");
        assertFalse(resultsOwner.participatesInRomWorldTransitionOffset(),
                "slot 70 is the real render-bit-2-clear screen-space owner");
        assertEquals(12, resultsWordsAtPublication.size(),
                "Obj_LevelResultsCreate must publish all 12 real child SSTs before Events_fg_5");
        assertEquals(12, resultsWordsAfterReloadTail.size(),
                "the completed synchronous reload tail must retain all 12 child SSTs");
        List<S3kResultsElementObjectInstance> carriedResultsChildren = act2
                .activeObjectsOfType(S3kResultsElementObjectInstance.class).stream()
                .filter(child -> child.parentResults() == resultsOwner)
                .toList();
        assertEquals(12, carriedResultsChildren.size(),
                "reload must neither independently allocate nor lose a results child");
        for (S3kResultsElementObjectInstance child : carriedResultsChildren) {
            int[] publication = resultsWordsAtPublication.get(child);
            assertNotNull(publication,
                    "every post-reload child must be the exact pre-reload SST identity");
            int[] afterReloadTail = resultsWordsAfterReloadTail.get(child);
            assertNotNull(afterReloadTail,
                    "the completed-tail set must contain no independently allocated child");
            assertExactIdentityAndSlot(act2, child, publication[0]);
            assertEquals(publication[0], afterReloadTail[0]);
            assertFalse(child.participatesInRomWorldTransitionOffset());
            int nativeSlideInDelta = child.entryIndex() < 2 ? 16 : -16;
            assertEquals(publication[1] + nativeSlideInDelta, afterReloadTail[1],
                    "between slot-70 publication and ScreenEvents, the child may receive only its "
                            + "one native LevResults_SlideIn dispatch, never the -$2E00 world offset");
            assertEquals(publication[2], afterReloadTail[2],
                    "results screen Y word must remain unchanged");
            assertSame(resultsOwner, child.parentResults(),
                    "the carried child must retain its exact slot-70 owner link");
        }

        assertEquals(0, fixedSlot3.getX() & 0xFFFF,
                "reserved slot 3 survives but is outside the native offset scan");
        assertEquals(0x0120, firstOffsetSlot4.getX() & 0xFFFF,
                "slot 4 is the first included native SST address");
        assertEquals(0x0130, lastOffsetSlot93.getX() & 0xFFFF,
                "slot 93 is the last included native SST address");
        assertEquals(0x2F40, firstExcludedSlot94.getX() & 0xFFFF,
                "slot 94 survives without coordinate mutation");
        assertEquals(0x5A, slot4Motion.xSub);
        assertEquals(0xC3, slot4Motion.ySub);
        assertEquals(0x7B, slot94Motion.xSub);
        assertEquals(0x2D, slot94Motion.ySub);
        assertEquals((rootX - 0x2E00) & 0xFFFF, root.getX() & 0xFFFF,
                "the real miniboss root receives one native word offset");

        assertSame(root, privateField(leftArm, "boss"));
        assertSame(root, privateField(rightArm, "boss"));
        assertSame(leftFirst, privateField(leftArm, "next"));
        assertSame(leftTerminal, privateField(leftArm, "terminal"));
        assertSame(root, privateField(leftFirst, "boss"));
        assertSame(leftArm, privateField(leftFirst, "arm"));
        assertSame(leftArm, privateField(leftFirst, "previous"));
        assertSame(leftFirst, privateField(leftArm, "next"));
        assertSame(leftArm, privateField(leftTerminal, "next"),
                "the terminal link must retain the exact arm-cycle closure");
    }

    @ParameterizedTest
    @EnumSource(value = Sonic3kFBZEvents.RedrawDirection.class,
            names = {"TOP_DOWN", "BOTTOM_UP", "LEFT_TO_RIGHT", "RIGHT_TO_LEFT"})
    void productionFrameDispatcherLeavesResultsSignalPendingInEveryRedrawRoutine(
            Sonic3kFBZEvents.RedrawDirection direction) {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider();
        Sonic3kFBZEvents events = manager.getFbzEvents();
        events.setBackgroundRedraw(switch (direction) {
            case TOP_DOWN -> 4;
            case BOTTOM_UP -> 8;
            case LEFT_TO_RIGHT -> 12;
            case RIGHT_TO_LEFT -> 16;
            default -> throw new AssertionError(direction);
        }, direction);
        events.restoreAct1EventState(0, 0x777, 1, 0, 0,
                Sonic3kFBZEvents.DeformMode.INDOOR,
                Sonic3kFBZEvents.PaletteVariant.INDOOR,
                Sonic3kFBZEvents.PaletteTarget.NORMAL,
                true, true, false, false);
        events.setEventsFg5(true);

        fixture.stepFrame(false, false, false, false, false);

        assertEquals(0, GameServices.level().getCurrentAct(),
                "a non-Normal FBZ1 background routine must not execute Load_Level");
        assertTrue(events.isEventsFg5(),
                "the real LevelFrameStep dispatcher must leave Events_fg_5 pending in " + direction);
        boolean horizontal = direction == Sonic3kFBZEvents.RedrawDirection.LEFT_TO_RIGHT
                || direction == Sonic3kFBZEvents.RedrawDirection.RIGHT_TO_LEFT;
        int expectedPosition = switch (direction) {
            case TOP_DOWN, LEFT_TO_RIGHT -> 0;
            case BOTTOM_UP -> 0xF0;
            case RIGHT_TO_LEFT -> 0x3F0;
            default -> throw new AssertionError(direction);
        };
        assertEquals(expectedPosition, events.getBackgroundRedrawPosition(),
                "FBZ1BGE_DrawBG" + direction
                        + " must execute its direction-specific first row/column cursor");
        assertEquals(horizontal ? 0 : 1, events.getBackgroundRedrawProgress(),
                "vertical redraws consume one row, while horizontal redraws consume two columns");
        assertEquals(horizontal ? -1 : 0, events.getBackgroundRedrawRowCount(),
                "the restored final horizontal pair completes this routine in one dispatch");
        assertEquals(horizontal ? Sonic3kFBZEvents.RedrawDirection.NONE : direction,
                events.getBackgroundRedrawDirection(),
                "FBZ1BGE_DrawBG left/right resets Events_routine_bg after the final two-column pair");
    }

    @Test
    void realLiveRewindRoundTripsAct1OwnersBeforeResultsPublication() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
        LiveRewindManager live = new LiveRewindManager(config);
        InputHandler input = new InputHandler();
        assertFalse(live.handleRealtimeRewindInput(GameMode.LEVEL, false, input));
        RewindController controller = fixture.gameplayMode().getRewindController();
        assertNotNull(controller);

        fixture.stepFrame(false, false, false, false, false);
        live.recordExternalFrame(GameMode.LEVEL, false, input);
        int healthyFrame = controller.currentFrame();
        ObjectManager manager = GameServices.level().getObjectManager();
        FbzMinibossInstance boss = manager.activeObjectsOfType(FbzMinibossInstance.class)
                .stream().findFirst().orElseThrow();
        int bossSlot = boss.getSlotIndex();
        short cameraX = fixture.camera().getX();
        short cameraY = fixture.camera().getY();
        short xCopy = fixture.camera().getXCopy();
        short yCopy = fixture.camera().getYCopy();
        short minX = fixture.camera().getMinX();
        short maxX = fixture.camera().getMaxX();
        short minY = fixture.camera().getMinY();
        short maxY = fixture.camera().getMaxY();
        short minXTarget = fixture.camera().getMinXTarget();
        short maxXTarget = fixture.camera().getMaxXTarget();
        short minYTarget = fixture.camera().getMinYTarget();
        short maxYTarget = fixture.camera().getMaxYTarget();
        int ringWindow = GameServices.level().getRingManager().capture().placementLastCameraX();

        boss.setDestroyed(true);
        fixture.camera().setX((short) 0x1111);
        fixture.camera().setY((short) 0x2222);
        fixture.camera().setXCopy((short) 0x3333);
        fixture.camera().setYCopy((short) 0x4444);
        fixture.camera().setMinX((short) 0x10);
        fixture.camera().setMaxX((short) 0x20);
        fixture.camera().setMinY((short) 0x30);
        fixture.camera().setMaxY((short) 0x40);
        live.recordExternalFrame(GameMode.LEVEL, false, input);

        controller.seekTo(healthyFrame);

        assertEquals(0, GameServices.level().getCurrentAct());
        assertEquals(0, GameServices.level().getApparentAct());
        assertSame(manager, GameServices.level().getObjectManager());
        assertTrue(manager.activeObjectsOfType(FbzMinibossInstance.class).stream()
                        .anyMatch(candidate -> candidate.getSlotIndex() == bossSlot && !candidate.isDestroyed()),
                "the live Act-1 object adapter must restore the owning placed boss SST");
        assertEquals(cameraX, fixture.camera().getX());
        assertEquals(cameraY, fixture.camera().getY());
        assertEquals(xCopy, fixture.camera().getXCopy());
        assertEquals(yCopy, fixture.camera().getYCopy());
        assertEquals(minX, fixture.camera().getMinX());
        assertEquals(maxX, fixture.camera().getMaxX());
        assertEquals(minY, fixture.camera().getMinY());
        assertEquals(maxY, fixture.camera().getMaxY());
        assertEquals(minXTarget, fixture.camera().getMinXTarget());
        assertEquals(maxXTarget, fixture.camera().getMaxXTarget());
        assertEquals(minYTarget, fixture.camera().getMinYTarget());
        assertEquals(maxYTarget, fixture.camera().getMaxYTarget());
        assertEquals(ringWindow,
                GameServices.level().getRingManager().capture().placementLastCameraX());
    }

    @Test
    void realLiveRewindCannotCrossResultsReloadButCanSeekInsideAct2Segment() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
        GameplayModeContext originalSession = fixture.gameplayMode();
        CheckpointState checkpoint = (CheckpointState) GameServices.level().getCheckpointState();
        checkpoint.saveCheckpoint(7, 0x2EE1, 0x0540, false);
        GameServices.level().setBonusStageReturnCheckpointIndex(7);
        fixture.sprite().setDead(false);
        fixture.sprite().setDeathCountdown(0);

        S3kResultsScreenObjectInstance results = ObjectConstructionContext.construct(
                TestEnvironment.objectServices(),
                () -> new S3kResultsScreenObjectInstance(PlayerCharacter.SONIC_ALONE, 0));
        GameServices.level().getObjectManager().addDynamicObject(results);

        LiveRewindManager live = new LiveRewindManager(config);
        InputHandler input = new InputHandler();
        assertFalse(live.handleRealtimeRewindInput(GameMode.LEVEL, false, input));
        List<RewindBoundary> observedBoundaries = new ArrayList<>();
        originalSession.setRewindBoundaryReporter(boundary -> {
            observedBoundaries.add(boundary);
            live.markBoundary(boundary);
        });
        RewindController controller = originalSession.getRewindController();
        assertNotNull(controller);

        int lastAct1Frame = controller.currentFrame();
        for (int frame = 0; frame < 120 && GameServices.level().getCurrentAct() == 0; frame++) {
            lastAct1Frame = controller.currentFrame();
            fixture.stepFrame(false, false, false, false, false);
            live.recordExternalFrame(GameMode.LEVEL, false, input);
        }

        assertEquals(1, GameServices.level().getCurrentAct(),
                "the real Obj_LevelResultsCreate publication must synchronously run FBZ1BGE_Normal");
        assertSame(originalSession, SessionManager.getCurrentGameplayMode(),
                "the seamless Load_Level must retain the owning gameplay session");
        assertFalse(observedBoundaries.contains(RewindBoundary.LEVEL_LOAD),
                "the nested Load_Level boundary must be suppressed until FBZ1BGE_Normal finishes its tail");
        assertEquals(List.of(
                        RewindBoundary.SEAMLESS_LEVEL_TRANSITION,
                        RewindBoundary.SEAMLESS_LEVEL_TRANSITION), observedBoundaries,
                "the real frame may expose only the publication and completed-tail seamless boundaries");
        assertTrue(checkpoint.isActive());
        assertEquals(7, checkpoint.getLastCheckpointIndex());
        assertFalse(fixture.sprite().getDead());
        assertEquals(0, fixture.sprite().getDeathCountdown());
        AbstractObjectInstance carriedAimer = GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(candidate -> candidate.getClass().getSimpleName().equals("FbzMinibossAimerChild"))
                .map(AbstractObjectInstance.class::cast)
                .findFirst().orElseThrow();
        assertEquals((carriedAimer.getSpawn().x() - 0x2E00) & 0xFFFF,
                readPrivateInt(carriedAimer, "nativeX"));
        assertEquals(carriedAimer.getSpawn().y() & 0xFFFF,
                readPrivateInt(carriedAimer, "nativeY"));

        int postBoundaryFloor = controller.earliestAvailableFrame();
        assertTrue(postBoundaryFloor >= lastAct1Frame,
                "the results publication boundary must discard every earlier Act 1 keyframe");
        controller.seekTo(0);
        assertEquals(postBoundaryFloor, controller.currentFrame(),
                "a cross-boundary seek must clamp to the first complete Act 2 state");
        assertEquals(1, GameServices.level().getCurrentAct());
        assertSame(originalSession, SessionManager.getCurrentGameplayMode());
        assertTrue(GameServices.level().getCheckpointState().isActive());
        assertFalse(fixture.sprite().getDead());

        assertTrue(GameServices.level().isTransitionRingInitializationPendingForRewind(),
                "the completed FBZ1BGE_Normal boundary is still before LevelLoop Load_Rings");
        assertEquals(Integer.MIN_VALUE,
                GameServices.level().getRingManager().capture().placementLastCameraX(),
                "ScreenEvents must expose the pre-Load_Rings placement sentinel");
        fixture.stepFrame(false, false, false, false, false);
        assertFalse(GameServices.level().isTransitionRingInitializationPendingForRewind(),
                "the later production LevelManager phase must execute Load_Rings");
        assertEquals(GameServices.camera().getX(),
                GameServices.level().getRingManager().capture().placementLastCameraX(),
                "Load_Rings windows FBZ2 data from the shifted camera");

        live.recordExternalFrame(GameMode.LEVEL, false, input);
        int healthyAct2Frame = controller.currentFrame();
        ObjectManager liveAct2Manager = GameServices.level().getObjectManager();
        Map<Integer, Integer> healthySlots = liveAct2Manager.occupiedDynamicSlotIds();
        FbzMinibossInstance healthyCarriedOwner = liveAct2Manager
                .activeObjectsOfType(FbzMinibossInstance.class).stream()
                .findFirst().orElseThrow();
        int healthyOwnerSlot = healthyCarriedOwner.getSlotIndex();
        int healthyRingWindow = GameServices.level().getRingManager().capture().placementLastCameraX();
        healthyCarriedOwner.setDestroyed(true);
        fixture.sprite().setDead(true);
        fixture.sprite().setDeathCountdown(37);
        GameServices.level().getCheckpointState().clear();
        GameServices.level().requestRespawn();
        live.recordExternalFrame(GameMode.LEVEL, false, input);
        assertTrue(fixture.sprite().getDead());
        assertFalse(GameServices.level().getCheckpointState().isActive());
        assertTrue(GameServices.level().isRespawnRequestedForRewind());

        controller.seekTo(healthyAct2Frame);
        assertEquals(healthyAct2Frame, controller.currentFrame(),
                "LiveRewindManager must remain usable inside the post-transition segment");
        assertEquals(1, GameServices.level().getCurrentAct());
        assertSame(originalSession, SessionManager.getCurrentGameplayMode());
        assertTrue(GameServices.level().getCheckpointState().isActive(),
                "the carried starpost must restore inside the Act 2 segment");
        assertEquals(7, GameServices.level().getCheckpointState().getLastCheckpointIndex());
        assertFalse(fixture.sprite().getDead());
        assertEquals(0, fixture.sprite().getDeathCountdown());
        assertFalse(GameServices.level().isRespawnRequestedForRewind());
        assertSame(liveAct2Manager, GameServices.level().getObjectManager(),
                "post-tail rewind must remain bound to the replacement Act-2 manager");
        FbzMinibossInstance restoredRoot = liveAct2Manager
                .activeObjectsOfType(FbzMinibossInstance.class).stream()
                .filter(candidate -> candidate.getSlotIndex() == healthyOwnerSlot
                        && !candidate.isDestroyed())
                .findFirst().orElse(null);
        assertNotNull(restoredRoot,
                "the replacement manager adapter must restore a diverged carried SST owner");
        AbstractObjectInstance restoredLeftArm = privateField(restoredRoot, "leftArm");
        AbstractObjectInstance restoredRightArm = privateField(restoredRoot, "rightArm");
        assertNotNull(restoredLeftArm);
        assertNotNull(restoredRightArm);
        assertSame(restoredRoot, privateField(restoredLeftArm, "boss"));
        assertSame(restoredRoot, privateField(restoredRightArm, "boss"));
        assertEquals(healthySlots, liveAct2Manager.occupiedDynamicSlotIds(),
                "post-tail rewind must restore Act-2 placement/slot occupancy, not dead Act-1 state");
        assertEquals(healthyRingWindow,
                GameServices.level().getRingManager().capture().placementLastCameraX());
    }

    @Test
    void screenEventReloadPreservesLiveTeamStateAndUsesShiftedWindows() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_FBZ, 0)
                .startPosition((short) 0x2EE1, (short) 0x0540)
                .startPositionIsCentre()
                .build();
        var level = GameServices.level();
        var camera = fixture.camera();
        camera.setMinX((short) 0x2E20);
        camera.setMaxX((short) 0x2EA0);
        camera.setMinY((short) 0x0540);
        camera.setMaxY((short) 0x0540);
        camera.setMinXTarget((short) 0x2D10);
        camera.setMaxXTarget((short) 0x2FB0);
        camera.setMinYTarget((short) 0x0520);
        camera.setMaxYTarget((short) 0x0560);
        camera.setX((short) 0x2EA0);
        camera.setY((short) 0x0540);
        camera.setXCopy((short) 0x2EA0);
        camera.setYCopy((short) 0x0540);
        var parallax = GameServices.parallax();
        java.util.Arrays.fill(parallax.getHScroll(), 0x1234_5678);
        ObjectManager act1Objects = level.getObjectManager();
        var act1Rings = List.copyOf(level.getCurrentLevel().getRings());
        fixture.sprite().giveShield(ShieldType.FIRE);
        fixture.sprite().giveInvincibility();
        PowerUpObject shield = fixture.sprite().getShieldObject();
        PowerUpObject invincibility = fixture.sprite().getInvincibilityObject();
        var instaShield = fixture.sprite().getInstaShieldObject();
        assertNotNull(shield);
        assertNotNull(invincibility);
        AbstractObjectInstance instaShieldObject = assertInstanceOf(
                AbstractObjectInstance.class, instaShield);
        fixture.sprite().getPowerUpSpawner().registerObject(instaShield);
        assertEquals(1, act1Objects.getActiveObjects().stream()
                .filter(candidate -> candidate == instaShield).count());
        assertEquals(1, act1Objects.snapshotAllLiveSstObjectsForTransition().stream()
                .filter(occupant -> occupant.identity() == instaShield).count(),
                "the pre-registered insta-shield must enter the ALL_LIVE_SST carry snapshot");
        int shieldSlot = ((AbstractObjectInstance) shield).getSlotIndex();
        int invincibilitySlot = ((AbstractObjectInstance) invincibility).getSlotIndex();
        int instaShieldSlot = instaShieldObject.getSlotIndex();
        var preTransitionInstaEntries = act1Objects.rewindSnapshottable().capture()
                .dynamicObjects().stream()
                .filter(entry -> entry.slotIndex() == instaShieldSlot
                        && entry.className().equals(instaShield.getClass().getName()))
                .toList();
        assertEquals(1, preTransitionInstaEntries.size());
        var instaShieldRewindId = preTransitionInstaEntries.getFirst().objectId();
        var carriedGamestate = level.getLevelGamestate();

        List<AbstractPlayableSprite> players = new ArrayList<>();
        players.add(fixture.sprite());
        players.addAll(GameServices.sprites().getSidekicks());
        int[] x = players.stream().mapToInt(AbstractPlayableSprite::getCentreX).toArray();
        int[] y = players.stream().mapToInt(AbstractPlayableSprite::getCentreY).toArray();
        players.forEach(player -> player.setControlLocked(true));

        Sonic3kLevelEventManager manager = (Sonic3kLevelEventManager)
                GameServices.module().getLevelEventProvider();
        Sonic3kFBZEvents events = manager.getFbzEvents();
        events.setEventsFg5(true);
        events.updateAct1BackgroundEvent(fixture.sprite().getCentreX(),
                fixture.sprite().getCentreY(), false);

        assertFalse(events.isEventsFg5());
        assertEquals(1, level.getCurrentAct());
        assertEquals(List.of(0x1C, 0x01, 0x4E),
                ((Sonic3kLevel) level.getCurrentLevel()).getPatternLoadCueSchedule(),
                "the production FBZ reload plan must queue PLC $1C once and omit $1D");
        assertFbz2PaletteSurface();
        assertEquals(0, level.getApparentAct());
        assertSame(carriedGamestate, level.getLevelGamestate());
        assertNotSame(act1Objects, level.getObjectManager());
        assertSame(shield, fixture.sprite().getShieldObject());
        assertSame(invincibility, fixture.sprite().getInvincibilityObject());
        assertEquals(shieldSlot, ((AbstractObjectInstance) shield).getSlotIndex());
        assertEquals(invincibilitySlot, ((AbstractObjectInstance) invincibility).getSlotIndex());
        assertEquals(1, level.getObjectManager().getActiveObjects().stream()
                .filter(candidate -> candidate == shield).count());
        assertEquals(1, level.getObjectManager().getActiveObjects().stream()
                .filter(candidate -> candidate == invincibility).count());
        assertSame(instaShield, fixture.sprite().getInstaShieldObject());
        assertEquals(instaShieldSlot, instaShieldObject.getSlotIndex());
        assertEquals(1, level.getObjectManager().getActiveObjects().stream()
                .filter(candidate -> candidate == instaShield).count());
        assertEquals(1, level.getObjectManager().snapshotAllLiveSstObjectsForTransition().stream()
                .filter(occupant -> occupant.identity() == instaShield).count());
        var postTransitionInstaEntries = level.getObjectManager().rewindSnapshottable().capture()
                .dynamicObjects().stream()
                .filter(entry -> entry.slotIndex() == instaShieldSlot
                        && entry.className().equals(instaShield.getClass().getName()))
                .toList();
        assertEquals(1, postTransitionInstaEntries.size());
        assertEquals(instaShieldRewindId, postTransitionInstaEntries.getFirst().objectId());
        assertSpawnerOwns(fixture.sprite(), level.getObjectManager());
        assertEquals(0x0020, camera.getMinX() & 0xFFFF);
        assertEquals(0x00A0, camera.getMaxX() & 0xFFFF);
        assertEquals(0x0540, camera.getMinY() & 0xFFFF);
        assertEquals(0x0540, camera.getMaxY() & 0xFFFF);
        assertEquals(0x2D10, camera.getMinXTarget() & 0xFFFF);
        assertEquals(0x2FB0, camera.getMaxXTarget() & 0xFFFF);
        assertEquals(0x0520, camera.getMinYTarget() & 0xFFFF);
        assertEquals(0x0560, camera.getMaxYTarget() & 0xFFFF);
        assertTrue(level.isTransitionRingInitializationPendingForRewind(),
                "the same-call FBZ deform tail must finish before later Load_Rings");
        assertEquals(0xFF60_FFE2, parallax.getHScroll()[0],
                "publication-frame Reset actual -> FBZ_Deform -> Reset effective -> GoDeform line 0");
        assertEquals(0xFF60_FFBA, parallax.getHScroll()[31]);
        assertEquals(0xFF60_FFC4, parallax.getHScroll()[95]);
        assertEquals(0xFF60_FFA6, parallax.getHScroll()[159]);
        assertEquals(0x028B, parallax.getVscrollFactorBG() & 0xFFFF);
        assertEquals(70, parallax.getMinScroll());
        assertEquals(130, parallax.getMaxScroll());
        var act2Rings = level.getCurrentLevel().getRings();
        assertFalse(act2Rings.isEmpty(), "FBZ2 must expose its authored ring source");
        assertEquals(Integer.MIN_VALUE, level.getRingManager().capture().placementLastCameraX(),
                "ScreenEvents binds FBZ2 ring data but Load_Rings has not run yet");
        assertTrue(level.getRingManager().getActiveSpawns().isEmpty(),
                "ring windowing must remain uninitialized inside ScreenEvents");
        for (var ring : act2Rings) {
            // Coordinate lookup selects the first authored placement when
            // multiple ROM ring entries occupy the same position.
            var firstAtPosition = act2Rings.stream()
                    .filter(candidate -> candidate.x() == ring.x() && candidate.y() == ring.y())
                    .findFirst().orElseThrow();
            assertSame(firstAtPosition, level.getRingManager().resolveCanonicalSpawn(ring.x(), ring.y()),
                    "Load_Rings must install every canonical FBZ2 ring during ScreenEvents");
        }
        var act1OnlyRing = act1Rings.stream()
                .filter(old -> act2Rings.stream().noneMatch(next -> next.x() == old.x() && next.y() == old.y()))
                .findFirst().orElseThrow();
        assertNull(level.getRingManager().resolveCanonicalSpawn(act1OnlyRing.x(), act1OnlyRing.y()),
                "the same-frame RingManager must no longer resolve the replaced FBZ1 source");

        var objectsBeforeRingPhase = List.copyOf(level.getObjectManager().getActiveObjects());
        level.update();
        assertEquals(camera.getX(), level.getRingManager().capture().placementLastCameraX(),
                "the later same-frame level/ring phase must window FBZ2 from the post-offset camera");
        assertEquals(objectsBeforeRingPhase, level.getObjectManager().getActiveObjects(),
                "running only the later ring phase must not instantiate next-frame FBZ2 objects");
        for (int i = 0; i < players.size(); i++) {
            assertEquals((x[i] - 0x2E00) & 0xFFFF, players.get(i).getCentreX() & 0xFFFF);
            assertEquals(y[i] & 0xFFFF, players.get(i).getCentreY() & 0xFFFF);
            assertTrue(players.get(i).isControlLocked());
        }
    }

    private static void assertSpawnerOwns(AbstractPlayableSprite player,
                                          ObjectManager expected) throws Exception {
        assertInstanceOf(DefaultPowerUpSpawner.class, player.getPowerUpSpawner());
        var field = DefaultPowerUpSpawner.class.getDeclaredField("objectManager");
        field.setAccessible(true);
        assertSame(expected, field.get(player.getPowerUpSpawner()));
    }

    private static int readPrivateInt(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T privateField(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + name, e);
        }
    }

    private static void assertExactIdentityAndSlot(ObjectManager manager,
                                                   AbstractObjectInstance identity,
                                                   int originalSlot) {
        assertEquals(originalSlot, identity.getSlotIndex());
        assertTrue(manager.getActiveObjects().stream().anyMatch(candidate -> candidate == identity),
                identity.getClass().getSimpleName()
                        + " must survive as the same identity in original SST slot " + originalSlot);
    }

    private static void assertFbz2PaletteSurface() throws Exception {
        var rom = GameServices.rom().getRom();
        int entry = Sonic3kConstants.PAL_POINTERS_ADDR
                + 0x13 * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
        int source = rom.read32BitAddr(entry) & 0x00FF_FFFF;
        int ramDest = rom.read16BitAddr(entry + 4) & 0xFFFF;
        int byteCount = ((rom.read16BitAddr(entry + 6) & 0xFFFF) + 1) * 4;
        int startLine = (ramDest & 0xFF) / Palette.PALETTE_SIZE_IN_ROM;
        byte[] bytes = rom.readBytes(source, byteCount);
        for (int offset = 0; offset < byteCount; offset += Palette.PALETTE_SIZE_IN_ROM) {
            int line = startLine + offset / Palette.PALETTE_SIZE_IN_ROM;
            Palette expected = new Palette();
            expected.fromSegaFormat(java.util.Arrays.copyOfRange(
                    bytes, offset, offset + Palette.PALETTE_SIZE_IN_ROM));
            Palette actual = GameServices.level().getCurrentLevel().getPalette(line);
            for (int color = 0; color < Palette.PALETTE_SIZE; color++) {
                assertEquals(expected.getColor(color).r, actual.getColor(color).r);
                assertEquals(expected.getColor(color).g, actual.getColor(color).g);
                assertEquals(expected.getColor(color).b, actual.getColor(color).b);
            }
        }
    }
}
