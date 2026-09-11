package com.openggf.version;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestBuildIdentity {
    @Test
    void officialSameVersionCompatible() {
        BuildIdentity current = new BuildIdentity("0.6.1", "", false);
        BuildIdentity recording = new BuildIdentity("0.6.1", "", false);

        assertTrue(current.isCompatibleWith(recording));
        assertEquals("0.6.1", current.displayVersion());
    }

    @Test
    void officialDifferentVersionIncompatible() {
        BuildIdentity current = new BuildIdentity("0.6.1", "", false);
        BuildIdentity recording = new BuildIdentity("0.6.0", "", false);

        assertFalse(current.isCompatibleWith(recording));
    }

    @Test
    void prereleaseSameBaseAndCommitCompatible() {
        BuildIdentity current = new BuildIdentity("0.6.prerelease", "84f1f269d", false);
        BuildIdentity recording = new BuildIdentity("0.6.prerelease", "84f1f269d", false);

        assertTrue(current.isCompatibleWith(recording));
        assertEquals("0.6.prerelease-84f1f269d", current.displayVersion());
    }

    @Test
    void prereleaseSameBaseButDifferentCommitIncompatible() {
        BuildIdentity current = new BuildIdentity("0.6.prerelease", "84f1f269d", false);
        BuildIdentity recording = new BuildIdentity("0.6.prerelease", "abc123def", false);

        assertFalse(current.isCompatibleWith(recording));
    }

    @Test
    void anyDirtySideIncompatible() {
        BuildIdentity clean = new BuildIdentity("0.6.prerelease", "84f1f269d", false);
        BuildIdentity dirty = new BuildIdentity("0.6.prerelease", "84f1f269d", true);

        assertFalse(clean.isCompatibleWith(dirty));
        assertFalse(dirty.isCompatibleWith(clean));
        assertEquals("0.6.prerelease-84f1f269d-dirty", dirty.displayVersion());
    }

    @Test
    void missingPrereleaseCommitIncompatible() {
        BuildIdentity current = new BuildIdentity("0.6.prerelease", "", false);
        BuildIdentity recording = new BuildIdentity("0.6.prerelease", "84f1f269d", false);

        assertFalse(current.isCompatibleWith(recording));
        assertFalse(recording.isCompatibleWith(current));
        assertEquals("0.6.prerelease-unknown", current.displayVersion());
    }

    @Test
    void appVersionLoaderComputesDisplayFromStructuredProperties() {
        String properties = """
                app.version=0.6.prerelease
                app.baseVersion=0.6.prerelease
                app.commit=84f1f269d
                app.dirty=true
                """;

        BuildIdentity identity = AppVersion.loadIdentity(new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)));

        assertEquals(new BuildIdentity("0.6.prerelease", "84f1f269d", true), identity);
        assertEquals("0.6.prerelease-84f1f269d-dirty", identity.displayVersion());
    }

    @Test
    void unresolvedMavenPlaceholdersDoNotLeakIntoDisplayVersion() {
        String properties = """
                app.version=0.6.prerelease
                app.baseVersion=0.6.prerelease
                app.commit=${openggf.git.commit}
                app.dirty=${openggf.git.dirty}
                """;

        BuildIdentity identity = AppVersion.loadIdentity(new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)),
                baseVersion -> new BuildIdentity(baseVersion, "", false));

        assertEquals(new BuildIdentity("0.6.prerelease", "", false), identity);
        assertEquals("0.6.prerelease-unknown", identity.displayVersion());
    }

    @Test
    void unresolvedMavenCommitUsesRuntimeGitIdentityWhenAvailable() {
        String properties = """
                app.version=0.6.prerelease
                app.baseVersion=0.6.prerelease
                app.commit=${openggf.git.commit}
                app.dirty=${openggf.git.dirty}
                """;

        BuildIdentity identity = AppVersion.loadIdentity(new ByteArrayInputStream(properties.getBytes(StandardCharsets.UTF_8)),
                baseVersion -> new BuildIdentity(baseVersion, "abc123def", true));

        assertEquals(new BuildIdentity("0.6.prerelease", "abc123def", true), identity);
        assertEquals("0.6.prerelease-abc123def-dirty", identity.displayVersion());
    }

    @Test
    void appVersionGetReturnsLoadedIdentityDisplay() {
        assertEquals(AppVersion.identity().displayVersion(), AppVersion.get());
    }
}
