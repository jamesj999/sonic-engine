package com.openggf.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.objects.AizIntroPlaneChild;
import com.openggf.game.sonic3k.objects.CutsceneKnucklesAiz1Instance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAizIntroStateTimeline {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_1 = 0;

    // ROM source: sonic3k.asm:38174-38177 (Level_FromSavedGame override)
    // move.w #$40,(Player_1+x_pos).w / move.w #$420,(Player_1+y_pos).w
    private static final short AIZ1_INTRO_CENTRE_X = 0x40;
    private static final short AIZ1_INTRO_CENTRE_Y = 0x420;

    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private Sonic sonic;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sonic = (Sonic) fixture.sprite();

        // Reposition sprite to the AIZ1 intro start position (centre coordinates),
        // matching what loadCurrentLevel() does via DynamicStartPositionProvider.
        sonic.setCentreX(AIZ1_INTRO_CENTRE_X);
        sonic.setCentreY(AIZ1_INTRO_CENTRE_Y);
        fixture.camera().updatePosition(true);

        // Initialize level events after sprite is at the correct position.
        LevelEventProvider lep = GameModuleRegistry.getCurrent().getLevelEventProvider();
        if (lep != null) {
            lep.initLevel(ZONE_AIZ, ACT_1);
        }

        // Reset object manager so intro objects spawn fresh.
        GameServices.level().getObjectManager().reset(0);
    }

    @AfterEach
    public void tearDown() {
        // Config restore is handled by @AfterAll; nothing per-test to restore.
    }

    @Test
    public void recordsAizIntroStateTransitionTimeline() {
        LevelManager levelManager = GameServices.level();
        ObjectManager objectManager = levelManager.getObjectManager();
        Camera camera = fixture.camera();

        List<IntroSnapshot> timeline = new ArrayList<>();

        boolean sawIntro = false;
        boolean sawKnuckles = false;
        boolean sawIntroSuperVisual = false;
        boolean sawFrozenCamera = false;
        boolean sawUnfrozenCamera = false;
        boolean sawLevelStartedFalseDuringIntro = false;
        boolean sawCameraAdvanceDuringIntro = false;
        boolean sawSonicAndCameraAdvanceTogether = false;
        boolean sawSonicWithinCameraFollowWindow = false;
        boolean sawKnuxNearCamera = false;
        boolean sawLevelStartedReenabled = false;

        int prevIntroRoutine = Integer.MIN_VALUE;
        int prevKnuxRoutine = Integer.MIN_VALUE;
        int prevPlaneX = Integer.MIN_VALUE;
        int prevPlaneY = Integer.MIN_VALUE;
        boolean prevPlaneOnScreen = false;
        boolean prevIntroPresent = false;
        boolean prevKnuxPresent = false;
        boolean prevPlanePresent = false;
        boolean prevCameraFrozen = camera.getFrozen();
        boolean prevObjectControlled = sonic.isObjectControlled();
        boolean prevControlLocked = sonic.isControlLocked();
        boolean prevSonicSuper = sonic.isSuperSonic();
        boolean prevIntroSuperVisual = false;
        boolean prevLevelStarted = camera.isLevelStarted();

        Integer heldCameraX = null;
        Integer heldSonicX = null;

        final int maxFrames = 5000;
        for (int i = 0; i < maxFrames; i++) {
            fixture.stepFrame(false, false, false, false, false);

            AizPlaneIntroInstance intro = null;
            CutsceneKnucklesAiz1Instance knux = null;
            AizIntroPlaneChild plane = null;
            for (ObjectInstance obj : objectManager.getActiveObjects()) {
                if (obj instanceof AizPlaneIntroInstance introObj) {
                    intro = introObj;
                } else if (obj instanceof CutsceneKnucklesAiz1Instance knuxObj) {
                    knux = knuxObj;
                } else if (obj instanceof AizIntroPlaneChild planeObj) {
                    plane = planeObj;
                }
            }

            boolean introPresent = intro != null;
            boolean knuxPresent = knux != null;
            boolean planePresent = plane != null;
            int introRoutine = introPresent ? intro.getRoutine() : -1;
            int knuxRoutine = knuxPresent ? knux.getRoutine() : -1;
            int planeX = planePresent ? plane.getX() : -1;
            int planeY = planePresent ? plane.getY() : -1;
            // Plane uses screen-space coordinates in ROM's +128 sprite-table domain.
            int planeScreenX = planeX - 128;
            int planeScreenY = planeY - 128;
            boolean planeOnScreen = planePresent
                    && planeScreenX >= 0
                    && planeScreenX < camera.getWidth()
                    && planeScreenY >= 0
                    && planeScreenY < camera.getHeight();
            boolean cameraFrozen = camera.getFrozen();
            boolean objectControlled = sonic.isObjectControlled();
            boolean controlLocked = sonic.isControlLocked();
            boolean sonicSuper = sonic.isSuperSonic();
            boolean introSuperVisual = introPresent && intro.isSuperSonicVisualActive();
            boolean levelStarted = camera.isLevelStarted();

            sawIntro |= introPresent;
            sawKnuckles |= knuxPresent;
            sawIntroSuperVisual |= introSuperVisual;
            sawFrozenCamera |= cameraFrozen;
            sawUnfrozenCamera |= !cameraFrozen;
            sawLevelStartedFalseDuringIntro |= (introPresent || knuxPresent) && !levelStarted;

            if (!levelStarted) {
                if (heldCameraX == null || heldSonicX == null) {
                    heldCameraX = (int) camera.getX();
                    heldSonicX = (int) sonic.getCentreX();
                }
                if ((introPresent || knuxPresent) && Math.abs(camera.getX()) >= 256) {
                    sawCameraAdvanceDuringIntro = true;
                }
                int sonicDelta = Math.abs(sonic.getCentreX() - heldSonicX);
                int cameraDelta = Math.abs(camera.getX() - heldCameraX);
                if (sonicDelta >= 200 && cameraDelta >= 160) {
                    sawSonicAndCameraAdvanceTogether = true;
                }
                if (sonic.getCentreX() >= 0x918) {
                    int sonicCameraDelta = sonic.getCentreX() - camera.getX();
                    if (sonicCameraDelta >= 120 && sonicCameraDelta <= 208) {
                        sawSonicWithinCameraFollowWindow = true;
                    }
                }
            } else {
                heldCameraX = null;
                heldSonicX = null;
            }
            if (knuxPresent) {
                int knuxScreenX = knux.getX() - camera.getX();
                int knuxScreenY = knux.getY() - camera.getY();
                if (knuxScreenX >= -64 && knuxScreenX <= camera.getWidth() + 64
                        && knuxScreenY >= -64 && knuxScreenY <= camera.getHeight() + 64) {
                    sawKnuxNearCamera = true;
                }
            }
            if (sawLevelStartedFalseDuringIntro && levelStarted && !introPresent && !knuxPresent) {
                sawLevelStartedReenabled = true;
            }

            boolean changed = introPresent != prevIntroPresent
                    || knuxPresent != prevKnuxPresent
                    || planePresent != prevPlanePresent
                    || introRoutine != prevIntroRoutine
                    || knuxRoutine != prevKnuxRoutine
                    || planeX != prevPlaneX
                    || planeY != prevPlaneY
                    || planeOnScreen != prevPlaneOnScreen
                    || cameraFrozen != prevCameraFrozen
                    || objectControlled != prevObjectControlled
                    || controlLocked != prevControlLocked
                    || sonicSuper != prevSonicSuper
                    || introSuperVisual != prevIntroSuperVisual
                    || levelStarted != prevLevelStarted;

            if (changed) {
                timeline.add(IntroSnapshot.capture(fixture.frameCount(), sonic, camera, intro, knux, plane));
                prevIntroPresent = introPresent;
                prevKnuxPresent = knuxPresent;
                prevPlanePresent = planePresent;
                prevIntroRoutine = introRoutine;
                prevKnuxRoutine = knuxRoutine;
                prevPlaneX = planeX;
                prevPlaneY = planeY;
                prevPlaneOnScreen = planeOnScreen;
                prevCameraFrozen = cameraFrozen;
                prevObjectControlled = objectControlled;
                prevControlLocked = controlLocked;
                prevSonicSuper = sonicSuper;
                prevIntroSuperVisual = introSuperVisual;
                prevLevelStarted = levelStarted;
            }

            // Stop once intro and Knuckles cutscene have both completed and camera control is restored.
            if (sawIntro && sawKnuckles && !introPresent && !knuxPresent
                    && !sonic.isObjectControlled() && !camera.getFrozen() && camera.isLevelStarted()) {
                break;
            }
        }

        String timelineDump = formatTimeline(timeline);
        System.out.println(timelineDump);

        IntroSnapshot firstSnapshot = timeline.isEmpty() ? null : timeline.get(0);
        assertTrue(sawIntro, "AIZ intro object never spawned.\n" + timelineDump);
        assertTrue(!timeline.isEmpty(), "No transition snapshots recorded.\n" + timelineDump);
        assertTrue(firstSnapshot != null && firstSnapshot.cameraY == 0x390, "AIZ intro camera start Y must clamp to AIZ1 maxY (0x390).\n" + timelineDump);
        assertTrue(firstSnapshot != null && firstSnapshot.introX < 128 && firstSnapshot.introY < 128, "AIZ intro should start off-screen top-left in ROM sprite-table space (+128 bias).\n" + timelineDump);
        assertTrue(timeline.stream().anyMatch(s -> s.planePresent && s.planeOnScreen), "AIZ plane child (Tornado) never appeared on-screen during intro.\n" + timelineDump);
        assertTrue(timeline.stream().anyMatch(s -> s.introRoutine >= 22), "AIZ intro routine never reached Knuckles trigger stage (>= 22).\n" + timelineDump);
        assertTrue(sawKnuckles, "Knuckles cutscene object never spawned.\n" + timelineDump);
        assertTrue(sawIntroSuperVisual, "AIZ intro never entered super visual phase.\n" + timelineDump);
        // ROM behavior: camera freeze flag remains clear; intro uses Level_started_flag for flow state.
        assertTrue(!sawFrozenCamera, "Camera was unexpectedly frozen during intro.\n" + timelineDump);
        assertTrue(sawLevelStartedFalseDuringIntro, "Level_started_flag was never cleared during intro/cutscene.\n" + timelineDump);
        assertTrue(sawCameraAdvanceDuringIntro, "Camera never advanced during intro/cutscene while Level_started_flag was clear.\n" + timelineDump);
        assertTrue(sawSonicAndCameraAdvanceTogether, "Sonic movement with corresponding camera advance was not observed.\n" + timelineDump);
        assertTrue(sawSonicWithinCameraFollowWindow, "Camera follow window relative to Sonic was not observed near Knuckles trigger range.\n" + timelineDump);
        assertTrue(sawKnuxNearCamera, "Knuckles never appeared near the camera viewport after spawning.\n" + timelineDump);
        assertTrue(sawLevelStartedReenabled, "Level_started_flag was not restored after cutscene completion.\n" + timelineDump);
    }

    private static String formatTimeline(List<IntroSnapshot> snapshots) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AIZ Intro Transition Timeline ===\n");
        sb.append("frame intro(r/x/y/super/map) plane(x/y/on) knux(r/x/y) cam(x/y/f/ls) ")
                .append("sonic(cx/cy/super/objCtrl/lock/hidden/map)\n");
        for (IntroSnapshot s : snapshots) {
            sb.append(String.format(
                    "%4d I(%2d/%5d/%4d/%s/%3d) P(%5d/%4d/%s) K(%2d/%5d/%4d) C(%5d/%4d/%s/%s) S(%5d/%4d/%s/%s/%s/%s/%3d)%n",
                    s.frame,
                    s.introRoutine, s.introX, s.introY, boolFlag(s.introSuperVisual), s.introMappingFrame,
                    s.planeX, s.planeY, boolFlag(s.planeOnScreen),
                    s.knuxRoutine, s.knuxX, s.knuxY,
                    s.cameraX, s.cameraY, boolFlag(s.cameraFrozen), boolFlag(s.levelStarted),
                    s.sonicCentreX, s.sonicCentreY,
                    boolFlag(s.sonicSuper),
                    boolFlag(s.sonicObjectControlled),
                    boolFlag(s.sonicControlLocked),
                    boolFlag(s.sonicHidden),
                    s.sonicMappingFrame));
        }
        return sb.toString();
    }

    private static String boolFlag(boolean value) {
        return value ? "Y" : "N";
    }

    private static final class IntroSnapshot {
        final int frame;
        final int cameraX;
        final int cameraY;
        final boolean cameraFrozen;
        final boolean levelStarted;
        final int sonicCentreX;
        final int sonicCentreY;
        final boolean sonicSuper;
        final boolean sonicObjectControlled;
        final boolean sonicControlLocked;
        final boolean sonicHidden;
        final int sonicMappingFrame;
        final int introRoutine;
        final int introX;
        final int introY;
        final boolean introSuperVisual;
        final int introMappingFrame;
        final boolean planePresent;
        final int planeX;
        final int planeY;
        final boolean planeOnScreen;
        final int knuxRoutine;
        final int knuxX;
        final int knuxY;

        private IntroSnapshot(int frame,
                              int cameraX,
                              int cameraY,
                              boolean cameraFrozen,
                              boolean levelStarted,
                              int sonicCentreX,
                              int sonicCentreY,
                              boolean sonicSuper,
                              boolean sonicObjectControlled,
                              boolean sonicControlLocked,
                              boolean sonicHidden,
                              int sonicMappingFrame,
                              int introRoutine,
                              int introX,
                              int introY,
                              boolean introSuperVisual,
                              int introMappingFrame,
                              boolean planePresent,
                              int planeX,
                              int planeY,
                              boolean planeOnScreen,
                              int knuxRoutine,
                              int knuxX,
                              int knuxY) {
            this.frame = frame;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraFrozen = cameraFrozen;
            this.levelStarted = levelStarted;
            this.sonicCentreX = sonicCentreX;
            this.sonicCentreY = sonicCentreY;
            this.sonicSuper = sonicSuper;
            this.sonicObjectControlled = sonicObjectControlled;
            this.sonicControlLocked = sonicControlLocked;
            this.sonicHidden = sonicHidden;
            this.sonicMappingFrame = sonicMappingFrame;
            this.introRoutine = introRoutine;
            this.introX = introX;
            this.introY = introY;
            this.introSuperVisual = introSuperVisual;
            this.introMappingFrame = introMappingFrame;
            this.planePresent = planePresent;
            this.planeX = planeX;
            this.planeY = planeY;
            this.planeOnScreen = planeOnScreen;
            this.knuxRoutine = knuxRoutine;
            this.knuxX = knuxX;
            this.knuxY = knuxY;
        }

        static IntroSnapshot capture(int frame,
                                     Sonic sonic,
                                     Camera camera,
                                     AizPlaneIntroInstance intro,
                                     CutsceneKnucklesAiz1Instance knux,
                                     AizIntroPlaneChild plane) {
            int introRoutine = intro != null ? intro.getRoutine() : -1;
            int introX = intro != null ? intro.getX() : -1;
            int introY = intro != null ? intro.getY() : -1;
            boolean introSuperVisual = intro != null && intro.isSuperSonicVisualActive();
            int introMappingFrame = intro != null ? intro.getMappingFrame() : -1;

            int planeX = plane != null ? plane.getX() : -1;
            int planeY = plane != null ? plane.getY() : -1;
            int planeScreenX = planeX - 128;
            int planeScreenY = planeY - 128;
            // Plane uses screen-space coordinates in ROM's +128 sprite-table domain.
            boolean planeOnScreen = plane != null
                    && planeScreenX >= 0
                    && planeScreenX < camera.getWidth()
                    && planeScreenY >= 0
                    && planeScreenY < camera.getHeight();

            int knuxRoutine = knux != null ? knux.getRoutine() : -1;
            int knuxX = knux != null ? knux.getX() : -1;
            int knuxY = knux != null ? knux.getY() : -1;

            return new IntroSnapshot(
                    frame,
                    camera.getX(),
                    camera.getY(),
                    camera.getFrozen(),
                    camera.isLevelStarted(),
                    sonic.getCentreX(),
                    sonic.getCentreY(),
                    sonic.isSuperSonic(),
                    sonic.isObjectControlled(),
                    sonic.isControlLocked(),
                    sonic.isHidden(),
                    sonic.getMappingFrame(),
                    introRoutine,
                    introX,
                    introY,
                    introSuperVisual,
                    introMappingFrame,
                    plane != null,
                    planeX,
                    planeY,
                    planeOnScreen,
                    knuxRoutine,
                    knuxX,
                    knuxY);
        }
    }
}


