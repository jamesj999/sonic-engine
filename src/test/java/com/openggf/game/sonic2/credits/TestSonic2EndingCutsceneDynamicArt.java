package com.openggf.game.sonic2.credits;

import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins ending DPLC ownership to cutscene updates, rather than the renderer.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestSonic2EndingCutsceneDynamicArt {

    @Test
    void headlessNormalUpdatePublishesThePreparedPlayerDecisionAndDrawIsInert()
            throws Exception {
        RecordingDecisionSink decisions = new RecordingDecisionSink();
        prepareHeadlessGraphics();
        Sonic2EndingCutsceneManager manager = newManager(decisions);
        manager.initialize(TestEnvironment.currentRom());

        invoke(manager, "enterCharacterAppear");
        int afterEnter = decisions.decisions.size();

        manager.update();
        assertTrue(decisions.decisions.size() > afterEnter,
                "headless update must own the normal player DPLC decision");

        int afterUpdate = decisions.decisions.size();
        drawPlayerFrame(manager, GameServices.graphics(), 0x54);
        assertEquals(afterUpdate, decisions.decisions.size(),
                "drawing must not publish or suppress ending DPLC decisions");
        assertEquals("NORMAL_OBJECT", decisions.decisions.getLast().kind());
        assertEquals("sonic", decisions.decisions.getLast().owner());
    }

    @Test
    void repeatedPilotFramesPublishEachDirectPartTwoDecision() throws Exception {
        RecordingDecisionSink decisions = new RecordingDecisionSink();
        prepareHeadlessGraphics();
        Sonic2EndingCutsceneManager manager = newManager(decisions);
        manager.initialize(TestEnvironment.currentRom());

        // Wrap into the four consecutive $10 entries at the front of the
        // ROM sequence; each expiry must still become a direct-Part2 decision.
        setInt(manager, "pilotAnimIndex", 23);
        for (int i = 0; i < 4; i++) {
            setInt(manager, "pilotAnimTimer", 0);
            invoke(manager, "updatePilotAnimation");
        }

        List<RecordedDecision> pilotDecisions = decisions.decisions.stream()
                .filter(decision -> decision.kind().equals("DIRECT_PART2"))
                .toList();
        assertEquals(4, pilotDecisions.size(),
                "each timer expiry is a semantic direct-Part2 decision");
        assertEquals(List.of(0x10, 0x10, 0x10, 0x10),
                pilotDecisions.stream().map(RecordedDecision::mappingFrame).toList());
        assertTrue(pilotDecisions.stream()
                        .allMatch(decision -> decision.owner().equals("tails")),
                "the Sonic ending's direct-Part2 pilot owner is Tails");
    }

    @Test
    void firstActivePilotUpdatePublishesFromZeroedRomTimer() throws Exception {
        RecordingDecisionSink decisions = new RecordingDecisionSink();
        prepareHeadlessGraphics();
        Sonic2EndingCutsceneManager manager = newManager(decisions);
        manager.initialize(TestEnvironment.currentRom());
        invoke(manager, "enterCharacterAppear");
        invoke(manager, "enterMainEnding");
        invoke(manager, "startObjCc");

        manager.update();

        List<RecordedDecision> pilotDecisions = decisions.decisions.stream()
                .filter(decision -> decision.kind().equals("DIRECT_PART2"))
                .toList();
        assertEquals(1, pilotDecisions.size(),
                "zeroed objoff_37 must expire on the first active pilot update");
        assertEquals(new RecordedDecision("DIRECT_PART2", "tails", 0x10),
                pilotDecisions.getFirst());
    }

    private static Sonic2EndingCutsceneManager newManager(
            RecordingDecisionSink decisions) {
        return new Sonic2EndingCutsceneManager(decisions);
    }

    private static void invoke(Sonic2EndingCutsceneManager manager, String method)
            throws Exception {
        Method target = Sonic2EndingCutsceneManager.class.getDeclaredMethod(method);
        target.setAccessible(true);
        target.invoke(manager);
    }

    private static void drawPlayerFrame(Sonic2EndingCutsceneManager manager,
                                        GraphicsManager graphicsManager,
                                        int frameIndex) throws Exception {
        Method target = Sonic2EndingCutsceneManager.class.getDeclaredMethod(
                "drawPlayerFrame", GraphicsManager.class, int.class,
                int.class, int.class);
        target.setAccessible(true);
        target.invoke(manager, graphicsManager, frameIndex, 0xA0, 0x50);
    }

    private static void prepareHeadlessGraphics() {
        GameServices.graphics().setHeadlessMode(true);
    }

    private static void setInt(Sonic2EndingCutsceneManager manager,
                               String field,
                               int value) throws Exception {
        Field target = Sonic2EndingCutsceneManager.class.getDeclaredField(field);
        target.setAccessible(true);
        target.setInt(manager, value);
    }

    private record RecordedDecision(String kind, String owner, int mappingFrame) {
    }

    private static final class RecordingDecisionSink
            implements Sonic2EndingDynamicArtDecisionSink {
        private final List<RecordedDecision> decisions = new ArrayList<>();

        @Override
        public void observe(Sonic2EndingDynamicArtDecisionSink.Decision decision) {
            decisions.add(new RecordedDecision(decision.kind().toString(),
                    decision.owner(), decision.mappingFrame()));
        }
    }
}
