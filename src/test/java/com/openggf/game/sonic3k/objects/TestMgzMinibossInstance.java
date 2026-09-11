package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.objects.boss.BossStateContext;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SingletonResetExtension.class)
@FullReset
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class TestMgzMinibossInstance {

    private static final TouchResponseResult ENEMY_HIT =
            new TouchResponseResult(0x10, 0, 0, TouchCategory.ENEMY);
    private static final String MGZ_MINIBOSS_PALETTE_OWNER = "s3k.mgz.miniboss";
    private static final int[] FLASH_INDICES = {12, 13, 14};
    private static final int[] FLASH_BRIGHT = {0x0EEE, 0x0EEE, 0x0EEE};
    private static final int[] ORIGINAL_WORDS = {0x0222, 0x0644, 0x0AAA};

    private Camera camera;

    @BeforeEach
    void setUp() {
        SessionManager.clear();
        camera = TestEnvironment.activeGameplayMode().getCamera();
        camera.resetState();
        camera.setX((short) 0);
        camera.setY((short) 0);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void initQueuesSongFadeTransitionInsteadOfPlayingMusicImmediately() {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);

        assertEquals(Sonic3kObjectIds.MGZ_MINIBOSS, services.gameState.getCurrentBossId());
        assertTrue(services.playedMusic.isEmpty(), "Init should not start miniboss music directly");
        assertEquals(0, services.fadeOutCalls, "Init should queue a fade helper instead of fading immediately");
        assertTrue(services.spawnedChildren.stream().anyMatch(SongFadeTransitionInstance.class::isInstance),
                "Init should spawn Obj_Song_Fade_Transition equivalent");
    }

    @Test
    void bodyUsesRomCollisionFlagsAndArmsUseHurtCollision() {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);

        assertEquals(0x10, boss.getCollisionFlags(), "Main body should use Tunnelbot collision flags");

        long armCount = services.spawnedChildren.stream()
                .filter(TouchResponseProvider.class::isInstance)
                .filter(child -> ((TouchResponseProvider) child).getCollisionFlags() == 0x9E)
                .count();
        assertEquals(2, armCount, "Expected both drill arms to use hurt collision");

        boss.onPlayerAttack(player, ENEMY_HIT);
        assertEquals(0, boss.getCollisionFlags(), "Body collision should disable during custom flash");
    }

    @Test
    void hitFlashSubmitsMgzMinibossPaletteOwnershipClaims() {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);
        boss.onPlayerAttack(player, ENEMY_HIT);

        services.paletteRegistry.beginFrame();
        boss.update(1, player);
        services.paletteRegistry.resolveInto(services.level.palettes(), null, null, null);

        for (int i = 0; i < FLASH_INDICES.length; i++) {
            int colorIndex = FLASH_INDICES[i];
            assertEquals(MGZ_MINIBOSS_PALETTE_OWNER,
                    services.paletteRegistry.ownerAt(PaletteSurface.NORMAL, 1, colorIndex),
                    "MGZ miniboss flash color " + colorIndex + " should be owned by the miniboss palette writer");
            assertColorWord(services.level.getPalette(1), colorIndex, FLASH_BRIGHT[i]);
        }
    }

    @Test
    void hitFlashRestoreSubmitsMgzMinibossPaletteOwnershipClaims() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        seedOriginalBossFlashColors(services.level.getPalette(1));
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);
        boss.onPlayerAttack(player, ENEMY_HIT);
        bossState(boss).invulnerabilityTimer = 1;

        services.paletteRegistry.beginFrame();
        boss.update(1, player);
        services.paletteRegistry.resolveInto(services.level.palettes(), null, null, null);

        for (int i = 0; i < FLASH_INDICES.length; i++) {
            int colorIndex = FLASH_INDICES[i];
            assertEquals(MGZ_MINIBOSS_PALETTE_OWNER,
                    services.paletteRegistry.ownerAt(PaletteSurface.NORMAL, 1, colorIndex),
                    "MGZ miniboss restore color " + colorIndex + " should be owned by the miniboss palette writer");
            assertColorWord(services.level.getPalette(1), colorIndex, ORIGINAL_WORDS[i]);
        }
    }

    @Test
    void returnSwingTransitionsBackToTunnelUpInsteadOfCeilingShake() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);
        BossStateContext state = bossState(boss);
        state.routine = 18;
        setPrivateInt(boss, "routineTimer", 0);

        boss.update(1, player);

        assertEquals(6, state.routine, "Post-return cycle must re-enter tunnel-up before the next shake phase");
    }

    @Test
    void harmlessDebrisDoesNotImplementTouchResponseButSpireDebrisDoes() throws Exception {
        Class<?> harmlessClass = Class.forName(
                "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingDebrisChild");
        var harmlessCtor = harmlessClass.getDeclaredConstructor(int.class, int.class, int.class, boolean.class);
        harmlessCtor.setAccessible(true);
        Object harmless = harmlessCtor.newInstance(0x100, 0x80, 2, false);
        assertFalse(harmless instanceof TouchResponseProvider,
                "Normal debris must not register as a touch-response object in this engine");

        Class<?> spireClass = Class.forName(
                "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingSpireChild");
        var spireCtor = spireClass.getDeclaredConstructor(int.class, int.class, int.class);
        spireCtor.setAccessible(true);
        Object spire = spireCtor.newInstance(0x100, 0x80, 0);
        assertTrue(spire instanceof TouchResponseProvider,
                "Spire debris should remain the only harmful touch-active debris");
        assertEquals(0x84, ((TouchResponseProvider) spire).getCollisionFlags());
    }

    @Test
    void ceilingSpireDeclaresAndConsumesShieldDeflectProfile() throws Exception {
        Class<?> spireClass = Class.forName(
                "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingSpireChild");
        var spireCtor = spireClass.getDeclaredConstructor(int.class, int.class, int.class);
        spireCtor.setAccessible(true);
        TouchResponseProvider spire = (TouchResponseProvider) spireCtor.newInstance(0x100, 0x80, 0);

        assertDoesNotThrow(() -> spireClass.getDeclaredMethod("getTouchResponseProfile"));
        assertDoesNotThrow(() -> spireClass.getDeclaredMethod("getTouchResponseProfile", boolean.class));

        TouchResponseProfile profile = spire.getTouchResponseProfile();
        assertEquals(TouchShieldDeflectCapability.SHIELD_DEFLECT, profile.shieldDeflectCapability());
        assertEquals(0x08, profile.shieldReactionFlags());

        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x120, (short) 0x80);
        assertTrue(spire.onShieldDeflect(player));
        assertEquals(0, spire.getCollisionFlags(),
                "loc_88820 clears the spire's collision response after the shield deflect");
    }

    @Test
    void upsideDownStatePersistsThroughDropAndRiseThenClearsBeforeTunnelUp() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);
        BossStateContext state = bossState(boss);

        state.routine = 10;
        setPrivateInt(boss, "routineTimer", -1);
        boss.update(1, player);
        assertTrue(getPrivateBoolean(boss, "upsideDown"), "Drop entry should flip the boss upside down");

        setPrivateInt(boss, "routineTimer", 0);
        boss.update(2, player);
        assertEquals(14, state.routine);
        assertTrue(getPrivateBoolean(boss, "upsideDown"), "Fall phase should stay upside down");

        setPrivateInt(boss, "routineTimer", 0);
        boss.update(3, player);
        assertEquals(16, state.routine);
        assertTrue(getPrivateBoolean(boss, "upsideDown"), "Rise phase should stay upside down");

        setPrivateInt(boss, "routineTimer", 0);
        boss.update(4, player);
        assertEquals(18, state.routine);
        assertTrue(getPrivateBoolean(boss, "upsideDown"), "Return swing should start upside down");

        setPrivateInt(boss, "routineTimer", 0);
        boss.update(5, player);
        assertEquals(18, state.routine,
                "StartNextCycle should retain the return-swing routine for its final Obj_Wait callback");
        assertEquals(0x1F, getPrivateInt(boss, "routineTimer"),
                "StartNextCycle should install the ROM's $1F release-wait counter");
        assertFalse(getPrivateBoolean(boss, "upsideDown"),
                "StartNextCycle should clear the vertical render flip before tunnel-up");

        for (int frame = 6; frame <= 36; frame++) {
            boss.update(frame, player);
            assertEquals(18, state.routine,
                    "Obj_Wait should retain routine $12 through countdown value zero");
            assertFalse(getPrivateBoolean(boss, "upsideDown"),
                    "The vertical render flip should stay cleared throughout the release wait");
        }
        assertEquals(0, getPrivateInt(boss, "routineTimer"),
                "Thirty-one Obj_Wait dispatches should count $1F down to zero without invoking the callback");

        boss.update(37, player);
        assertEquals(6, state.routine,
                "The thirty-second Obj_Wait dispatch should go negative and invoke RestartRumble");
        assertFalse(getPrivateBoolean(boss, "upsideDown"),
                "Tunnel-up should inherit the cleared vertical render state");
    }

    @Test
    void upsideDownBossMirrorsDrillArmOffsets() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);
        BossStateContext state = bossState(boss);
        state.x = 0x240;
        state.y = 0x180;
        setPrivateBoolean(boss, "facingRight", true);
        setPrivateBoolean(boss, "upsideDown", true);

        List<ObjectInstance> arms = services.spawnedChildren.stream()
                .filter(TouchResponseProvider.class::isInstance)
                .filter(child -> ((TouchResponseProvider) child).getCollisionFlags() == 0x9E)
                .toList();
        assertEquals(2, arms.size(), "Expected both drill arm children to be present");

        for (ObjectInstance arm : arms) {
            arm.update(1, player);
        }

        boolean foundLeft = arms.stream().anyMatch(arm -> arm.getX() == state.x + 0x1C && arm.getY() == state.y + 0x16);
        boolean foundRight = arms.stream().anyMatch(arm -> arm.getX() == state.x - 0x1C && arm.getY() == state.y + 0x16);
        assertTrue(foundLeft, "Upside-down mirrored state should move one drill arm to the lower-right");
        assertTrue(foundRight, "Upside-down mirrored state should move the other drill arm to the lower-left");
    }

    @Test
    void spireDebrisFallsBackToVisibleRendererWhenSpireSheetIsUnavailable() throws Exception {
        PatternSpriteRenderer debrisRenderer = mock(PatternSpriteRenderer.class);
        when(debrisRenderer.isReady()).thenReturn(true);

        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(eq("mgz_miniboss_spire"))).thenReturn(null);
        when(renderManager.getRenderer(eq("mgz_miniboss_debris"))).thenReturn(debrisRenderer);

        RecordingServices services = new RecordingServices(camera);
        services.renderManager = renderManager;

        Class<?> spireClass = Class.forName(
                "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingSpireChild");
        var spireCtor = spireClass.getDeclaredConstructor(int.class, int.class, int.class);
        spireCtor.setAccessible(true);
        AbstractObjectInstance spire = (AbstractObjectInstance) spireCtor.newInstance(0x120, 0x90, 0);
        spire.setServices(services);

        spire.appendRenderCommands(List.of());

        verify(debrisRenderer).drawFrameIndex(0, 0x120, 0x90, false, false);
    }

    void knucklesDropBranchSpawnsSpikePlatformAndUsesShortRecovery() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("knuckles", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);
        BossStateContext state = bossState(boss);
        state.routine = 10;
        setPrivateInt(boss, "routineTimer", -1);

        boss.update(1, player);

        assertTrue(services.spawnedChildren.stream()
                        .anyMatch(child -> child.getClass().getSimpleName().contains("KnucklesSpikePlatform")),
                "Knuckles branch should spawn the extra moving spike platform");

        setPrivateInt(boss, "routineTimer", 0);
        boss.update(2, player);

        for (int frame = 3; frame < 29; frame++) {
            boss.update(frame, player);
        }

        assertEquals(18, state.routine,
                "Knuckles branch should skip the full fall-then-rise recovery and reach the return swing quickly");
    }

    @Test
    void defeatQueuesLevelMusicFadeCameraScrollAndSignpostFlow() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);
        long initialSongFadeCount = services.spawnedChildren.stream()
                .filter(SongFadeTransitionInstance.class::isInstance)
                .count();
        BossStateContext state = bossState(boss);
        state.hitCount = 1;

        boss.onPlayerAttack(player, ENEMY_HIT);

        assertTrue(state.defeated, "Final hit should mark the boss defeated");
        assertEquals(1000, services.gameState.getScore(), "Boss defeat should award the standard score");
        assertEquals(0, services.fadeOutCalls, "ROM does not fade music immediately on the lethal hit");
        assertEquals(initialSongFadeCount, services.spawnedChildren.stream()
                        .filter(SongFadeTransitionInstance.class::isInstance)
                        .count(),
                "Level music restore helper should wait for the pre-fade delay");

        for (int frame = 1; frame < 80; frame++) {
            boss.update(frame, player);
        }

        assertTrue(services.spawnedChildren.stream().anyMatch(SongFadeTransitionInstance.class::isInstance),
                "Defeat flow should queue Wait_FadeToLevelMusic music restoration");
        assertTrue(services.spawnedChildren.stream().anyMatch(S3kBossDefeatSignpostFlow.class::isInstance),
                "Defeat flow should hand off into the signpost/results controller");
        assertTrue(services.spawnedChildren.stream()
                        .anyMatch(child -> child.getClass().getSimpleName().contains("CameraScrollHelper")),
                "Defeat flow should spawn the MGZ camera auto-scroll helper");
        assertEquals(Sonic3kObjectIds.MGZ_MINIBOSS, services.gameState.getCurrentBossId(),
                "Boss ID should remain active until the signpost flow advances");
    }

    @Test
    void defeatCameraHelperAdvancesCameraAndLeftBoundWhileScrolling() throws Exception {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance boss = createBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2D00, (short) 0x0100);

        boss.update(0, player);
        BossStateContext state = bossState(boss);
        state.hitCount = 1;

        boss.onPlayerAttack(player, ENEMY_HIT);
        for (int frame = 1; frame < 80; frame++) {
            boss.update(frame, player);
        }

        ObjectInstance helper = services.spawnedChildren.stream()
                .filter(child -> child.getClass().getSimpleName().contains("CameraScrollHelper"))
                .findFirst()
                .orElseThrow();

        camera.setX((short) 0x2DFE);
        camera.setMinX((short) 0x2DFE);
        camera.setMaxX((short) 0x2DFE);

        helper.update(80, player);

        assertEquals(0x2DFF, camera.getX() & 0xFFFF, "Camera helper should advance one pixel per frame");
        assertEquals(0x2DFF, camera.getMinX() & 0xFFFF, "Camera helper should carry the left lock with it");
        assertEquals(0x2DFE, camera.getMaxX() & 0xFFFF,
                "loc_887DA must leave Camera_max_X_pos untouched");
        assertFalse(helper.isDestroyed(), "Camera helper should remain active before reaching its target");

        helper.update(81, player);

        assertEquals(0x2E00, camera.getX() & 0xFFFF, "Camera helper should stop at the ROM target X");
        assertEquals(0x2E00, camera.getMinX() & 0xFFFF, "Camera helper should advance the left lock");
        assertEquals(0x2DFE, camera.getMaxX() & 0xFFFF,
                "Camera helper should continue to leave the right bound untouched");
        assertTrue(helper.isDestroyed(), "Camera helper should release ownership once the target is reached");
    }

    @Test
    void defeatCameraHelperPreservesRomPostIncrementWhenStartingAtTarget() {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance.MgzBossCameraScrollHelper helper =
                new MgzMinibossInstance.MgzBossCameraScrollHelper(0x2E00);
        helper.setServices(services);
        camera.setX((short) 0x2E00);
        camera.setMinX((short) 0x2E00);
        camera.setMaxX((short) 0x2E00);

        helper.update(0, null);

        assertEquals(0x2E01, camera.getX() & 0xFFFF,
                "loc_887DA increments Camera_X_pos before comparing against the target");
        assertEquals(0x2E01, camera.getMinX() & 0xFFFF,
                "loc_887DA copies the post-increment camera position into Camera_min_X_pos");
        assertEquals(0x2E00, camera.getMaxX() & 0xFFFF,
                "loc_887DA does not write Camera_max_X_pos");
        assertTrue(helper.isDestroyed(), "Camera helper should release ownership after the post-increment compare");
    }

    @Test
    void defeatCameraHelperPreservesRomPostIncrementWhenRestoredAboveTarget() {
        RecordingServices services = new RecordingServices(camera);
        MgzMinibossInstance.MgzBossCameraScrollHelper helper =
                new MgzMinibossInstance.MgzBossCameraScrollHelper(0x2E00);
        helper.setServices(services);
        camera.setX((short) 0x2E01);
        camera.setMinX((short) 0x2E01);
        camera.setMaxX((short) 0x2E01);

        helper.update(0, null);

        assertEquals(0x2E02, camera.getX() & 0xFFFF,
                "loc_887DA performs its increment even when restored above the target");
        assertEquals(0x2E02, camera.getMinX() & 0xFFFF,
                "loc_887DA publishes the incremented restored position as the left bound");
        assertEquals(0x2E01, camera.getMaxX() & 0xFFFF,
                "loc_887DA leaves the right bound untouched");
        assertTrue(helper.isDestroyed(), "Camera helper should release ownership after the post-increment compare");
    }

    private void assertCameraLockedAtTarget(ObjectInstance helper) {
        assertEquals(0x2E00, camera.getX() & 0xFFFF, "Camera X should clamp to the handoff target");
        assertEquals(0x2E00, camera.getMinX() & 0xFFFF, "Left bound should clamp to the handoff target");
        assertEquals(0x2E00, camera.getMaxX() & 0xFFFF, "Right bound should clamp to the handoff target");
        assertTrue(helper.isDestroyed(), "Camera helper should release ownership at the target");
    }

    @Test
    void defeatExplosionChildRendersWhenBossExplosionRendererIsAvailable() {
        PatternSpriteRenderer explosionRenderer = mock(PatternSpriteRenderer.class);
        when(explosionRenderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getBossExplosionRenderer()).thenReturn(explosionRenderer);

        RecordingServices services = new RecordingServices(camera);
        services.renderManager = renderManager;

        S3kBossExplosionChild explosion = new S3kBossExplosionChild(0x180, 0x90);
        explosion.setServices(services);

        explosion.appendRenderCommands(List.of());

        verify(explosionRenderer).drawFrameIndex(0, 0x180, 0x90, false, false);
    }

    private static MgzMinibossInstance createBoss(RecordingServices services) {
        MgzMinibossInstance boss = new MgzMinibossInstance(
                new ObjectSpawn(0x2D80, 0x0100, Sonic3kObjectIds.MGZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        return boss;
    }

    private static BossStateContext bossState(MgzMinibossInstance boss) throws Exception {
        Field field = AbstractBossInstance.class.getDeclaredField("state");
        field.setAccessible(true);
        return (BossStateContext) field.get(boss);
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static int getPrivateInt(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void setPrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static boolean getPrivateBoolean(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void seedOriginalBossFlashColors(Palette palette) {
        for (int i = 0; i < FLASH_INDICES.length; i++) {
            palette.getColor(FLASH_INDICES[i]).fromSegaFormat(segaWordBytes(ORIGINAL_WORDS[i]), 0);
        }
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF);
    }

    private static byte[] segaWordBytes(int segaWord) {
        return new byte[] {
                (byte) ((segaWord >> 8) & 0xFF),
                (byte) (segaWord & 0xFF)
        };
    }

    private static final class RecordingServices extends StubObjectServices {
        private final Camera camera;
        private final GameStateManager gameState = new GameStateManager();
        private final StubLevel level = new StubLevel();
        private final PaletteOwnershipRegistry paletteRegistry = new PaletteOwnershipRegistry();
        private final List<Integer> playedMusic = new ArrayList<>();
        private final List<ObjectInstance> spawnedChildren = new ArrayList<>();
        private final ObjectManager objectManager;
        private ObjectRenderManager renderManager;
        private int fadeOutCalls;

        private RecordingServices(Camera camera) {
            this.camera = camera;
            this.objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                ObjectInstance child = invocation.getArgument(0);
                if (child instanceof AbstractObjectInstance instance) {
                    instance.setServices(this);
                }
                spawnedChildren.add(child);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any());
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public GameStateManager gameState() {
            return gameState;
        }

        @Override
        public Level currentLevel() {
            return level;
        }

        @Override
        public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
            return paletteRegistry;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }

        @Override
        public void playMusic(int musicId) {
            playedMusic.add(musicId);
        }

        @Override
        public void fadeOutMusic() {
            fadeOutCalls++;
        }

        @Override
        public int getCurrentLevelMusicId() {
            return Sonic3kMusic.MGZ1.id;
        }
    }

    private static final class StubLevel implements Level {
        private final Palette[] palettes = new Palette[] {
                new Palette(), new Palette(), new Palette(), new Palette()
        };

        Palette[] palettes() {
            return palettes;
        }

        @Override public int getPaletteCount() { return palettes.length; }
        @Override public Palette getPalette(int index) { return palettes[index]; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { throw new UnsupportedOperationException(); }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { throw new UnsupportedOperationException(); }
        @Override public int getBlockCount() { return 0; }
        @Override public Block getBlock(int index) { throw new UnsupportedOperationException(); }
        @Override public SolidTile getSolidTile(int index) { throw new UnsupportedOperationException(); }
        @Override public Map getMap() { return null; }
        @Override public List<ObjectSpawn> getObjects() { return List.of(); }
        @Override public List<RingSpawn> getRings() { return List.of(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 0; }
    }
}
