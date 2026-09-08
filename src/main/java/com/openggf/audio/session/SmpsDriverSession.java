package com.openggf.audio.session;

import com.openggf.audio.driver.PreparedSfxAdmission;
import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.driver.SmpsDriverSessionAccess;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsLoadReadiness;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owner-thread composition root for one persistent logical SMPS driver and
 * one physical chip pair.
 */
public final class SmpsDriverSession implements AutoCloseable {
    private static final int MAX_OVERRIDES = 128;

    public interface DacDependencyResolver {
        DacData resolve(SmpsSourceDescriptor source);

        default SmpsLoadReadiness resolveReadiness(
                SmpsSourceDescriptor source) {
            return SmpsLoadReadiness.immediatePlan();
        }
    }

    public record PreparedRestore(
            SmpsDriverSessionSnapshot session,
            SmpsDriverSnapshot logical,
            DacData resolvedDac,
            List<PreparedSavedOverride> savedOverrides,
            PreparedPendingService pendingService) {
        public PreparedRestore {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(logical, "logical");
            savedOverrides = List.copyOf(Objects.requireNonNull(
                    savedOverrides, "savedOverrides"));
        }
    }

    public record PreparedSavedOverride(
            SmpsDriverSnapshot logical,
            SmpsLogicalTransitionPolicy policy,
            SmpsDacSelection selectedDac,
            PreparedPendingService pendingService) {
        public PreparedSavedOverride {
            Objects.requireNonNull(logical, "logical");
            if (musicId(logical) >= 0) {
                Objects.requireNonNull(policy, "policy");
            }
        }
    }

    public record PreparedPendingService(
            SmpsMusicActivation activation,
            SmpsDriverSnapshot readyLogical,
            SmpsWriteProgram firstServiceWrites,
            SmpsDacSelection selectedDac,
            SmpsLoadReadiness readiness,
            SmpsLoadReadiness.Context readinessContext,
            long remainingTStates) {
        public PreparedPendingService {
            Objects.requireNonNull(firstServiceWrites,
                    "firstServiceWrites");
            Objects.requireNonNull(readiness, "readiness");
            Objects.requireNonNull(readinessContext, "readinessContext");
        }
    }

    public interface LiveMutationToken {
    }

    private record SavedOverride(
            SmpsDriverSnapshot logical,
            SmpsLogicalTransitionPolicy policy,
            SmpsDacSelection selectedDac,
            PendingService pendingService) {
        private SavedOverride {
            Objects.requireNonNull(logical, "logical");
            if (musicId(logical) >= 0) {
                Objects.requireNonNull(policy, "policy");
            }
        }
    }

    private static final class PendingService {
        private final SmpsMusicActivation activation;
        private final SmpsDriverSnapshot logical;
        private final SmpsWriteProgram firstServiceWrites;
        private final SmpsDacSelection selectedDac;
        private final SmpsLoadReadiness.Work readiness;
        private final SmpsLoadReadiness plan;
        private final SmpsLoadReadiness.Context readinessContext;

        private PendingService(
                SmpsMusicActivation activation,
                SmpsDriverSnapshot logical,
                SmpsWriteProgram firstServiceWrites,
                SmpsDacSelection selectedDac,
                SmpsLoadReadiness plan,
                SmpsLoadReadiness.Context readinessContext,
                SmpsLoadReadiness.Work readiness) {
            this.activation = activation;
            this.logical = logical;
            this.firstServiceWrites = Objects.requireNonNull(
                    firstServiceWrites, "firstServiceWrites");
            this.selectedDac = selectedDac;
            this.readiness = Objects.requireNonNull(readiness, "readiness");
            this.plan = Objects.requireNonNull(plan, "plan");
            this.readinessContext = Objects.requireNonNull(
                    readinessContext, "readinessContext");
        }

        SmpsMusicActivation activation() { return activation; }
        SmpsDriverSnapshot logical() { return logical; }
        SmpsWriteProgram firstServiceWrites() { return firstServiceWrites; }
        SmpsDacSelection selectedDac() { return selectedDac; }
        SmpsLoadReadiness.Work readiness() { return readiness; }

        PendingService copy() {
            return new PendingService(activation, logical,
                    firstServiceWrites, selectedDac, plan,
                    readinessContext, readiness.copy());
        }
    }

    /**
     * One in-flight run of the driver's own SEGA PCM transport.
     *
     * <p>S3K's {@code zPlaySEGAPCM} (Sound/Z80 Sound Driver.asm:4372-4424)
     * holds the bus with interrupts disabled and sends one sample byte per
     * loop iteration, so this state is the Z80's position in that loop: the
     * next byte, the elapsed part of the current byte's delay, and whether
     * {@code cmd_StopSEGA} has been seen.</p>
     */
    private static final class SegaPcmTransport {
        private final SmpsSegaPcmTransport transport;
        private final byte[] pcm;
        private int cursor;
        private long cycleAccumulator;
        private boolean stopRequested;

        private SegaPcmTransport(
                SmpsSegaPcmTransport transport, byte[] pcm) {
            this.transport = transport;
            this.pcm = pcm;
        }

        private SegaPcmTransport copy() {
            SegaPcmTransport copy = new SegaPcmTransport(transport, pcm);
            copy.cursor = cursor;
            copy.cycleAccumulator = cycleAccumulator;
            copy.stopRequested = stopRequested;
            return copy;
        }
    }

    private SavedOverride[] mutationOverrides;

    private final class SessionLiveMutation implements LiveMutationToken {
        private final Object ownerIdentity;
        private final SmpsPhysicalDevice.LiveMutationToken physical;
        private final SmpsDriver.LiveCommandMutationToken logical;
        private final SmpsDriver driverIdentity;
        private final boolean initialized;
        private final SmpsPendingGlobalCommand pendingGlobalCommand;
        private final SmpsSourceDescriptor selectedDacSource;
        private final PendingService pendingService;
        private final SavedOverride[] overrides;
        private final int overrideCount;
        private final boolean speedShoesEnabled;
        private final int speedMultiplier;
        private final boolean ringLeft;
        private final int musicFmDacTrackCount;
        private final SegaPcmTransport segaPcmTransport;
        private final int diagnosticCount;
        private boolean commitPrepared;
        private boolean consumed;

        private SessionLiveMutation(
                SmpsPhysicalDevice.LiveMutationToken physical) {
            ownerIdentity = sessionIdentity;
            this.physical = physical;
            driverIdentity = driver;
            logical = driver == null
                    ? null : driver.captureLiveCommandMutation();
            initialized = SmpsDriverSession.this.initialized;
            pendingGlobalCommand =
                    SmpsDriverSession.this.pendingGlobalCommand;
            selectedDacSource =
                    SmpsDriverSession.this.selectedDacSource;
            pendingService = SmpsDriverSession.this.pendingService == null
                    ? null : SmpsDriverSession.this.pendingService.copy();
            if (mutationOverrides == null) mutationOverrides = new SavedOverride[overrideStack.length];
            System.arraycopy(overrideStack, 0, mutationOverrides, 0,
                    SmpsDriverSession.this.overrideCount);
            overrides = mutationOverrides;
            this.overrideCount = SmpsDriverSession.this.overrideCount;
            speedShoesEnabled =
                    SmpsDriverSession.this.speedShoesEnabled;
            speedMultiplier = SmpsDriverSession.this.speedMultiplier;
            ringLeft = SmpsDriverSession.this.ringLeft;
            musicFmDacTrackCount = SmpsDriverSession.this.musicFmDacTrackCount;
            segaPcmTransport =
                    SmpsDriverSession.this.segaPcmTransport == null
                            ? null
                            : SmpsDriverSession.this.segaPcmTransport.copy();
            diagnosticCount = diagnostics.size();
        }
    }

    private final class PortCapability implements SmpsPhysicalPort {
        private final Object ownerSessionIdentity;
        private final SmpsDriverServiceObserver.DriverIdentity owner;
        private final long epoch;

        private PortCapability(
                SmpsDriverServiceObserver.DriverIdentity owner,
                long epoch) {
            ownerSessionIdentity = sessionIdentity;
            this.owner = owner;
            this.epoch = epoch;
        }

        @Override
        public SmpsDriverServiceObserver.DriverIdentity owner() {
            return owner;
        }

        @Override
        public long epoch() {
            return epoch;
        }

        @Override
        public void writeFm(int port, int register, int value) {
            requireOpen(this);
            beforePhysicalWrite(new SmpsChipWrite.Ym2612(port, register,
                    value));
            device.writeFm(port, register, value);
        }

        @Override
        public void writePsg(int value) {
            requireOpen(this);
            beforePhysicalWrite(new SmpsChipWrite.Psg(value));
            device.writePsg(value);
        }

        @Override
        public void applyTransientPsgSilence(SmpsWriteProgram program) {
            requireOpen(this);
            device.applyTransientPsgSilence(program);
        }

        @Override
        public void setInstrument(int channelId, byte[] voice) {
            requireOpen(this);
            device.setInstrument(channelId, voice);
        }

        @Override
        public void playDac(int note) {
            requireOpen(this);
            device.playDac(note);
        }

        @Override
        public void stopDac() {
            requireOpen(this);
            device.stopDac();
        }

        @Override
        public void selectDac(SmpsDacSelection selection) {
            requireOpen(this);
            SmpsDacSelection resolved = Objects.requireNonNull(
                    selection, "selection");
            device.selectDac(resolved.data());
            selectedDacSource = resolved.source();
        }

        @Override
        public void forceSilenceFmChannel(int channelId) {
            requireOpen(this);
            device.forceSilenceFmChannel(channelId);
        }

        @Override
        public void setFmMute(int channel, boolean mute) {
            requireOpen(this);
            device.setFmMute(channel, mute);
        }

        @Override
        public void setPsgMute(int channel, boolean mute) {
            requireOpen(this);
            device.setPsgMute(channel, mute);
        }

        @Override
        public void silenceOutput() {
            requireOpen(this);
            device.silenceOutput();
        }

        @Override
        public AdmissionToken captureAdmissionState(
                int fmMask, int psgMask) {
            requireOpen(this);
            return new AdmissionState(this,
                    device.captureAdmissionState(fmMask, psgMask));
        }

        @Override
        public void restoreAdmissionState(AdmissionToken token) {
            requireOpen(this);
            AdmissionState state = requireAdmissionToken(token);
            if (state.consumed) {
                throw new IllegalStateException(
                        "SMPS admission token has already been consumed");
            }
            device.restoreAdmissionState(state.physical);
            state.consumed = true;
        }
    }

    private final class AdmissionState
            implements SmpsPhysicalPort.AdmissionToken {
        private final Object ownerSessionIdentity;
        private final SmpsPhysicalDevice ownerDevice;
        private final SmpsDriverServiceObserver.DriverIdentity owner;
        private final long epoch;
        private final SmpsPhysicalDevice.AdmissionState physical;
        private boolean consumed;

        private AdmissionState(
                PortCapability capability,
                SmpsPhysicalDevice.AdmissionState physical) {
            ownerSessionIdentity = sessionIdentity;
            ownerDevice = device;
            owner = capability.owner;
            epoch = capability.epoch;
            this.physical = physical;
        }
    }

    /** Resolves each logical write against the open epoch; stores no port. */
    private final class SmpsSessionSynthesizerAccess
            implements SmpsDriverSessionAccess {
        @Override
        public void writeFm(
                Object source, int port, int reg, int val) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(capability ->
                    capability.writeFm(port, reg, val));
        }

        @Override
        public void writePsg(Object source, int val) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(port -> port.writePsg(val));
        }

        @Override
        public void setInstrument(
                Object source, int channelId, byte[] voice) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(port ->
                    port.setInstrument(channelId, voice));
        }

        @Override
        public void playDac(Object source, int note) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(port -> port.playDac(note));
        }

        @Override
        public void stopDac(Object source) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(SmpsPhysicalPort::stopDac);
        }

        @Override
        public void setDacData(DacData data) {
            if (logicalMaterialization) {
                return;
            }
            throw new IllegalArgumentException(
                    "session DAC selection requires a source descriptor");
        }

        @Override
        public void selectDac(
                SmpsSourceDescriptor source, DacData data) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(port -> port.selectDac(
                    new SmpsDacSelection(source, data)));
        }

        @Override
        public void setFmMute(int channel, boolean mute) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(port -> port.setFmMute(channel, mute));
        }

        @Override
        public void setPsgMute(int channel, boolean mute) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(port -> port.setPsgMute(channel, mute));
        }

        @Override
        public void setDacInterpolate(boolean interpolate) {
            if (logicalMaterialization) {
                return;
            }
            if (interpolate != profile.settings().dacInterpolate()) {
                throw new IllegalArgumentException(
                        "session DAC interpolation is profile-owned");
            }
        }

        @Override
        public void silenceAll() {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(port -> applyProgram(
                    port, policy.stopAll()));
        }

        @Override
        public void forceSilenceFmChannel(int channelId) {
            if (logicalMaterialization) {
                return;
            }
            withDirectPort(port ->
                    port.forceSilenceFmChannel(channelId));
        }

        private void withDirectPort(
                Consumer<SmpsPhysicalPort> action) {
            Consumer<SmpsPhysicalPort> resolved =
                    Objects.requireNonNull(action, "action");
            if (openOwner != null) {
                resolved.accept(currentPort());
                return;
            }
            SmpsDriverSession.this.withPort(driverIdentity, port -> {
                resolved.accept(port);
                return null;
            });
        }

        @Override
        public boolean completeFadeOut() {
            if (logicalMaterialization
                    || !fadeOutCompletesWithGlobalStop()) {
                return false;
            }
            applyGlobalStopNow();
            globalStopConsumedDuringService = true;
            return true;
        }

        @Override
        public boolean fadeOutCompletesWithGlobalStop() {
            return configuration.statefulCommandPolicy().fadeOutCompletesWithGlobalStop();
        }

        private PortCapability currentPort() {
            requireActive();
            if (openOwner == null) {
                throw new IllegalStateException(
                        "SMPS logical write occurred outside a scoped epoch");
            }
            return new PortCapability(openOwner, openEpoch);
        }
    }

    private final Thread ownerThread;
    private final Object sessionIdentity = new Object();
    private final SmpsPhysicalDevice device;
    private final SmpsDriver.DirectPcmRenderer directRenderer;
    private final SmpsPhysicalPolicy policy;
    private final SmpsSessionProfileFingerprint profile;
    private final SmpsDriverSessionConfiguration configuration;
    private ChipWriteObserver chipWriteObserver;
    private final SmpsDiagnosticQueue diagnostics = new SmpsDiagnosticQueue();
    private final SavedOverride[] overrideStack =
            new SavedOverride[MAX_OVERRIDES];
    private final SmpsDriverServiceObserver.DriverIdentity driverIdentity =
            new SmpsDriverServiceObserver.DriverIdentity(
                    0,
                    SmpsDriverServiceObserver.DriverAdmissionOrigin
                            .unspecified());

    private SmpsDriver driver;
    private boolean initialized;
    private SmpsPendingGlobalCommand pendingGlobalCommand =
            SmpsPendingGlobalCommand.NONE;
    private SmpsSourceDescriptor selectedDacSource;
    private PendingService pendingService;
    private int overrideCount;
    private boolean speedShoesEnabled;
    private int speedMultiplier = 1;
    private boolean ringLeft = true;
    // Retain the loaded header's DAC disposition after its sequencer ends.
    // S2 DACEnabled is driver RAM, not the existence of active music tracks.
    private int musicFmDacTrackCount;
    private SegaPcmTransport segaPcmTransport;
    private SmpsDriverServiceObserver.DriverIdentity openOwner;
    private long openEpoch;
    private long nextEpoch;
    private int serviceInvocationCount;
    // Per-call outcome, not driver state: reset before every forward service.
    private boolean globalStopConsumedDuringService;
    private boolean transactionOpen;
    private boolean logicalMaterialization;
    private boolean closed;
    private SmpsDriverServiceObserver driverServiceObserver =
            SmpsDriverServiceObserver.NONE;
    private SfxContentionObserver sfxContentionObserver =
            SfxContentionObserver.NONE;
    private Consumer<RuntimeException> diagnosticErrorSink = ignored -> { };
    private Consumer<SmpsChipWrite> physicalWriteInterceptorForTesting =
            ignored -> { };
    private Runnable statefulLogicalMutationInterceptorForTesting = () -> { };

    public SmpsDriverSession(
            SmpsPhysicalDevice.Settings settings,
            SmpsPhysicalPolicy policy,
            ChipWriteObserver observer,
            SmpsSessionProfileFingerprint profile,
            SmpsDriverSessionConfiguration configuration) {
        ownerThread = Thread.currentThread();
        SmpsPhysicalDevice.Settings resolvedSettings =
                Objects.requireNonNull(settings, "settings");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.configuration = Objects.requireNonNull(configuration,
                "configuration");
        chipWriteObserver = Objects.requireNonNull(observer, "observer");
        if (!resolvedSettings.equals(profile.settings())) {
            throw new IllegalArgumentException(
                    "session settings do not match the profile fingerprint");
        }
        if (!policy.identity().equals(profile.physicalPolicyId())) {
            throw new IllegalArgumentException(
                    "physical policy does not match the profile fingerprint");
        }
        if (!configuration.statefulCommandPolicy().identity().equals(
                profile.statefulCommandPolicyId())) {
            throw new IllegalArgumentException(
                    "stateful-command policy does not match the profile fingerprint");
        }
        device = new SmpsPhysicalDevice(resolvedSettings,
                new ChipWriteObserver() {
                    @Override
                    public void onYm2612Write(
                            int port, int register, int value) {
                        if (transactionOpen) {
                            diagnostics.addYm(port, register, value);
                            return;
                        }
                        try {
                            chipWriteObserver.onYm2612Write(port, register, value);
                        } catch (RuntimeException failure) {
                            reportDiagnosticFailure(failure);
                        }
                    }

                    @Override
                    public void onPsgWrite(int value) {
                        if (transactionOpen) {
                            diagnostics.addPsg(value);
                            return;
                        }
                        try {
                            chipWriteObserver.onPsgWrite(value);
                        } catch (RuntimeException failure) {
                            reportDiagnosticFailure(failure);
                        }
                    }

                    @Override
                    public boolean observesPhysicalWrites() {
                        return chipWriteObserver.observesPhysicalWrites();
                    }

                    @Override
                    public void onYm2612BusWrite(long cycle, int busPort,
                            int value,
                            ChipWriteObserver.PhysicalWriteOrigin origin) {
                        if (transactionOpen) {
                            diagnostics.addYmBus(cycle, busPort, value, origin);
                            return;
                        }
                        try {
                            chipWriteObserver.onYm2612BusWrite(cycle, busPort, value, origin);
                        } catch (RuntimeException failure) {
                            reportDiagnosticFailure(failure);
                        }
                    }

                    @Override
                    public void onPsgBusWrite(long tick, int value) {
                        if (transactionOpen) {
                            diagnostics.addPsgBus(tick, value);
                            return;
                        }
                        try {
                            chipWriteObserver.onPsgBusWrite(tick, value);
                        } catch (RuntimeException failure) {
                            reportDiagnosticFailure(failure);
                        }
                    }

                    @Override
                    public void onPhysicalTimelineBoundary(
                            ChipWriteObserver.ChipClockDomain domain,
                            long clock,
                            ChipWriteObserver.PhysicalTimelineBoundary boundary) {
                        if (transactionOpen) {
                            diagnostics.addBoundary(domain, clock, boundary);
                            return;
                        }
                        try {
                            chipWriteObserver.onPhysicalTimelineBoundary(domain, clock, boundary);
                        } catch (RuntimeException failure) {
                            reportDiagnosticFailure(failure);
                        }
                    }
                });
        directRenderer = (buffer, frameOffset, frames) -> {
            device.renderFrames(buffer, frameOffset * 2, frames);
            emitPendingDacSampleEnds();
        };
    }

    /** Creates and initializes the one persistent logical driver. */
    public void install() {
        requireActive();
        if (driver != null) {
            throw new IllegalStateException(
                    "SMPS driver session is already installed");
        }
        if (transactionOpen) {
            throw new IllegalStateException(
                    "cannot install during a live mutation");
        }
        driver = SmpsDriver.createSessionDriver(
                new SmpsSessionSynthesizerAccess());
        driver.setDiagnosticIdentity(driverIdentity);
        installDriverObservers();
        withPort(driverIdentity, port -> {
            applyProgram(port, policy.boot());
            // zInitAudioDriver jumps into zPlayDigitalAudio and never returns
            // (Sound/Z80 Sound Driver.asm:550-551), so the loop's entry writes
            // follow the init immediately on the physical device.
            applyProgram(port, policy.enterDacIdleLoop());
            port.silenceOutput();
            return null;
        });
        initialized = true;
        driver.observeLifecycle(
                SmpsDriverServiceObserver.LifecycleKind.DRIVER_CREATED);
    }

    public boolean installed() {
        requireActive();
        return driver != null;
    }

    /** Captures one lock-held host-owned stateful-command input. */
    public SmpsStatefulCommandOperation prepareStatefulCommand(
            SmpsSessionCommand command) {
        requireInstalled();
        return configuration.statefulCommandPolicy().prepare(
                new SmpsStatefulCommandOperation.Input(
                        Objects.requireNonNull(command, "command"),
                        driver.captureOwnershipProjection()));
    }

    public SmpsServiceOutcome serviceForward() {
        requireInstalled();
        globalStopConsumedDuringService = false;
        serviceInvocationCount++;
        if (segaPcmTransport != null) {
            // S2/S3K block their Z80 driver inside the PCM loop. S1's
            // 68000 PlaySegaSound also blocks in its busyloop while the
            // separate Z80 plays the chant (s1.sounddriver.asm:733-747).
            return SmpsServiceOutcome.SEGA_PCM_TRANSPORT;
        }
        if (pendingGlobalCommand == SmpsPendingGlobalCommand.STOP_ALL) {
            applyGlobalStopNow();
            return SmpsServiceOutcome.GLOBAL_STOP_CONSUMED;
        }

        PendingService service = pendingService;
        if (service != null) {
            if (!service.readiness().ready()) {
                if (!service.readiness().advanceOnePresentation()) {
                    return SmpsServiceOutcome.LOAD_PENDING;
                }
                // A non-immediate load is one synchronous driver invocation.
                // Reaching zero during this presentation means that invocation
                // is still in flight; the next service boundary atomically
                // commits its built-in first update instead of dispatching a
                // second update. S2 zBGMLoad returns into zUpdateMusic in the
                // same zUpdateEverything pass (s2.sounddriver.asm:1738-2006).
                return SmpsServiceOutcome.SERVICE_IN_FLIGHT;
            }
            if (service.logical() != null) {
                restoreLogicalWithoutWrites(service.logical());
                applyCurrentLogicalControls();
            }
            withPort(driverIdentity, port -> {
                if (service.selectedDac() != null) {
                    port.selectDac(service.selectedDac());
                }
                if (service.activation() != null) {
                    musicFmDacTrackCount = service.activation().fmDacTrackCount();
                    applyProgram(port,
                            policy.activateMusic(service.activation()));
                }
                applyProgram(port,
                        service.firstServiceWrites());
                driver.serviceOuterFrame();
                return null;
            });
            pendingService = null;
        } else {
            withPort(driverIdentity, port -> {
                driver.serviceOuterFrame();
                return null;
            });
        }
        emitDacIdleLoopEnableIfQueued();
        return globalStopConsumedDuringService
                ? SmpsServiceOutcome.GLOBAL_STOP_CONSUMED : SmpsServiceOutcome.ORDINARY;
    }

    /**
     * Emits the ROM's DAC enable for a sample this service queued.
     *
     * <p>{@code zUpdateDACTrack} stores {@code zDACIndex} inside the V-int
     * service (Sound/Z80 Sound Driver.asm:2896-2903), and the idle loop the
     * service returns to is what finds the index non-zero and writes
     * {@code 2Bh = 80h} before decoding (:4269-4276). So the enable opens the
     * window after the queueing service rather than closing it, which is
     * where this call sits.</p>
     *
     * <p>Its other half is the disable, emitted from
     * {@link #emitPendingDacSampleEnds()} when the chip exhausts the sample.
     * The two must ship together: an enable without a disable leaves the DAC
     * on holding its last level once the sample has finished.</p>
     */
    private void emitDacIdleLoopEnableIfQueued() {
        if (!driver.consumeDacIdleLoopPass()) {
            return;
        }
        SmpsWriteProgram enable = policy.enableDacFromIdleLoop();
        if (enable.writes().isEmpty()) {
            return;
        }
        withPort(driverIdentity, port -> {
            applyProgram(port, enable);
            return null;
        });
    }

    /**
     * Emits the ROM's {@code 2Bh = 0} for every sample the chip has finished
     * since the last render (Sound/Z80 Sound Driver.asm:4348-4355,
     * :4256-4260), and reports how many it emitted.
     */
    int emitPendingDacSampleEnds() {
        SmpsWriteProgram disable = policy.enterDacIdleLoop();
        if (disable.writes().isEmpty()) {
            return 0;
        }
        return device.emitDacSampleEnds(disable);
    }

    /**
     * Runs the physical chip for {@code stereoFrames} of output time without
     * producing audio and without servicing the driver: the Z80 sitting in
     * {@code zPlayDigitalAudio} between two V-ints
     * (Sound/Z80 Sound Driver.asm:4296-4351). The DAC byte writes it makes
     * reach the chip write observer like any other write.
     */
    public void advanceDacIdleLoop(short[] scratch, int stereoFrames) {
        requireInstalled();
        Objects.requireNonNull(scratch, "scratch");
        if (stereoFrames < 0 || stereoFrames * 2 > scratch.length) {
            throw new IllegalArgumentException(
                    "scratch does not hold the requested frame range");
        }
        renderFrames(scratch, 0, stereoFrames);
        emitPendingDacSampleEnds();
    }

    public int renderFrames(
            short[] target, int offsetSamples, int stereoFrames) {
        requireInstalled();
        if (segaPcmTransport == null) {
            return device.renderFrames(target, offsetSamples, stereoFrames);
        }
        return renderSegaPcmTransport(target, offsetSamples, stereoFrames);
    }

    /**
     * Whether the driver itself owns the SEGA chant on this profile.
     *
     * <p>A policy that describes the transport plays it through the chip's
     * DAC; one that does not leaves the SEGA screen to whatever mechanism
     * its game already uses.</p>
     */
    public boolean ownsSegaPcmTransport() {
        return policy.segaPcmTransport().isPresent();
    }

    /**
     * Enters the driver's SEGA PCM loop with {@code pcm}.
     *
     * <p>The ROM reaches the loop one pass later than the request:
     * {@code zPlaySegaSound} only sets {@code PlaySegaPCMFlag} and returns
     * (Sound/Z80 Sound Driver.asm:2703-2719); the DAC idle loop reads the
     * flag on its next pass and jumps into {@code zPlaySEGAPCM}
     * (:4265-4267). The enter block is written here so the transport's first
     * write opens the window the caller's next service closes.</p>
     */
    public void beginSegaPcmTransport(byte[] pcm) {
        requireInstalled();
        byte[] sample = Objects.requireNonNull(pcm, "pcm").clone();
        SmpsSegaPcmTransport transport = policy.segaPcmTransport()
                .orElseThrow(() -> new IllegalStateException(
                        "this SMPS profile does not own the SEGA PCM"
                                + " transport"));
        if (segaPcmTransport != null) {
            endSegaPcmTransport();
        }
        segaPcmTransport = new SegaPcmTransport(transport, sample);
        // The ROM's loop writes its byte first and takes the djnz delay
        // afterwards (Sound/Z80 Sound Driver.asm:4400-4413), so the first
        // sample is due the moment the loop is entered.
        segaPcmTransport.cycleAccumulator = segaPcmByteCost(transport);
        withPort(driverIdentity, port -> {
            applyProgram(port, transport.enter());
            return null;
        });
    }

    /**
     * Presents {@code cmd_StopSEGA} to the running transport. The ROM's loop
     * compares {@code zMusicNumber} once per byte and breaks at that
     * boundary (Sound/Z80 Sound Driver.asm:4394-4397), so the stop takes
     * effect at the next sample, not mid-byte.
     */
    public void requestSegaPcmTransportStop() {
        requireInstalled();
        if (segaPcmTransport != null) {
            segaPcmTransport.stopRequested = true;
        }
    }

    /** Whether a SEGA PCM transport currently holds the driver. */
    public boolean segaPcmTransportActive() {
        requireActive();
        return segaPcmTransport != null;
    }

    /**
     * Renders while the transport holds the bus, presenting one sample byte
     * every {@link SmpsSegaPcmTransport#z80CyclesPerByte()} Z80 cycles of
     * rendered time. The bytes are ordinary physical writes, so the chip's
     * DAC renders them exactly as it renders the driver's own.
     */
    /** One sample byte's loop cost, in Z80 cycles times output frames. */
    private long segaPcmByteCost(SmpsSegaPcmTransport transport) {
        return (long) transport.z80CyclesPerByte()
                * (long) Math.round(device.outputSampleRate());
    }

    private int renderSegaPcmTransport(
            short[] target, int offsetSamples, int stereoFrames) {
        int rendered = 0;
        while (rendered < stereoFrames && segaPcmTransport != null) {
            SegaPcmTransport active = segaPcmTransport;
            long cyclesPerByte = segaPcmByteCost(active.transport);
            if (active.cycleAccumulator >= cyclesPerByte) {
                active.cycleAccumulator -= cyclesPerByte;
                advanceSegaPcmTransport(active);
                continue;
            }
            long remainingCycles = cyclesPerByte - active.cycleAccumulator;
            int framesUntilByte = (int) Math.min(stereoFrames - rendered,
                    Math.max(1L, (remainingCycles
                            + SmpsSegaPcmTransport.Z80_CLOCK_HZ - 1)
                            / SmpsSegaPcmTransport.Z80_CLOCK_HZ));
            device.renderFrames(target, offsetSamples + rendered * 2,
                    framesUntilByte);
            active.cycleAccumulator += (long) framesUntilByte
                    * SmpsSegaPcmTransport.Z80_CLOCK_HZ;
            rendered += framesUntilByte;
        }
        if (rendered < stereoFrames) {
            device.renderFrames(target, offsetSamples + rendered * 2,
                    stereoFrames - rendered);
            rendered = stereoFrames;
        }
        return rendered;
    }

    /** Sends one sample byte, or leaves the loop at its ROM exit condition. */
    private void advanceSegaPcmTransport(SegaPcmTransport active) {
        if (active.stopRequested || active.cursor >= active.pcm.length) {
            endSegaPcmTransport();
            return;
        }
        int value = active.pcm[active.cursor++] & 0xFF;
        SmpsSegaPcmTransport transport = active.transport;
        withPort(driverIdentity, port -> {
            port.writeFm(transport.dataPort(), transport.dataRegister(),
                    value);
            return null;
        });
    }

    /**
     * Leaves through the driver's own DAC disposition. S1 returns to idle
     * without a write; S2 restores its music DAC flag; S3K disables the DAC.
     */
    private void endSegaPcmTransport() {
        segaPcmTransport = null;
        withPort(driverIdentity, port -> {
            applyProgram(port, policy.exitSegaPcmTransport(musicFmDacTrackCount));
            return null;
        });
    }

    /** Preserves standalone sample-owned cadence without a driver-owned device. */
    int readDirect(short[] target, int length) {
        requireInstalled();
        return driver.readDirect(target, length, directRenderer);
    }

    SmpsPhysicalDevice.Snapshot capturePhysicalSnapshotForTesting() {
        requireActive();
        return device.captureSnapshot();
    }

    void restorePhysicalSnapshotForTesting(
            SmpsPhysicalDevice.Snapshot snapshot, DacData selectedDac) {
        requireActive();
        device.restoreSnapshot(snapshot, selectedDac);
    }

    public void queueActivation(
            PreparedSmpsMusicActivation activation) {
        queueActivation(activation, true);
    }

    private void queueActivation(
            PreparedSmpsMusicActivation activation, boolean ordinaryLoad) {
        requireInstalled();
        PreparedSmpsMusicActivation resolved = Objects.requireNonNull(
                activation, "activation");
        interruptSegaPcmForRequest();
        SmpsLogicalTransitionPolicy.Result transition =
                resolved.logicalPolicy().prepareMusicStart(
                        driver.captureSnapshot(),
                        resolved.incomingMusic());
        boolean resetsTempo = ordinaryLoad
                && resolved.logicalPolicy().resetsTempoOnMusicStart();
        SmpsLoadReadiness.Context context = new SmpsLoadReadiness.Context(
                driver.getRegion(), !resetsTempo && speedShoesEnabled);
        SmpsLoadReadiness.Work readiness = resolved.readiness().begin(context);
        if (resetsTempo) {
            speedShoesEnabled = false;
            speedMultiplier = 1;
            // The same ordinary-load boundary abandons any saved song;
            // retain neither its tracks nor its old tempo for a later restore.
            clearOverrides();
        }
        if (resolved.readiness().immediate()) {
            restoreLogicalWithoutWrites(transition.logical());
            applyCurrentLogicalControls();
        } else {
            withPort(driverIdentity, port -> {
                applyProgram(port, policy.beginMusicLoad());
                return null;
            });
            restoreLogicalWithoutWrites(emptyLogicalSnapshot(
                    driver.captureSnapshot()));
        }
        pendingService = new PendingService(
                resolved.activation(), resolved.readiness().immediate()
                        ? null : transition.logical(),
                transition.firstServiceWrites(), resolved.selectedDac(),
                resolved.readiness(), context, readiness);
    }

    public void applyCommand(SmpsSessionCommand command) {
        requireInstalled();
        Objects.requireNonNull(command, "command");
        if (!(command instanceof SmpsSessionCommand.ResetRingAlternation)
                && !(command instanceof SmpsSessionCommand.SetSpeedMultiplier)) {
            interruptSegaPcmForRequest();
        }
        switch (command) {
            case SmpsSessionCommand.AdmitSfx admit ->
                    admitSfx(admit.program());
            case SmpsSessionCommand.StopMusic ignored -> stopMusic();
            case SmpsSessionCommand.StopAllSfx ignored -> stopAllSfx();
            case SmpsSessionCommand.StopSmpsSfx stop -> stopSmpsSfx(stop);
            case SmpsSessionCommand.SilencePsg ignored ->
                    withPort(driverIdentity, port -> {
                        port.applyTransientPsgSilence(
                                policy.silenceAllPsg());
                        return null;
                    });
            case SmpsSessionCommand.PushOverride push ->
                    pushOverride(push.activation());
            case SmpsSessionCommand.SuspendForPcmOverride ignored ->
                    suspendForPcmOverride();
            case SmpsSessionCommand.RestoreOverride ignored ->
                    restoreOverride();
            case SmpsSessionCommand.EndOverride end ->
                    endOverride(end.musicId());
            case SmpsSessionCommand.FadeMusic fade -> {
                prepareFadeOut();
                armFadeOut(fade.steps(), fade.delay());
            }
            case SmpsSessionCommand.SetSpeedMultiplier speed -> {
                speedMultiplier = speed.multiplier();
                applyCurrentLogicalControls();
            }
            case SmpsSessionCommand.SetSpeedShoes speed -> {
                speedShoesEnabled = speed.enabled();
                applyCurrentLogicalControls();
            }
            case SmpsSessionCommand.ChangeMusicTempo tempo -> {
                SmpsSequencer music = driver.firstMusicSequencer();
                if (music != null) {
                    music.updateDividingTiming(tempo.dividingTiming());
                }
            }
            case SmpsSessionCommand.ResetRingAlternation reset ->
                    ringLeft = reset.ringLeft();
            case SmpsSessionCommand.HardReset ignored -> hardReset();
        }
    }

    private void interruptSegaPcmForRequest() {
        if (segaPcmTransport != null && policy.segaPcmInterruptedByRequest()) {
            // The host delivers requests at presentation boundaries. Finish
            // the old transport before a new request mutates driver state.
            endSegaPcmTransport();
        }
    }

    public void retainGlobalStop() {
        requireInstalled();
        interruptSegaPcmForRequest();
        pendingGlobalCommand = SmpsPendingGlobalCommand.STOP_ALL;
    }

    public boolean suppressesSfxDuringOverride() {
        requireInstalled();
        return configuration.statefulCommandPolicy().suppressesSfxDuringOverride();
    }

    /** See {@link SmpsStatefulCommandPolicy#releasesSfxSuppressionAtRestore()}. */
    public boolean releasesSfxSuppressionAtRestore() {
        requireInstalled();
        return configuration.statefulCommandPolicy()
                .releasesSfxSuppressionAtRestore();
    }

    /** See {@link SmpsStatefulCommandPolicy#stopsSfxWhenOverrideStarts()}. */
    public boolean stopsSfxWhenOverrideStarts() {
        requireInstalled();
        return configuration.statefulCommandPolicy().stopsSfxWhenOverrideStarts();
    }

    /**
     * Whether the music song is fading in: the engine's {@code f_fadein_flag}
     * / {@code FadeInFlag}, which lives on the song's own fade state in the
     * S1 and S2 drivers.
     */
    public boolean isMusicFadingIn() {
        requireInstalled();
        SmpsSequencer music = driver.firstMusicSequencer();
        return music != null && music.isFadingIn();
    }

    /**
     * Applies the host policy's complete global stop and clears the matching
     * logical save area at the same physical ownership boundary. Package
     * access lets the isolated direct-read adapter reuse the production
     * operation without exposing the physical port or duplicating policy.
     */
    void applyGlobalStopNow() {
        requireInstalled();
        pendingService = null;
        clearOverrides();
        // S3K zStopAllSound clears zTempoSpeedup before silencing channels
        // (pinned skdisasm D:2460-2521); normalized controls are off / 1x.
        speedShoesEnabled = false;
        speedMultiplier = 1;
        ringLeft = true;
        Consumer<SmpsPhysicalPort> silence = port -> {
            applyProgram(port, policy.stopAll());
            port.silenceOutput();
        };
        // A terminal fade arrives inside the driver's existing write epoch.
        // Reuse that capability; opening another epoch would reject ownership.
        if (openOwner != null) {
            silence.accept(new PortCapability(openOwner, openEpoch));
        } else {
            withPort(driverIdentity, port -> {
                silence.accept(port);
                return null;
            });
        }
        restoreLogicalWithoutWrites(emptyLogicalSnapshot(
                driver.captureSnapshot()));
        pendingGlobalCommand = SmpsPendingGlobalCommand.NONE;
    }

    /** Current retained tempo control for presentation metadata after host commands. */
    public boolean speedShoesEnabled() {
        requireActive();
        return speedShoesEnabled;
    }

    public SmpsDriverSessionSnapshot captureSnapshot() {
        requireActive();
        return new SmpsDriverSessionSnapshot(
                initialized,
                pendingGlobalCommand,
                profile,
                selectedDacSource,
                speedShoesEnabled,
                speedMultiplier,
                ringLeft,
                musicFmDacTrackCount,
                segaPcmTransport == null ? null
                        : new SmpsSegaPcmTransportSnapshot(
                                segaPcmTransport.pcm,
                                segaPcmTransport.cursor,
                                segaPcmTransport.cycleAccumulator,
                                segaPcmTransport.stopRequested),
                device.captureSnapshot());
    }

    /** Captures the one logical driver memento, including override save RAM. */
    public SmpsDriverSnapshot captureLogicalSnapshot() {
        requireInstalled();
        SmpsDriverSnapshot current = driver.captureSnapshot();
        List<SmpsDriverSnapshot.SavedOverride> saved =
                new ArrayList<>(overrideCount);
        for (int index = 0; index < overrideCount; index++) {
            saved.add(new SmpsDriverSnapshot.SavedOverride(
                    copyWithSessionState(
                            overrideStack[index].logical(), List.of(),
                            snapshotPendingService(
                                    overrideStack[index]
                                            .pendingService()))));
        }
        return copyWithSessionState(current, saved,
                snapshotPendingService(pendingService));
    }

    public PreparedRestore prepareRestore(
            SmpsDriverSessionSnapshot sessionSnapshot,
            SmpsDriverSnapshot logicalSnapshot,
            DacDependencyResolver dependencies) {
        requireInstalled();
        SmpsDriverSessionSnapshot resolvedSession =
                Objects.requireNonNull(sessionSnapshot, "sessionSnapshot");
        SmpsDriverSnapshot resolvedLogical =
                Objects.requireNonNull(logicalSnapshot, "logicalSnapshot");
        DacDependencyResolver resolver =
                Objects.requireNonNull(dependencies, "dependencies");
        if (!profile.equals(resolvedSession.profile())) {
            throw new IllegalArgumentException(
                    "SMPS session profile fingerprint does not match");
        }
        if (!profile.settings().equals(
                resolvedSession.physical().settings())) {
            throw new IllegalArgumentException(
                    "SMPS physical snapshot settings do not match");
        }
        validateLogicalSnapshot(resolvedLogical);
        DacData resolvedDac = resolvedSession.selectedDacSource() == null
                ? null : Objects.requireNonNull(
                        resolver.resolve(
                                resolvedSession.selectedDacSource()),
                        "resolved DAC dependency");
        List<PreparedSavedOverride> savedOverrides =
                new ArrayList<>(resolvedLogical.savedOverrides().size());
        for (SmpsDriverSnapshot.SavedOverride saved
                : resolvedLogical.savedOverrides()) {
            SmpsDacSelection selected = resolveSelectedDac(
                    saved.logical(), resolver);
            SmpsLogicalTransitionPolicy savedPolicy =
                    musicId(saved.logical()) < 0
                            ? null : policyForSavedMusic(saved.logical());
            savedOverrides.add(new PreparedSavedOverride(
                    saved.logical(), savedPolicy,
                    selected, preparePendingService(
                            saved.logical().pendingService(), resolver)));
        }
        PreparedPendingService preparedPending = preparePendingService(
                resolvedLogical.pendingService(), resolver);
        return new PreparedRestore(
                resolvedSession, resolvedLogical, resolvedDac,
                savedOverrides, preparedPending);
    }

    public void commitRestore(PreparedRestore restore) {
        requireInstalled();
        PreparedRestore resolved = Objects.requireNonNull(
                restore, "restore");
        if (!profile.equals(resolved.session().profile())) {
            throw new IllegalArgumentException(
                    "prepared restore belongs to another session profile");
        }
        restoreLogicalWithoutWrites(resolved.logical());
        device.restoreSnapshot(
                resolved.session().physical(), resolved.resolvedDac());
        initialized = resolved.session().initialized();
        pendingGlobalCommand =
                resolved.session().pendingGlobalCommand();
        selectedDacSource = resolved.session().selectedDacSource();
        speedShoesEnabled = resolved.session().speedShoesEnabled();
        speedMultiplier = resolved.session().speedMultiplier();
        ringLeft = resolved.session().ringLeft();
        musicFmDacTrackCount = resolved.session().musicFmDacTrackCount();
        segaPcmTransport = materializeSegaPcmTransport(
                resolved.session().segaPcmTransport());
        pendingService = materializePendingService(
                resolved.pendingService());
        clearOverrides();
        for (PreparedSavedOverride saved : resolved.savedOverrides()) {
            if (overrideCount == overrideStack.length) {
                throw new IllegalArgumentException(
                        "prepared override stack exceeds session capacity");
            }
            overrideStack[overrideCount++] = new SavedOverride(
                    withoutSessionMetadata(saved.logical()),
                    saved.policy(), saved.selectedDac(),
                    materializePendingService(saved.pendingService()));
        }
        applyCurrentLogicalControls();
    }

    public LiveMutationToken captureLiveMutation() {
        requireActive();
        if (transactionOpen) {
            throw new IllegalStateException(
                    "an SMPS session mutation is already active");
        }
        transactionOpen = true;
        try {
            return new SessionLiveMutation(device.captureSessionMutation());
        } catch (RuntimeException failure) {
            transactionOpen = false;
            if (mutationOverrides != null) Arrays.fill(mutationOverrides, null);
            throw failure;
        }
    }

    public void commitLiveMutation(LiveMutationToken token) {
        requireActive();
        SessionLiveMutation state = requireLiveMutation(token);
        requireUnconsumed(state);
        if (!transactionOpen) {
            throw new IllegalStateException(
                    "SMPS session mutation is not active");
        }
        if (!state.commitPrepared) {
            prepareLiveMutationCommit(token);
        }
        state.consumed = true;
        transactionOpen = false;
        Arrays.fill(mutationOverrides, null);
        if (state.logical != null && driver == state.driverIdentity) {
            driver.releaseLiveCommandMutation(state.logical);
        }
    }

    /** Validates commit while the composite owner can still roll back. */
    public void prepareLiveMutationCommit(LiveMutationToken token) {
        requireActive();
        SessionLiveMutation state = requireLiveMutation(token);
        requireUnconsumed(state);
        if (!transactionOpen) {
            throw new IllegalStateException(
                    "SMPS session mutation is not active");
        }
        if (state.commitPrepared) {
            throw new IllegalStateException(
                    "SMPS session mutation commit is already prepared");
        }
        state.commitPrepared = true;
    }

    public void rollbackLiveMutation(LiveMutationToken token) {
        requireActive();
        SessionLiveMutation state = requireLiveMutation(token);
        requireUnconsumed(state);
        if (!transactionOpen) {
            throw new IllegalStateException(
                    "SMPS session mutation is not active");
        }
        RuntimeException primary = null;
        try {
            if (state.logical != null) {
                if (driver != state.driverIdentity) {
                    throw new IllegalStateException(
                            "persistent driver identity changed during mutation");
                }
                driver.rollbackLiveCommandMutation(state.logical);
            } else if (driver != null) {
                throw new IllegalStateException(
                        "session was installed during a live mutation");
            }
        } catch (RuntimeException failure) {
            primary = failure;
        }
        try {
            device.rollbackLiveMutation(state.physical);
        } catch (RuntimeException failure) {
            if (primary == null) {
                primary = failure;
            } else {
                primary.addSuppressed(failure);
            }
        }
        initialized = state.initialized;
        pendingGlobalCommand = state.pendingGlobalCommand;
        selectedDacSource = state.selectedDacSource;
        pendingService = state.pendingService;
        clearOverrides();
        System.arraycopy(state.overrides, 0,
                overrideStack, 0, state.overrideCount);
        overrideCount = state.overrideCount;
        speedShoesEnabled = state.speedShoesEnabled;
        speedMultiplier = state.speedMultiplier;
        ringLeft = state.ringLeft;
        musicFmDacTrackCount = state.musicFmDacTrackCount;
        segaPcmTransport = state.segaPcmTransport;
        truncateDiagnostics(state.diagnosticCount);
        state.consumed = true;
        transactionOpen = false;
        Arrays.fill(mutationOverrides, null);
        // The aborted raw strobes remain private, but the restored chip state
        // and monotonic diagnostic clocks require a surviving segment break.
        device.reportPhysicalTimelineBoundary(
                ChipWriteObserver.PhysicalTimelineBoundary.TRANSACTION_ROLLBACK);
        if (primary != null) {
            throw primary;
        }
    }

    /** Publishes the committed transaction's diagnostics exactly once. */
    public void publishCommittedDiagnostics() {
        requireActive();
        if (transactionOpen) {
            throw new IllegalStateException(
                    "cannot publish diagnostics before commit");
        }
        if (diagnostics.isEmpty()) {
            return;
        }
        diagnostics.publishAll(chipWriteObserver, diagnosticErrorSink);
    }

    public void applyChannelMasks(int fmMask, int psgMask) {
        requireActive();
        withPort(driverIdentity, port -> {
            for (int channel = 0; channel < 6; channel++) {
                port.setFmMute(channel,
                        (fmMask & (1 << channel)) != 0);
            }
            for (int channel = 0; channel < 4; channel++) {
                port.setPsgMute(channel,
                        (psgMask & (1 << channel)) != 0);
            }
            return null;
        });
    }

    public void setDriverServiceObserver(
            SmpsDriverServiceObserver observer) {
        requireActive();
        driverServiceObserver = Objects.requireNonNull(
                observer, "observer");
        if (driver != null) {
            installDriverObservers();
        }
    }

    public void setChipWriteObserver(ChipWriteObserver observer) {
        requireActive();
        chipWriteObserver = Objects.requireNonNull(observer, "observer");
    }

    void setPhysicalWriteInterceptorForTesting(
            Consumer<SmpsChipWrite> interceptor) {
        requireActive();
        physicalWriteInterceptorForTesting = Objects.requireNonNull(
                interceptor, "interceptor");
    }

    void setStatefulLogicalMutationInterceptorForTesting(
            Runnable interceptor) {
        requireActive();
        statefulLogicalMutationInterceptorForTesting = Objects.requireNonNull(
                interceptor, "interceptor");
    }

    public void setSfxContentionObserver(
            SfxContentionObserver observer) {
        requireActive();
        sfxContentionObserver = Objects.requireNonNull(
                observer, "observer");
        if (driver != null) {
            installDriverObservers();
        }
    }

    public void setDiagnosticErrorSink(
            Consumer<RuntimeException> errorSink) {
        requireActive();
        diagnosticErrorSink = Objects.requireNonNull(
                errorSink, "errorSink");
    }

    <T> T withPort(
            SmpsDriverServiceObserver.DriverIdentity owner,
            Function<SmpsPhysicalPort, T> action) {
        requireActive();
        if (openOwner != null) {
            throw new IllegalStateException(
                    "an SMPS physical capability epoch is already open");
        }
        openOwner = Objects.requireNonNull(owner, "owner");
        openEpoch = Math.incrementExact(nextEpoch);
        nextEpoch = openEpoch;
        PortCapability capability =
                new PortCapability(openOwner, openEpoch);
        try {
            return Objects.requireNonNull(action, "action")
                    .apply(capability);
        } finally {
            openOwner = null;
            openEpoch = 0;
        }
    }

    SmpsPhysicalPort openTestEpoch(
            SmpsDriverServiceObserver.DriverIdentity owner) {
        requireActive();
        if (openOwner != null) {
            throw new IllegalStateException(
                    "an SMPS physical capability epoch is already open");
        }
        openOwner = Objects.requireNonNull(owner, "owner");
        openEpoch = Math.incrementExact(nextEpoch);
        nextEpoch = openEpoch;
        return new PortCapability(openOwner, openEpoch);
    }

    void closeTestEpoch(long epoch) {
        requireActive();
        if (openOwner == null || epoch != openEpoch) {
            throw new IllegalStateException(
                    "SMPS physical capability epoch is not active");
        }
        openOwner = null;
        openEpoch = 0;
    }

    SmpsDriver logicalDriverForTesting() {
        requireInstalled();
        return driver;
    }

    Object physicalIdentityForTesting() {
        requireActive();
        return device.identityForTesting();
    }

    boolean hasPendingActivation() {
        requireActive();
        return pendingService != null
                && pendingService.activation() != null;
    }

    /** True while ROM-owned load work prevents mailbox/request service. */
    public boolean blocksForwardRequestConsumption() {
        requireInstalled();
        return pendingService != null
                && !pendingService.plan.immediate();
    }

    int serviceInvocationCountForTesting() {
        requireActive();
        return serviceInvocationCount;
    }

    int renderInvocationCountForTesting() {
        requireActive();
        return device.renderInvocationCountForTesting();
    }

    long renderedStereoFramesForTesting() {
        requireActive();
        return device.renderedStereoFramesForTesting();
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (closed) {
            return;
        }
        if (transactionOpen) {
            throw new IllegalStateException(
                    "cannot close during a live mutation");
        }
        openOwner = null;
        openEpoch = 0;
        diagnostics.clear();
        device.close();
        closed = true;
    }

    private void admitSfx(PreparedSmpsSfxProgram program) {
        PreparedSmpsSfxProgram resolved = Objects.requireNonNull(
                program, "program");
        withPort(driverIdentity, port -> {
            PreparedSfxAdmission extension =
                    driver.prepareContinuousSfxExtension(
                            resolved.continuousSfxId(),
                            resolved.continuousTrackCount());
            if (extension != null) {
                driver.commitSfxAdmission(extension);
                return null;
            }
            SmpsSequencer sequencer = materialize(
                    resolved.incomingSfx());
            PreparedSfxAdmission admission =
                    driver.prepareNewSfxAdmission(
                            sequencer,
                            resolved.continuousSfxId(),
                            resolved.continuousTrackCount());
            sequencer.beginSfxAdmission();
            driver.commitSfxAdmission(admission);
            return null;
        });
    }

    private SmpsSequencer materialize(
            SmpsDriverSnapshot.SequencerEntry entry) {
        SmpsSequencer sequencer = new SmpsSequencer(
                entry.smpsData(), entry.dacData(),
                driver, driver, entry.audioManager(), entry.config(),
                entry.source(), entry.sourceDescriptorTrust());
        sequencer.restoreSnapshot(entry.snapshot());
        sequencer.setIsSfx(entry.sfx());
        if (entry.sfx() && entry.fallbackVoiceSource() == null) {
            SmpsSequencer music = driver.firstMusicSequencer();
            if (music != null) {
                sequencer.setFallbackVoiceData(music.getSmpsData());
            }
        }
        return sequencer;
    }

    /**
     * Runs {@code zFadeInToPrevious}'s body on the music that has just come
     * back from beneath the extra-life jingle (skdisasm Sound/Z80 Sound
     * Driver.asm:2725-2789). The routine restores the saved track region and
     * then, per track, marks it playing, leaves the PSG tracks overridden,
     * clears the overriding bit on the FM ones, lowers their volume by 40h and
     * resends their instrument, before arming the fade in with 40h steps and a
     * delay of 2. {@code SmpsSequencer.triggerFadeIn} is that body; without
     * this call the restored song simply reappeared at full volume with the
     * voices it happened to be left with.
     */
    public void fadeInRestoredMusic() {
        SmpsSequencer music = driver.firstMusicSequencer();
        if (music != null) {
            music.triggerFadeIn();
        }
    }

    private void stopMusic() {
        stopMusic(true);
    }

    private void stopMusic(boolean discardOverrides) {
        SmpsSequencer music = driver.firstMusicSequencer();
        if (music != null) {
            withPort(driverIdentity, port -> {
                for (SmpsSequencer.Track track : music.getTracks()) {
                    if (track.overridden) {
                        continue;
                    }
                    switch (track.type) {
                        case FM -> {
                            port.forceSilenceFmChannel(track.channelId);
                            int channel = track.channelId < 3
                                    ? track.channelId
                                    : track.channelId + 1;
                            port.writeFm(0, 0x28, channel);
                        }
                        case PSG -> port.writePsg(0x80
                                | (track.channelId << 5) | 0x1F);
                        case DAC -> port.stopDac();
                    }
                }
                return null;
            });
        }
        restoreLogicalWithoutWrites(filterLogicalSnapshot(
                driver.captureSnapshot(), true));
        pendingService = null;
        if (discardOverrides) {
            clearOverrides();
        }
        if (driver.captureSnapshot().sequencers().isEmpty()) {
            withPort(driverIdentity, port -> {
                port.silenceOutput();
                return null;
            });
        }
    }

    private void stopAllSfx() {
        withPort(driverIdentity, port -> {
            driver.stopAllSfx();
            if (driver.firstMusicSequencer() == null) {
                port.silenceOutput();
            }
            return null;
        });
    }

    /** Applies a prepared host operation without donor/game-name dispatch. */
    private void stopSmpsSfx(SmpsSessionCommand.StopSmpsSfx command) {
        SmpsStatefulCommandOperation operation = prepareStatefulCommand(command);
        if (!operation.handled()) {
            driver.stopAllSfxWithoutRestoreWrites();
            return;
        }
        if (operation.rejected()) {
            return;
        }
        boolean localTransaction = !transactionOpen;
        LiveMutationToken mutation = localTransaction
                ? captureLiveMutation() : null;
        try {
            withPort(driverIdentity, port -> {
                applyProgram(port, operation.writes());
                return null;
            });
            // zStopSFX clears only SFX slot state. Preserve continuous SFX,
            // raw PCM, pending service and all host session controls.
            driver.stopAllSfxWithoutRestoreWrites();
            // This seam intentionally follows the logical mutation. It proves
            // the enclosing live-mutation rollback restores both the exact
            // physical program and the driver/session state before lifecycle
            // publication commits.
            statefulLogicalMutationInterceptorForTesting.run();
            if (localTransaction) {
                prepareLiveMutationCommit(mutation);
                commitLiveMutation(mutation);
                publishCommittedDiagnostics();
            }
        } catch (RuntimeException failure) {
            if (localTransaction) {
                try {
                    rollbackLiveMutation(mutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private void pushOverride(
            PreparedSmpsMusicActivation activation) {
        SmpsDriverSnapshot current = driver.captureSnapshot();
        int activeMusicId = musicId(current);
        if (activeMusicId < 0 || activeMusicId == activation.activation()
                .source().id()) {
            queueActivation(activation, false);
            return;
        }
        if (overrideCount == overrideStack.length) {
            throw new IllegalStateException(
                    "SMPS music override capacity exhausted");
        }
        overrideStack[overrideCount++] = new SavedOverride(
                current,
                policyForSavedMusic(current),
                selectedDacFor(current), pendingService);
        queueActivation(activation, false);
        driver.observeLifecycle(
                SmpsDriverServiceObserver.LifecycleKind.SAVE);
    }

    private void suspendForPcmOverride() {
        if (overrideCount == overrideStack.length) {
            throw new IllegalStateException(
                    "SMPS music override capacity exhausted");
        }
        SmpsDriverSnapshot current = driver.captureSnapshot();
        boolean savesSmpsMusic = musicId(current) >= 0;
        SmpsDriverSnapshot saved = savesSmpsMusic
                ? current : emptyLogicalSnapshot(current);
        overrideStack[overrideCount++] = new SavedOverride(
                saved,
                savesSmpsMusic ? policyForSavedMusic(saved) : null,
                savesSmpsMusic ? selectedDacFor(saved) : null,
                savesSmpsMusic ? pendingService : null);
        stopMusic(false);
        driver.observeLifecycle(
                SmpsDriverServiceObserver.LifecycleKind.SAVE);
    }

    private void restoreOverride() {
        if (overrideCount == 0) {
            return;
        }
        SavedOverride saved = overrideStack[--overrideCount];
        overrideStack[overrideCount] = null;
        if (musicId(saved.logical()) < 0) {
            stopMusic(false);
        } else if (saved.pendingService() != null) {
            restoreLogicalWithoutWrites(saved.logical());
            pendingService = saved.pendingService();
        } else {
            SmpsLogicalTransitionPolicy.Result transition =
                    saved.policy().prepareOverrideRestore(
                            driver.captureSnapshot(), saved.logical());
            restoreLogicalWithoutWrites(transition.logical());
            pendingService = new PendingService(
                    null, null, transition.firstServiceWrites(),
                    saved.selectedDac(), SmpsLoadReadiness.immediatePlan(),
                    new SmpsLoadReadiness.Context(
                            driver.getRegion(),
                            speedShoesEnabled), SmpsLoadReadiness.immediatePlan()
                            .begin(new SmpsLoadReadiness.Context(
                                    driver.getRegion(),
                                    speedShoesEnabled)));
        }
        applyCurrentLogicalControls();
    }

    private void endOverride(int musicId) {
        SmpsSequencer active = driver.firstMusicSequencer();
        if (active != null
                && active.getSourceDescriptor().id() == musicId) {
            restoreOverride();
            return;
        }
        for (int index = overrideCount - 1; index >= 0; index--) {
            SmpsDriverSnapshot saved = overrideStack[index].logical();
            if (musicId(saved) != musicId) {
                continue;
            }
            int remaining = overrideCount - index - 1;
            if (remaining > 0) {
                System.arraycopy(overrideStack, index + 1,
                        overrideStack, index, remaining);
            }
            overrideStack[--overrideCount] = null;
            return;
        }
    }

    /** Pre-service effects for hosts whose SFX slots must stop before the music walk. */
    public void prepareFadeOut() {
        requireInstalled();
        SmpsFadeOutEffects effects = configuration.statefulCommandPolicy().fadeOutEffects();
        if (effects.stopSfx()) {
            stopAllSfx();
        }
        if (effects.clearSpeedShoes()) {
            speedShoesEnabled = false;
            applyCurrentLogicalControls();
        }
    }

    /** Arms the fade at the host's command-dispatch boundary. */
    public void armFadeOut(int steps, int delay) {
        requireInstalled();
        SmpsFadeOutEffects effects = configuration.statefulCommandPolicy().fadeOutEffects();
        if (effects.driverOwnedCounters()) {
            driver.armFadeOut(steps, delay);
        }
        SmpsSequencer music = driver.firstMusicSequencer();
        withPort(driverIdentity, port -> {
            if (music != null) {
                music.triggerFadeOut(steps, delay);
            }
            port.applyTransientPsgSilence(effects.psgSilence());
            return null;
        });
    }

    private void hardReset() {
        pendingService = null;
        pendingGlobalCommand = SmpsPendingGlobalCommand.NONE;
        clearOverrides();
        speedShoesEnabled = false;
        speedMultiplier = 1;
        ringLeft = true;
        restoreLogicalWithoutWrites(emptyLogicalSnapshot(
                driver.captureSnapshot()));
        withPort(driverIdentity, port -> {
            applyProgram(port, policy.boot());
            // zInitAudioDriver jumps into zPlayDigitalAudio and never returns
            // (Sound/Z80 Sound Driver.asm:550-551), so the loop's entry writes
            // follow the init immediately on the physical device.
            applyProgram(port, policy.enterDacIdleLoop());
            port.silenceOutput();
            return null;
        });
        initialized = true;
        selectedDacSource = null;
        driver.observeLifecycle(
                SmpsDriverServiceObserver.LifecycleKind.RESET);
    }

    private void applyCurrentLogicalControls() {
        SmpsSequencer music = driver.firstMusicSequencer();
        if (music != null) {
            music.setSpeedShoes(speedShoesEnabled);
            music.setSpeedMultiplier(speedMultiplier);
        }
    }

    private void restoreLogicalWithoutWrites(
            SmpsDriverSnapshot snapshot) {
        if (logicalMaterialization) {
            throw new IllegalStateException(
                    "SMPS logical materialization cannot be nested");
        }
        logicalMaterialization = true;
        try {
            driver.restoreSnapshot(Objects.requireNonNull(
                    snapshot, "snapshot"));
            SmpsSequencer music = driver.firstMusicSequencer();
            musicFmDacTrackCount = music == null ? 0 : music.getSmpsData().getChannels();
        } finally {
            logicalMaterialization = false;
        }
    }

    private void installDriverObservers() {
        SmpsDriverServiceObserver service = driverServiceObserver;
        if (service == SmpsDriverServiceObserver.NONE) {
            driver.setServiceObserver(SmpsDriverServiceObserver.NONE);
        } else {
            driver.setServiceObserver(new SmpsDriverServiceObserver() {
                @Override
                public void onServiceBegin(ServiceEvent event) {
                    emitDiagnostic(() -> service.onServiceBegin(event));
                }

                @Override
                public void onServiceEnd(
                        ServiceEvent event,
                        SmpsDriverSnapshot snapshot) {
                    emitDiagnostic(() ->
                            service.onServiceEnd(event, snapshot));
                }

                @Override
                public void onLifecycle(LifecycleEvent event) {
                    emitDiagnostic(() -> service.onLifecycle(event));
                }
            });
        }

        SfxContentionObserver contention = sfxContentionObserver;
        if (contention == SfxContentionObserver.NONE) {
            driver.setSfxContentionObserver(SfxContentionObserver.NONE);
        } else {
            driver.setSfxContentionObserver(
                    new SfxContentionObserver() {
                        @Override
                        public void onSfxAdmitted(Admission admission) {
                            emitDiagnostic(() ->
                                    contention.onSfxAdmitted(admission));
                        }

                        @Override
                        public void onRoleArbitrated(
                                Arbitration arbitration) {
                            emitDiagnostic(() ->
                                    contention.onRoleArbitrated(
                                            arbitration));
                        }
                    });
        }
    }

    private void beforePhysicalWrite(SmpsChipWrite write) {
        physicalWriteInterceptorForTesting.accept(write);
    }

    private void emitDiagnostic(Runnable diagnostic) {
        if (transactionOpen) {
            diagnostics.addRunnable(diagnostic);
            return;
        }
        try {
            diagnostic.run();
        } catch (RuntimeException failure) {
            reportDiagnosticFailure(failure);
        }
    }

    private void reportDiagnosticFailure(RuntimeException failure) {
        try {
            diagnosticErrorSink.accept(failure);
        } catch (RuntimeException ignored) {
            // Diagnostic failures cannot influence committed audio state.
        }
    }

    private static void applyProgram(
            SmpsPhysicalPort port, SmpsWriteProgram program) {
        for (SmpsChipWrite write : Objects.requireNonNull(
                program, "program").writes()) {
            if (write instanceof SmpsChipWrite.Ym2612 ym) {
                port.writeFm(ym.port(), ym.register(), ym.value());
            } else if (write instanceof SmpsChipWrite.Psg psg) {
                port.writePsg(psg.value());
            }
        }
    }

    private SmpsDacSelection selectedDacFor(
            SmpsDriverSnapshot snapshot) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : snapshot.sequencers()) {
            if (!entry.sfx() && entry.dacData() != null) {
                return new SmpsDacSelection(
                        entry.source(), entry.dacData());
            }
        }
        return null;
    }

    private static int musicId(SmpsDriverSnapshot snapshot) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : snapshot.sequencers()) {
            if (!entry.sfx()) {
                return entry.source().id();
            }
        }
        return -1;
    }

    private static SmpsLogicalTransitionPolicy policyForSavedMusic(
            SmpsDriverSnapshot snapshot) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : snapshot.sequencers()) {
            if (!entry.sfx()) {
                return SmpsLogicalTransitionPolicies.forConfig(
                        entry.config());
            }
        }
        throw new IllegalArgumentException(
                "override save area contains no music program");
    }

    private static SmpsDacSelection resolveSelectedDac(
            SmpsDriverSnapshot snapshot,
            DacDependencyResolver resolver) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : snapshot.sequencers()) {
            if (!entry.sfx() && entry.dacData() != null) {
                return new SmpsDacSelection(entry.source(),
                        Objects.requireNonNull(
                                resolver.resolve(entry.source()),
                                "resolved saved-override DAC dependency"));
            }
        }
        return null;
    }

    private static PreparedPendingService preparePendingService(
            SmpsDriverSnapshot.PendingService pending,
            DacDependencyResolver resolver) {
        if (pending == null) {
            return null;
        }
        SmpsDacSelection selected = pending.selectedDacSource() == null
                ? null : new SmpsDacSelection(
                        pending.selectedDacSource(),
                        Objects.requireNonNull(
                                resolver.resolve(
                                        pending.selectedDacSource()),
                                "resolved pending-service DAC dependency"));
        SmpsLoadReadiness readiness = pending.activation() == null
                ? SmpsLoadReadiness.immediatePlan()
                : resolver.resolveReadiness(pending.activation().source());
        if (!readiness.provenance(pending.readinessContext()).equals(
                pending.readinessProvenance())) {
            throw new IllegalStateException(
                    "SMPS load-readiness provenance mismatch");
        }
        return new PreparedPendingService(
                pending.activation(), pending.readyLogical(),
                pending.firstServiceWrites(), selected,
                readiness,
                pending.readinessContext(), pending.remainingTStates());
    }

    /** Rebuilds an in-flight transport from a restored session snapshot. */
    private SegaPcmTransport materializeSegaPcmTransport(
            SmpsSegaPcmTransportSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        SegaPcmTransport restored = new SegaPcmTransport(
                policy.segaPcmTransport().orElseThrow(
                        () -> new IllegalArgumentException(
                                "restored SEGA PCM transport does not belong"
                                        + " to this SMPS profile")),
                snapshot.pcm());
        restored.cursor = snapshot.cursor();
        restored.cycleAccumulator = snapshot.cycleAccumulator();
        restored.stopRequested = snapshot.stopRequested();
        return restored;
    }

    private static PendingService materializePendingService(
            PreparedPendingService pending) {
        return pending == null ? null : new PendingService(
                pending.activation(), pending.readyLogical(),
                pending.firstServiceWrites(), pending.selectedDac(),
                pending.readiness(), pending.readinessContext(),
                pending.readiness().resume(pending.readinessContext(),
                        pending.remainingTStates()));
    }

    private static SmpsDriverSnapshot.PendingService
            snapshotPendingService(PendingService pending) {
        return pending == null ? null
                : new SmpsDriverSnapshot.PendingService(
                        pending.activation(),
                        pending.logical(),
                        pending.firstServiceWrites(),
                        pending.selectedDac() == null
                                ? null : pending.selectedDac().source(),
                        pending.plan.provenance(pending.readinessContext),
                        pending.readiness().remainingTStates(),
                        pending.readinessContext);
    }

    private static SmpsDriverSnapshot withoutSessionMetadata(
            SmpsDriverSnapshot snapshot) {
        return copyWithSessionState(snapshot, List.of(), null);
    }

    /** Package-visible for TestSmpsDriverSnapshotCopyCoverageGuard. */
    static SmpsDriverSnapshot copyWithSessionState(
            SmpsDriverSnapshot snapshot,
            List<SmpsDriverSnapshot.SavedOverride> savedOverrides,
            SmpsDriverSnapshot.PendingService pendingService) {
        return new SmpsDriverSnapshot(
                snapshot.region(), snapshot.readMode(),
                snapshot.continuousSfxId(),
                snapshot.continuousSfxFlag(),
                snapshot.contSfxLoopCnt(), snapshot.palUpdateCounter(),
                snapshot.sequencers(), snapshot.fmLockSequencerIds(),
                snapshot.psgLockSequencerIds(), savedOverrides,
                pendingService,
                // The driver's fade counters are as much of its state as the
                // lock table. Dropping them here left a capture and restore
                // during a fade with the attenuation frozen where it stood.
                snapshot.fadeDelay(), snapshot.fadeDelayTimeout(),
                snapshot.fadeOutTimeout(), snapshot.fadeInTimeout(),
                snapshot.driverOwnedFade());
    }

    private static SmpsDriverSnapshot emptyLogicalSnapshot(
            SmpsDriverSnapshot current) {
        return new SmpsDriverSnapshot(
                current.region(), current.readMode(),
                0, false, 0, current.palUpdateCounter(),
                List.of(), new int[] {-1, -1, -1, -1, -1, -1},
                new int[] {-1, -1, -1, -1});
    }

    private static SmpsDriverSnapshot filterLogicalSnapshot(
            SmpsDriverSnapshot current, boolean retainSfx) {
        return filterLogicalSnapshot(current, retainSfx, retainSfx);
    }

    private static SmpsDriverSnapshot filterLogicalSnapshot(
            SmpsDriverSnapshot current,
            boolean retainSfx,
            boolean retainContinuousSfxState) {
        List<SmpsDriverSnapshot.SequencerEntry> source =
                current.sequencers();
        int[] remap = new int[source.size()];
        Arrays.fill(remap, -1);
        List<SmpsDriverSnapshot.SequencerEntry> retained =
                new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index).sfx() == retainSfx) {
                remap[index] = retained.size();
                retained.add(source.get(index));
            }
        }
        return new SmpsDriverSnapshot(
                current.region(), current.readMode(),
                retainContinuousSfxState
                        ? current.continuousSfxId() : 0,
                retainContinuousSfxState
                        && current.continuousSfxFlag(),
                retainContinuousSfxState
                        ? current.contSfxLoopCnt() : 0,
                current.palUpdateCounter(), retained,
                remapLocks(current.fmLockSequencerIds(), remap),
                remapLocks(current.psgLockSequencerIds(), remap));
    }

    private static int[] remapLocks(int[] locks, int[] remap) {
        int[] result = new int[locks.length];
        Arrays.fill(result, -1);
        for (int index = 0; index < locks.length; index++) {
            int prior = locks[index];
            if (prior >= 0 && prior < remap.length) {
                result[index] = remap[prior];
            }
        }
        return result;
    }

    private static void validateLogicalSnapshot(
            SmpsDriverSnapshot logical) {
        for (SmpsDriverSnapshot.SequencerEntry entry
                : logical.sequencers()) {
            if (entry.sourceDescriptorTrust()
                    == SmpsSequencer.SourceDescriptorTrust
                    .PRECOMPUTED_IMMUTABLE) {
                continue;
            }
            SmpsSourceDescriptor actual =
                    SmpsSourceDescriptor.from(
                            entry.source().dependencyGeneration(),
                            entry.smpsData(),
                            entry.source().dataLength(),
                            entry.source().dataHash());
            if (!entry.source().matches(actual)) {
                throw new IllegalStateException(
                        "logical snapshot dependency does not match source "
                                + entry.source());
            }
        }
        for (SmpsDriverSnapshot.SavedOverride saved
                : logical.savedOverrides()) {
            validateLogicalSnapshot(saved.logical());
        }
    }

    private void clearOverrides() {
        Arrays.fill(overrideStack, 0, overrideCount, null);
        overrideCount = 0;
    }

    private void truncateDiagnostics(int size) {
        diagnostics.truncate(size);
    }

    private void requireOpen(PortCapability capability) {
        requireActive();
        if (capability.ownerSessionIdentity != sessionIdentity
                || capability.epoch != openEpoch
                || !capability.owner.equals(openOwner)) {
            throw new IllegalStateException(
                    "SMPS physical capability is not active");
        }
    }

    private AdmissionState requireAdmissionToken(
            SmpsPhysicalPort.AdmissionToken token) {
        if (!(token instanceof SmpsDriverSession.AdmissionState state)
                || state.ownerSessionIdentity != sessionIdentity
                || state.ownerDevice != device
                || state.epoch != openEpoch
                || !state.owner.equals(openOwner)) {
            throw new IllegalArgumentException(
                    "SMPS admission token does not belong to this capability");
        }
        return state;
    }

    private SessionLiveMutation requireLiveMutation(
            LiveMutationToken token) {
        if (!(token instanceof SmpsDriverSession.SessionLiveMutation state)
                || state.ownerIdentity != sessionIdentity) {
            throw new IllegalArgumentException(
                    "session mutation token belongs to another session");
        }
        return state;
    }

    private static void requireUnconsumed(SessionLiveMutation state) {
        if (state.consumed) {
            throw new IllegalStateException(
                    "session mutation token has already been consumed");
        }
    }

    private void requireInstalled() {
        requireActive();
        if (driver == null) {
            throw new IllegalStateException(
                    "SMPS driver session is not installed");
        }
    }

    private void requireActive() {
        requireOwnerThread();
        if (closed) {
            throw new IllegalStateException("SMPS driver session is closed");
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "SMPS driver session accessed off its owner thread");
        }
    }




}
