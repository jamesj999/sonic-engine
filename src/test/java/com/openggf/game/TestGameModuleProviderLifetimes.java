package com.openggf.game;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.level.objects.ObjectRegistry;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestGameModuleProviderLifetimes {

    @BeforeAll
    static void configureEngineServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void clearSession() {
        SessionManager.clear();
    }

    @Test
    public void sonic1ObjectArtProvider_NotCachedAcrossCalls() {
        Sonic1GameModule module = new Sonic1GameModule();

        ObjectArtProvider first = module.getObjectArtProvider();
        ObjectArtProvider second = module.getObjectArtProvider();

        assertNotSame(first, second, "Sonic 1 object art provider should be recreated per call");
    }

    @Test
    public void sonic2ObjectRegistry_CachedAcrossCalls() {
        Sonic2GameModule module = new Sonic2GameModule();

        ObjectRegistry first = module.createObjectRegistry();
        ObjectRegistry second = module.createObjectRegistry();

        assertSame(first, second, "Sonic 2 object registry should be reused within a module instance");
    }

    @Test
    public void sonic1ObjectRegistry_CachedAcrossCalls() {
        Sonic1GameModule module = new Sonic1GameModule();

        ObjectRegistry first = module.createObjectRegistry();
        ObjectRegistry second = module.createObjectRegistry();

        assertSame(first, second, "Sonic 1 object registry should be reused within a module instance");
    }

    @Test
    public void sonic3kObjectRegistry_CachedAcrossCalls() {
        Sonic3kGameModule module = new Sonic3kGameModule();

        ObjectRegistry first = module.createObjectRegistry();
        ObjectRegistry second = module.createObjectRegistry();

        assertSame(first, second, "Sonic 3K object registry should be reused within a module instance");
    }
}


