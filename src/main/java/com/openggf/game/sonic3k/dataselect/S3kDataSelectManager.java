package com.openggf.game.sonic3k.dataselect;


import com.openggf.game.session.EngineServices;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.dataselect.DataSelectSessionController;
import com.openggf.game.save.SaveManager;

import java.nio.file.Path;
import java.util.function.IntConsumer;

public final class S3kDataSelectManager extends S3kDataSelectPresentation {
    public S3kDataSelectManager() {
        this(new DataSelectSessionController(new S3kDataSelectProfile()),
                Path.of("saves"),
                EngineServices.current().configuration());
    }

    public S3kDataSelectManager(Path saveRoot, SonicConfigurationService config) {
        this(new DataSelectSessionController(new S3kDataSelectProfile()), saveRoot, config);
    }

    public S3kDataSelectManager(DataSelectSessionController controller) {
        this(controller, Path.of("saves"), EngineServices.current().configuration());
    }

    /**
     * Native S3K uses the primary ROM; a donated host (S1/S2) needs the
     * S3K ROM opened as a secondary ROM since the primary is the host game.
     */
    private static S3kDataSelectAssetSource resolveAssets(DataSelectSessionController controller) {
        if (!"s3k".equals(controller.hostProfile().gameCode())) {
            return createDonorAssets(controller.hostProfile());
        }
        return createDefaultAssets();
    }

    public S3kDataSelectManager(DataSelectSessionController controller,
                                Path saveRoot, SonicConfigurationService config) {
        this(controller,
                saveRoot,
                config,
                resolveAssets(controller),
                new S3kDataSelectRenderer(),
                resolveMusicPlayer(controller),
                resolveMenuSfxPlayer(controller));
    }

    S3kDataSelectManager(DataSelectSessionController controller,
                         Path saveRoot,
                         SonicConfigurationService config,
                         S3kDataSelectAssetSource assets,
                         S3kDataSelectRenderer renderer,
                         IntConsumer musicPlayer) {
        this(controller, saveRoot, config, assets, renderer, musicPlayer, S3kDataSelectPresentation::playMovementSafely);
    }

    S3kDataSelectManager(DataSelectSessionController controller,
                         Path saveRoot,
                         SonicConfigurationService config,
                         S3kDataSelectAssetSource assets,
                         S3kDataSelectRenderer renderer,
                         IntConsumer musicPlayer,
                         IntConsumer menuSfxPlayer) {
        super(controller,
                new SaveManager(saveRoot),
                config,
                assets,
                renderer,
                musicPlayer,
                new S3kSaveScreenSelectorState(menuSfxPlayer),
                menuSfxPlayer);
    }

    static IntConsumer resolveMusicPlayer(DataSelectSessionController controller) {
        return isDonatedHost(controller)
                ? S3kDataSelectManager::playDonorMusicSafely
                : S3kDataSelectPresentation::playMusicSafely;
    }

    static IntConsumer resolveMenuSfxPlayer(DataSelectSessionController controller) {
        return isDonatedHost(controller)
                ? S3kDataSelectManager::playDonorSfxSafely
                : S3kDataSelectPresentation::playMovementSafely;
    }

    private static boolean isDonatedHost(DataSelectSessionController controller) {
        return controller != null && !"s3k".equals(controller.hostProfile().gameCode());
    }

    private static void playDonorMusicSafely(int musicId) {
        try {
            if (GameServices.audio() != null) {
                GameServices.audio().playDonorMusic("s3k", musicId);
            }
        } catch (Exception ignored) {
        }
    }

    private static void playDonorSfxSafely(int sfxId) {
        try {
            if (GameServices.audio() != null) {
                GameServices.audio().playDonorSfx("s3k", sfxId);
            }
        } catch (Exception ignored) {
        }
    }
}
