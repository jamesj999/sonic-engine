package com.openggf.tests.trace;

import com.openggf.trace.*;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseDebugHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestTouchResponseDebugHitFormatter {

    @Test
    void summarisesScansUsingActualObjectCoordinates() {
        TouchResponseDebugHit hit = new TouchResponseDebugHit(
                41,
                new ObjectSpawn(0x0244, 0x038C, 0x23, 0, 0, false, 0),
                0x024C,
                0x0384,
                0x87,
                7,
                6,
                6,
                TouchCategory.HURT,
                false,
                "");

        assertEquals(
                "scan s41 0x23 HURT ov=0 sz=6x6 obj=@024C,0384 sp=@0244,038C",
                TouchResponseDebugHitFormatter.summariseNearbyScans(List.of(hit), 0x0241, 0x0394));
    }

    @Test
    void summarisesOverlapsUsingActualObjectCoordinates() {
        TouchResponseDebugHit hit = new TouchResponseDebugHit(
                35,
                new ObjectSpawn(0x025B, 0x03B7, 0x40, 0, 0, false, 0),
                0x0259,
                0x03B5,
                0x08,
                8,
                16,
                16,
                TouchCategory.ENEMY,
                true,
                "");

        assertEquals(
                "touch s35 0x40 ENEMY obj=@0259,03B5 sp=@025B,03B7",
                TouchResponseDebugHitFormatter.summariseOverlaps(List.of(hit)));
    }

    @Test
    void includesDebugDetailsWhenPresent() {
        TouchResponseDebugHit hit = new TouchResponseDebugHit(
                32,
                new ObjectSpawn(0x037F, 0x00AC, 0x2D, 0, 0, true, 0),
                0x037F,
                0x00AC,
                0x05,
                5,
                12,
                18,
                TouchCategory.ENEMY,
                true,
                "state=3 timer=0");

        assertEquals(
                "touch s32 0x2D ENEMY obj=@037F,00AC state=3 timer=0",
                TouchResponseDebugHitFormatter.summariseOverlaps(List.of(hit)));
    }
}
