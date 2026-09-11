package com.openggf.integration.presence.discord;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DiscordIpcTransports {
    private DiscordIpcTransports() {
    }

    public static DiscordIpcTransportFactory defaultFactory() {
        return DiscordIpcTransports::openFirstAvailable;
    }

    private static DiscordIpcTransport openFirstAvailable() throws IOException {
        List<IOException> failures = new ArrayList<>();
        for (Path candidate : candidatePaths()) {
            try {
                return open(candidate);
            } catch (IOException e) {
                failures.add(e);
            }
        }
        IOException failure = new IOException("Discord IPC pipe not available.");
        for (IOException suppressed : failures) {
            failure.addSuppressed(suppressed);
        }
        throw failure;
    }

    private static DiscordIpcTransport open(Path path) throws IOException {
        if (isWindows()) {
            return new DiscordIpcFrameTransport(FileChannel.open(path,
                    StandardOpenOption.READ, StandardOpenOption.WRITE));
        }
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(path));
            return new DiscordIpcFrameTransport(channel);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    private static List<Path> candidatePaths() {
        List<Path> paths = new ArrayList<>();
        if (isWindows()) {
            for (int i = 0; i < 10; i++) {
                paths.add(Path.of("\\\\.\\pipe\\discord-ipc-" + i));
            }
            return paths;
        }

        Set<String> roots = new LinkedHashSet<>();
        addEnvRoot(roots, "XDG_RUNTIME_DIR");
        addEnvRoot(roots, "TMPDIR");
        addEnvRoot(roots, "TMP");
        addEnvRoot(roots, "TEMP");
        roots.add("/tmp");
        for (String root : roots) {
            for (int i = 0; i < 10; i++) {
                paths.add(Path.of(root, "discord-ipc-" + i));
            }
        }
        return paths;
    }

    private static void addEnvRoot(Set<String> roots, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            roots.add(value);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
