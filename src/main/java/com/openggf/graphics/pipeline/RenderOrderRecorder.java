package com.openggf.graphics.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Records render commands in order for testing.
 * Use in tests to verify render order compliance.
 */
public class RenderOrderRecorder {
    private static RenderOrderRecorder instance;
    private final List<RenderCommand> commands = new ArrayList<>();
    private int orderCounter = 0;
    private boolean enabled = false;

    public static synchronized RenderOrderRecorder getInstance() {
        if (instance == null) {
            instance = new RenderOrderRecorder();
        }
        return instance;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void record(RenderPhase phase, String component) {
        if (enabled) {
            commands.add(RenderCommand.of(phase, component, orderCounter++));
        }
    }

    public void recordPostFadeDiagnostic(String component) {
        record(RenderPhase.POST_FADE_DIAGNOSTIC, component);
    }

    public List<RenderCommand> getCommands() {
        return Collections.unmodifiableList(commands);
    }

    public void clear() {
        commands.clear();
        orderCounter = 0;
    }

    /** Verify that all phases are in correct order */
    public List<String> verifyOrder() {
        List<String> violations = new ArrayList<>();
        RenderPhase lastPhase = null;

        for (RenderCommand cmd : commands) {
            if (lastPhase != null && cmd.phase().ordinal() < lastPhase.ordinal()) {
                violations.add("Order violation: " + cmd.component() + " (" + cmd.phase() +
                    ") rendered after " + lastPhase + " at index " + cmd.orderIndex());
            }
            lastPhase = cmd.phase();
        }
        return violations;
    }

    /** Check if fade was rendered last */
    public boolean fadeRenderedLast() {
        if (commands.isEmpty()) return true;
        RenderCommand last = commands.get(commands.size() - 1);
        return last.phase() == RenderPhase.FADE_PASS;
    }

    /**
     * Validate that every command after the fade pass is an explicitly allowed
     * diagnostic/exception. The normal UI contract still ends at fade.
     */
    public List<String> verifyPostFadeDiagnosticsAllowed(List<String> allowedComponents) {
        Set<String> allowed = new HashSet<>(allowedComponents);
        List<String> violations = new ArrayList<>();
        boolean sawFade = false;

        for (RenderCommand cmd : commands) {
            if (cmd.phase() == RenderPhase.FADE_PASS) {
                sawFade = true;
                continue;
            }

            if (sawFade) {
                if (cmd.phase() != RenderPhase.POST_FADE_DIAGNOSTIC) {
                    violations.add("Unexpected post-fade phase: " + cmd.component() + " (" + cmd.phase() + ")");
                } else if (!allowed.contains(cmd.component())) {
                    violations.add("Unregistered post-fade diagnostic: " + cmd.component());
                }
            }
        }

        return violations;
    }
}
