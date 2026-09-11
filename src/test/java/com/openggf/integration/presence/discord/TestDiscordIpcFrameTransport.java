package com.openggf.integration.presence.discord;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestDiscordIpcFrameTransport {

    @Test
    void send_writesLittleEndianHeaderAndJsonPayload() throws IOException {
        CapturingChannel channel = new CapturingChannel();
        DiscordIpcFrameTransport transport = new DiscordIpcFrameTransport(channel);

        transport.send(1, "{\"cmd\":\"PING\"}");

        byte[] bytes = channel.toByteArray();
        ByteBuffer header = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, header.getInt());
        assertEquals(14, header.getInt());
        assertArrayEquals("{\"cmd\":\"PING\"}".getBytes(StandardCharsets.UTF_8),
                java.util.Arrays.copyOfRange(bytes, 8, bytes.length));
    }

    private static final class CapturingChannel implements WritableByteChannel {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private boolean open = true;

        @Override
        public int write(ByteBuffer src) {
            int count = src.remaining();
            byte[] bytes = new byte[count];
            src.get(bytes);
            out.writeBytes(bytes);
            return count;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        private byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
