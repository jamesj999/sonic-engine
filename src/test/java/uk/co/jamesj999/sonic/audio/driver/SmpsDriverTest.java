package uk.co.jamesj999.sonic.audio.driver;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class SmpsDriverTest {

    @Test
    public void testSfxEndReleasesLocks() throws Exception {
        SmpsDriver driver = new SmpsDriver();

        // Mock Sequencers
        TestSequencer music = new TestSequencer(driver);
        TestSequencer sfx = new TestSequencer(driver);

        driver.addSequencer(music, false);
        driver.addSequencer(sfx, true);

        // SFX locks PSG Channel 1 (index 1)
        // We simulate this by having SFX write to PSG Channel 1
        // 0x80 | (1 << 5) | (1 << 4) | 0x0F (Volume) -> 0xBF
        driver.writePsg(sfx, 0xBF);

        // Verify lock is held by SFX
        Object[] psgLocks = getPsgLocks(driver);
        if (psgLocks[1] != sfx) {
            fail("SFX should have locked PSG Channel 1");
        }
        if (psgLocks[0] != null) {
            fail("PSG Channel 0 should be unlocked");
        }

        // Now SFX ends
        sfx.setComplete(true);

        // Trigger driver update (which handles cleanup)
        // We pass a dummy buffer
        driver.read(new short[2]);

        // Verify locks
        // psgLocks[1] should be released (null)
        // psgLocks[0] should be released (null) - BUT BUG causes it to be locked to sfx!

        psgLocks = getPsgLocks(driver);

        if (psgLocks[1] != null) {
             fail("PSG Channel 1 should have been released. Holder: " + psgLocks[1]);
        }

        if (psgLocks[0] != null) {
             // This is where the bug manifests
             fail("PSG Channel 0 should be unlocked. Holder: " + psgLocks[0]);
        }

        assertNull("PSG Channel 2 should be unlocked", psgLocks[2]);
        assertNull("PSG Channel 3 should be unlocked", psgLocks[3]);
    }

    private Object[] getPsgLocks(SmpsDriver driver) throws Exception {
        Field f = SmpsDriver.class.getDeclaredField("psgLocks");
        f.setAccessible(true);
        return (Object[]) f.get(driver);
    }

    private static class TestSequencer extends SmpsSequencer {
        private boolean complete = false;

        public TestSequencer(SmpsDriver driver) {
            super(new DummySmpsData(), new DacData(Collections.emptyMap(), Collections.emptyMap()), driver);
        }

        public void setComplete(boolean complete) {
            this.complete = complete;
        }

        @Override
        public boolean isComplete() {
            return complete;
        }
    }

    private static class DummySmpsData extends AbstractSmpsData {
        public DummySmpsData() {
            super(new byte[100], 0);
        }

        @Override
        protected void parseHeader() { }

        @Override
        public byte[] getVoice(int voiceId) { return null; }

        @Override
        public byte[] getPsgEnvelope(int id) { return null; }

        @Override
        public int read16(int offset) { return 0; }

        @Override
        public int getBaseNoteOffset() { return 0; }
    }
}
