package com.openggf.game.timeattack.mp;

import com.openggf.game.MenuFeedback;
import static com.openggf.game.MenuFeedback.Cue.*;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.game.MenuStyle;
import com.openggf.game.MenuTextEditor;
import com.openggf.game.timeattack.TimeAttackLaunchRequest;
import com.openggf.graphics.PixelFont;
import com.openggf.net.client.ClientRaceSession;
import com.openggf.net.protocol.ControlMessage;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Master-title sub-screen that owns the room network pump between rounds. */
public final class RaceLobbyScreen {
    private enum Focus { PLAYERS, HISTORY, START, CHAT, LEAVE }
    private Focus focus;
    private int playerPage;
    private int chatPage;
    private InputHandler menuInput;
    private MenuTextEditor editor;

    private final MultiplayerRaceCoordinator coordinator;
    private final PixelFont font;
    private final boolean host;
    private final ControlMessage.RoundConfig configuredRound;
    private final String localCharacter;
    private final Consumer<TimeAttackLaunchRequest> roundLauncher;
    private final Runnable leaveHandler;
    private boolean launchedCurrentRound;

    public RaceLobbyScreen(MultiplayerRaceCoordinator coordinator, PixelFont font,
                           boolean host, ControlMessage.RoundConfig configuredRound,
                           String localCharacter,
                           Consumer<TimeAttackLaunchRequest> roundLauncher,
                           Runnable leaveHandler) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.font = font;
        this.host = host;
        this.focus = host ? Focus.START : Focus.CHAT;
        this.configuredRound = Objects.requireNonNull(configuredRound, "configuredRound");
        this.localCharacter = Objects.requireNonNull(localCharacter, "localCharacter");
        this.roundLauncher = Objects.requireNonNull(roundLauncher, "roundLauncher");
        this.leaveHandler = Objects.requireNonNull(leaveHandler, "leaveHandler");
    }

    public void update(InputHandler input) {
        coordinator.pump();
        menuInput = input;
        if (coordinator.hudState().connectionLost() || coordinator.hudState().kickReason() != null) {
            MenuFeedback.emit(ERROR);
            leaveHandler.run();
            return;
        }
        if (editor != null) {
            editor.update(input);
            switch (editor.consumeResult()) {
                case ACCEPTED -> {
                    if (!editor.value().isBlank()) coordinator.sendChat(editor.value());
                    editor = null;
                }
                case CANCELLED -> editor = null;
                case NONE -> { }
            }
        } else {
            if (MenuInput.back(input)) { MenuFeedback.emit(CANCEL); leaveHandler.run(); return; }
            Focus beforeFocus = focus;
            int beforePlayers = playerPage, beforeChat = chatPage;
            List<Focus> choices = host ? List.of(Focus.PLAYERS, Focus.HISTORY, Focus.START, Focus.CHAT, Focus.LEAVE)
                    : List.of(Focus.PLAYERS, Focus.HISTORY, Focus.CHAT, Focus.LEAVE);
            int delta = MenuInput.up(input) ? -1 : MenuInput.down(input) ? 1 : 0;
            if (delta != 0) focus = choices.get(Math.floorMod(choices.indexOf(focus) + delta, choices.size()));
            int horizontal = MenuInput.left(input) ? -1 : MenuInput.right(input) ? 1 : 0;
            if (focus == Focus.PLAYERS) playerPage = Math.clamp(playerPage + horizontal, 0,
                    Math.max(0, (coordinator.session().players().size() - 1) / 2));
            if (focus == Focus.HISTORY) chatPage = Math.clamp(chatPage + horizontal, 0,
                    Math.max(0, (coordinator.session().chatLines().size() - 1) / 2));
            if (focus != beforeFocus || playerPage != beforePlayers || chatPage != beforeChat) MenuFeedback.emit(NAVIGATE);
            if (MenuInput.accept(input)) {
                switch (focus) {
                    case CHAT -> { editor = new MenuTextEditor("ROOM CHAT", "", 200); MenuFeedback.emit(CONFIRM); }
                    case LEAVE -> { MenuFeedback.emit(CANCEL); leaveHandler.run(); return; }
                    case START -> {
                        if (canStart()) { coordinator.sendRoundConfigure(configuredRound); MenuFeedback.emit(CONFIRM); }
                        else MenuFeedback.emit(ERROR);
                    }
                    case PLAYERS, HISTORY -> { }
                }
            }
        }
        ClientRaceSession.Phase phase = coordinator.session().phase();
        if (phase == ClientRaceSession.Phase.LOBBY) {
            launchedCurrentRound = false;
        } else if ((phase == ClientRaceSession.Phase.COUNTDOWN
                || phase == ClientRaceSession.Phase.RUNNING) && !launchedCurrentRound) {
            ControlMessage.RoundConfig round = coordinator.session().roundConfig();
            if (round != null) {
                launchedCurrentRound = true;
                roundLauncher.accept(new TimeAttackLaunchRequest(round.gameId(), round.zone(),
                        round.act(), localCharacter, List.of()));
            }
        }
    }

    private boolean canStart() {
        ClientRaceSession.Phase phase = coordinator.session().phase();
        return host && (phase == ClientRaceSession.Phase.LOBBY || phase == ClientRaceSession.Phase.ROUND_END);
    }

    public void render() {
        if (font == null) return;
        if (editor != null) { editor.render(font, 320); return; }
        ControlMessage.RoomDescriptor room = coordinator.session().room();
        MenuStyle.page(font, 320, "RACE LOBBY", room == null ? "Connecting..." : room.name());
        List<ControlMessage.PlayerInfo> players = coordinator.session().players();
        playerPage = Math.min(playerPage, Math.max(0, (players.size() - 1) / 2));
        if (focus == Focus.PLAYERS) MenuStyle.focus(font, 7, 44, 306, 57);
        text("PLAYERS " + (playerPage + 1) + "/" + Math.max(1, (players.size() + 1) / 2), 10, 46, 300, .5f, .9f, 1);
        for (int i = playerPage * 2; i < Math.min(players.size(), playerPage * 2 + 2); i++) {
            ControlMessage.PlayerInfo player = players.get(i);
            String fingerprint = player.fingerprint() == null ? "????"
                    : player.fingerprint().substring(0, Math.min(4, player.fingerprint().length()));
            String name = player.displayName();
            String badge = i == 0 ? " HOST" : player.newPlayer() ? " NEW" : "";
            MenuStyle.label(font, name, 12, 58 + (i % 2) * 22, 180, 1, 1, 1);
            text("#" + fingerprint + badge, 12, 70 + (i % 2) * 22, 180, .7f, .8f, .9f);
            MenuStyle.label(font, player.character().toUpperCase(), 204, 58 + (i % 2) * 22, 108, 1, 1, 1);
        }
        List<String> history = coordinator.session().chatLines();
        chatPage = Math.min(chatPage, Math.max(0, (history.size() - 1) / 2));
        if (focus == Focus.HISTORY) MenuStyle.focus(font, 7, 103, 306, 34);
        text("CHAT HISTORY  < " + (chatPage + 1) + " >", 10, 105, 300, .5f, .9f, 1);
        int last = Math.max(0, history.size() - chatPage * 2);
        int first = Math.max(0, last - 2);
        for (int i = first; i < last; i++) text(history.get(i), 12, 117 + (i - first) * 10, 296, .8f, .85f, .95f);
        if (history.isEmpty()) text("No messages yet", 12, 117, 296, .7f, .8f, .9f);
        boolean verified = room != null && room.verified();
        text((verified ? "VERIFIED" : "UNVERIFIED TIMES") + " / " + coordinator.session().phase(),
                10, 143, 300, verified ? .6f : 1, .8f, verified ? 1 : .3f);
        if (host) action(Focus.START, canStart() ? "START ROUND" : "WAITING FOR ROUND", 153, canStart());
        action(Focus.CHAT, "WRITE CHAT MESSAGE", 169, true);
        action(Focus.LEAVE, "LEAVE ROOM", 185, true);
        String confirm = menuInput == null ? "Enter" : MenuInput.confirmLabel(menuInput);
        String back = menuInput == null ? "Esc" : MenuInput.backLabel(menuInput);
        MenuStyle.footer(font, 320, "Up/Down Select  L/R Players/History", confirm + " Open  " + back + " Leave");
    }

    private void action(Focus action, String label, int y, boolean enabled) {
        if (focus == action) MenuStyle.focusLabel(font, 7, y, 306, 16);
        float brightness = enabled ? 1 : .55f;
        MenuStyle.label(font, label, 12, y, 296, brightness, brightness, brightness);
    }

    private void text(String value, int x, int y, int maxWidth, float r, float g, float b) {
        MenuStyle.text(font, value, x, y, maxWidth, r, g, b);
    }
}
