package com.openggf.game.sonic3k.audio;

import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.data.Rom;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test to trace voice resolution for S3K tracks with
 * known audio issues: Mini-Boss (0x12E) and Knuckles' Theme (0x11F).
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kVoiceResolution {
    private Sonic3kSmpsLoader loader;

    @BeforeEach
    public void setUp() {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        loader = new Sonic3kSmpsLoader(rom);
    }

    @Test
    public void traceMiniBossVoices() {
        System.out.println("=== Mini-Boss (S3) - music ID 0x2E via S3 tables ===");
        AbstractSmpsData data = loader.loadS3Music(0x2E);
        assertNotNull(data, "Should load S3 Mini-Boss music data");

        System.out.println("Voice pointer: 0x" + Integer.toHexString(data.getVoicePtr()));
        System.out.println("FM channels: " + data.getChannels());
        System.out.println("PSG channels: " + data.getPsgChannels());

        assertTrue(data.getChannels() > 0, "Mini-Boss should have at least 1 FM channel");

        int voiceCount = 0;
        for (int v = 0; v < 20; v++) {
            byte[] voice = data.getVoice(v);
            if (voice != null) {
                System.out.println("Voice " + v + ": " + hexDump(voice));
                voiceCount++;
            }
        }
        assertTrue(voiceCount > 0, "Mini-Boss should have at least 1 voice definition");
    }

    @Test
    public void traceKnucklesVoices() {
        System.out.println("=== Knuckles' Theme (S3) - music ID 0x1F via S3 tables ===");
        AbstractSmpsData data = loader.loadS3Music(0x1F);
        assertNotNull(data, "Should load S3 Knuckles music data");

        System.out.println("Voice pointer: 0x" + Integer.toHexString(data.getVoicePtr()));
        System.out.println("FM channels: " + data.getChannels());
        System.out.println("PSG channels: " + data.getPsgChannels());

        assertTrue(data.getChannels() > 0, "Knuckles should have at least 1 FM channel");

        int voiceCount = 0;
        for (int v = 0; v < 20; v++) {
            byte[] voice = data.getVoice(v);
            if (voice != null) {
                System.out.println("Voice " + v + ": " + hexDump(voice));
                voiceCount++;
            }
        }
        assertTrue(voiceCount > 0, "Knuckles should have at least 1 voice definition");
    }

    @Test
    public void compareS3VsSkMiniBoss() {
        System.out.println("=== Comparing Mini-Boss: S&K (0x18) vs S3 (0x2E) ===");

        AbstractSmpsData sk = loader.loadMusic(0x18);
        AbstractSmpsData s3 = loader.loadS3Music(0x2E);

        assertNotNull(sk, "Should load S&K Mini-Boss music data");
        assertNotNull(s3, "Should load S3 Mini-Boss music data");

        System.out.println("S&K voice ptr: 0x" + Integer.toHexString(sk.getVoicePtr()));
        System.out.println("S3  voice ptr: 0x" + Integer.toHexString(s3.getVoicePtr()));

        assertTrue(sk.getChannels() > 0, "S&K Mini-Boss should have FM channels");
        assertTrue(s3.getChannels() > 0, "S3 Mini-Boss should have FM channels");

        assertCrossVersionVoiceRelationStable(sk, s3, loader.loadMusic(0x18), loader.loadS3Music(0x2E),
                "Mini-Boss S&K(0x18) vs S3(0x2E)");
    }

    @Test
    public void compareS3VsSkKnuckles() {
        System.out.println("=== Comparing Knuckles: S&K (0x1F) vs S3 (0x1F via S3 tables) ===");

        AbstractSmpsData sk = loader.loadMusic(0x1F);
        AbstractSmpsData s3 = loader.loadS3Music(0x1F);

        assertNotNull(sk, "Should load S&K Knuckles music data");
        assertNotNull(s3, "Should load S3 Knuckles music data");

        System.out.println("S&K voice ptr: 0x" + Integer.toHexString(sk.getVoicePtr()));
        System.out.println("S3  voice ptr: 0x" + Integer.toHexString(s3.getVoicePtr()));

        assertTrue(sk.getChannels() > 0, "S&K Knuckles should have FM channels");
        assertTrue(s3.getChannels() > 0, "S3 Knuckles should have FM channels");

        assertCrossVersionVoiceRelationStable(sk, s3, loader.loadMusic(0x1F), loader.loadS3Music(0x1F),
                "Knuckles S&K(0x1F) vs S3(0x1F)");
    }

    /**
     * Characterization guard for the S&amp;K-vs-S3 voice relationship.
     *
     * <p>The intended direction (the S&amp;K and S3 voice tables may legitimately
     * match for shared themes or differ for half-specific arrangements) is not
     * asserted here. Instead, this prints the per-voice comparison for diagnostics
     * and pins the relationship to be reproducible: ROM-backed voice resolution is
     * deterministic, so a fresh reload of each track must reproduce the exact same
     * per-voice null-state and the exact same {@code Arrays.equals(sk, s3)} result.
     * This fails if voice resolution becomes non-deterministic or returns garbage.
     */
    private void assertCrossVersionVoiceRelationStable(
            AbstractSmpsData sk, AbstractSmpsData s3,
            AbstractSmpsData skReload, AbstractSmpsData s3Reload,
            String label) {
        for (int v = 0; v < 10; v++) {
            byte[] skVoice = sk.getVoice(v);
            byte[] s3Voice = s3.getVoice(v);
            boolean skNull = (skVoice == null);
            boolean s3Null = (s3Voice == null);

            byte[] skVoiceReload = skReload.getVoice(v);
            byte[] s3VoiceReload = s3Reload.getVoice(v);

            // Null-state must be reproducible across reloads.
            assertEquals(skNull, skVoiceReload == null,
                    label + " voice " + v + ": S&K null-state must be reproducible");
            assertEquals(s3Null, s3VoiceReload == null,
                    label + " voice " + v + ": S3 null-state must be reproducible");

            if (skNull && s3Null) continue;

            System.out.println("\nVoice " + v + ":");
            if (!skNull) System.out.println("  S&K: " + hexDump(skVoice));
            else System.out.println("  S&K: null");
            if (!s3Null) System.out.println("  S3:  " + hexDump(s3Voice));
            else System.out.println("  S3:  null");

            // A reloaded voice must be byte-identical (deterministic resolution).
            if (!skNull) {
                assertArrayEquals(skVoice, skVoiceReload,
                        label + " voice " + v + ": S&K voice resolution must be deterministic");
            }
            if (!s3Null) {
                assertArrayEquals(s3Voice, s3VoiceReload,
                        label + " voice " + v + ": S3 voice resolution must be deterministic");
            }

            if (!skNull && !s3Null) {
                boolean match = java.util.Arrays.equals(skVoice, s3Voice);
                System.out.println("  Match: " + match);
                // characterization guard: the cross-version relationship is whatever
                // the ROM currently resolves to, and it must be reproducible.
                assertEquals(match, java.util.Arrays.equals(skVoiceReload, s3VoiceReload),
                        label + " voice " + v + ": cross-version match result must be reproducible");
            }
        }
    }

    @Test
    public void traceVoiceResolutionPath() {
        // For debugging: check what path voices take through getVoice()
        System.out.println("=== Voice Resolution Path Tracing ===\n");

        // S3 Mini-Boss
        AbstractSmpsData s3mb = loader.loadS3Music(0x2E);
        assertNotNull(s3mb, "Should load S3 Mini-Boss for voice path tracing");
        System.out.println("S3 Mini-Boss voicePtr=0x" + Integer.toHexString(s3mb.getVoicePtr()));
        int ptr = s3mb.getVoicePtr();
        if (ptr >= 0x8000) {
            System.out.println("  -> Bank-relative voice pointer (0x8000+ range)");
        } else if (ptr >= 0x1300 && ptr < 0x2000) {
            System.out.println("  -> Global instrument table range");
        } else {
            System.out.println("  -> Unexpected range: 0x" + Integer.toHexString(ptr));
        }

        // S3 Knuckles
        AbstractSmpsData s3kn = loader.loadS3Music(0x1F);
        assertNotNull(s3kn, "Should load S3 Knuckles for voice path tracing");
        System.out.println("S3 Knuckles voicePtr=0x" + Integer.toHexString(s3kn.getVoicePtr()));
        ptr = s3kn.getVoicePtr();
        if (ptr >= 0x8000) {
            System.out.println("  -> Bank-relative voice pointer (0x8000+ range)");
        } else if (ptr >= 0x1300 && ptr < 0x2000) {
            System.out.println("  -> Global instrument table range");
        } else {
            System.out.println("  -> Unexpected range: 0x" + Integer.toHexString(ptr));
        }

        // S&K versions for comparison
        AbstractSmpsData skmb = loader.loadMusic(0x18);
        assertNotNull(skmb, "Should load S&K Mini-Boss");
        System.out.println("S&K Mini-Boss voicePtr=0x" + Integer.toHexString(skmb.getVoicePtr()));

        AbstractSmpsData skkn = loader.loadMusic(0x1F);
        assertNotNull(skkn, "Should load S&K Knuckles");
        System.out.println("S&K Knuckles voicePtr=0x" + Integer.toHexString(skkn.getVoicePtr()));

        // Check a known good track for reference
        AbstractSmpsData aiz1 = loader.loadMusic(0x01);
        assertNotNull(aiz1, "Should load AIZ1 music");
        System.out.println("AIZ1 voicePtr=0x" + Integer.toHexString(aiz1.getVoicePtr()));

        // All loaded tracks should have valid (non-zero) voice pointers
        assertTrue(s3mb.getVoicePtr() > 0, "S3 Mini-Boss voice pointer should be non-zero");
        assertTrue(s3kn.getVoicePtr() > 0, "S3 Knuckles voice pointer should be non-zero");
        assertTrue(aiz1.getVoicePtr() > 0, "AIZ1 voice pointer should be non-zero");
    }

    private static String hexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}


