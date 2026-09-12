package com.openggf.mods;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestModApiReleasePolicy {
    private static final Path POLICY_PATH = Path.of("mod-api-release-policy.properties");
    private static final String VALID = """
            schemaVersion=1
            targetBranch=next
            masterLine=0.5
            developLine=0.6
            nextLine=0.7
            currentApi=0.7.0
            currentStatus=candidate
            publishedBaselines=
            """;

    @Test
    void repositoryDescriptorRetainsUnpublishedCandidateAfterRollover() throws IOException {
        ModApiReleasePolicy policy = ModApiReleasePolicy.read(POLICY_PATH);
        assertTrue(List.of("develop", "next").contains(policy.targetBranch()));
        assertEquals(new ModApiReleasePolicy.ReleaseLine(0, 6), policy.masterLine());
        assertEquals(new ModApiReleasePolicy.ReleaseLine(0, 7), policy.developLine());
        assertEquals(new ModApiReleasePolicy.ReleaseLine(0, 8), policy.nextLine());
        assertEquals(SemanticVersion.parse("0.7.0"), policy.currentApi());
        assertEquals(ModApiReleasePolicy.Status.CANDIDATE, policy.currentStatus());
        assertEquals(List.of(), policy.publishedBaselines());
        assertEquals(Map.of("mod-api-signatures-0.7.txt", SemanticVersion.parse("0.7.0")),
                policy.expectedPins());
    }

    @Test
    void destinationPropertyIsOptionalButMustAgreeWhenPresent() throws IOException {
        String destination = System.getProperty("modApi.destinationBranch");
        if (destination == null) return;
        ModApiReleasePolicy policy = ModApiReleasePolicy.read(POLICY_PATH);
        assertTrue(ModApiReleasePolicy.BRANCHES.contains(destination),
                () -> "Invalid modApi.destinationBranch '" + destination
                        + "': use next, develop, or master");
        assertEquals(policy.targetBranch(), destination,
                () -> "Mod API policy targets '" + policy.targetBranch()
                        + "' but CI destination is '" + destination
                        + "'; update targetBranch or run against the intended destination branch");
    }

    @Test
    void everyRequiredKeyMustAppearExactlyOnce() {
        assertInvalid(VALID.replace("currentApi=0.7.0\n", ""), "currentApi", "<missing>");
        assertInvalid(VALID + "currentApi=0.7.0\n", "currentApi", "0.7.0");
        assertInvalid(VALID + "mystery=value\n", "mystery", "value");
        assertInvalid(VALID.replace("currentApi=", " currentApi="), " currentApi", "0.7.0");
        assertInvalid(VALID + "not-a-property\n", "Line", "key=value");
    }

    @Test
    void schemaBranchStatusAndCanonicalNumbersAreStrict() {
        assertInvalid(replace("schemaVersion", "2"), "schemaVersion", "2");
        assertInvalid(replace("targetBranch", "feature"), "targetBranch", "feature");
        assertInvalid(replace("currentStatus", "draft"), "currentStatus", "draft");
        assertInvalid(replace("currentStatus", "CANDIDATE"), "currentStatus", "CANDIDATE");
        assertInvalid(replace("masterLine", "00.5"), "masterLine", "00.5");
        assertInvalid(replace("currentApi", "0.07.0"), "currentApi", "0.07.0");
    }

    @Test
    void topologyUsesLexicographicReleaseLineOrderingIncludingZeroNineToOneZero() {
        assertInvalid(replace("developLine", "0.5"),
                "masterLine/developLine/nextLine", "0.5/0.5/0.7");
        String crossingMajor = VALID
                .replace("masterLine=0.5", "masterLine=0.9")
                .replace("developLine=0.6", "developLine=1.0")
                .replace("nextLine=0.7", "nextLine=1.1")
                .replace("currentApi=0.7.0", "currentApi=1.1.0");
        assertEquals(new ModApiReleasePolicy.ReleaseLine(1, 1),
                ModApiReleasePolicy.parse(crossingMajor).nextLine());
    }

    @Test
    void candidatesCannotLeadSelectedEngineLineAndMustUsePatchZero() {
        assertInvalid(replace("currentApi", "0.8.0"), "currentApi", "0.8.0");
        assertInvalid(replace("currentApi", "0.7.1"), "currentApi", "0.7.1");
    }

    @Test
    void developmentRolloverCanRetainCandidateAcrossBothDestinationBranches() {
        String promoted = VALID
                .replace("masterLine=0.5", "masterLine=0.6")
                .replace("developLine=0.6", "developLine=0.7")
                .replace("nextLine=0.7", "nextLine=0.8");
        for (String branch : List.of("develop", "next")) {
            ModApiReleasePolicy policy = ModApiReleasePolicy.parse(
                    promoted.replace("targetBranch=next", "targetBranch=" + branch));
            assertEquals(branch, policy.targetBranch());
            assertEquals(SemanticVersion.parse("0.7.0"), policy.currentApi());
            assertEquals(ModApiReleasePolicy.Status.CANDIDATE, policy.currentStatus());
            assertEquals(List.of(), policy.publishedBaselines());
            assertEquals(Map.of("mod-api-signatures-0.7.txt", SemanticVersion.parse("0.7.0")),
                    policy.expectedPins());
        }
    }

    @Test
    void masterCandidateStillRequiresItsSelectedReleaseLine() {
        assertInvalid(VALID.replace("targetBranch=next", "targetBranch=master"),
                "currentApi", "0.7.0");
    }

    @Test
    void publicationStateIsConsistentAndBaselinesAreCanonicalUniqueAndOrdered() {
        assertInvalid(replace("currentStatus", "published"), "publishedBaselines", "");
        String publishedOutsideMaster = VALID
                .replace("currentStatus=candidate", "currentStatus=published")
                .replace("publishedBaselines=", "publishedBaselines=0.7.0");
        assertInvalid(publishedOutsideMaster, "targetBranch", "next");
        assertInvalid(replace("publishedBaselines", "0.7.0"), "publishedBaselines", "0.7.0");
        assertInvalid(replace("publishedBaselines", "0.5.0,0.5.0"), "publishedBaselines", "0.5.0");
        assertInvalid(replace("publishedBaselines", "0.6.0,0.5.0"), "publishedBaselines", "0.6.0,0.5.0");
        assertInvalid(replace("publishedBaselines", "0.5.0, 0.6.0"), "publishedBaselines", "0.5.0, 0.6.0");
    }

    @Test
    void olderBaselinesAndMultipleMaintenancePinsNormalizeWithoutCollision() {
        String policyText = VALID.replace("publishedBaselines=",
                "publishedBaselines=0.3.0,0.3.1,0.5.2");
        ModApiReleasePolicy policy = ModApiReleasePolicy.parse(policyText);
        assertEquals(List.of(SemanticVersion.parse("0.3.0"), SemanticVersion.parse("0.3.1"),
                SemanticVersion.parse("0.5.2")), policy.publishedBaselines());
        assertEquals(List.of("mod-api-signatures-0.3.0.txt", "mod-api-signatures-0.3.1.txt",
                "mod-api-signatures-0.5.2.txt", "mod-api-signatures-0.7.txt"),
                policy.expectedPins().keySet().stream().toList());
    }

    @Test
    void baselineLaterThanCurrentIsForbidden() {
        assertInvalid(replace("publishedBaselines", "0.8.0"), "publishedBaselines", "0.8.0");
    }

    @Test
    void publishedCurrentUsesOnlyItsFullVersionPin() {
        String published = VALID
                .replace("targetBranch=next", "targetBranch=master")
                .replace("currentApi=0.7.0", "currentApi=0.5.0")
                .replace("currentStatus=candidate", "currentStatus=published")
                .replace("publishedBaselines=", "publishedBaselines=0.3.0,0.5.0");
        ModApiReleasePolicy policy = ModApiReleasePolicy.parse(published);
        assertEquals(List.of("mod-api-signatures-0.3.0.txt", "mod-api-signatures-0.5.0.txt"),
                policy.expectedPins().keySet().stream().toList());
    }

    @Test
    void returnedCollectionsAreImmutable() {
        ModApiReleasePolicy policy = ModApiReleasePolicy.parse(
                replace("publishedBaselines", "0.5.0"));
        assertThrows(UnsupportedOperationException.class,
                () -> policy.publishedBaselines().add(SemanticVersion.parse("0.6.0")));
        assertThrows(UnsupportedOperationException.class,
                () -> policy.expectedPins().clear());
    }

    private static String replace(String key, String value) {
        return VALID.replaceAll("(?m)^" + key + "=.*$", key + "=" + value);
    }

    private static void assertInvalid(String text, String expectedKey, String expectedValue) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ModApiReleasePolicy.parse(text));
        assertTrue(error.getMessage().contains(expectedKey), error.getMessage());
        assertTrue(error.getMessage().contains(expectedValue), error.getMessage());
    }
}
