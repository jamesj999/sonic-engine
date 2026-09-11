package com.openggf.integration.presence.discord;

import java.io.IOException;

@FunctionalInterface
public interface DiscordIpcTransportFactory {
    DiscordIpcTransport open() throws IOException;
}
