package com.openggf.game;

import com.openggf.configuration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestMasterTitlePreviewInvalidation {
    @TempDir Path root;

    @Test void unrelatedApplyRetainsDecodedPreviewAndOnlyChangedRomIsRetried() throws Exception {
        var rom = root.resolve("game.gen");
        Files.write(rom, new byte[]{1});
        var config = SonicConfigurationService.createStandalone(root);
        config.setConfigValue(SonicConfiguration.SONIC_1_ROM, rom.toString());
        config.setConfigValue(SonicConfiguration.SONIC_2_ROM, root.resolve("missing2").toString());
        config.setConfigValue(SonicConfiguration.SONIC_3K_ROM, root.resolve("missing3").toString());
        var screen = new MasterTitleScreen(config);
        var field = MasterTitleScreen.class.getDeclaredField("renderer");
        field.setAccessible(true);
        field.set(screen, mock(com.openggf.graphics.TexturedQuadRenderer.class));
        var refresh = MasterTitleScreen.class.getDeclaredMethod("refreshRomPreviews");
        refresh.setAccessible(true);
        try (var decoder = mockStatic(MasterTitleRomPreview.class)) {
            decoder.when(() -> MasterTitleRomPreview.loadSequenceFor(MasterTitleScreen.GameEntry.SONIC_1, rom))
                    .thenReturn(Optional.empty());
            refresh.invoke(screen);
            refresh.invoke(screen);
            decoder.verify(() -> MasterTitleRomPreview.loadSequenceFor(MasterTitleScreen.GameEntry.SONIC_1, rom), times(1));
            Files.write(rom, new byte[]{1, 2});
            refresh.invoke(screen);
            decoder.verify(() -> MasterTitleRomPreview.loadSequenceFor(MasterTitleScreen.GameEntry.SONIC_1, rom), times(2));
            Files.delete(rom);
            refresh.invoke(screen);
            decoder.verifyNoMoreInteractions();
        }
    }
}
