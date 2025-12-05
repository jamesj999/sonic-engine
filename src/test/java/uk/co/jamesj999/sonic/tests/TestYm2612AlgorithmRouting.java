package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;

import static org.junit.Assert.assertEquals;

/**
 * Verifies FM algorithm routing matches the documented YM2612 flows using the shared routing helpers.
 */
public class TestYm2612AlgorithmRouting {

    private static final double[] OP_OUT = {1.0, 2.0, 3.0, 4.0};
    private static final double FEEDBACK = 0.5;
    private static final double EPS = 1e-9;

    @Test
    public void algorithm0SerialStack() {
        assertEquals(FEEDBACK, Ym2612Chip.computeModulationInput(0, 0, OP_OUT, FEEDBACK), EPS);
        assertEquals(1.0, Ym2612Chip.computeModulationInput(0, 1, OP_OUT, FEEDBACK), EPS);
        assertEquals(2.0, Ym2612Chip.computeModulationInput(0, 2, OP_OUT, FEEDBACK), EPS);
        assertEquals(3.0, Ym2612Chip.computeModulationInput(0, 3, OP_OUT, FEEDBACK), EPS);
        assertEquals(4.0, Ym2612Chip.computeCarrierSum(0, OP_OUT), EPS);
    }

    @Test
    public void algorithm1DualModIntoStack() {
        assertEquals(3.0, Ym2612Chip.computeModulationInput(1, 2, OP_OUT, FEEDBACK), EPS); // op0+op1 into op2
        assertEquals(3.0, Ym2612Chip.computeModulationInput(1, 3, OP_OUT, FEEDBACK), EPS); // op2 into op3
        assertEquals(4.0, Ym2612Chip.computeCarrierSum(1, OP_OUT), EPS);
    }

    @Test
    public void algorithm2ParallelPlusStack() {
        assertEquals(2.0, Ym2612Chip.computeModulationInput(2, 2, OP_OUT, FEEDBACK), EPS); // op1 into op2
        assertEquals(4.0, Ym2612Chip.computeModulationInput(2, 3, OP_OUT, FEEDBACK), EPS); // op2+op0 into op3
        assertEquals(4.0, Ym2612Chip.computeCarrierSum(2, OP_OUT), EPS);
    }

    @Test
    public void algorithm3StackPlusDirectModulator() {
        assertEquals(1.0, Ym2612Chip.computeModulationInput(3, 1, OP_OUT, FEEDBACK), EPS); // op0 into op1
        assertEquals(5.0, Ym2612Chip.computeModulationInput(3, 3, OP_OUT, FEEDBACK), EPS); // op1+op2 into op3
        assertEquals(4.0, Ym2612Chip.computeCarrierSum(3, OP_OUT), EPS);
    }

    @Test
    public void algorithm4DualStacks() {
        assertEquals(1.0, Ym2612Chip.computeModulationInput(4, 1, OP_OUT, FEEDBACK), EPS); // op0 into op1
        assertEquals(3.0, Ym2612Chip.computeModulationInput(4, 3, OP_OUT, FEEDBACK), EPS); // op2 into op3
        assertEquals(6.0, Ym2612Chip.computeCarrierSum(4, OP_OUT), EPS); // op1 + op3 carriers
    }

    @Test
    public void algorithm5CommonModulator() {
        assertEquals(1.0, Ym2612Chip.computeModulationInput(5, 1, OP_OUT, FEEDBACK), EPS);
        assertEquals(1.0, Ym2612Chip.computeModulationInput(5, 2, OP_OUT, FEEDBACK), EPS);
        assertEquals(1.0, Ym2612Chip.computeModulationInput(5, 3, OP_OUT, FEEDBACK), EPS);
        assertEquals(9.0, Ym2612Chip.computeCarrierSum(5, OP_OUT), EPS); // carriers 2,3,4
    }

    @Test
    public void algorithm6StackPlusTwoCarriers() {
        assertEquals(1.0, Ym2612Chip.computeModulationInput(6, 1, OP_OUT, FEEDBACK), EPS); // op0 into op1
        assertEquals(0.0, Ym2612Chip.computeModulationInput(6, 2, OP_OUT, FEEDBACK), EPS); // ops2/3 direct
        assertEquals(9.0, Ym2612Chip.computeCarrierSum(6, OP_OUT), EPS); // carriers 2,3,4
    }

    @Test
    public void algorithm7AllCarriers() {
        assertEquals(0.0, Ym2612Chip.computeModulationInput(7, 1, OP_OUT, FEEDBACK), EPS);
        assertEquals(10.0, Ym2612Chip.computeCarrierSum(7, OP_OUT), EPS); // all carriers
    }
}
