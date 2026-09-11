package com.openggf.game.sonic1.objects;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every Sonic 1 {@code SolidObjectProvider} must account for the ROM
 * {@code obActWid} that {@code Sonic_Balance} reads off the object the player
 * is standing on ({@code docs/s1disasm/_incObj/01 Sonic.asm:423}).
 *
 * <p><b>Why this guard exists.</b> {@code AbstractObjectInstance
 * #getBalanceWidthPixels()} returns {@code getOnScreenHalfWidth()} -- the shared
 * 16 -- except for top-solid objects, where it returns
 * {@code getSolidParams().halfWidth()}. That fallback encodes an assumption:
 * that a {@code PlatformObject} caller passes {@code obActWid} straight through
 * as {@code d1}. The assumption is <em>usually true</em>. It holds for seven S1
 * top-solid objects and fails for four, and because it is silent, two of those
 * four sat wrong until a trace went looking and the other two until an audit
 * did. An assumption that is usually right and quietly wrong is a missing
 * invariant, not a series of individual mistakes -- so it is written down here
 * rather than deleted, since deleting it would mean adding seven overrides that
 * only preserve behaviour those classes already get.
 *
 * <p><b>The trap this closes.</b> On a top-solid object the fallback is
 * consulted <em>before</em> {@code getOnScreenHalfWidth()}, so supplying the ROM
 * byte at the on-screen accessor -- the normally correct siting, because
 * {@code BuildSprites} reads the same byte -- is a <b>silent no-op for
 * balance</b>. It looks like a fix, compiles, changes nothing, and measures
 * clean. Only an override at {@code getBalanceWidthPixels()} reaches the test.
 *
 * <p><b>What this guard does and does not check.</b> Every expected value below
 * is transcribed by hand from the disassembly with its file and line, never read
 * back off the engine -- an expectation sourced from the code under test is a
 * tautology that passes forever while the engine drifts. What is verified is
 * that each class's <em>declaration</em> matches the disposition recorded for
 * it, and that no {@code SolidObjectProvider} is missing from the table. What is
 * <em>not</em> verified is the runtime value: this guard does not instantiate
 * objects, so a class recorded as {@code FALLBACK_MATCHES_ROM} whose solid
 * params later change will not be caught here. Closing that would need live
 * instances and is a larger piece of work.
 *
 * <p>Full survey:
 * {@code docs/architecture/audits/2026-08-19-s1-obactwid-balance-width-audit.md}.
 */
class TestS1BalanceWidthRomParityGuard {

    /** How a class accounts for its ROM {@code obActWid}. */
    private enum Disposition {
        /** ROM {@code obActWid} is 16, so the shared default is already right. */
        DEFAULT_IS_ROM_CORRECT,
        /** Top-solid, and the ROM routine passes {@code obActWid} as {@code d1}. */
        FALLBACK_MATCHES_ROM,
        /** The class supplies the byte itself. */
        DECLARES_OWN_WIDTH,
        /** Known divergence, cited, not yet fixed. Fails on purpose. */
        KNOWN_MISMATCH,
        /**
         * ROM {@code obActWid} differs from the shared 16 and the class inherits
         * it, but the object is not top-solid and no assessment was made of
         * whether a standing-still balance on it is reachable. Recorded so the
         * class is not silently absent; does not fail, because the divergence is
         * unconfirmed rather than established.
         */
        RECORDED_UNASSESSED,
        /**
         * ROM {@code obActWid} differs from the shared 16 and the class inherits
         * it, but the ROM makes a grounded, standing-still balance on the object
         * unreachable, so the difference cannot be observed. Recorded with the
         * routine that forecloses it rather than fixed, because an override here
         * would be a change no movie can distinguish -- and the citation is what
         * stops the row being re-opened every time the audit is re-read.
         */
        RECORDED_UNREACHABLE
    }

    /**
     * A class's ROM {@code obActWid}. {@code Fixed} carries the byte; {@code
     * Dynamic} says the ROM recomputes it at runtime and names what it tracks.
     * The distinction is deliberate rather than a sentinel: Obj83's width walks
     * from {@code 0x80} to zero as its floor crumbles, and a number in this
     * column would invite someone to "fix" such an object with a constant that
     * agrees with the ROM at exactly one width.
     */
    private sealed interface RomWidth {
        record Fixed(int pixels) implements RomWidth { }

        record Dynamic(String tracks) implements RomWidth { }
    }

    private record Entry(RomWidth romActWid, Disposition disposition, String citation, String note) {
        Entry(RomWidth romActWid, Disposition disposition, String citation) {
            this(romActWid, disposition, citation, "");
        }
    }

    private static RomWidth px(int pixels) {
        return new RomWidth.Fixed(pixels);
    }

    private static RomWidth dynamic(String tracks) {
        return new RomWidth.Dynamic(tracks);
    }

    private static final int SHARED_DEFAULT = 16;

    /**
     * ROM {@code obActWid} per class. Values are hand-read from the listing at
     * the cited line; where a {@code FixBugs} conditional writes two, the
     * shipped {@code FixBugs = 0} branch is the one recorded, because that is
     * what the ROM does and what every trace records.
     */
    private static final Map<String, Entry> ROM_ACT_WID = new LinkedHashMap<>();

    static {
        // --- Supplies its own width -------------------------------------------------
        ROM_ACT_WID.put("Sonic1GlassBlockObjectInstance", new Entry(px(32), Disposition.DECLARES_OWN_WIDTH, "30 MZ Large Green Glass Blocks.asm:57,78",
                "pillar; the shine child takes #32/2 at :84 and is a separate class"));
        ROM_ACT_WID.put("Sonic1MonitorObjectInstance", new Entry(px(15), Disposition.DECLARES_OWN_WIDTH, "26, 2E Monitors and Power-Ups.asm:43",
                "the #16/2 at :234 is Pow_Main, the Obj2E icon"));
        ROM_ACT_WID.put("Sonic1CollapsingLedgeObjectInstance", new Entry(px(100), Disposition.DECLARES_OWN_WIDTH, "1A, 53 Collapsing Ledges and Floors.asm:47",
                "FixBugs = 0 branch; SlopeObject d1 is #96/2 = 48 at :61"));
        ROM_ACT_WID.put("Sonic1CollapsingFloorObjectInstance", new Entry(px(68), Disposition.DECLARES_OWN_WIDTH, "1A, 53 Collapsing Ledges and Floors.asm:172",
                "PlatformObject d1 is #64/2 = 32 at :184"));
        ROM_ACT_WID.put("Sonic1LargeGrassyPlatformObjectInstance", new Entry(dynamic("see note"), Disposition.DECLARES_OWN_WIDTH, "2F, 35 MZ Large Grassy Platforms and Burning Grass.asm:53",
                "subtype table"));
        ROM_ACT_WID.put("Sonic1StomperDoorObjectInstance", new Entry(dynamic("see note"), Disposition.DECLARES_OWN_WIDTH, "6B SBZ Stomper and Sliding Door.asm:41", "subtype table"));
        ROM_ACT_WID.put("Sonic1ChainedStomperObjectInstance", new Entry(dynamic("see note"), Disposition.DECLARES_OWN_WIDTH, "31 MZ Chained Stompers.asm:93,105,120", "per-piece"));
        ROM_ACT_WID.put("Sonic1FloatingBlockObjectInstance", new Entry(dynamic("see note"), Disposition.DECLARES_OWN_WIDTH, "56 SYZ, SLZ Floating Blocks and LZ Doors.asm:51", "subtype table"));
        ROM_ACT_WID.put("Sonic1LabyrinthBlockObjectInstance", new Entry(dynamic("see note"), Disposition.DECLARES_OWN_WIDTH, "61 LZ Blocks.asm:42", "subtype table"));
        ROM_ACT_WID.put("Sonic1ElevatorObjectInstance", new Entry(dynamic("see note"), Disposition.DECLARES_OWN_WIDTH, "59 SLZ Elevators.asm:72", "subtype table"));
        ROM_ACT_WID.put("Sonic1GirderBlockObjectInstance", new Entry(px(96), Disposition.DECLARES_OWN_WIDTH, "70 SBZ Girder Block.asm:28"));
        ROM_ACT_WID.put("Sonic1RockObjectInstance", new Entry(px(19), Disposition.DECLARES_OWN_WIDTH, "3B GHZ Purple Rock.asm:20-27",
                "FixBugs = 0 branch (#38/2); the fixed branch would write #48/2 = 24. Full-solid, so "
                        + "the byte is supplied at getOnScreenHalfWidth() and balance inherits it. "
                        + "Rock_Solid d1 is #32/2+sonic_solid_width = $1B at :31"));
        ROM_ACT_WID.put("Sonic1SpinPlatformObjectInstance", new Entry(dynamic("128 trapdoor / 16 spinner"), Disposition.DECLARES_OWN_WIDTH,
                "69 SBZ Spinning Platforms and Trapdoors.asm:28-31,46,49",
                "Spin_Main writes #256/2 for every Obj69 on the FixBugs = 0 branch and overwrites "
                        + "it with #32/2 only on the spinner path; the split is the subtype bit 7 the "
                        + "class already reads. Full-solid, so the byte is supplied at "
                        + "getOnScreenHalfWidth(). Trapdoor SolidObject d1 is #128/2+sonic_solid_width "
                        + "= $4B at :85"));
        ROM_ACT_WID.put("Sonic1SpringObjectInstance", new Entry(dynamic("8 sideways / 16 upright and downward"), Disposition.DECLARES_OWN_WIDTH,
                "41 Springs.asm:45,49-56",
                "Spring_Main writes #32/2 for every spring and overwrites it with #16/2 only on the "
                        + "btst #4 sideways branch that also selects Spring_LR; the downward branch "
                        + "leaves it alone. Full-solid, so the byte is supplied at "
                        + "getOnScreenHalfWidth(). Spring_LR d1 is #16/2+sonic_solid_width = $13 at :117"));
        ROM_ACT_WID.put("Sonic1InvisibleBarrierObjectInstance", new Entry(dynamic("((subtype & $F0) + $10) >> 1"), Disposition.DECLARES_OWN_WIDTH,
                "71 Invisible Solid Barriers.asm:22-27",
                "8 to 120 by placement; sbz1 alone places $70 and $61, giving 64 and 56. Full-solid, "
                        + "so the byte is supplied at getOnScreenHalfWidth(); the cull consumer is "
                        + "dormant because Invis_Solid gates on ChkObjectVisible, which never reads "
                        + "obActWid. Invis_Solid d1 is obActWid+sonic_solid_width at :43-45"));
        ROM_ACT_WID.put("Sonic1PushBlockObjectInstance", new Entry(dynamic("PushB_Var: 16 for the 1x1, 64 for the 4x1"), Disposition.DECLARES_OWN_WIDTH,
                "33 MZ, LZ Pushable Blocks.asm:22-24,48-49",
                "full-solid (isTopSolidOnly() is false), so the byte is supplied at "
                        + "getOnScreenHalfWidth(). PushB_Action pads d1 by sonic_solid_width without "
                        + "writing it back. mz2 places the one 4x1 block, subtype $81"));
        ROM_ACT_WID.put("Sonic1BossBlockInstance", new Entry(px(16), Disposition.DEFAULT_IS_ROM_CORRECT,
                "75, 76 Boss - SYZ Main and Blocks.asm:756"));

        // --- Assessed: the ROM byte differs, but the balance cannot be reached --------
        ROM_ACT_WID.put("Sonic1EdgeWallObjectInstance", new Entry(px(8), Disposition.RECORDED_UNREACHABLE,
                "44 GHZ Edge Walls.asm:22; sub SolidWall.asm:14-67",
                "Edge_Solid calls EdgeWall_SolidWall, not SolidObject. That routine sets only the "
                        + "pushing bits and a ceiling stop; it never sets Status_OnObj on the player "
                        + "and never records a stood-on object, so Sonic_Balance can never select "
                        + "this object however wide its obActWid is"));
        ROM_ACT_WID.put("Sonic1EggPrisonButtonObjectInstance", new Entry(px(12), Disposition.RECORDED_UNREACHABLE,
                "3E Prison Capsule.asm:34,50,88-109",
                "Pri_Switch calls SolidObject and then, on the same frame obSolid comes back set, "
                        + "clears the player's Status_OnObj (bclr #3,(v_player+obStatus).w), sets the "
                        + "in-air bit, clears obSolid and locks the controls. The standing state never "
                        + "survives into a player frame, so the balance test never sees the switch"));
        ROM_ACT_WID.put("Sonic1SpikeObjectInstance", new Entry(dynamic("Spikes_Config: 20/16/4/28/64/16 by subtype nibble"),
                Disposition.RECORDED_UNREACHABLE, "36 Spikes.asm:22-28,89-121",
                "the two subtypes the player can stand still on are the sideways ones ($1x, $5x), "
                        + "whose Spikes_Config width is #32/2 = 16 and already matches the default. "
                        + "Every subtype with a different width is upright, and on the shipped "
                        + "FixBugs = 0 branch Spikes_Upright reads btst #3,obStatus and branches "
                        + "straight to Spikes_Hurt, so standing on one damages the player every frame "
                        + "instead of idling. The exception is v_invinc, which skips Spikes_Hurt; no "
                        + "level was checked for an invincibility monitor in reach of a wide spike"));
        ROM_ACT_WID.put("Sonic1LavaWallObjectInstance", new Entry(px(80), Disposition.RECORDED_UNREACHABLE,
                "4E MZ Wall of Lava.asm:22-37,77-87; Sonic ReactToItem.asm:14-38,100",
                "LWall_Main seeds a1 with movea.l a0,a1, so the parent's own slot takes the .make "
                        + "block -- both obActWid = #160/2 and obColType = col_128x64|col_hurt. The "
                        + "parent is the stood-on slot (LWall_Solid, routine 2), so standing on it is "
                        + "standing on a hurt object. The boxes overlap rather than merely abut: "
                        + "SolidObject seats the player at objY - d3 - y_radius = objY - 44, "
                        + "ReactToItem gives him a box of objY-60..objY-28 (top edge at y - "
                        + "(height-3), height 2*that), and col_128x64 is a 64x32 extent spanning "
                        + "objY-32..objY+32, so 4px of overlap hurts him every frame. Same "
                        + "v_invinc caveat as the spikes"));
        ROM_ACT_WID.put("Sonic1EggPrisonObjectInstance", new Entry(px(32), Disposition.DECLARES_OWN_WIDTH, "3E Prison Capsule.asm:33,50", "subtype 0, capsule"));
        ROM_ACT_WID.put("FZCylinder", new Entry(dynamic("see note"), Disposition.DECLARES_OWN_WIDTH, "85,84,86 Boss - FZ Main, Cylinders, and Plasma Balls.asm:100",
                "table-driven per cylinder"));
        ROM_ACT_WID.put("Sonic1FZBossInstance", new Entry(dynamic("32 combat / 48 defeat fall"), Disposition.DECLARES_OWN_WIDTH,
                "85,84,86 Boss - FZ Main, Cylinders, and Plasma Balls.asm:56-58,96-101,355-371",
                "BossFinal_ObjData2 row 0 lands in the parent's own slot via movea.l a0,a1. Both "
                        + "sites are Revision conditionals, not FixBugs ones: REV00 writes obWidth. "
                        + "Only the combat 32 is balance-observable -- reaching Eggman_Fall needs the "
                        + "boss defeated, and Sonic_Balance skips a stood-on object whose obStatus "
                        + "bit 7 is set (Sonic ReactToItem.asm:268; 01 Sonic.asm:418-420). The fall "
                        + "value is still the live BuildSprites cull bound"));
        ROM_ACT_WID.put("FZPlasmaLauncher", new Entry(px(0), Disposition.DECLARES_OWN_WIDTH,
                "85,84,86 Boss - FZ Main, Cylinders, and Plasma Balls.asm:990-1001,1022-1027; sub DeleteObject.asm:10-19",
                "zero, and that is the ROM's own omission rather than a gap here: BossPlasma_Main "
                        + "writes obWidth where it meant obActWid -- the same fumble the listing flags "
                        + "for the cylinders at :776-778, except the cylinders were repaired in REV01 "
                        + "and this was not, in either revision. DeleteObject zeroes the slot, so the "
                        + "byte stays 0. At 0 the Sonic_Balance window covers every position, so the "
                        + "ROM balances the whole time the player stands on it"));
        ROM_ACT_WID.put("Sonic1JunctionObjectInstance", new Entry(px(48), Disposition.DECLARES_OWN_WIDTH,
                "66 SBZ Rotating Junction.asm:32,43,47-48,60",
                "parent only. The #112/2 = 56 at :43 belongs to the cover-up children, which Jun_Main "
                        + "puts straight into routine 4 (Jun_Display, display-only) at :32 and never "
                        + "makes solid, so 48 is the only value balance can read. Full-solid, so the "
                        + "byte is supplied at getOnScreenHalfWidth(). Jun_Action d1 is "
                        + "#74/2+sonic_solid_width at :60"));
        ROM_ACT_WID.put("Sonic1SmallDoorObjectInstance", new Entry(px(8), Disposition.DECLARES_OWN_WIDTH,
                "2A SBZ Small Door.asm:20-21,62",
                "half the shared default, so the inherited 16 made the balance window WIDER than the "
                        + "ROM's, not narrower. The class already held the byte as an unused ACT_WIDTH "
                        + "constant. ADoor_Animate d1 is #12/2+sonic_solid_width = $11 at :62"));

        // --- Top-solid, routine passes obActWid straight through as d1 --------------
        ROM_ACT_WID.put("Sonic1PlatformObjectInstance", new Entry(px(32), Disposition.FALLBACK_MATCHES_ROM, "18 Platforms.asm:30,70"));
        ROM_ACT_WID.put("Sonic1CirclingPlatformObjectInstance", new Entry(px(24), Disposition.FALLBACK_MATCHES_ROM, "5A SLZ Circling Platform.asm:28,35"));
        ROM_ACT_WID.put("Sonic1SeesawObjectInstance", new Entry(px(48), Disposition.FALLBACK_MATCHES_ROM, "5E SLZ Seesaw.asm:38"));
        ROM_ACT_WID.put("Sonic1SwingingPlatformObjectInstance", new Entry(dynamic("see note"), Disposition.FALLBACK_MATCHES_ROM, "15 Swinging Platforms.asm:33,42,51,139", "zone-dependent"));
        ROM_ACT_WID.put("Sonic1MovingBlockObjectInstance", new Entry(dynamic("see note"), Disposition.FALLBACK_MATCHES_ROM, "52 Moving Blocks.asm:62,75", "subtype table"));
        ROM_ACT_WID.put("Sonic1VanishingPlatformObjectInstance", new Entry(px(16), Disposition.FALLBACK_MATCHES_ROM, "6C SBZ Vanishing Platforms.asm:28"));
        ROM_ACT_WID.put("Sonic1LZConveyorObjectInstance", new Entry(px(16), Disposition.FALLBACK_MATCHES_ROM, "63 LZ Conveyor.asm:61"));

        // --- ROM obActWid is 16; the shared default is correct ----------------------
        ROM_ACT_WID.put("Sonic1ButtonObjectInstance", new Entry(px(16), Disposition.DEFAULT_IS_ROM_CORRECT, "32 Button.asm:28"));
        ROM_ACT_WID.put("Sonic1BreakableWallObjectInstance", new Entry(px(16), Disposition.DEFAULT_IS_ROM_CORRECT, "3C GHZ, SLZ Smashable Wall.asm:25"));
        ROM_ACT_WID.put("Sonic1MzBrickObjectInstance", new Entry(px(16), Disposition.DEFAULT_IS_ROM_CORRECT, "46 MZ Bricks.asm:26"));
        ROM_ACT_WID.put("Sonic1SmashBlockObjectInstance", new Entry(px(16), Disposition.DEFAULT_IS_ROM_CORRECT, "51 MZ Smashable Green Block.asm:26"));
        ROM_ACT_WID.put("Sonic1SpinConveyorObjectInstance", new Entry(px(16), Disposition.DEFAULT_IS_ROM_CORRECT, "6F SBZ Spin Platform Conveyor.asm:65"));

        // --- Known divergences: cited, unfixed, failing on purpose ------------------
        ROM_ACT_WID.put("Sonic1BridgeObjectInstance", new Entry(px(128),
                Disposition.DECLARES_OWN_WIDTH, "11 GHZ Bridge.asm:37",
                "FixBugs = 0 branch, which the listing itself calls \"way too large\". The player "
                        + "stands on the parent -- the log children are routine $A, display only -- "
                        + "and Bri_CheckOnBridge passes d1 = bridge_children*8+8 at :122-126, a "
                        + "different quantity the engine had inherited as the balance width"));
        ROM_ACT_WID.put("Sonic1FalseFloorInstance",
                new Entry(dynamic("currentHalfWidth -- the remaining half-width d0"),
                        Disposition.DECLARES_OWN_WIDTH,
                        "82, 83 SBZ Eggman Cutscene and Crumbling Floor.asm:265-280",
                        "FFloor_Solid stores d0 into obActWid and only then passes "
                                + "d1 = sonic_solid_width + d0 to SolidObject, so the two differ by "
                                + "exactly $B. The width shrinks as the floor breaks, so the override "
                                + "must track the live field rather than pin a constant"));

        // --- ROM obActWid differs from 16, inherited, reachability never assessed ---
        // These are the ordinary inherit-16 shape from the audit. They are recorded
        // so that none is silently absent, and they do not fail: the audit measured
        // the ROM byte but never established that a grounded, standing-still balance
        // on any of them is reachable, so each is an unconfirmed divergence rather
        // than a known defect. Closing one means reading its reachability first.
        record Deferred(String type, int rom, String cite) { }
        for (Deferred deferred : List.of(
                new Deferred("Sonic1FlappingDoorObjectInstance", 40, "0C LZ Flapping Door.asm:24"))) {
            ROM_ACT_WID.put(deferred.type(), new Entry(deferred.rom() < 0 ? dynamic("see citation") : px(deferred.rom()),
                    Disposition.RECORDED_UNASSESSED, deferred.cite()));
        }
    }

    private static final Path SOURCE_ROOT =
            Path.of("src", "main", "java", "com", "openggf", "game", "sonic1", "objects");

    @Test
    void everySolidObjectProviderAccountsForItsRomActWid() throws IOException {
        List<String> providers = solidObjectProviders();
        assertTrue(providers.size() > 30,
                "expected the S1 SolidObjectProvider scan to find the known population, found "
                        + providers.size() + " -- has the source layout moved?");

        List<String> problems = new ArrayList<>();

        // Constraint: nothing may be silently absent. A class with no entry is the
        // exact gap this guard exists to close, so omission fails rather than skips.
        for (String provider : providers) {
            if (!ROM_ACT_WID.containsKey(provider)) {
                problems.add(provider + ": no ROM obActWid recorded. Read the byte out of the "
                        + "disassembly and add an entry with its file and line; do not read it off the engine.");
            }
        }
        for (String recorded : ROM_ACT_WID.keySet()) {
            if (!providers.contains(recorded)) {
                problems.add(recorded + ": recorded here but no longer a SolidObjectProvider. "
                        + "Remove the entry or fix the scan.");
            }
        }

        for (String provider : providers) {
            Entry entry = ROM_ACT_WID.get(provider);
            if (entry == null) {
                continue;
            }
            boolean declares = declaresOwnWidth(provider);
            switch (entry.disposition()) {
                case DECLARES_OWN_WIDTH -> {
                    if (!declares) {
                        problems.add(provider + ": recorded as supplying ROM obActWid "
                                + describe(entry.romActWid()) + " (" + entry.citation() + ") but declares neither "
                                + "getBalanceWidthPixels() nor getOnScreenHalfWidth().");
                    }
                }
                case DEFAULT_IS_ROM_CORRECT -> {
                    if (!(entry.romActWid() instanceof RomWidth.Fixed fixed)
                            || fixed.pixels() != SHARED_DEFAULT) {
                        problems.add(provider + ": recorded as relying on the shared default, but its "
                                + "ROM obActWid is " + describe(entry.romActWid())
                                + " (" + entry.citation() + ").");
                    }
                }
                case FALLBACK_MATCHES_ROM -> {
                    // The ROM routine passes obActWid to the platform helper unchanged, so the
                    // top-solid fallback resolves to the ROM byte. Recorded, not runtime-verified.
                }
                case RECORDED_UNASSESSED, RECORDED_UNREACHABLE -> {
                    // Recorded, deliberately not failing. See the enum comments.
                }
                case KNOWN_MISMATCH -> problems.add(provider + ": KNOWN, UNFIXED. ROM obActWid "
                        + describe(entry.romActWid()) + " -- " + entry.citation()
                        + (entry.note().isEmpty() ? "" : " -- " + entry.note()));
            }
        }

        if (!problems.isEmpty()) {
            fail("Sonic_Balance reads obActWid off the stood-on object (01 Sonic.asm:423). "
                    + problems.size() + " S1 SolidObjectProvider(s) do not account for theirs:\n  "
                    + String.join("\n  ", new TreeSet<>(problems))
                    + "\n\nNote that on a top-solid object an override at getOnScreenHalfWidth() is a "
                    + "SILENT NO-OP for balance: getBalanceWidthPixels() consults the "
                    + "getSolidParams().halfWidth() fallback first. Only the balance accessor reaches "
                    + "the test. Survey: docs/architecture/audits/"
                    + "2026-08-19-s1-obactwid-balance-width-audit.md");
        }
    }

    private static String describe(RomWidth width) {
        return switch (width) {
            case RomWidth.Fixed fixed -> String.valueOf(fixed.pixels());
            case RomWidth.Dynamic dynamic -> "dynamic (" + dynamic.tracks() + ")";
        };
    }

    private static List<String> solidObjectProviders() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                if (body.contains("implements") && body.contains("SolidObjectProvider")
                        && body.contains("getSolidParams")) {
                    names.add(file.getFileName().toString().replace(".java", ""));
                }
            }
        }
        return names;
    }

    private static boolean declaresOwnWidth(String simpleName) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.getFileName().toString()
                    .equals(simpleName + ".java")).toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                if (body.contains("public int getBalanceWidthPixels()")
                        || body.contains("public int getOnScreenHalfWidth()")) {
                    return true;
                }
            }
        }
        return false;
    }
}
