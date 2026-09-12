package com.openggf.game;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;
import java.util.ArrayList;
import java.util.List;

/** Stable, manually scrollable help. Unsupported font glyphs retain their Unicode identity. */
public final class MenuDetailsScreen {
    private static final int VISIBLE_LINES = 14;
    private final String title;
    private final String text;
    private int columns;
    private int first;
    private List<String> lines = List.of();
    private String back = "Esc", directions = "Arrows", horizontal = "Left/Right";

    public MenuDetailsScreen(String title, String text) {
        this.title = title;
        this.text = readable(text);
        wrap(50);
    }

    public boolean update(InputHandler input) {
        if (input == null) return false;
        back = MenuInput.backLabel(input);
        directions = MenuInput.directionLabel(input); horizontal = MenuInput.horizontalLabel(input);
        if (MenuInput.textBack(input)) { MenuFeedback.emit(MenuFeedback.Cue.CANCEL); return true; }
        int previous = first;
        if (MenuInput.textUp(input)) first--;
        if (MenuInput.textDown(input)) first++;
        if (MenuInput.textLeft(input)) first -= VISIBLE_LINES;
        if (MenuInput.textRight(input)) first += VISIBLE_LINES;
        first = Math.clamp(first, 0, Math.max(0, lines.size() - VISIBLE_LINES));
        if (previous != first) MenuFeedback.emit(MenuFeedback.Cue.NAVIGATE);
        return false;
    }

    public void render(PixelFont font, int width) {
        if (font == null) return;
        width = Math.max(320, width);
        wrap(Math.max(1, (width - 24) / 6));
        MenuStyle.page(font, width, title, "DETAILS");
        for (int i = 0; i < VISIBLE_LINES && first + i < lines.size(); i++) {
            MenuStyle.text(font, lines.get(first + i), 12, 44 + i * 11, width - 24, .9f, .94f, 1);
        }
        MenuStyle.footer(font, width, horizontal + " Page  Lines " + (first + 1) + "-" + Math.min(first + VISIBLE_LINES, lines.size()) + "/" + lines.size(),
                directions + " Scroll  " + back + " Back");
    }

    private void wrap(int nextColumns) {
        if (columns == nextColumns) return;
        columns = nextColumns;
        lines = wrapText(text, columns);
        first = Math.min(first, Math.max(0, lines.size() - VISIBLE_LINES));
    }

    static List<String> wrapText(String text, int columns) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            String remaining = paragraph;
            while (remaining.length() > columns) {
                int end = remaining.lastIndexOf(' ', columns);
                if (end <= 0) end = columns;
                result.add(remaining.substring(0, end));
                remaining = remaining.substring(end).stripLeading();
            }
            result.add(remaining);
        }
        return List.copyOf(result);
    }

    /** ASCII font representation only; never use this value for editing or persistence. */
    public static String readable(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(c -> {
            if (c >= 32 && c <= 126 || c == '\n' || c == '\r') result.appendCodePoint(c);
            else result.append("[U+").append(Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT)).append(']');
        });
        return result.toString();
    }
}
