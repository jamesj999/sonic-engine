package com.openggf.graphics.shaderlib;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class DisplayShaderSelectionModel {
    private static final String OFF_CATEGORY = "System";

    private final List<SelectionItem> items;
    private List<SelectionItem> visibleItems;
    private String currentFolder = "";

    public DisplayShaderSelectionModel(DisplayShaderLibrary library) {
        this(Objects.requireNonNull(library, "library").entries());
    }

    public DisplayShaderSelectionModel(List<DisplayShaderPresetRef> entries) {
        Objects.requireNonNull(entries, "entries");
        this.items = entries.stream()
                .map(DisplayShaderSelectionModel::toSelectionItem)
                .toList();
        this.visibleItems = items;
    }

    public List<SelectionItem> filter(String query) {
        String normalizedQuery = normalizeQuery(query);

        List<SelectionItem> filtered = new ArrayList<>();
        if (currentFolder.isEmpty()) {
            addIfMatches(filtered, offItem(), normalizedQuery);
        } else {
            addIfMatches(filtered, SelectionItem.parent(currentFolder), normalizedQuery);
        }

        List<SelectionItem> folders = immediateFolders(normalizedQuery);
        List<SelectionItem> shaders = immediateShaders(normalizedQuery);
        filtered.addAll(folders);
        filtered.addAll(shaders);
        visibleItems = List.copyOf(filtered);
        return visibleItems;
    }

    public String currentFolder() {
        return currentFolder;
    }

    public void enterFolder(String folderPath) {
        String normalized = normalizeFolder(folderPath);
        if (!normalized.isEmpty()) {
            currentFolder = normalized.contains("/") || currentFolder.isEmpty()
                    ? normalized
                    : currentFolder + "/" + normalized;
        }
    }

    public void enterParentFolder() {
        if (currentFolder.isEmpty()) {
            return;
        }
        int slash = currentFolder.lastIndexOf('/');
        currentFolder = slash < 0 ? "" : currentFolder.substring(0, slash);
    }

    public void showFolderFor(DisplayShaderPresetRef ref) {
        if (ref == null || ref.kind() == DisplayShaderPresetRef.Kind.OFF || ref.relativePath() == null) {
            currentFolder = "";
            return;
        }
        String path = ref.relativePath().replace('\\', '/');
        int slash = path.lastIndexOf('/');
        currentFolder = slash < 0 ? "" : path.substring(0, slash);
    }

    private List<SelectionItem> immediateFolders(String normalizedQuery) {
        Set<String> folderPaths = new LinkedHashSet<>();
        for (SelectionItem item : items) {
            if (item.ref().kind() == DisplayShaderPresetRef.Kind.OFF) {
                continue;
            }
            String relativePath = item.ref().relativePath().replace('\\', '/');
            if (!isInsideCurrentFolder(relativePath)) {
                continue;
            }
            String remainder = remainderInCurrentFolder(relativePath);
            int slash = remainder.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String childName = remainder.substring(0, slash);
            String childPath = currentFolder.isEmpty() ? childName : currentFolder + "/" + childName;
            folderPaths.add(childPath);
        }
        return folderPaths.stream()
                .map(SelectionItem::folder)
                .filter(item -> matchesRow(item, normalizedQuery))
                .sorted(Comparator.comparing(SelectionItem::displayPath, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SelectionItem::displayPath))
                .toList();
    }

    private List<SelectionItem> immediateShaders(String normalizedQuery) {
        List<SelectionItem> shaders = new ArrayList<>();
        for (SelectionItem item : items) {
            if (item.ref().kind() == DisplayShaderPresetRef.Kind.OFF
                    || !isInsideCurrentFolder(item.ref().relativePath())) {
                continue;
            }
            String remainder = remainderInCurrentFolder(item.ref().relativePath());
            if (remainder.contains("/")) {
                continue;
            }
            SelectionItem row = SelectionItem.shader(item.ref(), item.category(), basename(item.ref()));
            addIfMatches(shaders, row, normalizedQuery);
        }
        return shaders;
    }

    private boolean isInsideCurrentFolder(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return currentFolder.isEmpty()
                || normalized.equals(currentFolder)
                || normalized.startsWith(currentFolder + "/");
    }

    private String remainderInCurrentFolder(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        return currentFolder.isEmpty() ? normalized : normalized.substring(currentFolder.length() + 1);
    }

    private static void addIfMatches(List<SelectionItem> rows, SelectionItem item, String normalizedQuery) {
        if (matchesRow(item, normalizedQuery)) {
            rows.add(item);
        }
    }

    private static boolean matchesRow(SelectionItem item, String normalizedQuery) {
        return normalizedQuery.isEmpty()
                || item.parentDirectory()
                || (item.ref() != null && item.ref().kind() == DisplayShaderPresetRef.Kind.OFF)
                || matches(item.displayPath(), normalizedQuery)
                || matches(item.category(), normalizedQuery);
    }

    public List<String> categories() {
        Set<String> categories = new LinkedHashSet<>();
        for (SelectionItem item : items) {
            String category = item.category();
            if (!category.isBlank() && item.ref().kind() != DisplayShaderPresetRef.Kind.OFF) {
                categories.add(category);
            }
        }
        return List.copyOf(categories);
    }

    public DisplayShaderPresetRef select(int visibleIndex) {
        Objects.checkIndex(visibleIndex, visibleItems.size());
        return visibleItems.get(visibleIndex).ref();
    }

    private static SelectionItem toSelectionItem(DisplayShaderPresetRef ref) {
        if (ref.kind() == DisplayShaderPresetRef.Kind.OFF) {
            return SelectionItem.shader(ref, OFF_CATEGORY, ref.label());
        }
        String displayPath = ref.relativePath().replace('\\', '/');
        return SelectionItem.shader(ref, categoryFor(displayPath), displayPath);
    }

    private static SelectionItem offItem() {
        return SelectionItem.shader(DisplayShaderPresetRef.OFF, OFF_CATEGORY, DisplayShaderPresetRef.OFF.label());
    }

    private static String categoryFor(String displayPath) {
        String[] segments = displayPath.split("/");
        int start = startAfterKnownInstallRoot(segments);
        int directoryLimit = Math.max(0, segments.length - 1);
        for (int i = start; i < directoryLimit; i++) {
            String segment = segments[i];
            if (!segment.isBlank() && !isImplementationSegment(segment)) {
                return segment;
            }
        }
        return "";
    }

    private static int startAfterKnownInstallRoot(String[] segments) {
        if (segments.length == 0) {
            return 0;
        }
        if ("libretro-glsl".equalsIgnoreCase(segments[0])) {
            return 1;
        }
        if (segments.length >= 2
                && "RetroArch".equalsIgnoreCase(segments[0])
                && "shaders_glsl".equalsIgnoreCase(segments[1])) {
            return 2;
        }
        return 0;
    }

    private static boolean isImplementationSegment(String segment) {
        return "shaders".equalsIgnoreCase(segment) || "resources".equalsIgnoreCase(segment);
    }

    private static boolean matches(String value, String normalizedQuery) {
        return value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeFolder(String folderPath) {
        if (folderPath == null) {
            return "";
        }
        String normalized = folderPath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String basename(DisplayShaderPresetRef ref) {
        if (ref.kind() == DisplayShaderPresetRef.Kind.OFF) {
            return ref.label();
        }
        String path = ref.relativePath().replace('\\', '/');
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public record SelectionItem(
            DisplayShaderPresetRef ref,
            String category,
            String displayPath,
            boolean directory,
            boolean parentDirectory,
            String folderPath) {

        static SelectionItem shader(DisplayShaderPresetRef ref, String category, String displayPath) {
            return new SelectionItem(ref, category, displayPath, false, false, null);
        }

        static SelectionItem folder(String folderPath) {
            String normalized = normalizeFolder(folderPath);
            String name = basename(normalized);
            return new SelectionItem(null, name, name + "/", true, false, normalized);
        }

        static SelectionItem parent(String currentFolder) {
            return new SelectionItem(null, "", "..", true, true, parentFolder(currentFolder));
        }

        private static String basename(String folderPath) {
            int slash = folderPath.lastIndexOf('/');
            return slash < 0 ? folderPath : folderPath.substring(slash + 1);
        }

        private static String parentFolder(String folderPath) {
            String normalized = normalizeFolder(folderPath);
            int slash = normalized.lastIndexOf('/');
            return slash < 0 ? "" : normalized.substring(0, slash);
        }
    }
}
