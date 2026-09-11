package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text architectural invariants that are intentionally not bytecode
 * ArchUnit rules.
 *
 * <p>Source paths resolve against the project root supplied by the surefire
 * system property {@code project.basedir} (with a fallback to the JVM working
 * directory) so the checks work from any IDE or Maven invocation.
 *
 * <p>The checks are substring-based, which means a comment or string literal
 * containing a forbidden identifier will also fail the test. That is intentional:
 * comments referencing the forbidden type are a hazard for future maintainers
 * and should be paraphrased rather than left as a quote.
 */
class TestArchitecturalReviewGuard {

    private static final Path PROJECT_ROOT = resolveProjectRoot();
    private static final Pattern DOCUMENTED_BASELINE_COUNT = Pattern.compile(
            "^- `([^`]+)`: (\\d+)\\s*$", Pattern.MULTILINE);
    private static final Pattern STORED_RULE_BASELINE_COUNT = Pattern.compile(
            "frozen baseline: (\\d+) violations?");
    private static final Pattern DOCUMENTED_CYCLE_CLUSTER = Pattern.compile("`cycle:([a-z0-9_-]+)`");
    private static final Map<String, String> DOCUMENTED_FROZEN_RULE_DESCRIPTIONS = Map.of(
            "low_level_layers_do_not_depend_on_runtime_layers",
            "audio, graphics, and data layers should not depend on runtime gameplay layers",
            "shared_layers_do_not_depend_on_game_specific_packages",
            "shared level and game layers should not depend on game-specific packages",
            "per_game_packages_do_not_cross_depend",
            "per-game packages should not cross-depend");

    private static Path resolveProjectRoot() {
        String basedir = System.getProperty("project.basedir");
        if (basedir != null && !basedir.isEmpty()) {
            return Paths.get(basedir);
        }
        return Paths.get(System.getProperty("user.dir", "."));
    }

    private static String readSource(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath));
    }

    @Test
    void pomDoesNotKeepJUnit4OrVintageOnTestClasspath() throws IOException {
        String pom = readSource("pom.xml");

        assertFalse(pom.contains("<groupId>junit</groupId>"));
        assertFalse(pom.contains("junit-vintage-engine"));
    }

    @Test
    void archUnitFrozenRulesDoNotAutoUpdateDuringNormalRuns() throws IOException {
        String properties = readSource("src/test/resources/archunit.properties");

        assertTrue(properties.contains("freeze.store.default.allowStoreUpdate=false"),
                "Frozen ArchUnit baselines must not auto-update in normal test runs");
    }

    @Test
    void archUnitPublishedBaselineCountsMatchFrozenStore() throws IOException {
        Map<String, Integer> documentedCounts = documentedBaselineCounts();
        Map<String, Integer> frozenCounts = frozenBaselineCounts();

        assertEquals(frozenCounts, documentedCounts,
                "docs/architecture/archunit-exceptions.md published baseline counts must match stored.rules");
    }

    @Test
    void archUnitCycleClusterDocumentationHasMatchingRatchets() throws IOException {
        String docs = readSource("docs/architecture/archunit-exceptions.md");
        String archUnitRules = readSource("src/test/java/com/openggf/tests/TestArchUnitRules.java");
        String storedRules = readSource("src/test/resources/archunit/frozen/stored.rules");
        Set<String> documentedClusters = documentedCycleClusters(docs);

        assertTrue(docs.contains("`package_slices_are_free_of_cycles`: 1 frozen top-level package cycle cluster"),
                "Cycle ratchet docs should publish the current cluster count");
        assertTrue(docs.contains("`cycle:core-runtime`"),
                "Cycle ratchet docs should name the current core runtime cluster");
        assertFalse(archUnitRules.contains(".ignoreDependency(inCoreRuntimeCycleCluster(), inCoreRuntimeCycleCluster())"),
                "Package cycle ratchets must not ignore every dependency inside a documented cycle cluster");
        assertFalse(archUnitRules.contains("inCoreRuntimeCycleCluster()"),
                "Package cycle ratchets should be frozen against concrete current debt, not a broad cluster predicate");
        assertFalse(archUnitRules.contains("CURRENT_TOP_LEVEL_CYCLE_SLICES"),
                "Package cycle ratchets should not use a broad current-slice allowlist");
        assertFalse(archUnitRules.contains("inCurrentTopLevelCycleSlice"),
                "Package cycle ratchets should use named frozen clusters, not a broad current-slice helper");

        for (String cluster : documentedClusters) {
            assertTrue(archUnitRules.contains(cluster) || storedRules.contains(cluster),
                    "Documented package cycle cluster needs a matching ArchUnit ratchet: " + cluster);
        }
    }

    @Test
    void traceReplayQuarantineIsExplicitAndNotGameSpecific() throws IOException {
        String pom = readSource("pom.xml");

        assertTrue(pom.contains("<id>trace-replay</id>"));
        assertTrue(pom.contains("**/tests/trace/**/*.java"),
                "Default Surefire should quarantine trace replay/regression workloads by directory");
        assertTrue(pom.contains("**/tests/trace/**/*TraceReplay.java"));
        assertTrue(pom.contains("**/tests/trace/**/*ReplayBootstrap.java"));
        assertTrue(pom.contains("**/tests/trace/s2/*Regression.java"));
        assertFalse(pom.contains("**/tests/trace/s1/TestS1"),
                "no game-specific trace exclude should be hand-written outside the standard pattern");
        assertFalse(pom.contains("**/trace/s1/TestS1*TraceReplay.java"));
    }

    private static Map<String, Integer> documentedBaselineCounts() throws IOException {
        String docs = readSource("docs/architecture/archunit-exceptions.md");
        Matcher matcher = DOCUMENTED_BASELINE_COUNT.matcher(docs);
        Map<String, Integer> counts = new LinkedHashMap<>();
        while (matcher.find()) {
            String ruleName = matcher.group(1);
            if (DOCUMENTED_FROZEN_RULE_DESCRIPTIONS.containsKey(ruleName)) {
                counts.put(ruleName, Integer.parseInt(matcher.group(2)));
            }
        }
        return counts;
    }

    private static Map<String, Integer> frozenBaselineCounts() throws IOException {
        Properties storedRules = new Properties();
        storedRules.load(new StringReader(readSource("src/test/resources/archunit/frozen/stored.rules")));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, String> documentedRule : DOCUMENTED_FROZEN_RULE_DESCRIPTIONS.entrySet()) {
            String ruleName = documentedRule.getKey();
            String ruleDescription = documentedRule.getValue();
            counts.put(ruleName, storedBaselineCount(storedRules, ruleDescription));
        }
        return counts;
    }

    private static int storedBaselineCount(Properties storedRules, String ruleDescription) {
        for (Object keyObject : storedRules.keySet()) {
            String storedRule = keyObject.toString();
            if (!storedRule.startsWith(ruleDescription)) {
                continue;
            }
            Matcher matcher = STORED_RULE_BASELINE_COUNT.matcher(storedRule);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        throw new AssertionError("No frozen baseline count found for stored rule: " + ruleDescription);
    }

    private static Set<String> documentedCycleClusters(String docs) {
        Matcher matcher = DOCUMENTED_CYCLE_CLUSTER.matcher(docs);
        Set<String> clusters = new HashSet<>();
        while (matcher.find()) {
            clusters.add(matcher.group(1));
        }
        return clusters;
    }
}
