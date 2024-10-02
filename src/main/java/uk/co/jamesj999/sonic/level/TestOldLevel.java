package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.sprites.interactive.monitors.RingMonitor;

public class TestOldLevel extends AbstractOldLevel {

    @Override
    protected void setupTiles() {
        SolidTile halfFlat = new SolidTile(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
                8, (byte) 0x00, true);

        SolidTile lowFlat = new SolidTile(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                (byte) 0x00, false);
        SolidTile lowFlatToSlope = new SolidTile(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3,
                3, 3, 3, (byte) 0xFE, false);
        SolidTile lowFlatToSlope2 = new SolidTile(3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 5,
                5, 5, 5, (byte) 0xFC, false);
        SolidTile lowFlatToSlope3 = new SolidTile(5, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8,
                8, 8, 8, (byte) 0xFA, false);
        SolidTile lowFlatToSlope4 = new SolidTile(9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11,
                12, 12, 12, 12, 13, (byte) 0xF8, false);
        SolidTile lowFlatToSlope5 = new SolidTile(13, 13, 14, 14, 14, 15, 15, 15, 16, 16,
                16, 16, 16, 16, 16, 16, (byte) 0xF6, false);
        SolidTile lowSteepSlope = new SolidTile(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4,
                4, 4, (byte) 0xF8, false);
        SolidTile lowSteepSlope2 = new SolidTile(5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8,
                8, 8, 8, (byte) 0xF8, false);
        SolidTile lowSteepSlope3 = new SolidTile(9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11,
                11, 12, 12, 12, 12, (byte) 0xF8, false);
        SolidTile lowSteepSlope4 = new SolidTile(13, 13, 13, 13, 14, 14, 14, 14, 15, 15,
                15, 15, 16, 16, 16, 16, (byte) 0xF8, false);
        SolidTile halfHighPlatform = new SolidTile(16, 16, 16, 16, 16, 16, 16, 16, 0, 0,
                0, 0, 0, 0, 0, 0, (byte) 0xFF, false);
        SolidTile lowLessSteep = new SolidTile(1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3,
                3, 3, (byte) 0xFC, false);
        SolidTile lowLessSteep2 = new SolidTile(3, 4, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6,
                7, 7, (byte) 0xF8, false);
        SolidTile highSlopeDown = new SolidTile(16, 16, 15, 14, 14, 13, 12, 11, 11, 10,
                10, 9, 8, 8, 7, 7, (byte) 0x18, false);
        SolidTile highSlopeDown2 = new SolidTile(6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0, 0,
                0, 0, 0, (byte) 0x10, false);
        SolidTile full = new SolidTile(16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16,
                16, 16, 16, 16, (byte) 0x00, false);

        SolidTile famousCurve1 = new SolidTile(1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2,
                3, 3, (byte) 0xFC, false);
        SolidTile famousCurve2 = new SolidTile(3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 7, 7, 8, 8,
                9, 9, (byte) 0xF0, false);
        SolidTile famousCurve3 = new SolidTile(10, 10, 11, 12, 12, 13, 14, 14, 15, 16,
                16, 16, 16, 16, 16, 16, (byte) 0xE8, false);
        SolidTile famousCurve4 = new SolidTile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4,
                5, 6, (byte) 0xE0, false);
        SolidTile famousCurve5 = new SolidTile(7, 8, 10, 11, 13, 14, 16, 16, 16, 16, 16,
                16, 16, 16, 16, 16, (byte) 0xD8, false);
        SolidTile famousCurve6 = new SolidTile(0, 0, 0, 0, 0, 0, 0, 2, 4, 6, 8, 11, 14,
                16, 16, 16, (byte) 0xD0, false);
        SolidTile famousCurve7 = new SolidTile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2,
                8, 16, (byte) 0xC4, false);

        addTile(lowSteepSlope, 10, 1);
        addTile(lowSteepSlope2, 11, 1);
        addTile(lowSteepSlope3, 12, 1);
        addTile(lowSteepSlope4, 13, 1);

        drawRange(0, 9, 1, 1, lowFlat);

        addTile(lowSteepSlope, 14, 2);
        addTile(lowSteepSlope2, 15, 2);
        addTile(lowSteepSlope3, 16, 2);
        addTile(lowSteepSlope4, 17, 2);

        drawRange(14, 17, 1, 1, full);

        addTile(lowSteepSlope, 18, 3);
        addTile(lowSteepSlope2, 19, 3);
        addTile(lowSteepSlope3, 20, 3);
        addTile(lowSteepSlope4, 21, 3);

        drawRange(18, 21, 1, 2, full);

        addTile(lowSteepSlope, 22, 4);
        addTile(lowSteepSlope2, 23, 4);
        addTile(lowSteepSlope3, 24, 4);
        addTile(lowSteepSlope4, 25, 4);

        drawRange(22, 25, 1, 3, full);

        addTile(lowSteepSlope, 26, 5);
        addTile(lowSteepSlope2, 27, 5);
        addTile(lowSteepSlope3, 28, 5);
        addTile(lowSteepSlope4, 29, 5);

        drawRange(26, 29, 1, 4, full);

        addTile(lowSteepSlope, 30, 6);
        addTile(lowSteepSlope2, 31, 6);
        addTile(lowSteepSlope3, 32, 6);
        addTile(lowSteepSlope4, 33, 6);

        drawRange(30, 33, 1, 5, full);

        addTile(highSlopeDown, 34, 6);
        addTile(highSlopeDown2, 35, 6);

        addTile(highSlopeDown, 36, 5);

        drawRange(37, 40, 1, 1, lowFlat);

        addTile(lowFlatToSlope, 41, 1);
        addTile(lowFlatToSlope2, 42, 1);
        addTile(lowFlatToSlope3, 43, 1);
        addTile(lowFlatToSlope4, 44, 1);
        addTile(lowFlatToSlope5, 45, 1);

        drawRange(46, 50, 1, 1, lowFlat);

        addTile(famousCurve1, 50, 1);
        addTile(famousCurve2, 51, 1);
        addTile(famousCurve3, 52, 1);
        addTile(famousCurve4, 52, 2);
        addTile(famousCurve5, 53, 2);
        addTile(famousCurve6, 53, 3);
        addTile(famousCurve7, 53, 4);

        drawRange(0, 255, 0, 0, full);

        drawRange(54, 70, 1, 1, lowFlat);

        drawRange(72, 85, 1, 1, halfFlat);

        drawRange(87, 92, 1, 1, lowFlat);

        drawRange(93, 99, 2, 2, halfHighPlatform);

        drawRange(101, 110, 3, 3, halfHighPlatform);

        drawRange(110, 128, 1, 1, halfFlat);


        //drawRange(60, 60, 1, 200, full);

        //drawRange(60, 2048, 4,4, full);

        drawRange(6, 25, 8, 8, halfFlat);
        drawRange(1, 9, 11, 11, halfFlat);
        drawRange(14, 29, 14, 14, halfFlat);
        drawRange(26, 38, 19, 19, halfFlat);
        drawRange(45, 48, 23, 23, halfFlat);
        drawRange(20, 40, 21, 21, halfFlat);
        drawRange(49, 52, 27, 27, halfFlat);
        drawRange(49, 52, 31, 31, halfFlat);
        drawRange(49, 52, 35, 35, halfFlat);
        drawRange(49, 52, 39, 39, halfFlat);
        drawRange(49, 52, 43, 43, halfFlat);
        drawRange(49, 52, 47, 47, halfFlat);
        drawRange(49, 52, 51, 51, halfFlat);
        drawRange(49, 52, 55, 55, halfFlat);
        drawRange(49, 52, 59, 59, halfFlat);
        drawRange(49, 52, 63, 63, halfFlat);
        drawRange(49, 52, 68, 68, halfFlat);
        drawRange(49, 52, 72, 72, halfFlat);
        drawRange(49, 52, 77, 77, halfFlat);
        drawRange(49, 52, 82, 82, halfFlat);
        drawRange(49, 52, 87, 87, halfFlat);
        drawRange(49, 52, 92, 92, halfFlat);
    }

    protected void registerSprites() {
        spriteManager.addSprite(new RingMonitor("001", (short) 533, (short) 364, 10));
        spriteManager.addSprite(new RingMonitor("002", (short) 560, (short) 36, 10));
    }
}
