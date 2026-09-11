package com.openggf.graphics.shaderlib;

import com.openggf.control.InputHandler;

import java.util.List;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.*;

public final class DisplayShaderPickerController {
    private static final String DOWNLOAD_HINT_TEXT = "Press F5 to download libretro shader pack";

    private DisplayShaderSelectionModel selectionModel;
    private final int pickerKey;
    private boolean open;
    private String query = "";
    private List<DisplayShaderSelectionModel.SelectionItem> visibleItems;
    private int selectedIndex;

    public DisplayShaderPickerController(DisplayShaderSelectionModel selectionModel, int pickerKey) {
        this.selectionModel = Objects.requireNonNull(selectionModel, "selectionModel");
        this.pickerKey = pickerKey;
        this.visibleItems = selectionModel.filter(query);
    }

    public Action update(InputHandler input, DisplayShaderPresetRef currentRef) {
        if (input == null) {
            return Action.none();
        }
        if (pickerKey >= 0 && input.isKeyPressed(pickerKey)) {
            if (open) {
                close();
            } else {
                open(currentRef);
            }
            return Action.none();
        }
        if (!open) {
            return Action.none();
        }
        if (input.isKeyPressed(GLFW_KEY_ESCAPE)) {
            close();
            return Action.none();
        }
        if (input.isKeyPressed(GLFW_KEY_ENTER)) {
            DisplayShaderSelectionModel.SelectionItem selected = selectedItem();
            if (selected.directory()) {
                if (selected.parentDirectory()) {
                    selectionModel.enterParentFolder();
                } else {
                    selectionModel.enterFolder(selected.folderPath());
                }
                query = "";
                refreshVisibleItems();
                selectedIndex = 0;
                return Action.none();
            }
            DisplayShaderPresetRef selectedRef = selected.ref();
            close();
            return new Action(ActionType.ACTIVATE, selectedRef);
        }
        if (input.isKeyPressed(GLFW_KEY_UP)) {
            moveSelection(-1);
            return Action.none();
        }
        if (input.isKeyPressed(GLFW_KEY_DOWN)) {
            moveSelection(1);
            return Action.none();
        }
        if (input.isKeyPressed(GLFW_KEY_BACKSPACE)) {
            deleteLastQueryCharacter();
            return Action.none();
        }
        if (input.isKeyPressed(GLFW_KEY_F5)) {
            return new Action(ActionType.DOWNLOAD_LIBRETRO_GLSL, null);
        }
        Character typed = typedCharacter(input);
        if (typed != null) {
            query += typed;
            refreshVisibleItems();
        }
        return Action.none();
    }

    public boolean isOpen() {
        return open;
    }

    public String query() {
        return query;
    }

    public String currentFolder() {
        return selectionModel.currentFolder();
    }

    public List<DisplayShaderSelectionModel.SelectionItem> visibleItems() {
        return visibleItems;
    }

    public static String downloadHintText() {
        return DOWNLOAD_HINT_TEXT;
    }

    public DisplayShaderSelectionModel.SelectionItem selectedItem() {
        return visibleItems.get(selectedIndex);
    }

    public void replaceSelectionModel(DisplayShaderSelectionModel newSelectionModel, DisplayShaderPresetRef currentRef) {
        this.selectionModel = Objects.requireNonNull(newSelectionModel, "newSelectionModel");
        this.selectionModel.showFolderFor(currentRef);
        refreshVisibleItems();
        selectedIndex = indexOf(currentRef);
    }

    private void open(DisplayShaderPresetRef currentRef) {
        open = true;
        query = "";
        selectionModel.showFolderFor(currentRef);
        refreshVisibleItems();
        selectedIndex = indexOf(currentRef);
    }

    private void close() {
        open = false;
    }

    private void moveSelection(int delta) {
        if (visibleItems.isEmpty()) {
            selectedIndex = 0;
            return;
        }
        selectedIndex = Math.floorMod(selectedIndex + delta, visibleItems.size());
    }

    private void deleteLastQueryCharacter() {
        if (!query.isEmpty()) {
            query = query.substring(0, query.length() - 1);
            refreshVisibleItems();
        } else if (!selectionModel.currentFolder().isEmpty()) {
            selectionModel.enterParentFolder();
            refreshVisibleItems();
            selectedIndex = 0;
        }
    }

    private void refreshVisibleItems() {
        visibleItems = selectionModel.filter(query);
        if (visibleItems.isEmpty()) {
            selectedIndex = 0;
        } else if (selectedIndex >= visibleItems.size()) {
            selectedIndex = visibleItems.size() - 1;
        }
    }

    private int indexOf(DisplayShaderPresetRef ref) {
        if (ref == null) {
            return 0;
        }
        for (int i = 0; i < visibleItems.size(); i++) {
            DisplayShaderPresetRef rowRef = visibleItems.get(i).ref();
            if (rowRef != null && sameRef(rowRef, ref)) {
                return i;
            }
        }
        return 0;
    }

    private static boolean sameRef(DisplayShaderPresetRef left, DisplayShaderPresetRef right) {
        if (left.kind() == DisplayShaderPresetRef.Kind.OFF || right.kind() == DisplayShaderPresetRef.Kind.OFF) {
            return left.kind() == right.kind();
        }
        return left.relativePath().equalsIgnoreCase(right.relativePath());
    }

    private static Character typedCharacter(InputHandler input) {
        boolean shift = input.isShiftDown();
        for (int key = GLFW_KEY_A; key <= GLFW_KEY_Z; key++) {
            if (input.isKeyPressed(key)) {
                char base = (char) ('a' + key - GLFW_KEY_A);
                return shift ? Character.toUpperCase(base) : base;
            }
        }
        for (int key = GLFW_KEY_0; key <= GLFW_KEY_9; key++) {
            if (input.isKeyPressed(key)) {
                return (char) ('0' + key - GLFW_KEY_0);
            }
        }
        if (input.isKeyPressed(GLFW_KEY_SPACE)) {
            return ' ';
        }
        if (input.isKeyPressed(GLFW_KEY_MINUS)) {
            return shift ? '_' : '-';
        }
        if (input.isKeyPressed(GLFW_KEY_SLASH)) {
            return '/';
        }
        if (input.isKeyPressed(GLFW_KEY_BACKSLASH)) {
            return '\\';
        }
        if (input.isKeyPressed(GLFW_KEY_PERIOD)) {
            return '.';
        }
        return null;
    }

    public enum ActionType {
        NONE,
        ACTIVATE,
        DOWNLOAD_LIBRETRO_GLSL
    }

    public record Action(ActionType type, DisplayShaderPresetRef ref) {
        private static Action none() {
            return new Action(ActionType.NONE, null);
        }
    }
}
