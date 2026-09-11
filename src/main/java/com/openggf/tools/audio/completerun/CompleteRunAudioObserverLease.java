package com.openggf.tools.audio.completerun;

import com.openggf.audio.AudioAdmissionObserver;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioRequestObserver;
import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.synth.ChipWriteObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, output-only observation lease for one production BK2 row at a
 * time. All callback domains share one monotonic ordinal, preserving their
 * actual cross-domain order without giving observations any runtime authority.
 */
public final class CompleteRunAudioObserverLease implements AutoCloseable {
    public static final int MAX_EVENTS_PER_BOUNDARY = 65_536;

    public sealed interface Observation permits RequestObserved,
            AdmissionObserved, ServiceBeginObserved, ServiceEndObserved,
            LifecycleObserved, YmWriteObserved, PsgWriteObserved,
            SfxAdmittedObserved, RoleArbitratedObserved {
        long ordinal();
    }

    public record RequestObserved(long ordinal,
            AudioRequestObserver.RequestClass requestClass,
            int rawSoundId) implements Observation {
        public RequestObserved {
            Objects.requireNonNull(requestClass, "requestClass");
        }
    }

    public record AdmissionObserved(long ordinal,
            AudioAdmissionObserver.AudioAdmissionDecision decision)
            implements Observation {
        public AdmissionObserved {
            Objects.requireNonNull(decision, "decision");
        }
    }

    public record ServiceBeginObserved(long ordinal,
            SmpsDriverServiceObserver.ServiceEvent event)
            implements Observation {
        public ServiceBeginObserved {
            Objects.requireNonNull(event, "event");
        }
    }

    public record ServiceEndObserved(long ordinal,
            SmpsDriverServiceObserver.ServiceEvent event,
            SmpsDriverSnapshot snapshot) implements Observation {
        public ServiceEndObserved {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public record LifecycleObserved(long ordinal,
            SmpsDriverServiceObserver.LifecycleEvent event)
            implements Observation {
        public LifecycleObserved {
            Objects.requireNonNull(event, "event");
        }
    }

    public record YmWriteObserved(long ordinal, int port, int register,
            int value) implements Observation {
    }

    public record PsgWriteObserved(long ordinal, int value)
            implements Observation {
    }

    public record SfxAdmittedObserved(long ordinal,
            SfxContentionObserver.Admission admission)
            implements Observation {
        public SfxAdmittedObserved {
            Objects.requireNonNull(admission, "admission");
        }
    }

    public record RoleArbitratedObserved(long ordinal,
            SfxContentionObserver.Arbitration arbitration)
            implements Observation {
        public RoleArbitratedObserved {
            Objects.requireNonNull(arbitration, "arbitration");
        }
    }

    /** Events produced after installation but before a row starts. */
    public record PreRowBoundary(int absoluteFrame,
            long firstRowEventOrdinal,
            AudioLogicalSnapshot logicalSnapshot,
            List<Observation> observationsBeforeRow) {
        public PreRowBoundary {
            if (absoluteFrame < 0 || firstRowEventOrdinal < 0) {
                throw new IllegalArgumentException("pre-row boundary is invalid");
            }
            Objects.requireNonNull(logicalSnapshot, "logicalSnapshot");
            observationsBeforeRow = List.copyOf(Objects.requireNonNull(
                    observationsBeforeRow, "observationsBeforeRow"));
        }
    }

    /** One immutable completed row, released only after presentation. */
    public record RowObservation(int absoluteFrame,
            AudioLogicalSnapshot logicalSnapshot,
            List<Observation> events) {
        public RowObservation {
            if (absoluteFrame < 0) {
                throw new IllegalArgumentException("row frame is invalid");
            }
            Objects.requireNonNull(logicalSnapshot, "logicalSnapshot");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }
    }

    private enum State { BEFORE_FIRST_ROW, ACTIVE_ROW, BETWEEN_ROWS, CLOSED }

    private final Thread ownerThread;
    private final AudioManager.DiagnosticObserverHandle managerHandle;
    private final List<Observation> preRowEvents = new ArrayList<>();
    private final List<Observation> rowEvents = new ArrayList<>();
    private State state = State.BEFORE_FIRST_ROW;
    private long nextOrdinal;
    private int activeFrame = -1;
    private int lastCompletedFrame = -1;

    private CompleteRunAudioObserverLease(AudioManager manager) {
        ownerThread = Thread.currentThread();
        managerHandle = Objects.requireNonNull(manager, "manager")
                .acquireDiagnosticObservers(new AudioManager.DiagnosticObserverSet(
                        this::onRequest,
                        this::onAdmission,
                        new SmpsDriverServiceObserver() {
                            @Override
                            public void onServiceBegin(ServiceEvent event) {
                                append(ordinal -> new ServiceBeginObserved(
                                        ordinal, event));
                            }

                            @Override
                            public void onServiceEnd(ServiceEvent event,
                                    SmpsDriverSnapshot snapshot) {
                                append(ordinal -> new ServiceEndObserved(
                                        ordinal, event, snapshot));
                            }

                            @Override
                            public void onLifecycle(LifecycleEvent event) {
                                append(ordinal -> new LifecycleObserved(
                                        ordinal, event));
                            }
                        },
                        new ChipWriteObserver() {
                            @Override
                            public void onYm2612Write(int port, int register,
                                    int value) {
                                append(ordinal -> new YmWriteObserved(
                                        ordinal, port, register, value));
                            }

                            @Override
                            public void onPsgWrite(int value) {
                                append(ordinal -> new PsgWriteObserved(
                                        ordinal, value));
                            }
                        },
                        new SfxContentionObserver() {
                            @Override
                            public void onSfxAdmitted(Admission admission) {
                                append(ordinal -> new SfxAdmittedObserved(
                                        ordinal, admission));
                            }

                            @Override
                            public void onRoleArbitrated(
                                    Arbitration arbitration) {
                                append(ordinal -> new RoleArbitratedObserved(
                                        ordinal, arbitration));
                            }
                        }));
    }

    public static CompleteRunAudioObserverLease acquire(AudioManager manager) {
        return new CompleteRunAudioObserverLease(manager);
    }

    public synchronized PreRowBoundary beginRow(int absoluteFrame,
            AudioLogicalSnapshot logicalSnapshot) {
        requireOwnerThread();
        Objects.requireNonNull(logicalSnapshot, "logicalSnapshot");
        if (state == State.CLOSED || state == State.ACTIVE_ROW) {
            throw new IllegalStateException("observer lease cannot begin a row now");
        }
        if (lastCompletedFrame >= 0 && absoluteFrame != lastCompletedFrame + 1) {
            throw new IllegalStateException("BK2 rows must be consecutive");
        }
        if (absoluteFrame < 0) {
            throw new IllegalArgumentException("absoluteFrame must be non-negative");
        }
        List<Observation> prior = state == State.BEFORE_FIRST_ROW
                ? List.copyOf(preRowEvents) : List.of();
        preRowEvents.clear();
        rowEvents.clear();
        activeFrame = absoluteFrame;
        state = State.ACTIVE_ROW;
        return new PreRowBoundary(
                absoluteFrame, nextOrdinal, logicalSnapshot, prior);
    }

    public synchronized RowObservation finishRow(int absoluteFrame,
            AudioLogicalSnapshot logicalSnapshot) {
        requireOwnerThread();
        Objects.requireNonNull(logicalSnapshot, "logicalSnapshot");
        if (state != State.ACTIVE_ROW) {
            throw new IllegalStateException("no active BK2 row to finish");
        }
        if (absoluteFrame != activeFrame) {
            throw new IllegalStateException(
                    "finished BK2 row does not match its active boundary");
        }
        RowObservation observation = new RowObservation(
                activeFrame, logicalSnapshot, rowEvents);
        lastCompletedFrame = activeFrame;
        activeFrame = -1;
        rowEvents.clear();
        state = State.BETWEEN_ROWS;
        return observation;
    }

    public synchronized boolean active() {
        return state != State.CLOSED && managerHandle.active();
    }

    @Override
    public synchronized void close() {
        requireOwnerThread();
        if (state == State.CLOSED) {
            return;
        }
        managerHandle.close();
        preRowEvents.clear();
        rowEvents.clear();
        state = State.CLOSED;
    }

    private void onRequest(AudioRequestObserver.RequestClass requestClass,
            int rawSoundId) {
        append(ordinal -> new RequestObserved(
                ordinal, requestClass, rawSoundId));
    }

    private void onAdmission(
            AudioAdmissionObserver.AudioAdmissionDecision decision) {
        append(ordinal -> new AdmissionObserved(ordinal, decision));
    }

    private synchronized void append(ObservationFactory factory) {
        requireOwnerThread();
        List<Observation> destination = switch (state) {
            case BEFORE_FIRST_ROW -> preRowEvents;
            case ACTIVE_ROW -> rowEvents;
            case BETWEEN_ROWS -> throw new IllegalStateException(
                    "audio observation occurred between BK2 rows");
            case CLOSED -> throw new IllegalStateException(
                    "audio observation occurred after lease close");
        };
        if (destination.size() >= MAX_EVENTS_PER_BOUNDARY) {
            throw new IllegalStateException(
                    "complete-run audio observation bound exceeded");
        }
        destination.add(factory.create(nextOrdinal++));
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "complete-run observations are owner-thread confined");
        }
    }

    @FunctionalInterface
    private interface ObservationFactory {
        Observation create(long ordinal);
    }
}
