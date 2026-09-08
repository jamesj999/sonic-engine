package com.openggf.tools.fbzvisual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestFbzVisualEvidenceToolingContract {

    @Test
    void amendmentPreservesSupersededEvidenceAndVersionedReplacementPaths() throws Exception {
        JsonNode amendment = new ObjectMapper().readTree(Path.of(
                "docs/architecture/research/s3k-zones/fbz-visual-evidence-amendment-proposal.json").toFile());
        assertEquals("rejected-superseded",
                amendment.path("policy").path("current_evidence_status").asText());
        assertEquals("refs/fbz/fbz1-start-outdoor-gameplay-v2-320x224.png",
                amendment.path("proposed_exact_start_replacement")
                        .path("proposed_reference_path").asText());
        assertTrue(amendment.path("superseded_exact_start").path("files").toString()
                .contains("9572942942CC4A385397A942802B2EECCCB6B55BEC067C194D4D7C0909DC0569"));
    }

    @Test
    void emulatorExportsVisibilityAndNaturalCadenceEvidence()
            throws Exception {
        String lua = Files.readString(Path.of("tools/bizhawk/capture_fbz_visual_references.lua"));
        for (String token : new String[]{
                "camera_x_copy", "camera_target_min_x", "anim_counters",
                "title_card_active", "palette_fade_timer", "game_mode",
                "OVERLAYS_DISABLED", "zero-step", "one-step", "vram_sha256"}) {
            assertTrue(lua.contains(token), "missing BizHawk evidence token: " + token);
        }

    }

    @Test
    void repositoryAmendmentCannotPublishUnreviewedVisibleFrameOrCadenceEvidence()
            throws Exception {
        // The historical PowerShell validator/finalizer were local tools and
        // were never tracked. Exercise the actual capture acceptance owner,
        // which must reject the committed pending replacement evidence.
        Path source = Path.of(
                "docs/architecture/research/s3k-zones/fbz-visual-evidence-amendment-proposal.json");
        FbzVisualEvidenceAmendment amendment = FbzVisualEvidenceAmendment.load(source,
                FbzVisualPrebootVerifier.sha256(Files.readAllBytes(source)));

        assertEquals("awaiting-fresh-independent-review",
                amendment.provenance().get("accepted_first_visible_frame_status"));
        assertTrue(assertThrows(IllegalStateException.class,
                amendment::acceptedLevelFrameCounter).getMessage()
                .contains("not independently approved"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> amendment.verifyAcceptedVisibleFrame(Map.of())).getMessage()
                .contains("not independently approved"));

        List<String> checkpoints = List.of("fbz1-aniplc-200", "fbz1-aniplc-208",
                "fbz1-aniplc-210", "fbz1-aniplc-230", "fbz1-aniplc-238");
        JsonNode series = new ObjectMapper().readTree(source.toFile())
                .path("proposed_aniplc_replacements").path("series");
        assertEquals(checkpoints.size(), series.size());
        for (int index = 0; index < checkpoints.size(); index++) {
            String checkpoint = checkpoints.get(index);
            assertEquals(checkpoint, series.get(index).path("checkpoint").asText());
            assertTrue(series.get(index).path("series").asText().endsWith("-v2"));
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> amendment.requireApprovedCadenceSeries(checkpoint)).getMessage()
                    .contains("not independently approved"));
            assertThrows(IllegalStateException.class,
                    () -> amendment.cadenceSeriesName(checkpoint));
        }
    }

    @Test
    void hiddenCaptureBootAndConfigurationShareOneExplicitEngineContext() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/tools/fbzvisual/HiddenGlCaptureSession.java"));

        assertTrue(source.contains("this(mode, EngineContext.fromLegacySingletonsForBootstrap())"));
        assertTrue(source.contains("this(mode, engineContext, HeadlessGameBoot::new)"));
        assertTrue(source.contains("configuration = engineContext.configuration()"));
        assertTrue(source.contains("configure(configuration, mode)"));
        assertTrue(source.contains("bootFactory.create("));
        assertTrue(source.contains("mode.framebufferHeight(), engineContext)"));
        assertTrue(source.contains("configuration.clearSessionOverrides()"));
        assertFalse(source.contains("SonicConfigurationService.getInstance()"));
    }

}
