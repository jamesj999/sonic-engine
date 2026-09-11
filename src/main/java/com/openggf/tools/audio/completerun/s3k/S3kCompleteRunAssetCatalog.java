package com.openggf.tools.audio.completerun.s3k;

import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.Asset;
import com.openggf.tools.audio.completerun.s3k.S3kCompleteRunStateNormalizer.RomPointer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source-exact occupied address catalog for the shipped locked-on S3K sound driver. */
public final class S3kCompleteRunAssetCatalog {
    public static final String ROM_SHA1 = "cfbf98c36c776677290a872547ac47c53d2761d6";
    private static final long ROM_BYTES = 4L * 1024 * 1024;
    private static final int Z80_ROM_WINDOW = 0x8000;

    private static final int[] SFX_STARTS = {
        0xfde30, 0xfde5e, 0xfde6f, 0xfdea1, 0xfdec5, 0xfdef4, 0xfdf2a, 0xfdf6b,
        0xfdf96, 0xfdfe5, 0xfe023, 0xfe05d, 0xfe088, 0xfe0ab, 0xfe0ce, 0xfe0f1,
        0xfe109, 0xfe122, 0xfe14f, 0xfe177, 0xfe1a4, 0xfe1c4, 0xfe1de, 0xfe206,
        0xfe22e, 0xfe278, 0xfe2a2, 0xfe2cf, 0xfe313, 0xfe322, 0xfe35a, 0xfe38b,
        0xfe3a8, 0xfe3e8, 0xfe42b, 0xfe453, 0xfe463, 0xfe481, 0xfe49a, 0xfe4f6,
        0xfe523, 0xfe530, 0xfe558, 0xfe581, 0xfe5b2, 0xfe5da, 0xfe61b, 0xfe64c,
        0xfe662, 0xfe68c, 0xfe6ab, 0xfe6e1, 0xfe730, 0xfe75c, 0xfe7b0, 0xfe7dd,
        0xfe811, 0xfe823, 0xfe833, 0xfe852, 0xfe886, 0xfe896, 0xfe8e0, 0xfe8ea,
        0xfe917, 0xfe94b, 0xfe978, 0xfe9a7, 0xfe9d1, 0xfea1b, 0xfea48, 0xfea93,
        0xfeac7, 0xfeaf7, 0xfeb28, 0xfeb55, 0xfeb6d, 0xfeb8b, 0xfebba, 0xfec05,
        0xfec32, 0xfec7e, 0xfecab, 0xfecd8, 0xfed05, 0xfed3b, 0xfed68, 0xfed75,
        0xfeda9, 0xfeddf, 0xfee10, 0xfee2a, 0xfee5b, 0xfee91, 0xfeec3, 0xfeef9,
        0xfef2d, 0xfef77, 0xfefa6, 0xfefd5, 0xff009, 0xff01c, 0xff068, 0xff090,
        0xff0af, 0xff114, 0xff14b, 0xff17f, 0xff1c0, 0xff1fc, 0xff214, 0xff23c,
        0xff274, 0xff2a1, 0xff2ce, 0xff2fb, 0xff313, 0xff33b, 0xff365, 0xff38f,
        0xff3ea, 0xff42a, 0xff49c, 0xff4ab, 0xff4da, 0xff507, 0xff582, 0xff5d7,
        0xff603, 0xff67d, 0xff6aa, 0xff6d2, 0xff713, 0xff745, 0xff76c, 0xff794,
        0xff7be, 0xff7ce, 0xff7f9, 0xff837, 0xff86a, 0xff89c, 0xff8d1, 0xff907,
        0xff91e, 0xff94e, 0xff97e, 0xff9b7, 0xff9f2, 0xffa21, 0xffa2b, 0xffa66,
        0xffa9c, 0xffad7, 0xffb12, 0xffb45, 0xffb60, 0xffb6a, 0xffba1, 0xffbbe,
        0xffbf4, 0xffc2d, 0xffc64, 0xffc9d, 0xffcce, 0xffcff, 0xffd32, 0xffd62,
        0xffd94
    };

    private final Map<String, Asset> assets;
    private final Map<Integer, Bank> musicBanks;
    private final Bank sfxBank;

    private S3kCompleteRunAssetCatalog() {
        LinkedHashMap<String, Asset> values = new LinkedHashMap<>();
        // sonic3k.lst:201159-201204. The byte at $E7FFF is startBank alignment fill,
        // and bank 2's finishBank address is $EFF41; neither bank tail is an asset.
        List<Asset> bank1c = addSequence(values, new Seed[] {
                seed("Snd_SKCredits", 0x0e4104), seed("Snd_GameOver", 0x0e5d4b),
                seed("Snd_Continue", 0x0e5fa6), seed("Snd_Results", 0x0e63c0),
                seed("Snd_Invic", 0x0e6574), seed("Snd_Menu", 0x0e67af),
                seed("Snd_FinalBoss", 0x0e774c), seed("Snd_PresSega", 0x0e7cde)
        }, 0x0e7fff, "music.");
        List<Asset> bank1d = addSequence(values, new Seed[] {
                seed("Snd_FBZ1", 0x0e8000), seed("Snd_FBZ2", 0x0e8597),
                seed("Snd_MHZ1", 0x0e8afe), seed("Snd_MHZ2", 0x0e9106),
                seed("Snd_SOZ1", 0x0e9688), seed("Snd_SOZ2", 0x0e9cf2),
                seed("Snd_LRZ1", 0x0ea2e5), seed("Snd_LRZ2", 0x0eacf3),
                seed("Snd_SSZ", 0x0ebe80), seed("Snd_DEZ1", 0x0ec2b4),
                seed("Snd_DEZ2", 0x0ec79f), seed("Snd_Minib_SK", 0x0ecbb1),
                seed("Snd_Boss", 0x0ecee1), seed("Snd_DDZ", 0x0ed3dd),
                seed("Snd_PachBonus", 0x0edcc0), seed("Snd_SpecialS", 0x0ee223),
                seed("Snd_SlotBonus", 0x0eeabb), seed("Snd_Knux", 0x0ef5a3),
                seed("Snd_Title", 0x0ef88e), seed("Snd_1UP", 0x0efd4b),
                seed("Snd_Emerald", 0x0efe75)
        }, 0x0eff41, "music.");

        // Lockon S3/LockOn Data.asm:1289-1334 selects only these S3 assets.
        // Their exact ends come from s3.lst; the omitted standalone songs are holes
        // in the locked-on layout, not permission to accept arbitrary bank bytes.
        List<Asset> bank59 = addSequence(values, new Seed[] {
                seed("Snd_AIZ1", 0x2c8000), seed("Snd_AIZ2", 0x2c9b6d),
                seed("Snd_HCZ1", 0x2cb0bc), seed("Snd_HCZ2", 0x2cc0c6),
                seed("Snd_MGZ1", 0x2cd364), seed("Snd_MGZ2", 0x2cd97b),
                seed("Snd_CNZ2", 0x2cdda9), seed("Snd_CNZ1", 0x2ce48f)
        }, 0x2cebf1, "music.");
        List<Asset> bank5a = addSequence(values, new Seed[] {
                seed("Snd_ICZ2", 0x2d0000), seed("Snd_ICZ1", 0x2d06aa),
                seed("Snd_LBZ2", 0x2d0dc8), seed("Snd_LBZ1", 0x2d1345)
        }, 0x2d17a7, "music.");
        // LockOn Data.asm has explicit orgs at +$AE8, +$19F7, +$6587 and
        // +$75E4. Each latter org equals the preceding source asset's exact end;
        // the leading hole and post-$7EAC bank tail remain unowned.
        List<Asset> bank5b = addSequence(values, new Seed[] {
                seed("Snd_GumBonus", 0x2d8ae8), seed("Snd_ALZ", 0x2d99f7),
                seed("Snd_BPZ", 0x2da4fd), seed("Snd_DPZ", 0x2db0ec),
                seed("Snd_CGZ", 0x2dc324), seed("Snd_EMZ", 0x2dda47),
                seed("Snd_S3Credits", 0x2de587), seed("Snd_2PMenu", 0x2df5e4),
                seed("Snd_Drown", 0x2dfabe)
        }, 0x2dfeac, "music.");

        List<Asset> sfx = new ArrayList<>(SFX_STARTS.length);
        for (int index = 0; index < SFX_STARTS.length; index++) {
            long end = index + 1 < SFX_STARTS.length ? SFX_STARTS[index + 1] : 0x0ffda9;
            String key = "sfx.Sound_" + Integer.toHexString(0x33 + index).toUpperCase();
            sfx.add(add(values, key, SFX_STARTS[index], end));
        }
        // sonic3k.lst:201256-201289: Sound_33 begins at $FDE30 and Sound_DB
        // ends at $FFDA9. The bank prefix and alignment tail are not SFX assets.

        add(values, "z80.driver", 0x0000, 0x1300);
        add(values, "z80.driver-data", 0x1300, 0x1c00);
        assets = Map.copyOf(values);
        musicBanks = Map.of(
                0x1c, new Bank(0x0e0000, bank1c),
                0x1d, new Bank(0x0e8000, bank1d),
                0x59, new Bank(0x2c8000, bank59),
                0x5a, new Bank(0x2d0000, bank5a),
                0x5b, new Bank(0x2d8000, bank5b));
        sfxBank = new Bank(0x0f8000, List.copyOf(sfx));
    }

    public static S3kCompleteRunAssetCatalog load(Path rom) throws IOException {
        Objects.requireNonNull(rom, "locked-on ROM path");
        if (Files.size(rom) != ROM_BYTES || !ROM_SHA1.equals(sha1(rom))) {
            throw new IllegalArgumentException("S3K audio catalog requires the pinned locked-on ROM");
        }
        return new S3kCompleteRunAssetCatalog();
    }

    public Map<String, Asset> assets() { return assets; }

    RomPointer musicPointer(int bankByte, int z80Pointer) {
        Bank bank = musicBanks.get(bankByte);
        if (bank == null) {
            throw new IllegalArgumentException("unknown shipped S3K music bank byte: " + bankByte);
        }
        return bank.resolve(z80Pointer);
    }

    RomPointer sfxPointer(int z80Pointer) { return sfxBank.resolve(z80Pointer); }

    RomPointer musicOrDriverDataPointer(int bankByte, int z80Pointer) {
        return isDriverData(z80Pointer) ? driverDataPointer(z80Pointer)
                : musicPointer(bankByte, z80Pointer);
    }

    RomPointer sfxOrDriverDataPointer(int z80Pointer) {
        return isDriverData(z80Pointer) ? driverDataPointer(z80Pointer) : sfxPointer(z80Pointer);
    }

    RomPointer driverPointer(int z80Pointer) {
        Asset driver = assets.get("z80.driver");
        if (z80Pointer <= 0 || z80Pointer >= driver.romEndExclusive()) {
            throw new IllegalArgumentException("S3K Z80 driver pointer is outside $0001..$12FF");
        }
        return new RomPointer(driver.key(), z80Pointer);
    }

    private RomPointer driverDataPointer(int z80Pointer) {
        Asset data = assets.get("z80.driver-data");
        if (z80Pointer < data.romBase() || z80Pointer >= data.romEndExclusive()) {
            throw new IllegalArgumentException("S3K Z80 driver-data pointer is outside $1300..$1BFF");
        }
        return new RomPointer(data.key(), z80Pointer);
    }

    private boolean isDriverData(int pointer) {
        Asset data = assets.get("z80.driver-data");
        return pointer >= data.romBase() && pointer < data.romEndExclusive();
    }

    private static List<Asset> addSequence(Map<String, Asset> target,
            Seed[] seeds, long finalEnd, String prefix) {
        List<Asset> result = new ArrayList<>(seeds.length);
        for (int index = 0; index < seeds.length; index++) {
            long end = index + 1 < seeds.length ? seeds[index + 1].start() : finalEnd;
            result.add(add(target, prefix + seeds[index].name(), seeds[index].start(), end));
        }
        return List.copyOf(result);
    }

    private static Seed seed(String name, long start) { return new Seed(name, start); }

    private static Asset add(Map<String, Asset> target, String key, long start, long end) {
        Asset asset = new Asset(key, start, end);
        target.put(key, asset);
        return asset;
    }

    private static String sha1(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM is missing SHA-1", error);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private record Seed(String name, long start) { }

    private record Bank(long windowBase, List<Asset> occupiedAssets) {
        private RomPointer resolve(int pointer) {
            if (pointer < Z80_ROM_WINDOW || pointer > 0xffff) {
                throw new IllegalArgumentException("S3K banked pointer is outside the Z80 ROM window");
            }
            long absolute = windowBase + pointer - Z80_ROM_WINDOW;
            Asset match = null;
            for (Asset candidate : occupiedAssets) {
                if (absolute >= candidate.romBase() && absolute < candidate.romEndExclusive()) {
                    if (match != null) {
                        throw new IllegalArgumentException("ambiguous S3K source asset pointer");
                    }
                    match = candidate;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException("S3K pointer is outside occupied source asset ranges");
            }
            return new RomPointer(match.key(), absolute);
        }
    }
}
