package com.openggf.tests;

import com.openggf.game.session.EngineServices;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.GameplayTeamBootstrap;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.IOException;

/**
 * Encapsulates the {@code @BeforeAll} level loading pattern used across
 * headless test classes. Loads a level once and stores the result for reuse.
 * <p>
 * Usage:
 * <pre>
 * {@literal @}BeforeClass
 * public static void loadLevel() throws Exception {
 *     shared = SharedLevel.load(SonicGame.SONIC_2, 0, 0);
 * }
 *
 * {@literal @}AfterClass
 * public static void cleanup() {
 *     if (shared != null) shared.dispose();
 * }
 * </pre>
 */
public final class SharedLevel {

    private final Level level;
    private final SonicGame game;
    private final int zone;
    private final int act;
    private final boolean skipIntros;
    private final String mainCharCode;
    private final String sidekickCharCode;
    private final String loadTimeSimulation;

    private SharedLevel(Level level, SonicGame game, int zone, int act,
                        boolean skipIntros, String mainCharCode,
                        String sidekickCharCode, String loadTimeSimulation) {
        this.level = level;
        this.game = game;
        this.zone = zone;
        this.act = act;
        this.skipIntros = skipIntros;
        this.mainCharCode = mainCharCode;
        this.sidekickCharCode = sidekickCharCode;
        this.loadTimeSimulation = loadTimeSimulation;
    }

    /**
     * Loads a level in headless mode and returns a {@code SharedLevel} holding
     * the loaded data.
     * <p>
     * This replicates the {@code @BeforeAll} boilerplate found in
     * {@code TestS2Ehz1Headless}, {@code TestHeadlessCNZ1LiftWallStick}, etc.:
     * <ol>
     *   <li>Init headless graphics</li>
     *   <li>Create the active gameplay team and register it</li>
     *   <li>Set camera focus and unfreeze</li>
     *   <li>Load the requested zone and act (runs full production path
     *       including camera bounds, level events, and player positioning
     *       via profile steps)</li>
     * </ol>
     *
     * @param game the game whose ROM is loaded (for documentation/querying)
     * @param zone zone index (e.g. 0 = EHZ for Sonic 2)
     * @param act  act index (0-based)
     * @return a new {@code SharedLevel} with the loaded level data
     */
    public static SharedLevel load(SonicGame game, int zone, int act) throws IOException {
        bootstrapRuntimeForSharedLevel(game);
        GraphicsManager.getInstance().initHeadless();

        SonicConfigurationService cs = SonicConfigurationService.getInstance();
        boolean skipIntros = cs.getBoolean(SonicConfiguration.S3K_SKIP_INTROS);
        String mainCharCode = cs.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        String sidekickCharCode = cs.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        String loadTimeSimulation = cs.getString(SonicConfiguration.LOAD_TIME_SIMULATION);

        AbstractPlayableSprite player = GameplayTeamBootstrap.registerActiveTeam(
                GameServices.module(), GameServices.sprites(), cs).mainSprite();
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(player);
        camera.setFrozen(false);

        LevelManager lm = GameServices.level();
        lm.loadZoneAndAct(zone, act);

        // loadZoneAndAct â†’ loadCurrentLevel now runs full profile including
        // InitCamera (bounds, snap, vertical wrap) and InitLevelEvents.
        // No manual camera setup needed.

        return new SharedLevel(
                lm.getCurrentLevel(), game, zone, act,
                skipIntros, mainCharCode, sidekickCharCode, loadTimeSimulation);
    }

    private static void bootstrapRuntimeForSharedLevel(SonicGame game) throws IOException {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

        File romFile = switch (game) {
            case SONIC_1 -> RomTestUtils.ensureSonic1RomAvailable();
            case SONIC_2 -> RomTestUtils.ensureSonic2RomAvailable();
            case SONIC_3K -> RomTestUtils.ensureSonic3kRomAvailable();
        };
        // A missing ROM is an environment condition, not a defect: skip the
        // calling test (with a reason) the same way @RequiresRom does, so a
        // ROM-less runner reports skips rather than errors. Release runs pass
        // the ROM properties explicitly and classify any remaining skips.
        Assumptions.assumeTrue(romFile != null,
                game.getDisplayName() + " ROM not available — skipping shared level fixture");

        Rom rom = new Rom();
        String romPath = romFile.getAbsolutePath();
        if (!rom.open(romPath)) {
            throw new IOException("Failed to open ROM for shared level fixture: " + romPath);
        }

        RomManager.getInstance().setRom(rom);
        GameModuleRegistry.detectAndSetModule(rom);

        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
    }

    /**
     * Cleans up state after the shared level is no longer needed.
     * Delegates to {@link TestEnvironment#resetAll()} for complete cleanup.
     */
    public void dispose() {
        TestEnvironment.resetAll();
    }

    /** Returns the loaded level, or {@code null} if loading failed. */
    public Level level() {
        return level;
    }

    /** Returns the game type that was used to load this level. */
    public SonicGame game() {
        return game;
    }

    /** Returns the zone index. */
    public int zone() {
        return zone;
    }

    /** Returns the act index. */
    public int act() {
        return act;
    }

    /** Returns the main character code used to create the player sprite. */
    public String mainCharCode() {
        return mainCharCode;
    }

    /** Returns whether S3K intro skip was enabled when the shared level was loaded. */
    public boolean skipIntros() {
        return skipIntros;
    }

    /** Returns the sidekick character configuration captured at load time. */
    /** The normal-play load-time mode the level was loaded under; re-applied per test. */
    public String loadTimeSimulation() {
        return loadTimeSimulation;
    }

    public String sidekickCharCode() {
        return sidekickCharCode;
    }
}


