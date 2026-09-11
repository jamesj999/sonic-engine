package com.openggf.game.sonic2.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Game-owned Sonic 2 sound mailbox, Z80 queue, and pre-dispatch request state.
 *
 * <p>This is deliberately a logical state machine. It accepts only engine-owned payloads and
 * never selects or instantiates a playback owner. Sources: {@code s2.asm:1270-1332,1520-1549}
 * and {@code s2.sounddriver.asm:1496-1600,2116-2178,2334-2347,2580-2655,3514-3535}.</p>
 */
public final class Sonic2SoundRequestPipeline<P> {
    private static final int READY = 0x80;
    private static final int FIRST_MUSIC = 0x81;
    private static final int FIRST_SFX = 0xA0;
    private static final int LAST_SFX = 0xF0;
    private static final int FIRST_COMMAND = 0xF8;
    private static final int PAUSE = 0xFE;
    private static final int UNPAUSE = 0xFF;
    private static final int RING = 0xB5;
    private static final int RING_LEFT = 0xCE;
    private static final int GLOOP = 0xDA;
    private static final int SPINDASH_REV = 0xE0;

    private SlotState<P> music0 = emptySlot();
    private SlotState<P> sfx0 = emptySlot();
    private SlotState<P> sfx1 = emptySlot();
    private SlotState<P> sfx2 = emptySlot();
    private SlotState<P> music1 = emptySlot();
    private SlotState<P> queue0 = emptySlot();
    private SlotState<P> queue1 = emptySlot();
    private SlotState<P> queue2 = emptySlot();
    private QueueToPlayState<P> queueToPlay = readyQueue();
    private int stopMusic;
    private int voiceTablePointer;
    private int sfxPriorityValue;
    private int ringSpeaker;
    private int gloopFlag;
    private int spindashPlayingCounter;
    private int spindashExtraFrequencyIndex;
    private boolean spindashActive;
    private int savedOneUpSfxPriorityValue;
    private boolean oneUpPlaying;

    /** Models {@code PlayMusic}: primary then overwrite fallback ({@code s2.asm:1520-1528}). */
    public void submitMusic(int requestByte, P payload) {
        SlotState<P> request = request(requestByte, payload);
        if (music0.requestByte() == 0) {
            music0 = request;
        } else {
            music1 = request;
        }
    }

    /** Models {@code PlaySound}'s overwrite-only {@code SFX0} ({@code s2.asm:1535-1539}). */
    public void submitSound(int requestByte, P payload) {
        sfx0 = request(requestByte, payload);
    }

    /** Models {@code PlaySound2}'s overwrite-only {@code SFX1} ({@code s2.asm:1546-1549}). */
    public void submitSound2(int requestByte, P payload) {
        sfx1 = request(requestByte, payload);
    }

    /**
     * Test-only raw seam for the shipped, otherwise unwritten {@code SFX2} source byte.
     */
    void submitSfx2ForTesting(int requestByte, P payload) {
        sfx2 = request(requestByte, payload);
    }

    /**
     * Runs one 68K {@code sndDriverInput} mailbox bridge service.
     *
     * <p>Shipped Sonic 2 builds with {@code fixBugs = 0}. The source's fourth SFX loop iteration
     * reads {@code Music1} and aliases the low byte of {@code VoiceTblPtr}; the fixed branch would
     * stop at three slots. See {@code docs/s2disasm/s2.asm:1270-1332}.</p>
     */
    public BridgeResult<P> bridge() {
        MusicBridgeResult<P> music = bridgeMusic();
        List<PhysicalTransfer<P>> transfers = new ArrayList<>(4);

        for (int physicalSlot = 3; physicalSlot >= 0; physicalSlot--) {
            SlotState<P> source = sourceForPhysicalSlot(physicalSlot);
            if (source.requestByte() == 0 || physicalDestinationOccupied(physicalSlot)) {
                continue;
            }

            SourceSlot sourceSlot = sourceSlotForPhysicalSlot(physicalSlot);
            clearSource(sourceSlot);
            if (physicalSlot == 3) {
                voiceTablePointer = (voiceTablePointer & 0xFF00) | source.requestByte();
            } else {
                setQueue(physicalSlot, source);
            }
            transfers.add(new PhysicalTransfer<>(sourceSlot, physicalSlot, source.requestByte(),
                    physicalSlot == 3, source.payload()));
        }
        return new BridgeResult<>(music, transfers);
    }

    /**
     * Runs one {@code zCycleQueue} admission pass without a sequencer; scan, clear, priority,
     * equality and one-result return follow {@code s2.sounddriver.asm:1496-1550}.
     */
    public CycleResult<P> cycleQueue() {
        if (queueToPlay.requestByte() != READY) {
            return new CycleResult<>(DecisionKind.BUSY, null, List.of());
        }
        if (queue0.requestByte() == 0 && queue1.requestByte() == 0 && queue2.requestByte() == 0) {
            return new CycleResult<>(DecisionKind.IDLE, null, List.of());
        }

        List<Request<P>> invalidDiscards = new ArrayList<>();
        int priority = sfxPriorityValue;
        for (int slot = 0; slot < 3; slot++) {
            SlotState<P> queued = queue(slot);
            if (queued.requestByte() == 0) {
                continue;
            }
            setQueue(slot, emptySlot());
            Request<P> request = new Request<>(QueueSlot.fromIndex(slot), queued.requestByte(), queued.payload());

            if (queued.requestByte() < FIRST_MUSIC) {
                invalidDiscards.add(request);
                continue;
            }
            if (queued.requestByte() >= FIRST_COMMAND) {
                queueToPlay = new QueueToPlayState<>(queued.requestByte(), queued.payload());
                return new CycleResult<>(DecisionKind.PROMOTED_COMMAND, request, invalidDiscards);
            }
            if (queued.requestByte() < FIRST_SFX) {
                queueToPlay = new QueueToPlayState<>(queued.requestByte(), queued.payload());
                return new CycleResult<>(DecisionKind.PROMOTED_MUSIC, request, invalidDiscards);
            }

            int candidatePriority = priorityFor(queued.requestByte());
            if (candidatePriority < priority) {
                return new CycleResult<>(DecisionKind.REJECTED_SFX, request, invalidDiscards);
            }
            if ((candidatePriority & 0x80) == 0) {
                sfxPriorityValue = candidatePriority;
            }
            queueToPlay = new QueueToPlayState<>(queued.requestByte(), queued.payload());
            return new CycleResult<>(DecisionKind.ACCEPTED_SFX, request, invalidDiscards);
        }
        return new CycleResult<>(DecisionKind.IDLE, null, invalidDiscards);
    }

    /**
     * Releases {@code QueueToPlay} at the ROM dispatch boundary and reports only deterministic
     * request selection. Playback ownership remains {@link DispatchKind#NOT_YET_DISPATCHED}.
     * Dispatch ranges and command/no-op gaps are {@code s2.sounddriver.asm:1555-1600}; ring,
     * gloop and spindash transforms are {@code :2116-2178}.
     */
    public DispatchResult<P> dispatchQueuedRequest() {
        QueueToPlayState<P> queued = queueToPlay;
        if (queued.requestByte() == READY) {
            return new DispatchResult<>(DispatchKind.NOTHING_TO_DISPATCH, 0, 0, null, 0);
        }
        queueToPlay = readyQueue();

        int requestByte = queued.requestByte();
        if (requestByte >= 0xF1 && requestByte <= 0xF7) {
            return new DispatchResult<>(DispatchKind.IGNORED_UNDEFINED_ID, requestByte, requestByte,
                    queued.payload(), 0);
        }
        if (requestByte < FIRST_SFX || requestByte > LAST_SFX) {
            return new DispatchResult<>(DispatchKind.NOT_YET_DISPATCHED, requestByte, requestByte,
                    queued.payload(), 0);
        }

        spindashActive = false;
        if (requestByte == RING) {
            int selected = ringSpeaker == 0 ? RING_LEFT : RING;
            ringSpeaker = (~ringSpeaker) & 0xFF;
            return new DispatchResult<>(DispatchKind.NOT_YET_DISPATCHED, requestByte, selected,
                    queued.payload(), 0);
        }
        if (requestByte == GLOOP) {
            gloopFlag = (~gloopFlag) & 0xFF;
            DispatchKind kind = gloopFlag == 0
                    ? DispatchKind.SUPPRESSED_GLOOP : DispatchKind.NOT_YET_DISPATCHED;
            return new DispatchResult<>(kind, requestByte, requestByte, queued.payload(), 0);
        }
        if (requestByte == SPINDASH_REV) {
            int candidateIndex = spindashPlayingCounter == 0
                    ? 0 : (spindashExtraFrequencyIndex + 1) & 0xFF;
            if (candidateIndex < 0x0C) {
                spindashExtraFrequencyIndex = candidateIndex;
            }
            spindashPlayingCounter = 0x3C;
            spindashActive = true;
            return new DispatchResult<>(DispatchKind.NOT_YET_DISPATCHED, requestByte, requestByte,
                    queued.payload(), spindashExtraFrequencyIndex);
        }
        return new DispatchResult<>(DispatchKind.NOT_YET_DISPATCHED, requestByte, requestByte,
                queued.payload(), 0);
    }

    /** Models the invocation counter paired with {@code s2.sounddriver.asm:2159-2175}. */
    public void finishDriverInvocation() {
        if (spindashPlayingCounter != 0) {
            spindashPlayingCounter--;
        }
    }

    /** Models an SFX-track stop priority clear ({@code s2.sounddriver.asm:3514-3535}). */
    public void onSfxTrackStopped() {
        sfxPriorityValue = 0;
    }

    /** Models the F8 stop-all-SFX clear ({@code s2.sounddriver.asm:2344-2347}). */
    public void onStopAllSfx() {
        sfxPriorityValue = 0;
    }

    /** Models {@code zKillSFXPrio} ({@code s2.sounddriver.asm:2116-2120,2334-2337}). */
    public void onSfxSuppressedDuringOneUpOrFadeIn() {
        sfxPriorityValue = 0;
    }

    /** Models the post-save 1-up-start clear of the global priority latch. */
    public void onOneUpStarted() {
        savedOneUpSfxPriorityValue = sfxPriorityValue;
        oneUpPlaying = true;
        sfxPriorityValue = 0;
    }

    public void onOrdinaryMusicStarted() {
        oneUpPlaying = false;
        savedOneUpSfxPriorityValue = 0;
        sfxPriorityValue = 0;
    }

    /**
     * Models only the mailbox fields reset by {@code zInitMusicPlayback}.
     *
     * <p>Shipped {@code fixBugs = 0} fails to preserve {@code Queue2}; the fixed branch backs it
     * up and restores it. The clear of {@code zAbsVar} also drops {@code StopMusic} and the full
     * {@code VoiceTblPtr}; the ordinary music-load path preserves {@code SFXPriorityVal}. See
     * {@code docs/s2disasm/s2.sounddriver.asm:2580-2655}.</p>
     */
    public void onMusicPlaybackInitialized() {
        stopMusic = 0;
        voiceTablePointer = 0;
        queueToPlay = readyQueue();
        queue2 = emptySlot();
    }

    /** Captures all logical bytes and their payload sidecars without producing an event. */
    public Snapshot<P> snapshot() {
        return new Snapshot<>(music0, sfx0, sfx1, sfx2, music1, queue0, queue1, queue2,
                queueToPlay, stopMusic, voiceTablePointer, sfxPriorityValue, ringSpeaker, gloopFlag,
                spindashPlayingCounter, spindashExtraFrequencyIndex, spindashActive,
                savedOneUpSfxPriorityValue, oneUpPlaying);
    }

    /** Restores a validated snapshot atomically and without producing an event. */
    public void restore(Snapshot<P> snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        music0 = snapshot.music0();
        sfx0 = snapshot.sfx0();
        sfx1 = snapshot.sfx1();
        sfx2 = snapshot.sfx2();
        music1 = snapshot.music1();
        queue0 = snapshot.queue0();
        queue1 = snapshot.queue1();
        queue2 = snapshot.queue2();
        queueToPlay = snapshot.queueToPlay();
        stopMusic = snapshot.stopMusic();
        voiceTablePointer = snapshot.voiceTablePointer();
        sfxPriorityValue = snapshot.sfxPriorityValue();
        ringSpeaker = snapshot.ringSpeaker();
        gloopFlag = snapshot.gloopFlag();
        spindashPlayingCounter = snapshot.spindashPlayingCounter();
        spindashExtraFrequencyIndex = snapshot.spindashExtraFrequencyIndex();
        spindashActive = snapshot.spindashActive();
        savedOneUpSfxPriorityValue = snapshot.savedOneUpSfxPriorityValue();
        oneUpPlaying = snapshot.oneUpPlaying();
    }

    private MusicBridgeResult<P> bridgeMusic() {
        if (queueToPlay.requestByte() != READY) {
            return new MusicBridgeResult<>(MusicBridgeKind.DEFERRED, 0, null);
        }
        SlotState<P> source = music0.requestByte() != 0 ? music0 : music1;
        SourceSlot sourceSlot = music0.requestByte() != 0 ? SourceSlot.MUSIC0 : SourceSlot.MUSIC1;
        if (source.requestByte() == 0) {
            return new MusicBridgeResult<>(MusicBridgeKind.IDLE, 0, null);
        }
        clearSource(sourceSlot);
        if (source.requestByte() == PAUSE) {
            stopMusic = 0x7F;
            return new MusicBridgeResult<>(MusicBridgeKind.PAUSE, source.requestByte(), source.payload());
        }
        if (source.requestByte() == UNPAUSE) {
            stopMusic = READY;
            return new MusicBridgeResult<>(MusicBridgeKind.UNPAUSE, source.requestByte(), source.payload());
        }
        queueToPlay = new QueueToPlayState<>(source.requestByte(), source.payload());
        return new MusicBridgeResult<>(MusicBridgeKind.PROMOTED, source.requestByte(), source.payload());
    }

    private int priorityFor(int requestByte) {
        if (requestByte <= LAST_SFX) {
            return Sonic2SmpsConstants.SFX_PRIORITY_TABLE[requestByte - FIRST_SFX];
        }
        // REV01's fixBugs=0/OptimiseDriver=0 zCycleQueue indexes seven bytes beyond the 81-byte
        // zSFXPriority table (decompressed driver 0x0FD8). They are zPSG_EnvTbl bytes at 0x1029;
        // this is arbitration only, because zPlaySoundByIndex ignores F1-F7 afterwards.
        return switch (requestByte) {
            case 0xF1 -> 0x43;
            case 0xF2 -> 0x10;
            case 0xF3 -> 0x5A;
            case 0xF4 -> 0x10;
            case 0xF5 -> 0x61;
            case 0xF6 -> 0x10;
            case 0xF7 -> 0x72;
            default -> throw new IllegalArgumentException("not a Sonic 2 queue SFX byte: " + requestByte);
        };
    }

    private boolean physicalDestinationOccupied(int physicalSlot) {
        return physicalSlot == 3 ? (voiceTablePointer & 0xFF) != 0 : queue(physicalSlot).requestByte() != 0;
    }

    private SlotState<P> sourceForPhysicalSlot(int physicalSlot) {
        return switch (physicalSlot) {
            case 0 -> sfx0;
            case 1 -> sfx1;
            case 2 -> sfx2;
            case 3 -> music1;
            default -> throw new IllegalArgumentException("physical slot must be 0..3");
        };
    }

    private SourceSlot sourceSlotForPhysicalSlot(int physicalSlot) {
        return switch (physicalSlot) {
            case 0 -> SourceSlot.SFX0;
            case 1 -> SourceSlot.SFX1;
            case 2 -> SourceSlot.SFX2;
            case 3 -> SourceSlot.MUSIC1;
            default -> throw new IllegalArgumentException("physical slot must be 0..3");
        };
    }

    private void clearSource(SourceSlot sourceSlot) {
        switch (sourceSlot) {
            case MUSIC0 -> music0 = emptySlot();
            case SFX0 -> sfx0 = emptySlot();
            case SFX1 -> sfx1 = emptySlot();
            case SFX2 -> sfx2 = emptySlot();
            case MUSIC1 -> music1 = emptySlot();
        }
    }

    private SlotState<P> queue(int index) {
        return switch (index) {
            case 0 -> queue0;
            case 1 -> queue1;
            case 2 -> queue2;
            default -> throw new IllegalArgumentException("queue slot must be 0..2");
        };
    }

    private void setQueue(int index, SlotState<P> slot) {
        switch (index) {
            case 0 -> queue0 = slot;
            case 1 -> queue1 = slot;
            case 2 -> queue2 = slot;
            default -> throw new IllegalArgumentException("queue slot must be 0..2");
        }
    }

    private static <P> SlotState<P> request(int requestByte, P payload) {
        return new SlotState<>(requestByte, payload);
    }

    private static <P> SlotState<P> emptySlot() {
        return new SlotState<>(0, null);
    }

    private static <P> QueueToPlayState<P> readyQueue() {
        return new QueueToPlayState<>(READY, null);
    }

    public enum SourceSlot {
        MUSIC0,
        SFX0,
        SFX1,
        SFX2,
        MUSIC1
    }

    public enum QueueSlot {
        QUEUE0,
        QUEUE1,
        QUEUE2;

        private static QueueSlot fromIndex(int index) {
            return values()[index];
        }
    }

    public enum MusicBridgeKind {
        IDLE,
        DEFERRED,
        PROMOTED,
        PAUSE,
        UNPAUSE
    }

    public enum DecisionKind {
        IDLE,
        BUSY,
        PROMOTED_MUSIC,
        PROMOTED_COMMAND,
        ACCEPTED_SFX,
        REJECTED_SFX
    }

    public enum DispatchKind {
        NOTHING_TO_DISPATCH,
        NOT_YET_DISPATCHED,
        SUPPRESSED_GLOOP,
        IGNORED_UNDEFINED_ID
    }

    public record SlotState<P>(int requestByte, P payload) {
        public SlotState {
            checkByte(requestByte, "request byte");
            if ((requestByte == 0) != (payload == null)) {
                throw new IllegalArgumentException("zero request bytes carry no payload; nonzero requests require one");
            }
        }
    }

    /** {@code QueueToPlay} accepts its ready sentinel without a payload. */
    public record QueueToPlayState<P>(int requestByte, P payload) {
        public QueueToPlayState {
            checkByte(requestByte, "QueueToPlay byte");
            if (requestByte == READY) {
                if (payload != null) {
                    throw new IllegalArgumentException("ready QueueToPlay has no payload");
                }
            } else if ((requestByte == 0) != (payload == null)) {
                throw new IllegalArgumentException("zero request bytes carry no payload; nonzero requests require one");
            }
        }
    }

    public record Snapshot<P>(SlotState<P> music0, SlotState<P> sfx0, SlotState<P> sfx1,
                              SlotState<P> sfx2, SlotState<P> music1, SlotState<P> queue0,
                              SlotState<P> queue1, SlotState<P> queue2, QueueToPlayState<P> queueToPlay,
                              int stopMusic, int voiceTablePointer, int sfxPriorityValue, int ringSpeaker,
                              int gloopFlag, int spindashPlayingCounter, int spindashExtraFrequencyIndex,
                              boolean spindashActive, int savedOneUpSfxPriorityValue,
                              boolean oneUpPlaying) {
        public Snapshot {
            requireSlot(music0, "Music0");
            requireSlot(sfx0, "SFX0");
            requireSlot(sfx1, "SFX1");
            requireSlot(sfx2, "SFX2");
            requireSlot(music1, "Music1");
            requireSlot(queue0, "Queue0");
            requireSlot(queue1, "Queue1");
            requireSlot(queue2, "Queue2");
            if (queueToPlay == null) {
                throw new IllegalArgumentException("QueueToPlay must not be null");
            }
            checkByte(stopMusic, "StopMusic");
            if (voiceTablePointer < 0 || voiceTablePointer > 0xFFFF) {
                throw new IllegalArgumentException("VoiceTblPtr must be an unsigned 16-bit value");
            }
            checkByte(sfxPriorityValue, "SFXPriorityVal");
            checkByte(ringSpeaker, "ring speaker");
            checkByte(gloopFlag, "gloop flag");
            checkByte(spindashPlayingCounter, "spindash playing counter");
            checkByte(spindashExtraFrequencyIndex, "spindash frequency index");
            if (spindashExtraFrequencyIndex > 0x0B) {
                throw new IllegalArgumentException("spindash frequency index must be 0..11");
            }
            checkByte(savedOneUpSfxPriorityValue,
                    "saved one-up SFXPriorityVal");
        }
    }

    public record BridgeResult<P>(MusicBridgeResult<P> music, List<PhysicalTransfer<P>> transfers) {
        public BridgeResult {
            if (music == null) {
                throw new IllegalArgumentException("music result must not be null");
            }
            transfers = List.copyOf(transfers);
        }
    }

    public record MusicBridgeResult<P>(MusicBridgeKind kind, int requestByte, P payload) {
        public MusicBridgeResult {
            if (kind == null) {
                throw new IllegalArgumentException("music bridge kind must not be null");
            }
            checkByte(requestByte, "music bridge request byte");
        }
    }

    public record PhysicalTransfer<P>(SourceSlot sourceSlot, int physicalSlot, int requestByte,
                                      boolean voiceTablePointerAlias, P payload) {
        public PhysicalTransfer {
            if (sourceSlot == null || physicalSlot < 0 || physicalSlot > 3) {
                throw new IllegalArgumentException("physical transfer slot is invalid");
            }
            checkByte(requestByte, "physical transfer request byte");
            if (requestByte == 0 || payload == null || voiceTablePointerAlias != (physicalSlot == 3)) {
                throw new IllegalArgumentException("physical transfer does not own a valid request payload");
            }
        }
    }

    public record Request<P>(QueueSlot sourceSlot, int requestByte, P payload) {
        public Request {
            if (sourceSlot == null || requestByte == 0 || payload == null) {
                throw new IllegalArgumentException("request does not own a valid slot and payload");
            }
            checkByte(requestByte, "request byte");
        }
    }

    public record CycleResult<P>(DecisionKind kind, Request<P> request, List<Request<P>> invalidDiscards) {
        public CycleResult {
            if (kind == null) {
                throw new IllegalArgumentException("decision kind must not be null");
            }
            invalidDiscards = List.copyOf(invalidDiscards);
        }
    }

    public record DispatchResult<P>(DispatchKind kind, int originalRequestByte, int selectedRequestByte,
                                    P payload, int spindashTransposeOffset) {
        public DispatchResult {
            if (kind == null || originalRequestByte < 0 || originalRequestByte > 0xFF
                    || selectedRequestByte < 0 || selectedRequestByte > 0xFF
                    || spindashTransposeOffset < 0 || spindashTransposeOffset > 0x0B) {
                throw new IllegalArgumentException("dispatch result is invalid");
            }
            if ((kind == DispatchKind.NOTHING_TO_DISPATCH) != (payload == null)) {
                throw new IllegalArgumentException("only an empty dispatch has no payload");
            }
        }
    }

    private static void requireSlot(SlotState<?> slot, String name) {
        if (slot == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    private static void checkByte(int value, String name) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException(name + " must be an unsigned byte");
        }
    }
}
