package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.GameStateManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMushmeanieBadnikInstance {

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void registryCreatesMushmeanieForSklSlot8dInMhz() {
        Sonic3kObjectRegistry registry = new MhzRegistry();

        ObjectInstance instance = registry.create(new ObjectSpawn(0x120, 0x100,
                Sonic3kObjectIds.MUSHMEANIE, 0, 0, false, 0));

        assertInstanceOf(MushmeanieBadnikInstance.class, instance);
    }

    @Test
    void usesRomRenderBoundsAndShellPriorityFromObjData() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        MushmeanieBadnikInstance.ShellChild shell = new MushmeanieBadnikInstance.ShellChild(mushmeanie);

        assertEquals(0x08, mushmeanie.getOnScreenHalfWidth(),
                "ObjDat_Mushmeanie width_pixels byte is $08");
        assertEquals(0x08, mushmeanie.getOnScreenHalfHeight(),
                "ObjDat_Mushmeanie height_pixels byte is $08");
        assertEquals(0x0C, shell.getOnScreenHalfWidth(),
                "word_8DCD6 shell width_pixels byte is $0C");
        assertEquals(0x08, shell.getOnScreenHalfHeight(),
                "word_8DCD6 shell height_pixels byte is $08");
        assertEquals(4, shell.getPriorityBucket(),
                "word_8DCD6 shell priority word $200 maps to render bucket 4");
    }

    @Test
    void setupUsesRomInitialBodyMappingFrame() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x1A0, 0x100);

        advancePastWaitOffscreenInit(mushmeanie, player);

        assertEquals(1, mappingFrameOf(mushmeanie),
                "ObjDat_Mushmeanie stores mapping_frame=$01 during SetUp_ObjAttributes");
    }

    @Test
    void fullRomCollisionByteStillUsesEnemyTouchSemantics() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x120, 0x100);

        advancePastWaitOffscreenInit(mushmeanie, player);

        assertEquals(0xD7, mushmeanie.getCollisionFlags());
        assertEquals(TouchCategoryDecodeMode.FORCE_ENEMY,
                mushmeanie.getTouchResponseProfile().categoryDecodeMode(),
                "sub_8DC6E handles Check_PlayerAttack/HurtCharacter_Directly despite ObjDat collision_flags=$D7");
    }

    @Test
    void startsSleepingUntilPlayerIsWithinRomEightyPixelRange() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x1A0, 0x100);

        advancePastWaitOffscreenInit(mushmeanie, player);
        mushmeanie.update(2, player);

        assertEquals("SLEEPING", mushmeanie.getStateName());
        assertEquals(0x120, mushmeanie.getX());
        assertEquals(0x100, mushmeanie.getY());

        player.setCentreX((short) 0x19F);
        mushmeanie.update(3, player);

        assertEquals("WAKING", mushmeanie.getStateName());
    }

    @Test
    void nativeP2InsideRomRangeWakesMushmeanieWhenP1IsTooFar() {
        TestablePlayableSprite sidekick = player(0x121, 0x100);
        MushmeanieBadnikInstance mushmeanie = mushmeanie(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = player(0x1A0, 0x100);

        advancePastWaitOffscreenInit(mushmeanie, sonic);
        mushmeanie.update(2, sonic);

        assertEquals("WAKING", mushmeanie.getStateName(),
                "Find_SonicTails chooses the closest native player before comparing d2 with $80");
    }

    @Test
    void findSonicTailsRangeUsesSignedWordDistanceAtCoordinateWrap() {
        AbstractObjectInstance.updateCameraBounds(0xFF00, 0x80, 0x10040, 0x160, 0);
        TestablePlayableSprite sidekick = player(0x7F00, 0x100);
        MushmeanieBadnikInstance mushmeanie = new MushmeanieBadnikInstance(new ObjectSpawn(
                0xFFE0, 0x100, Sonic3kObjectIds.MUSHMEANIE, 0, 0, false, 0));
        mushmeanie.setServices(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = player(0x0020, 0x100);

        mushmeanie.update(0, sonic);
        mushmeanie.update(1, sonic);
        mushmeanie.update(2, sonic);

        assertEquals("WAKING", mushmeanie.getStateName(),
                "loc_8DB3E uses Find_SonicTails d2; $FFE0-$0020 wraps to a $40 activation distance");
    }

    @Test
    void objWaitOffscreenSuppressesShellSetupAndCollisionUntilVisible() {
        SpawnHarness harness = new SpawnHarness();
        MushmeanieBadnikInstance mushmeanie = mushmeanie(harness.services);
        TestablePlayableSprite player = player(0x120, 0x100);

        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 0xC0, 0);
        mushmeanie.update(0, player);

        assertEquals(0, harness.spawned.size());
        assertEquals(0, mushmeanie.getCollisionFlags());
        assertEquals("SLEEPING", mushmeanie.getStateName());

        putMushmeanieOnScreen();
        mushmeanie.update(1, player);

        assertEquals(0, harness.spawned.size());
        assertEquals(0, mushmeanie.getCollisionFlags());

        mushmeanie.update(2, player);

        assertEquals(1, harness.spawned.size(),
                "Obj_WaitOffscreen returns before loc_8DB1E creates the shell child");
        assertEquals("SLEEPING", mushmeanie.getStateName(),
                "loc_8DB1E setup returns before the range-detection routine can run");
        assertEquals(0xD7, mushmeanie.getCollisionFlags(),
                "ObjDat_Mushmeanie stores collision_flags=$D7, not just size index $17");

        mushmeanie.update(3, player);

        assertEquals("WAKING", mushmeanie.getStateName());
    }

    @Test
    void wakingAnimationRaisesBodyBeforeJumpingTowardPlayer() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x100, 0x100);

        advancePastWaitOffscreenInit(mushmeanie, player);
        mushmeanie.update(2, player);
        assertEquals("WAKING", mushmeanie.getStateName());

        for (int frame = 3; frame <= 7; frame++) {
            mushmeanie.update(frame, player);
        }

        assertEquals(0x0FA, mushmeanie.getY());
        assertEquals("JUMPING", mushmeanie.getStateName());
        assertEquals(-0x100, mushmeanie.getXVelocity());
        assertEquals(-0x300, mushmeanie.getYVelocity());
    }

    @Test
    void wakingRawMultiDelayScriptStartsJumpOnFifthAnimatedUpdate() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x100, 0x100);

        advancePastWaitOffscreenInit(mushmeanie, player);
        mushmeanie.update(2, player);

        for (int frame = 3; frame <= 6; frame++) {
            mushmeanie.update(frame, player);
        }
        assertEquals("WAKING", mushmeanie.getStateName(),
                "byte_8DCE6 has not reached its $F4 callback after four Animate_RawMultiDelay updates");

        mushmeanie.update(7, player);

        assertEquals("JUMPING", mushmeanie.getStateName(),
                "byte_8DCE6 terminates through loc_8DB76 on the fifth animated update");
        assertEquals(0x0FA, mushmeanie.getY(),
                "the wake script raises y_pos by 3 on frames 1 and 4 before starting the jump");
        assertEquals(-0x100, mushmeanie.getXVelocity());
        assertEquals(-0x300, mushmeanie.getYVelocity());
    }

    @Test
    void jumpDirectionTracksPlayerOneEvenWhenNativeP2TriggeredWake() {
        TestablePlayableSprite playerOne = player(0x1A0, 0x100);
        TestablePlayableSprite sidekick = player(0x100, 0x100);
        TestObjectServices services = new TestObjectServices() {
            @Override
            public ObjectPlayerQuery playerQuery() {
                return new ObjectPlayerQuery(() -> playerOne, () -> List.of(sidekick));
            }
        };
        services.withGameState(mock(GameStateManager.class));
        MushmeanieBadnikInstance mushmeanie = mushmeanie(services);

        advancePastWaitOffscreenInit(mushmeanie, sidekick);
        mushmeanie.update(2, sidekick);
        for (int frame = 3; frame <= 7; frame++) {
            mushmeanie.update(frame, sidekick);
        }

        assertEquals("JUMPING", mushmeanie.getStateName());
        assertEquals(0x100, mushmeanie.getXVelocity(),
                "loc_8DB76 calls Set_VelocityXTrackSonic, which hardwires Player_1 as the tracking target");
    }

    @Test
    void jumpingBouncesOffSideWallsLikeRom() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x19F, 0x100);
        advanceToJumping(mushmeanie, player);
        assertEquals("JUMPING", mushmeanie.getStateName());
        assertEquals(0x100, mushmeanie.getXVelocity());

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(0x129, 0x0F7))
                    .thenReturn(new TerrainCheckResult(-4, (byte) 0, 0));

            mushmeanie.update(12, player);
        }

        assertEquals(0x11D, mushmeanie.getX(),
                "loc_8DBB4 applies the negative wall distance to x_pos before reversing");
        assertEquals(-0x100, mushmeanie.getXVelocity(),
                "loc_8DBB4 negates x_vel after a right-wall collision");
    }

    @Test
    void jumpingUsesRomMoveSpriteLightGravityStep() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x19F, 0x100);
        advanceToJumping(mushmeanie, player);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());

            mushmeanie.update(12, player);
        }

        assertEquals(0x121, mushmeanie.getX(),
                "MoveSprite_LightGravity applies x_vel=$100 before any floor check");
        assertEquals(0x0F7, mushmeanie.getY(),
                "MoveSprite_LightGravity applies old y_vel=-$300 before gravity");
        assertEquals(-0x2E0, mushmeanie.getYVelocity(),
                "MoveSprite_LightGravity adds #$20 to y_vel after moving");
    }

    @Test
    void jumpDoesNotEnterLandingRoutineUntilFloorCollision() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x19F, 0x100);
        advanceToJumping(mushmeanie, player);
        assertEquals("JUMPING", mushmeanie.getStateName());

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());

            for (int frame = 12; frame <= 44; frame++) {
                mushmeanie.update(frame, player);
            }
        }

        assertEquals("JUMPING", mushmeanie.getStateName(),
                "loc_8DBC6 calls ObjHitFloor_DoRoutine; nonnegative y_vel alone must not start loc_8DBD4");
        assertTrue(mushmeanie.getYVelocity() >= 0);
    }

    @Test
    void landingAnimationFirstAdvanceAppliesRomYOffset() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x19F, 0x100);
        advanceToJumping(mushmeanie, player);
        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));

            for (int frame = 12; frame <= 44 && !"LANDING".equals(mushmeanie.getStateName()); frame++) {
                mushmeanie.update(frame, player);
            }
            assertEquals("LANDING", mushmeanie.getStateName());
            int landedY = mushmeanie.getY();

            mushmeanie.update(45, player);

            assertEquals(landedY + 3, mushmeanie.getY(),
                    "loc_8DBEC adds word_8DC08[anim_frame=2] when byte_8DCED first advances");
        }
    }

    @Test
    void landingAnimationCompletionStartsNextJumpWithoutReturningToWakeup() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x100, 0x100);
        advanceToJumping(mushmeanie, player);
        player.setCentreX((short) 0x80);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));

            for (int frame = 12; frame <= 44 && !"LANDING".equals(mushmeanie.getStateName()); frame++) {
                mushmeanie.update(frame, player);
            }
            assertEquals("LANDING", mushmeanie.getStateName());

            for (int frame = 45; frame <= 55; frame++) {
                mushmeanie.update(frame, player);
            }

            assertEquals("JUMPING", mushmeanie.getStateName(),
                    "byte_8DCED terminates through loc_8DB76, directly starting the next jump");
            assertEquals(-0x300, mushmeanie.getYVelocity());
            assertEquals(-0x100, mushmeanie.getXVelocity());
        }
    }

    @Test
    void firstAttackRemovesShellCollisionTemporarilyAndSecondAttackDefeats() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x120, 0x100);
        TouchResponseResult result = new TouchResponseResult(0x0A, 0, 0, TouchCategory.ENEMY);

        advancePastWaitOffscreenInit(mushmeanie, player);
        mushmeanie.onPlayerAttack(player, result);

        assertFalse(mushmeanie.isDestroyed());
        assertEquals(0, mushmeanie.getCollisionSizeIndexForTest());
        assertEquals(0x20, mushmeanie.getShellRecoverTimer());

        for (int frame = 0; frame < 0x20; frame++) {
            mushmeanie.update(frame, player);
        }

        assertEquals(0x17, mushmeanie.getCollisionSizeIndexForTest());

        mushmeanie.onPlayerAttack(player, result);

        assertTrue(mushmeanie.isDestroyed());
    }

    @Test
    void attackDuringShellRecoveryCannotConsumeSecondHit() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        TestablePlayableSprite player = player(0x120, 0x100);
        TouchResponseResult result = new TouchResponseResult(0x0A, 0, 0, TouchCategory.ENEMY);

        advancePastWaitOffscreenInit(mushmeanie, player);

        mushmeanie.onPlayerAttack(player, result);
        mushmeanie.onPlayerAttack(player, result);

        assertFalse(mushmeanie.isDestroyed(),
                "loc_8DC9C clears collision_flags and seeds $20=$20, so sub_8DC6E cannot take a second hit "
                        + "until the recovery counter restores collision_flags=$D7");
        assertEquals(0, mushmeanie.getCollisionSizeIndexForTest());

        for (int frame = 0; frame < 0x20; frame++) {
            mushmeanie.update(frame, player);
        }
        mushmeanie.onPlayerAttack(player, result);

        assertTrue(mushmeanie.isDestroyed(),
                "once $20 reaches zero, sub_8DC6E restores collision_flags and the next valid attack defeats it");
    }

    @Test
    void shellChildTracksParentUntilFirstHitThenLaunchesWithRomVelocity() {
        SpawnHarness harness = new SpawnHarness();
        MushmeanieBadnikInstance mushmeanie = mushmeanie(harness.services);
        TestablePlayableSprite player = player(0x120, 0x100);

        advancePastWaitOffscreenInit(mushmeanie, player);

        assertEquals(1, harness.spawned.size(),
                "loc_8DB1E creates one loc_8DC14 shell child with CreateChild1_Normal");
        MushmeanieBadnikInstance.ShellChild shell = assertInstanceOf(
                MushmeanieBadnikInstance.ShellChild.class, harness.spawned.get(0));
        assertEquals(0x120, shell.getX());
        assertEquals(0x100, shell.getY());

        player.setXSpeed((short) 0x180);
        TouchResponseResult result = new TouchResponseResult(0x0A, 0, 0, TouchCategory.ENEMY);
        mushmeanie.onPlayerAttack(player, result);
        shell.update(1, player);

        assertEquals(0x122, shell.getX(),
                "loc_8DC42 reads the negated player x_vel and moves the shell at +$200");
        assertEquals(0x0FE, shell.getY(),
                "loc_8DC42 gives the shell y_vel=-$200 before MoveSprite_LightGravity");
        assertEquals(0x200, shell.getXVelocity());
        assertEquals(-0x1E0, shell.getYVelocity(),
                "MoveSprite_LightGravity applies +$20 gravity after the launch move");
    }

    @Test
    void shellChildLaunchDirectionUsesAttackingPlayerSavedOnParent() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        MushmeanieBadnikInstance.ShellChild shell = new MushmeanieBadnikInstance.ShellChild(mushmeanie);
        TestablePlayableSprite sonic = player(0x120, 0x100);
        TestablePlayableSprite sidekick = player(0x120, 0x100);
        sonic.setXSpeed((short) 0x100);
        sidekick.setXSpeed((short) 0x180);
        TouchResponseResult result = new TouchResponseResult(0x0A, 0, 0, TouchCategory.ENEMY);

        mushmeanie.onPlayerAttack(sidekick, result);
        shell.update(1, sonic);

        assertEquals(0x200, shell.getXVelocity(),
                "loc_8DC42 reads parent $44, the player that hit the shell, not whichever player is updating the object");
    }

    @Test
    void launchedShellDeletesAfterMoveWhenOutsideSpriteCheckDeleteXYWindow() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x100, 0x100, 0);
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        MushmeanieBadnikInstance.ShellChild shell = new MushmeanieBadnikInstance.ShellChild(mushmeanie);
        TestablePlayableSprite player = player(0x120, 0x100);
        TouchResponseResult result = new TouchResponseResult(0x0A, 0, 0, TouchCategory.ENEMY);

        player.setXSpeed((short) 0x180);
        mushmeanie.onPlayerAttack(player, result);
        shell.update(1, player);

        assertEquals(0x122, shell.getX(),
                "loc_8DC62 runs MoveSprite_LightGravity before Sprite_CheckDeleteXY");
        assertTrue(shell.isDestroyed(),
                "loc_8DC62 must delete the launched shell once Sprite_CheckDeleteXY sees it outside the viewport");
        assertTrue(shell.isDestroyedRespawnable(),
                "Sprite_CheckDeleteXY is an offscreen self-delete, not a permanent defeat");
    }

    @Test
    void attachedShellChildDeletesWhenParentIsDestroyed() {
        MushmeanieBadnikInstance mushmeanie = mushmeanie();
        MushmeanieBadnikInstance.ShellChild shell = new MushmeanieBadnikInstance.ShellChild(mushmeanie);
        TestablePlayableSprite player = player(0x120, 0x100);

        mushmeanie.setDestroyed(true);
        shell.update(1, player);

        assertTrue(shell.isDestroyed(),
                "loc_8DC24 jumps through Child_Draw_Sprite before launch, so parent status bit 7 deletes the shell");
    }

    @Test
    void shellChildRendersWithRomPaletteLineTwo() {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(com.openggf.game.sonic3k.Sonic3kObjectArtKeys.MUSHMEANIE))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        MushmeanieBadnikInstance mushmeanie = mushmeanie(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withLevelManager(levelManager));
        MushmeanieBadnikInstance.ShellChild shell = new MushmeanieBadnikInstance.ShellChild(mushmeanie);
        shell.setServices(new TestObjectServices().withLevelManager(levelManager));

        shell.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(0, 0x120, 0x100, false, false, 2);
    }

    private static MushmeanieBadnikInstance mushmeanie() {
        return mushmeanie(new TestObjectServices().withGameState(mock(GameStateManager.class)));
    }

    private static MushmeanieBadnikInstance mushmeanie(TestObjectServices services) {
        MushmeanieBadnikInstance mushmeanie = new MushmeanieBadnikInstance(new ObjectSpawn(
                0x120, 0x100, Sonic3kObjectIds.MUSHMEANIE, 0, 0, false, 0));
        mushmeanie.setServices(services);
        return mushmeanie;
    }

    private static void advancePastWaitOffscreenInit(MushmeanieBadnikInstance mushmeanie,
            TestablePlayableSprite player) {
        putMushmeanieOnScreen();
        mushmeanie.update(0, player);
        mushmeanie.update(1, player);
    }

    private static void advanceToJumping(MushmeanieBadnikInstance mushmeanie,
            TestablePlayableSprite player) {
        advancePastWaitOffscreenInit(mushmeanie, player);
        mushmeanie.update(2, player);
        for (int frame = 3; frame <= 7; frame++) {
            mushmeanie.update(frame, player);
        }
    }

    private static TestablePlayableSprite player(int x, int y) {
        return new TestablePlayableSprite("sonic", (short) x, (short) y);
    }

    private static int mappingFrameOf(MushmeanieBadnikInstance mushmeanie) {
        try {
            Field field = AbstractS3kBadnikInstance.class.getDeclaredField("mappingFrame");
            field.setAccessible(true);
            return field.getInt(mushmeanie);
        } catch (ReflectiveOperationException e) {
            fail(e);
            return -1;
        }
    }

    private static void putMushmeanieOnScreen() {
        AbstractObjectInstance.updateCameraBounds(0x80, 0x80, 0x1C0, 0x160, 0);
    }

    private static final class MhzRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
        }
    }

    private static final class SpawnHarness {
        private final List<ObjectInstance> spawned = new ArrayList<>();
        private final TestObjectServices services;

        private SpawnHarness() {
            ObjectManager objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                spawned.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));

            LevelManager levelManager = mock(LevelManager.class);
            org.mockito.Mockito.when(levelManager.getObjectManager()).thenReturn(objectManager);
            services = new TestObjectServices()
                    .withGameState(mock(GameStateManager.class))
                    .withLevelManager(levelManager);
        }
    }
}
