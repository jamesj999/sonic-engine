package com.openggf.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ModApiReleasePolicy {
    static final int SCHEMA_VERSION = 1;
    static final Set<String> BRANCHES = Set.of("master", "develop", "next");
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "schemaVersion", "targetBranch", "masterLine", "developLine", "nextLine",
            "currentApi", "currentStatus", "publishedBaselines");

    enum Status { CANDIDATE, PUBLISHED }

    record ReleaseLine(int major, int minor) implements Comparable<ReleaseLine> {
        ReleaseLine {
            if (major < 0 || minor < 0) {
                throw new IllegalArgumentException("Release-line components must be nonnegative");
            }
        }

        static ReleaseLine parse(String key, String value) {
            if (!value.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) {
                throw invalid(key, value, "use canonical MAJOR.MINOR form");
            }
            try {
                String[] parts = value.split("\\.", -1);
                return new ReleaseLine(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (NumberFormatException ex) {
                throw invalid(key, value, "use components no larger than Integer.MAX_VALUE");
            }
        }

        static ReleaseLine of(SemanticVersion version) {
            return new ReleaseLine(version.major(), version.minor());
        }

        @Override
        public int compareTo(ReleaseLine other) {
            int majorResult = Integer.compare(major, other.major);
            return majorResult != 0 ? majorResult : Integer.compare(minor, other.minor);
        }

        @Override
        public String toString() {
            return major + "." + minor;
        }
    }

    private final String targetBranch;
    private final ReleaseLine masterLine;
    private final ReleaseLine developLine;
    private final ReleaseLine nextLine;
    private final SemanticVersion currentApi;
    private final Status currentStatus;
    private final List<SemanticVersion> publishedBaselines;
    private final Map<String, SemanticVersion> expectedPins;

    private ModApiReleasePolicy(String targetBranch, ReleaseLine masterLine,
            ReleaseLine developLine, ReleaseLine nextLine, SemanticVersion currentApi,
            Status currentStatus, List<SemanticVersion> publishedBaselines) {
        this.targetBranch = targetBranch;
        this.masterLine = masterLine;
        this.developLine = developLine;
        this.nextLine = nextLine;
        this.currentApi = currentApi;
        this.currentStatus = currentStatus;
        this.publishedBaselines = List.copyOf(publishedBaselines);
        LinkedHashMap<String, SemanticVersion> pins = new LinkedHashMap<>();
        for (SemanticVersion baseline : publishedBaselines) {
            addPin(pins, publishedPin(baseline), baseline);
        }
        String currentPin = currentStatus == Status.CANDIDATE
                ? candidatePin(currentApi) : publishedPin(currentApi);
        addPin(pins, currentPin, currentApi);
        this.expectedPins = Collections.unmodifiableMap(pins);
    }

    static ModApiReleasePolicy read(Path path) throws IOException {
        return parse(Files.readAllLines(path));
    }

    static ModApiReleasePolicy parse(String text) {
        return parse(text.lines().toList());
    }

    static ModApiReleasePolicy parse(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isEmpty()) continue;
            int equals = line.indexOf('=');
            if (equals < 1) {
                throw new IllegalArgumentException("Line " + (index + 1)
                        + " must use exact key=value form");
            }
            String key = line.substring(0, equals);
            String value = line.substring(equals + 1);
            if (!key.equals(key.trim())) {
                throw invalid(key, value, "remove whitespace around the key");
            }
            if (!REQUIRED_KEYS.contains(key)) {
                throw invalid(key, value, "remove the unknown key or use a documented required key");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw invalid(key, value, "define each required key exactly once");
            }
        }
        for (String key : REQUIRED_KEYS) {
            if (!values.containsKey(key)) {
                throw invalid(key, "<missing>", "add the required key exactly once");
            }
        }

        int schema = parseInt("schemaVersion", values.get("schemaVersion"));
        if (schema != SCHEMA_VERSION) {
            throw invalid("schemaVersion", values.get("schemaVersion"),
                    "set it to supported schema version " + SCHEMA_VERSION);
        }
        String branch = values.get("targetBranch");
        if (!BRANCHES.contains(branch)) {
            throw invalid("targetBranch", branch, "use one of master, develop, next");
        }
        ReleaseLine master = ReleaseLine.parse("masterLine", values.get("masterLine"));
        ReleaseLine develop = ReleaseLine.parse("developLine", values.get("developLine"));
        ReleaseLine next = ReleaseLine.parse("nextLine", values.get("nextLine"));
        if (!(master.compareTo(develop) < 0 && develop.compareTo(next) < 0)) {
            throw invalid("masterLine/developLine/nextLine",
                    master + "/" + develop + "/" + next,
                    "configure strictly ordered lines: master < develop < next");
        }
        SemanticVersion current = parseVersion("currentApi", values.get("currentApi"));
        String statusValue = values.get("currentStatus");
        Status status;
        if (statusValue.equals("candidate")) {
            status = Status.CANDIDATE;
        } else if (statusValue.equals("published")) {
            status = Status.PUBLISHED;
        } else {
            throw invalid("currentStatus", values.get("currentStatus"), "use candidate or published");
        }
        ReleaseLine selected = switch (branch) {
            case "master" -> master;
            case "develop" -> develop;
            case "next" -> next;
            default -> throw new AssertionError(branch);
        };
        ReleaseLine apiLine = ReleaseLine.of(current);
        // Product rollover can carry the same unpublished API into a later engine line.
        // Publication and master maintenance still require the selected release line.
        boolean developmentCandidate = status == Status.CANDIDATE && !branch.equals("master");
        if (developmentCandidate ? apiLine.compareTo(selected) > 0 : !apiLine.equals(selected)) {
            throw invalid("currentApi", current.toString(), developmentCandidate
                    ? "use a candidate API line no later than the " + selected
                            + " engine line selected by targetBranch=" + branch
                    : "use the " + selected + " release line selected by targetBranch=" + branch);
        }
        if (status == Status.CANDIDATE && !branch.equals("master") && current.patch() != 0) {
            throw invalid("currentApi", current.toString(),
                    "use patch zero for a " + branch + " candidate");
        }

        List<SemanticVersion> baselines = parseBaselines(values.get("publishedBaselines"));
        for (SemanticVersion baseline : baselines) {
            if (baseline.compareTo(current) > 0) {
                throw invalid("publishedBaselines", baseline.toString(),
                        "remove baselines later than currentApi=" + current);
            }
        }
        if (status == Status.PUBLISHED && !baselines.contains(current)) {
            throw invalid("publishedBaselines", values.get("publishedBaselines"),
                    "include currentApi=" + current + " when currentStatus=published");
        }
        if (status == Status.PUBLISHED && !branch.equals("master")) {
            throw invalid("targetBranch", branch,
                    "set targetBranch=master before marking currentStatus=published; develop and next own candidates");
        }
        if (status == Status.CANDIDATE && baselines.contains(current)) {
            throw invalid("publishedBaselines", current.toString(),
                    "remove currentApi while currentStatus=candidate");
        }
        return new ModApiReleasePolicy(branch, master, develop, next, current, status, baselines);
    }

    private static List<SemanticVersion> parseBaselines(String value) {
        if (value.isEmpty()) return List.of();
        String[] entries = value.split(",", -1);
        LinkedHashSet<SemanticVersion> unique = new LinkedHashSet<>();
        for (String entry : entries) {
            if (!entry.equals(entry.trim()) || entry.isEmpty()) {
                throw invalid("publishedBaselines", value,
                        "use a comma-separated list of canonical versions without whitespace");
            }
            SemanticVersion version = parseVersion("publishedBaselines", entry);
            if (!unique.add(version)) {
                throw invalid("publishedBaselines", entry, "remove the duplicate published version");
            }
        }
        List<SemanticVersion> sorted = new ArrayList<>(unique);
        sorted.sort(SemanticVersion::compareTo);
        if (!sorted.equals(new ArrayList<>(unique))) {
            throw invalid("publishedBaselines", value, "list published versions in ascending order");
        }
        return List.copyOf(sorted);
    }

    private static SemanticVersion parseVersion(String key, String value) {
        try {
            return SemanticVersion.parse(value);
        } catch (IllegalArgumentException ex) {
            throw invalid(key, value, "use canonical MAJOR.MINOR.PATCH form");
        }
    }

    private static int parseInt(String key, String value) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw invalid(key, value, "use a canonical nonnegative integer");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw invalid(key, value, "use a value no larger than Integer.MAX_VALUE");
        }
    }

    private static void addPin(Map<String, SemanticVersion> pins, String filename,
            SemanticVersion version) {
        SemanticVersion previous = pins.putIfAbsent(filename, version);
        if (previous != null && !previous.equals(version)) {
            throw invalid("publishedBaselines", version.toString(),
                    "publish at most one baseline per normalized pin filename " + filename);
        }
    }

    static String candidatePin(SemanticVersion version) {
        return "mod-api-signatures-" + version.major() + "." + version.minor() + ".txt";
    }

    static String publishedPin(SemanticVersion version) {
        return "mod-api-signatures-" + version + ".txt";
    }

    static IllegalArgumentException invalid(String key, String value, String action) {
        return new IllegalArgumentException("Invalid Mod API policy key '" + key
                + "' with value '" + value + "': " + action);
    }

    String targetBranch() { return targetBranch; }
    ReleaseLine masterLine() { return masterLine; }
    ReleaseLine developLine() { return developLine; }
    ReleaseLine nextLine() { return nextLine; }
    SemanticVersion currentApi() { return currentApi; }
    Status currentStatus() { return currentStatus; }
    List<SemanticVersion> publishedBaselines() { return publishedBaselines; }
    Map<String, SemanticVersion> expectedPins() { return expectedPins; }
}
