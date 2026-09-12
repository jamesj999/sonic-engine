package com.openggf.game;

import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Local controller path picker; browsing never creates or modifies files. */
public final class MenuPathBrowser {
    private static final int ROWS = 7;
    private Path directory;
    private record Entry(Path path, boolean directory) { }
    private record Listing(Path directory, List<Entry> entries, String error) { }
    private List<Entry> entries = List.of();
    private MenuLoadTask<Listing> load;
    private int row;
    private String error;
    private String selected;
    private boolean cancelled;
    private MenuDetailsScreen details;
    private String acceptHint = "Enter", backHint = "Esc", detailsHint = "F1", directionsHint = "Arrows";

    public MenuPathBrowser(String initial) {
        load = new MenuLoadTask<>(() -> {
            Path candidate = Path.of(initial.isBlank() ? "." : initial).toAbsolutePath().normalize();
            while (candidate != null && !Files.isDirectory(candidate)) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                candidate = candidate.getParent();
            }
            return read(candidate == null ? Path.of(".").toAbsolutePath().normalize() : candidate);
        });
    }
    private static Listing read(Path directory) {
        try (var stream = Files.list(directory)) {
            List<Entry> entries = stream.map(path -> new Entry(path, Files.isDirectory(path)))
                    .sorted(Comparator.comparing(Entry::directory).reversed()
                            .thenComparing(e -> e.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER)).toList();
            return new Listing(directory, entries, null);
        } catch (IOException | SecurityException e) { return new Listing(directory, List.of(), "Cannot read folder: " + e.getMessage()); }
    }
    private void refresh(Path next) {
        row = 0;
        error = null;
        load = new MenuLoadTask<>(() -> read(next));
    }
    private void consumeLoad() {
        if (load == null || !load.done()) return;
        try {
            Listing result = load.result();
            directory = result.directory(); entries = result.entries(); error = result.error();
        } catch (Exception e) { error = "Cannot read path: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()); }
        load = null;
    }
    public boolean loading() { return load != null; }
    public void update(InputHandler input) {
        if (input == null || selected != null || cancelled) return;
        acceptHint = MenuInput.confirmLabel(input); backHint = MenuInput.backLabel(input); detailsHint = MenuInput.detailsLabel(input); directionsHint = MenuInput.directionLabel(input);
        if (details != null) { if (details.update(input)) details = null; return; }
        if (MenuInput.textBack(input)) { if (load != null) { load.cancel(); load = null; } cancelled = true; MenuFeedback.emit(MenuFeedback.Cue.CANCEL); return; }
        consumeLoad();
        if (load != null) return;
        if (MenuInput.textDetails(input)) {
            String full = directory == null ? "" : row < 2 ? directory.toString() : entries.get(row - 2).path().toString();
            details = new MenuDetailsScreen("PATH", error == null ? full : error + "\n" + full); return;
        }
        if (directory == null) return;
        int count = entries.size() + 2;
        if (MenuInput.textUp(input)) { row = Math.floorMod(row - 1, count); MenuFeedback.emit(MenuFeedback.Cue.NAVIGATE); }
        if (MenuInput.textDown(input)) { row = (row + 1) % count; MenuFeedback.emit(MenuFeedback.Cue.NAVIGATE); }
        if (MenuInput.textAccept(input)) {
            if (row == 0) selected = directory.toString();
            else if (row == 1) { if (directory.getParent() != null) { refresh(directory.getParent()); } }
            else {
                Entry entry = entries.get(row - 2);
                if (entry.directory()) refresh(entry.path());
                else selected = entry.path().toString();
            }
            MenuFeedback.emit(MenuFeedback.Cue.CONFIRM);
        }
    }
    public String selected() { return selected; }
    public boolean cancelled() { return cancelled; }
    public void render(PixelFont font, int width) {
        if (font == null) return;
        if (details != null) { details.render(font, width); return; }
        width = Math.max(320, width);
        MenuStyle.page(font, width, "BROWSE PATH", null);
        MenuStyle.text(font, MenuDetailsScreen.readable(directory == null ? "" : directory.toString()), 10, 34, width - 20, .6f, .8f, 1);
        if (load != null || directory == null) {
            MenuStyle.text(font, load != null ? "Loading folder..." : "Cannot open path", 10, 60, width - 20, 1, .8f, .4f);
            MenuStyle.footer(font, width, error == null ? "" : error, backHint + " Back  " + detailsHint + " Details");
            return;
        }
        int first = row / ROWS * ROWS;
        for (int i = first; i < Math.min(entries.size() + 2, first + ROWS); i++) {
            int y = 52 + (i - first) * 19;
            if (i == row) MenuStyle.focusLabel(font, 8, y, width - 16, 18);
            String name = i == 0 ? "Use this folder" : i == 1 ? ".. Parent folder" :
                    (entries.get(i - 2).directory() ? "/ " : "") + entries.get(i - 2).path().getFileName();
            MenuStyle.label(font, MenuDetailsScreen.readable(name), 13, y, width - 26, 1, 1, 1);
        }
        MenuStyle.footer(font, width, detailsHint + " Details; " + (error == null ? "type in editor for a new path" : error),
                acceptHint + " Choose  " + backHint + " Back  " + directionsHint);
    }
}
