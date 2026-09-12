package com.openggf.game;

import com.openggf.configuration.ConfigCatalog;
import com.openggf.configuration.ConfigType;
import com.openggf.configuration.EngineSettingsDraft;
import com.openggf.configuration.GlfwKeyNameResolver;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.MenuFeedback;
import static com.openggf.game.MenuFeedback.Cue.*;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/** Controller-first preferences with readable native-pixel pages and a cancellable persisted draft. */
public final class EngineSettingsScreen {
    private static final int VISIBLE_ROWS = 4;
    private static final int DESCRIPTION_LINES = 2;
    private static final EngineSettingsDraft.Category[] CATEGORIES = EngineSettingsDraft.Category.values();
    private static final float[] WHITE = {1, 1, 1};
    private static final float[] AMBER = {1, .73f, .25f};
    private static final float[] CYAN = {.4f, .88f, 1};
    private static final float[] MUTED = {.65f, .75f, .9f};
    private final EngineSettingsDraft draft;
    private final SonicConfigurationService config;
    private final PixelFont font;
    private int category;
    private int row;
    private int ticks;
    private boolean fields;
    private boolean closeRequested;
    private boolean applied;
    private boolean choosing;
    private int choice;
    private boolean keyMenu;
    private boolean capturing;
    private boolean discardPrompt;
    private boolean discardSelected;
    private MenuTextEditor textEditor;
    private MenuDetailsScreen details;
    private String detailsHint = "F1";
    private long presentationRevision = -1;
    private final java.util.Map<SonicConfiguration, String> labels = new java.util.EnumMap<>(SonicConfiguration.class);
    private final java.util.Map<SonicConfiguration, String> valueLabels = new java.util.EnumMap<>(SonicConfiguration.class);
    private final java.util.Map<EngineSettingsDraft.Category, Boolean> categoryChanged = new java.util.EnumMap<>(EngineSettingsDraft.Category.class);
    private final java.util.Map<SonicConfiguration, List<String>> optionCache = new java.util.EnumMap<>(SonicConfiguration.class);
    private String descriptionText;
    private int descriptionWidth;
    private List<String> descriptionLines = List.of();
    private String message = "Apply saves; Details explains when changes take effect";
    private String confirmHint = "Enter";
    private String backHint = "Esc";
    private String directionsHint = "Arrows";

    public EngineSettingsScreen(SonicConfigurationService config, PixelFont font) {
        this.config = config;
        this.draft = new EngineSettingsDraft(config);
        this.font = font;
    }

    public void update(InputHandler input) {
        if (input == null || closeRequested) return;
        ticks++;
        confirmHint = MenuInput.confirmLabel(input);
        backHint = MenuInput.backLabel(input);
        directionsHint = MenuInput.directionLabel(input);
        detailsHint = MenuInput.detailsLabel(input);
        if (details != null) { if (details.update(input)) details = null; return; }
        if (discardPrompt) {
            if (MenuInput.left(input) || MenuInput.right(input)) { discardSelected = !discardSelected; MenuFeedback.emit(NAVIGATE); }
            if (MenuInput.back(input)) { discardPrompt = false; MenuFeedback.emit(CANCEL); }
            else if (MenuInput.accept(input)) {
                MenuFeedback.emit(discardSelected ? CANCEL : CONFIRM);
                if (discardSelected) closeRequested = true;
                discardPrompt = false;
            }
            return;
        }
        if (textEditor != null) {
            textEditor.update(input);
            switch (textEditor.consumeResult()) {
                case ACCEPTED -> {
                    try {
                        draft.set(selected(), textEditor.value());
                        MenuFeedback.emit(CONFIRM);
                        textEditor = null;
                        message = "Draft changed. Choose Apply to save";
                    } catch (IllegalArgumentException e) { textEditor.reject(e.getMessage()); }
                }
                case CANCELLED -> textEditor = null;
                case NONE -> { }
            }
            return;
        }
        if (!MenuInput.back(input) && MenuInput.details(input) && !capturing) {
            details = new MenuDetailsScreen(fields ? EngineSettingLabels.label(selected()) : "SETTINGS HELP",
                    fields ? ConfigCatalog.meta(selected()).description() + "\n\n" + effect(selected())
                            + "\n\nCurrent draft value:\n" + draft.text(selected())
                            : "Apply saves the draft. Cancel preserves saved settings.\n\n"
                            + "White values are engine defaults; amber values differ from defaults. An asterisk marks unsaved changes.\n\n"
                            + "Open a setting's Details for its full description, value and when it takes effect.\n\n" + message);
            MenuFeedback.emit(CONFIRM); return;
        }
        if (capturing) { updateCapture(input); return; }
        if (keyMenu) { updateKeyMenu(input); return; }
        if (choosing) { updateChoice(input); return; }
        if (MenuInput.back(input)) {
            if (fields) { fields = false; MenuFeedback.emit(CANCEL); }
            else requestClose();
            return;
        }
        if (!fields) {
            if (MenuInput.up(input)) { category = Math.floorMod(category - 1, CATEGORIES.length + 2); row = 0; ticks = 0; MenuFeedback.emit(NAVIGATE); }
            if (MenuInput.down(input)) { category = (category + 1) % (CATEGORIES.length + 2); row = 0; ticks = 0; MenuFeedback.emit(NAVIGATE); }
            if (MenuInput.accept(input)) {
                if (category == CATEGORIES.length) apply();
                else if (category == CATEGORIES.length + 1) requestClose();
                else { fields = true; row = 0; ticks = 0; MenuFeedback.emit(CONFIRM); }
            }
            return;
        }
        List<SonicConfiguration> keys = keys();
        if (MenuInput.up(input)) { row = Math.floorMod(row - 1, keys.size()); ticks = 0; MenuFeedback.emit(NAVIGATE); }
        if (MenuInput.down(input)) { row = (row + 1) % keys.size(); ticks = 0; MenuFeedback.emit(NAVIGATE); }
        try {
            if (MenuInput.left(input) || MenuInput.right(input)) {
                String before = draft.text(selected());
                draft.step(selected(), MenuInput.left(input) ? -1 : 1);
                if (!before.equals(draft.text(selected()))) MenuFeedback.emit(NAVIGATE);
            }
            else if (MenuInput.accept(input)) {
                MenuFeedback.emit(CONFIRM);
                ConfigType type = ConfigCatalog.meta(selected()).type();
                if (type == ConfigType.KEY) { keyMenu = true; choice = 0; }
                else if (type == ConfigType.BOOL || type == ConfigType.ENUM) {
                    choosing = true;
                    choice = Math.max(0, options().indexOf(draft.text(selected())));
                } else openTextEditor();
            }
        } catch (IllegalArgumentException e) { message = e.getMessage(); MenuFeedback.emit(ERROR); }
    }

    private List<SonicConfiguration> keys() {
        return draft.keys(CATEGORIES[Math.min(category, CATEGORIES.length - 1)]);
    }
    private SonicConfiguration selected() { return keys().get(row); }
    private void requestClose() {
        MenuFeedback.emit(CANCEL);
        if (draft.dirty()) { discardPrompt = true; discardSelected = false; }
        else closeRequested = true;
    }
    private void apply() {
        try {
            boolean changed = draft.dirty();
            draft.apply();
            MenuFeedback.emit(CONFIRM);
            applied |= changed;
            message = changed ? "Saved. See setting Details for when changes apply" : "No changes to save";
        } catch (IOException e) { message = "Save failed. Draft kept; retry Apply"; MenuFeedback.emit(ERROR); }
        catch (IllegalArgumentException e) { message = e.getMessage(); MenuFeedback.emit(ERROR); }
    }
    private void openTextEditor() {
        Object defaultValue = config.getDefaultValue(selected());
        textEditor = new MenuTextEditor(EngineSettingLabels.label(selected()), draft.text(selected()), 4096,
                defaultValue == null ? "" : defaultValue.toString(), fieldMode(selected()));
        textEditor.deferAcceptanceFeedback();
    }
    private List<String> options() {
        return optionCache.computeIfAbsent(selected(), key -> {
            if (ConfigCatalog.meta(key).type() == ConfigType.BOOL) return List.of("false", "true");
            return ConfigCatalog.meta(key).allowedValues().stream().sorted().toList();
        });
    }

    static MenuTextEditor.Mode fieldMode(SonicConfiguration key) {
        return switch (key) {
            case SONIC_1_ROM, SONIC_2_ROM, SONIC_3K_ROM, PLAYBACK_MOVIE_PATH, TRACE_CATALOG_DIR,
                    CAPTURE_OUTPUT_DIR -> MenuTextEditor.Mode.PATH;
            case TIME_ATTACK_NET_LAST_JOIN_ADDRESS -> MenuTextEditor.Mode.ADDRESS;
            default -> switch (ConfigCatalog.meta(key).type()) {
                case INT -> MenuTextEditor.Mode.INTEGER;
                case DOUBLE -> MenuTextEditor.Mode.DECIMAL;
                default -> MenuTextEditor.Mode.TEXT;
            };
        };
    }

    static String effect(SonicConfiguration key) {
        // InputBindingFactory's supplier is sampled by InputHandler each frame.
        return switch (key) {
            case UP, DOWN, LEFT, RIGHT, P1_A, P1_B, P1_C, START,
                    P2_UP, P2_DOWN, P2_LEFT, P2_RIGHT, P2_A, P2_B, P2_C, P2_START,
                    CONTROLLER_ENABLED, CONTROLLER_DEADZONE, CONTROLLER_PLAYER1, CONTROLLER_PLAYER2,
                    DEBUG_MODE_KEY, LIVE_REWIND_KEY, FRAME_STEP_KEY -> "Takes effect immediately after Apply.";
            // MasterTitleScreen refreshes ROM previews on consumeApplied; load resolves saved ROM paths.
            case SONIC_1_ROM, SONIC_2_ROM, SONIC_3K_ROM -> "After Apply: ROM previews refresh; used for the next game load.";
            case TRACE_CATALOG_DIR -> "After Apply: used when the trace picker next opens.";
            case TIME_ATTACK_NET_LAST_JOIN_ADDRESS -> "After Apply: used when the LAN menu next opens.";
            // LiveCaptureRecorderFactory reads these when creating a recording.
            case CAPTURE_CODEC, CAPTURE_AUDIO_CODEC, CAPTURE_ENCODER_THREADS, CAPTURE_ENCODER_PRESET,
                    CAPTURE_FFMPEG_PASS1_ARGS, CAPTURE_FFMPEG_PASS2_ARGS, CAPTURE_QUEUE_BUDGET_MB,
                    CAPTURE_OUTPUT_DIR, CAPTURE_CONTAINER -> "After Apply: used for the next recording.";
            default -> "Saved by Apply. Restart the engine to ensure this setting takes effect.";
        };
    }

    private void refreshPresentation() {
        if (presentationRevision == draft.revision()) return;
        for (EngineSettingsDraft.Category group : CATEGORIES) {
            boolean any = false;
            for (SonicConfiguration key : draft.keys(group)) {
                labels.computeIfAbsent(key, EngineSettingLabels::label);
                valueLabels.put(key, MenuDetailsScreen.readable(EngineSettingLabels.value(key, draft.text(key))));
                any |= draft.nonDefault(key);
            }
            categoryChanged.put(group, any);
        }
        presentationRevision = draft.revision();
    }

    private void updateChoice(InputHandler input) {
        if (MenuInput.back(input)) { choosing = false; MenuFeedback.emit(CANCEL); return; }
        int count = options().size() + 1;
        if (MenuInput.up(input)) { choice = Math.floorMod(choice - 1, count); MenuFeedback.emit(NAVIGATE); }
        if (MenuInput.down(input)) { choice = (choice + 1) % count; MenuFeedback.emit(NAVIGATE); }
        if (MenuInput.accept(input)) {
            MenuFeedback.emit(CONFIRM);
            if (choice == count - 1) draft.reset(selected());
            else draft.set(selected(), options().get(choice));
            choosing = false;
            message = "Draft changed. Choose Apply to save";
        }
    }
    private void updateKeyMenu(InputHandler input) {
        if (MenuInput.back(input)) { keyMenu = false; MenuFeedback.emit(CANCEL); return; }
        if (MenuInput.up(input)) { choice = Math.floorMod(choice - 1, 4); MenuFeedback.emit(NAVIGATE); }
        if (MenuInput.down(input)) { choice = (choice + 1) % 4; MenuFeedback.emit(NAVIGATE); }
        if (MenuInput.accept(input)) {
            MenuFeedback.emit(CONFIRM);
            switch (choice) {
                case 0 -> capturing = true;
                case 1 -> openTextEditor();
                case 2 -> { draft.set(selected(), ""); keyMenu = false; }
                case 3 -> { draft.reset(selected()); keyMenu = false; }
                default -> throw new IllegalStateException("Unknown binding action");
            }
        }
    }
    private void updateCapture(InputHandler input) {
        if (MenuInput.textBack(input)) { capturing = false; MenuFeedback.emit(CANCEL); return; }
        for (int key = GLFW_KEY_SPACE; key <= GLFW_KEY_LAST; key++) {
            if (key >= GLFW_KEY_LEFT_SHIFT && key <= GLFW_KEY_RIGHT_SUPER) continue;
            if (!MenuInput.textKeyPressed(input, key)) continue;
            String chord = (input.isPhysicalControlDown() ? "CTRL+" : "")
                    + (input.isPhysicalShiftDown() ? "SHIFT+" : "")
                    + (input.isPhysicalAltDown() ? "ALT+" : "")
                    + (input.isPhysicalSuperDown() ? "META+" : "")
                    + GlfwKeyNameResolver.nameOf(key);
            draft.set(selected(), chord);
            MenuFeedback.emit(CONFIRM);
            capturing = false;
            keyMenu = false;
            message = "Binding changed. Choose Apply to save";
            return;
        }
    }
    public boolean consumeCloseRequested() {
        boolean result = closeRequested;
        closeRequested = false;
        return result;
    }
    public boolean consumeApplied() {
        boolean result = applied;
        applied = false;
        return result;
    }

    public void render(int viewportWidth) {
        if (font == null) return;
        int width = Math.max(320, viewportWidth);
        if (details != null) { details.render(font, width); return; }
        refreshPresentation();
        if (textEditor != null) { textEditor.render(font, width); return; }
        if (choosing) { renderChoice(width); return; }
        if (keyMenu || capturing) { renderKeyMenu(width); return; }
        MenuStyle.page(font, width, "ENGINE SETTINGS", null);
        text(draft.dirty() ? "DRAFT" : "SAVED", width - 42, 10, 36, draft.dirty() ? AMBER : MUTED);
        int railWidth = 100;
        MenuStyle.panel(font, 6, 35, railWidth - 5, 141);
        for (int i = 0; i < CATEGORIES.length + 2; i++) {
            int y = railY(i);
            if (category == i) {
                if (!fields) MenuStyle.focusLabel(font, 7, y, railWidth - 7, 13);
                else MenuStyle.panel(font, 7, y - 1, railWidth - 7, 13);
            }
            String name = i < CATEGORIES.length ? CATEGORIES[i].label() : i == CATEGORIES.length ? "Apply" : "Cancel";
            boolean nonDefault = i < CATEGORIES.length && categoryChanged.get(CATEGORIES[i]);
            label(name, 12, y, 81, nonDefault ? AMBER : WHITE);
        }
        if (category < CATEGORIES.length) {
            List<SonicConfiguration> keys = keys();
            int page = row / VISIBLE_ROWS;
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int index = page * VISIBLE_ROWS + i;
                if (index >= keys.size()) break;
                SonicConfiguration key = keys.get(index);
                int y = 39 + i * 30;
                if (fields && row == index) MenuStyle.focusContent(font, 104, y, width - 111, 29, 23);
                int space = width - 120;
                label(window(labels.get(key), space / 9, fields && row == index), 111, y, space, WHITE);
                String value = valueLabels.get(key);
                label(window(value, space / 9 - 2, fields && row == index) + (draft.changed(key) ? " *" : ""),
                        111, y + 13, space, draft.nonDefault(key) ? AMBER : WHITE);
            }
            text("Page " + (page + 1) + "/" + ((keys.size() + VISIBLE_ROWS - 1) / VISIBLE_ROWS)
                    + "  " + (row + 1) + "/" + keys.size(), 111, 162, width - 120, MUTED);
            String description = fields ? ConfigCatalog.meta(selected()).description() : "Choose a category, then a setting. White: default. Amber: changed.";
            description(description, width, 178);
        } else {
            label(category == CATEGORIES.length ? "Save all changes" : "Discard changes", 111, 48, width - 120, WHITE);
            text("Details explains change timing", 111, 70, width - 120, MUTED);
            text("White: engine default", 111, 94, width - 120, WHITE);
            text("Amber: non-default value", 111, 108, width - 120, AMBER);
        }
        footer(width, confirmHint + (fields ? " Edit" : " Select") + "  " + backHint + " Back  " + directionsHint,
                detailsHint + " Details  " + window(message, Math.max(1, (width - 18) / 6 - detailsHint.length() - 10), true));
        if (discardPrompt) renderDiscard(width);
    }
    private static int railY(int i) { return i < CATEGORIES.length ? 40 + i * 13 : 151 + (i - CATEGORIES.length) * 14; }
    private void renderDiscard(int width) {
        MenuStyle.panel(font, 24, 72, width - 48, 79);
        label("Discard unsaved changes?", 35, 84, width - 70, WHITE);
        int buttonWidth = (width - 80) / 2;
        int selectedX = discardSelected ? width / 2 + 5 : 35;
        MenuStyle.focus(font, selectedX - 3, 115, buttonWidth, 19);
        label("Keep editing", 35, MenuStyle.textY(115, 19, 1), buttonWidth - 6, WHITE);
        label("Discard", width / 2 + 5, MenuStyle.textY(115, 19, 1), buttonWidth - 6, AMBER);
    }
    private void renderChoice(int width) {
        MenuStyle.page(font, width, EngineSettingLabels.label(selected()), "CHOOSE A VALUE");
        List<String> values = options();
        String defaultText = String.valueOf(config.getDefaultValue(selected()));
        for (int i = 0; i <= values.size(); i++) {
            int y = 59 + i * 18;
            if (choice == i) MenuStyle.focusLabel(font, 9, y, width - 18, 17);
            String label = i == values.size() ? "Restore engine default" : EngineSettingLabels.value(selected(), values.get(i));
            boolean nonDefault = i < values.size() && !values.get(i).equals(defaultText);
            label(label, 17, y, width - 34, nonDefault ? AMBER : WHITE);
        }
        footer(width, confirmHint + " Set  " + backHint + " Cancel  " + directionsHint,
                "Changes stay in draft until Apply");
    }
    private void renderKeyMenu(int width) {
        MenuStyle.page(font, width, EngineSettingLabels.label(selected()), "KEYBOARD BINDING");
        label(EngineSettingLabels.value(selected(), draft.text(selected())), 12, 51, width - 24,
                draft.nonDefault(selected()) ? AMBER : WHITE);
        if (capturing) {
            label("Press a keyboard key or chord", 12, 87, width - 24, CYAN);
            text("Modifiers: Ctrl, Shift, Alt, Super", 12, 103, width - 24, MUTED);
            text("Use key-name editing to bind Escape", 12, 129, width - 24, MUTED);
            footer(width, backHint + " Cancel capture", "Controller buttons keep navigating menus");
            return;
        }
        String[] labels = { "Capture keyboard binding", "Edit key name / chord", "Unbind this shortcut", "Restore engine default" };
        for (int i = 0; i < labels.length; i++) {
            int y = 81 + i * 23;
            if (choice == i) MenuStyle.focusLabel(font, 9, y, width - 18, 21);
            label(labels[i], 17, y, width - 34, WHITE);
        }
        footer(width, confirmHint + " Choose  " + backHint + " Back  " + directionsHint,
                detailsHint + " Details  Draft until Apply");
    }
    private void description(String value, int width, int y) {
        if (!value.equals(descriptionText) || width != descriptionWidth) {
            descriptionText = value;
            descriptionWidth = width;
            descriptionLines = MenuDetailsScreen.wrapText(MenuDetailsScreen.readable(value), (width - 18) / 6);
        }
        for (int i = 0; i < DESCRIPTION_LINES && i < descriptionLines.size(); i++) {
            text(descriptionLines.get(i), 9, y + i * 10, width - 18, MUTED);
        }
    }

    private String window(String value, int chars, boolean scroll) {
        if (value.length() <= chars) return value;
        if (!scroll) return value.substring(0, Math.max(0, chars - 3)) + "...";
        int overflow = value.length() - chars;
        int offset = Math.min(overflow, Math.max(0, (ticks / 8) % (overflow + 30) - 15));
        return value.substring(offset, offset + chars);
    }
    private void footer(int width, String controls, String detail) {
        MenuStyle.footer(font, width, detail, controls);
    }
    private void label(String value, int x, int y, int space, float[] color) {
        MenuStyle.label(font, value, x, y, space, color[0], color[1], color[2]);
    }
    private void text(String value, int x, int y, int space, float[] color) {
        MenuStyle.text(font, value, x, y, space, color[0], color[1], color[2]);
    }
}
