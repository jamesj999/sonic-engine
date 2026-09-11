package com.openggf.game;

import com.openggf.data.Rom;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.Sonic1RomDetector;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.Sonic2RomDetector;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kRomDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHeaderNameRomDetectors {

    @AfterEach
    void resetBootstrapModule() {
        GameModuleRegistry.reset();
    }

    @Test
    void domesticMatchShortCircuitsInternationalRead() throws Exception {
        Rom rom = openRom("SONIC THE HEDGEHOG 2", "unused");

        assertTrue(new Sonic2RomDetector().canHandle(rom));

        verify(rom, never()).readInternationalName();
    }

    @Test
    void internationalNameIsReadAfterDomesticMiss() throws Exception {
        Rom rom = openRom("OTHER", "SONIC THE HEDGEHOG 2");

        assertTrue(new Sonic2RomDetector().canHandle(rom));
    }

    @Test
    void domesticReadFailureDoesNotAttemptInternational() throws Exception {
        Rom rom = mock(Rom.class);
        when(rom.isOpen()).thenReturn(true);
        when(rom.readDomesticName()).thenThrow(new IOException("header"));

        assertFalse(new Sonic2RomDetector().canHandle(rom));

        verify(rom, never()).readInternationalName();
    }

    @Test
    void nullAndClosedRomsAreRejected() {
        Rom closedRom = mock(Rom.class);

        assertFalse(new Sonic2RomDetector().canHandle(null));
        assertFalse(new Sonic2RomDetector().canHandle(closedRom));
    }

    @Test
    void repeatedWhitespaceAndMixedCaseAreNormalizedBeforeMatching() throws Exception {
        Rom rom = openRom("  sonic\tTHE\nhedgehog    2  ", "unused");

        assertTrue(new Sonic2RomDetector().canHandle(rom));
    }

    @Test
    void sonic1AcceptsSonic1AndRejectsSonic2AndSonic3() throws Exception {
        Sonic1RomDetector detector = new Sonic1RomDetector();

        assertTrue(detector.canHandle(openRom("SONIC THE HEDGEHOG", "unused")));
        assertFalse(detector.canHandle(openRom("SONIC THE HEDGEHOG 2", "unused")));
        assertFalse(detector.canHandle(openRom("SONIC THE HEDGEHOG 3", "unused")));
    }

    @Test
    void sonic2AcceptsOnlyTheSonic2Phrase() throws Exception {
        Sonic2RomDetector detector = new Sonic2RomDetector();

        assertTrue(detector.canHandle(openRom("SONIC THE HEDGEHOG 2", "unused")));
        assertFalse(detector.canHandle(openRom("SONIC THE HEDGEHOG", "unused")));
        assertFalse(detector.canHandle(openRom("SONIC THE HEDGEHOG 3", "unused")));
        assertFalse(detector.canHandle(openRom("SONIC & KNUCKLES", "unused")));
    }

    @Test
    void sonic3kAcceptsEveryCurrentAlias() throws Exception {
        Sonic3kRomDetector detector = new Sonic3kRomDetector();

        assertTrue(detector.canHandle(openRom("SONIC THE HEDGEHOG 3", "unused")));
        assertTrue(detector.canHandle(openRom("SONIC & KNUCKLES", "unused")));
        assertTrue(detector.canHandle(openRom("SONIC3 & KNUCKLES", "unused")));
        assertTrue(detector.canHandle(openRom("SONIC AND KNUCKLES", "unused")));
    }

    @Test
    void prioritiesGameNamesAndModuleTypesRemainUnchanged() {
        Sonic1RomDetector sonic1 = new Sonic1RomDetector();
        Sonic2RomDetector sonic2 = new Sonic2RomDetector();
        Sonic3kRomDetector sonic3k = new Sonic3kRomDetector();

        assertEquals(90, sonic1.getPriority());
        assertEquals(100, sonic2.getPriority());
        assertEquals(80, sonic3k.getPriority());
        assertEquals("Sonic the Hedgehog", sonic1.getGameName());
        assertEquals("Sonic the Hedgehog 2", sonic2.getGameName());
        assertEquals("Sonic 3 & Knuckles", sonic3k.getGameName());
        assertEquals(Sonic1GameModule.class, sonic1.createModule().getClass());
        assertEquals(Sonic2GameModule.class, sonic2.createModule().getClass());
        assertEquals(Sonic3kGameModule.class, sonic3k.createModule().getClass());
    }

    @Test
    void concreteDetectorsShareTheHeaderNameTemplate() {
        assertTrue(AbstractHeaderNameRomDetector.class.isAssignableFrom(Sonic1RomDetector.class));
        assertTrue(AbstractHeaderNameRomDetector.class.isAssignableFrom(Sonic2RomDetector.class));
        assertTrue(AbstractHeaderNameRomDetector.class.isAssignableFrom(Sonic3kRomDetector.class));
    }

    @Test
    void concreteDetectorsRetainOverridableDeclaredCanHandleMethods() {
        assertAll(
                () -> assertDeclaresPublicNonFinalCanHandle(Sonic1RomDetector.class),
                () -> assertDeclaresPublicNonFinalCanHandle(Sonic2RomDetector.class),
                () -> assertDeclaresPublicNonFinalCanHandle(Sonic3kRomDetector.class)
        );
    }

    @Test
    void applyDetectedModuleInstallsTheDetectedBootstrapModule() {
        GameModule detectedModule = new Sonic1GameModule();

        assertTrue(GameModuleRegistry.applyDetectedModule(Optional.of(detectedModule)));

        assertSame(detectedModule, GameModuleRegistry.getBootstrapDefault());
    }

    @Test
    void applyDetectedModuleInstallsAFreshSonic2FallbackWhenDetectionIsEmpty() {
        GameModule previousFallback = new Sonic2GameModule();
        GameModuleRegistry.setCurrent(previousFallback);

        assertFalse(GameModuleRegistry.applyDetectedModule(Optional.empty()));

        assertInstanceOf(Sonic2GameModule.class, GameModuleRegistry.getBootstrapDefault());
        assertNotSame(previousFallback, GameModuleRegistry.getBootstrapDefault());
    }

    @Test
    void serviceAndRegistryDetectionShareSuccessAndFallbackResults() throws Exception {
        assertEquivalentDetectionOutcome(openRom("SONIC THE HEDGEHOG", "unused"));
        assertEquivalentDetectionOutcome(openRom("UNRECOGNIZED", "unused"));
        assertEquivalentDetectionOutcome(null);
        assertEquivalentDetectionOutcome(new Rom());
    }

    private static void assertDeclaresPublicNonFinalCanHandle(Class<?> detectorClass) {
        assertFalse(Modifier.isFinal(detectorClass.getModifiers()));
        Method canHandle = assertDoesNotThrow(
                () -> detectorClass.getDeclaredMethod("canHandle", Rom.class));
        assertTrue(Modifier.isPublic(canHandle.getModifiers()));
        assertFalse(Modifier.isFinal(canHandle.getModifiers()));
    }

    private static void assertEquivalentDetectionOutcome(Rom rom) {
        GameModuleRegistry.reset();
        boolean serviceDetected = RomDetectionService.getInstance().detectAndSetModule(rom);
        Class<?> serviceModuleType = GameModuleRegistry.getBootstrapDefault().getClass();

        GameModuleRegistry.reset();
        boolean registryDetected = GameModuleRegistry.detectAndSetModule(rom);
        Class<?> registryModuleType = GameModuleRegistry.getBootstrapDefault().getClass();

        assertEquals(serviceDetected, registryDetected);
        assertEquals(serviceModuleType, registryModuleType);
    }

    private static Rom openRom(String domestic, String international) throws IOException {
        Rom rom = mock(Rom.class);
        when(rom.isOpen()).thenReturn(true);
        when(rom.readDomesticName()).thenReturn(domestic);
        when(rom.readInternationalName()).thenReturn(international);
        return rom;
    }
}
