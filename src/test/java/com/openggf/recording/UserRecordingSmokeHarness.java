package com.openggf.game.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.GameId;
import com.openggf.game.GameServices;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tools.RecordingFrameDriver;
import com.openggf.version.AppVersion;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Necessary-but-not-sufficient user-recording smoke harness for fresh-start
 * core determinism. This intentionally exercises the shared headless
 * {@link RecordingFrameDriver}; GameLoop mode routing, runtime controls, UI,
 * render suppression, and transitions are covered by later integration tasks.
 */
public final class UserRecordingSmokeHarness {
    private static final int ACTION_A_MASK = 0x01;
    private static final String LAUNCH_ROUTE = "current-act-fresh-start";
    private static final ObjectMapper SIDECAR_MAPPER = new ObjectMapper();

    private UserRecordingSmokeHarness() {
    }

    public static SelectedRom selectAvailableRom() {
        List<Candidate> candidates = new ArrayList<>();
        if (System.getProperty("s3k.rom.path") != null) {
            candidates.add(s3kCandidate());
        }
        candidates.add(s2Candidate());
        candidates.add(s1Candidate());

        for (Candidate candidate : candidates) {
            File romFile = candidate.findRom();
            if (romFile != null) {
                return new SelectedRom(candidate.game(), romFile, candidate.gameId(),
                        candidate.zone(), candidate.act());
            }
        }

        Assumptions.assumeTrue(false,
                "No local Sonic ROM available for user recording determinism smoke "
                        + "(checked -Ds3k.rom.path first when present, then Sonic 2, then Sonic 1)");
        throw new IllegalStateException("unreachable after failed assumption");
    }

    public static Result recordAndReplay(SelectedRom selectedRom, Path outputDirectory, int frameCount)
            throws IOException {
        Objects.requireNonNull(selectedRom, "selectedRom");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive");
        }

        Files.createDirectories(outputDirectory);

        RecordingLaunchContext launchContext = launchContextFor(selectedRom);
        HeadlessTestFixture recordFixture = openFreshFixture(selectedRom, launchContext);
        RecordingFrameDriver recordDriver = new RecordingFrameDriver(recordFixture.sprite());

        List<RecordedFrameInput> inputs = new ArrayList<>(frameCount);
        List<DesyncLiteFrame> sidecarFrames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount;) {
            SmokeInput input = inputFor(frame);
            recordDriver.stepFrame(input.up(), input.down(), input.left(), input.right(), input.jump(),
                    input.p2Mask(), input.p2Start(), input.p1Start());
            if (!recordDriver.didLastFrameRunGameplay()) {
                continue;
            }
            inputs.add(input.toRecordedFrame(frame));
            sidecarFrames.add(DesyncLiteSnapshotter.capture(frame));
            frame++;
        }

        Path bk2Path = outputDirectory.resolve("user-recording-determinism-smoke.bk2");
        UserRecordingManifest manifest = manifestFor(launchContext, frameCount);
        UserRecordingWriter.write(bk2Path, manifest, inputs, sidecarFrames);

        UserRecordingManifest reloadedManifest = readManifest(bk2Path);
        List<DesyncLiteFrame> reloadedSidecarFrames = readSidecarFrames(bk2Path);
        Bk2Movie movie = new Bk2MovieLoader().load(bk2Path);

        HeadlessTestFixture replayFixture = openFreshFixture(selectedRom, reloadedManifest.launchContext());
        RecordingFrameDriver replayDriver = new RecordingFrameDriver(replayFixture.sprite());
        replayDriver.setBk2Movie(movie, 0);

        UserRecordingVerifier verifier = new UserRecordingVerifier(reloadedSidecarFrames);
        for (int frame = 0; frame < movie.getFrameCount();) {
            Bk2FrameInput frameInput = movie.getFrame(frame);
            replayDriver.stepFrameFromRecording();
            if (!replayDriver.didLastFrameRunGameplay()) {
                continue;
            }
            verifier.observer().afterFrameAdvanced(frameInput, false);
            frame++;
        }

        return new Result(bk2Path, reloadedManifest.launchContext(),
                movie.getFrameCount(), verifier.result());
    }

    private static RecordingLaunchContext launchContextFor(SelectedRom selectedRom) {
        return new RecordingLaunchContext(
                selectedRom.gameId(),
                selectedRom.zone(),
                selectedRom.act(),
                "sonic",
                List.of(),
                false,
                LAUNCH_ROUTE);
    }

    private static UserRecordingManifest manifestFor(RecordingLaunchContext launchContext, int frameCount) {
        return new UserRecordingManifest(
                UserRecordingManifest.CURRENT_SCHEMA_VERSION,
                "determinism-smoke",
                AppVersion.identity(),
                launchContext,
                UserRecordingSidecarMetadata.everyFrame(),
                new RecordingDeterminismMetadata(null, null),
                "A",
                frameCount,
                UserRecordingStopReason.USER_STOPPED,
                Instant.now());
    }

    private static HeadlessTestFixture openFreshFixture(SelectedRom selectedRom,
                                                       RecordingLaunchContext launchContext) {
        Rom rom = new Rom();
        Assumptions.assumeTrue(rom.open(selectedRom.romFile().getAbsolutePath()),
                "ROM file could not be opened for user recording determinism smoke: "
                        + selectedRom.romFile().getAbsolutePath());
        TestEnvironment.configureRomFixture(rom);

        GameId actualGameId = GameServices.module().getGameId();
        Assumptions.assumeTrue(actualGameId.code().equalsIgnoreCase(launchContext.gameId()),
                "Selected ROM did not detect as " + launchContext.gameId()
                        + " (detected " + actualGameId.code() + ")");

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, launchContext.mainCharacter());
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                String.join(",", launchContext.sidekickCharacters()));
        config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, launchContext.debugToolsEnabled());
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        if (selectedRom.game() == SonicGame.SONIC_3K) {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        }

        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(launchContext.zone(), launchContext.act())
                .build();

        String activeMain = ActiveGameplayTeamResolver.resolveMainCharacterCode(config);
        Assumptions.assumeTrue(activeMain.equalsIgnoreCase(launchContext.mainCharacter()),
                "Fresh recording smoke did not rebuild requested main character: " + activeMain);
        return fixture;
    }

    private static SmokeInput inputFor(int frame) {
        boolean right = frame >= 16 && frame < 80;
        boolean jump = frame >= 32 && frame < 38;
        int p2Mask = 0;
        if (frame >= 44 && frame < 56) {
            p2Mask |= AbstractPlayableSprite.INPUT_RIGHT;
        }
        if (frame >= 48 && frame < 52) {
            p2Mask |= AbstractPlayableSprite.INPUT_JUMP;
        }
        return new SmokeInput(false, false, false, right, jump, false, p2Mask, false);
    }

    private static UserRecordingManifest readManifest(Path bk2Path) throws IOException {
        try (ZipFile zip = new ZipFile(bk2Path.toFile())) {
            ZipEntry entry = requireEntry(zip, "OpenGGF/manifest.json");
            try (InputStream input = zip.getInputStream(entry)) {
                return UserRecordingJson.readManifest(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static List<DesyncLiteFrame> readSidecarFrames(Path bk2Path) throws IOException {
        try (ZipFile zip = new ZipFile(bk2Path.toFile())) {
            ZipEntry entry = requireEntry(zip, "OpenGGF/desync-lite.jsonl");
            try (InputStream input = zip.getInputStream(entry)) {
                String jsonl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                List<DesyncLiteFrame> frames = new ArrayList<>();
                for (String line : jsonl.split("\\R")) {
                    if (!line.isBlank()) {
                        frames.add(SIDECAR_MAPPER.readValue(line, DesyncLiteFrame.class));
                    }
                }
                return frames;
            }
        }
    }

    private static ZipEntry requireEntry(ZipFile zip, String name) throws IOException {
        ZipEntry direct = zip.getEntry(name);
        if (direct != null) {
            return direct;
        }
        String expected = name.toLowerCase(Locale.ROOT);
        return zip.stream()
                .filter(entry -> entry.getName().toLowerCase(Locale.ROOT).equals(expected))
                .findFirst()
                .orElseThrow(() -> new IOException("BK2 missing required entry: " + name));
    }

    private static Candidate s3kCandidate() {
        return new Candidate(SonicGame.SONIC_3K, "s3k", 0, 0,
                RomTestUtils::ensureSonic3kRomAvailable);
    }

    private static Candidate s2Candidate() {
        return new Candidate(SonicGame.SONIC_2, "s2", 0, 0,
                RomTestUtils::ensureSonic2RomAvailable);
    }

    private static Candidate s1Candidate() {
        return new Candidate(SonicGame.SONIC_1, "s1", 0, 0,
                RomTestUtils::ensureSonic1RomAvailable);
    }

    public record SelectedRom(SonicGame game, File romFile, String gameId, int zone, int act) {
    }

    public record Result(Path bk2Path, RecordingLaunchContext launchContext, int recordedFrameCount,
                         UserRecordingVerificationResult verification) {
    }

    private record Candidate(SonicGame game, String gameId, int zone, int act, RomSupplier romSupplier) {
        File findRom() {
            return romSupplier.find();
        }
    }

    private record SmokeInput(boolean up, boolean down, boolean left, boolean right, boolean jump,
                              boolean p1Start, int p2Mask, boolean p2Start) {
        RecordedFrameInput toRecordedFrame(int frame) {
            int p1Mask = 0;
            if (up) {
                p1Mask |= AbstractPlayableSprite.INPUT_UP;
            }
            if (down) {
                p1Mask |= AbstractPlayableSprite.INPUT_DOWN;
            }
            if (left) {
                p1Mask |= AbstractPlayableSprite.INPUT_LEFT;
            }
            if (right) {
                p1Mask |= AbstractPlayableSprite.INPUT_RIGHT;
            }
            if (jump) {
                p1Mask |= AbstractPlayableSprite.INPUT_JUMP;
            }
            int p1ActionMask = jump ? ACTION_A_MASK : 0;
            int p2ActionMask = (p2Mask & AbstractPlayableSprite.INPUT_JUMP) != 0 ? ACTION_A_MASK : 0;
            return new RecordedFrameInput(frame, p1Mask, p1ActionMask, p1Start,
                    p2Mask, p2ActionMask, p2Start);
        }
    }

    @FunctionalInterface
    private interface RomSupplier {
        File find();
    }
}
