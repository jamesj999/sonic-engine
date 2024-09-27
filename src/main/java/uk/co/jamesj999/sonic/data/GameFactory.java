package uk.co.jamesj999.sonic.data;

import uk.co.jamesj999.sonic.data.games.Sonic2;
import uk.co.jamesj999.sonic.level.Level;
import java.util.Optional;

public class GameFactory {

    // Private constructor to prevent instantiation
    private GameFactory() {}

    // Factory method to build the appropriate Game object
    public static Optional<Game> build(Rom rom) {
        Game game = new Sonic2(rom);
        if (game.isCompatible()) {
            return Optional.of(game);
        }

        game = new Sonic2(rom);
        if (game.isCompatible()) {
            return Optional.of(game);
        }

        return Optional.empty();
    }
}
