package com.openggf.net.host;

import com.openggf.net.hub.RoomHost;
import com.openggf.net.hub.RoomHostConfig;
import com.openggf.net.hub.TrackValidationProfileSource;
import com.openggf.net.identity.PlayerIdentity;
import com.openggf.net.protocol.Protocol;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct-connect WebSocket transport for one player-hosted race room. */
@com.openggf.game.ModApi
public final class RaceHostServer implements AutoCloseable {
    private static final long TICK_MILLIS = 50;
    private static final int MAX_CONNECTIONS_PER_IP = 4;

    private final EventLoopGroup group;
    private final Channel serverChannel;
    private final RoomHost room;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RaceHostServer(EventLoopGroup group, Channel serverChannel, RoomHost room) {
        this.group = group;
        this.serverChannel = serverChannel;
        this.room = room;
    }

    public static RaceHostServer start(int port, RoomHostConfig config,
                                       PlayerIdentity hostIdentity,
                                       TrackValidationProfileSource profiles) {
        return start(port, config, hostIdentity, profiles, System::currentTimeMillis);
    }

    // Package-private clock seam: public transport behavior keeps wall time.
    static RaceHostServer start(int port, RoomHostConfig config,
                                PlayerIdentity hostIdentity,
                                TrackValidationProfileSource profiles,
                                LongSupplier clockMillis) {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        RoomHost room = new RoomHost(config, hostIdentity, clockMillis, profiles);
        ConnectionHygiene.ConnectionCounter counter =
                new ConnectionHygiene.ConnectionCounter(MAX_CONNECTIONS_PER_IP);
        try {
            Channel serverChannel = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            ChannelPipeline pipeline = channel.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(Protocol.MAX_CONTROL_BYTES));
                            pipeline.addLast(new WebSocketServerProtocolHandler(
                                    "/race", null, true, Protocol.MAX_CONTROL_BYTES));
                            pipeline.addLast(new WebSocketFrameAggregator(Protocol.MAX_CONTROL_BYTES));
                            pipeline.addLast(new IdleStateHandler(60, 0, 0));
                            pipeline.addLast(new RaceHostChannelHandler(room, counter));
                        }
                    })
                    .bind(port)
                    .syncUninterruptibly()
                    .channel();
            group.next().scheduleAtFixedRate(room::tick,
                    TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
            return new RaceHostServer(group, serverChannel, room);
        } catch (RuntimeException | Error e) {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            throw e;
        }
    }

    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void execute(Runnable task) {
        if (closed.get()) {
            throw new IllegalStateException("race host is closed");
        }
        group.next().execute(task);
    }

    public RoomHost room() {
        return room;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        serverChannel.close().syncUninterruptibly();
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
