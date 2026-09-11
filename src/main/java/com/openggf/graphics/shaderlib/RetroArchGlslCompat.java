package com.openggf.graphics.shaderlib;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RetroArchGlslCompat {
    private static final int ENGINE_GLSL_VERSION = 410;
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*#\\s*version\\s+(\\d+)\\b[^\\r\\n]*(?:\\R|$)");
    private static final Pattern TEXTURE2D_CALL = Pattern.compile("\\btexture2D\\s*\\(");
    private static final Pattern ATTRIBUTE_TOKEN = Pattern.compile("\\battribute\\b");
    private static final Pattern VARYING_TOKEN = Pattern.compile("\\bvarying\\b");
    private static final Pattern FRAGMENT_OUTPUT = Pattern.compile(
            "(?m)^\\s*(?:layout\\s*\\([^\\r\\n]*\\)\\s*)?out\\s+(?:\\w+\\s+)*\\w+\\s+\\w+\\s*(?:\\[[^\\]]+\\])?\\s*;");
    private static final Pattern OUTPUT_NAME = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*(?:\\[[^\\]]+\\])?\\s*;$");

    private RetroArchGlslCompat() {
    }

    public static String stageSource(String source, String stage) throws DisplayShaderLoadException {
        return stageSource(source, stage, false);
    }

    public static String stageSource(String source, String stage, boolean enableParameterUniforms)
            throws DisplayShaderLoadException {
        if (source == null) {
            throw new DisplayShaderLoadException("Shader source is required");
        }

        String normalizedStage = normalizeStage(stage);
        String body = stripVersionLines(source);
        StringBuilder staged = new StringBuilder(source.length() + 128);

        staged.append("#version 410 core\n");
        appendDefineIfMissing(staged, body, normalizedStage);
        appendDefineIfMissing(staged, body, "COMPAT_" + normalizedStage);
        if (enableParameterUniforms) {
            appendDefineIfMissing(staged, body, "PARAMETER_UNIFORM");
        }
        String stageRelevantBody = stageRelevantBody(body, normalizedStage);
        FragmentOutputInfo fragmentOutputs = fragmentOutputs(stageRelevantBody);
        boolean hasLegacyFragColor = "FRAGMENT".equals(normalizedStage)
                && stageRelevantBody.contains("gl_FragColor");
        boolean needsFragColorOutput = hasLegacyFragColor && fragmentOutputs.count() == 0;
        String fragColorAliasTarget = fragColorAliasTarget(hasLegacyFragColor, needsFragColorOutput,
                fragmentOutputs);
        appendLegacyPrelude(staged, body, normalizedStage, fragColorAliasTarget);
        appendBody(staged, body, needsFragColorOutput);

        return staged.toString();
    }

    private static String normalizeStage(String stage) throws DisplayShaderLoadException {
        if (stage == null) {
            throw new DisplayShaderLoadException("Shader stage is required");
        }

        String normalized = stage.trim().toUpperCase(Locale.ROOT);
        if (!"VERTEX".equals(normalized) && !"FRAGMENT".equals(normalized)) {
            throw new DisplayShaderLoadException("Unsupported shader stage: " + stage);
        }
        return normalized;
    }

    private static String stripVersionLines(String source) throws DisplayShaderLoadException {
        Matcher matcher = VERSION_LINE.matcher(source);
        while (matcher.find()) {
            int version = parseVersion(matcher.group(1));
            if (version > ENGINE_GLSL_VERSION) {
                throw new UnsupportedShaderException("GLSL version " + version
                        + " is newer than engine context version " + ENGINE_GLSL_VERSION);
            }
        }
        return matcher.replaceAll("");
    }

    private static void appendDefineIfMissing(StringBuilder staged, String body, String name) {
        if (!hasDefine(body, name)) {
            staged.append("#define ").append(name).append('\n');
        }
    }

    private static boolean hasDefine(String body, String name) {
        return Pattern.compile("(?m)^\\s*#\\s*define\\s+" + Pattern.quote(name) + "\\b")
                .matcher(body)
                .find();
    }

    private static int parseVersion(String rawVersion) throws DisplayShaderLoadException {
        try {
            return Integer.parseInt(rawVersion);
        } catch (NumberFormatException e) {
            throw new DisplayShaderLoadException("Invalid GLSL version: " + rawVersion, e);
        }
    }

    private static void appendLegacyPrelude(StringBuilder staged, String body, String stage,
                                            String fragColorAliasTarget) {
        if (TEXTURE2D_CALL.matcher(body).find()) {
            staged.append("#define texture2D texture\n");
        }

        if ("VERTEX".equals(stage)) {
            if (ATTRIBUTE_TOKEN.matcher(body).find()) {
                staged.append("#define attribute in\n");
            }
            if (VARYING_TOKEN.matcher(body).find()) {
                staged.append("#define varying out\n");
            }
            return;
        }

        if (VARYING_TOKEN.matcher(body).find()) {
            staged.append("#define varying in\n");
        }
        if (fragColorAliasTarget != null) {
            staged.append("#define gl_FragColor ").append(fragColorAliasTarget).append('\n');
        }
    }

    private static String fragColorAliasTarget(boolean hasLegacyFragColor, boolean needsFragColorOutput,
                                               FragmentOutputInfo fragmentOutputs)
            throws DisplayShaderLoadException {
        if (!hasLegacyFragColor) {
            return null;
        }
        if (needsFragColorOutput) {
            return "FragColor";
        }
        if (fragmentOutputs.count() == 1) {
            if (fragmentOutputs.name() == null) {
                throw new UnsupportedShaderException(
                        "Shader uses gl_FragColor with an unrecognized fragment output declaration");
            }
            return fragmentOutputs.name();
        }
        throw new UnsupportedShaderException(
                "Shader uses gl_FragColor with multiple fragment outputs, which is unsupported");
    }

    private static String stageRelevantBody(String body, String stage) {
        StringBuilder out = new StringBuilder(body.length());
        Deque<StageFrame> stack = new ArrayDeque<>();
        boolean active = true;
        for (String line : body.split("(?<=\\R)", -1)) {
            String trimmed = line.trim();
            String stageIf = stageConditionalStage(trimmed, "#if", "#ifdef");
            if (stageIf != null) {
                boolean matches = stageIf.equals(stage);
                stack.push(new StageFrame(active, matches, true));
                active = active && matches;
                continue;
            }
            if (trimmed.startsWith("#if")) {
                stack.push(new StageFrame(active, false, false));
                continue;
            }

            String stageElif = stageConditionalStage(trimmed, "#elif");
            if (stageElif != null && !stack.isEmpty() && stack.peek().stageConditional) {
                StageFrame frame = stack.peek();
                boolean matches = stageElif.equals(stage);
                active = frame.parentActive && !frame.matchedStage && matches;
                frame.matchedStage = frame.matchedStage || matches;
                continue;
            }
            if (trimmed.startsWith("#elif") && !stack.isEmpty()) {
                active = stack.peek().parentActive;
                continue;
            }
            if (trimmed.startsWith("#else") && !stack.isEmpty()) {
                StageFrame frame = stack.peek();
                active = frame.stageConditional ? frame.parentActive && !frame.matchedStage : frame.parentActive;
                frame.matchedStage = true;
                continue;
            }
            if (trimmed.startsWith("#endif") && !stack.isEmpty()) {
                active = stack.pop().parentActive;
                continue;
            }
            if (active) {
                out.append(line);
            }
        }
        return out.toString();
    }

    private static String stageConditionalStage(String directive, String... directives) {
        for (String prefix : directives) {
            if (!directive.startsWith(prefix)) {
                continue;
            }
            if (directive.matches("^" + Pattern.quote(prefix) + "\\s+(?:defined\\s*\\(\\s*)?VERTEX\\s*\\)?\\s*$")) {
                return "VERTEX";
            }
            if (directive.matches("^" + Pattern.quote(prefix) + "\\s+(?:defined\\s*\\(\\s*)?FRAGMENT\\s*\\)?\\s*$")) {
                return "FRAGMENT";
            }
        }
        return null;
    }

    private static final class StageFrame {
        private final boolean parentActive;
        private final boolean stageConditional;
        private boolean matchedStage;

        private StageFrame(boolean parentActive, boolean matchedStage, boolean stageConditional) {
            this.parentActive = parentActive;
            this.matchedStage = matchedStage;
            this.stageConditional = stageConditional;
        }
    }

    private static FragmentOutputInfo fragmentOutputs(String body) {
        Matcher matcher = FRAGMENT_OUTPUT.matcher(body);
        int count = 0;
        String name = null;
        while (matcher.find()) {
            count++;
            if (name == null) {
                name = outputName(matcher.group());
            }
        }
        return new FragmentOutputInfo(count, name);
    }

    private static String outputName(String declaration) {
        Matcher matcher = OUTPUT_NAME.matcher(declaration);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private record FragmentOutputInfo(int count, String name) {
    }

    private static void appendBody(StringBuilder staged, String body, boolean needsFragColorOutput) {
        if (!needsFragColorOutput) {
            staged.append(body);
            return;
        }

        int insertionPoint = leadingExtensionBlockEnd(body);
        staged.append(body, 0, insertionPoint);
        staged.append("out vec4 FragColor;\n");
        staged.append(body.substring(insertionPoint));
    }

    private static int leadingExtensionBlockEnd(String body) {
        int index = 0;
        while (index < body.length()) {
            int lineEnd = body.indexOf('\n', index);
            int nextIndex = lineEnd < 0 ? body.length() : lineEnd + 1;
            int contentEnd = lineEnd < 0 ? body.length() : lineEnd;
            if (contentEnd > index && body.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }

            String line = body.substring(index, contentEnd).trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#define")
                    || line.startsWith("#extension")) {
                index = nextIndex;
                continue;
            }
            if (line.startsWith("/*")) {
                int commentStart = body.indexOf("/*", index);
                int commentEnd = body.indexOf("*/", commentStart + 2);
                if (commentEnd < 0) {
                    return body.length();
                }
                index = commentEnd + 2;
                if (index < body.length() && body.charAt(index) == '\r') {
                    index++;
                }
                if (index < body.length() && body.charAt(index) == '\n') {
                    index++;
                }
                continue;
            }
            break;
        }
        return index;
    }
}
