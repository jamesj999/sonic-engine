package com.openggf.game.recording;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_I;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_J;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_K;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestUserRecordingSession {
    @TempDir
    Path tempDir;

    @Test
    void stopByUserWritesTwoRecordedFrames() {
        RecordingWrite write = new RecordingWrite();
        UserRecordingSession session = newSession(write, frameSource());
        InputHandler input = new InputHandler();

        input.handleKeyEvent(GLFW_KEY_D, GLFW_PRESS);
        session.beforeLevelFrame(input);
        session.afterLevelFrame();
        input.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        session.beforeLevelFrame(input);
        session.afterLevelFrame();
        session.requestStop(UserRecordingStopReason.USER_STOPPED);

        assertFalse(session.isActive());
        assertEquals(1, write.calls);
        assertEquals(2, write.manifest.frameCount());
        assertEquals(UserRecordingStopReason.USER_STOPPED, write.manifest.stopReason());
        assertEquals(2, write.inputs.size());
        assertEquals(2, write.sidecarFrames.size());
        assertEquals(2, session.hudState().frame());
    }

    @Test
    void ioFailureDeactivatesWithRedHudState() {
        RecordingWrite write = new RecordingWrite();
        write.failure = new IOException("disk full");
        UserRecordingSession session = newSession(write, frameSource());

        session.beforeLevelFrame(new InputHandler());
        session.afterLevelFrame();
        session.requestStop(UserRecordingStopReason.USER_STOPPED);

        UserRecordingHudState hud = session.hudState();
        assertFalse(session.isActive());
        assertTrue(hud.redWarning());
        assertEquals(1, hud.frame());
        assertTrue(hud.primaryText().contains("ERROR"));
        assertTrue(hud.secondaryText().contains(UserRecordingStopReason.IO_ERROR.name()));
    }

    @Test
    void hudStateSwitchesFromRecordingToStopped() {
        RecordingWrite write = new RecordingWrite();
        UserRecordingSession session = newSession(write, frameSource());

        UserRecordingHudState recording = session.hudState();
        assertTrue(recording.visible());
        assertTrue(recording.primaryText().startsWith("REC"));
        assertFalse(recording.redWarning());

        session.requestStop(UserRecordingStopReason.USER_STOPPED);

        UserRecordingHudState stopped = session.hudState();
        assertTrue(stopped.visible());
        assertTrue(stopped.primaryText().contains("STOPPED"));
        assertFalse(stopped.redWarning());
    }

    @Test
    void abortedBeforeGameplayWithZeroFramesDeletesOutputAndDoesNotWrite() throws Exception {
        RecordingWrite write = new RecordingWrite();
        Path output = tempDir.resolve("already-created.bk2");
        Files.writeString(output, "stale");
        UserRecordingSession session = newSession(write, frameSource(), output);

        session.requestStop(UserRecordingStopReason.ABORTED_BEFORE_GAMEPLAY);

        assertFalse(Files.exists(output));
        assertEquals(0, write.calls);
        assertFalse(session.isActive());
    }

    @Test
    void capturesLogicalActionMasksForBothPlayers() {
        RecordingWrite write = new RecordingWrite();
        UserRecordingSession session = newSession(write, frameSource());
        InputHandler input = new InputHandler(InputBindingFactory.supplier(configuredKeys()));
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_RIGHT,
                        AbstractPlayableSprite.INPUT_RIGHT,
                        InputActionMasks.ACTION_B | InputActionMasks.ACTION_C,
                        InputActionMasks.ACTION_B,
                        true,
                        true),
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_LEFT,
                        AbstractPlayableSprite.INPUT_LEFT,
                        InputActionMasks.ACTION_C,
                        InputActionMasks.ACTION_C,
                        true,
                        true)));

        session.beforeLevelFrame(input);
        session.afterLevelFrame();
        session.requestStop(UserRecordingStopReason.USER_STOPPED);

        RecordedFrameInput frame = write.inputs.getFirst();
        assertEquals(AbstractPlayableSprite.INPUT_JUMP, frame.p1InputMask() & AbstractPlayableSprite.INPUT_JUMP);
        assertEquals(AbstractPlayableSprite.INPUT_JUMP, frame.p2InputMask() & AbstractPlayableSprite.INPUT_JUMP);
        assertEquals(InputActionMasks.ACTION_B | InputActionMasks.ACTION_C, frame.p1ActionMask());
        assertEquals(InputActionMasks.ACTION_C, frame.p2ActionMask());
        assertTrue(frame.p1Start());
        assertTrue(frame.p2Start());
    }

    @Test
    void holdPromptTextIsStableForGameLoopWiring() {
        assertEquals("Hold Shift+Record for 1 Sec to Begin Recording", UserRecordingHud.HOLD_PROMPT_TEXT);
        UserRecordingHudState state = UserRecordingHud.holdPromptState(30, 60);
        assertTrue(state.visible());
        assertEquals(UserRecordingHud.HOLD_PROMPT_TEXT, state.primaryText());
        assertTrue(state.redWarning());
        assertFalse(state.amberWarning());
    }

    private UserRecordingSession newSession(RecordingWrite write, UserRecordingSession.SidecarSnapshotSource source) {
        return newSession(write, source, tempDir.resolve("movie.bk2"));
    }

    private UserRecordingSession newSession(RecordingWrite write, UserRecordingSession.SidecarSnapshotSource source,
            Path output) {
        return new UserRecordingSession(
                launchContext(),
                output,
                configuredKeys(),
                write,
                source,
                () -> Instant.parse("2026-06-29T12:00:00Z"));
    }

    private RecordingLaunchContext launchContext() {
        return new RecordingLaunchContext("s3k", 0, 1, "sonic", List.of("tails"),
                true, "current-act-fresh-start");
    }

    private UserRecordingSession.SidecarSnapshotSource frameSource() {
        return frame -> new DesyncLiteFrame(frame, frame + 10, 20, 0, 0, 0, 0, 0,
                0, 0, frame % 60, 0, 0, 0, 0);
    }

    private SonicConfigurationService configuredKeys() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.UP, GLFW_KEY_W);
        config.setConfigValue(SonicConfiguration.DOWN, GLFW_KEY_S);
        config.setConfigValue(SonicConfiguration.LEFT, GLFW_KEY_A);
        config.setConfigValue(SonicConfiguration.RIGHT, GLFW_KEY_D);
        config.setConfigValue(SonicConfiguration.JUMP, GLFW_KEY_SPACE);
        config.setConfigValue(SonicConfiguration.START, GLFW_KEY_RIGHT_CONTROL);
        config.setConfigValue(SonicConfiguration.P2_UP, GLFW_KEY_I);
        config.setConfigValue(SonicConfiguration.P2_DOWN, GLFW_KEY_K);
        config.setConfigValue(SonicConfiguration.P2_LEFT, GLFW_KEY_J);
        config.setConfigValue(SonicConfiguration.P2_RIGHT, GLFW_KEY_L);
        config.setConfigValue(SonicConfiguration.P2_JUMP, GLFW_KEY_RIGHT_SHIFT);
        config.setConfigValue(SonicConfiguration.P2_START, GLFW_KEY_RIGHT_CONTROL);
        return config;
    }

    private static final class RecordingWrite implements UserRecordingSession.RecordingFileWriter {
        int calls;
        UserRecordingManifest manifest;
        List<RecordedFrameInput> inputs = List.of();
        List<DesyncLiteFrame> sidecarFrames = List.of();
        IOException failure;

        @Override
        public void write(Path path, UserRecordingManifest manifest, List<RecordedFrameInput> inputs,
                List<DesyncLiteFrame> sidecarFrames) throws IOException {
            calls++;
            this.manifest = manifest;
            this.inputs = new ArrayList<>(inputs);
            this.sidecarFrames = new ArrayList<>(sidecarFrames);
            if (failure != null) {
                throw failure;
            }
        }
    }
}
