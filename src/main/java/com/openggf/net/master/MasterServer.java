package com.openggf.net.master;

import com.openggf.net.host.ConnectionHygiene;
import com.openggf.net.hub.BundledProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.Protocol;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone TLS master/browser server with master-owned relay rooms. */
public final class MasterServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(MasterServer.class.getName());
    private static final int MAX_CONNECTIONS_PER_IP = 4;

    private final MasterConfig config;
    private final Path dataDir;
    private final NioEventLoopGroup brokerGroup;
    private final NioEventLoopGroup relayGroup;
    private final Channel serverChannel;
    private final ChannelGroup clientChannels;
    private final List<ScheduledFuture<?>> scheduledTasks;
    private final SqliteIdentityStore store;
    private final TrustLadder ladder;
    private final SessionRegistry registry;
    private final GuestTunnelRouter tunnels;
    private final RelayRoomManager relays;
    private final RoomBroker broker;
    private final RecordingBlobStore recordingBlobs;
    private final VerifierRegistry verifiers;
    private final VerificationJobQueue verificationJobs;
    private final VerdictConsequences verdictConsequences;
    private final AtomicBoolean closed = new AtomicBoolean();
    private AdminEndpoint admin;

    private MasterServer(MasterConfig config, Path dataDir,
                         NioEventLoopGroup brokerGroup, NioEventLoopGroup relayGroup,
                         Channel serverChannel, ChannelGroup clientChannels,
                         List<ScheduledFuture<?>> scheduledTasks,
                         SqliteIdentityStore store,
                         TrustLadder ladder, SessionRegistry registry,
                         GuestTunnelRouter tunnels, RelayRoomManager relays,
                         RoomBroker broker, RecordingBlobStore recordingBlobs,
                         VerifierRegistry verifiers,
                         VerificationJobQueue verificationJobs,
                         VerdictConsequences verdictConsequences) {
        this.config = config;
        this.dataDir = dataDir;
        this.brokerGroup = brokerGroup;
        this.relayGroup = relayGroup;
        this.serverChannel = serverChannel;
        this.clientChannels = clientChannels;
        this.scheduledTasks = scheduledTasks;
        this.store = store;
        this.ladder = ladder;
        this.registry = registry;
        this.tunnels = tunnels;
        this.relays = relays;
        this.broker = broker;
        this.recordingBlobs = recordingBlobs;
        this.verifiers = verifiers;
        this.verificationJobs = verificationJobs;
        this.verdictConsequences = verdictConsequences;
    }

    public static MasterServer start(MasterConfig config, Path dataDir) throws Exception {
        return start(config, dataDir, System::currentTimeMillis);
    }

    static MasterServer start(MasterConfig config, Path dataDir,
                              java.util.function.LongSupplier clock) throws Exception {
        Files.createDirectories(dataDir);
        NioEventLoopGroup brokerGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup relayGroup = new NioEventLoopGroup(
                Math.max(1, Runtime.getRuntime().availableProcessors()));
        SqliteIdentityStore store = null;
        Channel channel = null;
        ChannelGroup clientChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        try {
            store = new SqliteIdentityStore(dataDir.resolve(config.dbPath()));
            NewIdentityCache cache = new NewIdentityCache(config.newIdentityCacheSize(),
                    config.newIdentityCacheTtlMinutes() * 60_000L, clock);
            TrustLadder ladder = new TrustLadder(store, cache, config.thresholds(), clock);
            SessionRegistry registry = new SessionRegistry(clock, config);
            GuestTunnelRouter tunnels = new GuestTunnelRouter();
            List<Executor> roomLoops = new ArrayList<>();
            for (EventExecutor eventExecutor : relayGroup) {
                roomLoops.add(eventExecutor);
            }
            Executor brokerLoop = brokerGroup.next();
            PlayerIdentity masterIdentity = PlayerIdentity.loadOrCreate(
                    dataDir.resolve("master-identity"));
            BundledProfileSource profiles = new BundledProfileSource();
            RecordingBlobStore recordingBlobs = new RecordingBlobStore(
                    dataDir.resolve("recordings"));
            VerifierRegistry verifiers = new VerifierRegistry(clock,
                    config.verifierStaleSeconds() * 1000L);
            VerificationJobQueue verificationJobs = new VerificationJobQueue(clock,
                    config.verifierLeaseSeconds() * 1000L);
            VerdictConsequences verdictConsequences = new VerdictConsequences(
                    store, ladder, clock, config.cheatBanDays() <= 0 ? 0
                    : config.cheatBanDays() * 24L * 3_600_000L);
            RelayRoomManager relays = new RelayRoomManager(masterIdentity, ladder,
                    profiles, roomLoops, brokerLoop, clock,
                    registry::heartbeat,
                    (roomId, owner, zone, act) ->
                            registry.updateTrack(roomId, owner, zone, act),
                    config, verificationJobs, verdictConsequences, verifiers);
            RoomBroker broker = new RoomBroker(masterIdentity, config, registry, store,
                    ladder, cache, clock, relays, tunnels,
                    key -> profileExists(profiles, key), verifiers);
            ConnectionHygiene.ConnectionCounter counter =
                    new ConnectionHygiene.ConnectionCounter(MAX_CONNECTIONS_PER_IP);
            SslContext ssl = sslContext(config);
            Map<String, Long> lastUploadByIdentity = new HashMap<>();
            channel = new ServerBootstrap().group(brokerGroup, relayGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socket) {
                            clientChannels.add(socket);
                            ChannelPipeline pipeline = socket.pipeline();
                            if (ssl != null) {
                                pipeline.addLast(ssl.newHandler(socket.alloc()));
                            }
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(
                                    config.maxRecordingBytes() + 8192));
                            pipeline.addLast(new MasterHttpRoutes(config,
                                    broker::fingerprintForSessionToken, recordingBlobs,
                                    verifiers, verificationJobs, verdictConsequences,
                                    clock, brokerLoop, relays::onVerdict,
                                    lastUploadByIdentity));
                            pipeline.addLast(new WebSocketServerProtocolHandler(
                                    "/master", null, true,
                                    Protocol.MAX_MASTER_FRAME_BYTES));
                            pipeline.addLast(new WebSocketFrameAggregator(
                                    Protocol.MAX_MASTER_FRAME_BYTES));
                            pipeline.addLast(new IdleStateHandler(60, 0, 0));
                            pipeline.addLast(new MasterChannelHandler(broker, relays,
                                    tunnels, brokerLoop, counter));
                        }
                    }).bind(config.port()).syncUninterruptibly().channel();
            List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();
            scheduledTasks.add(brokerGroup.next().scheduleAtFixedRate(broker::tick,
                    1, 1, TimeUnit.SECONDS));
            scheduledTasks.add(brokerGroup.next().scheduleAtFixedRate(relays::tickAll,
                    50, 50, TimeUnit.MILLISECONDS));
            scheduledTasks.add(brokerGroup.next().scheduleAtFixedRate(() -> {
                relays.voidExpiredUploads();
                verificationJobs.requeueExpiredLeases();
                verifiers.expireStale();
            }, 1, 1, TimeUnit.SECONDS));
            scheduledTasks.add(brokerGroup.next().scheduleAtFixedRate(
                    () -> recordingBlobs.deleteOlderThan(
                            clock.getAsLong() - config.recordingRetentionDays()
                                    * 24L * 3_600_000L),
                    1, 1, TimeUnit.HOURS));
            MasterServer server = new MasterServer(config, dataDir, brokerGroup,
                    relayGroup, channel, clientChannels, List.copyOf(scheduledTasks),
                    store, ladder, registry, tunnels, relays, broker,
                    recordingBlobs, verifiers, verificationJobs, verdictConsequences);
            server.admin = AdminEndpoint.start(config, server, dataDir);
            return server;
        } catch (Throwable failure) {
            if (channel != null) {
                channel.close().syncUninterruptibly();
            }
            clientChannels.close().syncUninterruptibly();
            if (store != null) {
                store.close();
            }
            relayGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            brokerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            throw failure;
        }
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public int adminPort() {
        return admin.port();
    }

    public void execute(Runnable task) {
        if (closed.get()) {
            throw new IllegalStateException("master server is closed");
        }
        brokerGroup.next().execute(task);
    }

    public RoomBroker broker() { return broker; }
    public RelayRoomManager relays() { return relays; }
    public VerifierRegistry verifiers() { return verifiers; }
    public VerificationJobQueue verificationJobs() { return verificationJobs; }
    public RecordingBlobStore recordingBlobs() { return recordingBlobs; }

    public void sanction(IdentityStore.SanctionRecord sanction) {
        ladder.sanction(sanction);
    }

    /** Test-only deterministic promotion, invoked on the broker loop. */
    public void establishForTest(String fingerprint) {
        long now = System.currentTimeMillis();
        long firstSeen = now - config.thresholds().establishedAgeMillis() - 1;
        store.establishForTest(fingerprint, firstSeen, now,
                config.thresholds().establishedCleanRounds());
        ladder.tierOf(fingerprint);
    }

    /** Test-only deterministic TRUSTED promotion, invoked on the broker loop. */
    public void trustForTest(String fingerprint) {
        long now = System.currentTimeMillis();
        long firstSeen = now - config.thresholds().trustedAgeMillis() - 1;
        store.establishForTest(fingerprint, firstSeen, now,
                config.thresholds().trustedCleanRounds());
        ladder.tierOf(fingerprint);
    }

    public List<IdentityStore.VerdictRecord> verdictsForTest(String fingerprint) {
        return store.verdictsFor(fingerprint);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (admin != null) {
            admin.close();
        }
        scheduledTasks.forEach(task -> task.cancel(false));
        serverChannel.close().syncUninterruptibly();
        clientChannels.close().syncUninterruptibly();
        drain(brokerGroup);
        drain(relayGroup);
        drain(brokerGroup);
        relayGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        brokerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        store.close();
    }

    private static void drain(NioEventLoopGroup group) {
        List<Future<?>> barriers = new ArrayList<>();
        for (EventExecutor executor : group) {
            barriers.add(executor.submit(() -> { }));
        }
        barriers.forEach(Future::syncUninterruptibly);
    }

    private static SslContext sslContext(MasterConfig config) throws Exception {
        if (config.plaintextForTest()) {
            LOG.log(System.Logger.Level.WARNING,
                    "MASTER RUNNING WITHOUT TLS - test mode only");
            return null;
        }
        if (config.tlsCertPath() == null || config.tlsKeyPath() == null) {
            throw new IllegalArgumentException("TLS certificate and key are required");
        }
        return SslContextBuilder.forServer(new File(config.tlsCertPath()),
                new File(config.tlsKeyPath())).build();
    }

    private static boolean profileExists(BundledProfileSource profiles, String key) {
        String[] parts = key == null ? new String[0] : key.split(":", -1);
        if (parts.length != 3) {
            return false;
        }
        try {
            return profiles.profileFor(parts[0], Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])).isPresent();
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
