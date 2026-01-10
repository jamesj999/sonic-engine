package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.audio.GameAudioProfile;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.objects.ObjectRegistry;
import uk.co.jamesj999.sonic.level.objects.PlaneSwitcherConfig;
import uk.co.jamesj999.sonic.level.objects.TouchResponseTable;

public interface GameModule {
    String getIdentifier();

    Game createGame(Rom rom);

    ObjectRegistry createObjectRegistry();

    GameAudioProfile getAudioProfile();

    TouchResponseTable createTouchResponseTable(RomByteReader romReader);

    int getPlaneSwitcherObjectId();

    PlaneSwitcherConfig getPlaneSwitcherConfig();
}
