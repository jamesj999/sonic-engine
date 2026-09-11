package com.openggf.tools.audio.parity.s3k;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsChipWrite;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.session.SmpsMusicActivation;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.session.SmpsSegaPcmTransport;
import com.openggf.audio.session.SmpsWriteProgram;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsPhysicalPolicy;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import com.openggf.tools.audio.parity.AudioParityChipWrite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * ROM-backed headless host that drives the engine's S3K SMPS driver with the
 * request timeline of an oracle reference capture and records, per complete driver service,
 * the ordered chip writes plus a driver-RAM-shaped state export.
 *
 * <p>The reference's per-tick mailbox bytes are driver <em>inputs</em> (the
 * 68k-side {@code zMusicNumber}/{@code zSFXNumber0/1} writes the coming
 * invocation consumes) — the analogue of the S1 tool playing the GHZ song.
 * All compared values (RAM state, write stream) come from the engine alone.
 *
 * <p>Request dispatch mirrors zPlaySoundByIndex (D:1641-1665): music
 * 01h-32h, credits DCh, SFX 33h-DBh, E0h/E2h/E6h-FEh stop-all, E4h stop-SFX.
 * E1h/E5h fades are not yet modelled by this capture host and are recorded
 * as unsupported (the tick keeps advancing). E3h executes the same
 * source-owned physical-policy program as production.
 */
public final class S3kOpenGgfAudioCapture {
    private static final double SAMPLE_RATE = 44_100.0;

    private S3kOpenGgfAudioCapture() {
    }

    public record CaptureResult(List<S3kAudioTick> ticks, List<String> unsupportedRequests) {
        public CaptureResult {
            ticks = List.copyOf(ticks);
            unsupportedRequests = List.copyOf(unsupportedRequests);
        }
    }

    public static CaptureResult capture(Path romPath, List<S3kAudioTick> reference,
            Integer corruptWriteTick) {
        Objects.requireNonNull(romPath, "romPath");
        verifyRomIdentity(romPath);
        Rom rom = new Rom();
        if (!rom.open(romPath.toString())) {
            throw new IllegalArgumentException("cannot open verified S3K ROM");
        }
        try (rom) {
            Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
            DacData dacData = loader.loadDacData();
            try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                    "s3k-oracle", 0,
                    new SmpsPhysicalDevice.Settings(
                            SAMPLE_RATE, false),
                    Sonic3kSmpsPhysicalPolicy.INSTANCE,
                    ChipWriteObserver.NONE,
                    new com.openggf.audio.session.SmpsDriverSessionConfiguration(
                            com.openggf.game.sonic3k.audio.Sonic3kStatefulCommandPolicy.INSTANCE))) {
            SmpsDriver driver = stream.logicalDriver();
            driver.setRegion(SmpsSequencer.Region.NTSC);
            List<AudioParityChipWrite> writes = new ArrayList<>();
            stream.setChipWriteObserver(new ChipWriteObserver() {
                @Override
                public void onYm2612Write(int port, int register, int value) {
                    writes.add(AudioParityChipWrite.ym2612(port, register, value));
                }

                @Override
                public void onPsgWrite(int value) {
                    writes.add(AudioParityChipWrite.psg(value));
                }
            });

            List<String> unsupported = new ArrayList<>();
            List<S3kAudioTick> ticks = new ArrayList<>(reference.size());
            if (reference.isEmpty()) {
                return new CaptureResult(ticks, unsupported);
            }

            // zInitAudioDriver owns one complete pre-V-int service. Its busy
            // loop has no engine-visible effect; the observable boundary is
            // zStopAllSound followed by the initial driver-variable stores
            // (D:523-551,2460-2521). The reference projector finds completion
            // through zPalDblUpdCounter=5, not through a movie frame.
            applyProgram(driver,
                    Sonic3kSmpsPhysicalPolicy.INSTANCE.boot());
            S3kAudioTick bootReference = reference.getFirst();
            addTick(ticks, driver, writes, bootReference, corruptWriteTick);
            // The init's last write is zStopAllSound's 27h (D:2513-2519); it
            // then jumps into zPlayDigitalAudio (D:550-551, :4256-4260), whose
            // 2Bh belongs to the window closed by the first zVInt return.
            applyProgram(driver,
                    Sonic3kSmpsPhysicalPolicy.INSTANCE.enterDacIdleLoop());

            // One V-int of Z80 time is the whole of what the driver is not
            // servicing: zPlayDigitalAudio streams DAC bytes for all of it
            // (Sound/Z80 Sound Driver.asm:4296-4351). The frame length is the
            // driver's own region cadence, not a measured quantity.
            int framesPerVint = (int) Math.round(
                    SAMPLE_RATE / SmpsSequencer.Region.NTSC.frameRate);
            short[] dacScratch = new short[framesPerVint * 2];

            boolean[] segaPending = new boolean[1];
            RingDispatch ringDispatch = new RingDispatch();
            for (int index = 1; index < reference.size(); index++) {
                S3kAudioTick referenceTick = reference.get(index);
                int ordinal = referenceTick.ordinal();
                if (segaPending[0]) {
                    // zPlaySegaSound only sets PlaySegaPCMFlag and returns
                    // (D:2703-2719); the DAC idle loop reads it on the next
                    // pass and jumps into zPlaySEGAPCM (D:4265-4267), which
                    // then holds the bus under di for the whole sample
                    // (D:4372-4424). The transport is therefore the whole of
                    // the service window after the request's own.
                    segaPending[0] = false;
                    applyProgram(driver, segaPcmProgram(rom));
                    addTick(ticks, driver, writes, referenceTick, corruptWriteTick);
                    continue;
                }
                // The Z80 spends the whole inter-V-int interval in
                // zPlayDigitalAudio, so this window's DAC bytes (and the
                // 2Bh = 0 of any sample that finishes inside it) precede the
                // service that closes the window.
                stream.advanceDacIdleLoop(dacScratch, framesPerVint);
                // zUpdateEverything walks the SFX tracks and runs TempoWait
                // and both fade handlers before it loads zMusicNumber and
                // reaches zFillSoundQueue (Sound/Z80 Sound Driver.asm:653-701),
                // so the service that consumes a request has already updated
                // the tracks that were playing when it arrived. Handing the
                // request to the driver rather than applying it here is what
                // puts it at that point instead of ahead of the whole service.
                for (int request : referenceTick.mailbox()) {
                    if (request != 0) {
                        driver.submitServiceRequest(() -> dispatch(
                                ringDispatch.select(request),
                                loader, dacData, stream, unsupported,
                                ordinal, segaPending));
                    }
                }
                driver.serviceOuterFrame();
                addTick(ticks, driver, writes, referenceTick, corruptWriteTick);
                if (driver.consumeDacIdleLoopPass()) {
                    // The V-int that queued the sample has returned; the idle
                    // loop's next pass finds zDACIndex non-zero and enables the
                    // DAC (D:4269-4276), so the write opens the following
                    // service's window rather than closing this one.
                    applyProgram(driver, Sonic3kSmpsPhysicalPolicy.INSTANCE
                            .enableDacFromIdleLoop());
                }
            }
            return new CaptureResult(ticks, unsupported);
            }
        }
    }

    /**
     * Diagnostic model of the retail request transform, deliberately local to
     * this capture host.  {@code zPlaySound_CheckRing} toggles
     * {@code zRingSpeaker} only when raw request 33h reaches dispatch, then
     * selects Sound34/Sound33 (Sound/Z80 Sound Driver.asm:1919-1928,
     * shipped {@code fix_sndbugs=0}).  Keeping selection inside the pending
     * service callback prevents submission itself from advancing the latch.
     * The live presentation still lacks S3K's complete three-slot mailbox
     * consumer and must not treat this diagnostic state as production parity.
     */
    static final class RingDispatch {
        private boolean leftNext = true;

        int select(int request) {
            if (request != 0x33) {
                return request;
            }
            int selected = leftNext ? 0x34 : 0x33;
            leftNext = !leftNext;
            return selected;
        }

        boolean leftNext() {
            return leftNext;
        }
    }

    private static void addTick(List<S3kAudioTick> ticks, SmpsDriver driver,
            List<AudioParityChipWrite> writes, S3kAudioTick referenceTick,
            Integer corruptWriteTick) {
        int ordinal = referenceTick.ordinal();
        if (corruptWriteTick != null && corruptWriteTick == ordinal && !writes.isEmpty()) {
            AudioParityChipWrite first = writes.getFirst();
            writes.set(0, first.chip().equals("psg")
                    ? AudioParityChipWrite.psg(first.value() ^ 0x01)
                    : AudioParityChipWrite.ym2612(first.port(), first.register(),
                            first.value() ^ 0x01));
        }
        S3kAudioTick normalized = S3kAudioStateNormalizer.normalize(ordinal,
                referenceTick.mailbox(), driver.captureSnapshot());
        ticks.add(new S3kAudioTick(ordinal, false, normalized.mailbox(),
                normalized.global(), normalized.tracks(), List.copyOf(writes)));
        writes.clear();
    }

    private static void applyProgram(
            SmpsDriver driver, SmpsWriteProgram program) {
        for (SmpsChipWrite write : program.writes()) {
            if (write instanceof SmpsChipWrite.Ym2612 ym) {
                driver.writeFm(driver, ym.port(), ym.register(), ym.value());
            } else if (write instanceof SmpsChipWrite.Psg psg) {
                driver.writePsg(driver, psg.value());
            }
        }
    }

    /**
     * The ROM's SEGA transport for the pinned ROM's own {@code SEGA_PCM}
     * bytes, read through the S3K profile's loader like any other runtime
     * asset.
     */
    private static SmpsWriteProgram segaPcmProgram(Rom rom) {
        SmpsSegaPcmTransport transport = Sonic3kSmpsPhysicalPolicy.INSTANCE
                .segaPcmTransport()
                .orElseThrow(() -> new IllegalStateException(
                        "S3K policy no longer owns the SEGA PCM transport"));
        try {
            return transport.program(
                    new Sonic3kAudioProfile().loadSegaPcm(rom));
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot read SEGA PCM from the verified S3K ROM", error);
        }
    }

    private static void dispatch(int request, Sonic3kSmpsLoader loader,
            DacData dacData, OwnedSmpsAudioStream stream,
            List<String> unsupported, int ordinal, boolean[] segaPending) {
        SmpsDriver driver = stream.logicalDriver();
        int id = request == S3kAudioParitySchema.CREDITS_K ? 0x32 : request;
        if (id >= S3kAudioParitySchema.MUSIC_FIRST && id <= S3kAudioParitySchema.MUSIC_LAST) {
            AbstractSmpsData song = loader.loadMusic(id);
            if (song == null) {
                unsupported.add("tick " + ordinal + ": music 0x" + Integer.toHexString(id)
                        + " did not load");
                return;
            }
            // zPlayMusic -> zStopAllSound before zBGMLoad (D:1786-1795).
            stream.stopAll();
            // zBGMLoad's own first hardware write, 0B6h=0C0h through port 1
            // (D:1811-1816), before either track loop runs. Production reaches
            // the same program through SmpsDriverSession's activation hook.
            // The ROM's order: zBGMLoad's own 0B6h write comes after the bank
            // switch and before either track loop, so it precedes every write
            // the track set makes (D:1811-1856). iy+2 is the FM+DAC track
            // count the loop is driven by, which is what the activation
            // carries.
            applyProgram(driver, Sonic3kSmpsPhysicalPolicy.INSTANCE
                    .activateMusic(new SmpsMusicActivation(
                            SmpsSourceDescriptor.baseMusic(song),
                            song.getFmPointers().length)));
            SmpsSequencer sequencer = new SmpsSequencer(song, dacData, driver, () -> { },
                    Sonic3kSmpsSequencerConfig.CONFIG);
            sequencer.setSampleRate(SAMPLE_RATE);
            driver.addSequencer(sequencer, false);
            return;
        }
        if (id >= S3kAudioParitySchema.SFX_FIRST && id <= S3kAudioParitySchema.SFX_LAST) {
            AbstractSmpsData sfx = loader.loadSfx(id);
            if (sfx == null) {
                unsupported.add("tick " + ordinal + ": sfx 0x" + Integer.toHexString(id)
                        + " did not load");
                return;
            }
            SmpsSequencer sequencer = new SmpsSequencer(sfx, dacData, driver, () -> { },
                    Sonic3kSmpsSequencerConfig.CONFIG);
            sequencer.setSampleRate(SAMPLE_RATE);
            sequencer.setSfxMode(true);
            driver.addSequencer(sequencer, true);
            return;
        }
        if (id == S3kAudioParitySchema.CMD_STOP_SFX) {
            stream.stopSmpsSfx();
            return;
        }
        if (id == S3kAudioParitySchema.CMD_FADE_OUT
                || id == S3kAudioParitySchema.CMD_FADE_OUT2) {
            // The production session owns zFadeOutMusic's counters, track
            // halts and physical silence, including the no-music case.
            stream.fadeOutMusic(
                    Sonic3kSmpsSequencerConfig.CONFIG.getFadeOutSteps(),
                    Sonic3kSmpsSequencerConfig.CONFIG.getFadeOutDelay());
            return;
        }
        if (id == S3kAudioParitySchema.CMD_SEGA) {
            // zPlaySegaSound calls zStopAllSound, sets PlaySegaPCMFlag and
            // returns without reaching the loop (D:2703-2719), so this
            // service carries only the stop; the transport is the next one.
            stream.stopAll();
            segaPending[0] = true;
            return;
        }
        if (id == S3kAudioParitySchema.CMD_MUTE_PSG) {
            applyProgram(driver,
                    Sonic3kSmpsPhysicalPolicy.INSTANCE.silenceAllPsg());
            return;
        }
        // E0h and E6h-FEh: zStopAllSound (map §4.3).
        stream.stopAll();
    }

    private static void verifyRomIdentity(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] chunk = new byte[64 * 1024];
            int count;
            while ((count = input.read(chunk)) >= 0) {
                sha1.update(chunk, 0, count);
            }
            String actual = HexFormat.of().formatHex(sha1.digest());
            if (!S3kAudioParitySchema.S3K_LOCKED_ON_SHA1.equals(actual)) {
                throw new IllegalArgumentException(
                        "S3K oracle capture requires the pinned locked-on ROM; got SHA-1 " + actual);
            }
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalArgumentException("cannot verify S3K ROM identity", error);
        }
    }
}
