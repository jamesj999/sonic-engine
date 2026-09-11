package com.openggf.tests.playback;

import com.openggf.tests.TestTempFiles;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.recording.UserRecordingWriter;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TestBk2MovieLoader {

    private final Bk2MovieLoader loader = new Bk2MovieLoader();

    @Test
    public void parsesNamedP1Columns() throws Exception {
        Path bk2 = createBk2("""
                [Input]
                LogKey:|P1 Up|P1 Down|P1 Left|P1 Right|P1 B|P1 C|P1 A|P1 Start|
                |.|.|.|R|.|.|.|.|
                |U|.|.|.|B|.|.|S|
                |.|D|L|.|.|C|.|.|
                [/Input]
                """);

        Bk2Movie movie = loader.load(bk2);
        assertEquals(3, movie.getFrameCount());

        int frame0 = movie.getFrame(0).p1InputMask();
        assertTrue((frame0 & AbstractPlayableSprite.INPUT_RIGHT) != 0);

        int frame1 = movie.getFrame(1).p1InputMask();
        assertTrue((frame1 & AbstractPlayableSprite.INPUT_UP) != 0);
        assertTrue((frame1 & AbstractPlayableSprite.INPUT_JUMP) != 0);
        assertTrue(movie.getFrame(1).p1StartPressed());

        int frame2 = movie.getFrame(2).p1InputMask();
        assertTrue((frame2 & AbstractPlayableSprite.INPUT_DOWN) != 0);
        assertTrue((frame2 & AbstractPlayableSprite.INPUT_LEFT) != 0);
        assertTrue((frame2 & AbstractPlayableSprite.INPUT_JUMP) != 0);
    }

    @Test
    public void parsesGroupedGenesisColumns() throws Exception {
        Path bk2 = createBk2("""
                [Input]
                LogKey:|UDLRSABCXYZM|
                |...R..A.....|
                [/Input]
                """);

        Bk2Movie movie = loader.load(bk2);
        assertEquals(1, movie.getFrameCount());
        int mask = movie.getFrame(0).p1InputMask();
        assertTrue((mask & AbstractPlayableSprite.INPUT_RIGHT) != 0);
        assertTrue((mask & AbstractPlayableSprite.INPUT_JUMP) != 0);
    }

    @Test
    public void parsesHashGroupedGenesisLanes() throws Exception {
        Path bk2 = createBk2("""
                [Input]
                LogKey:#Power|Reset|#P1 Up|P1 Down|P1 Left|P1 Right|P1 A|P1 B|P1 C|P1 Start|P1 X|P1 Y|P1 Z|P1 Mode|#P2 Up|P2 Down|P2 Left|P2 Right|P2 A|P2 B|P2 C|P2 Start|P2 X|P2 Y|P2 Z|P2 Mode|
                |..|...R........|............|
                |..|.D....C.....|............|
                |..|U......S....|............|
                [/Input]
                """);

        Bk2Movie movie = loader.load(bk2);
        assertEquals(3, movie.getFrameCount());

        int frame0 = movie.getFrame(0).p1InputMask();
        assertTrue((frame0 & AbstractPlayableSprite.INPUT_RIGHT) != 0);
        assertTrue((frame0 & AbstractPlayableSprite.INPUT_UP) == 0);

        int frame1 = movie.getFrame(1).p1InputMask();
        assertTrue((frame1 & AbstractPlayableSprite.INPUT_DOWN) != 0);
        assertTrue((frame1 & AbstractPlayableSprite.INPUT_JUMP) != 0);

        int frame2 = movie.getFrame(2).p1InputMask();
        assertTrue((frame2 & AbstractPlayableSprite.INPUT_UP) != 0);
        assertTrue(movie.getFrame(2).p1StartPressed());
    }

    @Test
    public void parsesP2HashGroupedGenesisLanes() throws Exception {
        // BizHawk Genplus-gx LogKey for two-controller setups (the format used by
        // S3K AIZ/CNZ trace recordings): hash-grouped lanes, P1 first then P2.
        Path bk2 = createBk2("""
                [Input]
                LogKey:#Power|Reset|#P1 Up|P1 Down|P1 Left|P1 Right|P1 A|P1 B|P1 C|P1 Start|#P2 Up|P2 Down|P2 Left|P2 Right|P2 A|P2 B|P2 C|P2 Start|
                |..|........|........|
                |..|..L.....|...R....|
                |..|........|....A...|
                |..|........|.......S|
                [/Input]
                """);

        Bk2Movie movie = loader.load(bk2);
        assertEquals(4, movie.getFrameCount());

        // Frame 0: nothing pressed on either lane.
        assertEquals(0, movie.getFrame(0).p1InputMask());
        assertEquals(0, movie.getFrame(0).p2InputMask());
        assertEquals(0, movie.getFrame(0).p2ActionMask());

        // Frame 1: P1 Left + P2 Right (independent lanes resolved separately).
        assertTrue((movie.getFrame(1).p1InputMask() & AbstractPlayableSprite.INPUT_LEFT) != 0);
        assertTrue((movie.getFrame(1).p2InputMask() & AbstractPlayableSprite.INPUT_RIGHT) != 0);
        assertTrue((movie.getFrame(1).p1InputMask() & AbstractPlayableSprite.INPUT_RIGHT) == 0);
        assertTrue((movie.getFrame(1).p2InputMask() & AbstractPlayableSprite.INPUT_LEFT) == 0);

        // Frame 2: P2 A → action mask bit 0 set, INPUT_JUMP composed into p2InputMask.
        assertEquals(0x01, movie.getFrame(2).p2ActionMask());
        assertTrue((movie.getFrame(2).p2InputMask() & AbstractPlayableSprite.INPUT_JUMP) != 0);
        assertEquals(0, movie.getFrame(2).p1ActionMask());

        // Frame 3: P2 Start.
        assertTrue(movie.getFrame(3).p2StartPressed());
        assertTrue(!movie.getFrame(3).p1StartPressed());
    }

    @Test
    public void parsesExactWriterLogKey() throws Exception {
        Path bk2 = createBk2("""
                [Input]
                LogKey:%s
                |.....A..|...R.A..|
                [/Input]
                """.formatted(UserRecordingWriter.LOG_KEY));

        Bk2Movie movie = loader.load(bk2);

        assertEquals(1, movie.getFrameCount());
        assertEquals(0x01, movie.getFrame(0).p1ActionMask());
        assertTrue((movie.getFrame(0).p1InputMask() & AbstractPlayableSprite.INPUT_JUMP) != 0);
        assertEquals(0, movie.getFrame(0).p1ActionMask() & 0x06);
        assertTrue((movie.getFrame(0).p2InputMask() & AbstractPlayableSprite.INPUT_RIGHT) != 0);
        assertEquals(0x01, movie.getFrame(0).p2ActionMask());
        assertTrue((movie.getFrame(0).p2InputMask() & AbstractPlayableSprite.INPUT_JUMP) != 0);
        assertTrue((movie.getFrame(0).p1InputMask() & AbstractPlayableSprite.INPUT_RIGHT) == 0);
    }

    @Test
    public void failsWhenInputLogMissing() throws Exception {
        Path file = TestTempFiles.createTempFile("missing-input-log", ".bk2");
        file.toFile().deleteOnExit();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            writeEntry(zip, "Header.txt", "Author: test\n");
        }

        try {
            loader.load(file);
            fail("Expected IOException for missing input log");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Input Log.txt"));
        }
    }

    private static Path createBk2(String inputLogContents) throws IOException {
        Path file = TestTempFiles.createTempFile("playback-test", ".bk2");
        file.toFile().deleteOnExit();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            writeEntry(zip, "Header.txt", "Author: unit-test\n");
            writeEntry(zip, "Input Log.txt", inputLogContents);
        }
        return file;
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

