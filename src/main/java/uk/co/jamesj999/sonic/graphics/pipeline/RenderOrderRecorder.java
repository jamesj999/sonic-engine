package uk.co.jamesj999.sonic.graphics.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
}
