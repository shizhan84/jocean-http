/**
 * 
 */
package org.jocean.redis.impl;

import java.net.SocketAddress;

import org.jocean.http.client.impl.AbstractChannelCreator;
import org.jocean.http.client.impl.ChannelCreator;
import org.jocean.http.client.impl.ChannelPool;
import org.jocean.http.client.impl.DefaultChannelPool;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.redis.RedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.redis.RedisArrayAggregator;
import io.netty.handler.codec.redis.RedisBulkStringAggregator;
import io.netty.handler.codec.redis.RedisDecoder;
import io.netty.handler.codec.redis.RedisEncoder;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class DefaultRedisClient implements RedisClient {
    
    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultRedisClient.class);
    
    private final Action1<RedisConnection> _doRecycleChannel = new Action1<RedisConnection>() {
        @Override
        public void call(final RedisConnection connection) {
            final DefaultRedisConnection defaultRedisConnection = (DefaultRedisConnection)connection;
            final Channel channel = defaultRedisConnection.channel();
            if (!defaultRedisConnection.isTransacting()) {
                if (_channelPool.recycleChannel(channel)) {
                    // recycle success
                    // perform read for recv FIN SIG and to change state to close
                    channel.read();
                }
            } else {
                channel.close();
                LOG.info("close transactioning redis channel: {}", channel);
            }
        }};
        
    @Override
    public Observable<? extends RedisConnection> getConnection() {
        return null == this._defaultRemoteAddress 
                ? Observable.<RedisConnection>error(new RuntimeException("No Default Redis Server"))
                : getConnection(this._defaultRemoteAddress);
    }
    
    @Override
    public Observable<? extends RedisConnection> getConnection(final SocketAddress remoteAddress) {
        return this._channelPool.retainChannel(remoteAddress)
                .map(channel2RedisConnection())
                .onErrorResumeNext(createChannelAndConnectTo(remoteAddress))
                ;
    }

    private Func1<Channel, RedisConnection> channel2RedisConnection() {
        return new Func1<Channel, RedisConnection>() {
            @Override
            public RedisConnection call(final Channel channel) {
                return new DefaultRedisConnection(channel, _doRecycleChannel);
            }};
    }

    private Observable<? extends RedisConnection> createChannelAndConnectTo(
            final SocketAddress remoteAddress) {
        final Observable<? extends RedisConnection> ret = this._channelCreator.newChannel()
            .doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    final ChannelPipeline p = channel.pipeline();
                    p.addLast(new RedisDecoder());
                    p.addLast(new RedisBulkStringAggregator());
                    p.addLast(new RedisArrayAggregator());
                    p.addLast(new RedisEncoder());
                }})
            .flatMap(RxNettys.asyncConnectTo(remoteAddress))
            .doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    Nettys.setChannelReady(channel);
                }})
            .map(channel2RedisConnection());
        if (null != this._fornew) {
            return ret.compose(this._fornew);
        } else {
            return ret;
        }
    }
    
    public DefaultRedisClient() {
        this(0);
    }
    
    public DefaultRedisClient(final int processThreadNumber) {
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(processThreadNumber))
                .channel(NioSocketChannel.class);
            }},
            new DefaultChannelPool());
    }
    
    public DefaultRedisClient(
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType) { 
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channel(channelType);
            }},
            new DefaultChannelPool());
    }
    
    public DefaultRedisClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory) { 
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channelFactory(channelFactory);
            }},
            new DefaultChannelPool());
    }
    
    public DefaultRedisClient(
            final ChannelCreator channelCreator) {
        this(channelCreator, new DefaultChannelPool());
    }
    
    public DefaultRedisClient(
            final ChannelCreator channelCreator,
            final ChannelPool channelPool) {
        this._channelCreator = channelCreator;
        this._channelPool = channelPool;
    }
    
    @Override
    public void close() {
        // Shut down executor threads to exit.
        this._channelCreator.close();
    }
    
    public void setFornew(final Transformer<? super RedisConnection, ? extends RedisConnection> fornew) {
        this._fornew = fornew;
    }
    
    public void setDefaultRedisServer(final SocketAddress defaultRedisServerAddr) {
        this._defaultRemoteAddress = defaultRedisServerAddr;
    }
    
    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private SocketAddress _defaultRemoteAddress;
    private Transformer<? super RedisConnection, ? extends RedisConnection> _fornew = null;
}
