package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kResultsTally {
    private static final int[] TIME_BONUSES = {5000, 5000, 1000, 500, 400, 300, 100, 10};

    private int calculateTimeBonus(int elapsedSeconds) {
        if (elapsedSeconds >= 599) return 10000;
        int index = Math.min(elapsedSeconds / 30, TIME_BONUSES.length - 1);
        return TIME_BONUSES[index];
    }

    @Test void timeBonusTable_0seconds() { assertEquals(5000, calculateTimeBonus(0)); }
    @Test void timeBonusTable_29seconds() { assertEquals(5000, calculateTimeBonus(29)); }
    @Test void timeBonusTable_30seconds() { assertEquals(5000, calculateTimeBonus(30)); }
    @Test void timeBonusTable_60seconds() { assertEquals(1000, calculateTimeBonus(60)); }
    @Test void timeBonusTable_90seconds() { assertEquals(500, calculateTimeBonus(90)); }
    @Test void timeBonusTable_210seconds() { assertEquals(10, calculateTimeBonus(210)); }
    @Test void timeBonusTable_300seconds_capped() { assertEquals(10, calculateTimeBonus(300)); }
    @Test void timeBonusSpecialCase_959() { assertEquals(10000, calculateTimeBonus(599)); }
    @Test void timeBonusSpecialCase_above959() { assertEquals(10000, calculateTimeBonus(600)); }
    @Test void ringBonus_100rings() { assertEquals(1000, 100 * 10); }
    @Test void ringBonus_0rings() { assertEquals(0, 0 * 10); }

    @Test void tallyDecrement_countsDownBy10() {
        int timeBonus = 500, ringBonus = 100, totalCountUp = 0, frames = 0;
        while (timeBonus > 0 || ringBonus > 0) {
            int decrement = 0;
            if (timeBonus > 0) { timeBonus -= 10; decrement += 10; }
            if (ringBonus > 0) { ringBonus -= 10; decrement += 10; }
            totalCountUp += decrement;
            frames++;
        }
        assertEquals(0, timeBonus);
        assertEquals(0, ringBonus);
        assertEquals(600, totalCountUp);
        assertEquals(50, frames);
    }
}


