package com.openggf.game.launch;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.ConfigurationPersistence;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.patch.ModuleResolutionService;
import com.openggf.game.patch.ResolutionContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

@com.openggf.game.ModApi
public class LaunchProfileStore {
    private static final Logger LOGGER = Logger.getLogger(LaunchProfileStore.class.getName());

    private final SonicConfigurationService configService;
    private final Map<MasterTitleScreen.GameEntry, List<String>> patchMainCharacters;
    private final Map<MasterTitleScreen.GameEntry, com.openggf.game.PlayableCharacterRegistry>
            patchCharacterRegistries;

    public LaunchProfileStore(SonicConfigurationService configService) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.patchMainCharacters = emptyAvailability();
        this.patchCharacterRegistries = emptyRegistries();
    }

    public LaunchProfileStore(SonicConfigurationService configService,
            ModuleResolutionService resolutionService, ResolutionContext resolutionContext) {
        this.configService = Objects.requireNonNull(configService, "configService");
        Objects.requireNonNull(resolutionService, "resolutionService");
        Objects.requireNonNull(resolutionContext, "resolutionContext");
        AvailabilitySnapshot snapshot = snapshotAvailability(resolutionService, resolutionContext);
        this.patchMainCharacters = snapshot.characters();
        this.patchCharacterRegistries = snapshot.registries();
    }

    public LaunchProfileStore(SonicConfigurationService configService,
            ModuleResolutionService resolutionService,
            ModuleResolutionService.PreparedLaunch preparedLaunch) {
        this.configService = Objects.requireNonNull(configService, "configService");
        Objects.requireNonNull(resolutionService, "resolutionService");
        Objects.requireNonNull(preparedLaunch, "preparedLaunch");
        List<String> gameIds = java.util.Arrays.stream(MasterTitleScreen.GameEntry.values())
                .map(LaunchProfile::gameId).toList();
        Map<String, ModuleResolutionService.MainCharacterAvailability> snapshot =
                resolutionService.snapshotMainCharacterAvailability(preparedLaunch, gameIds);
        EnumMap<MasterTitleScreen.GameEntry, List<String>> characters =
                new EnumMap<>(MasterTitleScreen.GameEntry.class);
        EnumMap<MasterTitleScreen.GameEntry, com.openggf.game.PlayableCharacterRegistry> registries =
                new EnumMap<>(MasterTitleScreen.GameEntry.class);
        for (MasterTitleScreen.GameEntry entry : MasterTitleScreen.GameEntry.values()) {
            ModuleResolutionService.MainCharacterAvailability available = snapshot.get(
                    LaunchProfile.gameId(entry));
            characters.put(entry, available == null ? List.of() : available.characters());
            registries.put(entry, available == null
                    ? com.openggf.game.PlayableCharacterRegistry.empty() : available.registry());
        }
        this.patchMainCharacters = Map.copyOf(characters);
        this.patchCharacterRegistries = Map.copyOf(registries);
    }

    private static AvailabilitySnapshot snapshotAvailability(
            ModuleResolutionService resolutionService, ResolutionContext resolutionContext) {
        AvailabilitySnapshot snapshot;
        int failuresBefore;
        do {
            failuresBefore = resolutionContext.failedOwners().size();
            snapshot = queryAvailability(resolutionService, resolutionContext);
        } while (resolutionContext.failedOwners().size() != failuresBefore);
        return snapshot;
    }

    private static AvailabilitySnapshot queryAvailability(
            ModuleResolutionService resolutionService, ResolutionContext resolutionContext) {
        EnumMap<MasterTitleScreen.GameEntry, List<String>> availability =
                new EnumMap<>(MasterTitleScreen.GameEntry.class);
        EnumMap<MasterTitleScreen.GameEntry, com.openggf.game.PlayableCharacterRegistry> registries =
                new EnumMap<>(MasterTitleScreen.GameEntry.class);
        for (MasterTitleScreen.GameEntry entry : MasterTitleScreen.GameEntry.values()) {
            availability.put(entry, List.copyOf(resolutionService.availableMainCharacters(
                    resolutionContext, LaunchProfile.gameId(entry))));
            registries.put(entry, resolutionService.availableMainCharacterRegistry(
                    resolutionContext, LaunchProfile.gameId(entry)));
        }
        return new AvailabilitySnapshot(Map.copyOf(availability), Map.copyOf(registries));
    }


    public LaunchProfile load(MasterTitleScreen.GameEntry entry) {
        Keys keys = keysFor(entry);
        String crossGameSource = configService.getString(keys.crossGameSource());
        if (LaunchProfile.gameId(entry).equals(crossGameSource)) {
            LOGGER.warning("Invalid launch profile donor for " + entry.gameId + ": " + crossGameSource
                    + "; replacing with off.");
            crossGameSource = "off";
        }
        return new LaunchProfile(
                configService.getBoolean(keys.rewind()),
                crossGameSource,
                configService.getBoolean(keys.debugTools()),
                configService.getString(keys.aspect()),
                configService.getString(keys.mainCharacter()),
                configService.getString(keys.sidekick()))
                .sanitizedFor(entry, patchMainCharacters(entry));
    }

    /**
     * Atomically saves a sanitized profile, then publishes it to configuration readers.
     * @throws UncheckedIOException if persistence fails; live preferences remain unchanged
     */
    public void save(MasterTitleScreen.GameEntry entry, LaunchProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Keys keys = keysFor(entry);
        LaunchProfile sanitized = sanitize(profile, entry);
        // Keep sanitization with the launch-profile owner; publish all six values together.
        Map<SonicConfiguration, Object> values = Map.of(
                keys.rewind(), sanitized.rewind(),
                keys.crossGameSource(), sanitized.crossGameSource(),
                keys.debugTools(), sanitized.debugTools(),
                keys.aspect(), sanitized.aspect(),
                keys.mainCharacter(), sanitized.mainCharacter(),
                keys.sidekick(), sanitized.sidekick());
        try {
            ConfigurationPersistence.apply(configService, values);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to save launch profile for " + entry.gameId, e);
        }
    }

    public LaunchProfile sanitize(LaunchProfile profile, MasterTitleScreen.GameEntry entry) {
        return Objects.requireNonNull(profile, "profile")
                .sanitizedFor(entry, patchMainCharacters(entry));
    }

    public LaunchProfile withNext(LaunchProfile profile, LaunchProfile.Row row,
            MasterTitleScreen.GameEntry entry) {
        return Objects.requireNonNull(profile, "profile")
                .withNext(row, entry, patchMainCharacters(entry));
    }

    public LaunchProfile withPrevious(LaunchProfile profile, LaunchProfile.Row row,
            MasterTitleScreen.GameEntry entry) {
        return Objects.requireNonNull(profile, "profile")
                .withPrevious(row, entry, patchMainCharacters(entry));
    }

    public boolean isNonStandard(LaunchProfile profile, LaunchProfile.Row row,
            MasterTitleScreen.GameEntry entry) {
        return Objects.requireNonNull(profile, "profile")
                .isNonStandard(row, entry, patchMainCharacters(entry));
    }

    public boolean isCharacterPairStandard(LaunchProfile profile,
            MasterTitleScreen.GameEntry entry) {
        return Objects.requireNonNull(profile, "profile")
                .isCharacterPairStandard(entry, patchMainCharacters(entry));
    }

    public String displayValue(LaunchProfile profile, LaunchProfile.Row row,
                               MasterTitleScreen.GameEntry entry) {
        return Objects.requireNonNull(profile, "profile").displayValue(row, entry,
                patchCharacterRegistries.getOrDefault(entry,
                        com.openggf.game.PlayableCharacterRegistry.empty()));
    }

    private List<String> patchMainCharacters(MasterTitleScreen.GameEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return patchMainCharacters.getOrDefault(entry, List.of());
    }

    private static Map<MasterTitleScreen.GameEntry, List<String>> emptyAvailability() {
        EnumMap<MasterTitleScreen.GameEntry, List<String>> availability =
                new EnumMap<>(MasterTitleScreen.GameEntry.class);
        for (MasterTitleScreen.GameEntry entry : MasterTitleScreen.GameEntry.values()) {
            availability.put(entry, List.of());
        }
        return Map.copyOf(availability);
    }

    private static Map<MasterTitleScreen.GameEntry, com.openggf.game.PlayableCharacterRegistry>
    emptyRegistries() {
        EnumMap<MasterTitleScreen.GameEntry, com.openggf.game.PlayableCharacterRegistry> registries =
                new EnumMap<>(MasterTitleScreen.GameEntry.class);
        for (MasterTitleScreen.GameEntry entry : MasterTitleScreen.GameEntry.values()) {
            registries.put(entry, com.openggf.game.PlayableCharacterRegistry.empty());
        }
        return Map.copyOf(registries);
    }

    private record AvailabilitySnapshot(
            Map<MasterTitleScreen.GameEntry, List<String>> characters,
            Map<MasterTitleScreen.GameEntry, com.openggf.game.PlayableCharacterRegistry> registries) {
    }

    private static Keys keysFor(MasterTitleScreen.GameEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return switch (entry) {
            case SONIC_1 -> new Keys(
                    SonicConfiguration.LAUNCH_S1_REWIND,
                    SonicConfiguration.LAUNCH_S1_CROSS_GAME_SOURCE,
                    SonicConfiguration.LAUNCH_S1_DEBUG_TOOLS,
                    SonicConfiguration.LAUNCH_S1_ASPECT,
                    SonicConfiguration.LAUNCH_S1_MAIN_CHARACTER,
                    SonicConfiguration.LAUNCH_S1_SIDEKICK);
            case SONIC_2 -> new Keys(
                    SonicConfiguration.LAUNCH_S2_REWIND,
                    SonicConfiguration.LAUNCH_S2_CROSS_GAME_SOURCE,
                    SonicConfiguration.LAUNCH_S2_DEBUG_TOOLS,
                    SonicConfiguration.LAUNCH_S2_ASPECT,
                    SonicConfiguration.LAUNCH_S2_MAIN_CHARACTER,
                    SonicConfiguration.LAUNCH_S2_SIDEKICK);
            case SONIC_3K -> new Keys(
                    SonicConfiguration.LAUNCH_S3K_REWIND,
                    SonicConfiguration.LAUNCH_S3K_CROSS_GAME_SOURCE,
                    SonicConfiguration.LAUNCH_S3K_DEBUG_TOOLS,
                    SonicConfiguration.LAUNCH_S3K_ASPECT,
                    SonicConfiguration.LAUNCH_S3K_MAIN_CHARACTER,
                    SonicConfiguration.LAUNCH_S3K_SIDEKICK);
        };
    }

    private record Keys(
            SonicConfiguration rewind,
            SonicConfiguration crossGameSource,
            SonicConfiguration debugTools,
            SonicConfiguration aspect,
            SonicConfiguration mainCharacter,
            SonicConfiguration sidekick) {
    }
}
