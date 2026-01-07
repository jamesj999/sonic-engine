package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.audio.GameAudioProfile;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.GameModule;
import uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2AudioProfile;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectConstants;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectIds;
import uk.co.jamesj999.sonic.game.sonic2.objects.Sonic2ObjectRegistry;
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
}
