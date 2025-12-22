package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.data.Rom;

import java.io.File;

public class InspectChemicalPlant {
    public static void main(String[] args) {
        Rom rom = new Rom();
        File romFile = new File("Sonic The Hedgehog 2 (W) (REV01) [!].gen");
        
        if (!rom.open(romFile.getAbsolutePath())) {
            System.err.println("Failed to open ROM at: " + romFile.getAbsolutePath());
            return;
        }

        Sonic2SmpsLoader loader = new Sonic2SmpsLoader(rom);
        System.out.println("Loading Chemical Plant Zone (0x8C)...");
        AbstractSmpsData data = loader.loadMusic(0x8C);

        if (data == null) {
            System.err.println("Failed to load SMPS data for 0x8C");
            return;
        }
        
        // Z80 start address for S2 music is 0x1380
        int z80Start = 0x1380;

        int[] fmPointers = data.getFmPointers();
        int[] fmVols = data.getFmVolumeOffsets();

        System.out.println("FM Tracks: " + fmPointers.length);
        // Inspect Track 3 and 4 (Indices 3 and 4)
        for (int i = 3; i <= 4; i++) {
            if (i >= fmPointers.length) break;
            int ptr = fmPointers[i];
            int vol = fmVols[i];
            System.out.printf("\nTrack %d: Ptr=%04X, Vol=%d\n", i, ptr, vol);
            
            // Relocate pointer to buffer offset
            // S2 pointers are Z80 addresses. data starts at 0x1380.
            int offset = ptr - z80Start;
            if (offset >= 0 && offset < data.getData().length) {
                System.out.print("Data: ");
                for (int j = 0; j < 10 && offset + j < data.getData().length; j++) {
                    System.out.printf("%02X ", data.getData()[offset + j]);
                }
                System.out.println();
                
                // Check for Voice change (EF xx)
                int voiceId = 0; // Default
                // Scan a bit further for EF
                for (int j = 0; j < 30 && offset + j < data.getData().length; j++) {
                    int b = data.getData()[offset + j] & 0xFF;
                    if (b == 0xEF) {
                        if (offset + j + 1 < data.getData().length) {
                            int foundVoice = data.getData()[offset + j + 1] & 0xFF;
                            System.out.println("Found Voice Change to: " + foundVoice);
                            dumpVoice(data, foundVoice, vol);
                        }
                    } else if (b >= 0xE0) {
                         // skip flags
                    } else if (b >= 0x80) {
                        // Note
                    }
                }
                
                // dumpVoice(data, voiceId, vol); // Don't dump last one again
            } else {
                System.out.println("Invalid Pointer/Offset");
            }
        }
    }
    
    private static void dumpVoice(AbstractSmpsData data, int voiceId, int volOffset) {
        byte[] voice = data.getVoice(voiceId);
        if (voice == null) {
            System.out.println("Voice " + voiceId + " not found.");
            return;
        }
        System.out.println("Voice " + voiceId + " (Raw):");
        // TL is at bytes 21, 22, 23, 24 (if length 25)
        // Sonic 2 uses 25 byte voices.
        // Byte 0: Feedback/Algo
        // Bytes 21-24: TL for Op 1, 3, 2, 4 (Slot order)
        
        String[] opNames = {"Op1", "Op3", "Op2", "Op4"};
        for (int k = 0; k < 25; k++) {
            System.out.printf("%02X ", voice[k]);
        }
        System.out.println();
        
        if (voice.length >= 25) {
            System.out.println("TL Values (Raw -> +Vol -> Clamped?):");
            for (int op = 0; op < 4; op++) {
                int original = voice[21 + op] & 0x7F;
                int withVol = original + volOffset;
                int wrapped = withVol & 0x7F;
                int clamped = Math.min(127, Math.max(0, withVol));
                
                System.out.printf("  %s: %3d + %2d = %3d -> Wrap: %3d | Clamp: %3d\n", 
                    opNames[op], original, volOffset, withVol, wrapped, clamped);
            }
        }
    }
}
