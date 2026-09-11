package com.openggf.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TraceFilesTest {

    @Test
    void resolvePrefersRegularPlainFileOverGzip(@TempDir Path dir) throws Exception {
        Path plain = Files.writeString(dir.resolve("physics.csv"), "plain");
        writeGzip(dir.resolve("physics.csv.gz"), "gzip");

        assertEquals(plain, TraceFiles.resolve(dir, "physics.csv"));
    }

    @Test
    void resolveFallsBackToRegularGzipFile(@TempDir Path dir) throws Exception {
        Path gzip = dir.resolve("physics.csv.gz");
        writeGzip(gzip, "gzip");

        assertEquals(gzip, TraceFiles.resolve(dir, "physics.csv"));
    }

    @Test
    void resolveRejectsDirectoriesAndMissingFiles(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("physics.csv"));

        assertNull(TraceFiles.resolve(dir, "physics.csv"));
        assertNull(TraceFiles.resolve(dir, "missing.csv"));
    }

    @Test
    void openReaderReadsPlainAndGzipAsUtf8(@TempDir Path dir) throws Exception {
        String expected = "Sonic – ソニック";
        Path plain = Files.writeString(dir.resolve("plain.csv"), expected, StandardCharsets.UTF_8);
        Path gzip = dir.resolve("gzip.csv.gz");
        writeGzip(gzip, expected);

        try (BufferedReader plainReader = TraceFiles.openReader(plain);
             BufferedReader gzipReader = TraceFiles.openReader(gzip)) {
            assertEquals(expected, plainReader.readLine());
            assertEquals(expected, gzipReader.readLine());
        }
    }

    @Test
    void malformedGzipThrowsIOException(@TempDir Path dir) throws Exception {
        Path malformed = Files.writeString(dir.resolve("bad.csv.gz"), "not gzip");

        assertThrows(IOException.class, () -> TraceFiles.openReader(malformed));
    }

    @Test
    void traceDataForwardersDelegateToSharedContract(@TempDir Path dir) throws Exception {
        Path gzip = dir.resolve("physics.csv.gz");
        writeGzip(gzip, "forwarded");

        assertEquals(gzip, TraceData.resolveTraceFile(dir, "physics.csv"));
        try (BufferedReader reader = TraceData.openTraceReader(gzip)) {
            assertEquals("forwarded", reader.readLine());
        }
    }

    private static void writeGzip(Path path, String contents) throws IOException {
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }
}
