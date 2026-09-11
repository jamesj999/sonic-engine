package com.openggf.graphics.shaderlib;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DisplayShaderPresetLoader {
    private static final Pattern BIZHAWK_VERTEX = Pattern.compile("(?im)^\\s*#\\s*ifdef\\s+VERTEX\\b");
    private static final Pattern BIZHAWK_FRAGMENT = Pattern.compile("(?im)^\\s*#\\s*ifdef\\s+FRAGMENT\\b");
    private static final Pattern RETROARCH_VERTEX = Pattern.compile("(?im)^\\s*#\\s*if\\s+defined\\s*\\(\\s*VERTEX\\s*\\)");
    private static final Pattern RETROARCH_FRAGMENT = Pattern.compile("(?im)^\\s*#\\s*elif\\s+defined\\s*\\(\\s*FRAGMENT\\s*\\)");
    private static final Pattern UNDERSCORE_VERTEX = Pattern.compile("(?im)\\b__vertex__\\b");
    private static final Pattern UNDERSCORE_FRAGMENT = Pattern.compile("(?im)\\b__fragment__\\b");
    private static final Pattern COMPAT_VERTEX = Pattern.compile("(?im)\\bCOMPAT_VERTEX\\b");
    private static final Pattern COMPAT_FRAGMENT = Pattern.compile("(?im)\\bCOMPAT_FRAGMENT\\b");
    private static final Pattern PARAMETER_PRAGMA = Pattern.compile(
            "(?im)^\\s*#\\s*pragma\\s+parameter\\s+([A-Za-z_]\\w*)\\s+\"[^\"]*\"\\s+([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)\\b");
    private static final Pattern GLSLP_UNSUPPORTED_LINE = Pattern.compile(
            "(?im)^\\s*(?:textures?|texture\\d+|lut\\d*|history\\w*|feedback\\w*|previous\\w*|prev\\w*|preset|reference)\\s*=");
    private static final Pattern GLSLP_REFERENCE_DIRECTIVE = Pattern.compile("(?im)^\\s*#\\s*reference\\b");
    private static final Pattern GLSLP_RUNTIME_INPUT_SOURCE = Pattern.compile(
            "(?i)\\b(?:OriginalHistory\\d*|PassPrev\\d*|Prev|Feedback)\\b");
    private static final Pattern GLSLP_EXTERNAL_TEXTURE_SOURCE = Pattern.compile(
            "(?im)^\\s*uniform\\s+sampler(?:1D|2D|3D|Cube)\\s+\\w*(?:LUT|Lookup|External)\\w*\\s*(?:\\[[^\\]]+])?\\s*;");
    private static final List<String> SCALER_SEGMENTS = List.of(
            "scalenx", "scalehq", "xbr", "xbrz", "xsal", "xsoft", "hq2x", "hq4x", "scalefx", "omniscale");

    public DisplayShaderPreset load(DisplayShaderPresetRef ref, ShaderPhase defaultPhase)
            throws IOException, DisplayShaderLoadException {
        if (ref == null) {
            throw new DisplayShaderLoadException("Shader preset ref is required");
        }
        if (ref.kind() == DisplayShaderPresetRef.Kind.OFF) {
            return new DisplayShaderPreset(ref.label(), defaultPhase, List.of());
        }
        if (ref.absolutePath() == null) {
            throw new DisplayShaderLoadException("Shader preset has no source path");
        }

        ShaderPhase phase = forceScalerPhase(ref) ? ShaderPhase.SCENE : defaultPhase;
        return switch (ref.kind()) {
            case GLSL -> loadStandaloneGlsl(ref, phase);
            case CGP -> loadPreset(ref, phase, PresetFormat.CGP);
            case GLSLP -> loadPreset(ref, phase, PresetFormat.GLSLP);
            case OFF -> new DisplayShaderPreset(ref.label(), phase, List.of());
        };
    }

    private static DisplayShaderPreset loadStandaloneGlsl(DisplayShaderPresetRef ref, ShaderPhase phase)
            throws IOException, DisplayShaderLoadException {
        String source = readGlslSource(ref.absolutePath());
        DisplayShaderPass pass = passForSource(source, 1, 1, ScaleType.SOURCE, ScaleType.SOURCE,
                false, WrapMode.CLAMP_TO_EDGE, Map.of());
        return new DisplayShaderPreset(ref.label(), phase, List.of(pass));
    }

    private static DisplayShaderPreset loadPreset(DisplayShaderPresetRef ref, ShaderPhase phase, PresetFormat format)
            throws IOException, DisplayShaderLoadException {
        String presetText = readTextFile(ref.absolutePath());
        rejectUnsupportedPresetFeatures(presetText);

        Map<String, String> fields = parseFields(presetText);
        int passCount = parseRequiredPassCount(fields, ref);
        Path parent = ref.absolutePath().getParent();
        Path shaderRoot = deriveShaderRoot(ref);
        List<DisplayShaderPass> passes = new ArrayList<>(passCount);

        for (int i = 0; i < passCount; i++) {
            String shaderRef = fields.get("shader" + i);
            if (shaderRef == null || shaderRef.isBlank()) {
                throw new DisplayShaderLoadException("Preset is missing shader" + i + ": " + ref.label());
            }
            Path shaderPath = resolveShaderPath(parent, shaderRoot, shaderRef, format);
            String source = readGlslSource(shaderPath);
            if (format == PresetFormat.GLSLP) {
                rejectUnsupportedGlslpPassSourceFeatures(source, shaderPath);
            }
            PassScale passScale = parsePassScale(fields, i, passCount);
            passes.add(passForSource(source,
                    passScale.scaleX(),
                    passScale.scaleY(),
                    passScale.scaleTypeX(),
                    passScale.scaleTypeY(),
                    parseFilterLinear(fields.get("filter_linear" + i)),
                    parseWrapMode(fields.get("wrap_mode" + i)),
                    parseParameterValues(source, fields)));
        }

        return new DisplayShaderPreset(ref.label(), phase, List.copyOf(passes));
    }

    private static Map<String, String> parseFields(String presetText) {
        Map<String, String> fields = new HashMap<>();
        String[] lines = presetText.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = trimmed.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = stripOptionalQuotes(stripTrailingComment(trimmed.substring(equals + 1).trim()));
            fields.put(key, value);
        }
        return fields;
    }

    private static int parseRequiredPassCount(Map<String, String> fields, DisplayShaderPresetRef ref)
            throws DisplayShaderLoadException {
        String raw = fields.get("shaders");
        if (raw == null || raw.isBlank()) {
            throw new DisplayShaderLoadException("Preset is missing shaders count: " + ref.label());
        }
        try {
            int count = Integer.parseInt(raw);
            if (count < 1) {
                throw new DisplayShaderLoadException("Preset has no GLSL shader passes: " + ref.label());
            }
            return count;
        } catch (NumberFormatException e) {
            throw new DisplayShaderLoadException("Invalid shaders count in preset: " + raw, e);
        }
    }

    private static double parseScale(String raw) throws DisplayShaderLoadException {
        if (raw == null || raw.isBlank()) {
            return 1.0;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (!Double.isFinite(parsed) || parsed <= 0.0) {
                throw new NumberFormatException(raw);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new DisplayShaderLoadException("Invalid shader scale: " + raw, e);
        }
    }

    private static PassScale parsePassScale(Map<String, String> fields, int passIndex, int passCount)
            throws DisplayShaderLoadException {
        String suffix = Integer.toString(passIndex);
        String scale = fields.get("scale" + suffix);
        String scaleX = fields.get("scale_x" + suffix);
        String scaleY = fields.get("scale_y" + suffix);
        String scaleType = fields.get("scale_type" + suffix);
        String scaleTypeX = fields.get("scale_type_x" + suffix);
        String scaleTypeY = fields.get("scale_type_y" + suffix);

        boolean hasUniformScale = scale != null && !scale.isBlank();
        boolean hasAnyScaleOption = hasUniformScale
                || hasValue(scaleX)
                || hasValue(scaleY)
                || hasValue(scaleType)
                || hasValue(scaleTypeX)
                || hasValue(scaleTypeY);
        boolean implicitFinalViewport = passIndex == passCount - 1 && !hasAnyScaleOption;

        double parsedScaleX = hasUniformScale ? parseScale(scale) : parseScale(scaleX);
        double parsedScaleY = hasUniformScale ? parsedScaleX : parseScale(scaleY);
        ScaleType parsedTypeX;
        ScaleType parsedTypeY;
        if (hasValue(scaleType)) {
            parsedTypeX = parseScaleType(scaleType);
            parsedTypeY = parsedTypeX;
        } else if (implicitFinalViewport) {
            parsedTypeX = ScaleType.VIEWPORT;
            parsedTypeY = ScaleType.VIEWPORT;
        } else {
            parsedTypeX = parseScaleType(scaleTypeX);
            parsedTypeY = parseScaleType(scaleTypeY);
        }

        return new PassScale(parsedScaleX, parsedScaleY, parsedTypeX, parsedTypeY);
    }

    private static boolean hasValue(String raw) {
        return raw != null && !raw.isBlank();
    }

    private static ScaleType parseScaleType(String raw) throws DisplayShaderLoadException {
        if (raw == null || raw.isBlank()) {
            return ScaleType.SOURCE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "source" -> ScaleType.SOURCE;
            case "viewport" -> ScaleType.VIEWPORT;
            case "absolute" -> throw new DisplayShaderLoadException("Absolute shader scale_type is not supported");
            default -> throw new DisplayShaderLoadException("Unsupported shader scale_type: " + raw);
        };
    }

    private static boolean parseFilterLinear(String raw) {
        return raw != null && Boolean.parseBoolean(raw.trim());
    }

    private static WrapMode parseWrapMode(String raw) throws DisplayShaderLoadException {
        if (raw == null || raw.isBlank()) {
            return WrapMode.CLAMP_TO_EDGE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "clamp_to_edge", "clamp_to_border" -> WrapMode.CLAMP_TO_EDGE;
            case "repeat" -> WrapMode.REPEAT;
            case "mirrored_repeat" -> WrapMode.MIRRORED_REPEAT;
            default -> throw new DisplayShaderLoadException("Unsupported shader wrap_mode: " + raw);
        };
    }

    private static Path resolveShaderPath(Path parent, Path shaderRoot, String rawShaderRef, PresetFormat format)
            throws DisplayShaderLoadException {
        Path relative = parseShaderReferencePath(rawShaderRef);
        if (relative.isAbsolute() || relative.getRoot() != null) {
            throw new DisplayShaderLoadException("Absolute or rooted shader paths are not supported: " + rawShaderRef);
        }

        if (format == PresetFormat.GLSLP) {
            if (!".glsl".equals(extension(relative))) {
                throw new DisplayShaderLoadException("GLSLP pass source must reference a .glsl file: " + rawShaderRef);
            }
            Path exact = resolveWithinShaderRoot(parent, shaderRoot, relative, rawShaderRef);
            if (!Files.isRegularFile(exact)) {
                throw new DisplayShaderLoadException("Missing GLSL shader source: " + rawShaderRef);
            }
            return exact;
        }

        if (".glsl".equals(extension(relative))) {
            Path exact = resolveWithinShaderRoot(parent, shaderRoot, relative, rawShaderRef);
            if (Files.isRegularFile(exact)) {
                return exact;
            }
        }

        Path sibling = resolveWithinShaderRoot(parent, shaderRoot, replaceExtension(relative, ".glsl"), rawShaderRef);
        if (Files.isRegularFile(sibling)) {
            return sibling;
        }
        throw new DisplayShaderLoadException("CGP preset has no loadable GLSL source for " + rawShaderRef);
    }

    private static Path parseShaderReferencePath(String rawShaderRef) throws DisplayShaderLoadException {
        try {
            return Path.of(rawShaderRef.replace('\\', '/'));
        } catch (InvalidPathException e) {
            throw new DisplayShaderLoadException("Invalid shader path reference: " + rawShaderRef, e);
        }
    }

    private static Path resolveWithinShaderRoot(Path parent, Path shaderRoot, Path relative, String rawShaderRef)
            throws DisplayShaderLoadException {
        Path resolved = parent.resolve(relative).toAbsolutePath().normalize();
        Path normalizedRoot = shaderRoot.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new DisplayShaderLoadException("Shader path escapes shader library root: " + rawShaderRef);
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new DisplayShaderLoadException("Shader source symbolic links are not supported: "
                    + resolved.getFileName());
        }
        if (Files.exists(resolved)) {
            try {
                Path rootReal = normalizedRoot.toRealPath();
                Path resolvedReal = resolved.toRealPath();
                if (!resolvedReal.startsWith(rootReal)) {
                    throw new DisplayShaderLoadException("Shader path escapes shader library root: " + rawShaderRef);
                }
            } catch (IOException e) {
                throw new DisplayShaderLoadException("Unable to resolve shader source path: " + rawShaderRef, e);
            }
        }
        return resolved;
    }

    private static Path deriveShaderRoot(DisplayShaderPresetRef ref) {
        Path presetPath = ref.absolutePath().toAbsolutePath().normalize();
        Path fallback = presetPath.getParent();
        String relativePath = ref.relativePath();
        if (relativePath == null || relativePath.isBlank()) {
            return fallbackShaderRoot(presetPath, fallback);
        }

        try {
            Path relative = Path.of(relativePath.replace('\\', '/'));
            if (relative.isAbsolute()) {
                return fallbackShaderRoot(presetPath, fallback);
            }
            Path root = presetPath;
            for (int i = 0; i < relative.getNameCount(); i++) {
                root = root.getParent();
                if (root == null) {
                    return fallbackShaderRoot(presetPath, fallback);
                }
            }
            Path reconstructedPreset = root.resolve(relative).toAbsolutePath().normalize();
            return reconstructedPreset.equals(presetPath) ? root : fallbackShaderRoot(presetPath, fallback);
        } catch (InvalidPathException e) {
            return fallbackShaderRoot(presetPath, fallback);
        }
    }

    private static Path fallbackShaderRoot(Path presetPath, Path fallback) {
        return fallback == null ? presetPath : fallback;
    }

    private static DisplayShaderPass passForSource(String source, double scaleX, double scaleY,
                                                   ScaleType scaleTypeX, ScaleType scaleTypeY,
                                                   boolean filterLinear, WrapMode wrapMode,
                                                   Map<String, Float> parameterValues) {
        GlslShape shape = detectShape(source);
        String vertexSource = shape == GlslShape.COMBINED ? source : null;
        return new DisplayShaderPass(vertexSource, source, shape, scaleX, scaleY, scaleTypeX, scaleTypeY,
                filterLinear, wrapMode, parameterValues);
    }

    private static Map<String, Float> parseParameterValues(String source, Map<String, String> fields)
            throws DisplayShaderLoadException {
        Map<String, Float> values = new LinkedHashMap<>();
        Matcher matcher = PARAMETER_PRAGMA.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            float value = parseParameterValue(name, matcher.group(2));
            String override = fields.get(name.toLowerCase(Locale.ROOT));
            if (override != null && !override.isBlank()) {
                value = parseParameterValue(name, override);
            }
            values.put(name, value);
        }
        return values;
    }

    private static float parseParameterValue(String name, String raw) throws DisplayShaderLoadException {
        try {
            float parsed = Float.parseFloat(raw.trim());
            if (!Float.isFinite(parsed)) {
                throw new NumberFormatException(raw);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new DisplayShaderLoadException("Invalid shader parameter value for " + name + ": " + raw, e);
        }
    }

    private static GlslShape detectShape(String source) {
        if ((BIZHAWK_VERTEX.matcher(source).find() && BIZHAWK_FRAGMENT.matcher(source).find())
                || (RETROARCH_VERTEX.matcher(source).find() && RETROARCH_FRAGMENT.matcher(source).find())
                || (UNDERSCORE_VERTEX.matcher(source).find() && UNDERSCORE_FRAGMENT.matcher(source).find())
                || (COMPAT_VERTEX.matcher(source).find() && COMPAT_FRAGMENT.matcher(source).find())) {
            return GlslShape.COMBINED;
        }
        return GlslShape.FRAGMENT_ONLY;
    }

    private static String readGlslSource(Path path) throws IOException, DisplayShaderLoadException {
        if (Files.isSymbolicLink(path)) {
            throw new DisplayShaderLoadException("Shader source symbolic links are not supported: "
                    + path.getFileName());
        }
        String source = readTextFile(path);
        if (containsInclude(source)) {
            throw new DisplayShaderLoadException("Shader includes are not supported: " + path.getFileName());
        }
        return source;
    }

    private static String readTextFile(Path path) throws IOException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return Files.readString(path, StandardCharsets.ISO_8859_1);
        }
    }

    private static void rejectUnsupportedPresetFeatures(String presetText) throws DisplayShaderLoadException {
        if (containsInclude(presetText)) {
            throw new DisplayShaderLoadException("Shader preset includes are not supported");
        }
        if (GLSLP_REFERENCE_DIRECTIVE.matcher(presetText).find()) {
            throw new DisplayShaderLoadException("Shader preset inheritance is not supported");
        }
        Matcher matcher = GLSLP_UNSUPPORTED_LINE.matcher(presetText);
        if (matcher.find()) {
            throw new DisplayShaderLoadException("Shader preset uses unsupported multi-pass external state: "
                    + matcher.group().trim());
        }
    }

    static boolean supportsPresetFeaturesForDiscovery(String presetText) {
        try {
            rejectUnsupportedPresetFeatures(presetText);
            return true;
        } catch (DisplayShaderLoadException ignored) {
            return false;
        }
    }

    private static void rejectUnsupportedGlslpPassSourceFeatures(String source, Path path)
            throws DisplayShaderLoadException {
        if (GLSLP_RUNTIME_INPUT_SOURCE.matcher(source).find()) {
            throw new DisplayShaderLoadException("GLSLP pass source uses unsupported runtime input: "
                    + path.getFileName());
        }
        if (GLSLP_EXTERNAL_TEXTURE_SOURCE.matcher(source).find()) {
            throw new DisplayShaderLoadException("GLSLP pass source uses unsupported external texture sampler: "
                    + path.getFileName());
        }
    }

    private static boolean containsInclude(String text) {
        return Pattern.compile("(?im)^\\s*#\\s*include\\b").matcher(text).find();
    }

    private static boolean forceScalerPhase(DisplayShaderPresetRef ref) {
        String relativePath = ref.relativePath();
        if (relativePath == null) {
            return false;
        }
        for (String segment : relativePath.replace('\\', '/').split("/")) {
            String normalized = stripExtension(segment).toLowerCase(Locale.ROOT);
            if (SCALER_SEGMENTS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingComment(String value) {
        int comment = commentIndex(value);
        if (comment >= 0) {
            return value.substring(0, comment).trim();
        }
        return value;
    }

    private static int commentIndex(String value) {
        boolean quoted = false;
        char quoteChar = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || value.charAt(i - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quoteChar = c;
                } else if (quoteChar == c) {
                    quoted = false;
                }
                continue;
            }
            if (!quoted && (c == '#' || c == ';')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static Path replaceExtension(Path path, String newExtension) {
        Path fileNamePath = path.getFileName();
        String fileName = fileNamePath == null ? "" : fileNamePath.toString();
        String baseName = stripExtension(fileName);
        Path replacement = Path.of(baseName + newExtension);
        Path parent = path.getParent();
        return parent == null ? replacement : parent.resolve(replacement);
    }

    private static String extension(Path path) {
        Path fileNamePath = path.getFileName();
        String fileName = fileNamePath == null ? "" : fileNamePath.toString().toLowerCase(Locale.ROOT);
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private enum PresetFormat {
        CGP,
        GLSLP
    }

    private record PassScale(double scaleX, double scaleY, ScaleType scaleTypeX, ScaleType scaleTypeY) {
    }
}
