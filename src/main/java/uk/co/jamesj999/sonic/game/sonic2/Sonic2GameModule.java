package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.audio.GameAudioProfile;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.GameModule;
import uk.co.jamesj999.sonic.game.LevelEventProvider;
import uk.co.jamesj999.sonic.game.LevelState;
import uk.co.jamesj999.sonic.game.RespawnState;
import uk.co.jamesj999.sonic.game.TitleCardProvider;
import uk.co.jamesj999.sonic.game.ZoneRegistry;
import uk.co.jamesj999.sonic.game.SpecialStageProvider;
import uk.co.jamesj999.sonic.game.BonusStageProvider;
import uk.co.jamesj999.sonic.game.ScrollHandlerProvider;
import uk.co.jamesj999.sonic.game.ZoneFeatureProvider;
import uk.co.jamesj999.sonic.game.RomOffsetProvider;
import uk.co.jamesj999.sonic.game.DebugModeProvider;
import uk.co.jamesj999.sonic.game.DebugOverlayProvider;
import uk.co.jamesj999.sonic.game.ZoneArtProvider;
import uk.co.jamesj999.sonic.game.sonic2.debug.Sonic2DebugModeProvider;
import uk.co.jamesj999.sonic.game.sonic2.scroll.Sonic2ScrollHandlerProvider;
import uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2AudioProfile;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectConstants;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectIds;
import uk.co.jamesj999.sonic.game.sonic2.objects.Sonic2ObjectRegistry;
import uk.co.jamesj999.sonic.game.sonic2.titlecard.TitleCardManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRegistry;
import uk.co.jamesj999.sonic.level.objects.PlaneSwitcherConfig;
import uk.co.jamesj999.sonic.level.objects.TouchResponseTable;

public class Sonic2GameModule implements GameModule {
    private final GameAudioProfile audioProfile = new Sonic2AudioProfile();

    @Override
    public String getIdentifier() {
        return "Sonic2";
    }

    @Override
    public Game createGame(Rom rom) {
        return new Sonic2(rom);
    }

    @Override
    public ObjectRegistry createObjectRegistry() {
        return Sonic2ObjectRegistry.getInstance();
    }

    @Override
    public GameAudioProfile getAudioProfile() {
        return audioProfile;
    }

    @Override
    public TouchResponseTable createTouchResponseTable(RomByteReader romReader) {
        return new TouchResponseTable(romReader,
                Sonic2ObjectConstants.TOUCH_SIZES_ADDR,
                Sonic2ObjectConstants.TOUCH_ENTRY_COUNT);
    }

    @Override
    public int getPlaneSwitcherObjectId() {
        return Sonic2ObjectIds.LAYER_SWITCHER;
    }

    @Override
    public PlaneSwitcherConfig getPlaneSwitcherConfig() {
        return new PlaneSwitcherConfig(
                Sonic2ObjectConstants.PATH0_TOP_SOLID_BIT,
                Sonic2ObjectConstants.PATH0_LRB_SOLID_BIT,
                Sonic2ObjectConstants.PATH1_TOP_SOLID_BIT,
                Sonic2ObjectConstants.PATH1_LRB_SOLID_BIT);
    }

    @Override
    public LevelEventProvider getLevelEventProvider() {
        return LevelEventManager.getInstance();
    }

    @Override
    public RespawnState createRespawnState() {
        return new CheckpointState();
    }

    @Override
    public LevelState createLevelState() {
        return new LevelGamestate();
    }

    @Override
    public TitleCardProvider getTitleCardProvider() {
        return TitleCardManager.getInstance();
    }

    @Override
    public ZoneRegistry getZoneRegistry() {
        return Sonic2ZoneRegistry.getInstance();
    }

    @Override
    public SpecialStageProvider getSpecialStageProvider() {
        // Return a new provider each time to avoid state issues
        // The underlying manager is a singleton
        return new Sonic2SpecialStageProvider();
    }

    @Override
    public BonusStageProvider getBonusStageProvider() {
        // Sonic 2 does not have bonus stages (uses special stages via checkpoints instead)
        return null;
    }

    @Override
    public ScrollHandlerProvider getScrollHandlerProvider() {
        return new Sonic2ScrollHandlerProvider();
    }

    @Override
    public ZoneFeatureProvider getZoneFeatureProvider() {
        return new Sonic2ZoneFeatureProvider();
    }

    @Override
    public RomOffsetProvider getRomOffsetProvider() {
        return new Sonic2RomOffsetProvider();
    }

    @Override
    public DebugModeProvider getDebugModeProvider() {
        return new Sonic2DebugModeProvider();
    }

    @Override
    public DebugOverlayProvider getDebugOverlayProvider() {
        // Debug overlay content is currently handled by the generic DebugRenderer
        // Future: Create Sonic2DebugOverlayProvider for game-specific overlay content
        return null;
    }

    @Override
    public ZoneArtProvider getZoneArtProvider() {
        return new Sonic2ZoneArtProvider();
    }
}
