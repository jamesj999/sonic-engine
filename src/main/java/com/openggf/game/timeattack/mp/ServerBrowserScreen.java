package com.openggf.game.timeattack.mp;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.game.MenuStyle;
import com.openggf.graphics.PixelFont;
import com.openggf.net.client.MasterClient;
import com.openggf.net.protocol.ControlMessage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;

/** Master-title room browser backed by an admitted master-server connection. */
@com.openggf.game.ModApi
public final class ServerBrowserScreen {
    private static final int VISIBLE_ROOMS = 4;
    private enum Focus { ROOMS, CREATE, REFRESH, PAGE }
    private Focus focus = Focus.ROOMS;
    private InputHandler menuInput;
    private static final long REFRESH_INTERVAL_MILLIS = 2000;

    @com.openggf.game.ModApi
    public interface Actions {
        void join(ControlMessage.RoomSummary room);
        void create(String routing);
        void back();
    }

    private final MasterClient client;
    private final String gameId;
    private final PixelFont font;
    private final Actions actions;
    private volatile List<ControlMessage.RoomSummary> rooms = List.of();
    private volatile String status = "Loading rooms...";
    private volatile boolean refreshInFlight;
    private int selected;
    private int page;
    private int totalPages;
    private String createRouting = "RELAY";
    private long lastRefreshAt = Long.MIN_VALUE;

    public ServerBrowserScreen(MasterClient client, String gameId,
                               PixelFont font, Actions actions) {
        this.client = Objects.requireNonNull(client, "client");
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.font = font;
        this.actions = Objects.requireNonNull(actions, "actions");
        refresh(System.currentTimeMillis());
    }

    public void update(InputHandler input) {
        long now = System.currentTimeMillis();
        if (!client.isOpen()) {
            status = "Master connection lost";
        } else if (lastRefreshAt == Long.MIN_VALUE
                || now - lastRefreshAt >= REFRESH_INTERVAL_MILLIS) {
            refresh(now);
        }
        menuInput = input;
        if (MenuInput.back(input)) { actions.back(); return; }
        if (MenuInput.up(input)) move(-1);
        if (MenuInput.down(input)) move(1);
        int delta = MenuInput.left(input) ? -1 : MenuInput.right(input) ? 1 : 0;
        if (delta != 0) {
            if (focus == Focus.CREATE) createRouting = createRouting.equals("RELAY") ? "DIRECT" : "RELAY";
            else if (focus == Focus.PAGE || focus == Focus.ROOMS) changePage(delta, now);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_R)) refresh(now);
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_C)) { actions.create(createRouting); return; }
        if (MenuInput.accept(input)) {
            switch (focus) {
                case ROOMS -> {
                    List<ControlMessage.RoomSummary> snapshot = rooms;
                    if (selected < snapshot.size()) actions.join(snapshot.get(selected));
                }
                case CREATE -> actions.create(createRouting);
                case REFRESH -> refresh(now);
                case PAGE -> changePage(1, now);
            }
        }
    }

    private void move(int delta) {
        int count = rooms.size();
        int current = focus == Focus.ROOMS ? Math.min(selected, Math.max(0, count - 1))
                : count + focus.ordinal() - 1;
        int next = Math.floorMod(current + delta, count + 3);
        if (next < count) { focus = Focus.ROOMS; selected = next; }
        else focus = Focus.values()[next - count + 1];
    }

    private void changePage(int delta, long now) {
        int next = Math.clamp(page + delta, 0, Math.max(0, totalPages - 1));
        if (next != page && !refreshInFlight) { page = next; selected = 0; refresh(now); }
    }

    public void render() {
        if (font == null) return;
        MenuStyle.page(font, 320, "TIME ATTACK ROOMS", gameId.toUpperCase());
        List<ControlMessage.RoomSummary> snapshot = rooms;
        int first = selected / VISIBLE_ROOMS * VISIBLE_ROOMS;
        if (snapshot.isEmpty()) text("No rooms. Create one below.", 10, 46, 300, .7f, .8f, .9f);
        for (int i = first; i < Math.min(snapshot.size(), first + VISIBLE_ROOMS); i++) {
            int y = 47 + (i - first) * 22;
            ControlMessage.RoomSummary room = snapshot.get(i);
            if (focus == Focus.ROOMS && i == selected) MenuStyle.focus(font, 7, y - 2, 306, 22);
            MenuStyle.label(font, room.name(), 12, y, 234, 1, 1, 1);
            MenuStyle.label(font, room.playerCount() + "/" + room.maxPlayers(), 258, y, 54, 1, 1, 1);
            text(room.routing() + " / " + (room.verified() ? "VERIFIED" : "UNVERIFIED TIMES"),
                    12, y + 11, 294, room.verified() ? .6f : 1, .8f, room.verified() ? 1 : .3f);
        }
        action(Focus.CREATE, "CREATE ROOM  < " + createRouting + " >", 147);
        action(Focus.REFRESH, "REFRESH ROOMS", 165);
        action(Focus.PAGE, "PAGE  < " + (page + 1) + " / " + Math.max(1, totalPages) + " >", 183);
        String confirm = menuInput == null ? "Enter" : MenuInput.confirmLabel(menuInput);
        String back = menuInput == null ? "Esc" : MenuInput.backLabel(menuInput);
        MenuStyle.footer(font, 320, MenuStyle.fit(status, 138) + "  L/R Page/Route",
                confirm + " Select  " + back + " Back");
    }

    private void action(Focus item, String label, int y) {
        if (focus == item) MenuStyle.focus(font, 7, y - 2, 306, 16);
        MenuStyle.label(font, label, 12, y, 294, 1, 1, 1);
    }

    private void text(String value, int x, int y, int maxWidth, float r, float g, float b) {
        MenuStyle.text(font, value, x, y, maxWidth, r, g, b);
    }

    private void refresh(long now) {
        if (refreshInFlight || !client.isOpen()) {
            return;
        }
        refreshInFlight = true;
        lastRefreshAt = now;
        client.listRooms(gameId, page).whenComplete((result, error) -> {
            refreshInFlight = false;
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                status = "Refresh failed: " + cause.getMessage();
                return;
            }
            rooms = List.copyOf(result.rooms());
            if (rooms.isEmpty() && focus == Focus.ROOMS) focus = Focus.CREATE;
            totalPages = result.totalPages();
            selected = Math.min(selected, Math.max(0, rooms.size() - 1));
            status = rooms.isEmpty() ? "No rooms found"
                    : "Page " + (result.page() + 1) + "/" + Math.max(1, totalPages);
        });
    }
}
