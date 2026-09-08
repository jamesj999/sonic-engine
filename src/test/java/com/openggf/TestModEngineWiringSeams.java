package com.openggf;

import com.openggf.audio.StreamedMusicPort;
import com.openggf.mods.EffectiveModCatalog;
import com.openggf.mods.ModCatalog;
import com.openggf.mods.ModMusicResolver;
import com.openggf.mods.ModRuntimeFindingStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Guards the named deterministic entry boundaries required by the mod policy. */
class TestModEngineWiringSeams {
    private static final String DISABLE = "disableExternalContentForDeterminism();";

    @Test
    void everyNamedEntrySeamInstallsEmptyAndReleasesPreparedPcm() throws Exception {
        for (String className : List.of(
                "com.openggf.game.timeattack.AttemptReplayHarness",
                "com.openggf.game.recording.UserRecordingSessionLauncher",
                "com.openggf.tools.TraceCaptureTool",
                "com.openggf.tools.TraceCaptureSession",
                "com.openggf.tools.HeadlessGameBoot")) {
            AtomicBoolean released = new AtomicBoolean();
            ModSubsystem subsystem = liveSubsystem(released);
            ModSubsystem.installProcess(subsystem);
            subsystem.beginNormalSession(8_000, "s1");

            Method seam = Class.forName(className).getDeclaredMethod(
                    "disableExternalContentForDeterminism");
            seam.setAccessible(true);
            seam.invoke(null);

            assertTrue(released.get(), className + " did not release prepared PCM");
            assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView(),
                    className + " did not install the empty session view");
            assertSame(ExternalContentMode.SESSION_DETERMINISTIC,
                    subsystem.policy().mode(), className + " retained normal policy");
        }
        ModSubsystem.clearProcess();
    }

    @Test
    void attemptReplayDisablesExternalContentBeforeRomAndFrameSetup() throws IOException {
        assertBefore("game/timeattack/AttemptReplayHarness.java", DISABLE,
                "if (!rom.open(romPath.toString()))");
    }

    @Test
    void userRecordingPlaybackDisablesExternalContentBeforeRestart() throws IOException {
        assertMethodOrder(source("game/recording/UserRecordingSessionLauncher.java"),
                "public UserRecordingPlaybackState beginPlayback(", DISABLE,
                "gameLoop.restartFromRecordingLaunchContext(context)");
    }

    @Test
    void traceCaptureToolDisablesExternalContentBeforeEitherVerifyOrCaptureBoot()
            throws IOException {
        assertBefore("tools/TraceCaptureTool.java", DISABLE,
                "TraceEntry entry = resolveTrace(args.trace())");
    }

    @Test
    void traceCaptureSessionDisablesExternalContentBeforeCaptureRuntimeStarts()
            throws IOException {
        assertBefore("tools/TraceCaptureSession.java", DISABLE,
                "GameServices.audio().beginCaptureMode(sampleRate, fps)");
    }

    @Test
    void headlessBootDisablesExternalContentBeforeModuleResolution() throws IOException {
        assertBefore("tools/HeadlessGameBoot.java", DISABLE,
                "ModuleResolutionService moduleResolutionService = services.moduleResolutionService()");
    }

    @Test
    void audioResetAndBackendReplacementReleaseStreamedSessionState() throws IOException {
        String source = source("audio/AudioManager.java");
        assertMethodOrder(source, "public synchronized void resetState()",
                "invalidateAndReleaseStreamedMusicSession()", "beginSourceMutation()");
        assertMethodOrder(source, "private void invalidateAndReleaseStreamedMusicSession()",
                "streamedMusicSessionInvalidator.run()", "releaseStreamedMusicPort()");
        assertMethodOrder(source, "private synchronized void releaseStreamedMusicPort()",
                "backend.resetStreamedMusicPort()", "previous.close()");
        assertMethodOrder(source, "public synchronized void setBackend(AudioBackend backend)",
                "invalidateAndReleaseStreamedMusicSession()",
                "destroyBackendQuietly(this.backend");
        assertMethodOrder(source, "public synchronized void setBackend(AudioBackend backend)",
                "destroyBackendQuietly(this.backend", "this.backend = backend");
    }

    @Test
    void engineChoosesBootPolicyBeforeWindowOrModRootWork() throws IOException {
        String source = source("Engine.java");
        assertMethodOrder(source, "private void init()",
                "initializeExternalContentAtBoot()", "glfwInit()");
        assertMethodOrder(source, "private void initializeExternalContentAtBoot()",
                "new ExternalContentPolicy(", "ModSubsystem.normalBootLoader(");
    }

    @Test
    void enginePreparesBackendAndModsBeforeOpeningGameplaySession() throws IOException {
        String source = source("Engine.java");
        assertMethodOrder(source, "public void initializeGame()",
                "romDetectionService.detectAndCreateModule(rom)",
                "preparePresentationForLaunch(module)");
        assertMethodOrder(source, "public void initializeGame()",
                "preparePresentationForLaunch(module)",
                "SessionManager.openGameplaySession(");
        assertMethodOrder(source, "private boolean preparePresentationForLaunch(GameModule module)",
                "audioManager.setBackendForLaunch(", "audioManager.outputSampleRate()");
        assertMethodOrder(source, "private boolean preparePresentationForLaunch(GameModule module)",
                "subsystem.beginNormalSession(", "return true");
    }

    @Test
    void standalonePresentationFailureKeepsOwnerSpecificErrorSelection() throws IOException {
        String source = source("Engine.java");
        assertTrue(source.contains(
                "preparePresentationForLaunch(module, descriptor.manifest().id())"));
        assertTrue(source.contains(
                "if (standaloneOwner != null) showStandaloneLaunchError(standaloneOwner)"));
    }

    @Test
    void gameplayRuntimeAttachesPolicyFilteredPatternWindowsToWorldSession() throws IOException {
        String source = source("Engine.java");
        assertMethodOrder(source, "private void initializeGameplayRuntime(",
                "PatternWindowSessionState.install(",
                "GameplaySessionFactory.attachManagers(");
        assertTrue(source.contains("ModSubsystem.current().patternWindowStateForSession()"));
    }

    @Test
    void engineInstallsRealManagerFactoryAndClearsSessionBeforeAudioReset() throws IOException {
        String source = source("Engine.java");
        assertMethodOrder(source, "private MasterTitleScreen createMasterTitleScreen()",
                "moduleResolutionService.prepareLaunch(", "new MasterTitleScreen(configService,");
        assertMethodOrder(source, "private MasterTitleScreen createMasterTitleScreen()",
                "new MasterTitleScreen(configService,", "setModManagerScreenFactory(");
        assertMethodOrder(source, "private void resetForGameplayFromMasterTitle()",
                "ModSubsystem.current().returnToTitle()", "audioManager.resetState()");
    }

    @Test
    void freshTimeAttackDisablesModsBeforePresentationAndSessionOpen() throws IOException {
        String source = source("Engine.java");
        assertMethodOrder(source, "private void launchTimeAttack(TimeAttackLaunchRequest request)",
                "resolveTimeAttackModuleForLaunch(rootModule, request)",
                "ModSubsystem.disableCurrentSessionForDeterminism()");
        assertMethodOrder(source, "private void launchTimeAttack(TimeAttackLaunchRequest request)",
                "ModSubsystem.disableCurrentSessionForDeterminism()",
                "preparePresentationForLaunch(module)");
        assertMethodOrder(source, "private void launchTimeAttack(TimeAttackLaunchRequest request)",
                "preparePresentationForLaunch(module)",
                "SessionManager.openGameplaySession(");
    }

    @Test
    void engineUsesStrictBackendInitializationAndClearsModsBeforeShutdown() throws IOException {
        String source = source("Engine.java");
        assertMethodOrder(source, "private boolean preparePresentationForLaunch(GameModule module)",
                "audioManager.setBackendForLaunch(", "subsystem.beginNormalSession(");
        assertMethodOrder(source, "private void cleanup()",
                "cleanupStep(\"mod subsystem\", ModSubsystem::clearProcess)",
                "cleanupStep(\"audio manager\", audioManager::destroy)");
    }

    private static void assertBefore(String relativePath, String first, String second)
            throws IOException {
        String source = source(relativePath);
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue(firstIndex >= 0, () -> relativePath + " is missing " + first);
        assertTrue(secondIndex >= 0, () -> relativePath + " is missing " + second);
        assertTrue(firstIndex < secondIndex,
                () -> relativePath + " must run " + first + " before " + second);
    }

    private static void assertMethodOrder(String source, String method,
            String first, String second) {
        int methodIndex = source.indexOf(method);
        assertTrue(methodIndex >= 0, () -> "source is missing " + method);
        int nextMethod = source.indexOf("\n    public ", methodIndex + method.length());
        String body = source.substring(methodIndex,
                nextMethod < 0 ? source.length() : nextMethod);
        int firstIndex = body.indexOf(first);
        int secondIndex = body.indexOf(second);
        assertTrue(firstIndex >= 0, () -> method + " is missing " + first);
        assertTrue(secondIndex >= 0, () -> method + " is missing " + second);
        assertTrue(firstIndex < secondIndex,
                () -> method + " must run " + first + " before " + second);
    }

    private static String source(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/java/com/openggf").resolve(relativePath));
    }

    private static ModSubsystem liveSubsystem(AtomicBoolean released) {
        ModCatalog catalog = new ModCatalog(List.of(), EffectiveModCatalog.EMPTY);
        return new ModSubsystem(catalog, new ModRuntimeFindingStore(),
                (rate, game) -> new SessionExternalContentView(
                        ModMusicResolver.EMPTY, closingPort(released)));
    }

    private static StreamedMusicPort closingPort(AtomicBoolean released) {
        return new StreamedMusicPort() {
            @Override public int outputRate() { return 8_000; }
            @Override public boolean hasStockOverride(int musicId) { return false; }
            @Override public boolean isCurrentStockOverride(int musicId) { return false; }
            @Override public void playStockOverride(int musicId) { }
            @Override public boolean hasSource() { return false; }
            @Override public int mixInto(short[] output, int frames) { return 0; }
            @Override public void pause(int reason) { }
            @Override public void resume(int reason) { }
            @Override public void fadeOut(int steps, int stepDelay) { }
            @Override public void fadeIn(int steps, int stepDelay) { }
            @Override public void advanceFade() { }
            @Override public boolean fadeActive() { return false; }
            @Override public boolean fadeAtFullGain() { return true; }
            @Override public void setSpeedMultiplier(int multiplier) { }
            @Override public void stop() { }
            @Override public void reset() { }
            @Override public java.util.Optional<State> captureState() {
                return java.util.Optional.empty();
            }
            @Override public boolean restoreState(State state) { return false; }
            @Override public void close() { released.set(true); }
        };
    }
}
