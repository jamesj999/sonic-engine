package com.openggf.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Object scaffold generator (Agent Workflow Support Option 4).
 * <p>
 * Generates a <b>guard-friendly</b> object instance skeleton and a focused JUnit 5
 * test shell for new OpenGGF object work. The scaffold deliberately does NOT invent
 * gameplay behavior: it produces the boring integration shell so an implementing
 * agent can spend its attention on ROM parity. The generated header comment states
 * <i>"behavior must be filled in from the disassembly, not guessed."</i>
 * <p>
 * The generated code defaults <b>away</b> from the patterns the architectural guard
 * tests reject:
 * <ul>
 *   <li>No {@code getInstance()} singleton access in object code
 *       (see {@code TestObjectServicesMigrationGuard}).</li>
 *   <li>No {@code services()} calls in the constructor
 *       (see {@code TestNoServicesInObjectConstructors} / {@code TestConstructionContextGuard}).</li>
 *   <li>No direct {@code ObjectManager.addDynamicObject(...)} — uses
 *       {@code spawnChild()} / {@code spawnFreeChild()} instead.</li>
 *   <li>No direct {@code setDestroyed(true)} — points at {@code ObjectLifetimeOps}
 *       and canonical lifecycle profiles.</li>
 * </ul>
 * It also includes a comment/assert about using center coordinates
 * ({@code getCentreX()}/{@code getCentreY()} / {@code NativePositionOps}) for ROM
 * {@code x_pos}/{@code y_pos}, never top-left {@code getX()}/{@code getY()}.
 * <p>
 * <b>Testability:</b> {@link #generateInstance(Game, String, String, boolean)} and
 * {@link #generateTest(Game, String, String, boolean)} are pure functions of
 * {@code (game, className, objectId, isBadnik)} that return a {@link String} with no
 * file I/O. {@code main} is the only part that touches the filesystem, and only when
 * an {@code --out} directory is supplied.
 * <p>
 * Usage:
 * <pre>
 * # Print an S3K badnik skeleton + test shell to stdout:
 * mvn exec:java "-Dexec.mainClass=com.openggf.tools.ObjectScaffoldTool" \
 *     "-Dexec.args=--game s3k --class MhzSpikySpringerObjectInstance --id 0x8A --badnik"
 *
 * # Write both files under a directory (instance + test):
 * mvn exec:java "-Dexec.mainClass=com.openggf.tools.ObjectScaffoldTool" \
 *     "-Dexec.args=--game s3k --class MhzSpikySpringerObjectInstance --id 0x8A --out target/scaffold"
 * </pre>
 * This tool generates source text only; it performs no ROM access and requires no ROM.
 */
public final class ObjectScaffoldTool {

    /** Supported games. Each maps to its object source package root. */
    public enum Game {
        S1("com.openggf.game.sonic1.objects", "Sonic1ObjectIds", "Sonic1ObjectRegistry", "s1disasm"),
        S2("com.openggf.game.sonic2.objects", "Sonic2ObjectIds", "Sonic2ObjectRegistry", "s2disasm"),
        S3K("com.openggf.game.sonic3k.objects", "Sonic3kObjectIds", "Sonic3kObjectRegistry", "skdisasm");

        public final String objectPackage;
        public final String idConstantsClass;
        public final String registryClass;
        public final String disasmDir;

        Game(String objectPackage, String idConstantsClass, String registryClass, String disasmDir) {
            this.objectPackage = objectPackage;
            this.idConstantsClass = idConstantsClass;
            this.registryClass = registryClass;
            this.disasmDir = disasmDir;
        }

        static Game parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("game is required (s1|s2|s3k)");
            }
            return switch (raw.trim().toLowerCase()) {
                case "s1", "sonic1" -> S1;
                case "s2", "sonic2" -> S2;
                case "s3k", "sonic3k" -> S3K;
                default -> throw new IllegalArgumentException("unknown game: " + raw + " (expected s1|s2|s3k)");
            };
        }
    }

    private ObjectScaffoldTool() {
    }

    // ------------------------------------------------------------------
    // Pure template generation (no file I/O — directly unit-testable).
    // ------------------------------------------------------------------

    /**
     * Generate the object instance skeleton source text.
     *
     * @param game      target game (selects package + base classes + disasm hints)
     * @param className the instance class name (e.g. {@code MhzSpikySpringerObjectInstance})
     * @param objectId  the object id as a display string (e.g. {@code 0x8A}); informational only
     * @param isBadnik  when true, extends {@code AbstractBadnikInstance}; otherwise {@code AbstractObjectInstance}
     * @return the full Java source of the instance skeleton (ends with a newline)
     */
    public static String generateInstance(Game game, String className, String objectId, boolean isBadnik) {
        requireValidClassName(className);
        String id = objectId == null ? "(unset)" : objectId.trim();
        boolean s3kBadnik = game == Game.S3K && isBadnik;
        String pkg = resolvePackage(game, isBadnik);
        String baseClass = resolveBaseClass(game, isBadnik);

        StringBuilder b = new StringBuilder();
        b.append("package ").append(pkg).append(";\n\n");

        // Imports.
        b.append("import com.openggf.game.PlayableEntity;\n");
        if (s3kBadnik) {
            // AbstractS3kBadnikInstance is package-private and lives in THIS package
            // (com.openggf.game.sonic3k.objects.badniks), so it needs no import. It already
            // supplies getCollisionSizeIndex(), appendRenderCommands(), touch response, and
            // ROM-accurate badnik destruction; subclasses implement movement + animation only.
            b.append("import com.openggf.level.objects.ObjectSpawn;\n\n");
        } else {
            b.append("import com.openggf.graphics.GLCommand;\n");
            b.append("import com.openggf.level.objects.").append(baseClass).append(";\n");
            b.append("import com.openggf.level.objects.ObjectLifetimeOps;\n");
            b.append("import com.openggf.level.objects.ObjectPlayerQuery;\n");
            b.append("import com.openggf.level.objects.ObjectSpawn;\n");
            b.append("import com.openggf.sprites.NativePositionOps;\n");
            b.append("import com.openggf.sprites.playable.ObjectControlState;\n\n");
            b.append("import java.util.List;\n\n");
        }

        // Header comment.
        b.append("/**\n");
        b.append(" * Object ").append(id).append(" - ").append(className).append(".\n");
        b.append(" * <p>\n");
        b.append(" * SCAFFOLD: behavior must be filled in from the disassembly, not guessed.\n");
        b.append(" * Port the real routine from the ").append(game.disasmDir).append(" disassembly and verify\n");
        b.append(" * every ROM address with RomOffsetFinder before wiring art/mappings/PLCs.\n");
        if (game == Game.S3K) {
            b.append(" * <p>\n");
            b.append(" * S3K: use S&amp;K-side (sonic3k.asm, address &lt; 0x200000) offsets ONLY. Never\n");
            b.append(" * substitute an s3.asm standalone address. Resolve the object name/id through\n");
            b.append(" * the active S3kZoneSet (S3KL zones 0-6, SKL zones 7-13) before assuming a routine.\n");
            b.append(" * Object spaces (locked-on cartridge): S3KL and SKL are the S&amp;K-side object\n");
            b.append(" * listings used at runtime; S3L (Sonic 3 standalone listing, in the s3.asm half)\n");
            b.append(" * is NOT used by the locked-on S3K runtime -- never resolve a routine/address from it.\n");
        }
        b.append(" * <p>\n");
        b.append(" * ROM-only runtime assets: load art, mappings, DPLCs, and PLC data from the\n");
        b.append(" * user-supplied ROM via the engine loaders. Do NOT read asset bytes from docs/.\n");
        b.append(" * <p>\n");
        b.append(" * Register the factory in ").append(game.objectPackage).append('.').append(game.registryClass)
                .append(".registerDefaultFactories():\n");
        b.append(" * <pre>\n");
        b.append(" *   factories.put(").append(game.idConstantsClass).append(".YOUR_ID,\n");
        b.append(" *       (spawn, registry) -&gt; new ").append(className).append("(spawn));\n");
        b.append(" * </pre>\n");
        b.append(" */\n");

        // Class body.
        b.append("public class ").append(className).append(" extends ").append(baseClass).append(" {\n\n");

        if (s3kBadnik) {
            b.append("    public ").append(className).append("(ObjectSpawn spawn) {\n");
            b.append("        // GUARD: the injected service context is NOT bound during construction wiring.\n");
            b.append("        // Read only ObjectSpawn here (TestNoServicesInObjectConstructors / migration guard).\n");
            b.append("        super(spawn, \"").append(className).append("\",\n");
            b.append("                /* rendererKey       */ \"TODO_register_in_Sonic3kObjectArtKeys\",\n");
            b.append("                /* collisionSizeIndex */ 0,  // TODO: ROM collision size index (verify in disasm)\n");
            b.append("                /* priorityBucket     */ 0); // TODO: ROM sprite priority bucket (verify in disasm)\n");
            b.append("        // AbstractS3kBadnikInstance derives facingLeft from render_flags bit 0 already.\n");
            b.append("        // TODO: init subtype state from spawn.subtype() (verify the bit layout in the disassembly).\n");
            b.append("    }\n\n");

            b.append("    @Override\n");
            b.append("    protected void updateMovement(int vIntRunCount, PlayableEntity player) {\n");
            b.append("        // services() is safe here (object is fully constructed and bound).\n");
            b.append("        // CENTER COORDINATES: the ROM stores x_pos/y_pos as CENTER coordinates. For player\n");
            b.append("        // interaction read player.getCentreX()/getCentreY(), NOT getX()/getY() (top-left).\n");
            b.append("        // Reuse inherited subpixel motion: set xVelocity/yVelocity then moveWithVelocity();\n");
            b.append("        // closestNativePlayerByHorizontalDistance(player) picks the nearer of Sonic/Tails.\n");
            b.append("        //\n");
            b.append("        // TODO: port the movement/AI routine from the disassembly.\n");
            b.append("    }\n\n");

            b.append("    @Override\n");
            b.append("    protected void updateAnimation(int vIntRunCount) {\n");
            b.append("        // Set the inherited protected `mappingFrame` field; the base appendRenderCommands\n");
            b.append("        // draws it from the ROM-backed art registered under the rendererKey above.\n");
            b.append("        // TODO: port animation frame stepping from the disassembly.\n");
            b.append("    }\n\n");
        } else if (isBadnik) {
            b.append("    public ").append(className).append("(ObjectSpawn spawn) {\n");
            b.append("        // GUARD: the injected service context is NOT bound during construction wiring.\n");
            b.append("        // Read only ObjectSpawn here (TestNoServicesInObjectConstructors / migration guard).\n");
            b.append("        super(spawn, \"").append(className).append("\");\n");
            b.append("        // TODO: init subtype / facing from spawn.subtype() and spawn.renderFlags()\n");
            b.append("        //       (verify the exact bit layout against the disassembly).\n");
            b.append("    }\n\n");

            b.append("    @Override\n");
            b.append("    protected int getCollisionSizeIndex() {\n");
            b.append("        // TODO: ROM collision size index (category 0x00 ENEMY | size bits). Verify in disasm.\n");
            b.append("        throw new UnsupportedOperationException(\"fill collision size index from disassembly\");\n");
            b.append("    }\n\n");

            b.append("    @Override\n");
            b.append("    protected void updateMovement(int vIntRunCount, PlayableEntity player) {\n");
            b.append("        // services() is safe here (object is fully constructed and bound).\n");
            b.append("        // CENTER COORDINATES: the ROM stores x_pos/y_pos as CENTER coordinates. For player\n");
            b.append("        // interaction read player.getCentreX()/getCentreY(), NOT getX()/getY() (top-left).\n");
            b.append("        // To write a playable sprite's native position use NativePositionOps, e.g.\n");
            b.append("        //   NativePositionOps.writeXPosPreserveSubpixel(sprite, newCentreX);\n");
            b.append("        //\n");
            b.append("        // TODO: port the movement/AI routine from the disassembly.\n");
            b.append("    }\n\n");

            b.append("    @Override\n");
            b.append("    protected void updateAnimation(int vIntRunCount) {\n");
            b.append("        // TODO: port animation frame stepping from the disassembly.\n");
            b.append("    }\n\n");

            b.append("    @Override\n");
            b.append("    public void appendRenderCommands(List<GLCommand> commands) {\n");
            b.append("        // services() is safe here. Resolve the renderer through services().renderManager()\n");
            b.append("        // and draw from ROM-backed art (registered in the game's art/PLC registry).\n");
            b.append("        // TODO: draw the correct mapping frame once art is registered.\n");
            b.append("    }\n\n");
        } else {
            b.append("    public ").append(className).append("(ObjectSpawn spawn) {\n");
            b.append("        // GUARD: the injected service context is NOT bound during construction wiring.\n");
            b.append("        // Read only ObjectSpawn here (TestNoServicesInObjectConstructors / migration guard).\n");
            b.append("        super(spawn, \"").append(className).append("\");\n");
            b.append("        // TODO: init subtype state from spawn.subtype() / spawn.renderFlags() (verify in disasm).\n");
            b.append("    }\n\n");

            b.append("    @Override\n");
            b.append("    public void update(int vIntRunCount, PlayableEntity player) {\n");
            b.append("        // services() is safe here (object is fully constructed and bound).\n");
            b.append("        // CENTER COORDINATES: the ROM stores x_pos/y_pos as CENTER coordinates. For player\n");
            b.append("        // interaction read player.getCentreX()/getCentreY(), NOT getX()/getY() (top-left).\n");
            b.append("        // To write a playable sprite's native position use NativePositionOps, e.g.\n");
            b.append("        //   NativePositionOps.writeYPosPreserveSubpixel(sprite, newCentreY);\n");
            b.append("        //\n");
            b.append("        // Player/sidekick participation: query via ObjectPlayerQuery + a participation\n");
            b.append("        // policy instead of poking sprites directly. Control bits: model with\n");
            b.append("        // ObjectControlState rather than raw status byte arithmetic.\n");
            b.append("        //\n");
            b.append("        // TODO: port the object routine from the disassembly.\n");
            b.append("    }\n\n");

            b.append("    @Override\n");
            b.append("    public void appendRenderCommands(List<GLCommand> commands) {\n");
            b.append("        // services() is safe here. Resolve the renderer through services().renderManager()\n");
            b.append("        // and draw from ROM-backed art (registered in the game's art/PLC registry).\n");
            b.append("        // TODO: draw the correct mapping frame once art is registered.\n");
            b.append("    }\n\n");
        }

        // Child spawning examples (commented — keep the scaffold compiling without invented children).
        b.append("    // Child spawning (uncomment + adapt when the disassembly spawns child objects):\n");
        b.append("    //\n");
        b.append("    //   // FindNextFreeObj semantics (child takes a HIGHER slot than this parent):\n");
        b.append("    //   spawnChild(() -> new SomeChildObjectInstance(buildSpawnAt(getX(), getY())));\n");
        b.append("    //\n");
        b.append("    //   // FindFreeObj semantics (lowest free slot):\n");
        b.append("    //   spawnFreeChild(() -> new SomeChildObjectInstance(buildSpawnAt(getX(), getY())));\n");
        b.append("    //\n");
        b.append("    // Do NOT insert children through the object manager directly (no addDynamicObject\n");
        b.append("    // calls from object code) — always go through spawnChild / spawnFreeChild above.\n\n");

        // Lifecycle guidance.
        b.append("    // Destruction / lifecycle (do NOT call setDestroyed(true) directly):\n");
        b.append("    //   ObjectLifetimeOps.destroyLatched(this);              // permanent (e.g. player kill)\n");
        b.append("    //   ObjectLifetimeOps.destroyRespawnableOffscreen(this); // off-screen, respawnable\n");
        b.append("    //   ObjectLifetimeOps.deleteNoRespawn(this);             // delete, never respawn\n");
        b.append("    // Prefer a canonical ObjectLifecycleProfile\n");
        b.append("    // (com.openggf.game.profiles.objectlifecycle.ObjectLifecycleProfile) over ad-hoc latches.\n");

        b.append("}\n");
        return b.toString();
    }

    /**
     * Generate the focused JUnit 5 test shell source text.
     * <p>
     * The shell intentionally does NOT boot OpenGL or load a ROM. It verifies the
     * object's static identity (construction from an {@link com.openggf.level.objects.ObjectSpawn})
     * and leaves behavior assertions as TODOs for the implementing agent to fill from
     * a {@code HeadlessTestRunner}-style harness.
     *
     * @param game      target game
     * @param className the instance class under test
     * @param objectId  the object id display string (informational)
     * @param isBadnik  whether the object is a badnik (selects the relevant TODO hints)
     * @return the full Java source of the test shell (ends with a newline)
     */
    public static String generateTest(Game game, String className, String objectId, boolean isBadnik) {
        requireValidClassName(className);
        String testName = "Test" + className;
        String id = objectId == null ? "(unset)" : objectId.trim();

        StringBuilder b = new StringBuilder();
        b.append("package ").append(resolvePackage(game, isBadnik)).append(";\n\n");

        // JUnit 5 / Jupiter ONLY — never org.junit.* (JUnit4).
        b.append("import org.junit.jupiter.api.Test;\n");
        b.append("import com.openggf.level.objects.ObjectSpawn;\n\n");
        b.append("import static org.junit.jupiter.api.Assertions.assertEquals;\n");
        b.append("import static org.junit.jupiter.api.Assertions.assertNotNull;\n\n");

        b.append("/**\n");
        b.append(" * Focused test shell for object ").append(id).append(" (").append(className).append(").\n");
        b.append(" * <p>\n");
        b.append(" * SCAFFOLD: behavior must be filled in from the disassembly, not guessed.\n");
        b.append(" * JUnit 5 / Jupiter ONLY — do not add org.junit.* (JUnit4) imports, rules, or runners.\n");
        b.append(" * No ROM and no OpenGL: keep this a pure construction/behavior unit test. For physics\n");
        b.append(" * or collision behavior, drive a HeadlessTestRunner-style harness in a later test.\n");
        b.append(" */\n");
        b.append("class ").append(testName).append(" {\n\n");

        // A minimal spawn factory the shell can use without ROM.
        b.append("    private static ObjectSpawn spawnAt(int x, int y) {\n");
        b.append("        // (x, y, objectId, subtype, renderFlags, respawnTracked, rawYWord, layoutIndex)\n");
        b.append("        return new ObjectSpawn(x, y, 0, 0, 0, false, 0, 0);\n");
        b.append("    }\n\n");

        b.append("    @Test\n");
        b.append("    void constructsFromSpawnWithoutServices() {\n");
        b.append("        // GUARD: the constructor must not call services() (TestNoServicesInObjectConstructors).\n");
        b.append("        ").append(className).append(" obj = new ").append(className).append("(spawnAt(0x100, 0x200));\n");
        b.append("        assertNotNull(obj, \"object should construct from an ObjectSpawn alone\");\n");
        b.append("    }\n\n");

        b.append("    @Test\n");
        b.append("    void preservesSpawnPosition() {\n");
        b.append("        ").append(className).append(" obj = new ").append(className).append("(spawnAt(0x140, 0x240));\n");
        b.append("        // getX()/getY() are TOP-LEFT render coords; ROM x_pos/y_pos are CENTER coords.\n");
        b.append("        // Assert against the convention the implementation actually exposes.\n");
        b.append("        assertEquals(0x140, obj.getX(), \"spawn x should be preserved\");\n");
        b.append("        assertEquals(0x240, obj.getY(), \"spawn y should be preserved\");\n");
        b.append("    }\n\n");

        b.append("    // TODO: add focused behavior tests once the routine is ported from the disassembly.\n");
        if (isBadnik) {
            b.append("    //   - movement/AI: step frames and assert ROM-accurate position deltas.\n");
            b.append("    //   - collision size index matches the disassembly.\n");
            b.append("    //   - touch response / destruction via ObjectLifetimeOps (never setDestroyed(true)).\n");
        } else {
            b.append("    //   - per-frame routine effects (solid contact, control bits, player interaction).\n");
            b.append("    //   - lifecycle via ObjectLifetimeOps (never setDestroyed(true)).\n");
        }
        b.append("}\n");
        return b.toString();
    }

    private static void requireValidClassName(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("className is required");
        }
        if (!className.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("invalid Java class name: " + className);
        }
    }

    /**
     * Source package for the generated class. S3K badniks live in the dedicated
     * {@code com.openggf.game.sonic3k.objects.badniks} package next to
     * {@code AbstractS3kBadnikInstance} (which is package-private); everything else
     * lives in the game's top-level objects package.
     */
    static String resolvePackage(Game game, boolean isBadnik) {
        if (game == Game.S3K && isBadnik) {
            return "com.openggf.game.sonic3k.objects.badniks";
        }
        return game.objectPackage;
    }

    /**
     * Base class for the generated object. S3K badniks extend the S3K-specific
     * {@code AbstractS3kBadnikInstance} (render-flag facing, S3K destruction config,
     * subpixel motion helpers); S1/S2 badniks extend the generic
     * {@code AbstractBadnikInstance}; non-badniks extend {@code AbstractObjectInstance}.
     */
    static String resolveBaseClass(Game game, boolean isBadnik) {
        if (game == Game.S3K && isBadnik) {
            return "AbstractS3kBadnikInstance";
        }
        return isBadnik ? "AbstractBadnikInstance" : "AbstractObjectInstance";
    }

    // ------------------------------------------------------------------
    // CLI entry point (the only part that touches the filesystem).
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        String gameRaw = null;
        String className = null;
        String objectId = null;
        boolean isBadnik = false;
        String outDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> gameRaw = arg(args, ++i, "--game");
                case "--class" -> className = arg(args, ++i, "--class");
                case "--id" -> objectId = arg(args, ++i, "--id");
                case "--badnik" -> isBadnik = true;
                case "--out" -> outDir = arg(args, ++i, "--out");
                case "--help", "-h" -> {
                    printUsage(System.out::println);
                    return;
                }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage(System.err::println);
                    System.exit(2);
                    return;
                }
            }
        }

        if (gameRaw == null || className == null) {
            System.err.println("Missing required arguments: --game and --class are mandatory.");
            printUsage(System.err::println);
            System.exit(2);
            return;
        }

        Game game;
        try {
            game = Game.parse(gameRaw);
            requireValidClassName(className);
        } catch (IllegalArgumentException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.exit(2);
            return;
        }

        String instanceSource = generateInstance(game, className, objectId, isBadnik);
        String testSource = generateTest(game, className, objectId, isBadnik);
        String pkg = resolvePackage(game, isBadnik);

        if (outDir == null) {
            System.out.println("// ===== " + className + ".java (" + pkg + ") =====");
            System.out.println(instanceSource);
            System.out.println("// ===== Test" + className + ".java (" + pkg + ") =====");
            System.out.println(testSource);
            return;
        }

        try {
            Path dir = Path.of(outDir);
            Files.createDirectories(dir);
            Path instancePath = dir.resolve(className + ".java");
            Path testPath = dir.resolve("Test" + className + ".java");
            Files.writeString(instancePath, instanceSource, StandardCharsets.UTF_8);
            Files.writeString(testPath, testSource, StandardCharsets.UTF_8);
            System.out.println("Wrote instance skeleton: " + instancePath.toAbsolutePath());
            System.out.println("Wrote test shell:        " + testPath.toAbsolutePath());
            System.out.println();
            System.out.println("Next: move them into src/main/.../"
                    + pkg.replace('.', '/') + "/ and src/test/.../"
                    + pkg.replace('.', '/') + "/, register the factory in "
                    + game.registryClass + ", then fill behavior from the disassembly.");
        } catch (IOException ex) {
            System.err.println("Failed to write scaffold: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static String arg(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[i];
    }

    private static void printUsage(java.util.function.Consumer<String> out) {
        out.accept("ObjectScaffoldTool — generate a guard-friendly object skeleton + test shell.");
        out.accept("");
        out.accept("Usage:");
        out.accept("  --game <s1|s2|s3k>   (required) target game");
        out.accept("  --class <ClassName>  (required) instance class name, e.g. MhzSpikySpringerObjectInstance");
        out.accept("  --id <0xNN>          (optional) object id for the generated comments");
        out.accept("  --badnik             (optional) extend AbstractBadnikInstance instead of AbstractObjectInstance");
        out.accept("  --out <dir>          (optional) write files to <dir>; without it, prints to stdout");
        out.accept("");
        out.accept("Generated code defaults away from getInstance(), constructor-time services(),");
        out.accept("ObjectManager.addDynamicObject(...), and setDestroyed(true). Behavior must be");
        out.accept("filled in from the disassembly, not guessed.");
    }
}
