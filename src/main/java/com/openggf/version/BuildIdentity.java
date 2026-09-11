package com.openggf.version;

public record BuildIdentity(String baseVersion, String commit, boolean dirty) {
    public String displayVersion() {
        if (!isPrerelease()) {
            return normalized(baseVersion);
        }
        String suffix = isBlank(commit) ? "unknown" : normalized(commit);
        return normalized(baseVersion) + "-" + suffix + (dirty ? "-dirty" : "");
    }

    public boolean isPrerelease() {
        return baseVersion != null && baseVersion.toLowerCase().contains("prerelease");
    }

    public boolean isCompatibleWith(BuildIdentity other) {
        if (other == null || dirty || other.dirty) {
            return false;
        }
        if (!isPrerelease() && !other.isPrerelease()) {
            return normalized(baseVersion).equals(normalized(other.baseVersion));
        }
        if (isBlank(commit) || isBlank(other.commit)) {
            return false;
        }
        return normalized(baseVersion).equals(normalized(other.baseVersion))
                && normalized(commit).equals(normalized(other.commit));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
