package com.openggf.integration.presence.discord;

import java.io.IOException;

public interface DiscordIpcTransport extends AutoCloseable {
    void send(int opcode, String json) throws IOException;

    @Override
    void close() throws IOException;
}
