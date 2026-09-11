package com.openggf.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes {@link BenchmarkReport} JSON.
 *
 * <p>Reports are written by one process (a benchmark run under one JVM) and read
 * by another (the comparison tool, running under whichever JVM happens to be
 * convenient), so the on-disk form is the interchange format between them. It is
 * pretty-printed because these files get committed alongside audit documents and
 * read in diffs.
 */
public final class BenchmarkReportIo {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private BenchmarkReportIo() {
    }

    public static void write(BenchmarkReport report, Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writeValue(destination.toFile(), report);
    }

    public static BenchmarkReport read(Path source) throws IOException {
        return MAPPER.readValue(source.toFile(), BenchmarkReport.class);
    }
}
