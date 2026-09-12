package com.openggf.game;

import com.openggf.control.InputHandler;
import com.openggf.game.timeattack.GhostStore;
import com.openggf.game.timeattack.TimeAttackMenu;
import com.openggf.game.timeattack.TimeAttackMenuState;
import com.openggf.game.timeattack.mp.MultiplayerRaceCoordinator;
import com.openggf.game.timeattack.mp.RaceLobbyScreen;
import com.openggf.game.timeattack.mp.RaceTransport;
import com.openggf.game.timeattack.mp.ServerBrowserScreen;
import com.openggf.net.client.ClientRaceSession;
import com.openggf.net.client.MasterClient;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.openggf.game.MenuFeedback.Cue.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.mockito.Mockito.*;

class TestNetworkMenuFeedback {
    @TempDir Path directory;

    @Test
    void timeAttackModeAndJoinEditorReportNestedActionsAndSingleGameBoundaryIsSilent() {
        TimeAttackMenu menu = new TimeAttackMenu(List.of("s2"), "s2", new GhostStore(directory), null, request -> { });
        Driver d = new Driver(menu::update);
        d.press(GLFW_KEY_LEFT); // only one game, already at initial character and track
        assertTrue(d.cues.isEmpty());
        d.press(GLFW_KEY_DOWN); d.press(GLFW_KEY_DOWN); d.press(GLFW_KEY_DOWN);
        d.press(GLFW_KEY_RIGHT); d.press(GLFW_KEY_RIGHT); // Solo -> Host -> Join
        assertEquals(TimeAttackMenuState.Mode.JOIN_LAN, menu.state().mode());
        d.press(GLFW_KEY_DOWN); // address
        d.press(GLFW_KEY_ENTER);
        d.press(GLFW_KEY_ESCAPE);
        assertEquals(List.of(NAVIGATE, NAVIGATE, NAVIGATE, NAVIGATE, NAVIGATE, NAVIGATE, CONFIRM, CANCEL), d.cues);
        assertFalse(menu.consumeCloseRequested());
    }

    @Test
    void timeAttackVoidCallbackFailureEndsWithErrorInsteadOfInferredSuccess() {
        TimeAttackMenu menu = new TimeAttackMenu(List.of("s2"), "s2", new GhostStore(directory), null,
                request -> MenuFeedback.emit(ERROR)); // host displays error and returns normally
        Driver d = new Driver(menu::update);
        for (int i = 0; i < 4; i++) d.press(GLFW_KEY_DOWN); // Go
        d.cues.clear();
        d.press(GLFW_KEY_ENTER);
        assertEquals(List.of(CONFIRM, ERROR), d.cues);
    }

    @Test
    void timeAttackThrownCallbackFailureStillEndsWithError() {
        TimeAttackMenu menu = new TimeAttackMenu(List.of("s2"), "s2", new GhostStore(directory), null,
                request -> { throw new IllegalStateException("Host unavailable"); });
        Driver d = new Driver(menu::update);
        for (int i = 0; i < 4; i++) d.press(GLFW_KEY_DOWN);
        d.cues.clear();
        assertThrows(IllegalStateException.class, () -> d.press(GLFW_KEY_ENTER));
        assertEquals(List.of(CONFIRM, ERROR), d.cues);
    }

    @Test
    void browserPagingBoundariesStaySilentAndRefreshFailureDispatchesOnUpdate() {
        ControlMessage.RoomSummary room = new ControlMessage.RoomSummary("room", "Room", "s2", 0, 0,
                "OPEN", 1, 8, "RELAY", true);
        MasterClient client = mock(MasterClient.class);
        when(client.isOpen()).thenReturn(true);
        CompletableFuture<ControlMessage.RoomListResult> refresh = new CompletableFuture<>();
        when(client.listRooms("s2", 0)).thenReturn(
                CompletableFuture.completedFuture(new ControlMessage.RoomListResult(List.of(room), 0, 1)), refresh);
        ServerBrowserScreen.Actions actions = mock(ServerBrowserScreen.Actions.class);
        Driver d = new Driver(new ServerBrowserScreen(client, "s2", null, actions)::update);
        d.press(GLFW_KEY_LEFT); d.press(GLFW_KEY_RIGHT);
        assertTrue(d.cues.isEmpty());
        d.press(GLFW_KEY_ENTER);
        verify(actions).join(room);
        d.press(GLFW_KEY_DOWN); // Create
        d.press(GLFW_KEY_ENTER);
        verify(actions).create("RELAY");
        d.press(GLFW_KEY_DOWN); // Refresh
        d.press(GLFW_KEY_ENTER);
        assertEquals(List.of(CONFIRM, NAVIGATE, CONFIRM, NAVIGATE, CONFIRM), d.cues);
        refresh.completeExceptionally(new IllegalStateException("Offline"));
        assertEquals(CONFIRM, d.cues.getLast(), "Transport callback must not dispatch menu audio");
        d.frame();
        assertEquals(ERROR, d.cues.getLast());
        int count = d.cues.size();
        d.frame();
        assertEquals(count, d.cues.size(), "One failed request is announced once");
    }

    @Test
    void lobbyNoOpPagesStaySilentWhileStartChatAndNestedBackReportActions() {
        RaceTransport transport = mock(RaceTransport.class);
        when(transport.isOpen()).thenReturn(true);
        when(transport.drainInbound()).thenReturn(List.of());
        ControlMessage.RoundConfig round = new ControlMessage.RoundConfig("s2", 0, 0, 300, "OPEN", null);
        Runnable leave = mock(Runnable.class);
        RaceLobbyScreen screen = new RaceLobbyScreen(new MultiplayerRaceCoordinator(transport, new ClientRaceSession(() -> 0)),
                null, true, round, "sonic", request -> { }, leave);
        Driver d = new Driver(screen::update);
        d.press(GLFW_KEY_UP); d.press(GLFW_KEY_UP); // Players
        d.cues.clear();
        d.press(GLFW_KEY_LEFT); d.press(GLFW_KEY_RIGHT); d.press(GLFW_KEY_ENTER);
        assertTrue(d.cues.isEmpty());
        d.press(GLFW_KEY_DOWN); d.press(GLFW_KEY_DOWN); // Start
        d.press(GLFW_KEY_ENTER);
        verify(transport).sendControl(new ControlMessage.RoundConfigure(round));
        d.press(GLFW_KEY_DOWN); d.press(GLFW_KEY_ENTER); // Chat editor
        d.press(GLFW_KEY_ESCAPE);
        verifyNoInteractions(leave);
        d.press(GLFW_KEY_ESCAPE);
        verify(leave).run();
        assertEquals(List.of(NAVIGATE, NAVIGATE, CONFIRM, NAVIGATE, CONFIRM, CANCEL, CANCEL), d.cues);
    }

    private static final class Driver {
        final InputHandler input = new InputHandler();
        final Consumer<InputHandler> update;
        final List<MenuFeedback.Cue> cues = new ArrayList<>();
        Driver(Consumer<InputHandler> update) { this.update = update; }
        void frame() {
            input.refreshLogicalSnapshot();
            MenuFeedback.withSink(cues::add, () -> update.accept(input));
            input.update();
        }
        void press(int key) {
            input.handleKeyEvent(key, GLFW_PRESS); frame();
            input.handleKeyEvent(key, GLFW_RELEASE); frame();
        }
    }
}
