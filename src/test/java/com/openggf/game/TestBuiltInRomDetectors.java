package com.openggf.game;

import com.openggf.game.sonic1.Sonic1RomDetector;
import com.openggf.game.sonic2.Sonic2RomDetector;
import com.openggf.game.sonic3k.Sonic3kRomDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TestBuiltInRomDetectors {

    @Test
    void allRetainsTheDeclaredBuiltInDetectorOrder() {
        assertEquals(
                List.of(Sonic3kRomDetector.class, Sonic1RomDetector.class,
                        Sonic2RomDetector.class),
                BuiltInRomDetectors.all().stream().map(Object::getClass).toList());
    }

    @ParameterizedTest
    @MethodSource("builtInGames")
    void forGameReturnsTheDetectorForItsGame(GameId gameId,
                                             Class<? extends RomDetector> detectorType) {
        assertInstanceOf(detectorType, BuiltInRomDetectors.forGame(gameId));
    }

    @ParameterizedTest
    @MethodSource("builtInGames")
    void forGameReturnsFreshDetectorInstances(GameId gameId,
                                              Class<? extends RomDetector> ignoredDetectorType) {
        assertNotSame(BuiltInRomDetectors.forGame(gameId),
                BuiltInRomDetectors.forGame(gameId));
    }

    private static Stream<Arguments> builtInGames() {
        return Stream.of(
                Arguments.of(GameId.S1, Sonic1RomDetector.class),
                Arguments.of(GameId.S2, Sonic2RomDetector.class),
                Arguments.of(GameId.S3K, Sonic3kRomDetector.class));
    }
}
