package com.openggf.integration.presence.discord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class DiscordIpcFrameTransport implements DiscordIpcTransport {
    private final WritableByteChannel channel;

    DiscordIpcFrameTransport(WritableByteChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    @Override
    public void send(int opcode, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(opcode);
        header.putInt(payload.length);
        header.flip();
        writeFully(header);
        writeFully(ByteBuffer.wrap(payload));
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private void writeFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
