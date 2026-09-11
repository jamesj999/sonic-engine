package com.openggf.game;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
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
    private static final float TEXT_SCALE = 0.68f;
    private static final int TEXT_SIDE_PADDING = 4;

    public enum Result { NONE, CLOSED }

    record RowView(String label, String value, boolean stock, boolean nonStandard, boolean experimental) {
    }

    record TextLineView(String text, int x, int y, float scale, int measuredWidth,
                        float r, float g, float b, float a) {
    }

    private final MasterTitleScreen.GameEntry entry;
    private final SonicConfigurationService configService;
    private final PixelFont font;
    @SuppressWarnings("unused")
    private final TexturedQuadRenderer renderer;
    private final LaunchProfile.Row[] rows = LaunchProfile.Row.values();

    private LaunchProfile profile;
    private int selectedRow;
    private Result pendingResult = Result.NONE;
    private boolean closed;

    public LaunchConfigPanel(MasterTitleScreen.GameEntry entry,
                             LaunchProfile currentProfile,
                             LaunchProfileStore store,
                             SonicConfigurationService configService,
                             PixelFont font,
                             TexturedQuadRenderer renderer) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.profile = Objects.requireNonNull(currentProfile, "currentProfile").sanitizedFor(entry);
        Objects.requireNonNull(store, "store");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.font = font;
        this.renderer = renderer;
    }

    public void update(InputHandler inputHandler) {
        if (closed || inputHandler == null) {
            return;
        }
        if (inputHandler.isKeyPressed(GLFW_KEY_TAB) || inputHandler.isKeyPressed(GLFW_KEY_ESCAPE)
                || inputHandler.isGamepadBackButtonPressed()) {
            close();
            return;
        }
        if (inputHandler.isKeyPressed(GLFW_KEY_BACKSPACE)) {
            profile = profile.withStock(entry);
            return;
        }
        if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.UP)) || inputHandler.logical().menuUp()) {
            selectedRow = wrapRow(selectedRow - 1, visibleRows().size());
        }
        if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.DOWN)) || inputHandler.logical().menuDown()) {
            selectedRow = wrapRow(selectedRow + 1, visibleRows().size());
        }
        normalizeSelectedRow();
        LaunchProfile.Row row = visibleRows().get(selectedRow);
        if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.LEFT)) || inputHandler.logical().menuLeft()) {
            profile = profile.withPrevious(row, entry);
        }
        if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.RIGHT)) || inputHandler.logical().menuRight()) {
            profile = profile.withNext(row, entry);
        }
        normalizeSelectedRow();
    }

    public Result consumeResult() {
        Result result = pendingResult;
        pendingResult = Result.NONE;
        return result;
    }

    public void render(int viewportWidth) {
        if (font == null) {
            return;
        }
        font.beginMegaBatch();
        try {
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
                    LaunchProfile.rowLabel(row),
                    rowValue(row),
                    profile.isStock(row, entry),
                    profile.isNonStandard(row, entry),
                    profile.isExperimental(row)));
        }
        return List.copyOf(views);
    }

    LaunchProfile.Row selectedRowForTest() {
        normalizeSelectedRow();
        return visibleRows().get(selectedRow);
    }

    LaunchProfile currentProfileForTest() {
        return profile;
    }

    LaunchProfile currentProfile() {
        return profile;
    }

    List<TextLineView> textLineViews(int viewportWidth) {
        List<TextLineView> lines = new ArrayList<>();
        addCenteredLine(lines, entry.displayName + " Launch", viewportWidth, 28,
                1.0f, 1f, 1f, 1f, 1f);
        List<RowView> views = rowViews();
        int y = 58;
        for (int i = 0; i < views.size(); i++) {
            RowView view = views.get(i);
            boolean selected = i == selectedRow;
            String marker = selected ? ">" : " ";
            String suffix = rowSuffix(view);
            float r = rowRed(view, selected);
            float g = rowGreen(view, selected);
            float b = rowBlue(view, selected);
            addCenteredLine(lines, marker + " " + view.label() + ": " + view.value() + suffix,
                    viewportWidth, y, TEXT_SCALE, r, g, b, 1f);
            y += 15;
        }
        addCenteredLine(lines, "! experimental", viewportWidth, SCREEN_H - 48,
                0.55f, 1f, 0.25f, 0.25f, 1f);
        addCenteredLine(lines, "* not possible in the original game", viewportWidth, SCREEN_H - 34,
                0.55f, 1f, 0.72f, 0.25f, 1f);
        addCenteredLine(lines, "Up/Down row  Left/Right value  Backspace stock  Tab/Esc close",
                viewportWidth, SCREEN_H - 20, 0.48f, 0.7f, 0.7f, 0.7f, 1f);
        return List.copyOf(lines);
    }

    private static String rowSuffix(RowView view) {
        if (view.experimental()) {
            return "!";
        }
        if (view.nonStandard()) {
            return "*";
        }
        return view.stock() ? " (stock)" : "";
    }

    private static float rowRed(RowView view, boolean selected) {
        return view.experimental() || view.nonStandard() || selected ? 1f : 0.72f;
    }

    private static float rowGreen(RowView view, boolean selected) {
        if (view.experimental()) {
            return 0.25f;
        }
        return view.nonStandard() ? 0.72f : (selected ? 1f : 0.72f);
    }

    private static float rowBlue(RowView view, boolean selected) {
        if (view.experimental()) {
            return 0.25f;
        }
        return view.nonStandard() ? 0.25f : (selected ? 1f : 0.72f);
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
        scale = fittedScale(text, scale, viewportWidth);
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
        return Math.round(text.length() * 6 * scale);
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
        int rowCount = visibleRows().size();
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
        return profile.displayValue(row, entry);
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
