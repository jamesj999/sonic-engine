package com.openggf.trace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Shared resolution and UTF-8 reader support for plain or gzip trace files. */
public final class TraceFiles {

    enum ReaderLifecycleEvent {
        OPENED,
        CLOSED
    }

    @FunctionalInterface
    interface ReaderLifecycleObserver {
        void onEvent(ReaderLifecycleEvent event, Path path);
    }

    private static final ThreadLocal<ReaderLifecycleObserver> READER_OBSERVER =
            new ThreadLocal<>();

    private TraceFiles() {
    }

    public static Path resolve(Path directory, String fileName) {
        Path plain = directory.resolve(fileName);
        if (Files.isRegularFile(plain)) {
            return plain;
        }
        Path gzip = directory.resolve(fileName + ".gz");
        return Files.isRegularFile(gzip) ? gzip : null;
    }

    public static BufferedReader openReader(Path path) throws IOException {
        BufferedReader reader;
        if (!path.getFileName().toString().endsWith(".gz")) {
            reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } else {
            InputStream input = Files.newInputStream(path);
            try {
                reader = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(input), StandardCharsets.UTF_8));
            } catch (IOException e) {
                input.close();
                throw e;
            }
        }
        ReaderLifecycleObserver observer = READER_OBSERVER.get();
        if (observer == null) {
            return reader;
        }
        return observeReader(reader, observer, path);
    }

    static AutoCloseable observeReadersForTest(ReaderLifecycleObserver observer) {
        ReaderLifecycleObserver installed = Objects.requireNonNull(observer, "observer");
        ReaderLifecycleObserver previous = READER_OBSERVER.get();
        READER_OBSERVER.set(installed);
        return new ReaderObservation(previous);
    }

    static BufferedReader observeReaderForTest(
            BufferedReader reader, Path path) {
        ReaderLifecycleObserver observer = READER_OBSERVER.get();
        return observer == null ? reader : observeReader(reader, observer, path);
    }

    private static BufferedReader observeReader(
            BufferedReader reader,
            ReaderLifecycleObserver observer,
            Path path) {
        try {
            observer.onEvent(ReaderLifecycleEvent.OPENED, path);
        } catch (RuntimeException | Error openedFailure) {
            try {
                reader.close();
            } catch (Throwable closeFailure) {
                if (closeFailure != openedFailure) {
                    openedFailure.addSuppressed(closeFailure);
                }
            }
            throw openedFailure;
        }
        return new ObservedBufferedReader(reader, observer, path);
    }

    /** True when a meaningful CSV line is the recorder's optional header. */
    public static boolean isCsvHeader(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.strip();
        if (trimmed.startsWith("\uFEFF")) {
            trimmed = trimmed.substring(1).stripLeading();
        }
        int comma = trimmed.indexOf(',');
        String firstColumn = comma >= 0
                ? trimmed.substring(0, comma).strip()
                : trimmed;
        return "frame".equalsIgnoreCase(firstColumn);
    }

    private static final class ReaderObservation implements AutoCloseable {
        private final ReaderLifecycleObserver previous;
        private boolean closed;

        private ReaderObservation(ReaderLifecycleObserver previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                READER_OBSERVER.remove();
            } else {
                READER_OBSERVER.set(previous);
            }
        }
    }

    private static final class ObservedBufferedReader extends BufferedReader {
        private final ReaderLifecycleObserver observer;
        private final Path path;
        private boolean closed;

        private ObservedBufferedReader(BufferedReader delegate,
                ReaderLifecycleObserver observer, Path path) {
            super(delegate);
            this.observer = observer;
            this.path = path;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            Throwable primaryFailure = null;
            try {
                super.close();
            } catch (Throwable closeFailure) {
                primaryFailure = closeFailure;
            }
            try {
                observer.onEvent(ReaderLifecycleEvent.CLOSED, path);
            } catch (Throwable observerFailure) {
                if (primaryFailure == null) {
                    rethrowCloseFailure(observerFailure);
                } else if (observerFailure != primaryFailure) {
                    primaryFailure.addSuppressed(observerFailure);
                }
            }
            if (primaryFailure != null) {
                rethrowCloseFailure(primaryFailure);
            }
        }

        private static void rethrowCloseFailure(Throwable failure)
                throws IOException {
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IOException("reader close failed", failure);
        }
    }
}
