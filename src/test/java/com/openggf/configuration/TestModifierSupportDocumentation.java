package com.openggf.configuration;

import com.openggf.debug.DebugOverlayToggle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;

/**
 * CONFIGURATION.md's <em>Modifier support per binding</em> table tells a player
 * which bindings act on a chord, and its third state -- "chord permanently
 * dead" -- is invisible from the config file, so the table is the only place it
 * is written down. That makes a stale row a real defect: a player reads the row,
 * believes a shortcut is inert once it carries a modifier, and the bare key
 * keeps firing.
 *
 * <p>The row is therefore checked against the call sites rather than trusted.
 */
// Minutes-long oracle sweep: excluded from the -Psmoke fast lane, still run by
// the default suite on pull requests, the nightly schedule and release validation.
@Tag("slow-suite")
class TestModifierSupportDocumentation {

    private static final Path CONFIGURATION_DOC = Path.of("CONFIGURATION.md");
    private static final Path BUNDLED_CONFIG = Path.of("src/main/resources/config.yaml");
    private static final Path SRC_MAIN = Path.of("src/main/java");
    private static final Pattern BINDING_NAME = Pattern.compile("`([A-Z][A-Z0-9_]+)`");
    /** The "or a bare key such as X" example, in either surface's punctuation. */
    private static final Pattern BARE_KEY_EXAMPLE =
            Pattern.compile("bare key such as `?([A-Za-z0-9_]+)`?");

    @TempDir
    Path configDir;

    /**
     * Every binding the table calls permanently dead must be read only through
     * the "no modifier held" check. A single plain {@code isKeyPressed} read
     * elsewhere makes the binding dual-state instead, which the table describes
     * separately.
     */
    @Test
    void everyBindingDocumentedAsChordDeadIsOnlyReadThroughTheUnmodifiedCheck() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String name : deadChordBindings()) {
            List<String> reads = readSitesOf(name);
            if (reads.isEmpty()) {
                violations.add(name + " is documented as chord-dead but is never read in src/main"
                        + "; the table names a binding that no longer exists as a shortcut");
                continue;
            }
            for (String read : reads) {
                if (!read.contains("isUnmodifiedDebugKeyPressed(")
                        && !read.contains("isKeyPressedWithoutModifiers(")) {
                    violations.add(name + " is documented as chord-dead but is named outside an "
                            + "unmodified check (a hoisted lookup needs the row re-verified by "
                            + "hand): " + read.trim());
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("CONFIGURATION.md's 'Chord permanently dead' row disagrees with the call sites:\n  "
                    + String.join("\n  ", violations));
        }
    }

    /**
     * The other direction, and the one that matters more. The check above walks
     * from the row outwards, so it can only catch the row naming something that
     * moved; it cannot catch the row being *incomplete*, which is the failure
     * that actually shipped -- CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY was
     * read through the unmodified check and named nowhere in the table, so the
     * catch-all "Modifiers ignored" row claimed a modifier held for an unrelated
     * reason was harmless to it when it silently killed the shortcut.
     */
    @Test
    void everyBindingReadThroughAnUnmodifiedCheckIsDocumentedAsSuch() throws IOException {
        Set<String> documented = new LinkedHashSet<>(deadChordBindings());
        documented.addAll(dualStateBindings());

        List<String> violations = new ArrayList<>();
        for (SonicConfiguration binding : SonicConfiguration.values()) {
            if (documented.contains(binding.name())) {
                continue;
            }
            for (String read : readSitesOf(binding.name())) {
                if (read.contains("isUnmodifiedDebugKeyPressed(")
                        || read.contains("isKeyPressedWithoutModifiers(")) {
                    violations.add(binding.name() + " is read through a no-modifier-held check but "
                            + "appears in neither the 'Chord permanently dead' row nor the "
                            + "dual-state paragraph: " + read);
                    break;
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("CONFIGURATION.md's 'Modifier support per binding' table is missing a binding "
                    + "whose chord is dead:\n  " + String.join("\n  ", violations));
        }
    }

    /**
     * The sweep above scans statements, and a hoisted read puts the binding name
     * and the check that reads it in different statements:
     * {@code int leftKey = configService.getInt(SonicConfiguration.LEFT);} at the
     * top of GameLoop.updateSpecialStageInput, then
     * {@code isUnmodifiedDebugKeyPressed(leftKey)} several statements down. The
     * statement returned for the reference matches neither marker, so a binding
     * written that way was classified by the catch-all "Modifiers ignored" row
     * and the sweep said nothing -- the exact shape of the
     * CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY miss it was written to catch,
     * and invisible because the five bindings that hoist today are all excused by
     * the dual-state paragraph. This pins the blind spot closed: the guard's own
     * reach is asserted, not assumed.
     */
    @Test
    void theSweepSeesAnUnmodifiedCheckOnAHoistedRead() throws IOException {
        // The hoisted reads only. LEFT is ALSO read inline inside the check at
        // GameLoop:1138, and asserting over every read site would pass on that
        // one whether or not the sweep can follow a local at all.
        List<String> hoisted = readSitesOf("LEFT").stream()
                .filter(read -> read.contains("GameLoop.java") && read.contains("int leftKey ="))
                .toList();

        assertFalse(hoisted.isEmpty(),
                "GameLoop no longer hoists LEFT into a local; re-point this guard at a binding "
                        + "that is still read that way, or the sweep's reach goes unasserted");
        assertTrue(hoisted.stream().anyMatch(read -> read.contains("isUnmodifiedDebugKeyPressed(")),
                "LEFT is hoisted into a local in updateSpecialStageInput and read through an "
                        + "unmodified check further down, so the sweep must follow the local. "
                        + "While it cannot, a newly-hoisted binding is silently undocumented: "
                        + hoisted);
    }

    @Test
    void mixedInlineCallsDoNotShareModifierClassification() throws IOException {
        Path source = configDir.resolve("MixedInput.java");
        Files.writeString(source, """
                class MixedInput {
                    boolean read(Input input, Config config) {
                        return consume(input.isKeyPressedWithoutModifiers(
                                config.getInt(SonicConfiguration.PLAYBACK_TOGGLE_KEY))
                                || input.isKeyPressed(
                                config.getInt(SonicConfiguration.REWIND_KEY)));
                    }
                }
                """);

        String noModifiers =
                readSitesOf("PLAYBACK_TOGGLE_KEY", List.of(source)).getFirst();
        String plain = readSitesOf("REWIND_KEY", List.of(source)).getFirst();

        assertTrue(noModifiers.contains("isKeyPressedWithoutModifiers("));
        assertFalse(plain.contains("isKeyPressedWithoutModifiers("));
        assertTrue(plain.contains("isKeyPressed("));
    }

    /**
     * DEBUG_MODE_KEY is the one binding in both states at once: GameLoop reads
     * it through the unmodified check, while SpriteManager (via the debugModeKey
     * field) and LiveRewindInputSource read it with a plain isKeyPressed. The
     * table's dual-state paragraph must say so, or the modifiers it silently
     * drops read as a documented "dead chord".
     */
    @Test
    void theDualStateParagraphNamesDebugModeKey() throws IOException {
        String doc = Files.readString(CONFIGURATION_DOC);
        int paragraph = doc.indexOf("are in **two** states at once");
        assertTrue(paragraph >= 0, "the dual-state paragraph was reworded; re-point this guard");
        String sentence = doc.substring(Math.max(0, paragraph - 400), paragraph);

        assertTrue(sentence.contains("DEBUG_MODE_KEY"),
                "DEBUG_MODE_KEY has both an unmodified read and a plain isKeyPressed read, so the "
                        + "dual-state paragraph must name it");
        assertFalse(deadChordBindings().contains("DEBUG_MODE_KEY"),
                "DEBUG_MODE_KEY cannot also be listed as permanently dead");
    }

    /**
     * The "chord permanently dead" row used to say a chorded value "resolves to
     * a live key code and still never fires". Only the first half is true: the
     * KEY branch of resolveInt keeps the chord's BARE key, so
     * {@code debug.playback.toggleKey: "CTRL+P"} is read as plain {@code P} and
     * an unmodified {@code P} does fire the shortcut. A player who trusted the
     * old wording believed the binding was inert while it was live on a key they
     * never chose. Doc and behaviour are pinned together here.
     */
    @Test
    void aChordOnAChordDeadBindingBindsItsBareKey() throws IOException {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(configDir);
        config.setConfigValue(SonicConfiguration.PLAYBACK_TOGGLE_KEY, "CTRL+P");

        assertEquals(GLFW_KEY_P, config.getInt(SonicConfiguration.PLAYBACK_TOGGLE_KEY),
                "a chord on a chord-unaware binding keeps its bare key");
        assertEquals(KeyChord.of(GLFW_KEY_P, KeyChord.Modifier.CTRL),
                config.getKeyChord(SonicConfiguration.PLAYBACK_TOGGLE_KEY));

        String row = deadChordRow();
        assertTrue(row.contains("binds plain `P`"),
                "the row must say the chord binds its bare key, not that it is inert: " + row);
        assertFalse(row.contains("still never fires"),
                "the old wording claimed the binding is inert; it is not: " + row);
    }

    /**
     * {@code capture.toggleKey} is the one chord-aware binding, so both shipped
     * surfaces show a bare-key example for "no modifier". That example must not
     * be a key {@link DebugOverlayToggle} already claims: those toggles are
     * hardcoded and PERFORMANCE (P) is dispatched above the debug-shortcuts
     * gate, so recommending a bare {@code P} started a recording AND flipped the
     * performance overlay on for its whole duration.
     */
    @Test
    void theRecommendedBareKeyExampleIsNotAHardcodedOverlayToggle() throws IOException {
        for (Path surface : List.of(CONFIGURATION_DOC, BUNDLED_CONFIG)) {
            Matcher matcher = BARE_KEY_EXAMPLE.matcher(Files.readString(surface));
            assertTrue(matcher.find(),
                    surface + " no longer names a bare-key example; re-point this guard");
            do {
                String name = matcher.group(1);
                OptionalInt keyCode = GlfwKeyNameResolver.resolve(name);
                assertTrue(keyCode.isPresent(),
                        surface + " recommends '" + name + "', which resolves to no key");
                for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
                    assertNotEquals(toggle.keyCode(), keyCode.getAsInt(),
                            surface + " recommends '" + name + "', which is the hardcoded "
                                    + toggle.label() + " overlay toggle");
                }
            } while (matcher.find());
        }
    }

    /** The whole "Chord permanently dead" table row. */
    private static String deadChordRow() throws IOException {
        for (String line : Files.readAllLines(CONFIGURATION_DOC)) {
            if (line.startsWith("| Chord permanently dead")) {
                return line;
            }
        }
        return fail("CONFIGURATION.md no longer has a 'Chord permanently dead' row");
    }

    /** The names in the "Chord permanently dead" cell, with the PLAYBACK glob expanded. */
    private static Set<String> deadChordBindings() throws IOException {
        String row = deadChordRow();
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = BINDING_NAME.matcher(row);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        if (row.contains("`PLAYBACK_*` keys")) {
            names.remove("PLAYBACK_");
            List<String> playbackKeys = Stream.of(SonicConfiguration.values())
                    .map(Enum::name)
                    .filter(name -> name.startsWith("PLAYBACK_") && name.endsWith("_KEY"))
                    .toList();
            assertEquals(9, playbackKeys.size(),
                    "the row says nine PLAYBACK_* keys; the enum now has " + playbackKeys.size());
            names.addAll(playbackKeys);
        }
        // `"CTRL+P"` and the like appear in the cell's prose, not as binding names.
        names.removeIf(name -> Stream.of(SonicConfiguration.values())
                .noneMatch(key -> key.name().equals(name)));
        return names;
    }

    /**
     * The names in the paragraph that follows the table, which covers the
     * bindings that are chord-dead on one path and chord-unaware on another.
     */
    private static Set<String> dualStateBindings() throws IOException {
        String doc = Files.readString(CONFIGURATION_DOC);
        int paragraph = doc.indexOf("are in **two** states at once");
        assertTrue(paragraph >= 0, "the dual-state paragraph was reworded; re-point this guard");
        int start = doc.lastIndexOf("\n\n", paragraph);
        int end = doc.indexOf("\n\n", paragraph);
        String text = doc.substring(Math.max(0, start), end < 0 ? doc.length() : end);

        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = BINDING_NAME.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        names.removeIf(name -> Stream.of(SonicConfiguration.values())
                .noneMatch(key -> key.name().equals(name)));
        return names;
    }

    /**
     * Every statement in src/main that names the binding, outside the config
     * plumbing. Statements, not lines: a call wrapped across two lines puts the
     * binding name on one line and the check that reads it on another, and a
     * per-line match would call that an unguarded read (or, in the reverse
     * sweep, miss it entirely).
     *
     * <p>A read hoisted into a local splits them the same way and a statement is
     * no longer enough either -- {@code int leftKey = configService.getInt(...)}
     * names the binding, and the {@code isUnmodifiedDebugKeyPressed(leftKey)}
     * that reads it is several statements down. So the unmodified checks on that
     * local are looked up in the rest of the enclosing block and appended to the
     * read. Hoisting is the dominant style in GameLoop.updateSpecialStageInput,
     * which is precisely the method these sweeps exist to see into.
     */
    private static List<String> readSitesOf(String name) throws IOException {
        return readSitesOf(name, mainSources());
    }

    private static List<String> readSitesOf(String name, List<Path> sources) throws IOException {
        Pattern reference = Pattern.compile("SonicConfiguration\\." + name + "(?![A-Z0-9_])");
        List<String> reads = new ArrayList<>();
        for (Path file : sources) {
            String flattened = flatten(Files.readString(file));
            Matcher matcher = reference.matcher(flattened);
            while (matcher.find()) {
                String statement = statementAround(flattened, matcher.start());
                String site = HOISTED_LOCAL.matcher(statement).find()
                        ? statement
                        : callAround(flattened, matcher.start(), statement);
                reads.add(file + ": " + site
                        + checksOnHoistedLocal(flattened, matcher.start(), statement));
            }
        }
        return reads;
    }

    /** {@code int leftKey =} — the local a hoisted binding read is assigned to. */
    private static final Pattern HOISTED_LOCAL =
            Pattern.compile("^(?:final )?int ([A-Za-z_$][A-Za-z0-9_$]*) ?=");

    /** The two spellings of the "no modifier held" check. */
    private static final List<String> UNMODIFIED_CHECKS =
            List.of("isUnmodifiedDebugKeyPressed(", "isKeyPressedWithoutModifiers(");

    /**
     * The unmodified checks applied to the local a hoisted read assigns to,
     * rendered so both sweeps match on them exactly as they would an inline
     * call. Empty when the read is not a hoisted assignment, which leaves every
     * other read site reading precisely as it did.
     */
    private static String checksOnHoistedLocal(String flattened, int index, String statement) {
        Matcher local = HOISTED_LOCAL.matcher(statement);
        if (!local.find()) {
            return "";
        }
        String block = restOfEnclosingBlock(flattened, index);
        StringBuilder checks = new StringBuilder();
        for (String check : UNMODIFIED_CHECKS) {
            String call = check + local.group(1) + ")";
            if (block.contains(call)) {
                checks.append(" -> ").append(call);
            }
        }
        return checks.toString();
    }

    /**
     * From {@code index} to the closing brace of the block enclosing it — the
     * method body, for a local declared at the top of one.
     */
    private static String restOfEnclosingBlock(String flattened, int index) {
        int depth = 0;
        for (int i = index; i < flattened.length(); i++) {
            char character = flattened.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                if (depth == 0) {
                    return flattened.substring(index, i);
                }
                depth--;
            }
        }
        return flattened.substring(index);
    }

    /** Source files that can hold a shortcut read, in a stable order. */
    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> sources = Files.walk(SRC_MAIN)) {
            return sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().replace('\\', '/')
                            .contains("com/openggf/configuration/"))
                    .sorted()
                    .toList();
        }
    }

    /** Collapses every whitespace run, newlines included, to a single space. */
    private static String flatten(String source) {
        return source.replaceAll("\\s+", " ");
    }

    /**
     * The immediate call outside the configuration getter enclosing {@code index},
     * or the nearest call/whole statement when that nesting is absent.
     */
    private static String callAround(String flattened, int index, String fallback) {
        int nearestClose = -1;
        int nearestNameStart = -1;
        boolean nearestIsConfigurationGetter = false;
        for (int open = index; open >= 0; open--) {
            char current = flattened.charAt(open);
            if (";{}".indexOf(current) >= 0) {
                break;
            }
            if (current != '(') {
                continue;
            }
            int close = matchingClose(flattened, open);
            if (close < index) {
                continue;
            }
            int nameStart = open;
            while (nameStart > 0
                    && (Character.isJavaIdentifierPart(flattened.charAt(nameStart - 1))
                    || flattened.charAt(nameStart - 1) == '.')) {
                nameStart--;
            }
            if (nameStart == open) {
                continue;
            }
            String callName = flattened.substring(nameStart, open);
            if (nearestNameStart < 0) {
                nearestClose = close;
                nearestNameStart = nameStart;
                nearestIsConfigurationGetter = callName.endsWith(".getInt");
                if (!nearestIsConfigurationGetter) {
                    break;
                }
            } else if (nearestIsConfigurationGetter) {
                return flattened.substring(nameStart, close + 1).trim();
            }
        }
        return nearestNameStart < 0
                ? fallback
                : flattened.substring(nearestNameStart, nearestClose + 1).trim();
    }

    private static int matchingClose(String flattened, int open) {
        int depth = 0;
        for (int cursor = open; cursor < flattened.length(); cursor++) {
            char current = flattened.charAt(cursor);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return cursor;
                }
            }
        }
        return -1;
    }

    /** The enclosing statement: everything between the surrounding {@code ; { }}. */
    private static String statementAround(String flattened, int index) {
        int start = index;
        while (start > 0 && ";{}".indexOf(flattened.charAt(start - 1)) < 0) {
            start--;
        }
        int end = index;
        while (end < flattened.length() && ";{}".indexOf(flattened.charAt(end)) < 0) {
            end++;
        }
        return flattened.substring(start, Math.min(end + 1, flattened.length())).trim();
    }
}
