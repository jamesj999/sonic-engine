package com.openggf.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Expert-level override of the argument list for one ffmpeg pass.
 *
 * <p>Recording runs two passes: the first encodes frames arriving on
 * {@code pipe:0} into a lossless intermediate, the second muxes that with the
 * raw audio into the finished file. Each pass has its own override, because a
 * single command string does not describe two processes.
 *
 * <p>Three states, distinguished so that "use the engine's command" and "do not
 * run this pass at all" cannot be confused:
 *
 * <ul>
 *   <li>{@link #DEFAULT} — the engine's built-in command. Keeping this as the
 *       configured default rather than writing the full command into
 *       {@code config.yaml} means improvements to the built-in command still
 *       reach anyone who has not deliberately overridden it.</li>
 *   <li>Empty — skip the pass. Only meaningful for the second pass, where it
 *       publishes the first pass's output directly and the recording has no
 *       audio track.</li>
 *   <li>Anything else — the literal argument list, placeholders expanded.</li>
 * </ul>
 *
 * <p>The executable itself is not part of the override: it is resolved from
 * configuration or {@code PATH} and prepended. Everything after it is yours.
 */
public final class FfmpegCommandTemplate {

    /** Configured value meaning "use the engine's built-in command". */
    public static final String DEFAULT = "default";

    private FfmpegCommandTemplate() {
    }

    /** Unset, or the literal {@link #DEFAULT}, means the engine's own command. */
    public static boolean usesBuiltIn(String configured) {
        return configured == null || DEFAULT.equalsIgnoreCase(configured.trim());
    }

    /**
     * True when the pass is configured away entirely. Whitespace counts as
     * empty: a key left as {@code pass2Args: " "} means the same thing as
     * {@code ""} and must not be expanded into an empty argument list.
     */
    public static boolean skipsPass(String configured) {
        return configured != null && configured.trim().isEmpty();
    }

    /**
     * Expands {@code {name}} placeholders and splits into arguments.
     *
     * <p>Splitting is on whitespace, honouring single and double quotes so a
     * path containing a space can be given as {@code "{output}"}. An unknown
     * placeholder is an error rather than being passed through literally: a
     * typo would otherwise reach ffmpeg as a filename and produce a confusing
     * failure much later.
     */
    public static List<String> expand(String template, Map<String, String> values) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inArgument = false;
        char quote = 0;

        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                    continue;
                }
                if (c != '{') {
                    current.append(c);
                    continue;
                }
                // Fall through: a placeholder inside quotes still expands, which
                // is the whole point of quoting one — "{output}" is how a path
                // containing a space is kept as a single argument.
            }
            if (quote == 0 && (c == '\'' || c == '"')) {
                quote = c;
                inArgument = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inArgument) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    inArgument = false;
                }
                continue;
            }
            if (c == '{') {
                int end = template.indexOf('}', i);
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "unterminated placeholder in ffmpeg command: " + template);
                }
                String name = template.substring(i + 1, end);
                String value = values.get(name);
                if (value == null) {
                    throw new IllegalArgumentException(
                            "unknown placeholder {" + name + "} in ffmpeg command;"
                                    + " known placeholders are " + values.keySet());
                }
                current.append(value);
                inArgument = true;
                i = end;
                continue;
            }
            current.append(c);
            inArgument = true;
        }
        if (quote != 0) {
            throw new IllegalArgumentException(
                    "unbalanced quote in ffmpeg command: " + template);
        }
        if (inArgument) {
            arguments.add(current.toString());
        }
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException(
                    "ffmpeg command expanded to no arguments: " + template);
        }
        return arguments;
    }
}
