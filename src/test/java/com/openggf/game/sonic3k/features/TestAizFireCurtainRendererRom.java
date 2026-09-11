package com.openggf.game.sonic3k.features;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.Sonic3kLoadBootstrap;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.events.FireCurtainStage;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.animation.AnimatedPaletteManager;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestAizFireCurtainRendererRom {
    private static final Sonic3kLoadBootstrap FIRE_TRANSITION_BOOTSTRAP =
            new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null);
    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;

    private static Sonic3kAIZEvents newFireTransitionEvents() {
        AtomicInteger vblankCounter = new AtomicInteger();
        return new Sonic3kAIZEvents(FIRE_TRANSITION_BOOTSTRAP, vblankCounter::getAndIncrement);
    }

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
    }

    @BeforeEach
    void restoreSkipIntroBootstrap() {
        // The singleton-reset extension restores configuration defaults before
        // each method. These tests reload AIZ1, so republish the same skip-intro
        // bootstrap selected by the shared fixture before that reload.
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @ParameterizedTest
    @CsvSource({"320", "352", "400", "528", "800"})
    public void realAizFakeoutProducesNonEmptyCurtainPlan(int screenWidth) throws Exception {
        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        Sonic3kAIZEvents events = newFireTransitionEvents();
        events.init(0);
        stageFireOverlay(events);
        events.setEventsFg5(true);

        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        boolean sawRisingCurtain = false;
        boolean sawRefreshCurtain = false;
        boolean sawRefreshRightEdge = false;

        for (int frame = 0; frame < 360 && !events.isAct2TransitionRequested(); frame++) {
            updateWithHardware(events, 0, frame);
            FireCurtainRenderState state = events.getFireCurtainRenderState(224);
            if (!state.active() || state.coverHeightPx() <= 0) {
                continue;
            }

            AizFireCurtainRenderer.CurtainCompositionPlan plan =
                    renderer.buildCompositionPlan(state, screenWidth, 224);
            int drawCount = 0;
            int rightmostDrawEdge = -1;
            for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
                drawCount += column.draws().size();
                for (AizFireCurtainRenderer.TileDraw draw : column.draws()) {
                    rightmostDrawEdge = Math.max(rightmostDrawEdge, draw.screenX() + 8);
                }
            }

            if (state.stage() == FireCurtainStage.AIZ1_RISING && drawCount > 0) {
                sawRisingCurtain = true;
            }
            if (state.stage() == FireCurtainStage.AIZ1_REFRESH && drawCount > 0) {
                sawRefreshCurtain = true;
                if (rightmostDrawEdge >= screenWidth) {
                    sawRefreshRightEdge = true;
                }
            }
        }

        assertTrue(sawRisingCurtain, "Expected non-empty curtain plan during AIZ1 rising fire");
        assertTrue(sawRefreshCurtain, "Expected non-empty curtain plan during AIZ1 refresh fire");
        assertTrue(sawRefreshRightEdge,
                "Expected the ROM-backed cached curtain to reach the configured viewport right edge");
    }

    @Test
    public void realAizFakeoutSamplesFlameOverlayTileRange() throws Exception {
        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        Sonic3kAIZEvents events = newFireTransitionEvents();
        events.init(0);
        stageFireOverlay(events);
        events.setEventsFg5(true);

        int overlayTileBase = 0x500;
        int overlayTileCount = loadFlameOverlayTileCount();
        int overlayTileEnd = overlayTileBase + overlayTileCount;

        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        boolean sawOverlayBackedCurtain = false;
        boolean sawDenseCurtain = false;

        for (int frame = 0; frame < 360 && !events.isAct2TransitionRequested(); frame++) {
            updateWithHardware(events, 0, frame);
            FireCurtainRenderState state = events.getFireCurtainRenderState(224);
            if (!state.active() || state.coverHeightPx() <= 0) {
                continue;
            }

            AizFireCurtainRenderer.CurtainCompositionPlan plan =
                    renderer.buildCompositionPlan(state, 320, 224);
            Set<Integer> palettes = new HashSet<>();
            int overlayTiles = 0;
            int totalTiles = 0;
            for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
                for (AizFireCurtainRenderer.TileDraw draw : column.draws()) {
                    int descriptor = draw.descriptor();
                    int patternIndex = descriptor & 0x7FF;
                    palettes.add((descriptor >> 13) & 0x3);
                    totalTiles++;
                    if (patternIndex >= overlayTileBase && patternIndex < overlayTileEnd) {
                        overlayTiles++;
                    }
                }
            }

            if (overlayTiles > 0) {
                sawOverlayBackedCurtain = true;
            }
            if (totalTiles >= 40 && palettes.size() == 1 && palettes.contains(3)) {
                sawDenseCurtain = true;
            }
            if (sawOverlayBackedCurtain && sawDenseCurtain) {
                break;
            }
        }

        assertTrue(sawOverlayBackedCurtain, "Expected sampled fire curtain tiles to reference the staged flame overlay range");
        assertTrue(sawDenseCurtain, "Expected fire curtain to provide a dense visible wall using palette line 4");
    }

    @Test
    public void realAizFakeoutReportsPerPhaseCurtainDescriptorStats() throws Exception {
        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        Sonic3kAIZEvents events = newFireTransitionEvents();
        events.init(0);
        stageFireOverlay(events);
        events.setEventsFg5(true);

        int overlayTileBase = 0x500;
        int overlayTileCount = loadFlameOverlayTileCount();
        int overlayTileEnd = overlayTileBase + overlayTileCount;

        AizFireCurtainRenderer renderer = new AizFireCurtainRenderer();
        EnumMap<FireCurtainStage, PhaseStats> statsByStage = new EnumMap<>(FireCurtainStage.class);
        PhaseStats latchedWaitFireStats = new PhaseStats();
        PhaseStats bgRedrawStats = new PhaseStats();

        for (int frame = 0; frame < 360 && !events.isAct2TransitionRequested(); frame++) {
            updateWithHardware(events, 0, frame);
            FireCurtainRenderState state = events.getFireCurtainRenderState(224);
            collectStageStats(renderer, state, overlayTileBase, overlayTileEnd, statsByStage,
                    events.isAct2WaitFireDrawActive(), latchedWaitFireStats, bgRedrawStats);
        }

        if (events.isAct2TransitionRequested()) {
            Sonic3kAIZEvents act2Events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
            act2Events.init(1);
            for (int frame = 0; frame < 240 && act2Events.getFireCurtainRenderState(224).active(); frame++) {
                updateWithHardware(act2Events, 1, frame);
                FireCurtainRenderState state = act2Events.getFireCurtainRenderState(224);
                collectStageStats(renderer, state, overlayTileBase, overlayTileEnd, statsByStage,
                        act2Events.isAct2WaitFireDrawActive(), latchedWaitFireStats, bgRedrawStats);
            }
        }

        for (var entry : statsByStage.entrySet()) {
            FireCurtainStage stage = entry.getKey();
            PhaseStats stats = entry.getValue();
            System.out.println("AIZ fire curtain stage=" + stage
                    + " frames=" + stats.framesSeen
                    + " sourceXs=" + stats.sourceWorldXs
                    + " palettes=" + Arrays.toString(stats.paletteCounts)
                    + " minPattern=0x" + Integer.toHexString(stats.minPatternIndex == Integer.MAX_VALUE ? 0 : stats.minPatternIndex)
                    + " maxPattern=0x" + Integer.toHexString(stats.maxPatternIndex == Integer.MIN_VALUE ? 0 : stats.maxPatternIndex)
                    + " sawOverlayPattern=" + stats.sawOverlayPattern);
        }

        assertTrue(statsByStage.containsKey(FireCurtainStage.AIZ1_RISING), "Expected to gather stage stats for the AIZ1 rising curtain");
        assertTrue(statsByStage.containsKey(FireCurtainStage.AIZ1_REFRESH), "Expected to gather stage stats for the AIZ1 refresh curtain");
        assertTrue(statsByStage.containsKey(FireCurtainStage.AIZ2_REDRAW), "Expected to gather stage stats for the AIZ2 redraw curtain");
        assertTrue(statsByStage.containsKey(FireCurtainStage.AIZ2_WAIT_FIRE), "Expected to gather stage stats for the AIZ2 wait-fire curtain");
        assertTrue(latchedWaitFireStats.framesSeen > 0,
                "Expected to gather latched WaitFire release-tail frames separately");
        assertTrue(latchedWaitFireStats.sawOverlayPattern,
                "Latched WaitFire must emit the ROM-backed fire overlay during its outro");
        assertTrue(bgRedrawStats.framesSeen > 0,
                "Expected to gather AIZ2 BG redraw frames separately");
        assertFalse(bgRedrawStats.sawOverlayPattern,
                "AIZ2 BG redraw must not emit fire overlay tiles after ROM release");
    }

    /**
     * ROM: AnPal_AIZ2 runs unconditionally every frame, even during the fire
     * continuation. The fire BG event (AIZ2BGE_WaitFire) overwrites palette
     * line 4 AFTER AnPal runs, so fire colors are preserved. Palette cycling
     * is allowed to modify line 4; the fire event restores it afterward.
     */
    @Test
    public void aiz2PaletteCyclerRunsDuringFireContinuation() throws Exception {
        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        Sonic3kAIZEvents act1Events = newFireTransitionEvents();
        act1Events.init(0);
        stageFireOverlay(act1Events);
        act1Events.setEventsFg5(true);

        for (int frame = 0; frame < 360 && !act1Events.isAct2TransitionRequested(); frame++) {
            updateWithHardware(act1Events, 0, frame);
        }

        levelManager.loadZoneAndAct(0, 1);
        Sonic3kAIZEvents act2Events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        act2Events.init(1);
        assertTrue(act2Events.isFireTransitionActive(), "Expected active fire continuation after the act 1 fake-out reload");

        AnimatedPaletteManager paletteManager = levelManager.getAnimatedPaletteManager();
        assertTrue(paletteManager != null, "Expected an animated palette manager for AIZ2");
        // Palette cycling should run without error during fire continuation
        for (int i = 0; i < 8; i++) {
            paletteManager.update();
        }
    }

    private static int loadFlameOverlayTileCount() throws Exception {
        Rom rom = GameServices.rom().getRom();
        ResourceLoader loader = new ResourceLoader(rom);
        byte[] fireOverlay8x8 = loader.loadSingle(
                LoadOp.kosinskiMBase(Sonic3kConstants.ART_KOSM_AIZ1_FIRE_OVERLAY_ADDR));
        return fireOverlay8x8.length / Pattern.PATTERN_SIZE_IN_ROM;
    }

    private static int[] snapshotPaletteEntries(Palette palette, int... indices) {
        int[] words = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            Palette.Color color = palette.getColor(indices[i]);
            words[i] = ((color.r & 0xFF) << 16) | ((color.g & 0xFF) << 8) | (color.b & 0xFF);
        }
        return words;
    }

    private static final class PhaseStats {
        private final int[] paletteCounts = new int[4];
        private final Set<Integer> sourceWorldXs = new HashSet<>();
        private int framesSeen;
        private int minPatternIndex = Integer.MAX_VALUE;
        private int maxPatternIndex = Integer.MIN_VALUE;
        private boolean sawOverlayPattern;
    }

    private static void collectStageStats(AizFireCurtainRenderer renderer,
                                          FireCurtainRenderState state,
                                          int overlayTileBase,
                                          int overlayTileEnd,
                                          EnumMap<FireCurtainStage, PhaseStats> statsByStage,
                                          boolean waitFireDrawActive,
                                          PhaseStats latchedWaitFireStats,
                                          PhaseStats bgRedrawStats) {
        if (state == null || !state.active() || state.coverHeightPx() <= 0 || state.stage() == FireCurtainStage.INACTIVE) {
            return;
        }

        AizFireCurtainRenderer.CurtainCompositionPlan plan =
                renderer.buildCompositionPlan(state, 320, 224);
        PhaseStats stats = statsByStage.computeIfAbsent(state.stage(), ignored -> new PhaseStats());
        recordDescriptorStats(stats, state, plan, overlayTileBase, overlayTileEnd);
        if (state.stage() == FireCurtainStage.AIZ2_WAIT_FIRE && waitFireDrawActive) {
            recordDescriptorStats(latchedWaitFireStats, state, plan, overlayTileBase, overlayTileEnd);
        }
        if (state.stage() == FireCurtainStage.AIZ2_BG_REDRAW) {
            recordDescriptorStats(bgRedrawStats, state, plan, overlayTileBase, overlayTileEnd);
        }
    }

    private static void recordDescriptorStats(PhaseStats stats,
                                              FireCurtainRenderState state,
                                              AizFireCurtainRenderer.CurtainCompositionPlan plan,
                                              int overlayTileBase,
                                              int overlayTileEnd) {
        stats.sourceWorldXs.add(state.sourceWorldX());
        stats.framesSeen++;

        for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
            for (AizFireCurtainRenderer.TileDraw draw : column.draws()) {
                int descriptor = draw.descriptor();
                int paletteIndex = (descriptor >> 13) & 0x3;
                int patternIndex = descriptor & 0x7FF;
                stats.paletteCounts[paletteIndex]++;
                stats.minPatternIndex = Math.min(stats.minPatternIndex, patternIndex);
                stats.maxPatternIndex = Math.max(stats.maxPatternIndex, patternIndex);
                if (patternIndex >= overlayTileBase && patternIndex < overlayTileEnd) {
                    stats.sawOverlayPattern = true;
                }
            }
        }
    }

    private static void updateWithHardware(
            Sonic3kAIZEvents events, int act, int frame) {
        HardwareBoundaryPump.service(HardwareServiceBoundary.VINT_SERVICE);
        HardwareBoundaryPump.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        events.update(act, frame);
        HardwareBoundaryPump.service(HardwareServiceBoundary.POST_OBJECTS);
    }

    private static void stageFireOverlay(Sonic3kAIZEvents events) {
        for (int frame = 0;
                frame < 100_000 && !events.isFireOverlayTilesLoaded();
                frame++) {
            updateWithHardware(events, 0, frame);
        }
        assertTrue(events.isFireOverlayTilesLoaded(),
                "AIZ1 loc_1C5C6 must finish staging flame art before the boss exit signal");
    }
}
