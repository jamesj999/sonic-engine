package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.GameRng;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
import com.openggf.game.rules.GameRules;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@FullReset
@ExtendWith(SingletonResetExtension.class)
class TestS3kSignpostInstance {

    @Test
    void shortNativeResultsTailUsesOneChildRetireDispatch() {
        // ROM Obj_LevelResultsWait2 sees $30(a0) reach zero one pass after the
        // last child SST deletes (children allocate after the parent,
        // docs/skdisasm/sonic3k.asm:62600, 62691-62693), and the player-visible
        // release lands on the following dispatch. The engine's next-dispatch
        // onExitReady supplies the first pass; only the carried and waited
        // owners retain a synthetic retire dispatch after that boundary.
        assertEquals(1, S3kSignpostInstance.resultsChildRetireDispatches(false, false, true));
        assertEquals(1, S3kSignpostInstance.resultsChildRetireDispatches(true, false, false));
        assertEquals(0, S3kSignpostInstance.resultsChildRetireDispatches(false, true, false));
        assertEquals(3, S3kSignpostInstance.resultsChildRetireDispatches(false, false, false));
    }

    @Test
    void sparkleYOffsetConsumesRomRandomWord() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 0x00001234L);

        assertEquals(6, S3kSignpostInstance.romSparkleYOffset(rng));
        assertEquals(0xEA56EA54L, rng.getSeed(),
                "Obj_SignpostSparkle calls Random_Number once before masking d0 with $1F "
                        + "(docs/skdisasm/sonic3k.asm:176294-176300)");
    }

    @Test
    void sparkleCadenceUsesGlobalVIntPhaseRatherThanAllocationAge() {
        assertFalse(S3kSignpostInstance.isRomSparkleFrame(11023));
        assertTrue(S3kSignpostInstance.isRomSparkleFrame(11024));
        assertFalse(S3kSignpostInstance.isRomSparkleFrame(11025));
        assertTrue(S3kSignpostInstance.isRomSparkleFrame(11028));
    }

    @Test
    void bumpFromBelowRequiresRomAnimationTwoAndUpwardVelocity() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setAir(true);
        player.setYSpeed((short) -0x100);
        player.setAnimationId(Sonic3kAnimationIds.SPRING);

        assertFalse(S3kSignpostInstance.hasRomBumpPose(player),
                "Obj_EndSign sub_83A70 rejects non-#2 animations before applying the upward hit "
                        + "(docs/skdisasm/sonic3k.asm:176372-176387)");

        player.setAnimationId(Sonic3kAnimationIds.ROLL);

        assertTrue(S3kSignpostInstance.hasRomBumpPose(player),
                "Obj_EndSign sub_83A70 accepts animation #2 with upward y_vel "
                        + "(docs/skdisasm/sonic3k.asm:176372-176387)");

        player.setYSpeed((short) 0);

        assertFalse(S3kSignpostInstance.hasRomBumpPose(player),
                "Obj_EndSign sub_83A70 rejects non-upward y_vel "
                        + "(docs/skdisasm/sonic3k.asm:176376-176377)");
    }

    @Test
    void nativeP1BumpSuppressesTheSameFrameNativeP2Bump() {
        TestablePlayableSprite sonic = eligibleBumpPlayer("sonic", 0x32B0, 0x045D);
        TestablePlayableSprite tails = eligibleBumpPlayer("tails", 0x329A, 0x045D);
        int signpostX = 0x329F;
        int signpostY = 0x045D;

        // Both are geometrically eligible; the ROM still only lets one of them
        // land the hit.
        assertTrue(S3kSignpostInstance.isRomBumpCandidate(signpostX, signpostY, sonic));
        assertTrue(S3kSignpostInstance.isRomBumpCandidate(signpostX, signpostY, tails));

        int finalVelocity = 0;
        for (TestablePlayableSprite player : List.of(sonic, tails)) {
            finalVelocity = S3kSignpostInstance.romBumpXVelocity(signpostX, player.getCentreX());
            break; // sub_83A70 tail-jumps away; Player 2 is never evaluated
        }

        assertEquals(-0x0110, finalVelocity,
                "Under FixBugs = 0 (docs/skdisasm/sonic3k.asm:176357-176365) sub_83A70 ends in "
                        + "jmp (HUD_AddToScore).l, so that routine's rts returns to loc_83A6A with d0 "
                        + "holding the 32-bit Score rather than the packed player addresses "
                        + "(docs/skdisasm/sonic3k.asm:17654-17665). The following swap/tst.w therefore "
                        + "cannot reach Tails, and the surviving wild read is inert: Score caps at "
                        + "$F423F so a1 is 0..$F, and every vector-table y_vel word it can address is "
                        + "$0000, which bpl.s locret_83ABC rejects. Player 1's kick stands.");
    }

    @Test
    void bumpRangeWordsEncodeOffsetAndWidth() {
        TestablePlayableSprite player = eligibleBumpPlayer("sonic", 0, 0);

        player.setCentreX((short) 0x120);
        assertFalse(S3kSignpostInstance.isRomBumpCandidate(0x100, 0, player),
                "EndSign_Range's second word is width $40 from x-$20, so x+$20 is exclusive");
        player.setCentreX((short) 0x11F);
        player.setCentreY((short) 0x18);
        assertFalse(S3kSignpostInstance.isRomBumpCandidate(0x100, 0, player),
                "EndSign_Range's fourth word is height $30 from y-$18, so y+$18 is exclusive");
        player.setCentreY((short) 0x17);
        assertTrue(S3kSignpostInstance.isRomBumpCandidate(0x100, 0, player),
                "Check_PlayerInRange adds each width to its negative origin "
                        + "(docs/skdisasm/sonic3k.asm:176410-176411,179994-180025)");
    }

    @Test
    void landedTimerAdvancesOnlyAfterSignedNegativePostDecrement() {
        assertFalse(S3kSignpostInstance.romPostLandTimerExpired(0),
                "Obj_EndSignLanded uses subq.w then bmi.s; timer 1 -> 0 must keep waiting "
                        + "(docs/skdisasm/sonic3k.asm:176198-176208)");
        assertTrue(S3kSignpostInstance.romPostLandTimerExpired(0xFFFF),
                "Obj_EndSignLanded advances only when the post-decrement word is negative "
                        + "(docs/skdisasm/sonic3k.asm:176198-176208)");
    }

    @Test
    void postObjectLandingCatchUpPreservesBumpedSignCadence() {
        assertEquals(0x3F, S3kSignpostInstance.initialPostLandTimer(0, true, false),
                "an unbumped post-object sign accounts for its already-consumed native dispatch");
        assertEquals(0x40, S3kSignpostInstance.initialPostLandTimer(0, true, true),
                "EndSign_CheckPlayerHit re-phases the falling owner, so a bumped sign keeps all $40 entries");
        assertEquals(0x3E, S3kSignpostInstance.initialPostLandTimer(1, true, false),
                "an explicit owner catch-up remains additive to the post-object allocation boundary");
    }

    @Test
    void groundedNoWaitKeepsIsolatedTimingCompensationUntilRealOwnerIsKnown() {
        assertEquals(S3kSignpostInstance.ResultsChildTimingAdjustment.UNSUPPORTED_GROUNDED_COMPENSATION,
                S3kSignpostInstance.resultsChildTimingAdjustment(false, false, false),
                "the grounded no-wait path retains an explicit engine compensation, "
                        + "not a claimed native SST owner");
        assertEquals(1,
                S3kSignpostInstance.ResultsChildTimingAdjustment.UNSUPPORTED_GROUNDED_COMPENSATION
                        .catchUpEntries());
        assertEquals(S3kSignpostInstance.ResultsChildTimingAdjustment.NONE,
                S3kSignpostInstance.resultsChildTimingAdjustment(true, false, false),
                "a sign that waited in routine 6 does not use the unsupported compensation");
        assertEquals(S3kSignpostInstance.ResultsChildTimingAdjustment.NONE,
                S3kSignpostInstance.resultsChildTimingAdjustment(false, true, false),
                "a post-object sign does not use the unsupported compensation");
        assertEquals(S3kSignpostInstance.ResultsChildTimingAdjustment.NONE,
                S3kSignpostInstance.resultsChildTimingAdjustment(false, false, true),
                "a separately retained grounded boundary does not use the unsupported compensation");
    }

    @Test
    void resultsAllocationRetainsNativeControlSlotBoundaryAcrossSplitOwners() {
        assertTrue(S3kSignpostInstance.usesFirstFreeResultsOwner(
                false, true, false, 4, 7),
                "a free slot below Obj_EndSignControl has already executed and must wait for the next pass");
        assertFalse(S3kSignpostInstance.usesFirstFreeResultsOwner(
                false, true, false, 5, 4),
                "a first-free slot above Obj_EndSignControl belongs to the forward same-pass allocation boundary");
    }

    @Test
    void fallingDispatchAppliesBumpBeforeGravityAndMovement() {
        assertEquals(-0x1F4, S3kSignpostInstance.romVelocityAfterGravity(-0x200),
                "Obj_EndSignFall checks the player hit before adding $0C gravity "
                        + "(docs/skdisasm/sonic3k.asm:176149-176160)");
        assertFalse(S3kSignpostInstance.romBumpCheckAvailableAfterCooldownEntry(1),
                "a nonzero $20 cooldown decrements and returns even when it becomes zero "
                        + "(docs/skdisasm/sonic3k.asm:176347-176405)");
        assertTrue(S3kSignpostInstance.romBumpCheckAvailableAfterCooldownEntry(0));
    }

    @Test
    void fallingDispatchSkipsExpiringCooldownThenAppliesBumpBeforeGravity() throws Exception {
        TestablePlayableSprite player = eligibleBumpPlayer("sonic", 0x200, 0x100);
        FallingDispatchServices services = new FallingDispatchServices(player);

        S3kSignpostInstance coolingSignpost = fallingSignpost(services, 1);
        coolingSignpost.update(1, player);

        assertEquals(0, privateInt(coolingSignpost, "bumpCooldown"),
                "EndSign_CheckPlayerHit returns after decrementing a nonzero entry cooldown");
        assertEquals(0x0C, privateInt(coolingSignpost, "yVel"),
                "the expiring cooldown must not admit a bump on the same dispatch");

        S3kSignpostInstance readySignpost = fallingSignpost(services, 0);
        readySignpost.update(1, player);

        assertEquals(-0x1F4, privateInt(readySignpost, "yVel"),
                "Obj_EndSignFall applies the player bump before its signed-word $0C gravity "
                        + "(docs/skdisasm/sonic3k.asm:176149-176160,176347-176405)");
    }

    @Test
    void mainEndingPoseDoesNotLockCtrl1LogicalInputHistory() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setGameRulesForTest(GameRules.SONIC_3K);
        player.setControlLocked(false);
        player.setSpindash(true);
        player.setPushing(true);
        player.setLogicalInputState(false, false, false, false, false);
        player.endOfTick();

        S3kSignpostInstance.applyMainPlayerEndingPose(player);

        assertTrue(player.isObjectControlSuppressesMovement(),
                "Set_PlayerEndingPose writes object_control=$81 to freeze movement "
                        + "(docs/skdisasm/sonic3k.asm:181977-181988)");
        assertFalse(player.isControlLocked(),
                "Set_PlayerEndingPose does not set Ctrl_1_locked; Obj_EndSignLanded only sets Ctrl_2_locked "
                        + "(docs/skdisasm/sonic3k.asm:176198-176218, 181977-181988)");
        assertFalse(player.getSpindash(),
                "Set_PlayerEndingPose clears spin_dash_flag before control is eventually restored");
        assertFalse(player.getPushing(),
                "Set_PlayerEndingPose clears Status_Push");

        player.setLogicalInputState(false, false, false, true, false);
        player.endOfTick();

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, player.getInputHistory(0),
                "Sonic_RecordPos must keep storing live Ctrl_1_logical for Tails' delayed follow input "
                        + "(docs/skdisasm/sonic3k.asm:21541-21545, 22119-22136)");
    }

    @Test
    void signpostCtrl2LockDoesNotApplyEndingPoseToSidekick() {
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        SidekickCpuController cpu = new SidekickCpuController(tails, sonic);
        tails.setXSpeed((short) 0x0060);
        tails.setYSpeed((short) -0x0100);
        tails.setGSpeed((short) 0x0060);
        tails.setAnimationId(Sonic3kAnimationIds.WALK);

        S3kSignpostInstance.applySidekickInputLock(tails);

        assertTrue(tails.isControlLocked(),
                "Obj_EndSignLanded writes Ctrl_2_locked before results");
        assertTrue(cpu.isController2SignedLocked(),
                "Ctrl_2_locked=$FF skips Tails_CPU_Control from the next player slot");
        assertEquals(0x0060, tails.getXSpeed());
        assertEquals(-0x0100, tails.getYSpeed());
        assertEquals(0x0060, tails.getGSpeed());
        assertEquals(Sonic3kAnimationIds.WALK.id(), tails.getAnimationId());
        assertFalse(tails.isObjectControlled(),
                "Set_PlayerEndingPose targets Player_1 here; Check_TailsEndPose runs later "
                        + "(docs/skdisasm/sonic3k.asm:176229-176238,181914-181943)");
    }

    @Test
    void laterCheckTailsEndPoseClearsLockAndStopsSidekick() {
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        SidekickCpuController cpu = new SidekickCpuController(tails, sonic);
        tails.setControlLocked(true);
        cpu.setController2SignedLocked(true);
        tails.setXSpeed((short) 0x0054);
        tails.setYSpeed((short) 0);
        tails.setGSpeed((short) 0x0054);

        S3kSignpostInstance.applySidekickEndingPose(tails);

        assertFalse(tails.isControlLocked());
        assertFalse(cpu.isController2SignedLocked());
        assertTrue(tails.isObjectControlled());
        assertEquals(0, tails.getXSpeed());
        assertEquals(0, tails.getYSpeed());
        assertEquals(0, tails.getGSpeed());
        assertEquals(Sonic3kAnimationIds.VICTORY.id(), tails.getAnimationId());
    }

    @Test
    void afterStateKeepsSignpostAliveInsideRomRangeBeyondGenericScreenMargin() throws Exception {
        Camera camera = new Camera();
        camera.setX((short) 0x31C0);
        camera.setY((short) 0x0400);
        AbstractObjectInstance.updateCameraBounds(0x31C0, 0x0400, 0x3300, 0x04E0, 0);
        S3kSignpostInstance signpost = new S3kSignpostInstance(0x32C0, 0);
        signpost.setServices(new TestObjectServices().withCamera(camera));
        setPrivateField(signpost, "state", enumConstant(signpost, "State", "AFTER"));
        setPrivateField(signpost, "worldY", 0x0390);

        signpost.update(0, null);

        assertFalse(signpost.isDestroyed(),
                "Obj_EndSignAfter keeps the signpost alive while "
                        + "y_pos-Camera_Y_pos+$80 is within $200, even when the generic "
                        + "64px object-screen margin would reject it "
                        + "(docs/skdisasm/sonic3k.asm:176244-176277)");
    }

    @Test
    void afterRangeUsesRomCoarseBackCameraWord() {
        assertTrue(S3kSignpostInstance.isWithinRomAfterRange(0x3140, 0x0400, 0x31C0, 0x0400),
                "Obj_EndSignAfter subtracts Camera_X_pos_coarse_back, which is "
                        + "(Camera_X_pos-$80)&$FF80, so the signpost remains alive at "
                        + "the ROM back edge (docs/skdisasm/sonic3k.asm:176262-176266,37550-37553)");
    }

    @Test
    void afterStateKeepsSignpostAliveOutsideRomRangeWhileResultsAreActive() throws Exception {
        Camera camera = new Camera();
        camera.setX((short) 0x3600);
        camera.setY((short) 0x0400);
        GameStateManager gameState = new GameStateManager();
        gameState.setEndOfLevelActive(true);

        S3kSignpostInstance signpost = new S3kSignpostInstance(0x32C0, 0);
        signpost.setServices(new TestObjectServices()
                .withCamera(camera)
                .withGameState(gameState));
        setPrivateField(signpost, "state", enumConstant(signpost, "State", "AFTER"));
        setPrivateField(signpost, "worldY", 0x0390);

        signpost.update(0, null);

        assertFalse(signpost.isDestroyed(),
                "Obj_EndSignAfter must not delete the signpost while Obj_LevelResults is active; "
                        + "CNZ moves the camera during the post-miniboss transition, but the signpost "
                        + "remains visible through the results screen");
    }

    @Test
    void resultsStateUsesCameraFocusedPlayerWhenUpdatePlayerIsNull() throws Exception {
        Camera camera = new Camera();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setAir(false);
        camera.setFocusedSprite(player);

        List<ObjectInstance> spawned = new ArrayList<>();
        ObjectManager objectManager = mock(ObjectManager.class);
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).registerRewindReconstructionChild(any());

        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        registry.install(new CnzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS, new Sonic3kCNZEvents()));

        S3kSignpostInstance signpost = new S3kSignpostInstance(0x32C0, 0);
        signpost.setServices(new SignpostResultsServices(camera, objectManager, registry));
        setPrivateField(signpost, "state", enumConstant(signpost, "State", "RESULTS"));

        ObjectConstructionContext.withRewindActiveRestore(() -> {
            signpost.update(0, null);
            return null;
        });

        assertTrue(spawned.stream().anyMatch(S3kResultsScreenObjectInstance.class::isInstance),
                "Obj_EndSignLanded must still allocate Obj_LevelResults when the active "
                        + "player is only available through the runtime player query/camera focus; "
                        + "CNZ spawns the signpost from the event path after the boss "
                        + "(docs/skdisasm/sonic3k.asm:176208-176218)");
        verify(objectManager).registerRewindReconstructionChild(any(S3kResultsScreenObjectInstance.class));
        verify(objectManager, never()).addDynamicObjectAfterCurrent(any());
    }

    private static TestablePlayableSprite eligibleBumpPlayer(String code, int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite(code, (short) 0, (short) 0);
        player.setCentreX((short) x);
        player.setCentreY((short) y);
        player.setYSpeed((short) -0x100);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        return player;
    }

    private static S3kSignpostInstance fallingSignpost(
            TestObjectServices services, int cooldown) throws Exception {
        S3kSignpostInstance signpost = new S3kSignpostInstance(0x200, 0);
        signpost.setServices(services);
        setPrivateField(signpost, "state", enumConstant(signpost, "State", "FALLING"));
        setPrivateField(signpost, "worldY", 0x100);
        setPrivateField(signpost, "bumpCooldown", cooldown);
        return signpost;
    }

    private static int privateInt(Object instance, String fieldName) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(instance);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Object instance, String enumSimpleName, String constantName) {
        for (Class<?> nested : instance.getClass().getDeclaredClasses()) {
            if (nested.getSimpleName().equals(enumSimpleName)) {
                return Enum.valueOf((Class<? extends Enum>) nested.asSubclass(Enum.class), constantName);
            }
        }
        throw new AssertionError("Missing nested enum " + enumSimpleName);
    }

    private static void setPrivateField(Object instance, String fieldName, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static final class SignpostResultsServices extends TestObjectServices {
        private final Camera camera;
        private final ObjectManager objectManager;
        private final ZoneRuntimeRegistry registry;

        private SignpostResultsServices(Camera camera, ObjectManager objectManager, ZoneRuntimeRegistry registry) {
            this.camera = camera;
            this.objectManager = objectManager;
            this.registry = registry;
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ZoneRuntimeRegistry zoneRuntimeRegistry() {
            return registry;
        }
    }

    private static final class FallingDispatchServices extends TestObjectServices {
        private final TestablePlayableSprite player;
        private final Camera camera = new Camera();
        private final GameStateManager gameState = new GameStateManager();

        private FallingDispatchServices(TestablePlayableSprite player) {
            this.player = player;
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> player, List::of);
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public GameStateManager gameState() {
            return gameState;
        }
    }
}
