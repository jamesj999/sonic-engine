package com.openggf.graphics.shaderlib;

import com.openggf.control.InputHandler;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class DisplayShaderController {
    public static final int NOTIFICATION_FRAMES = 120;

    private DisplayShaderLibrary library;
    private final int nextKey;
    private final int previousKey;
    private final Consumer<String> persistSelection;
    private final Predicate<DisplayShaderPresetRef> activate;
    private final Set<String> failedShaderPaths = new HashSet<>();
    private int currentIndex;
    private String notificationText;
    private int notificationFramesRemaining;

    public DisplayShaderController(DisplayShaderLibrary library,
                                   String savedSelection,
                                   int nextKey,
                                   int previousKey,
                                   Consumer<String> persistSelection,
                                   Predicate<DisplayShaderPresetRef> activate) {
        this.library = Objects.requireNonNull(library, "library");
        this.nextKey = nextKey;
        this.previousKey = previousKey;
        this.persistSelection = Objects.requireNonNull(persistSelection, "persistSelection");
        this.activate = Objects.requireNonNull(activate, "activate");
        this.currentIndex = library.indexOfRelativePath(savedSelection);
    }

    public void update(InputHandler input) {
        if (input != null && nextKey >= 0 && input.isKeyPressed(nextKey)) {
            cycle(1);
            return;
        }
        if (input != null && previousKey >= 0 && input.isKeyPressed(previousKey)) {
            cycle(-1);
            return;
        }

        if (notificationFramesRemaining > 0) {
            notificationFramesRemaining--;
            if (notificationFramesRemaining == 0) {
                notificationText = null;
            }
        }
    }

    public void applySavedSelectionSilently() {
        DisplayShaderPresetRef savedRef = currentRef();
        if (activate.test(savedRef)) {
            return;
        }
        if (savedRef.kind() == DisplayShaderPresetRef.Kind.OFF) {
            return;
        }

        markFailed(savedRef);
        currentIndex = 0;
        activate.test(DisplayShaderPresetRef.OFF);
    }

    public DisplayShaderPresetRef currentRef() {
        return library.at(currentIndex);
    }

    public void replaceLibrary(DisplayShaderLibrary newLibrary, String savedSelection) {
        this.library = Objects.requireNonNull(newLibrary, "newLibrary");
        this.currentIndex = library.indexOfRelativePath(savedSelection);
        this.failedShaderPaths.clear();
    }

    public String notificationText() {
        return notificationFramesRemaining > 0 ? notificationText : null;
    }

    public boolean select(DisplayShaderPresetRef ref) {
        Objects.requireNonNull(ref, "ref");
        int nextIndex = indexOf(ref);
        if (nextIndex < 0) {
            return false;
        }
        return select(nextIndex, library.at(nextIndex));
    }

    private void cycle(int direction) {
        int size = library.size();
        for (int step = 1; step <= size; step++) {
            int candidateIndex = Math.floorMod(currentIndex + direction * step, size);
            DisplayShaderPresetRef candidate = library.at(candidateIndex);
            if (isFailedRealShader(candidate)) {
                continue;
            }
            select(candidateIndex, candidate);
            return;
        }
    }

    private boolean select(int nextIndex, DisplayShaderPresetRef ref) {
        if (activate.test(ref)) {
            currentIndex = nextIndex;
            persistSelection.accept(persistedValue(ref));
            showNotification("Shader: " + ref.shortLabel());
            return true;
        }

        markFailed(ref);
        showNotification("Shader failed: " + ref.shortLabel());
        return false;
    }

    private int indexOf(DisplayShaderPresetRef ref) {
        if (ref.kind() == DisplayShaderPresetRef.Kind.OFF) {
            return 0;
        }
        int index = library.indexOfRelativePath(ref.relativePath());
        return index == 0 ? -1 : index;
    }

    private boolean isFailedRealShader(DisplayShaderPresetRef ref) {
        return ref.kind() != DisplayShaderPresetRef.Kind.OFF
                && failedShaderPaths.contains(ref.relativePath());
    }

    private void markFailed(DisplayShaderPresetRef ref) {
        if (ref.kind() != DisplayShaderPresetRef.Kind.OFF) {
            failedShaderPaths.add(ref.relativePath());
        }
    }

    private String persistedValue(DisplayShaderPresetRef ref) {
        return ref.kind() == DisplayShaderPresetRef.Kind.OFF ? "OFF" : ref.relativePath();
    }

    private void showNotification(String text) {
        notificationText = text;
        notificationFramesRemaining = NOTIFICATION_FRAMES;
    }
}
