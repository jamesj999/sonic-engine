package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;

import static org.junit.Assert.assertEquals;

/**
 * Verifies FM algorithm routing matches the documented YM2612 flows using the shared routing helpers.
 * Operator indices follow ops[0..3] -> Op1, Op2, Op3, Op4 with slot mapping {0,2,1,3}.
 */
public class TestYm2612AlgorithmRouting {

    private static final double[] OP_OUT = {1.0, 2.0, 3.0, 4.0};
    private static final double FEEDBACK = 0.5;
    private static final double MEM = 6.0;
    private static final double EPS = 1e-9;

    @Test
    public void algorithm0SerialStack() {
        assertEquals(FEEDBACK, Ym2612Chip.computeModulationInputWithMem(0, 0, OP_OUT, FEEDBACK, MEM), EPS);
        assertEquals(MEM, Ym2612Chip.computeModulationInputWithMem(0, 1, OP_OUT, FEEDBACK, MEM), EPS); // Op2 <- MEM
        assertEquals(1.0, Ym2612Chip.computeModulationInputWithMem(0, 2, OP_OUT, FEEDBACK, MEM), EPS); // Op3 <- Op1
        assertEquals(2.0, Ym2612Chip.computeModulationInputWithMem(0, 3, OP_OUT, FEEDBACK, MEM), EPS); // Op4 <- Op2
        assertEquals(4.0, Ym2612Chip.computeCarrierSum(0, OP_OUT), EPS);
    }

    @Test
    public void algorithm1DualModIntoStack() {
        assertEquals(MEM, Ym2612Chip.computeModulationInputWithMem(1, 1, OP_OUT, FEEDBACK, MEM), EPS); // Op2 <- MEM
        assertEquals(0.0, Ym2612Chip.computeModulationInputWithMem(1, 2, OP_OUT, FEEDBACK, MEM), EPS); // Op3 is pure modulator
        assertEquals(2.0, Ym2612Chip.computeModulationInputWithMem(1, 3, OP_OUT, FEEDBACK, MEM), EPS); // Op4 <- Op2
        assertEquals(4.0, Ym2612Chip.computeCarrierSum(1, OP_OUT), EPS);
    }

    @Test
    public void algorithm2ParallelPlusStack() {
        assertEquals(MEM, Ym2612Chip.computeModulationInputWithMem(2, 1, OP_OUT, FEEDBACK, MEM), EPS); // Op2 <- MEM
        assertEquals(3.0, Ym2612Chip.computeModulationInputWithMem(2, 3, OP_OUT, FEEDBACK, MEM), EPS); // Op4 <- Op1 + Op2
        assertEquals(4.0, Ym2612Chip.computeCarrierSum(2, OP_OUT), EPS);
    }

    @Test
    public void algorithm3StackPlusDirectModulator() {
        assertEquals(0.0, Ym2612Chip.computeModulationInputWithMem(3, 1, OP_OUT, FEEDBACK, MEM), EPS); // Op2 direct carrier
        assertEquals(1.0, Ym2612Chip.computeModulationInputWithMem(3, 2, OP_OUT, FEEDBACK, MEM), EPS); // Op3 <- Op1
        assertEquals(8.0, Ym2612Chip.computeModulationInputWithMem(3, 3, OP_OUT, FEEDBACK, MEM), EPS); // Op4 <- MEM + Op2
        assertEquals(4.0, Ym2612Chip.computeCarrierSum(3, OP_OUT), EPS);
    }

    @Test
    public void algorithm4DualStacks() {
        assertEquals(0.0, Ym2612Chip.computeModulationInputWithMem(4, 1, OP_OUT, FEEDBACK, MEM), EPS); // Op2 carrier
        assertEquals(1.0, Ym2612Chip.computeModulationInputWithMem(4, 2, OP_OUT, FEEDBACK, MEM), EPS); // Op3 <- Op1
        assertEquals(2.0, Ym2612Chip.computeModulationInputWithMem(4, 3, OP_OUT, FEEDBACK, MEM), EPS); // Op4 <- Op2
        assertEquals(7.0, Ym2612Chip.computeCarrierSum(4, OP_OUT), EPS); // Op3 + Op4 carriers
    }

    @Test
    public void algorithm5CommonModulator() {
        assertEquals(MEM, Ym2612Chip.computeModulationInputWithMem(5, 1, OP_OUT, FEEDBACK, MEM), EPS);
        assertEquals(1.0, Ym2612Chip.computeModulationInputWithMem(5, 2, OP_OUT, FEEDBACK, MEM), EPS);
        assertEquals(1.0, Ym2612Chip.computeModulationInputWithMem(5, 3, OP_OUT, FEEDBACK, MEM), EPS);
        assertEquals(9.0, Ym2612Chip.computeCarrierSum(5, OP_OUT), EPS); // carriers Op2, Op3, Op4
    }

    @Test
    public void algorithm6StackPlusTwoCarriers() {
        assertEquals(0.0, Ym2612Chip.computeModulationInputWithMem(6, 1, OP_OUT, FEEDBACK, MEM), EPS); // Op2 direct carrier
        assertEquals(1.0, Ym2612Chip.computeModulationInputWithMem(6, 2, OP_OUT, FEEDBACK, MEM), EPS); // Op3 <- Op1
        assertEquals(0.0, Ym2612Chip.computeModulationInputWithMem(6, 3, OP_OUT, FEEDBACK, MEM), EPS); // Op4 direct carrier
        assertEquals(9.0, Ym2612Chip.computeCarrierSum(6, OP_OUT), EPS); // carriers Op2, Op3, Op4
    }

    @Test
    public void algorithm7AllCarriers() {
        assertEquals(0.0, Ym2612Chip.computeModulationInputWithMem(7, 1, OP_OUT, FEEDBACK, MEM), EPS);
        assertEquals(10.0, Ym2612Chip.computeCarrierSum(7, OP_OUT), EPS); // all carriers
    }
}
