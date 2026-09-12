package com.openggf.game.timeattack.mp;

import com.openggf.control.InputHandler;
import com.openggf.net.client.MasterClient;
import com.openggf.graphics.PixelFont;
import java.util.ArrayList;
import com.openggf.net.protocol.ControlMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.lwjgl.glfw.GLFW.*;

class TestServerBrowserScreen {
    @Test
    void visibleActionsCreateAndRefreshWithoutLetterShortcuts() {
        MasterClient client = client(List.of());
        ServerBrowserScreen.Actions actions = mock(ServerBrowserScreen.Actions.class);
        ServerBrowserScreen screen = new ServerBrowserScreen(client, "s2", null, actions);
        InputHandler input = new InputHandler();
        press(screen, input, GLFW_KEY_RIGHT);
        press(screen, input, GLFW_KEY_ENTER);
        verify(actions).create("DIRECT");
        press(screen, input, GLFW_KEY_DOWN);
        press(screen, input, GLFW_KEY_ENTER);
        verify(client, times(2)).listRooms("s2", 0);
        verify(actions, never()).join(any());
    }

    @Test
    void everyRoomRemainsReachablePastTheVisiblePage() {
        List<ControlMessage.RoomSummary> rooms = IntStream.range(0, 14).mapToObj(i ->
                new ControlMessage.RoomSummary("room" + i, "Room " + i, "s2", 0, 0,
                        "OPEN", 1, 8, "RELAY", true)).toList();
        ServerBrowserScreen.Actions actions = mock(ServerBrowserScreen.Actions.class);
        ServerBrowserScreen screen = new ServerBrowserScreen(client(rooms), "s2", null, actions);
        InputHandler input = new InputHandler();
        for (int i = 0; i < 13; i++) press(screen, input, GLFW_KEY_DOWN);
        press(screen, input, GLFW_KEY_ENTER);
        verify(actions).join(rooms.get(13));
        press(screen, input, GLFW_KEY_DOWN);
        press(screen, input, GLFW_KEY_ENTER);
        verify(actions).create("RELAY");
    }

    @Test
    void backWinsOverSimultaneousAccept() {
        ServerBrowserScreen.Actions actions = mock(ServerBrowserScreen.Actions.class);
        ServerBrowserScreen screen = new ServerBrowserScreen(client(List.of()), "s2", null, actions);
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);
        screen.update(input);
        verify(actions).back();
        verify(actions, never()).create(any());
    }

    @Test
    void longRoomLabelsStayBoundedAndSelectionScrollsIntoView() {
        List<ControlMessage.RoomSummary> rooms = IntStream.range(0, 14).mapToObj(i ->
                new ControlMessage.RoomSummary("room" + i, "Room" + i + "-" + "long".repeat(30), "s2", 0, 0,
                        "OPEN", 1, 8, "RELAY", false)).toList();
        RecordingFont font = new RecordingFont();
        ServerBrowserScreen screen = new ServerBrowserScreen(client(rooms), "s2", font,
                mock(ServerBrowserScreen.Actions.class));
        InputHandler input = new InputHandler();
        for (int i = 0; i < 13; i++) press(screen, input, GLFW_KEY_DOWN);
        screen.render();
        assertTrue(font.lines.stream().anyMatch(line -> line.startsWith("Room13-")));
        assertFalse(font.lines.stream().anyMatch(line -> line.startsWith("Room0-")));
    }

    private static final class RecordingFont extends PixelFont {
        final List<String> lines = new ArrayList<>();
        @Override public void drawText(String text, int x, int y, float r, float g, float b, float a) {
            record(text, x, y, 9);
        }
        @Override public void drawText(String text, int x, int y, float scale, float r, float g, float b, float a) {
            record(text, x, y, scale >= 1 ? 9 : 6);
        }
        private void record(String text, int x, int y, int advance) {
            assertTrue(x >= 0 && x + text.length() * advance <= 320, text);
            assertTrue(y >= 0 && y + 10 <= 224, text);
            lines.add(text);
        }
    }

    private static MasterClient client(List<ControlMessage.RoomSummary> rooms) {
        MasterClient client = mock(MasterClient.class);
        when(client.isOpen()).thenReturn(true);
        when(client.listRooms(eq("s2"), anyInt())).thenAnswer(call ->
                CompletableFuture.completedFuture(new ControlMessage.RoomListResult(rooms, call.getArgument(1), 1)));
        return client;
    }

    private static void press(ServerBrowserScreen screen, InputHandler input, int key) {
        input.handleKeyEvent(key, GLFW_PRESS);
        screen.update(input); input.update();
        input.handleKeyEvent(key, GLFW_RELEASE);
        screen.update(input); input.update();
    }
}
