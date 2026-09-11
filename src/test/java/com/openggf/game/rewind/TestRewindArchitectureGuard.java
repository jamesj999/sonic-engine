package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

class TestRewindArchitectureGuard {
    private static final Path MAIN_ROOT = Path.of("src/main/java");

    private static final List<Path> OBJECT_SOURCE_ROOTS = List.of(
            Path.of("src/main/java/com/openggf/level/objects"),
            Path.of("src/main/java/com/openggf/level/rings"),
            Path.of("src/main/java/com/openggf/game/sonic1"),
            Path.of("src/main/java/com/openggf/game/sonic2"),
            Path.of("src/main/java/com/openggf/game/sonic3k")
    );

    private static final Pattern CAPTURE_OVERRIDE = Pattern.compile(
            "\\bpublic\\s+\\S*PerObjectRewindSnapshot\\s+captureRewindState\\s*\\(");
    private static final Pattern RESTORE_OVERRIDE = Pattern.compile(
            "\\bpublic\\s+void\\s+restoreRewindState\\s*\\(");

    private static final Map<String, Integer> OBJECT_REWIND_OVERRIDE_BASELINE = Map.ofEntries(
            Map.entry("src/main/java/com/openggf/level/objects/AbstractObjectInstance.java#captureRewindState", 2),
            Map.entry("src/main/java/com/openggf/level/objects/AbstractObjectInstance.java#restoreRewindState", 2),
            // Shared badnik base keeps the no-arg compatibility overrides and
            // adds context-aware overloads so default badnik compact sidecars can
            // resolve captured player/object references through the restore table.
            Map.entry("src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java#captureRewindState", 2),
            Map.entry("src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java#restoreRewindState", 2),
            Map.entry("src/main/java/com/openggf/level/objects/AbstractMonitorObjectInstance.java#captureRewindState", 1),
            Map.entry("src/main/java/com/openggf/level/objects/AbstractMonitorObjectInstance.java#restoreRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/ARZPlatformObjectInstance.java#captureRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/ARZPlatformObjectInstance.java#restoreRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/ConveyorObjectInstance.java#captureRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/ConveyorObjectInstance.java#restoreRewindState", 1),
            // Obj70 compresses eight ROM SST cog teeth into one multi-piece
            // object plus slot-pressure children, so rewind must capture the
            // rotating tooth phase/offsets and whether child slots were spawned.
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/CogObjectInstance.java#captureRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/CogObjectInstance.java#restoreRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/BadnikProjectileInstance.java#captureRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/BadnikProjectileInstance.java#restoreRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/BuzzerBadnikInstance.java#captureRewindState", 2),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/BuzzerBadnikInstance.java#restoreRewindState", 2),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/CoconutsBadnikInstance.java#captureRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/CoconutsBadnikInstance.java#restoreRewindState", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/MasherBadnikInstance.java#captureRewindState", 2),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/MasherBadnikInstance.java#restoreRewindState", 2),
            // Obj08 skid dust has transient animation/delete/DPLC-preload state
            // that is not reconstructible from placement data alone.
            Map.entry("src/main/java/com/openggf/level/objects/SkidDustObjectInstance.java#captureRewindState", 1),
            Map.entry("src/main/java/com/openggf/level/objects/SkidDustObjectInstance.java#restoreRewindState", 1)
    );

    private static final Map<String, Integer> OBJECT_REWIND_ANNOTATION_BASELINE = Map.ofEntries(
            Map.entry("src/main/java/com/openggf/level/objects/ShieldObjectInstance.java#@RewindTransient", 3),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/CutsceneKnucklesAiz1Instance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/badniks/StarPointerBadnikInstance.java#@RewindTransient", 1),
            // Structural parent pointers on inner particle/support child classes: the parent
            // reference is object-graph structure rebuilt when the parent re-spawns its
            // children, not rewindable state. Same triage precedent as the entries above.
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/LbzCupElevatorInstance.java#@RewindTransient", 4),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/LbzTubeElevatorInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/IczSnowPileObjectInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/IczTensionPlatformObjectInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/Mhz1CutsceneKnucklesInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/badniks/CluckoidBadnikInstance.java#@RewindTransient", 1),
            // BuggernautBaby's parent pointer is live object-graph structure relinked
            // to the nearest live parent on recreate, not rewindable scalar state.
            // Same structural-parent triage precedent as the entries above.
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/badniks/BuggernautBabyInstance.java#@RewindTransient", 1),
            // Checkpoint/starpost orbit children keep ROM parent pointers as
            // structural live links. Rewind recreates them only when a matching
            // live parent exists, then reapplies captured scalar orbit state.
            Map.entry("src/main/java/com/openggf/game/sonic1/objects/Sonic1LamppostTwirlInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/CheckpointDongleInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/CheckpointStarInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/MTZLongPlatformCogInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/Sonic3kStarPostBonusStarChild.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/Sonic3kStarPostStarChild.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostStubChild.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/MhzEndBossArenaHelperInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/MhzEndBossHitProxyChild.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/MhzEndBossRobotnikHeadChild.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/MhzEndBossSpikeChild.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/MhzEndBossVisualChild.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/MhzEndBossWeatherMachineChild.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/MhzEndBossWeatherVisualChild.java#@RewindTransient", 1),
            // Obj50 body/wing links are live object graph structure. The child
            // pointer is rebuilt from allocation and the wing's parent pointer
            // mirrors the ROM SST parent pointer rather than rewindable state.
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/badniks/AquisBadnikInstance.java#@RewindTransient", 2),
            // 2026-07-02 triage: LBZ rewind-tail coverage (541d471da) and the CNZ2
            // point-pokey prize objects (e870059b6) annotate structural parent/child
            // links and constructor-derived offsets — object-graph structure rebuilt
            // by the owning reconstruction path, not rewindable scalar state. Every
            // annotation carries an inline reason; same precedent as the entries above.
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/BombPrizeObjectInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/RingPrizeObjectInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/CutsceneKnucklesLbz2Instance.java#@RewindTransient", 4),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/Lbz2RobotnikShipInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/LbzKnuxPillarInstance.java#@RewindTransient", 2),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/LbzLoweringGrappleObjectInstance.java#@RewindTransient", 4),
            // Runtime-art queue handles are transient facades rebound from their
            // captured production-submission ordinals after hardware-ledger restore.
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/Lbz1RobotnikEventController.java#@RewindTransient", 3),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/LbzEndBossInstance.java#@RewindTransient", 12),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/bosses/LbzFinalBoss1Instance.java#@RewindTransient", 13),
            // 2026-08-19 triage: three annotation groups landed on develop under the
            // two precedents already recorded above and were never baselined.
            //   - Obj11 bridge parent/child slot links (BridgeObjectInstance,
            //     BridgeSegmentObjectInstance) are object-graph structure relinked by
            //     adoptSegmentForRewind on the segment's recreate path, exactly like the
            //     ARZRotPformsObjectInstance and EggPrisonObjectInstance slot children.
            //   - MgzDrillingRobotnikInstance's KosM queue facade and hardware handles are
            //     transient facades rebound from captured production-submission ordinals,
            //     the same shape as Lbz1RobotnikEventController and LbzEndBossInstance.
            //   - LbzFinalBoss1Instance's 12 -> 13 is TurretSegmentChild's
            //     nestedChildrenInitialized (6c32885cc). Unlike the entries above it is a
            //     state flag rather than a link or a handle: it records that the ROM's
            //     segment init has created its nested children, and it is left transient
            //     so the boss reconstruction path -- which owns that child graph -- re-runs
            //     that creation rather than restoring a flag that says it already happened.
            //     Flagged as the weakest of these four; the LBZ traces the commit advanced
            //     are green, but a reviewer may want to re-triage it on its own terms.
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/BridgeObjectInstance.java#@RewindTransient", 2),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/BridgeSegmentObjectInstance.java#@RewindTransient", 1),
            Map.entry("src/main/java/com/openggf/game/sonic3k/objects/MgzDrillingRobotnikInstance.java#@RewindTransient", 3),
            // S2 trace-parity slot models keep parent/child graph links and
            // constructor-derived child roles outside scalar rewind capture.
            // Focused graph tests cover recreation and relinking.
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/ARZRotPformsObjectInstance.java#@RewindTransient", 5),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/EggPrisonObjectInstance.java#@RewindTransient", 4),
            Map.entry("src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2OOZBossInstance.java#@RewindTransient", 1)
    );

    private static final Set<String> REWIND_REGISTRY_PRODUCTION_ALLOWLIST = Set.of(
            "src/main/java/com/openggf/game/session/GameplayModeContext.java"
    );

    @Test
    void objectRewindOverridesDoNotGrowWithoutExplicitBaselineTriage() throws IOException {
        Map<String, Integer> actual = objectRewindOverrideCounts();

        assertBaselineMatches(
                actual,
                OBJECT_REWIND_OVERRIDE_BASELINE,
                "Object subclasses should prefer generic/schema rewind capture. "
                        + "New per-object capture/restore overrides need explicit triage.");
    }

    @Test
    void objectRewindAnnotationsDoNotGrowWithoutExplicitBaselineTriage() throws IOException {
        Map<String, Integer> actual = objectRewindAnnotationCounts();

        assertBaselineMatches(
                actual,
                OBJECT_REWIND_ANNOTATION_BASELINE,
                "Object packages should prefer central rewind policies/codecs. "
                        + "New @RewindTransient/@RewindDeferred annotations need explicit triage.");
    }

    @Test
    void productionRewindRegistryConstructionStaysGameplayScoped() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources(MAIN_ROOT)) {
            String normalized = normalize(source);
            if (REWIND_REGISTRY_PRODUCTION_ALLOWLIST.contains(normalized)) {
                continue;
            }
            List<String> lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("new RewindRegistry(")) {
                    violations.add(normalized + ":" + (i + 1));
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Production RewindRegistry ownership must stay in approved gameplay lifecycle code:\n"
                    + String.join("\n", violations));
        }
    }

    @Test
    void objectManagerDynamicRewindCodecsDoNotReferenceConcreteGamePackages() throws IOException {
        Path source = Path.of("src/main/java/com/openggf/level/objects/ObjectManager.java");
        List<String> violations = new ArrayList<>();
        List<String> lines = Files.readAllLines(source);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("com.openggf.game.sonic1")
                    || line.contains("com.openggf.game.sonic2")
                    || line.contains("com.openggf.game.sonic3k")) {
                violations.add(normalize(source) + ":" + (i + 1) + ": " + line.trim());
            }
        }

        if (!violations.isEmpty()) {
            fail("ObjectManager is shared object infrastructure; dynamic rewind codec knowledge "
                    + "for concrete games must live behind ObjectRegistry providers:\n"
                    + String.join("\n", violations));
        }
    }

    private static Map<String, Integer> objectRewindOverrideCounts() throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Path source : objectSources()) {
            String text = Files.readString(source);
            addCount(counts, normalize(source) + "#captureRewindState",
                    CAPTURE_OVERRIDE.matcher(text).results().count());
            addCount(counts, normalize(source) + "#restoreRewindState",
                    RESTORE_OVERRIDE.matcher(text).results().count());
        }
        return counts;
    }

    private static Map<String, Integer> objectRewindAnnotationCounts() throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Path source : objectSources()) {
            String text = Files.readString(source);
            addCount(counts, normalize(source) + "#@RewindTransient",
                    countOccurrences(text, "@RewindTransient"));
            addCount(counts, normalize(source) + "#@RewindDeferred",
                    countOccurrences(text, "@RewindDeferred"));
        }
        return counts;
    }

    private static void addCount(Map<String, Integer> counts, String key, long count) {
        if (count > 0) {
            counts.put(key, Math.toIntExact(count));
        }
    }

    private static long countOccurrences(String text, String token) {
        return Pattern.compile(Pattern.quote(token)).matcher(text).results().count();
    }

    private static List<Path> objectSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path root : OBJECT_SOURCE_ROOTS) {
            for (Path source : javaSources(root)) {
                String normalized = normalize(source);
                if (normalized.contains("/objects/")) {
                    sources.add(source);
                }
            }
        }
        sources.sort(Comparator.comparing(TestRewindArchitectureGuard::normalize));
        return sources;
    }

    private static List<Path> javaSources(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(TestRewindArchitectureGuard::normalize))
                    .toList();
        }
    }

    private static void assertBaselineMatches(Map<String, Integer> actual,
                                              Map<String, Integer> baseline,
                                              String message) {
        List<String> unexpected = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            Integer expected = baseline.get(entry.getKey());
            if (expected == null) {
                unexpected.add(entry.getKey() + " = " + entry.getValue());
            } else if (!expected.equals(entry.getValue())) {
                unexpected.add(entry.getKey() + " = " + entry.getValue()
                        + " (baseline " + expected + ")");
            }
        }

        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : baseline.entrySet()) {
            Integer observed = actual.get(entry.getKey());
            if (observed == null) {
                stale.add(entry.getKey() + " baseline " + entry.getValue()
                        + " is no longer present");
            }
        }

        if (!unexpected.isEmpty() || !stale.isEmpty()) {
            List<String> sections = new ArrayList<>();
            if (!unexpected.isEmpty()) {
                sections.add("Unexpected growth:\n" + String.join("\n", unexpected));
            }
            if (!stale.isEmpty()) {
                sections.add("Stale baseline:\n" + String.join("\n", stale));
            }
            fail(message + "\n" + String.join("\n\n", sections));
        }
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
