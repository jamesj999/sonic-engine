package com.openggf.integration.presence;

import java.io.IOException;

public interface PresenceClient extends AutoCloseable {
    void connect() throws IOException;

    void update(PresencePayload payload) throws IOException;

    void clear() throws IOException;

    @Override
    void close() throws IOException;
}
