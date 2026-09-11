package com.openggf.tests.trace.runs;

import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManager.DiagnosticObserverHandle;
import com.openggf.audio.AudioManager.DiagnosticObserverSet;
import com.openggf.audio.AudioAdmissionObserver;
import com.openggf.audio.AudioRequestObserver;
import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.tests.TestEnvironment;
import com.openggf.tools.audio.completerun.s2.S2ProductionRequestProjector;
import com.openggf.tools.audio.parity.s2.S2OracleRawStream;
import com.openggf.tools.audio.parity.s2.S2OracleSchema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

/** Test-only production-BK2 request observation for the fixed S2 window. */
final class S2RequestProjectionBk2Capture extends AbstractRunChainTest {
    private static final Path RUN_DIR = Path.of("src", "test", "resources",
            "traces", "s2", "runs", "s2-sonic-tails-complete-emeralds");
    private static final Path RUN_BK2 = RUN_DIR.resolve(
            "sonic-2-sonic-tails-complete-emeralds.bk2");
    private static final String ROM_SHA1 =
            "8bca5dcef1af3e00098666fd892dc1c2a76333f9";
    private static final String BK2_SHA256 =
            "e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5";
    private static final int FIRST_ROW = 10_150;
    private static final int EXCLUSIVE_END = 10_900;
    private static final int DESTINATION_SEGMENT = 2;
    private static final int DESTINATION_START_ROW = 10_334;

    S2RequestProjectionBk2TestBridge.Capture capture(
            Path romPath, Path bk2Path)
            throws Exception {
        return capture(romPath, bk2Path, FIRST_ROW, EXCLUSIVE_END);
    }

    /**
     * The same production observation over another bounded interval of the same
     * committed run chain. The interval only decides which rows are observed and
     * how far the chain replays; it never changes what the engine does.
     */
    S2RequestProjectionBk2TestBridge.Capture capture(
            Path romPath, Path bk2Path, int firstRow, int exclusiveEnd)
            throws Exception {
        if (firstRow < DESTINATION_START_ROW - (DESTINATION_START_ROW - FIRST_ROW)
                || exclusiveEnd <= firstRow) {
            throw new IllegalArgumentException("window is not a valid interval");
        }
        requireIdentity(romPath, "SHA-1", ROM_SHA1, "S2 REV01 ROM");
        requireIdentity(bk2Path, "SHA-256", BK2_SHA256, "S2 complete-run BK2");
        requireIdentity(RUN_BK2, "SHA-256", BK2_SHA256,
                "repository S2 complete-run BK2");
        S2ProductionRequestProjector projector = new S2ProductionRequestProjector();
        List<Integer> requestRows = new ArrayList<>();
        List<Integer> musicSubmissionRows = new ArrayList<>();
        ProductionAudioRecorder audioRecorder = new ProductionAudioRecorder();
        audioRecorder.observeWindow(firstRow, exclusiveEnd);
        int[] productionOutputRow = {-1};
        Sonic2AudioProfile profile = new Sonic2AudioProfile(event -> {
            int row = productionOutputRow[0];
            if (row >= firstRow && row < exclusiveEnd) {
                int before = projector.requests().size();
                int musicBefore = projector.musicSubmissions().size();
                projector.accept(event);
                if (projector.requests().size() > before) {
                    requestRows.add(row);
                }
                if (projector.musicSubmissions().size() > musicBefore) {
                    musicSubmissionRows.add(row);
                }
            }
        });
        Rom rom = new Rom();
        if (!rom.open(romPath.toString())) {
            throw new IllegalArgumentException("cannot open verified S2 REV01 ROM");
        }
        try {
            TestEnvironment.configureGameModuleFixture(
                    new ObservedSonic2Module(profile));
            RomManager.getInstance().setRom(rom);
            AudioManager audio = GameServices.audio();
            try (DiagnosticObserverHandle ignored =
                         audio.acquireDiagnosticObservers(audioRecorder.observers())) {
                productionOutputRowObserver = row -> {
                    if (row != productionOutputRow[0]) {
                        audioRecorder.presentOpenRow(audio);
                        productionOutputRow[0] = row;
                        audioRecorder.beginRow(row);
                    }
                };
                afterProductionStep = () -> { };
                assertChainReplayThroughSegmentRow(RUN_DIR, DESTINATION_SEGMENT,
                        exclusiveEnd - DESTINATION_START_ROW);
                audioRecorder.presentOpenRow(audio);
                return new S2RequestProjectionBk2TestBridge.Capture(
                        projector, requestRows, musicSubmissionRows,
                        audioRecorder.rows(),
                        audioRecorder.publicAudioRequests(),
                        audioRecorder.updateTicks());
            }
        } finally {
            productionOutputRowObserver = row -> { };
            afterProductionStep = () -> { };
            RomManager.getInstance().setRom(null);
            rom.close();
        }
    }

    static final class ProductionAudioRecorder {
        private final List<S2RequestProjectionBk2TestBridge.ProductionAudioRow> rows =
                new ArrayList<>();
        private final List<S2OracleRawStream.ChipWrite> writes = new ArrayList<>();
        private final List<S2RequestProjectionBk2TestBridge.PublicAudioRequest>
                publicAudioRequests = new ArrayList<>();
        private int row = -1;
        private SmpsDriverSnapshot finalSnapshot;
        private SmpsDriverServiceObserver.ServiceEvent activeService;
        private boolean activeUpdateMusicService;
        private boolean completedDriverService;
        /**
         * Driver-update ticks for the v2 driver-state reference. The engine has
         * no service whose scope equals one ROM vertical interrupt, so an
         * update tick closes where the engine finishes the audio work for that
         * update, carrying the last driver snapshot it committed and every chip
         * write since the previous update boundary.
         */
        private final List<S2RequestProjectionBk2TestBridge.DriverUpdateTick>
                updateTicks = new ArrayList<>();
        private final List<S2OracleRawStream.ChipWrite> updateWrites =
                new ArrayList<>();
        private SmpsDriverSnapshot updateSnapshot;
        private boolean updateCompleted;
        private int windowFirstRow = FIRST_ROW;
        private int windowExclusiveEnd = EXCLUSIVE_END;

        void observeWindow(int firstRow, int exclusiveEnd) {
            windowFirstRow = firstRow;
            windowExclusiveEnd = exclusiveEnd;
        }

        DiagnosticObserverSet observers() {
            return new DiagnosticObserverSet(
                    (requestClass, rawSoundId) -> {
                        publicAudioRequests.add(
                                new S2RequestProjectionBk2TestBridge.PublicAudioRequest(
                                        row, requestClass, rawSoundId));
                    },
                    AudioAdmissionObserver.NONE,
                    new SmpsDriverServiceObserver() {
                        @Override
                        public void onServiceBegin(ServiceEvent event) {
                            if (activeService != null) {
                                throw new IllegalStateException(
                                        "driver service observer was re-entered");
                            }
                            activeService = event;
                            activeUpdateMusicService = isUpdateMusic(event);
                        }

                        @Override
                        public void onServiceEnd(
                                ServiceEvent event, SmpsDriverSnapshot snapshot) {
                            if (activeService == null) {
                                throw new IllegalStateException(
                                        "driver service ended without a begin");
                            }
                            if (!activeService.equals(event)) {
                                throw new IllegalStateException(
                                        "driver service end does not match its begin");
                            }
                            if (activeUpdateMusicService) {
                                if (completedDriverService) {
                                    throw new IllegalStateException(
                                            "row completed multiple EHZ music services");
                                }
                                finalSnapshot = snapshot;
                                completedDriverService = true;
                            }
                            if (snapshot != null) {
                                updateSnapshot = snapshot;
                                updateCompleted = true;
                            }
                            activeService = null;
                            activeUpdateMusicService = false;
                        }
                    },
                    new ChipWriteObserver() {
                        @Override
                        public void onYm2612Write(
                                int port, int register, int value) {
                            updateWrites.add(new S2OracleRawStream.ChipWrite(
                                    true, port, register, value,
                                    S2OracleRawStream.ChipWrite.SERVICE_UPDATE_MUSIC));
                            if (activeUpdateMusicService) {
                                writes.add(new S2OracleRawStream.ChipWrite(
                                        true, port, register, value,
                                        S2OracleRawStream.ChipWrite
                                                .SERVICE_UPDATE_MUSIC));
                            }
                        }

                        @Override
                        public void onPsgWrite(int value) {
                            updateWrites.add(new S2OracleRawStream.ChipWrite(
                                    false, 0, 0, value,
                                    S2OracleRawStream.ChipWrite.SERVICE_UPDATE_MUSIC));
                            if (activeUpdateMusicService) {
                                writes.add(new S2OracleRawStream.ChipWrite(
                                        false, 0, 0, value,
                                        S2OracleRawStream.ChipWrite
                                                .SERVICE_UPDATE_MUSIC));
                            }
                        }
                    },
                    SfxContentionObserver.NONE);
        }

        void beginRow(int selectedRow) {
            if (row >= 0) {
                throw new IllegalStateException("previous audio row was not closed");
            }
            row = selectedRow;
            writes.clear();
            finalSnapshot = null;
            completedDriverService = false;
        }

        void presentOpenRow(AudioManager audio) {
            if (row < 0) {
                return;
            }
            audio.presentFrame(PresentationMode.FORWARD);
            int closedRow = row;
            S2RequestProjectionBk2TestBridge.ProductionAudioRow observed =
                    finishObservedRow();
            if (observed.row() >= S2OracleSchema.ANCHOR_ROW
                    && observed.row() < EXCLUSIVE_END) {
                rows.add(observed);
            }
            // Only updates inside the observed window are published, matching
            // the reference, whose rows begin at its own first row.
            if (updateCompleted && closedRow >= windowFirstRow
                    && closedRow < windowExclusiveEnd) {
                updateTicks.add(
                        new S2RequestProjectionBk2TestBridge.DriverUpdateTick(
                                closedRow, updateSnapshot, updateWrites));
            }
            updateWrites.clear();
            updateSnapshot = null;
            updateCompleted = false;
        }

        S2RequestProjectionBk2TestBridge.ProductionAudioRow finishObservedRow() {
            if (activeService != null) {
                throw new IllegalStateException("driver service did not complete");
            }
            if (row < 0) {
                throw new IllegalStateException("no audio row is open");
            }
            S2RequestProjectionBk2TestBridge.ProductionAudioRow observed =
                    new S2RequestProjectionBk2TestBridge.ProductionAudioRow(
                            row, finalSnapshot, writes,
                            completedDriverService);
            row = -1;
            return observed;
        }

        List<S2RequestProjectionBk2TestBridge.ProductionAudioRow> rows() {
            if (row >= 0 || activeService != null) {
                throw new IllegalStateException("audio observation is incomplete");
            }
            return List.copyOf(rows);
        }

        List<S2RequestProjectionBk2TestBridge.DriverUpdateTick> updateTicks() {
            return List.copyOf(updateTicks);
        }

        List<S2RequestProjectionBk2TestBridge.PublicAudioRequest>
                publicAudioRequests() {
            return List.copyOf(publicAudioRequests);
        }

        private static boolean isUpdateMusic(
                SmpsDriverServiceObserver.ServiceEvent event) {
            return event.kind()
                            == SmpsDriverServiceObserver.ServiceKind
                                    .SEQUENCER_TICK
                    && !event.sequencer().sfx()
                    && event.sequencer().source().kind()
                            == SmpsSourceDescriptor.Kind.BASE_MUSIC
                    && event.sequencer().source().id()
                            == Sonic2Music.EMERALD_HILL.id;
        }
    }

    private static void requireIdentity(Path path, String algorithm,
            String expected, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " is absent");
        }
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(label + " identity differs");
            }
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("cannot verify " + label, failure);
        }
    }

    private static final class ObservedSonic2Module extends Sonic2GameModule {
        private final GameAudioProfile profile;

        private ObservedSonic2Module(GameAudioProfile profile) {
            this.profile = profile;
        }

        @Override
        public GameAudioProfile getAudioProfile() {
            return profile;
        }
    }
}
