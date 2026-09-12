package com.openggf.game;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.game.launch.LaunchProfile;
import com.openggf.game.launch.LaunchProfileStore;
import com.openggf.graphics.PixelFont;
import com.openggf.graphics.TexturedQuadRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;

/**
 * Per-game launch options panel hosted by {@link MasterTitleScreen}.
 */
public final class LaunchConfigPanel {

    private static final int SCREEN_H = 224;
    private static final float TEXT_SCALE = 1f;
    private static final int TEXT_SIDE_PADDING = 4;

    public enum Result { NONE, CLOSED, CANCELLED }

    record RowView(String label, String value, boolean stock, boolean nonStandard, boolean experimental) {
    }

    record TextLineView(String text, int x, int y, float scale, int measuredWidth,
                        float r, float g, float b, float a) {
    }

    private final MasterTitleScreen.GameEntry entry;
    private final LaunchProfileStore store;
    private final SonicConfigurationService configService;
    private final PixelFont font;
    @SuppressWarnings("unused")
    private final TexturedQuadRenderer renderer;
    private final LaunchProfile.Row[] rows = LaunchProfile.Row.values();

    private LaunchProfile profile;
    private final LaunchProfile originalProfile;
    private InputHandler lastInput;
    private boolean acceptArmed;
    private int selectedRow;
    private Result pendingResult = Result.NONE;
    private boolean closed;
    private String saveError;

    public LaunchConfigPanel(MasterTitleScreen.GameEntry entry,
                             LaunchProfile currentProfile,
                             LaunchProfileStore store,
                             SonicConfigurationService configService,
                             PixelFont font,
                             TexturedQuadRenderer renderer) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.store = Objects.requireNonNull(store, "store");
        this.profile = this.store.sanitize(
                Objects.requireNonNull(currentProfile, "currentProfile"), entry);
        this.originalProfile = this.profile;
        this.configService = Objects.requireNonNull(configService, "configService");
        this.font = font;
        this.renderer = renderer;
    }

    public void update(InputHandler inputHandler) {
        if (closed || inputHandler == null) {
            return;
        }
        lastInput = inputHandler;
        boolean accept = MenuInput.accept(inputHandler);
        if (!accept) acceptArmed = true;
        if (MenuInput.back(inputHandler)) {
            profile = originalProfile;
            close();
            pendingResult = Result.CANCELLED;
            return;
        }
        if (inputHandler.isKeyPressed(GLFW_KEY_TAB) || inputHandler.isGamepadBackButtonPressed()) {
            close();
            return;
        }
        if (inputHandler.isKeyPressed(GLFW_KEY_BACKSPACE)) {
            profile = profile.withStock(entry);
            return;
        }
        if (MenuInput.up(inputHandler)) {
            selectedRow = wrapRow(selectedRow - 1, visibleRows().size() + 3);
        }
        if (MenuInput.down(inputHandler)) {
            selectedRow = wrapRow(selectedRow + 1, visibleRows().size() + 3);
        }
        normalizeSelectedRow();
        int fieldCount = visibleRows().size();
        if (selectedRow >= fieldCount) {
            if (MenuInput.left(inputHandler)) selectedRow = fieldCount + Math.floorMod(selectedRow - fieldCount - 1, 3);
            if (MenuInput.right(inputHandler)) selectedRow = fieldCount + (selectedRow - fieldCount + 1) % 3;
        }
        if (accept && acceptArmed) {
            acceptArmed = false;
            if (selectedRow == fieldCount) {
                profile = profile.withStock(entry);
                saveError = null;
            } else if (selectedRow == fieldCount + 2) {
                profile = originalProfile;
                close();
                pendingResult = Result.CANCELLED;
            } else close();
            return;
        }
        if (selectedRow >= fieldCount) return;
        LaunchProfile.Row row = visibleRows().get(selectedRow);
        if (MenuInput.left(inputHandler)) {
            profile = store.withPrevious(profile, row, entry);
        }
        if (MenuInput.right(inputHandler)) {
            profile = store.withNext(profile, row, entry);
        }
        normalizeSelectedRow();
    }

    public Result consumeResult() {
        Result result = pendingResult;
        pendingResult = Result.NONE;
        return result;
    }

    void saveFailed() {
        closed = false;
        acceptArmed = false;
        saveError = "Save failed - retry or cancel";
    }

    public void render(int viewportWidth) {
        if (font == null) {
            return;
        }
        font.beginMegaBatch();
        try {
            MenuStyle.page(font, viewportWidth, "LAUNCH OPTIONS", entry.displayName);
            int fieldCount = visibleRows().size();
            if (selectedRow < fieldCount) {
                MenuStyle.focus(font, 9, 48 + selectedRow * 16, viewportWidth - 18, 15);
                font.drawText(">", 13, 52 + selectedRow * 16, TEXT_SCALE, .5f, .91f, 1f, 1f);
            }
            String[] actions = {"Stock", "Save", "Cancel"};
            int buttonWidth = (viewportWidth - 24) / 3;
            for (int i = 0; i < actions.length; i++) {
                int x = 8 + i * (buttonWidth + 4);
                MenuStyle.panel(font, x, 154, buttonWidth, 17);
                if (selectedRow == fieldCount + i) MenuStyle.focus(font, x, 154, buttonWidth, 17);
                MenuStyle.label(font, actions[i], x + 6, 159, buttonWidth - 12, 1, 1, 1);
            }
            for (TextLineView line : textLineViews(viewportWidth)) {
                font.drawText(line.text(), line.x(), line.y(), line.scale(),
                        line.r(), line.g(), line.b(), line.a());
            }
        } finally {
            font.endMegaBatch();
        }
    }

    List<RowView> rowViews() {
        List<LaunchProfile.Row> visibleRows = visibleRows();
        List<RowView> views = new ArrayList<>(visibleRows.size());
        for (LaunchProfile.Row row : visibleRows) {
            views.add(new RowView(
                    row == LaunchProfile.Row.MAIN_CHARACTER ? "Character" : LaunchProfile.rowLabel(row),
                    rowValue(row),
                    profile.isStock(row, entry),
                    store.isNonStandard(profile, row, entry),
                    profile.isExperimental(row)));
        }
        return List.copyOf(views);
    }

    LaunchProfile.Row selectedRowForTest() {
        normalizeSelectedRow();
        return selectedRow < visibleRows().size() ? visibleRows().get(selectedRow) : null;
    }

    LaunchProfile currentProfileForTest() {
        return profile;
    }

    LaunchProfile currentProfile() {
        return profile;
    }

    List<TextLineView> textLineViews(int viewportWidth) {
        List<TextLineView> lines = new ArrayList<>();
        List<RowView> views = rowViews();
        int y = 52;
        for (int i = 0; i < views.size(); i++) {
            RowView view = views.get(i);
            boolean selected = i == selectedRow;
            String marker = " ";
            String suffix = rowSuffix(view);
            float r = rowRed(view, selected);
            float g = rowGreen(view, selected);
            float b = rowBlue(view, selected);
            addCenteredLine(lines, marker + " " + view.label() + ": " + view.value() + suffix,
                    viewportWidth, y, TEXT_SCALE, r, g, b, 1f);
            y += 16;
        }
        addCenteredLine(lines, saveError == null ? "! experimental" : saveError, viewportWidth, SCREEN_H - 48,
                MenuStyle.COMPACT, 1f, 0.25f, 0.25f, 1f);
        addCenteredLine(lines, "* non-default / non-stock", viewportWidth, SCREEN_H - 34,
                MenuStyle.COMPACT, 1f, 0.72f, 0.25f, 1f);
        addCenteredLine(lines, (lastInput == null ? "Arrows" : MenuInput.directionLabel(lastInput)) + (selectedRow < visibleRows().size() ? " Edit " : " Move ")
                        + (lastInput == null ? "Enter" : MenuInput.confirmLabel(lastInput)) + (selectedRow < visibleRows().size() ? " Save " : " OK ")
                        + (lastInput == null ? "Esc" : MenuInput.backLabel(lastInput)) + " Cancel",
                viewportWidth, SCREEN_H - 16, 1f, 0.7f, 0.7f, 0.7f, 1f);
        return List.copyOf(lines);
    }

    private static String rowSuffix(RowView view) {
        if (view.experimental()) {
            return "!";
        }
        if (view.nonStandard()) {
            return "*";
        }
        return view.stock() ? "" : "*";
    }

    private static float rowRed(RowView view, boolean selected) {
        return 1f;
    }

    private static float rowGreen(RowView view, boolean selected) {
        if (view.experimental()) {
            return 0.25f;
        }
        return view.nonStandard() || !view.stock() ? 0.72f : 1f;
    }

    private static float rowBlue(RowView view, boolean selected) {
        if (view.experimental()) {
            return 0.25f;
        }
        return view.nonStandard() || !view.stock() ? 0.25f : 1f;
    }

    private void close() {
        if (closed) {
            return;
        }
        closed = true;
        pendingResult = Result.CLOSED;
    }

    private void addCenteredLine(List<TextLineView> lines, String text, int viewportWidth,
                                 int y, float scale, float r, float g, float b, float a) {
        if (measureWidth(text, scale) > viewportWidth - 18) {
            text = scale < 1f ? MenuStyle.fit(text, viewportWidth - 18)
                    : MenuStyle.fitLabel(text, viewportWidth - 18);
        }
        int width = measureWidth(text, scale);
        int x = Math.round((viewportWidth - width) / 2f);
        lines.add(new TextLineView(text, x, y, scale, width, r, g, b, a));
    }

    private float fittedScale(String text, float preferredScale, int viewportWidth) {
        int width = measureWidth(text, preferredScale);
        int maxWidth = Math.max(1, viewportWidth - TEXT_SIDE_PADDING * 2);
        if (width <= maxWidth) {
            return preferredScale;
        }
        return preferredScale * maxWidth / width;
    }

    private int measureWidth(String text, float scale) {
        if (font != null) {
            return font.measureWidth(text, scale);
        }
        return text.length() * (scale < 1 ? 6 : 9 * Math.max(1, Math.round(scale)));
    }

    private int wrapRow(int index, int rowCount) {
        if (rowCount <= 0) {
            return 0;
        }
        if (index < 0) {
            return rowCount - 1;
        }
        if (index >= rowCount) {
            return 0;
        }
        return index;
    }

    private void normalizeSelectedRow() {
        int rowCount = visibleRows().size() + 3;
        if (selectedRow >= rowCount) {
            selectedRow = Math.max(0, rowCount - 1);
        }
    }

    private List<LaunchProfile.Row> visibleRows() {
        List<LaunchProfile.Row> visible = new ArrayList<>(rows.length);
        for (LaunchProfile.Row row : rows) {
            if (profile.isVisibleInLaunchPanel(row)) {
                visible.add(row);
            }
        }
        return visible;
    }

    private String rowValue(LaunchProfile.Row row) {
        if (row == LaunchProfile.Row.WIDESCREEN && "global".equals(profile.aspect())) {
            return "Global (" + aspectLabel(configService.getString(SonicConfiguration.DISPLAY_ASPECT)) + ")";
        }
        return store.displayValue(profile, row, entry);
    }

    private static String aspectLabel(String value) {
        return switch (value) {
            case "WIDE_16_10" -> "16:10";
            case "WIDE_16_9" -> "16:9";
            case "ULTRA_21_9" -> "21:9";
            case "SUPER_32_9" -> "32:9";
            default -> "4:3";
        };
    }
}
