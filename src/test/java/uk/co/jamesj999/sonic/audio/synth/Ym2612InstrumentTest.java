package uk.co.jamesj999.sonic.audio.synth;

import org.junit.Test;
import static org.junit.Assert.*;

public class Ym2612InstrumentTest {

    @Test
    public void testSetInstrumentMapping() {
        Ym2612Chip chip = new Ym2612Chip();

        // Format: Op Order 1, 3, 2, 4.
        // Byte 0: FB=5 (101), Algo=3 (011) -> 101011 = 0x2B

        // Params:
        // Group 1: DT/MUL. (DT upper 3, MUL lower 4).
        // Op 1: DT=1, MUL=1 -> 0x11
        // Op 3: DT=3, MUL=3 -> 0x33
        // Op 2: DT=2, MUL=2 -> 0x22
        // Op 4: DT=4, MUL=4 -> 0x44

        // Group 2: RS/AR. (RS upper 2, AR lower 5).
        // Op 1: RS=0, AR=1 -> 0x01
        // Op 3: RS=2, AR=3 -> 0x83
        // Op 2: RS=1, AR=2 -> 0x42
        // Op 4: RS=3, AR=4 -> 0xC4

        // Group 3: AM/D1R. (AM bit 7, D1R lower 5).
        // Op 1: AM=0, D1R=1 -> 0x01
        // Op 3: AM=1, D1R=3 -> 0x83
        // Op 2: AM=0, D1R=2 -> 0x02
        // Op 4: AM=1, D1R=4 -> 0x84

        // Group 4: D2R. (Lower 5).
        // Op 1: 0x01
        // Op 3: 0x03
        // Op 2: 0x02
        // Op 4: 0x04

        // Group 5: D1L/RR. (D1L upper 4, RR lower 4).
        // Op 1: D1L=1, RR=1 -> 0x11
        // Op 3: D1L=3, RR=3 -> 0x33
        // Op 2: D1L=2, RR=2 -> 0x22
        // Op 4: D1L=4, RR=4 -> 0x44

        // Group 6: TL. (Lower 7).
        // Op 1: 0x01
        // Op 3: 0x03
        // Op 2: 0x02
        // Op 4: 0x04

        byte[] voice = new byte[] {
            (byte)0x2B, // 0: FB/Algo

            (byte)0x11, (byte)0x33, (byte)0x22, (byte)0x44, // 1-4: DT/MUL

            (byte)0x01, (byte)0x83, (byte)0x42, (byte)0xC4, // 5-8: RS/AR

            (byte)0x01, (byte)0x83, (byte)0x02, (byte)0x84, // 9-12: AM/D1R

            (byte)0x01, (byte)0x03, (byte)0x02, (byte)0x04, // 13-16: D2R

            (byte)0x11, (byte)0x33, (byte)0x22, (byte)0x44, // 17-20: D1L/RR

            (byte)0x01, (byte)0x03, (byte)0x02, (byte)0x04  // 21-24: TL
        };

        chip.setInstrument(0, voice);

        Ym2612Chip.Channel ch = chip.channels[0];

        assertEquals("Feedback", 5, ch.feedback);
        assertEquals("Algo", 3, ch.algo);

        // Check Operator 1 (Index 0)
        verifyOp(ch.ops[0], 1, 1, 0, 1, 0, 1, 1, 1, 1, 1);

        // Check Operator 2 (Index 1)
        verifyOp(ch.ops[1], 2, 2, 1, 2, 0, 2, 2, 2, 2, 2);

        // Check Operator 3 (Index 2)
        verifyOp(ch.ops[2], 3, 3, 2, 3, 1, 3, 3, 3, 3, 3);

        // Check Operator 4 (Index 3)
        verifyOp(ch.ops[3], 4, 4, 3, 4, 1, 4, 4, 4, 4, 4);
    }

    private void verifyOp(Ym2612Chip.Operator op, int expectedVal,
                          int mul, int rs, int ar, int am, int d1r, int d2r, int d1l, int rr, int tl) {
        assertEquals("MUL", mul, op.mul);
        assertEquals("DT1", expectedVal, op.dt1);
        assertEquals("RS", rs, op.rs);
        assertEquals("AR", ar, op.ar);
        assertEquals("AM", am, op.am);
        assertEquals("D1R", d1r, op.d1r);
        assertEquals("D2R", d2r, op.d2r);
        assertEquals("D1L", d1l, op.d1l);
        assertEquals("RR", rr, op.rr);
        assertEquals("TL", tl, op.tl);
    }
}
