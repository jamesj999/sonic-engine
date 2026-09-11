package com.openggf.tools;

import com.openggf.game.sonic3k.constants.S3kZoneSet;
import com.openggf.tools.AgentWorkflowTool.Game;
import com.openggf.tools.AgentWorkflowTool.RegistryStatus;
import com.openggf.tools.AgentWorkflowTool.Request;
import com.openggf.tools.AgentWorkflowTool.WorkType;
import org.junit.jupiter.api.Test;

import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic golden-output tests for {@link AgentWorkflowTool}'s pure logic.
 *
 * <p>No ROM, no OpenGL, no {@code RomOffsetFinder} invocation. Exercises argument
 * parsing, S3K zone-set (S3KL/SKL) resolution, and the checklist text assembly with
 * injected stub resolvers.
 */
class TestAgentWorkflowTool {

    // ------------------------------------------------------------------
    // Argument parsing
    // ------------------------------------------------------------------

    @Test
    void parsesDocumentedExampleInvocation() {
        // Matches: object s3k MHZ 0x8A
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        assertEquals(WorkType.OBJECT, req.workType());
        assertEquals(Game.S3K, req.game());
        assertEquals("MHZ", req.zoneName());
        assertEquals(0x07, req.zoneId());
        assertEquals(0x8A, req.objectId());
        assertEquals(-1, req.act());
        assertEquals("0x8A", req.objectIdHex());
    }

    @Test
    void parsesDecimalObjectIdAndOptionalAct() {
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "AIZ", "9", "1"});
        assertEquals(9, req.objectId());
        assertEquals(1, req.act());
    }

    @Test
    void aliasesAreAccepted() {
        Request a = AgentWorkflowTool.parseArgs(new String[] {"badnik", "sonic3k", "MHZ"});
        assertEquals(WorkType.OBJECT, a.workType());
        assertEquals(Game.S3K, a.game());
        assertEquals(-1, a.objectId());
    }

    @Test
    void rejectsMissingArgsAndUnknownTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentWorkflowTool.parseArgs(new String[] {"object", "s3k"}));
        assertThrows(IllegalArgumentException.class,
                () -> AgentWorkflowTool.parseArgs(new String[] {"object", "s4", "AIZ"}));
        assertThrows(IllegalArgumentException.class,
                () -> AgentWorkflowTool.parseArgs(new String[] {"levelmutation", "s3k", "AIZ"}));
    }

    @Test
    void invalidObjectIdDegradesToNoneNotException() {
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "notahex"});
        assertEquals(-1, req.objectId());
        assertEquals("(none)", req.objectIdHex());
    }

    // ------------------------------------------------------------------
    // S3K zone-set resolution (S3KL vs SKL)
    // ------------------------------------------------------------------

    @Test
    void resolvesS3klSetForLowZones() {
        // AIZ (0x00) and LBZ (0x06) belong to S3KL (zones 0-6).
        assertEquals(0x00, AgentWorkflowTool.resolveS3kZoneId("AIZ"));
        assertEquals(0x06, AgentWorkflowTool.resolveS3kZoneId("LBZ"));
        Request aiz = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "AIZ", "0x03"});
        assertEquals(S3kZoneSet.S3KL, aiz.zoneSet());
        assertTrue(aiz.hasResolvedS3kZone());
    }

    @Test
    void resolvesSklSetForHighZones() {
        // MHZ (0x07) is the first SKL zone (zones 7-13).
        assertEquals(0x07, AgentWorkflowTool.resolveS3kZoneId("mhz")); // case-insensitive
        Request mhz = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        assertEquals(S3kZoneSet.SKL, mhz.zoneSet());
    }

    @Test
    void unknownZoneNameIsUnresolved() {
        assertEquals(-1, AgentWorkflowTool.resolveS3kZoneId("ZZZ"));
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "ZZZ", "0x01"});
        assertFalse(req.hasResolvedS3kZone());
        assertNull(req.zoneSet());
    }

    @Test
    void resolvesNonMainZonesHpzCompetitionAndBonus() {
        // Beyond the 13 main zones, HPZ / competition / bonus must still resolve (not degrade to -1)
        // so delegated agents keep zone-set + identity guidance.
        assertEquals(0x16, AgentWorkflowTool.resolveS3kZoneId("HPZ"));
        // Balloon Park is ROM zone $0F: LevelSizes rows 30/31
        // (skdisasm/sonic3k.asm:38110-38111) and OffsAnPal entries 30/31
        // (sonic3k.asm:3148-3149), both indexed zone*2 + act.
        assertEquals(0x0F, AgentWorkflowTool.resolveS3kZoneId("BPZ"));
        assertEquals(0x13, AgentWorkflowTool.resolveS3kZoneId("gumball")); // case-insensitive
        Request hpz = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "HPZ", "0x01"});
        assertTrue(hpz.hasResolvedS3kZone());
        assertEquals(S3kZoneSet.SKL, hpz.zoneSet()); // forZone() returns SKL for every zone > 6
        String out = AgentWorkflowTool.buildChecklist(hpz, null, null);
        // Non-main zones are flagged rather than mislabeled "zones 7-13: MHZ-DDZ".
        assertTrue(out.contains("NON-MAIN zone"), "non-main zone must be flagged, not silently dropped");
        assertFalse(out.contains("MHZ-DDZ"), "a 0x16 zone must not claim the main MHZ-DDZ range");
    }

    @Test
    void mainSklZoneKeepsFamiliarRangeLabel() {
        Request mhz = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        String out = AgentWorkflowTool.buildChecklist(mhz, null, null);
        assertTrue(out.contains("zones 7-13: MHZ-DDZ"), "main SKL zone keeps the MHZ-DDZ label");
        assertFalse(out.contains("NON-MAIN zone"), "a main SKL zone must not be flagged non-main");
    }

    @Test
    void nonS3kGameHasNoZoneSet() {
        Request s2 = AgentWorkflowTool.parseArgs(new String[] {"object", "s2", "EHZ", "0x01"});
        assertEquals(-1, s2.zoneId());
        assertNull(s2.zoneSet());
        assertFalse(s2.hasResolvedS3kZone());
    }

    // ------------------------------------------------------------------
    // Checklist assembly (golden content)
    // ------------------------------------------------------------------

    private static final IntFunction<String> STUB_NAME = id -> id == 0x8A ? "FBZExitHall"
            : AgentWorkflowTool.UNKNOWN_NAME;
    private static final IntFunction<RegistryStatus> STUB_STATUS =
            id -> id == 0x8A ? RegistryStatus.PLACEHOLDER_ONLY : RegistryStatus.UNKNOWN;

    @Test
    void checklistContainsAllRequiredSections() {
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        String out = AgentWorkflowTool.buildChecklist(req, STUB_NAME, STUB_STATUS);

        assertTrue(out.contains("## Task"));
        assertTrue(out.contains("## S3K Zone-Set Resolution"));
        assertTrue(out.contains("## Object Identity & Registry Status"));
        assertTrue(out.contains("## Likely Disassembly Labels"));
        assertTrue(out.contains("## RomOffsetFinder Commands"));
        assertTrue(out.contains("## Related Existing Files To Inspect"));
        assertTrue(out.contains("## Required Guard Tests"));
        assertTrue(out.contains("## Suggested Focused Tests"));
        assertTrue(out.contains("## Trace Replay Workflow"));
        assertTrue(out.contains("## Documentation Likely Affected"));
        assertTrue(out.contains("## Non-Negotiable Rules"));
    }

    @Test
    void checklistShowsSklResolutionAndResolvedName() {
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        String out = AgentWorkflowTool.buildChecklist(req, STUB_NAME, STUB_STATUS);

        assertTrue(out.contains("Resolved zone set: SKL"));
        assertTrue(out.contains("MHZ-DDZ"));
        assertTrue(out.contains("Primary name: FBZExitHall"));
        assertTrue(out.contains("0x8A"));
        assertTrue(out.contains("PLACEHOLDER_ONLY"));
    }

    @Test
    void checklistEmitsS3kGuardsAndSAndKAddressRule() {
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        String out = AgentWorkflowTool.buildChecklist(req, STUB_NAME, STUB_STATUS);

        // S3K-specific guard tests present.
        assertTrue(out.contains("TestSonic3kPlcArtRegistry"));
        assertTrue(out.contains("TestPatternSpriteRendererCorruptionGuard"));
        // Always-on guards present.
        assertTrue(out.contains("TestObjectServicesMigrationGuard"));
        assertTrue(out.contains("TestNoServicesInObjectConstructors"));
        assertTrue(out.contains("TestNoDirectMapMutationsInGameplay"));
        // S&K-half address rule and ROM-only rule present.
        assertTrue(out.contains("sonic3k.asm"));
        assertTrue(out.contains("0x200000"));
        assertTrue(out.contains("ROM-only runtime assets"));
        // RomOffsetFinder commands use --game s3k and are printed, not executed.
        assertTrue(out.contains("--game s3k"));
        assertTrue(out.contains("com.openggf.tools.disasm.RomOffsetFinder"));
        // Trace comparison-only invariant present.
        assertTrue(out.contains("comparison-only"));
    }

    @Test
    void checklistForS2OmitsS3kOnlySections() {
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s2", "EHZ", "0x01"});
        String out = AgentWorkflowTool.buildChecklist(req, null, null);

        assertTrue(out.contains("N/A -- zone-set system is S3K-only"));
        assertFalse(out.contains("TestSonic3kPlcArtRegistry"));
        assertFalse(out.contains("sonic3k.asm"));
        assertTrue(out.contains("--game s2"));
        // Without a name resolver, identity degrades gracefully to UNKNOWN.
        assertTrue(out.contains(AgentWorkflowTool.UNKNOWN_NAME));
    }

    @Test
    void buildChecklistIsDeterministic() {
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        String a = AgentWorkflowTool.buildChecklist(req, STUB_NAME, STUB_STATUS);
        String b = AgentWorkflowTool.buildChecklist(req, STUB_NAME, STUB_STATUS);
        assertEquals(a, b);
    }

    // ------------------------------------------------------------------
    // Default (real, ROM-free) resolvers — registry name switch.
    // ------------------------------------------------------------------

    @Test
    void defaultStatusResolverDoesNotFalselyClaimPlaceholderOnly() {
        // Regression: the CLI default used to report PLACEHOLDER_ONLY for EVERY known name,
        // wrongly implying an already-implemented object still needs a factory. It must instead
        // report that the factory map was not probed.
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        IntFunction<RegistryStatus> status = AgentWorkflowTool.defaultStatusResolver(req);
        assertNotNull(status, "S3K resolved-zone request should expose a default status resolver");
        assertEquals(RegistryStatus.NAME_KNOWN_FACTORY_UNVERIFIED, status.apply(0x8A));
        assertFalse(status.apply(0x8A) == RegistryStatus.PLACEHOLDER_ONLY,
                "default resolver must not claim PLACEHOLDER_ONLY without probing the factory map");
    }

    @Test
    void defaultNameResolverResolvesKnownSklNameRomFree() {
        // 0x8A in MHZ (SKL) -> FBZExitHall, via the pure Sonic3kObjectRegistry switch (no ROM).
        Request req = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "MHZ", "0x8A"});
        IntFunction<String> names = AgentWorkflowTool.defaultNameResolver(req);
        assertNotNull(names);
        assertEquals("FBZExitHall", names.apply(0x8A));
    }

    @Test
    void defaultResolversAreNullForNonS3kOrUnresolvedZone() {
        Request s2 = AgentWorkflowTool.parseArgs(new String[] {"object", "s2", "EHZ", "0x01"});
        assertNull(AgentWorkflowTool.defaultNameResolver(s2));
        assertNull(AgentWorkflowTool.defaultStatusResolver(s2));
        Request badZone = AgentWorkflowTool.parseArgs(new String[] {"object", "s3k", "ZZZ", "0x01"});
        assertNull(AgentWorkflowTool.defaultStatusResolver(badZone));
    }
}
