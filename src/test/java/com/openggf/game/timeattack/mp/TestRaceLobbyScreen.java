package com.openggf.game.timeattack.mp;

import com.openggf.control.InputHandler;
import com.openggf.net.client.ClientRaceSession;
import com.openggf.net.client.RaceClient;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestRaceLobbyScreen {
    private static final ControlMessage.RoundConfig ROUND =
            new ControlMessage.RoundConfig("s2", 0, 0, 300, "OPEN", null);

    @Test
    void explicitHostStartAndLeaveActionsRemainReachable() {
        List<ControlMessage> sent = new ArrayList<>();
        AtomicInteger leaves = new AtomicInteger();
        RaceLobbyScreen screen = screen(sent, new ClientRaceSession(() -> 0), true, leaves, new AtomicInteger());
        InputHandler input = new InputHandler();
        press(screen, input, GLFW_KEY_ENTER);
        assertTrue(sent.contains(new ControlMessage.RoundConfigure(ROUND)));
        press(screen, input, GLFW_KEY_DOWN); // Chat
        press(screen, input, GLFW_KEY_DOWN); // Leave
        press(screen, input, GLFW_KEY_ENTER);
        assertEquals(1, leaves.get());
    }

    @Test
    void cancellingChatReturnsToLobbyWithoutLeavingOrStarting() {
        List<ControlMessage> sent = new ArrayList<>();
        AtomicInteger leaves = new AtomicInteger();
        RaceLobbyScreen screen = screen(sent, new ClientRaceSession(() -> 0), true, leaves, new AtomicInteger());
        InputHandler input = new InputHandler();
        press(screen, input, GLFW_KEY_DOWN);
        press(screen, input, GLFW_KEY_ENTER);
        press(screen, input, GLFW_KEY_ESCAPE);
        assertEquals(0, leaves.get());
        assertFalse(sent.stream().anyMatch(ControlMessage.RoundConfigure.class::isInstance));
        press(screen, input, GLFW_KEY_ESCAPE);
        assertEquals(1, leaves.get());
    }

    @Test
    void remoteRoundLaunchStillRunsWhileChatEditorIsOpen() {
        ClientRaceSession session = new ClientRaceSession(() -> 0);
        AtomicInteger launches = new AtomicInteger();
        RaceLobbyScreen screen = screen(new ArrayList<>(), session, false, new AtomicInteger(), launches);
        InputHandler input = new InputHandler();
        press(screen, input, GLFW_KEY_ENTER); // Guest defaults to Chat.
        session.onControl(new ControlMessage.RoundStart(ROUND, 1000, 10000));
        screen.update(input);
        screen.update(input);
        assertEquals(1, launches.get());
    }

    @Test
    void chatHistoryDetailsReturnWithoutLeavingAndStillPumpRoundLaunch() {
        ClientRaceSession session = new ClientRaceSession(() -> 0);
        AtomicInteger leaves = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        RaceLobbyScreen screen = screen(new ArrayList<>(), session, true, leaves, launches);
        InputHandler input = new InputHandler();
        press(screen, input, GLFW_KEY_UP); // History.
        press(screen, input, GLFW_KEY_ENTER);
        session.onControl(new ControlMessage.RoundStart(ROUND, 1000, 10000));
        screen.update(input);
        assertEquals(1, launches.get());
        press(screen, input, GLFW_KEY_ESCAPE);
        assertEquals(0, leaves.get());
        press(screen, input, GLFW_KEY_ESCAPE);
        assertEquals(1, leaves.get());
    }

    private static RaceLobbyScreen screen(List<ControlMessage> sent, ClientRaceSession session,
                                          boolean host, AtomicInteger leaves, AtomicInteger launches) {
        RaceTransport transport = new RaceTransport() {
            @Override public List<RaceClient.InboundEvent> drainInbound() { return List.of(); }
            @Override public void sendControl(ControlMessage message) { sent.add(message); }
            @Override public void sendBinary(byte[] bytes) { }
            @Override public int playerSlot() { return 0; }
            @Override public boolean isOpen() { return true; }
            @Override public void close() { }
        };
        return new RaceLobbyScreen(new MultiplayerRaceCoordinator(transport, session), null, host,
                ROUND, "sonic", request -> launches.incrementAndGet(), leaves::incrementAndGet);
    }

    private static void press(RaceLobbyScreen screen, InputHandler input, int key) {
        input.handleKeyEvent(key, GLFW_PRESS);
        screen.update(input); input.update();
        input.handleKeyEvent(key, GLFW_RELEASE);
        screen.update(input); input.update();
    }
}
