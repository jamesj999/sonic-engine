package com.openggf.game;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;

import java.util.Objects;

import static org.lwjgl.glfw.GLFW.*;

/** Shared single-line editor: normal physical typing and a complete controller keyboard. */
public final class MenuTextEditor {
    public enum Result { NONE, ACCEPTED, CANCELLED }
    private static final String LOWER = "1234567890qwertyuiopasdfghjkl zxcvbnm.-_/:@?&=+%#~";
    private static final String SYMBOLS = symbols();
    private static final int COLUMNS = 10;
    private static final int CHARACTER_CELLS = 50;
    private static final int CELL_COUNT = 60;
    private final String title;
    private final String original;
    private final String defaultValue;
    private final int maxLength;
    private String value;
    private int caret;
    private int page;
    private int cell = 59;
    private int ticks;
    private Result result = Result.NONE;
    private boolean finished;
    private boolean controller;
    private String error;

    public MenuTextEditor(String title, String initial, int maxLength) {
        this(title, initial, maxLength, null);
    }

    public MenuTextEditor(String title, String initial, int maxLength, String defaultValue) {
        this.title = Objects.requireNonNull(title, "title");
        if (maxLength < 1) throw new IllegalArgumentException("Text length must be positive");
        this.maxLength = maxLength;
        this.original = Objects.requireNonNull(initial, "initial");
        this.defaultValue = defaultValue;
        // Never silently shorten an existing preference just by opening its editor.
        value = original;
        caret = value.length();
    }

    public String value() { return value; }
    public Result consumeResult() {
        Result pending = result;
        result = Result.NONE;
        return pending;
    }

    /** Keep the same value/caret visible when the owner rejects an accepted value. */
    public void reject(String reason) {
        error = Objects.requireNonNull(reason);
        result = Result.NONE;
        finished = false;
    }

    public void update(InputHandler input) {
        if (finished || input == null) return;
        ticks++;
        controller = MenuInput.controller(input);
        if (MenuInput.textBack(input)) { finish(Result.CANCELLED); return; }
        String typed = MenuInput.consumeText(input);
        if (!typed.isEmpty()) insert(typed);
        if (MenuInput.textKeyPressed(input, GLFW_KEY_ENTER)
                || MenuInput.textKeyPressed(input, GLFW_KEY_KP_ENTER)) {
            accept();
            return;
        }
        if (MenuInput.textKeyPressed(input, GLFW_KEY_BACKSPACE)) eraseBefore();
        if (MenuInput.textKeyPressed(input, GLFW_KEY_DELETE)) eraseAfter();
        if (MenuInput.textKeyPressed(input, GLFW_KEY_HOME)) caret = 0;
        if (MenuInput.textKeyPressed(input, GLFW_KEY_END)) caret = value.length();
        // Arrows have conventional text-field meaning on a physical keyboard.
        // Controller directions address the visible key grid instead.
        if (MenuInput.textKeyPressed(input, GLFW_KEY_LEFT)) { moveCaret(-1); return; }
        if (MenuInput.textKeyPressed(input, GLFW_KEY_RIGHT)) { moveCaret(1); return; }
        if (MenuInput.textKeyPressed(input, GLFW_KEY_UP)) return;
        if (MenuInput.textKeyPressed(input, GLFW_KEY_DOWN)) return;
        if (MenuInput.textLeft(input)) cell = Math.floorMod(cell - 1, CELL_COUNT);
        if (MenuInput.textRight(input)) cell = (cell + 1) % CELL_COUNT;
        if (MenuInput.textUp(input)) cell = Math.floorMod(cell - COLUMNS, CELL_COUNT);
        if (MenuInput.textDown(input)) cell = (cell + COLUMNS) % CELL_COUNT;
        if (MenuInput.textAccept(input)) chooseCell();
    }

    private void chooseCell() {
        if (cell < CHARACTER_CELLS) {
            insert(Character.toString(characters().charAt(cell)));
            return;
        }
        switch (cell) {
            case 50 -> page = (page + 1) % 3;
            case 51 -> insert(" ");
            case 52 -> eraseBefore();
            case 53 -> { value = ""; caret = 0; error = null; }
            case 54 -> { value = defaultValue == null ? original : defaultValue; caret = value.length(); error = null; }
            case 55 -> moveCaret(-1);
            case 56 -> moveCaret(1);
            case 57 -> caret = 0;
            case 58 -> caret = value.length();
            case 59 -> accept();
            default -> throw new IllegalStateException("Unknown editor cell");
        }
    }

    private void accept() {
        if (value.codePointCount(0, value.length()) > maxLength) {
            error = "Limit: " + maxLength + " characters";
            return;
        }
        finish(Result.ACCEPTED);
    }
    private void finish(Result next) { result = next; finished = true; }
    private void insert(String text) {
        error = null;
        StringBuilder accepted = new StringBuilder();
        int remaining = Math.max(0, maxLength - value.codePointCount(0, value.length()));
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) continue;
            if (remaining-- <= 0) { error = "Limit: " + maxLength + " characters"; break; }
            accepted.appendCodePoint(codePoint);
        }
        if (!accepted.isEmpty()) {
            value = value.substring(0, caret) + accepted + value.substring(caret);
            caret += accepted.length();
        }
    }
    private void moveCaret(int direction) {
        if (direction < 0 && caret > 0) caret = value.offsetByCodePoints(caret, -1);
        if (direction > 0 && caret < value.length()) caret = value.offsetByCodePoints(caret, 1);
    }
    private void eraseBefore() {
        if (caret <= 0) return;
        int previous = value.offsetByCodePoints(caret, -1);
        value = value.substring(0, previous) + value.substring(caret);
        caret = previous;
        error = null;
    }
    private void eraseAfter() {
        if (caret >= value.length()) return;
        value = value.substring(0, caret) + value.substring(value.offsetByCodePoints(caret, 1));
        error = null;
    }
    private String characters() { return page == 0 ? LOWER : page == 1 ? LOWER.toUpperCase(java.util.Locale.ROOT) : SYMBOLS; }

    public void render(PixelFont font, int width) {
        if (font == null) return;
        width = Math.max(320, width);
        MenuStyle.page(font, width, title, null);
        MenuStyle.panel(font, 8, 37, width - 16, 31);
        int columns = Math.max(1, (width - 32) / 9 - 1);
        int caretColumn = value.codePointCount(0, caret);
        int startColumn = Math.max(0, caretColumn - columns + 1);
        // Compact menu glyphs cover ASCII. One replacement glyph per unsupported
        // code point keeps the visible caret aligned without changing the stored text.
        StringBuilder display = new StringBuilder();
        value.codePoints().forEach(c -> display.append(c >= 32 && c <= 126 ? (char) c : '?'));
        int start = Math.min(startColumn, display.length());
        String visible = display.substring(start, Math.min(display.length(), start + columns));
        MenuStyle.label(font, visible.isEmpty() ? " " : visible, 15, 45, width - 30, 1, 1, 1);
        if (ticks % 60 < 40) MenuStyle.label(font, "_", 15 + (caretColumn - startColumn) * 9, 54, 9, .3f, .9f, 1);
        MenuStyle.text(font, controller ? "D-Pad chooses keys; A enters a key" : "Type normally. Enter accepts; Esc cancels", 9, 73, width - 18, .65f, .75f, .9f);
        int cellWidth = (width - 20) / COLUMNS;
        for (int i = 0; i < CELL_COUNT; i++) {
            int x = 10 + i % COLUMNS * cellWidth;
            int y = 91 + i / COLUMNS * 15;
            if (controller && cell == i) MenuStyle.focus(font, x - 2, y - 3, cellWidth - 1, 14);
            String label = cellLabel(i);
            MenuStyle.label(font, label, x, y, cellWidth - 3, i >= 50 ? 1 : .88f, i >= 50 ? .73f : .92f, i >= 50 ? .25f : 1);
        }
        String detail = error != null ? error : controller ? cellDescription() : "Arrows: cursor   Home/End   Backspace/Delete";
        MenuStyle.text(font, detail, 9, 185, width - 18, error == null ? .65f : 1, error == null ? .75f : .7f, error == null ? .9f : .2f);
        MenuStyle.footer(font, width, value.codePointCount(0, value.length()) + "/" + maxLength + " characters",
                controller ? "A Choose  B Cancel  D-Pad Move" : "Enter Accept  Esc Cancel");
    }

    private String cellLabel(int index) {
        if (index < CHARACTER_CELLS) return characters().charAt(index) == ' ' ? "SPC" : Character.toString(characters().charAt(index));
        return switch (index) {
            case 50 -> page == 0 ? "ABC" : page == 1 ? "SYM" : "abc";
            case 51 -> "SPC";
            case 52 -> "DEL";
            case 53 -> "CLR";
            case 54 -> defaultValue == null ? "RST" : "DEF";
            case 55 -> "<";
            case 56 -> ">";
            case 57 -> "|<";
            case 58 -> ">|";
            case 59 -> "OK";
            default -> "";
        };
    }
    private String cellDescription() {
        return switch (cell) {
            case 50 -> "Switch keyboard: abc / ABC / symbols";
            case 51 -> "Insert a space";
            case 52 -> "Delete the character before the cursor";
            case 53 -> "Clear this field";
            case 54 -> defaultValue == null ? "Restore the value when this editor opened" : "Restore the engine default";
            case 55 -> "Move the cursor left";
            case 56 -> "Move the cursor right";
            case 57 -> "Move to the beginning";
            case 58 -> "Move to the end";
            case 59 -> "Accept this value";
            default -> "Insert " + (characters().charAt(cell) == ' ' ? "a space" : characters().charAt(cell));
        };
    }
    private static String symbols() {
        StringBuilder result = new StringBuilder();
        for (char c = 32; c <= 126; c++) if (!Character.isLetterOrDigit(c)) result.append(c);
        result.append("0123456789");
        while (result.length() < CHARACTER_CELLS) result.append(' ');
        return result.toString();
    }
}
