package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

class TestS3kRuntimeStateReadGuard {

    @Test
    void zoneEvents_shouldResolvePlayerCharacterThroughSharedRuntimeStateAdapter() throws IOException {
        String file = "src/main/java/com/openggf/game/sonic3k/events/Sonic3kZoneEvents.java";
        String content = Files.readString(Path.of(file));
        List<String> violations = new ArrayList<>();

        if (!content.contains("S3kRuntimeStates.resolvePlayerCharacter(")) {
            violations.add(file + " does not resolve player character through S3kRuntimeStates.resolvePlayerCharacter(...)");
        }
        if (content.contains("getPlayerCharacter()")) {
            violations.add(file + " still calls Sonic3kLevelEventManager.getPlayerCharacter()");
        }

        if (!violations.isEmpty()) {
            fail("S3K zone events should resolve player character through shared runtime state adapters:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }

    @Test
    void aizFallingLog_shouldReadIntroTriggerFromAizRuntimeState() throws IOException {
        String file = "src/main/java/com/openggf/game/sonic3k/objects/AizFallingLogObjectInstance.java";
        String content = Files.readString(Path.of(file));
        List<String> violations = new ArrayList<>();

        if (content.contains("Sonic3kLevelEventManager")) {
            violations.add(file + " still references Sonic3kLevelEventManager directly");
        }
        if (!content.contains("S3kRuntimeStates.currentAiz(")) {
            violations.add(file + " does not read AIZ state through S3kRuntimeStates.currentAiz(...)");
        }

        if (!violations.isEmpty()) {
            fail("AIZ falling log should read intro/runtime state through the shared zone runtime adapter:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }

    /**
     * The {@code Save_Level_Data2} snapshot moved from the ring to the flash:
     * ROM calls it from {@code SSEntryFlash_GoSS} (skdisasm/sonic3k.asm:128392)
     * with {@code a0} on the flash object, not from the ring's touch response.
     * The guarded property is unchanged — whichever object owns the save must
     * read {@code Dynamic_resize_routine} through the typed runtime registry and
     * never through {@code Sonic3kLevelEventManager} — so it is asserted on the
     * pair, with the registry read required at the owning site.
     */
    @Test
    void ssEntryRing_shouldSaveResizeRoutineFromZoneRuntimeState() throws IOException {
        String ringFile = "src/main/java/com/openggf/game/sonic3k/objects/Sonic3kSSEntryRingObjectInstance.java";
        String flashFile = "src/main/java/com/openggf/game/sonic3k/objects/Sonic3kSSEntryFlashObjectInstance.java";
        List<String> violations = new ArrayList<>();

        for (String file : List.of(ringFile, flashFile)) {
            if (Files.readString(Path.of(file)).contains("Sonic3kLevelEventManager")) {
                violations.add(file + " still references Sonic3kLevelEventManager directly");
            }
        }
        String flashContent = Files.readString(Path.of(flashFile));
        if (!flashContent.contains("saveBigRingReturn")) {
            violations.add(flashFile + " no longer models Save_Level_Data2 at SSEntryFlash_GoSS");
        }
        if (!flashContent.contains("services().zoneRuntimeState()")) {
            violations.add(flashFile + " does not read the resize routine from services().zoneRuntimeState()");
        }

        if (!violations.isEmpty()) {
            fail("S3K special-stage entry rings should save resize state through the runtime registry:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }

    @Test
    void iczPaletteCycler_shouldReadIndoorCycleGateFromRuntimeState() throws IOException {
        String file = "src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java";
        String content = Files.readString(Path.of(file));
        List<String> violations = new ArrayList<>();

        if (content.contains("manager.getIczEvents()")) {
            violations.add(file + " still reads ICZ palette gate through Sonic3kLevelEventManager.getIczEvents()");
        }
        if (!content.contains("S3kRuntimeStates.currentIcz(")) {
            violations.add(file + " does not read the ICZ palette gate through S3kRuntimeStates.currentIcz(...)");
        }

        if (!violations.isEmpty()) {
            fail("ICZ palette cycling should consume typed runtime state instead of event-manager internals:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }

    @Test
    void iczPatternAnimator_shouldReadScrollPhasesFromRuntimeState() throws IOException {
        String file = "src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java";
        String content = Files.readString(Path.of(file));
        List<String> violations = new ArrayList<>();

        if (!content.contains("S3kRuntimeStates.currentIcz(")) {
            violations.add(file + " does not read ICZ scroll phases through S3kRuntimeStates.currentIcz(...)");
        }
        if (!content.contains("state.iczBgCameraX(")
                || !content.contains("state.iczEventsBg10(")
                || !content.contains("state.iczAct1BgCameraY(")) {
            violations.add(file + " does not route ICZ phase inputs through IczZoneRuntimeState");
        }

        if (!violations.isEmpty()) {
            fail("ICZ animated tiles should consume typed runtime state for scroll-derived phase inputs:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }
}
