package com.openggf.integration.presence.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.integration.presence.PresenceClient;
import com.openggf.integration.presence.PresencePayload;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

public final class DiscordIpcPresenceClient implements PresenceClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int OPCODE_HANDSHAKE = 0;
    private static final int OPCODE_FRAME = 1;

    private final DiscordIpcTransportFactory transportFactory;
    private final long activityStartEpochSeconds;
    private final Object transportLock = new Object();
    private volatile DiscordIpcTransport transport;
    private boolean closed;

    public DiscordIpcPresenceClient(DiscordIpcTransportFactory transportFactory) {
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
        this.activityStartEpochSeconds = System.currentTimeMillis() / 1000L;
    }

    @Override
    public void connect() throws IOException {
        synchronized (transportLock) {
            if (closed) {
                throw new IOException("Discord IPC client is closed.");
            }
            if (transport != null) {
                return;
            }
        }
        DiscordIpcTransport opened = transportFactory.open();
        boolean discard;
        boolean rejectBecauseClosed;
        synchronized (transportLock) {
            rejectBecauseClosed = closed;
            discard = rejectBecauseClosed || transport != null;
            if (!discard) {
                transport = opened;
            }
        }
        if (discard) {
            closeQuietly(opened);
            if (rejectBecauseClosed) {
                throw new IOException("Discord IPC client is closed.");
            }
            return;
        }
        ObjectNode handshake = MAPPER.createObjectNode();
        handshake.put("v", 1);
        handshake.put("client_id", DiscordPresenceConstants.APPLICATION_ID);
        try {
            opened.send(OPCODE_HANDSHAKE, MAPPER.writeValueAsString(handshake));
        } catch (IOException failure) {
            boolean ownedTransport;
            synchronized (transportLock) {
                ownedTransport = transport == opened;
                if (ownedTransport) {
                    transport = null;
                }
            }
            if (ownedTransport) {
                closeQuietly(opened);
            }
            throw failure;
        }
    }

    @Override
    public void update(PresencePayload payload) throws IOException {
        DiscordIpcTransport current = ensureConnected();
        current.send(OPCODE_FRAME, MAPPER.writeValueAsString(setActivity(payload)));
    }

    @Override
    public void clear() throws IOException {
        DiscordIpcTransport current = ensureConnected();
        current.send(OPCODE_FRAME, MAPPER.writeValueAsString(setActivity(null)));
    }

    @Override
    public void close() throws IOException {
        DiscordIpcTransport current;
        synchronized (transportLock) {
            closed = true;
            current = transport;
            transport = null;
        }
        if (current != null) {
            current.close();
        }
    }

    private static void closeQuietly(DiscordIpcTransport transport) {
        try {
            transport.close();
        } catch (IOException ignored) {
            // The original connect failure is the useful diagnostic.
        }
    }

    private DiscordIpcTransport ensureConnected() throws IOException {
        DiscordIpcTransport current = transport;
        if (current == null) {
            connect();
            current = transport;
        }
        if (current == null) {
            throw new IOException("Discord IPC client is not connected.");
        }
        return current;
    }

    private ObjectNode setActivity(PresencePayload payload) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("cmd", "SET_ACTIVITY");
        ObjectNode args = root.putObject("args");
        args.put("pid", ProcessHandle.current().pid());
        if (payload == null) {
            args.set("activity", MAPPER.nullNode());
        } else {
            ObjectNode activity = args.putObject("activity");
            activity.put("details", payload.details());
            if (payload.state() != null && !payload.state().isBlank()) {
                activity.put("state", payload.state());
            }
            activity.putObject("timestamps").put("start", activityStartEpochSeconds);
        }
        root.put("nonce", UUID.randomUUID().toString());
        return root;
    }
}
