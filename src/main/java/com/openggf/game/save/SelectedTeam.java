package com.openggf.game.save;

import java.util.List;
import java.util.Objects;

public record SelectedTeam(String mainCharacter, List<String> sidekicks) {
    public SelectedTeam {
        Objects.requireNonNull(mainCharacter, "mainCharacter");
        Objects.requireNonNull(sidekicks, "sidekicks");
        sidekicks = List.copyOf(sidekicks);
    }
}
