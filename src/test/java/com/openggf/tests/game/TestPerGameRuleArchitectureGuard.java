package com.openggf.tests.game;

import com.openggf.game.rules.AirCollisionRules;
import com.openggf.game.rules.CameraRules;
import com.openggf.game.rules.CollisionRules;
import com.openggf.game.rules.DrowningBubbleRules;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.ObjectInteractionRules;
import com.openggf.game.rules.PlayerAnimationRules;
import com.openggf.game.rules.PlayerCapabilityRules;
import com.openggf.game.rules.PlayerLandingRules;
import com.openggf.game.rules.PlayerMovementRules;
import com.openggf.game.rules.PowerUpRules;
import com.openggf.game.rules.RingRules;
import com.openggf.game.rules.SidekickCpuRules;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPerGameRuleArchitectureGuard {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");
    private static final Pattern LEGACY_FEATURE_SET_USAGE =
            Pattern.compile("\\bgetPhysicsFeatureSet\\s*\\(|\\bPhysicsFeatureSet\\b");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*$", Pattern.MULTILINE);
    private static final int MAX_RULE_COMPONENTS = 20;
    private static final List<Class<? extends Record>> RULE_RECORDS = List.of(
            GameRules.class,
            PlayerMovementRules.class,
            PlayerLandingRules.class,
            PlayerCapabilityRules.class,
            CollisionRules.class,
            AirCollisionRules.class,
            PlayerAnimationRules.class,
            CameraRules.class,
            RingRules.class,
            ObjectInteractionRules.class,
            SidekickCpuRules.class,
            PowerUpRules.class,
            DrowningBubbleRules.class
    );

    @Test
    void productionCodeDoesNotUseLegacyPhysicsFeatureSet() throws IOException {
        TreeSet<String> actual = findLegacyFeatureSetUsers();

        assertTrue(actual.isEmpty(), () -> """
                Production code must consume typed GameRules groups directly.
                Remove PhysicsFeatureSet references, getPhysicsFeatureSet() APIs, and legacy rule conversion
                instead of adding a new baseline entry.
                Remaining files:
                """ + String.join(System.lineSeparator(), actual));
        assertTrue(Files.notExists(MAIN_SOURCE_ROOT.resolve("com/openggf/game/PhysicsFeatureSet.java")),
                "PhysicsFeatureSet should be deleted after typed GameRules fully own per-game gates");
    }

    @Test
    void ruleRecordsDoNotExposeLegacyConversionFactories() {
        for (Class<? extends Record> ruleRecord : RULE_RECORDS) {
            assertTrue(Stream.of(ruleRecord.getDeclaredMethods())
                            .noneMatch(method -> method.getName().equals("fromLegacy")),
                    () -> ruleRecord.getSimpleName() + " should expose direct constants, not fromLegacy(...)");
        }
        assertTrue(Stream.of(GameRules.class.getDeclaredMethods())
                        .noneMatch(method -> method.getName().equals("fromLegacy")),
                """
                GameRules should be constructed from direct per-game constants.
                A fromLegacy(...) factory keeps legacy conversion as a parallel rule source.
                """);
    }

    @Test
    void typedRuleRecordsStaySmallEnoughToReview() {
        for (Class<? extends Record> ruleRecord : RULE_RECORDS) {
            RecordComponent[] components = ruleRecord.getRecordComponents();

            assertTrue(components.length <= MAX_RULE_COMPONENTS,
                    () -> ruleRecord.getSimpleName() + " has " + components.length
                            + " components; split it into narrower rule groups before increasing this limit");
        }
    }

    private static TreeSet<String> findLegacyFeatureSetUsers() throws IOException {
        TreeSet<String> result = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCE_ROOT)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(TestPerGameRuleArchitectureGuard::normalize)
                    .filter(TestPerGameRuleArchitectureGuard::containsLegacyFeatureSetUsage)
                    .forEach(result::add);
        }
        return result;
    }

    private static boolean containsLegacyFeatureSetUsage(String normalizedPath) {
        try {
            String source = stripComments(Files.readString(Path.of(normalizedPath)));
            return LEGACY_FEATURE_SET_USAGE.matcher(source).find();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan " + normalizedPath, e);
        }
    }

    private static String stripComments(String source) {
        String withoutBlockComments = BLOCK_COMMENT.matcher(source).replaceAll("");
        return LINE_COMMENT.matcher(withoutBlockComments).replaceAll("");
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
