package com.openggf.version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class AppVersion {
    private static final String RESOURCE_PATH = "/version.properties";
    private static final String VERSION_PROPERTY = "app.version";
    private static final String BASE_VERSION_PROPERTY = "app.baseVersion";
    private static final String COMMIT_PROPERTY = "app.commit";
    private static final String DIRTY_PROPERTY = "app.dirty";
    private static final String DEFAULT_VERSION = "dev";
    private static final BuildIdentity IDENTITY = loadIdentity();

    private AppVersion() {
    }

    public static String get() {
        return identity().displayVersion();
    }

    public static BuildIdentity identity() {
        return IDENTITY;
    }

    private static BuildIdentity loadIdentity() {
        return loadIdentity(AppVersion.class.getResourceAsStream(RESOURCE_PATH));
    }

    private static String loadVersion(InputStream input) {
        return loadIdentity(input).displayVersion();
    }

    static BuildIdentity loadIdentity(InputStream input) {
        return loadIdentity(input, AppVersion::loadRuntimeGitIdentity);
    }

    static BuildIdentity loadIdentity(InputStream input, Function<String, BuildIdentity> runtimeIdentityProvider) {
        try (InputStream stream = input) {
            if (stream == null) {
                return defaultIdentity();
            }
            Properties properties = new Properties();
            properties.load(stream);
            String baseVersion = firstNonBlank(
                    properties.getProperty(BASE_VERSION_PROPERTY),
                    properties.getProperty(VERSION_PROPERTY),
                    DEFAULT_VERSION);
            String commit = filteredValue(properties.getProperty(COMMIT_PROPERTY));
            String dirtyValue = filteredValue(properties.getProperty(DIRTY_PROPERTY));
            boolean dirty = Boolean.parseBoolean(dirtyValue);
            BuildIdentity identity = new BuildIdentity(baseVersion, commit, dirty);
            if (identity.isPrerelease() && commit.isEmpty()) {
                BuildIdentity runtimeIdentity = runtimeIdentityProvider.apply(baseVersion);
                if (runtimeIdentity != null && !trim(runtimeIdentity.commit()).isEmpty()) {
                    return runtimeIdentity;
                }
            }
            return identity;
        } catch (IOException | IllegalArgumentException e) {
            return defaultIdentity();
        }
    }

    private static BuildIdentity loadRuntimeGitIdentity(String baseVersion) {
        String commit = runGit("rev-parse", "--short=9", "HEAD");
        if (commit.isEmpty()) {
            return new BuildIdentity(baseVersion, "", false);
        }
        boolean dirty = !runGit("status", "--porcelain").isEmpty();
        return new BuildIdentity(baseVersion, commit, dirty);
    }

    private static String runGit(String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(2, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "";
            }
            byte[] output = process.getInputStream().readAllBytes();
            if (process.exitValue() != 0) {
                return "";
            }
            return new String(output, StandardCharsets.UTF_8).trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private static BuildIdentity defaultIdentity() {
        return new BuildIdentity(DEFAULT_VERSION, "", false);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = filteredValue(value);
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return DEFAULT_VERSION;
    }

    private static String filteredValue(String value) {
        String trimmed = trim(value);
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return "";
        }
        return trimmed;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
