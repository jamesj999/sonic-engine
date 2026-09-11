package com.openggf.graphics.shaderlib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class DisplayShaderLibrary {
    private static final Pattern SHADER_REF_PATTERN = Pattern.compile("^\\s*shader\\d+\\s*=\\s*(.+?)\\s*$");
    private static final Comparator<DisplayShaderPresetRef> ENTRY_ORDER =
            Comparator.comparing(DisplayShaderPresetRef::relativePath, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DisplayShaderPresetRef::relativePath);

    private final List<DisplayShaderPresetRef> entries;

    private DisplayShaderLibrary(List<DisplayShaderPresetRef> entries) {
        this.entries = List.copyOf(entries);
    }

    public static DisplayShaderLibrary scan(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) {
            return offOnly();
        }

        List<Path> discoveredFiles = new ArrayList<>();
        Set<Path> referencedShaderPasses = new HashSet<>();
        try (Stream<Path> walk = Files.walk(normalizedRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> isUnderRoot(normalizedRoot, path))
                    .filter(path -> !hasHiddenRelativeDirectory(normalizedRoot, path))
                    .forEach(path -> {
                        Path normalizedPath = path.toAbsolutePath().normalize();
                        if (isPresetFile(normalizedPath)) {
                            referencedShaderPasses.addAll(readReferencedShaderPasses(normalizedRoot, normalizedPath));
                        }
                        if (isUnsupportedPresetForDiscovery(normalizedPath)) {
                            return;
                        }
                        discoveredFiles.add(normalizedPath);
                    });
        } catch (IOException ignored) {
            return offOnly();
        }

        List<DisplayShaderPresetRef> selectableEntries = new ArrayList<>();
        for (Path path : discoveredFiles) {
            DisplayShaderPresetRef.Kind kind = kindFor(path);
            if (kind == null) {
                continue;
            }
            if (kind == DisplayShaderPresetRef.Kind.GLSL
                    && (referencedShaderPasses.contains(path) || hasImplementationSegment(normalizedRoot, path))) {
                continue;
            }
            selectableEntries.add(new DisplayShaderPresetRef(kind, relativePath(normalizedRoot, path), path));
        }

        selectableEntries.sort(DisplayShaderLibrary::compareEntriesForScanOrder);

        List<DisplayShaderPresetRef> entries = new ArrayList<>();
        entries.add(DisplayShaderPresetRef.OFF);
        entries.addAll(selectableEntries);
        return new DisplayShaderLibrary(entries);
    }

    public List<DisplayShaderPresetRef> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public DisplayShaderPresetRef at(int index) {
        return entries.get(index);
    }

    public int indexOfRelativePath(String selection) {
        String normalizedSelection = normalizeSelection(selection);
        if (normalizedSelection == null) {
            return 0;
        }
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).relativePath().equalsIgnoreCase(normalizedSelection)) {
                return i;
            }
        }
        return 0;
    }

    static int compareEntriesForScanOrder(DisplayShaderPresetRef left, DisplayShaderPresetRef right) {
        return ENTRY_ORDER.compare(left, right);
    }

    private static DisplayShaderLibrary offOnly() {
        return new DisplayShaderLibrary(List.of(DisplayShaderPresetRef.OFF));
    }

    private static Path normalizeRoot(Path root) {
        if (root == null) {
            return null;
        }
        return root.normalize().toAbsolutePath().normalize();
    }

    private static boolean isUnderRoot(Path root, Path path) {
        return path.toAbsolutePath().normalize().startsWith(root);
    }

    private static boolean hasHiddenRelativeDirectory(Path root, Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        Path current = root;
        int directoryNameCount = Math.max(0, relative.getNameCount() - 1);
        for (int i = 0; i < directoryNameCount; i++) {
            Path segment = relative.getName(i);
            current = current.resolve(segment);
            if (segment.toString().startsWith(".") || isHidden(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isPresetFile(Path path) {
        DisplayShaderPresetRef.Kind kind = kindFor(path);
        return kind == DisplayShaderPresetRef.Kind.CGP || kind == DisplayShaderPresetRef.Kind.GLSLP;
    }

    private static boolean isUnsupportedPresetForDiscovery(Path path) {
        if (!isPresetFile(path)) {
            return false;
        }
        try {
            return !DisplayShaderPresetLoader.supportsPresetFeaturesForDiscovery(Files.readString(path));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static DisplayShaderPresetRef.Kind kindFor(Path path) {
        String extension = extension(path);
        return switch (extension) {
            case ".cgp" -> DisplayShaderPresetRef.Kind.CGP;
            case ".glslp" -> DisplayShaderPresetRef.Kind.GLSLP;
            case ".glsl" -> DisplayShaderPresetRef.Kind.GLSL;
            default -> null;
        };
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return name.substring(dot);
    }

    private static Set<Path> readReferencedShaderPasses(Path root, Path preset) {
        Set<Path> refs = new HashSet<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(preset);
        } catch (IOException ignored) {
            return refs;
        }

        Path parent = preset.getParent();
        for (String line : lines) {
            Matcher matcher = SHADER_REF_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String rawRef = normalizePresetReference(matcher.group(1).trim());
            if (rawRef.isBlank()) {
                continue;
            }
            Path resolved;
            try {
                resolved = parent.resolve(rawRef).toAbsolutePath().normalize();
            } catch (InvalidPathException ignored) {
                continue;
            }
            if (!resolved.startsWith(root)) {
                continue;
            }
            String extension = extension(resolved);
            if (".glsl".equals(extension)) {
                refs.add(resolved);
            } else if (".cg".equals(extension)) {
                refs.add(replaceExtension(resolved, ".glsl"));
            }
        }
        return refs;
    }

    private static Path replaceExtension(Path path, String replacementExtension) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot < 0 ? fileName : fileName.substring(0, dot);
        return path.resolveSibling(baseName + replacementExtension).toAbsolutePath().normalize();
    }

    private static String normalizePresetReference(String value) {
        if (value.isBlank()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '"' || first == '\'') {
            int closingQuote = value.indexOf(first, 1);
            if (closingQuote > 1) {
                return value.substring(1, closingQuote).trim();
            }
        }
        int commentIndex = value.indexOf('#');
        String withoutComment = commentIndex >= 0 ? value.substring(0, commentIndex) : value;
        return withoutComment.trim();
    }

    private static boolean hasImplementationSegment(Path root, Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        int directoryNameCount = Math.max(0, relative.getNameCount() - 1);
        for (int i = 0; i < directoryNameCount; i++) {
            String segment = relative.getName(i).toString();
            if ("shaders".equalsIgnoreCase(segment) || "resources".equalsIgnoreCase(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String relativePath(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String normalizeSelection(String selection) {
        if (selection == null) {
            return null;
        }
        String trimmed = selection.trim();
        if (trimmed.isEmpty() || "OFF".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            Path normalizedPath = Path.of(trimmed.replace('\\', '/')).normalize();
            if (normalizedPath.isAbsolute() || normalizedPath.startsWith("..") || normalizedPath.getNameCount() == 0) {
                return null;
            }
            return normalizedPath.toString().replace('\\', '/');
        } catch (InvalidPathException ignored) {
            return null;
        }
    }
}
