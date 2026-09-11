package com.openggf.game.launch;

import com.openggf.configuration.WidescreenAspect;
import com.openggf.game.MasterTitleScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record LaunchProfile(
        boolean rewind,
        String crossGameSource,
        boolean debugTools,
        String aspect,
        String mainCharacter,
        String sidekick) {

    private static final String OFF = "off";
    private static final String GLOBAL = "global";
    private static final String SONIC = "sonic";
    private static final String TAILS = "tails";
    private static final String KNUCKLES = "knuckles";
    private static final String NONE = "none";

    private static final String[] ASPECT_VALUES = {
            GLOBAL,
            "NATIVE_4_3",
            "WIDE_16_10",
            "WIDE_16_9",
            "ULTRA_21_9",
            "SUPER_32_9"
    };

    public LaunchProfile {
        crossGameSource = normalizeLower(crossGameSource, OFF);
        aspect = normalizeAspect(aspect);
        mainCharacter = normalizeLower(mainCharacter, SONIC);
        sidekick = normalizeLower(sidekick, NONE);
    }

    public enum Row {
        REWIND,
        CROSS_GAME,
        DEBUG_TOOLS,
        WIDESCREEN,
        MAIN_CHARACTER,
        SIDEKICK
    }

    public static LaunchProfile stockFor(MasterTitleScreen.GameEntry entry) {
        Objects.requireNonNull(entry, "entry");
        String stockSidekick = entry == MasterTitleScreen.GameEntry.SONIC_1 ? NONE : TAILS;
        return new LaunchProfile(false, OFF, false, GLOBAL, SONIC, stockSidekick);
    }

    public int enabledCount(MasterTitleScreen.GameEntry entry) {
        int count = 0;
        for (Row row : Row.values()) {
            if (isVisibleInLaunchPanel(row) && !isStock(row, entry)) {
                count++;
            }
        }
        return count;
    }

    public boolean isVisibleInLaunchPanel(Row row) {
        Objects.requireNonNull(row, "row");
        return !usesS3kDataSelectCharacters() || (row != Row.MAIN_CHARACTER && row != Row.SIDEKICK);
    }

    public boolean usesS3kDataSelectCharacters() {
        return "s3k".equals(crossGameSource);
    }

    public LaunchProfile withNext(Row row, MasterTitleScreen.GameEntry entry) {
        return withValue(row, entry, true);
    }

    public LaunchProfile withPrevious(Row row, MasterTitleScreen.GameEntry entry) {
        return withValue(row, entry, false);
    }

    public LaunchProfile withStock(MasterTitleScreen.GameEntry entry) {
        return stockFor(entry);
    }

    public LaunchProfile sanitizedFor(MasterTitleScreen.GameEntry entry) {
        Objects.requireNonNull(entry, "entry");
        String sanitizedMainCharacter = sanitize(mainCharacter, mainCharacterValues(entry), SONIC);
        String sanitizedSidekick = sanitize(sidekick, sidekickValues(entry), stockFor(entry).sidekick());
        if (mainCharacter.equals(sanitizedMainCharacter) && sidekick.equals(sanitizedSidekick)) {
            return this;
        }
        return new LaunchProfile(rewind, crossGameSource, debugTools, aspect,
                sanitizedMainCharacter, sanitizedSidekick);
    }

    public boolean isStock(Row row, MasterTitleScreen.GameEntry entry) {
        LaunchProfile stock = stockFor(entry);
        return switch (row) {
            case REWIND -> rewind == stock.rewind;
            case CROSS_GAME -> crossGameSource.equals(stock.crossGameSource);
            case DEBUG_TOOLS -> debugTools == stock.debugTools;
            case WIDESCREEN -> aspect.equals(stock.aspect);
            case MAIN_CHARACTER -> mainCharacter.equals(stock.mainCharacter);
            case SIDEKICK -> sidekick.equals(stock.sidekick);
        };
    }

    public boolean isNonStandard(Row row, MasterTitleScreen.GameEntry entry) {
        return switch (row) {
            case REWIND -> rewind;
            case CROSS_GAME -> !OFF.equals(crossGameSource);
            case DEBUG_TOOLS -> debugTools;
            case WIDESCREEN -> !GLOBAL.equals(aspect) && !WidescreenAspect.NATIVE_4_3.name().equals(aspect);
            case MAIN_CHARACTER, SIDEKICK -> !isCharacterPairStandard(entry);
        };
    }

    public boolean isExperimental(Row row) {
        return row == Row.WIDESCREEN && ("ULTRA_21_9".equals(aspect) || "SUPER_32_9".equals(aspect));
    }

    public boolean isCharacterPairStandard(MasterTitleScreen.GameEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return switch (entry) {
            case SONIC_1 -> pair(SONIC, NONE);
            case SONIC_2 -> pair(SONIC, NONE) || pair(SONIC, TAILS) || pair(TAILS, NONE);
            case SONIC_3K -> pair(SONIC, NONE) || pair(SONIC, TAILS)
                    || pair(TAILS, NONE) || pair(KNUCKLES, NONE);
        };
    }

    public String displayValue(Row row, MasterTitleScreen.GameEntry entry) {
        return switch (row) {
            case REWIND -> onOff(rewind);
            case CROSS_GAME -> gameLabel(crossGameSource);
            case DEBUG_TOOLS -> onOff(debugTools);
            case WIDESCREEN -> aspectLabel(aspect);
            case MAIN_CHARACTER -> characterLabel(mainCharacter);
            case SIDEKICK -> characterLabel(sidekick);
        };
    }

    public static String rowLabel(Row row) {
        return switch (row) {
            case REWIND -> "Rewind";
            case CROSS_GAME -> "Cross-game";
            case DEBUG_TOOLS -> "Debug tools";
            case WIDESCREEN -> "Widescreen";
            case MAIN_CHARACTER -> "Main character";
            case SIDEKICK -> "Sidekick";
        };
    }

    public static String gameId(MasterTitleScreen.GameEntry entry) {
        return Objects.requireNonNull(entry, "entry").gameId;
    }

    private LaunchProfile withValue(Row row, MasterTitleScreen.GameEntry entry, boolean forward) {
        return switch (row) {
            case REWIND -> new LaunchProfile(!rewind, crossGameSource, debugTools, aspect, mainCharacter, sidekick);
            case CROSS_GAME -> new LaunchProfile(rewind, cycle(crossGameValues(entry), crossGameSource, forward),
                    debugTools, aspect, mainCharacter, sidekick).sanitizedFor(entry);
            case DEBUG_TOOLS -> new LaunchProfile(rewind, crossGameSource, !debugTools, aspect, mainCharacter, sidekick);
            case WIDESCREEN -> new LaunchProfile(rewind, crossGameSource, debugTools,
                    cycle(List.of(ASPECT_VALUES), aspect, forward), mainCharacter, sidekick);
            case MAIN_CHARACTER -> new LaunchProfile(rewind, crossGameSource, debugTools, aspect,
                    cycle(mainCharacterValues(entry), mainCharacter, forward), sidekick);
            case SIDEKICK -> new LaunchProfile(rewind, crossGameSource, debugTools, aspect, mainCharacter,
                    cycle(sidekickValues(entry), sidekick, forward));
        };
    }

    private static List<String> crossGameValues(MasterTitleScreen.GameEntry entry) {
        Objects.requireNonNull(entry, "entry");
        List<String> values = new ArrayList<>();
        values.add(OFF);
        for (MasterTitleScreen.GameEntry candidate : MasterTitleScreen.GameEntry.values()) {
            if (candidate != entry) {
                values.add(candidate.gameId);
            }
        }
        return values;
    }

    private List<String> mainCharacterValues(MasterTitleScreen.GameEntry entry) {
        return switch (effectiveCharacterDonor(entry)) {
            case "s2" -> List.of(SONIC, TAILS);
            case "s3k" -> List.of(SONIC, TAILS, KNUCKLES);
            default -> List.of(SONIC);
        };
    }

    private List<String> sidekickValues(MasterTitleScreen.GameEntry entry) {
        String donor = effectiveCharacterDonor(entry);
        String stock = stockFor(entry).sidekick();
        List<String> values = new ArrayList<>();
        if (isSidekickAllowedForDonor(stock, donor)) {
            values.add(stock);
        }
        if (!values.contains(NONE)) {
            values.add(NONE);
        }
        for (String candidate : sidekickCandidatesForDonor(donor)) {
            if (!values.contains(candidate)) {
                values.add(candidate);
            }
        }
        return values;
    }

    private String effectiveCharacterDonor(MasterTitleScreen.GameEntry entry) {
        if (OFF.equals(crossGameSource)) {
            return gameId(entry);
        }
        return switch (crossGameSource) {
            case "s1", "s2", "s3k" -> crossGameSource;
            default -> gameId(entry);
        };
    }

    private static List<String> sidekickCandidatesForDonor(String donor) {
        return switch (donor) {
            case "s2" -> List.of(TAILS, SONIC);
            case "s3k" -> List.of(TAILS, SONIC, KNUCKLES);
            default -> List.of(SONIC);
        };
    }

    private static boolean isSidekickAllowedForDonor(String sidekick, String donor) {
        return sidekickCandidatesForDonor(donor).contains(sidekick) || NONE.equals(sidekick);
    }

    private static String sanitize(String value, List<String> values, String fallback) {
        if (values.contains(value)) {
            return value;
        }
        if (values.contains(fallback)) {
            return fallback;
        }
        return values.get(0);
    }

    private static String cycle(List<String> values, String current, boolean forward) {
        int index = values.indexOf(current);
        if (index < 0) {
            return forward ? values.get(0) : values.get(values.size() - 1);
        }
        int next = forward ? index + 1 : index - 1;
        if (next < 0) {
            next = values.size() - 1;
        } else if (next >= values.size()) {
            next = 0;
        }
        return values.get(next);
    }

    private boolean pair(String main, String companion) {
        return mainCharacter.equals(main) && sidekick.equals(companion);
    }

    private static String normalizeLower(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeAspect(String value) {
        if (value == null || value.isBlank()) {
            return GLOBAL;
        }
        String trimmed = value.trim();
        if (GLOBAL.equalsIgnoreCase(trimmed)) {
            return GLOBAL;
        }
        for (WidescreenAspect aspect : WidescreenAspect.values()) {
            if (aspect.name().equals(trimmed)) {
                return aspect.name();
            }
        }
        return GLOBAL;
    }

    private static String onOff(boolean enabled) {
        return enabled ? "On" : "Off";
    }

    private static String gameLabel(String gameId) {
        return switch (gameId) {
            case "s1" -> "Sonic 1";
            case "s2" -> "Sonic 2";
            case "s3k" -> "Sonic 3K";
            default -> "Off";
        };
    }

    private static String aspectLabel(String value) {
        return switch (value) {
            case GLOBAL -> "Global";
            case "NATIVE_4_3" -> "4:3";
            case "WIDE_16_10" -> "16:10";
            case "WIDE_16_9" -> "16:9";
            case "ULTRA_21_9" -> "21:9";
            case "SUPER_32_9" -> "32:9";
            default -> value;
        };
    }

    private static String characterLabel(String value) {
        return switch (value) {
            case SONIC -> "Sonic";
            case TAILS -> "Tails";
            case KNUCKLES -> "Knuckles";
            case NONE -> "None";
            default -> value;
        };
    }
}
