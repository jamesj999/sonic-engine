package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the audited PLC producer boundary executable.  Surefire treats a
 * missing class in a comma-separated {@code -Dtest} selector as non-fatal when
 * another selector matches, so this guard deliberately loads both required
 * suites and makes every audited Route row and its production submission token
 * an explicit test obligation.
 */
class TestPlcProducerCoverageGuard {
    private static final Path AUDIT = Path.of("docs/architecture/audits/"
            + "2026-07-29-s1-s2-plc-producer-call-site-audit.md");
    private static final Pattern ROUTE_KEY = Pattern.compile("^\\| `(S[12]_[A-Z0-9_]+)` \\|");

    @Test
    void auditRouteCardinalityAndRequiredExecutableSuitesRemainInLockstep() throws Exception {
        String audit = Files.readString(AUDIT);
        assertTrue(audit.contains("## Sonic 2 boss-defeat producers"));
        assertTrue(audit.contains("EndDemo_Levels[8]"));
        // The four suites jointly execute every audit row: S1 lifecycle and
        // title/result owners, S1 DLE thresholds, S2 lifecycle/event owners,
        // and each ordinary-boss killing-hit plus post-defeat handoff. Keep
        // these explicit so a reduced Maven selector cannot silently turn the
        // 40-row audit back into façade-only coverage.
        Class.forName("com.openggf.game.sonic1.TestSonic1PlcProducerCoverage");
        Class.forName("com.openggf.game.sonic1.events.TestSonic1PlcProducerOwnerCoverage");
        Class.forName("com.openggf.game.sonic2.TestSonic2PlcProducerCoverage");
        Class.forName("com.openggf.game.sonic2.objects.bosses.TestSonic2BossPlcProducerCoverage");
        assertEquals(40, PlcProducerRouteRegistry.bindings().size(),
                "every audit row needs exactly one registered executable owner case");
        assertEquals(40, PlcProducerRouteRegistry.bindings().stream()
                        .map(PlcProducerRouteRegistry.Binding::key).collect(java.util.stream.Collectors.toSet()).size(),
                "route keys must be unique; a duplicate cannot stand in for a missing owner case");
    }

    @Test
    void auditRouteKeysExactlyMatchTheExecutableRegistry() throws IOException {
        java.util.Set<String> auditKeys = new java.util.HashSet<>();
        for (String line : Files.readAllLines(AUDIT)) {
            Matcher matcher = ROUTE_KEY.matcher(line);
            if (matcher.find()) auditKeys.add(matcher.group(1));
        }
        java.util.Set<String> executableKeys = PlcProducerRouteRegistry.bindings().stream()
                .map(PlcProducerRouteRegistry.Binding::key)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(executableKeys, auditKeys,
                "every audit route key needs exactly one executable production-owner case");
    }

    @Test
    void everyRouteKeyStillNamesItsConcreteExecutableOwnerCase() throws IOException {
        for (PlcProducerRouteRegistry.Binding binding : PlcProducerRouteRegistry.bindings()) {
            String testSource = Files.readString(Path.of(binding.testSource()));
            assertTrue(testSource.contains(binding.executableAnchor()),
                    () -> binding.key() + " no longer has its registered production-owner test case "
                            + binding.executableAnchor());
        }
    }

    @Test
    void everyAuditedOwnerStillContainsTheNativeQueueSubmissionItIsCoveredFor()
            throws IOException {
        for (OwnerSubmission submission : OWNER_SUBMISSIONS) {
            String source = Files.readString(Path.of("src/main/java", submission.path()));
            assertTrue(source.contains(submission.token()),
                    () -> submission.path() + " no longer contains audited PLC submission "
                            + submission.token() + "; amend the audit and executable coverage together");
        }
    }

    private record OwnerSubmission(String path, String token) { }

    // Each entry corresponds to at least one of the 40 rows above.  Grouped
    // event/boss owners retain the exact PLC identifier rather than merely an
    // import or a helper name, so an art-only regression cannot satisfy this guard.
    private static final List<OwnerSubmission> OWNER_SUBMISSIONS = List.of(
            new OwnerSubmission("com/openggf/game/sonic1/titlescreen/Sonic1TitleScreenManager.java", "replaceQueued(0)"),
            new OwnerSubmission("com/openggf/game/sonic1/credits/Sonic1CreditsManager.java", "Sonic1PlcService.appendOperation(1)"),
            new OwnerSubmission("com/openggf/game/sonic1/Sonic1LevelInitProfile.java", "Sonic1PlcService.clear()"),
            new OwnerSubmission("com/openggf/game/sonic1/events/Sonic1GHZEvents.java", "requestSonic1Plc(17)"),
            new OwnerSubmission("com/openggf/game/sonic1/events/Sonic1LZEvents.java", "requestSonic1Plc(17)"),
            new OwnerSubmission("com/openggf/game/sonic1/events/Sonic1MZEvents.java", "requestSonic1Plc(17)"),
            new OwnerSubmission("com/openggf/game/sonic1/events/Sonic1SLZEvents.java", "requestSonic1Plc(17)"),
            new OwnerSubmission("com/openggf/game/sonic1/events/Sonic1SYZEvents.java", "requestSonic1Plc(17)"),
            new OwnerSubmission("com/openggf/game/sonic1/events/Sonic1SBZEvents.java", "requestSonic1Plc(30)"),
            new OwnerSubmission("com/openggf/game/sonic1/events/Sonic1SBZEvents.java", "requestSonic1Plc(31)"),
            new OwnerSubmission("com/openggf/game/sonic1/objects/Sonic1SignpostObjectInstance.java", "replaceQueued(16)"),
            new OwnerSubmission("com/openggf/game/sonic1/objects/Sonic1EggPrisonObjectInstance.java", "replaceQueued(16)"),
            new OwnerSubmission("com/openggf/game/sonic1/events/Sonic1FixedTitleCardManager.java", "PLC_EXPLODE = 2"),
            new OwnerSubmission("com/openggf/game/sonic1/specialstage/Sonic1SpecialStageProvider.java", "Sonic1PlcService.appendOperation(27)"),
            new OwnerSubmission("com/openggf/game/sonic2/titlescreen/TitleScreenManager.java", "Sonic2PlcService.replaceOperation(0)"),
            new OwnerSubmission("com/openggf/game/sonic2/Sonic2LevelInitProfile.java", "Sonic2PlcService.clearOperation()"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2EHZEvents.java", "Sonic2Constants.PLC_EHZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2MTZEvents.java", "Sonic2Constants.PLC_MTZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2WFZEvents.java", "Sonic2Constants.PLC_TORNADO"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2HTZEvents.java", "Sonic2Constants.PLC_HTZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2OOZEvents.java", "Sonic2Constants.PLC_OOZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2MCZEvents.java", "Sonic2Constants.PLC_MCZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2CNZEvents.java", "Sonic2Constants.PLC_CNZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2CPZEvents.java", "Sonic2Constants.PLC_CPZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2DEZEvents.java", "Sonic2Constants.PLC_FIERY_EXPLOSION"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2DEZEvents.java", "Sonic2Constants.PLC_DEZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/events/Sonic2ARZEvents.java", "Sonic2Constants.PLC_ARZ_BOSS"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/SignpostObjectInstance.java", "replaceOperation"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/EggPrisonObjectInstance.java", "replaceOperation"),
            new OwnerSubmission("com/openggf/game/sonic2/titlecard/TitleCardManager.java", "PLC_STD_WATER"),
            new OwnerSubmission("com/openggf/game/sonic2/Sonic2SpecialStageProvider.java", "replaceOperation(0)"),
            new OwnerSubmission("com/openggf/game/sonic2/specialstage/Sonic2SpecialStageIntro.java", "PLC_SPECIAL_STAGE_BOMBS"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/bosses/Sonic2EHZBossInstance.java", "PLC_CAPSULE"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/bosses/Sonic2HTZBossInstance.java", "PLC_CAPSULE"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/bosses/Sonic2ARZBossInstance.java", "PLC_CAPSULE"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/bosses/Sonic2MCZBossInstance.java", "PLC_CAPSULE"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/bosses/Sonic2CNZBossInstance.java", "PLC_CAPSULE"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/bosses/Sonic2CPZBossInstance.java", "PLC_CAPSULE"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/bosses/Sonic2MTZBossInstance.java", "PLC_CAPSULE"),
            new OwnerSubmission("com/openggf/game/sonic2/objects/bosses/Sonic2OOZBossInstance.java", "PLC_CAPSULE"));
}
