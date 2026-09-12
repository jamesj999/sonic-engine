package com.openggf.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceInputColumnReader {
    @Test
    void preservesSplitSemanticsAtEveryColumn() {
        for (String line : new String[]{" 0a ,FF,00", "00,,fF", "1,2,", ",,", "00", "  +a , -1,7fffffff"}) {
            String[] columns = line.split(",", -1);
            for (int column = 0; column < columns.length; column++) {
                final int index = column;
                try {
                    int expected = Integer.parseInt(columns[column].trim(), 16);
                    assertEquals(expected, TestTraceFixtureMovieAlignmentGuard.inputValue(line, column));
                } catch (NumberFormatException expected) {
                    assertThrows(NumberFormatException.class,
                            () -> TestTraceFixtureMovieAlignmentGuard.inputValue(line, index));
                }
            }
            assertThrows(ArrayIndexOutOfBoundsException.class,
                    () -> TestTraceFixtureMovieAlignmentGuard.inputValue(line, columns.length));
        }
    }

    @Test
    void preservesMissingHeaderBlankRowsAndPrimitiveGrowth(@TempDir Path dir) throws IOException {
        assertArrayEquals(new int[0], TestTraceFixtureMovieAlignmentGuard.loadInputColumn(dir));
        Path payload = dir.resolve("physics.csv");
        Files.writeString(payload, "");
        assertArrayEquals(new int[0], TestTraceFixtureMovieAlignmentGuard.loadInputColumn(dir));
        Files.writeString(payload, "frame,other\n0,00\n");
        assertThrows(IOException.class, () -> TestTraceFixtureMovieAlignmentGuard.loadInputColumn(dir));
        Files.writeString(payload, "frame,input,other\n\n   \n" + "0, aF ,ignored\n".repeat(2050));
        int[] expected = new int[2050];
        java.util.Arrays.fill(expected, 0xAF);
        assertArrayEquals(expected, TestTraceFixtureMovieAlignmentGuard.loadInputColumn(dir));
        Files.writeString(payload, "frame,input\n0\n");
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> TestTraceFixtureMovieAlignmentGuard.loadInputColumn(dir));
        Files.writeString(payload, "frame,input\n0,zz\n");
        assertThrows(NumberFormatException.class,
                () -> TestTraceFixtureMovieAlignmentGuard.loadInputColumn(dir));
    }
}
