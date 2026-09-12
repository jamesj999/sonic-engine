package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static com.openggf.configuration.SonicConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;

class TestEngineSettingsDraft {
    @TempDir Path directory;

    @Test
    void presentationRevisionChangesOnlyWithDraftOrSavedState() throws IOException {
        EngineSettingsDraft draft = new EngineSettingsDraft(service());
        long initial = draft.revision();
        assertSame(draft.keys(EngineSettingsDraft.Category.INPUT), draft.keys(EngineSettingsDraft.Category.INPUT));
        draft.set(AUDIO_ENABLED, draft.text(AUDIO_ENABLED));
        assertEquals(initial, draft.revision());
        draft.step(AUDIO_ENABLED, 1);
        assertTrue(draft.revision() > initial);
        long edited = draft.revision();
        assertTrue(draft.changed(AUDIO_ENABLED));
        assertTrue(draft.dirty());
        draft.apply();
        assertTrue(draft.revision() > edited);
        assertFalse(draft.dirty());
        assertFalse(draft.changed(AUDIO_ENABLED));
        assertTrue(draft.nonDefault(AUDIO_ENABLED));
    }

    @Test
    void everyPersistedPreferenceHasExactlyOneVisibleCategory() {
        EngineSettingsDraft draft = new EngineSettingsDraft(service());
        Set<SonicConfiguration> reachable = new HashSet<>();
        for (EngineSettingsDraft.Category category : EngineSettingsDraft.Category.values()) {
            assertFalse(draft.keys(category).isEmpty(), category.name());
            for (SonicConfiguration key : draft.keys(category)) assertTrue(reachable.add(key), key.name());
        }
        assertEquals(new HashSet<>(ConfigCatalog.emitOrder()), reachable);
        assertFalse(reachable.contains(SCREEN_WIDTH_PIXELS));
        assertFalse(reachable.contains(SCREEN_HEIGHT_PIXELS));
    }

    @Test
    void cancelDoesNotMutatePreferencesOrCreateAFile() throws IOException {
        SonicConfigurationService config = service();
        boolean before = config.getBoolean(AUDIO_ENABLED);
        Path file = directory.resolve("config.yaml");
        String beforeFile = Files.exists(file) ? Files.readString(file) : null;
        EngineSettingsDraft draft = new EngineSettingsDraft(config);
        draft.set(AUDIO_ENABLED, Boolean.toString(!before));
        assertTrue(draft.dirty());
        assertEquals(before, config.getBoolean(AUDIO_ENABLED));
        assertEquals(beforeFile, Files.exists(file) ? Files.readString(file) : null);
        assertFalse(new EngineSettingsDraft(config).dirty());
    }

    @Test
    void applyingPersistsOnlyChangesAndNonDefaultRemainsAmberAfterSave() throws IOException {
        SonicConfigurationService config = service();
        EngineSettingsDraft draft = new EngineSettingsDraft(config);
        boolean defaultAudio = (Boolean) config.getDefaultValue(AUDIO_ENABLED);
        draft.set(AUDIO_ENABLED, Boolean.toString(!defaultAudio));
        assertTrue(draft.nonDefault(AUDIO_ENABLED));
        draft.apply();
        assertFalse(draft.dirty());
        assertTrue(draft.nonDefault(AUDIO_ENABLED));
        SonicConfigurationService reloaded = service();
        assertEquals(!defaultAudio, reloaded.getBoolean(AUDIO_ENABLED));
        assertFalse(Files.readString(directory.resolve("config.yaml")).contains("shaderLibraryRoot"));
        draft.reset(AUDIO_ENABLED);
        assertFalse(draft.nonDefault(AUDIO_ENABLED));
        assertTrue(draft.dirty());
    }

    @Test
    void sessionAndDerivedOverridesAreNotWrittenBackAsPreferences() throws IOException {
        SonicConfigurationService config = service();
        config.setConfigValue(DISPLAY_ASPECT, "NATIVE_4_3");
        config.setSessionOverride(DISPLAY_ASPECT, "WIDE_16_9");
        config.resolveDisplayAspect();
        EngineSettingsDraft draft = new EngineSettingsDraft(config);
        assertEquals("NATIVE_4_3", draft.text(DISPLAY_ASPECT));
        draft.step(AUDIO_ENABLED, 1);
        draft.apply();
        assertEquals("NATIVE_4_3", service().getString(DISPLAY_ASPECT));
        assertEquals("WIDE_16_9", config.getString(DISPLAY_ASPECT));
        assertFalse(Files.readString(directory.resolve("config.yaml")).contains("widthPixels"));
    }

    @Test
    void saveFailureKeepsLivePreferencesUntouchedAndDraftAvailableForRetry() throws IOException {
        SonicConfigurationService config = service();
        EngineSettingsDraft draft = new EngineSettingsDraft(config);
        boolean before = config.getBoolean(AUDIO_ENABLED);
        draft.set(AUDIO_ENABLED, Boolean.toString(!before));
        Path target = directory.resolve("config.yaml");
        Files.deleteIfExists(target);
        Files.createDirectory(target);
        assertThrows(IOException.class, draft::apply);
        assertEquals(before, config.getBoolean(AUDIO_ENABLED));
        assertTrue(draft.dirty());
        Files.delete(target);
        draft.apply();
        assertFalse(draft.dirty());
        assertEquals(!before, service().getBoolean(AUDIO_ENABLED));
    }

    @Test
    void invalidTypedValuesNeverEnterTheDraftOrLiveConfiguration() {
        SonicConfigurationService config = service();
        EngineSettingsDraft draft = new EngineSettingsDraft(config);
        assertThrows(IllegalArgumentException.class, () -> draft.set(FPS, "0"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(FPS, "2.5"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(CONTROLLER_DEADZONE, "NaN"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(CONTROLLER_DEADZONE, "1"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(TIME_ATTACK_NET_HOST_PORT, "65536"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(DISPLAY_ASPECT, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(P1_A, "not a key"));
        assertThrows(IllegalArgumentException.class, () -> draft.set(SCREEN_WIDTH_PIXELS, "500"));
        assertFalse(draft.dirty());
    }

    @Test
    void crossFieldValidationRunsBeforeSaving() {
        SonicConfigurationService config = service();
        EngineSettingsDraft draft = new EngineSettingsDraft(config);
        draft.set(LIVE_REWIND_TAPE_COAST_MIN_STEPS, "1000");
        draft.set(LIVE_REWIND_TAPE_COAST_MAX_STEPS, "2");
        assertThrows(IllegalArgumentException.class, draft::apply);
        assertNotEquals(1000, config.getDouble(LIVE_REWIND_TAPE_COAST_MIN_STEPS));
        assertTrue(draft.dirty());
    }

    @Test
    void keyNamesAndCodesHaveEquivalentDefaultStatusAndChordsRoundTrip() throws IOException {
        SonicConfigurationService config = service();
        config.setConfigValue(P1_A, config.getInt(P1_A));
        EngineSettingsDraft draft = new EngineSettingsDraft(config);
        assertFalse(draft.nonDefault(P1_A));
        draft.set(P1_A, "CTRL+SHIFT+K");
        draft.apply();
        assertEquals(KeyChord.parse("CTRL+SHIFT+K"), service().getKeyChord(P1_A));
        draft.set(P1_A, "");
        draft.apply();
        assertFalse(service().getKeyChord(P1_A).isBound());
    }

    private SonicConfigurationService service() {
        return SonicConfigurationService.createStandalone(directory);
    }
}
