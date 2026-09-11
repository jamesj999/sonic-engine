package com.openggf.game.sonic3k.audio.smps;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.data.Rom;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROM-backed fixed-point reachability inventory for S3K FF meta coordination
 * commands.
 *
 * <p>The three commands in scope are deliberately not inferred from arbitrary
 * bytes in the SFX bank. The bank contains voices and unrelated streams, so a
 * byte-pattern scan produces false positives. Each loaded track entry is
 * instead decoded to a fixed point over its command/control-flow graph. The
 * worklist follows note durations, jumps, calls, returns, counted loops,
 * conditional loop exits, and continuous-SFX edges until every reachable edge
 * is closed or a malformed/unexplored frontier is reported.</p>
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestSonic3kSmpsMetaCommandReachability {

    // FF01..03 are source-only paths whose raw state is not retained by the
    // Phase-1 E4 projection.  A shipped reachability result would require a
    // different projection contract; it must not be reconstructed from Java
    // track fields.
    private static final Set<Integer> UNRETAINED_META_SUBCOMMANDS = Set.of(0x01, 0x02, 0x03);
    private static final int NATIVE_SFX_LAST_ID = 0xDF;
    private static final int NATIVE_SFX_COUNT = NATIVE_SFX_LAST_ID - Sonic3kSfx.ID_BASE + 1;
    private static final int Z80_BANK_SIZE = 0x8000;
    private static final int S3_DRIVER_ROM = Sonic3kSmpsConstants.S3_Z80_DRIVER_ADDR
            + Sonic3kSmpsConstants.S3_ROM_OFFSET_IN_COMBINED;
    private static final int S3_SFX_BANK_ROM = Sonic3kSmpsConstants.S3_ROM_OFFSET_IN_COMBINED
            + Sonic3kSmpsConstants.SFX_BANK_BASE;
    private static final int SK_ADDITIONAL_LOAD_ADDRESS = Sonic3kSmpsConstants.Z80_GENERAL_PTR_LIST;
    // zTracks DAC/FM then PSG channel-RAM order for S3K music headers.
    private static final int[] MUSIC_FM_VOICE_CONTROLS = {0x16, 0x00, 0x01, 0x02,
            0x04, 0x05, 0x06};
    private static final int[] MUSIC_PSG_VOICE_CONTROLS = {0x80, 0xA0, 0xC0};
    private static final Set<Integer> SHIPPED_PSG_F3_VOICE_CONTROLS =
            Set.of(0x80, 0xA0, 0xC0);
    private static final Set<Integer> FM_OR_DAC_VOICE_CONTROLS =
            Set.of(0x16, 0x00, 0x01, 0x02, 0x04, 0x05, 0x06);

    private enum NativeDispatch {
        MUSIC_CREDITS,
        SFX,
        OTHER
    }

    private enum NativeHalf {
        SK("S&K", true),
        S3("S3", false);

        private final String label;
        private final boolean hasCreditsGuard;

        NativeHalf(String label, boolean hasCreditsGuard) {
            this.label = label;
            this.hasCreditsGuard = hasCreditsGuard;
        }
    }

    @Test
    void loaderScopedSkSfxAndSkS3MusicHaveClosedControlFlowAndDoNotReachTargetMetaCommands() {
        Rom rom = TestEnvironment.currentRom();
        Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(rom);
        Set<Integer> metaSubcommands = new HashSet<>();
        Set<Integer> coordFlags = new HashSet<>();

        int skMusicCount = 0;
        for (int id = 0x01; id <= 0x33; id++) {
            AbstractSmpsData data = loader.loadMusic(id);
            assertNotNull(data, "Missing S&K music stream 0x" + hex(id));
            ControlFlowInventory inventory = inventory(data);
            assertClosed(inventory, "S&K music 0x" + hex(id));
            assertMusicRootsUseShippedPsgNoise(data, "S&K music 0x" + hex(id));
            assertNoMusicMetaPair(data, id, "S&K");
            metaSubcommands.addAll(inventory.metaSubcommands);
            coordFlags.addAll(inventory.coordFlags);
            skMusicCount++;
        }

        int s3MusicCount = 0;
        for (int id = 0x01; id <= 0x32; id++) {
            AbstractSmpsData data = loader.loadS3Music(id);
            assertNotNull(data, "Missing S3 music stream 0x" + hex(id));
            ControlFlowInventory inventory = inventory(data);
            assertClosed(inventory, "S3 music 0x" + hex(id));
            assertMusicRootsUseShippedPsgNoise(data, "S3 music 0x" + hex(id));
            assertNoMusicMetaPair(data, id, "S3");
            metaSubcommands.addAll(inventory.metaSubcommands);
            coordFlags.addAll(inventory.coordFlags);
            s3MusicCount++;
        }

        int skSfxCount = 0;
        for (int id = Sonic3kSfx.ID_BASE; id <= Sonic3kSfx.ID_MAX; id++) {
            AbstractSmpsData data = loader.loadSfx(id);
            assertNotNull(data, "Missing SFX stream 0x" + hex(id));
            ControlFlowInventory inventory = inventory(data);
            assertClosed(inventory, "SFX 0x" + hex(id));
            metaSubcommands.addAll(inventory.metaSubcommands);
            coordFlags.addAll(inventory.coordFlags);
            skSfxCount++;
        }

        assertEquals(0x33, skMusicCount);
        assertEquals(0x32, s3MusicCount);
        assertEquals(Sonic3kSfx.ID_MAX - Sonic3kSfx.ID_BASE + 1, skSfxCount);
        assertTrue(metaSubcommands.contains(0x00),
                "The inventory must observe a live FF00 tempo command");
        assertTrue(metaSubcommands.contains(0x07),
                "The inventory must observe the live SFX FF07 command");
        assertFalse(metaSubcommands.stream().anyMatch(UNRETAINED_META_SUBCOMMANDS::contains),
                () -> "Loader-scoped stream set reached an unimplemented meta command: " + metaSubcommands);
        assertFalse(coordFlags.contains(0xFE),
                "loader-scoped shipped streams reached FM3-special state that Phase 1 does not retain");
    }

    @Test
    void nativeSkAndS3SfxTablesCoverEveryEntryAndCloseFullBanks() throws IOException {
        Rom rom = TestEnvironment.currentRom();
        Set<Integer> metaSubcommands = new HashSet<>();
        Set<Integer> coordFlags = new HashSet<>();

        NativeSfxBank sk = readNativeSfxBank(rom, NativeHalf.SK, readSkAdditionalData(rom),
                Sonic3kSmpsConstants.Z80_SFX_PTR_LIST - SK_ADDITIONAL_LOAD_ADDRESS,
                Sonic3kSmpsConstants.SFX_BANK_BASE, readSkDriver(rom));
        byte[] s3Driver = rom.readBytes(S3_DRIVER_ROM, Z80_BANK_SIZE);
        NativeSfxBank s3 = readNativeSfxBank(rom, NativeHalf.S3, s3Driver,
                Sonic3kSmpsConstants.Z80_SFX_PTR_LIST, S3_SFX_BANK_ROM, s3Driver);

        assertEquals(NATIVE_SFX_COUNT, sk.pointers.size(), "S&K native SFX table count");
        assertEquals(NATIVE_SFX_COUNT, s3.pointers.size(), "S3 native SFX table count");
        assertAliasTargets(sk, "S&K");
        assertAliasTargets(s3, "S3");
        // Independent ROM assertions for the two source-different entries and aliases.
        assertEquals(0xF0AF, sk.pointers.get(0x9B), "S&K ROM 9B pointer");
        assertEquals(0xF49C, sk.pointers.get(0xAD), "S&K ROM AD pointer");
        assertEquals(0xFD94, sk.pointers.get(0xDB), "S&K ROM DB pointer");
        assertEquals(0xFD94, sk.pointers.get(0xDC), "S&K ROM DC alias pointer");
        assertEquals(0xFD94, sk.pointers.get(0xDD), "S&K ROM DD alias pointer");
        assertEquals(0xFD94, sk.pointers.get(0xDE), "S&K ROM DE alias pointer");
        assertEquals(0xFD94, sk.pointers.get(0xDF), "S&K ROM DF alias pointer");
        assertEquals(0xF0AF, s3.pointers.get(0x9B), "S3 ROM 9B pointer");
        assertEquals(0xF49C, s3.pointers.get(0xAD), "S3 ROM AD pointer");
        assertEquals(0xFD94, s3.pointers.get(0xDB), "S3 ROM DB pointer");
        assertEquals(0xFD94, s3.pointers.get(0xDC), "S3 ROM DC alias pointer");
        assertEquals(0xFD94, s3.pointers.get(0xDD), "S3 ROM DD alias pointer");
        assertEquals(0xFD94, s3.pointers.get(0xDE), "S3 ROM DE alias pointer");
        assertEquals(0xFD94, s3.pointers.get(0xDF), "S3 ROM DF alias pointer");
        assertEquals(2, sk.trackCounts.get(0x9B), "S&K ROM 9B track count");
        assertEquals(3, s3.trackCounts.get(0x9B), "S3 ROM 9B track count");
        assertEquals(4, sk.channelIds.get(0xAD), "S&K ROM AD channel ID");
        assertEquals(2, s3.channelIds.get(0xAD), "S3 ROM AD channel ID");
        assertFalse(Arrays.equals(sk.headers.get(0x9B), s3.headers.get(0x9B)),
                "native SFX 0x9B must use the differing S&K/S3 bank entry");
        assertFalse(Arrays.equals(sk.headers.get(0xAD), s3.headers.get(0xAD)),
                "native SFX 0xAD must use the differing S&K/S3 bank entry");

        assertEquals(NativeDispatch.MUSIC_CREDITS, sk.dispatches.get(0xDC),
                "S&K DC dispatch from the ROM type-check routine");
        for (int id = 0xDD; id <= NATIVE_SFX_LAST_ID; id++) {
            assertEquals(NativeDispatch.SFX, sk.dispatches.get(id),
                    "S&K 0x" + hex(id) + " dispatch from the ROM type-check routine");
        }
        for (int id = 0xDC; id <= NATIVE_SFX_LAST_ID; id++) {
            assertEquals(NativeDispatch.SFX, s3.dispatches.get(id),
                    "S3 0x" + hex(id) + " dispatch from the ROM type-check routine");
        }

        for (ControlFlowInventory inventory : sk.inventories.values()) {
            metaSubcommands.addAll(inventory.metaSubcommands);
            coordFlags.addAll(inventory.coordFlags);
        }
        for (ControlFlowInventory inventory : s3.inventories.values()) {
            metaSubcommands.addAll(inventory.metaSubcommands);
            coordFlags.addAll(inventory.coordFlags);
        }
        assertFalse(metaSubcommands.stream().anyMatch(UNRETAINED_META_SUBCOMMANDS::contains),
                () -> "Native SFX banks reached an unimplemented meta command: " + metaSubcommands);
        assertFalse(coordFlags.contains(0xFE),
                "native shipped SFX banks reached FM3-special state that Phase 1 does not retain");
    }

    @Test
    void controlFlowEdgesUseEachRomPointerLayoutAndAreMutationSensitive() {
        assertControlFlowEdges(0xF6, 3, 0x23, false);
        assertControlFlowEdges(0xF7, 5, 0x25, true);
        assertControlFlowEdges(0xF8, 3, 0x23, true);
        // EB is index, pointer-lo, pointer-hi: its fall-through is pos + 4.
        assertControlFlowEdges(0xEB, 4, 0x24, true);
        assertControlFlowEdges(0xFC, 3, 0x23, true);
    }

    @Test
    void terminalCommandsDoNotFallThroughAndMalformedEdgesStayOpen() {
        for (int terminal : new int[] {0xE3, 0xF2, 0xF9}) {
            Sonic3kSfxData data = syntheticSfx(new byte[] {(byte) terminal, (byte) 0xFF, 0x08});
            ControlFlowInventory result = inventory(data, true);
            assertTrue(result.frontier.isEmpty(), "terminal 0x" + hex(terminal)
                    + " must not fall through to an unknown command");
            assertEquals(Set.of(0x20), result.reachable,
                    "terminal 0x" + hex(terminal) + " must close at its own byte");
        }

        Sonic3kSfxData ff07 = syntheticSfx(new byte[] {(byte) 0xFF, 0x07, (byte) 0xF2});
        ControlFlowInventory ff07Result = inventory(ff07, true);
        assertTrue(ff07Result.frontier.isEmpty(), "FF07 has no operand beyond its subcommand");
        assertTrue(ff07Result.reachable.contains(0x22), "FF07 must advance by two bytes");

        Sonic3kSfxData unknownSubcommand = syntheticSfx(new byte[] {(byte) 0xFF, 0x08});
        assertFalse(inventory(unknownSubcommand, true).frontier.isEmpty(),
                "unknown FF subcommands must remain an unexplored frontier");

        Sonic3kSfxData malformedPointer = syntheticSfx(new byte[] {(byte) 0xF6, 0x01, 0x00});
        assertFalse(inventory(malformedPointer, true).frontier.isEmpty(),
                "out-of-bank control-flow pointers must remain an unexplored frontier");

        byte[] malformedRootBank = syntheticBank();
        setLe16(malformedRootBank, 0x16, 0x0001);
        Sonic3kSfxData malformedRoot = new Sonic3kSfxData(malformedRootBank, 0x8000, 0, 0x10);
        assertThrows(AssertionError.class,
                () -> assertRawTrackRoots(malformedRoot, malformedRootBank, 0x10),
                "raw roots outside 0x8000-0xFFFF must be rejected");

        byte[] bank = syntheticBank();
        Arrays.fill(bank, 0x20, 0xFF, (byte) 0);
        bank[0xFF] = 0x01; // duration falls exactly off the 0x100-byte bank
        ControlFlowInventory bankEnd = inventory(new Sonic3kSfxData(bank, 0x8000, 0, 0x10), true);
        assertFalse(bankEnd.frontier.isEmpty(), "bank-end falloff must remain an unexplored frontier");
    }

    @Test
    void skMusic1dUsesTheShippedPsg1F3Root() {
        Sonic3kSmpsLoader loader = new Sonic3kSmpsLoader(TestEnvironment.currentRom());
        AbstractSmpsData data = loader.loadMusic(0x1D);
        assertNotNull(data, "S&K music 0x1D must resolve through the loader");
        assertTrue(data instanceof Sonic3kSmpsData,
                "S&K music 0x1D must retain native S3K bank coordinates");
        Sonic3kSmpsData music = (Sonic3kSmpsData) data;
        NativeTrackRoot psg1 = musicTrackRoots(music, "S&K music 0x1D").stream()
                .filter(root -> root.rawVoiceControl == 0x80)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "S&K music 0x1D must retain a PSG1/0x80 root"));
        assertEquals(0x72FB, psg1.offset,
                "S&K music 0x1D PSG1/0x80 root bank offset");
        ControlFlowInventory inventory = inventory(music, false, List.of(psg1.offset));
        assertFalse(inventory.f3Offsets.isEmpty(),
                "S&K music 0x1D PSG1 root must reach F3");
        assertReachableF3UsesShippedPsgNoise(psg1, inventory, music.getBankData(),
                "S&K music 0x1D");
    }

    @Test
    void perRootF3AuditRejectsFm3EvenWithTheShippedNoiseOperand() {
        Sonic3kSfxData data = syntheticSfx(new byte[] {(byte) 0xF3, (byte) 0xE7,
                (byte) 0xF2});
        NativeTrackRoot fm3 = new NativeTrackRoot(0x20, SmpsSequencer.TrackType.FM, 0x02);
        assertThrows(AssertionError.class,
                () -> assertReachableF3UsesShippedPsgNoise(fm3,
                        inventory(data, true, List.of(fm3.offset)), data.getData(),
                        "mutated FM3 F3 root"),
                "a matching operand must not make an FM3 F3 root look like PSG noise");
    }

    private static void assertControlFlowEdges(int command, int commandLength,
            int fallThrough, boolean hasFallThrough) {
        byte[] stream = new byte[commandLength];
        stream[0] = (byte) command;
        stream[1] = 0x00;
        int pointerOffset = switch (command) {
            case 0xF6, 0xF8, 0xFC -> 1;
            case 0xF7 -> 3;
            case 0xEB -> 2;
            default -> throw new IllegalArgumentException("not a control-flow opcode");
        };
        setLe16(stream, pointerOffset, 0x8040);
        Sonic3kSfxData data = syntheticSfx(stream);
        ControlFlowInventory result = inventory(data, true);
        assertTrue(result.frontier.isEmpty(), "opcode 0x" + hex(command) + " left a frontier");
        assertTrue(result.reachable.contains(0x40), "opcode 0x" + hex(command)
                + " must reach its pointer target");
        if (hasFallThrough) {
            assertTrue(result.reachable.contains(fallThrough), "opcode 0x" + hex(command)
                    + " must reach its fall-through");
        } else {
            assertFalse(result.reachable.contains(0x23), "opcode 0x" + hex(command)
                    + " must not fall through");
        }

        byte[] mutated = Arrays.copyOf(stream, stream.length);
        setLe16(mutated, pointerOffset, 0x8050);
        ControlFlowInventory mutatedResult = inventory(syntheticSfx(mutated), true);
        assertTrue(mutatedResult.reachable.contains(0x50), "opcode 0x" + hex(command)
                + " must respond to a target mutation");
        assertFalse(mutatedResult.reachable.contains(0x40), "opcode 0x" + hex(command)
                + " retained the stale target after mutation");
    }

    private static Sonic3kSfxData syntheticSfx(byte[] stream) {
        byte[] bank = syntheticBank();
        System.arraycopy(stream, 0, bank, 0x20, stream.length);
        return new Sonic3kSfxData(bank, 0x8000, 0, 0x10);
    }

    private static byte[] syntheticBank() {
        byte[] bank = new byte[0x100];
        bank[0x13] = 1; // one track
        bank[0x14] = (byte) 0x80;
        bank[0x15] = 4;
        setLe16(bank, 0x16, 0x8020);
        bank[0x40] = (byte) 0xF2;
        bank[0x50] = (byte) 0xF2;
        bank[0x23] = (byte) 0xF2;
        bank[0x24] = (byte) 0xF2;
        bank[0x25] = (byte) 0xF2;
        return bank;
    }

    private static NativeSfxBank readNativeSfxBank(Rom rom, NativeHalf half, byte[] pointerBlob,
            int pointerOffset, int bankRomAddress, byte[] driverBytes) throws IOException {
        byte[] bank = rom.readBytes(bankRomAddress, Z80_BANK_SIZE);
        NativeSfxBank result = new NativeSfxBank(dispatchesFromDriver(driverBytes, half), NATIVE_SFX_COUNT);
        for (int id = Sonic3kSfx.ID_BASE; id <= NATIVE_SFX_LAST_ID; id++) {
            int tableOffset = pointerOffset + (id - Sonic3kSfx.ID_BASE) * 2;
            assertTrue(tableOffset >= 0 && tableOffset + 1 < pointerBlob.length,
                    half.label + " pointer table truncated before 0x" + hex(id));
            int pointer = readLe16(pointerBlob, tableOffset);
            int headerOffset = pointer - Sonic3kSmpsConstants.Z80_BANK_BASE;
            assertTrue(pointer >= 0x8000 && pointer <= 0xFFFF,
                    half.label + " SFX 0x" + hex(id) + " raw header pointer must be in Z80 bank");
            assertTrue(headerOffset >= 0 && headerOffset + 4 <= bank.length,
                    half.label + " SFX 0x" + hex(id) + " header pointer 0x" + hexWord(pointer)
                            + " is outside the 0x8000 bank");

            int declaredTrackCount = bank[headerOffset + 3] & 0xFF;
            assertTrue(headerOffset + 4 + declaredTrackCount * 6 <= bank.length,
                    half.label + " SFX 0x" + hex(id) + " track headers overrun bank");
            Sonic3kSfxData data = new Sonic3kSfxData(bank, Sonic3kSmpsConstants.Z80_BANK_BASE,
                    0, headerOffset);
            List<? extends SmpsSfxData.SmpsSfxTrack> tracks = data.getTrackEntries();
            assertEquals(declaredTrackCount, tracks.size(),
                    half.label + " SFX 0x" + hex(id) + " parsed track count");
            assertTrue(!tracks.isEmpty(), half.label + " SFX 0x" + hex(id) + " has no track roots");
            result.trackCounts.put(id, declaredTrackCount);
            for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
                SmpsSfxData.SmpsSfxTrack track = tracks.get(trackIndex);
                assertEquals(0x80, bank[headerOffset + 4 + trackIndex * 6] & 0xFF,
                        half.label + " SFX 0x" + hex(id)
                                + " playbackFlags must be the shipped playing bit");
                int rawTrackPointer = readLe16(bank, headerOffset + 6 + trackIndex * 6);
                assertRawTrackRoot(rawTrackPointer, track.pointer(),
                        half.label + " SFX 0x" + hex(id));
                int start = track.pointer();
                assertTrue(start >= 0 && start < bank.length,
                        half.label + " SFX 0x" + hex(id) + " has unresolved track root 0x"
                                + hex(start));
                int rawVoiceControl = bank[headerOffset + 5 + trackIndex * 6] & 0xFF;
                NativeTrackRoot root = new NativeTrackRoot(start,
                        trackType(rawVoiceControl), rawVoiceControl);
                assertReachableF3UsesShippedPsgNoise(root,
                        inventory(data, true, List.of(start)), bank,
                        half.label + " SFX 0x" + hex(id));
            }

            ControlFlowInventory inventory = inventory(data, true);
            assertClosed(inventory, half.label + " native SFX 0x" + hex(id));
            result.pointers.put(id, pointer);
            result.channelIds.put(id, bank[headerOffset + 5] & 0xFF);
            result.headers.put(id, Arrays.copyOfRange(bank, headerOffset,
                    headerOffset + 4 + declaredTrackCount * 6));
            result.inventories.put(id, inventory);
        }
        return result;
    }

    private static void assertAliasTargets(NativeSfxBank bank, String name) {
        int target = bank.pointers.get(0xDB);
        for (int id = 0xDC; id <= NATIVE_SFX_LAST_ID; id++) {
            assertEquals(target, bank.pointers.get(id),
                    name + " native SFX 0x" + hex(id) + " must alias 0xDB's ROM target");
        }
    }

    private static byte[] readSkAdditionalData(Rom rom) throws IOException {
        byte[] compressed = rom.readBytes(Sonic3kSmpsConstants.Z80_ADDITIONAL_DATA_ADDR,
                Z80_BANK_SIZE);
        return KosinskiReader.decompress(Channels.newChannel(new ByteArrayInputStream(compressed)), false);
    }

    private static byte[] readSkDriver(Rom rom) throws IOException {
        byte[] compressed = rom.readBytes(Sonic3kSmpsConstants.Z80_DRIVER_ADDR, Z80_BANK_SIZE);
        return KosinskiReader.decompress(Channels.newChannel(new ByteArrayInputStream(compressed)), false);
    }

    private static Map<Integer, NativeDispatch> dispatchesFromDriver(byte[] driver, NativeHalf half) {
        int typeCheck = half == NativeHalf.SK
                ? findUnique(driver, 0xFE, 0xDC, 0xCA)
                : findUnique(driver, 0xFE, 0xFF, 0xCA, 0xFB, 0x09, 0xFE, 0x33, 0xDA);
        assertTrue(typeCheck >= 0, half.label + " type-check routine is not present in the ROM driver");

        boolean hasCreditsGuard = driver[typeCheck] == (byte) 0xFE
                && (driver[typeCheck + 1] & 0xFF) == 0xDC
                && (driver[typeCheck + 2] & 0xFF) == 0xCA;
        assertEquals(half.hasCreditsGuard, hasCreditsGuard,
                half.label + " ROM type-check credits guard does not match driver variant");
        int musicBoundaryOffset = findOpcodeAfter(driver, typeCheck, 0xFE, 0x33, 0xDA);
        int sfxBoundaryOffset = findOpcodeAfter(driver, typeCheck, 0xFE, 0xE0, 0xDA);
        assertTrue(musicBoundaryOffset >= 0,
                half.label + " ROM type-check routine has no music boundary");
        assertTrue(sfxBoundaryOffset >= 0,
                half.label + " ROM type-check routine has no SFX boundary");
        int musicBoundary = driver[musicBoundaryOffset + 1] & 0xFF;
        int sfxBoundary = driver[sfxBoundaryOffset + 1] & 0xFF;
        assertEquals(0x33, musicBoundary, half.label + " ROM music boundary");
        assertEquals(0xE0, sfxBoundary, half.label + " ROM SFX boundary");
        int creditsId = driver[typeCheck + 1] & 0xFF;

        Map<Integer, NativeDispatch> dispatches = new TreeMap<>();
        for (int id = Sonic3kSfx.ID_BASE; id <= NATIVE_SFX_LAST_ID; id++) {
            if (hasCreditsGuard && id == creditsId) {
                dispatches.put(id, NativeDispatch.MUSIC_CREDITS);
            } else if (id >= musicBoundary && id < sfxBoundary) {
                dispatches.put(id, NativeDispatch.SFX);
            } else {
                dispatches.put(id, NativeDispatch.OTHER);
            }
        }
        return dispatches;
    }

    private static int findUnique(byte[] bytes, int... pattern) {
        int found = -1;
        for (int i = 0; i + pattern.length <= bytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if ((bytes[i + j] & 0xFF) != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                assertEquals(-1, found, "ROM driver dispatch marker occurs more than once");
                found = i;
            }
        }
        return found;
    }

    private static int findOpcodeAfter(byte[] bytes, int start, int... pattern) {
        for (int i = start; i + pattern.length <= bytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if ((bytes[i + j] & 0xFF) != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private static void assertClosed(ControlFlowInventory inventory, String stream) {
        assertTrue(inventory.reachableOffsets > 0, stream + " has no reachable track bytes");
        assertTrue(inventory.terminalOrCycle, stream + " has neither a terminal nor a closed cycle");
        assertTrue(inventory.frontier.isEmpty(),
                () -> stream + " left unexplored control-flow frontier: " + inventory.frontier);
    }

    private static ControlFlowInventory inventory(AbstractSmpsData data) {
        return inventory(data, false);
    }

    private static ControlFlowInventory inventory(AbstractSmpsData data, boolean strictFullBank) {
        boolean bankSpace = data instanceof Sonic3kSmpsData s3
                && s3.getBankData() != null;
        return inventory(data, strictFullBank, trackStarts(data, bankSpace));
    }

    private static ControlFlowInventory inventory(AbstractSmpsData data,
            boolean strictFullBank, List<Integer> starts) {
        byte[] bytes = data.getData();
        boolean bankSpace = data instanceof Sonic3kSmpsData s3 && s3.getBankData() != null;
        if (bankSpace) bytes = ((Sonic3kSmpsData) data).getBankData();
        ControlFlowInventory result = new ControlFlowInventory();
        ArrayDeque<Integer> work = new ArrayDeque<>();
        for (int start : starts) {
            // Zero/foreign pointers are non-track header slots for loader-scoped
            // blobs. A strict full-bank proof must surface them as malformed roots.
            if (start >= 0) {
                work.add(start);
            } else if (strictFullBank) {
                result.frontier.add("unresolved control-flow root");
            }
        }

        while (!work.isEmpty()) {
            int pos = work.removeFirst();
            if (pos < 0 || pos >= bytes.length) {
                result.frontier.add("offset 0x" + Integer.toHexString(pos)
                        + " reaches or exceeds bank end");
                continue;
            }
            if (!result.reachable.add(pos)) {
                result.terminalOrCycle = true;
                continue;
            }
            result.reachableOffsets++;

            int command = bytes[pos] & 0xFF;
            if (command < 0x80) {
                enqueue(result, work, pos + 1, bytes.length, "duration");
                continue;
            }
            if (command < 0xE0) {
                int next = pos + 1;
                if (next < bytes.length && (bytes[next] & 0xFF) < 0x80) {
                    next++;
                }
                enqueue(result, work, next, bytes.length, "note");
                continue;
            }
            if (command != 0xFF) {
                result.coordFlags.add(command);
                if (command == 0xF3) {
                    result.f3Offsets.add(pos);
                }
            }

            switch (command) {
                case 0xE3, 0xF2, 0xF9 -> result.terminalOrCycle = true;
                case 0xF6 -> addPointerEdge(result, work, data, pos + 1, false, "goto", strictFullBank);
                case 0xF7 -> addPointerEdge(result, work, data, pos + 3, true, "loop", strictFullBank);
                case 0xF8 -> addPointerEdge(result, work, data, pos + 1, true, "call", strictFullBank);
                case 0xEB -> addPointerEdge(result, work, data, pos + 2, true, "loop-exit", strictFullBank);
                case 0xFC -> addPointerEdge(result, work, data, pos + 1, true, "continuous", strictFullBank);
                case 0xFF -> {
                    if (pos + 1 >= bytes.length) {
                        result.frontier.add("FF at 0x" + Integer.toHexString(pos) + " lacks subcommand");
                        continue;
                    }
                    int subcommand = bytes[pos + 1] & 0xFF;
                    result.metaSubcommands.add(subcommand);
                    int operands = switch (subcommand) {
                        case 0x00, 0x01, 0x02, 0x04 -> 1;
                        case 0x03 -> 3;
                        case 0x05 -> 4;
                        case 0x06 -> 2;
                        case 0x07 -> 0;
                        default -> -1;
                    };
                    if (operands < 0) {
                        result.frontier.add("unknown FF subcommand 0x" + Integer.toHexString(subcommand)
                                + " at 0x" + Integer.toHexString(pos));
                    } else {
                        enqueue(result, work, pos + 2 + operands, bytes.length, "meta");
                    }
                }
                default -> {
                    int length = parameterLength(command);
                    if (length < 0) {
                        result.frontier.add("unknown command 0x" + Integer.toHexString(command)
                                + " at 0x" + Integer.toHexString(pos));
                    } else {
                        enqueue(result, work, pos + 1 + length, bytes.length, "command");
                    }
                }
            }
        }
        return result;
    }

    private static void assertReachableF3UsesShippedPsgNoise(NativeTrackRoot root,
            ControlFlowInventory inventory, byte[] bank, String label) {
        assertClosed(inventory, label + " root 0x" + hexWord(root.offset));
        for (int commandOffset : inventory.f3Offsets) {
            assertTrue(commandOffset + 1 < bank.length,
                    label + " root 0x" + hexWord(root.offset)
                            + " has truncated reachable F3 at 0x" + hexWord(commandOffset));
            assertEquals(0xE7, bank[commandOffset + 1] & 0xFF,
                    label + " root VoiceControl 0x" + hex(root.rawVoiceControl)
                            + " reachable F3 operand at 0x" + hexWord(commandOffset));
            assertEquals(SmpsSequencer.TrackType.PSG, root.type,
                    label + " root 0x" + hexWord(root.offset)
                            + " reached F3 from a non-PSG VoiceControl 0x"
                            + hex(root.rawVoiceControl));
            assertTrue(SHIPPED_PSG_F3_VOICE_CONTROLS.contains(root.rawVoiceControl),
                    label + " root 0x" + hexWord(root.offset)
                            + " reached F3 from an unsupported PSG VoiceControl 0x"
                            + hex(root.rawVoiceControl));
            assertFalse(FM_OR_DAC_VOICE_CONTROLS.contains(root.rawVoiceControl),
                    label + " root 0x" + hexWord(root.offset)
                            + " reached F3 from FM/DAC VoiceControl 0x"
                            + hex(root.rawVoiceControl));
        }
    }

    private static void assertMusicRootsUseShippedPsgNoise(AbstractSmpsData data,
            String label) {
        assertTrue(data instanceof Sonic3kSmpsData,
                label + " must retain native S3K bank coordinates");
        Sonic3kSmpsData music = (Sonic3kSmpsData) data;
        byte[] bank = music.getBankData();
        assertNotNull(bank, label + " must retain its source bank for root attribution");
        for (NativeTrackRoot root : musicTrackRoots(music, label)) {
            // Keep this inventory per root: a pooled walk loses the source
            // VoiceControl that determines whether F3 is FM3-special or PSG noise.
            assertReachableF3UsesShippedPsgNoise(root,
                    inventory(music, false, List.of(root.offset)), bank, label);
        }
    }

    private static List<NativeTrackRoot> musicTrackRoots(Sonic3kSmpsData music,
            String label) {
        boolean bankSpace = music.getBankData() != null;
        assertTrue(bankSpace, label + " must retain its full Z80 bank");
        List<NativeTrackRoot> roots = new ArrayList<>();
        int[] fmPointers = music.getFmPointers();
        assertTrue(fmPointers.length <= MUSIC_FM_VOICE_CONTROLS.length,
                label + " declares more DAC/FM roots than zTracks supports");
        for (int index = 0; index < fmPointers.length; index++) {
            addMusicTrackRoot(roots, fmPointers[index], MUSIC_FM_VOICE_CONTROLS[index],
                    music, bankSpace, label + " DAC/FM index " + index);
        }
        int[] psgPointers = music.getPsgPointers();
        assertTrue(psgPointers.length <= MUSIC_PSG_VOICE_CONTROLS.length,
                label + " declares more PSG roots than zTracks supports");
        for (int index = 0; index < psgPointers.length; index++) {
            addMusicTrackRoot(roots, psgPointers[index], MUSIC_PSG_VOICE_CONTROLS[index],
                    music, bankSpace, label + " PSG index " + index);
        }
        return roots;
    }

    private static void addMusicTrackRoot(List<NativeTrackRoot> roots, int pointer,
            int rawVoiceControl, Sonic3kSmpsData music, boolean bankSpace, String label) {
        if (pointer <= 0) {
            return;
        }
        int offset = resolvePointer(pointer, music, bankSpace);
        assertTrue(offset >= 0, label + " pointer 0x" + hexWord(pointer)
                + " must resolve in its source bank");
        roots.add(new NativeTrackRoot(offset, trackType(rawVoiceControl), rawVoiceControl));
    }

    private static void addPointerEdge(ControlFlowInventory result, ArrayDeque<Integer> work,
            AbstractSmpsData data, int pointerOffset, boolean fallThrough, String edgeName,
            boolean strictFullBank) {
        byte[] bytes = data instanceof Sonic3kSmpsData s3 && s3.getBankData() != null
                ? s3.getBankData() : data.getData();
        int afterPointer = pointerOffset + 2;
        if (afterPointer > bytes.length) {
            result.frontier.add(edgeName + " at 0x" + Integer.toHexString(pointerOffset - 1)
                    + " has truncated pointer");
            return;
        }
        int raw = (bytes[pointerOffset] & 0xFF) | ((bytes[pointerOffset + 1] & 0xFF) << 8);
        int base = data instanceof Sonic3kSmpsData s3 && s3.getBankData() != null
                ? s3.getBankZ80Base() : data.getZ80StartAddress();
        int target;
        if (strictFullBank) {
            target = raw >= base && raw < base + bytes.length ? raw - base : -1;
        } else {
            target = raw - base;
            if (target < 0 || target >= bytes.length) {
                target = raw < bytes.length ? raw : -1;
            }
        }
        if (target < 0) {
            if ("call".equals(edgeName) && !strictFullBank) {
                // S3 music can call a shared bank routine that is outside the
                // loader's per-song blob. It is a closed external edge, not
                // an unexplored in-stream frontier.
                result.terminalOrCycle = true;
            } else {
                result.frontier.add(edgeName + " target 0x" + Integer.toHexString(raw)
                        + " is outside stream");
            }
        } else {
            enqueue(result, work, target, bytes.length, edgeName + " target");
        }
        if (fallThrough) {
            enqueue(result, work, afterPointer, bytes.length, edgeName + " fallthrough");
        }
    }

    private static void enqueue(ControlFlowInventory result, ArrayDeque<Integer> work,
            int next, int length, String edgeName) {
        if (next < 0 || next >= length) {
            result.frontier.add(edgeName + " reaches 0x" + Integer.toHexString(next)
                    + " at bank end");
        } else {
            work.add(next);
        }
    }

    private static List<Integer> trackStarts(AbstractSmpsData data, boolean bankSpace) {
        List<Integer> starts = new ArrayList<>();
        if (data instanceof SmpsSfxData sfx) {
            for (SmpsSfxData.SmpsSfxTrack track : sfx.getTrackEntries()) {
                starts.add(track.pointer());
            }
            return starts;
        }
        for (int pointer : data.getFmPointers()) {
            if (pointer > 0) starts.add(resolvePointer(pointer, data, bankSpace));
        }
        for (int pointer : data.getPsgPointers()) {
            if (pointer > 0) starts.add(resolvePointer(pointer, data, bankSpace));
        }
        return starts;
    }

    private static int resolvePointer(int pointer, AbstractSmpsData data, boolean bankSpace) {
        if (pointer <= 0) return -1;
        if (bankSpace) {
            Sonic3kSmpsData s3 = (Sonic3kSmpsData) data;
            int relative = pointer - s3.getBankZ80Base();
            return relative >= 0 && relative < s3.getBankData().length ? relative : -1;
        }
        if (pointer < data.getData().length) return pointer;
        int relative = pointer - data.getZ80StartAddress();
        return relative >= 0 && relative < data.getData().length ? relative : -1;
    }

    private static SmpsSequencer.TrackType trackType(int rawVoiceControl) {
        if (rawVoiceControl == 0x10 || rawVoiceControl == 0x16) {
            return SmpsSequencer.TrackType.DAC;
        }
        return (rawVoiceControl & 0x80) != 0
                ? SmpsSequencer.TrackType.PSG : SmpsSequencer.TrackType.FM;
    }

    private static int parameterLength(int command) {
        return switch (command) {
            case 0xE0, 0xE1, 0xE2, 0xE4, 0xE6, 0xE8, 0xEA, 0xEC, 0xED, 0xEF,
                    0xF3, 0xF4, 0xF5, 0xFB, 0xFD -> 1;
            case 0xE7, 0xE9, 0xFA -> 0;
            case 0xE5, 0xEE, 0xF1, 0xF6, 0xF8 -> 2;
            case 0xEB, 0xF7, 0xF0, 0xFE -> 4;
            default -> -1;
        };
    }

    private static void assertNoMusicMetaPair(AbstractSmpsData data, int id, String table) {
        byte[] bytes = data.getData();
        for (int pos = 0; pos + 1 < bytes.length; pos++) {
            if ((bytes[pos] & 0xFF) != 0xFF) continue;
            int sub = bytes[pos + 1] & 0xFF;
            int blobOffset = pos;
            assertFalse(UNRETAINED_META_SUBCOMMANDS.contains(sub),
                    () -> table + " music 0x" + hex(id) + " contains FF" + hex(sub)
                            + " at blob offset 0x" + Integer.toHexString(blobOffset));
        }
    }

    private static String hex(int value) {
        return String.format("%02X", value & 0xFF);
    }

    private static String hexWord(int value) {
        return String.format("%04X", value & 0xFFFF);
    }

    private static int readLe16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static void setLe16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void assertRawTrackRoots(Sonic3kSfxData data, byte[] bank, int headerOffset) {
        List<? extends SmpsSfxData.SmpsSfxTrack> tracks = data.getTrackEntries();
        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            int raw = readLe16(bank, headerOffset + 6 + trackIndex * 6);
            assertRawTrackRoot(raw, tracks.get(trackIndex).pointer(), "synthetic SFX");
        }
    }

    private static void assertRawTrackRoot(int rawPointer, int parsedPointer, String stream) {
        assertTrue(rawPointer >= 0x8000 && rawPointer <= 0xFFFF,
                stream + " raw track pointer must be in Z80 bank");
        assertEquals(rawPointer - Sonic3kSmpsConstants.Z80_BANK_BASE, parsedPointer,
                stream + " parsed root must match raw pointer");
    }

    private static final class NativeSfxBank {
        private final Map<Integer, NativeDispatch> dispatches;
        private final Map<Integer, Integer> pointers = new TreeMap<>();
        private final Map<Integer, Integer> trackCounts = new TreeMap<>();
        private final Map<Integer, Integer> channelIds = new TreeMap<>();
        private final Map<Integer, byte[]> headers = new TreeMap<>();
        private final Map<Integer, ControlFlowInventory> inventories = new TreeMap<>();

        private NativeSfxBank(Map<Integer, NativeDispatch> dispatches, int expectedCount) {
            this.dispatches = dispatches;
            assertEquals(expectedCount, dispatches.size(), "native dispatch table count");
        }
    }

    private record NativeTrackRoot(int offset, SmpsSequencer.TrackType type,
            int rawVoiceControl) {
    }

    private static final class ControlFlowInventory {
        private final Set<Integer> reachable = new HashSet<>();
        private final Set<String> frontier = new HashSet<>();
        private final Set<Integer> metaSubcommands = new HashSet<>();
        private final Set<Integer> coordFlags = new HashSet<>();
        private final Set<Integer> f3Offsets = new HashSet<>();
        private int reachableOffsets;
        private boolean terminalOrCycle;
    }
}
