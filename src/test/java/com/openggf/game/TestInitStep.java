package com.openggf.game;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class TestInitStep {

    @Test
    public void initStepExecutesAction() {
        AtomicBoolean ran = new AtomicBoolean(false);
        var step = new InitStep("TestStep", "test.asm:1", () -> ran.set(true));
        step.execute();
        assertTrue(ran.get());
    }

    @Test
    public void staticFixupAppliesAction() {
        AtomicBoolean applied = new AtomicBoolean(false);
        var fixup = new StaticFixup("WireGroundSensor",
            "Static ref goes stale after reset",
            () -> applied.set(true));
        fixup.apply();
        assertTrue(applied.get());
    }

    @Test
    public void stepsExecuteInDeclaredOrder() {
        var order = new AtomicInteger(0);
        var steps = List.of(
            new InitStep("First", "test:1", () -> assertEquals(0, order.getAndIncrement())),
            new InitStep("Second", "test:2", () -> assertEquals(1, order.getAndIncrement())),
            new InitStep("Third", "test:3", () -> assertEquals(2, order.getAndIncrement()))
        );
        for (var step : steps) {
            step.execute();
        }
        assertEquals(3, order.get());
    }
}


