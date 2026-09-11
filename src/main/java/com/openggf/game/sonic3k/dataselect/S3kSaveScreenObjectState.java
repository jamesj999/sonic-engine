package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.dataselect.HostSlotPreview;
import com.openggf.level.render.SpriteMappingFrame;

import java.util.List;
import java.util.Objects;

/**
 * Runtime object state for the native S3K save screen.
 */
public record S3kSaveScreenObjectState(
        S3kSaveScreenLayoutObjects layoutObjects,
        S3kSaveScreenSelectorState selectorState,
        VisualState visualState,
        SelectedSlotIcon selectedSlotIcon,
        int deleteWorldX,
        String launchErrorMessage) {

    public S3kSaveScreenObjectState(S3kSaveScreenLayoutObjects layoutObjects,
                                    S3kSaveScreenSelectorState selectorState,
                                    VisualState visualState) {
        this(layoutObjects, selectorState, visualState, null, layoutObjects.deleteIcon().worldX(), null);
    }

    public S3kSaveScreenObjectState(S3kSaveScreenLayoutObjects layoutObjects,
                                    S3kSaveScreenSelectorState selectorState,
                                    VisualState visualState,
                                    SelectedSlotIcon selectedSlotIcon) {
        this(layoutObjects, selectorState, visualState, selectedSlotIcon, layoutObjects.deleteIcon().worldX(), null);
    }

    public S3kSaveScreenObjectState(S3kSaveScreenLayoutObjects layoutObjects,
                                    S3kSaveScreenSelectorState selectorState,
                                    VisualState visualState,
                                    SelectedSlotIcon selectedSlotIcon,
                                    int deleteWorldX) {
        this(layoutObjects, selectorState, visualState, selectedSlotIcon, deleteWorldX, null);
    }

    public S3kSaveScreenObjectState {
        layoutObjects = Objects.requireNonNull(layoutObjects, "layoutObjects");
        selectorState = Objects.requireNonNull(selectorState, "selectorState");
        visualState = Objects.requireNonNull(visualState, "visualState");
        if (visualState.slotStates().size() != layoutObjects.slots().size()) {
            throw new IllegalArgumentException("Expected " + layoutObjects.slots().size()
                    + " slot visual states, got " + visualState.slotStates().size());
        }
        for (int i = 0; i < visualState.slotStates().size(); i++) {
            SlotVisualState slotState = visualState.slotStates().get(i);
            if (slotState == null) {
                throw new NullPointerException("slotStates[" + i + "]");
            }
            if (slotState.slotIndex() != i) {
                throw new IllegalArgumentException("Expected slot visual state index " + i
                        + " but got " + slotState.slotIndex());
            }
        }
    }

    public enum SlotVisualKind {
        EMPTY,
        OCCUPIED,
        CLEAR
    }

    public enum SlotLabelKind {
        BLANK,
        ZONE,
        CLEAR
    }

    public record SlotVisualState(int slotIndex,
                                  SlotVisualKind kind,
                                  int objectMappingFrame,
                                  SpriteMappingFrame customObjectFrame,
                                  int sub2MappingFrame,
                                  SlotLabelKind labelKind,
                                  int zoneDisplayNumber,
                                  int headerStyleIndex,
                                  int lives,
                                  int continuesCount,
                                  List<Integer> emeraldMappingFrames,
                                  HostSlotPreview hostPreview) {
        public SlotVisualState {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(labelKind, "labelKind");
            emeraldMappingFrames = List.copyOf(Objects.requireNonNull(emeraldMappingFrames, "emeraldMappingFrames"));
        }

        public SlotVisualState(int slotIndex, SlotVisualKind kind) {
            this(slotIndex, kind, 4, null, -1, SlotLabelKind.BLANK, 0, 0, 0, 0, List.of(), null);
        }

        public SlotVisualState(int slotIndex,
                               SlotVisualKind kind,
                               int objectMappingFrame,
                               int sub2MappingFrame,
                               SlotLabelKind labelKind,
                               int zoneDisplayNumber,
                               int headerStyleIndex,
                               int lives,
                               int continuesCount,
                               List<Integer> emeraldMappingFrames) {
            this(slotIndex, kind, objectMappingFrame, null, sub2MappingFrame, labelKind, zoneDisplayNumber,
                    headerStyleIndex, lives, continuesCount, emeraldMappingFrames, null);
        }
    }

    public record VisualState(int noSaveMappingFrame,
                              SpriteMappingFrame noSaveCustomFrame,
                              int noSaveChildMappingFrame,
                              int deleteMappingFrame,
                              int deleteChildMappingFrame,
                              int activeHeaderAnimationFrame,
                              List<SlotVisualState> slotStates) {
        public VisualState {
            slotStates = List.copyOf(Objects.requireNonNull(slotStates, "slotStates"));
        }

        public VisualState(int noSaveMappingFrame, int deleteMappingFrame, List<SlotVisualState> slotStates) {
            this(noSaveMappingFrame, null, 0xF, deleteMappingFrame, 8, 0, slotStates);
        }

        public VisualState(int noSaveMappingFrame,
                           int noSaveChildMappingFrame,
                           int deleteMappingFrame,
                           int deleteChildMappingFrame,
                           int activeHeaderAnimationFrame,
                           List<SlotVisualState> slotStates) {
            this(noSaveMappingFrame, null, noSaveChildMappingFrame, deleteMappingFrame, deleteChildMappingFrame,
                    activeHeaderAnimationFrame, slotStates);
        }
    }

    public record SelectedSlotIcon(int slotIndex,
                                   int worldX,
                                   int worldY,
                                   int iconIndex,
                                   boolean finishCard,
                                   int paletteIndex,
                                   int mappingFrame) {
    }
}
