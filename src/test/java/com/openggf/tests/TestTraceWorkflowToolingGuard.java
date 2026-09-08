package com.openggf.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceWorkflowToolingGuard {
    @Test
    void developTraceValidatorComesFromWorkflowRevisionWhileReportsStayInDevelopCheckout()
            throws Exception {
        JsonNode workflow = new ObjectMapper(new YAMLFactory()).readTree(
                Path.of(".github/workflows/ci.yml").toFile());
        JsonNode job = workflow.path("jobs").path("develop-trace-replay");
        assertEquals("github.event_name == 'workflow_dispatch'", job.path("if").asText());
        String toolPath = ".trace-validation-tools";
        String helper = "tools/testing/validate-trace-reports.py";
        boolean developCheckout = false;
        boolean toolingCheckout = false;
        boolean invocation = false;
        for (JsonNode step : job.path("steps")) {
            if (step.path("uses").asText().startsWith("actions/checkout@")) {
                JsonNode options = step.path("with");
                if (options.path("path").asText().isEmpty()) {
                    assertEquals("develop", options.path("ref").asText());
                    developCheckout = true;
                } else if (toolPath.equals(options.path("path").asText())) {
                    assertTrue(developCheckout, "nested tooling checkout must follow the develop checkout");
                    assertEquals("${{ github.sha }}", options.path("ref").asText(),
                            "tooling must come from the workflow revision, not mutable develop");
                    assertEquals(helper, options.path("sparse-checkout").asText().trim());
                    assertEquals("false", options.path("sparse-checkout-cone-mode").asText());
                    assertTrue(Files.isRegularFile(Path.of(helper)),
                            "the workflow revision must actually supply its validator");
                    toolingCheckout = true;
                }
            }
            String run = step.path("run").asText();
            if (run.contains("validate-trace-reports.py")) {
                assertTrue(toolingCheckout, "validator must be checked out before invocation");
                assertTrue(run.contains("python3 " + toolPath + "/" + helper),
                        "invoke the supplied workflow-revision validator");
                assertTrue(run.contains("--root target/trace-reports"));
                assertTrue(step.path("working-directory").asText().isEmpty(),
                        "report paths must remain relative to the develop checkout");
                invocation = true;
            }
        }
        assertTrue(developCheckout);
        assertTrue(toolingCheckout, "develop does not necessarily contain next's validator");
        assertTrue(invocation);
    }
}
