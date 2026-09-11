package com.openggf.trace.catalog;

import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceCatalogDescriptorOwnership {

    @Test
    void catalogValidationNeverCallsStaticEagerPlanner(@TempDir Path root)
            throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root.resolve("s3k/runs"));
        TraceV5RunFixture.writeMovie(runDir.resolve("synthetic.bk2"));
        TraceEntry entry = TraceCatalog.scan(root).stream()
                .filter(TraceEntry::isRun)
                .findFirst()
                .orElseThrow();

        TraceCatalog.RunLaunchValidation validation =
                TraceCatalog.validateRunLaunch(entry);

        assertTrue(validation.launchable(), validation.diagnostic());
    }

    @Test
    void catalogValidationPlansDescriptorsWithoutEagerReplayPayloads(
            @TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root.resolve("s3k/runs"));
        TraceV5RunFixture.writeMovie(runDir.resolve("synthetic.bk2"));
        TraceEntry entry = TraceCatalog.scan(root).stream()
                .filter(TraceEntry::isRun)
                .findFirst()
                .orElseThrow();
        AtomicInteger descriptorPlans = new AtomicInteger();

        TraceCatalog.RunLaunchValidation validation = TraceCatalog.validateRunLaunch(
                entry,
                movie -> new Bk2MovieLoader().load(movie),
                (manifest, directory) -> {
                    descriptorPlans.incrementAndGet();
                    return TraceRunReplayWalker.planDescriptors(manifest, directory);
                });

        assertTrue(validation.launchable(), validation.diagnostic());
        assertEquals(1, descriptorPlans.get());
    }

    @Test
    void descriptorPreparationNeverCallsTheStaticEagerPlanner(@TempDir Path root)
            throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root.resolve("s3k/runs"));
        TraceV5RunFixture.writeMovie(runDir.resolve("synthetic.bk2"));
        TraceEntry entry = TraceCatalog.scan(root).stream()
                .filter(TraceEntry::isRun)
                .findFirst()
                .orElseThrow();

        AtomicInteger descriptorPlans = new AtomicInteger();
        TraceCatalog.PreparedDescriptorRunLaunch prepared =
                TraceCatalog.prepareDescriptorRunLaunch(
                        entry,
                        movie -> new Bk2MovieLoader().load(movie),
                        (manifest, directory) -> {
                            descriptorPlans.incrementAndGet();
                            return TraceRunReplayWalker.planDescriptors(
                                    manifest, directory);
                        });

        assertEquals(1, descriptorPlans.get());
        assertEquals(entry.runManifest().segments().size(),
                prepared.segments().size());
    }
}
