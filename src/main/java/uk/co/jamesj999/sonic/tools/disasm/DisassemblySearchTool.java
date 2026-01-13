package uk.co.jamesj999.sonic.tools.disasm;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Tool for searching the Sonic 2 disassembly (s2disasm) for items by label name or file name.
 * Parses BINCLUDE directives and returns information about matching entries.
 */
public class DisassemblySearchTool {

    private static final Pattern BINCLUDE_PATTERN = Pattern.compile(
            "^\\s*(\\w+):\\s*(?:BINCLUDE|binclude)\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BINCLUDE_NO_LABEL_PATTERN = Pattern.compile(
            "^\\s*(?:BINCLUDE|binclude)\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    private final Path disasmRoot;

    public DisassemblySearchTool(Path disasmRoot) {
        this.disasmRoot = disasmRoot;
    }

    public DisassemblySearchTool(String disasmRootPath) {
        this(Path.of(disasmRootPath));
    }

    /**
     * Search for items by label name (case-insensitive partial match).
     */
    public List<DisassemblySearchResult> searchByLabel(String labelPattern) throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();
        String lowerPattern = labelPattern.toLowerCase();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFile(asmFile, results, lowerPattern, true);
        }

        return results;
    }

    /**
     * Search for items by file name (case-insensitive partial match).
     */
    public List<DisassemblySearchResult> searchByFileName(String fileNamePattern) throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();
        String lowerPattern = fileNamePattern.toLowerCase();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFile(asmFile, results, lowerPattern, false);
        }

        return results;
    }

    /**
     * Search for items matching a general pattern (matches both label and file name).
     */
    public List<DisassemblySearchResult> search(String pattern) throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();
        String lowerPattern = pattern.toLowerCase();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFileBoth(asmFile, results, lowerPattern);
        }

        return results;
    }

    /**
     * Search for files by compression type.
     */
    public List<DisassemblySearchResult> searchByCompressionType(CompressionType type) throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFileByCompression(asmFile, results, type);
        }

        return results;
    }

    /**
     * List all binary includes in the disassembly.
     */
    public List<DisassemblySearchResult> listAllIncludes() throws IOException {
        List<DisassemblySearchResult> results = new ArrayList<>();

        for (Path asmFile : findAsmFiles()) {
            searchAsmFileBoth(asmFile, results, "");
        }

        return results;
    }

    /**
     * Find a file in the disassembly by exact path.
     */
    public Path resolveFilePath(String relativePath) {
        return disasmRoot.resolve(relativePath);
    }

    /**
     * Get the file size of a disassembly file.
     */
    public long getFileSize(String relativePath) throws IOException {
        Path path = resolveFilePath(relativePath);
        if (Files.exists(path)) {
            return Files.size(path);
        }
        return -1;
    }

    /**
     * Read bytes from a disassembly file.
     */
    public byte[] readFileBytes(String relativePath) throws IOException {
        return Files.readAllBytes(resolveFilePath(relativePath));
    }

    private List<Path> findAsmFiles() throws IOException {
        List<Path> asmFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(disasmRoot)) {
            walk.filter(p -> p.toString().endsWith(".asm"))
                .forEach(asmFiles::add);
        }
        return asmFiles;
    }

    private void searchAsmFile(Path asmFile, List<DisassemblySearchResult> results,
                                String pattern, boolean matchLabel) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(asmFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher matcher = BINCLUDE_PATTERN.matcher(line);
                if (matcher.find()) {
                    String label = matcher.group(1);
                    String filePath = matcher.group(2);
                    String matchTarget = matchLabel ? label.toLowerCase() : filePath.toLowerCase();

                    if (matchTarget.contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                CompressionType.fromExtension(filePath),
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                    }
                } else {
                    Matcher noLabelMatcher = BINCLUDE_NO_LABEL_PATTERN.matcher(line);
                    if (noLabelMatcher.find()) {
                        String filePath = noLabelMatcher.group(1);
                        if (!matchLabel && filePath.toLowerCase().contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    null,
                                    filePath,
                                    CompressionType.fromExtension(filePath),
                                    disasmRoot.relativize(asmFile).toString(),
                                    lineNumber,
                                    line.trim()
                            ));
                        }
                    }
                }
            }
        }
    }

    private void searchAsmFileBoth(Path asmFile, List<DisassemblySearchResult> results,
                                    String pattern) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(asmFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher matcher = BINCLUDE_PATTERN.matcher(line);
                if (matcher.find()) {
                    String label = matcher.group(1);
                    String filePath = matcher.group(2);

                    if (pattern.isEmpty() ||
                        label.toLowerCase().contains(pattern) ||
                        filePath.toLowerCase().contains(pattern)) {
                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                CompressionType.fromExtension(filePath),
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                    }
                } else {
                    Matcher noLabelMatcher = BINCLUDE_NO_LABEL_PATTERN.matcher(line);
                    if (noLabelMatcher.find()) {
                        String filePath = noLabelMatcher.group(1);
                        if (pattern.isEmpty() || filePath.toLowerCase().contains(pattern)) {
                            results.add(new DisassemblySearchResult(
                                    null,
                                    filePath,
                                    CompressionType.fromExtension(filePath),
                                    disasmRoot.relativize(asmFile).toString(),
                                    lineNumber,
                                    line.trim()
                            ));
                        }
                    }
                }
            }
        }
    }

    private void searchAsmFileByCompression(Path asmFile, List<DisassemblySearchResult> results,
                                             CompressionType type) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(asmFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                Matcher matcher = BINCLUDE_PATTERN.matcher(line);
                if (matcher.find()) {
                    String label = matcher.group(1);
                    String filePath = matcher.group(2);
                    CompressionType fileType = CompressionType.fromExtension(filePath);

                    if (fileType == type) {
                        results.add(new DisassemblySearchResult(
                                label,
                                filePath,
                                fileType,
                                disasmRoot.relativize(asmFile).toString(),
                                lineNumber,
                                line.trim()
                        ));
                    }
                } else {
                    Matcher noLabelMatcher = BINCLUDE_NO_LABEL_PATTERN.matcher(line);
                    if (noLabelMatcher.find()) {
                        String filePath = noLabelMatcher.group(1);
                        CompressionType fileType = CompressionType.fromExtension(filePath);

                        if (fileType == type) {
                            results.add(new DisassemblySearchResult(
                                    null,
                                    filePath,
                                    fileType,
                                    disasmRoot.relativize(asmFile).toString(),
                                    lineNumber,
                                    line.trim()
                            ));
                        }
                    }
                }
            }
        }
    }
}
