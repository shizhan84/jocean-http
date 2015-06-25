/**
 * 
 */
package org.jocean.http.server.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.server.HttpServer;
import org.jocean.http.util.Class2ApplyBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.OnHttpObject;
import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.http.util.PipelineApply;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.rx.RxFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Func1;
import rx.functions.FuncN;
import rx.functions.Functions;

/**
 * @author isdom
 *
 */
public class DefaultHttpServer implements HttpServer {

    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }
    
    interface ChannelRecycler {
        public void onResponseCompleted(final Channel channel, final boolean isKeepAlive);
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpServer.class);
    
    /* (non-Javadoc)
     * @see org.jocean.http.server.HttpServer#create(java.net.SocketAddress)
     */
    @Override
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress,
            final Feature... features) {
        final Feature[] applyFeatures = 
                (null != features && features.length > 0 ) ? features : this._defaultFeatures;
        return Observable.create(new OnSubscribe<HttpTrade>() {
            @Override
            public void call(final Subscriber<? super HttpTrade> subscriber) {
                if (!subscriber.isUnsubscribed()) {
                    final ServerBootstrap bootstrap = _creator.newBootstrap();
                    bootstrap.childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(final Channel channel) throws Exception {
                            for (Feature feature : applyFeatures) {
                                feature.call(_BUILDER, channel.pipeline());
                            }
                            APPLY_HTTPSERVER.call(_BUILDER, channel.pipeline());
                            subscriber.onNext(createHttpTrade(channel, subscriber));
                        }});
                    final ChannelFuture future = bootstrap.bind(localAddress);
                    subscriber.add(RxNettys.subscriptionFrom(future.channel()));
                    RxNettys.<ChannelFuture, HttpTrade>emitErrorOnFailure()
                        .call(future)
                        .subscribe(subscriber);
                }
            }});
    }

    private DefaultHttpTrade createHttpTrade(
            final Channel channel, 
            final Subscriber<? super HttpTrade> subscriber) {
        return new DefaultHttpTrade(channel, 
                createChannelRecycler(subscriber),
                _BUILDER, 
                new APPLY_WORKER());
    }

    private ChannelRecycler createChannelRecycler(final Subscriber<? super HttpTrade> subscriber) {
        return new ChannelRecycler() {
            @Override
            public void onResponseCompleted(
                    final Channel channel, final boolean isKeepAlive) {
                //  reference: https://github.com/netty/netty/commit/5112cec5fafcec8724b2225507da33bbb9bc47f3
                //  Detail:
                //  Bypass the encoder in case of an empty buffer, so that the following idiom works:
                //
                //     ch.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                //
                // See https://github.com/netty/netty/issues/2983 for more information.
                if (isKeepAlive && !subscriber.isUnsubscribed()) {
                    channel.flush();
                    if (channel.eventLoop().inEventLoop()) {
                        subscriber.onNext(createHttpTrade(channel, subscriber));
                    } else {
                        channel.eventLoop().submit(new Runnable() {
                            @Override
                            public void run() {
                                subscriber.onNext(createHttpTrade(channel, subscriber));
                            }});
                    }
                } else {
                    channel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                        .addListener(ChannelFutureListener.CLOSE);
                }
            }};
    }

    public DefaultHttpServer() {
        this((Feature[])null);
    }
    
    public DefaultHttpServer(
            final Feature... defaultFeatures) {
        this(new AbstractBootstrapCreator(new NioEventLoopGroup(1), new NioEventLoopGroup()) {
            @Override
            protected void initializeBootstrap(ServerBootstrap bootstrap) {
                bootstrap.option(ChannelOption.SO_BACKLOG, 1024);
                bootstrap.channel(NioServerSocketChannel.class);
            }}, defaultFeatures);
    }
    
    public DefaultHttpServer(
            final BootstrapCreator creator,
            final Feature... defaultFeatures) {
        this._creator = creator;
        this._defaultFeatures = null!=defaultFeatures ? defaultFeatures : Feature.EMPTY_FEATURES;
    }

    @Override
    public void close() throws IOException {
        this._creator.close();
    }
    
    private final BootstrapCreator _creator;
    private final Feature[] _defaultFeatures;
    
    private static final Class2ApplyBuilder _BUILDER;
        
    private static final FuncN<ChannelHandler> HTTPSERVER_CODEC_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpServerCodec();
        }};
        
    private static final FuncN<ChannelHandler> CONTENT_COMPRESSOR_FUNCN = new FuncN<ChannelHandler>() {
        @Override
        public ChannelHandler call(final Object... args) {
            return new HttpContentCompressor();
        }
    };
            
    public static final Func1<OnHttpObject, ChannelHandler> HTTPSERVER_WORK_FUNC1 = 
            new Func1<OnHttpObject, ChannelHandler>() {
        @Override
        public ChannelHandler call(final OnHttpObject onHttpObject) {
            return new SimpleChannelInboundHandler<HttpObject>() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx,
                        Throwable cause) throws Exception {
                    LOG.warn("exceptionCaught {}, detail:{}", ctx.channel(),
                            ExceptionUtils.exception2detail(cause));
                    onHttpObject.onError(cause);
                    ctx.close();
                }

                // @Override
                // public void channelReadComplete(ChannelHandlerContext ctx) {
                // ctx.flush();
                // }

                @Override
                public void channelInactive(final ChannelHandlerContext ctx)
                        throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({})/handler({}): channelInactive and call onHttpObject({}).onError with RuntimeException.", 
                                ctx.channel(), this, onHttpObject);
                    }
                    onHttpObject.onError(new RuntimeException("channelInactive"));
                }

                @Override
                protected void channelRead0(final ChannelHandlerContext ctx,
                        final HttpObject msg) throws Exception {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("channel({})/handler({}): channelRead0 and call onHttpObject({}).onHttpObject with msg({}).", 
                                ctx.channel(), this, onHttpObject, msg);
                    }
                    onHttpObject.onHttpObject(msg);
                }

                // @Override
                // public void channelActive(final ChannelHandlerContext ctx)
                // throws Exception {
                // }
            };
        }
    };
    
    private static enum APPLY implements PipelineApply {
        LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
        CLOSE_ON_IDLE(Functions.fromFunc(Nettys.CLOSE_ON_IDLE_FUNC1)),
        SSL(Functions.fromFunc(Nettys.SSL_FUNC2)),
        HTTPSERVER(HTTPSERVER_CODEC_FUNCN),
        CONTENT_COMPRESSOR(CONTENT_COMPRESSOR_FUNCN),
        WORKER(Functions.fromFunc(HTTPSERVER_WORK_FUNC1)),
        ;
        
        @Override
        public ChannelHandler applyTo(final ChannelPipeline pipeline, final Object ... args) {
            if (null==this._factory) {
                throw new UnsupportedOperationException("ChannelHandler's factory is null");
            }
            return Nettys.insertHandler(
                    pipeline,
                this.name(), 
                this._factory.call(args), 
                TO_ORDINAL);
        }
    
        public static final ToOrdinal TO_ORDINAL = Nettys.ordinal(APPLY.class);
        
        private APPLY(final FuncN<ChannelHandler> factory) {
            this._factory = factory;
        }
    
        private final FuncN<ChannelHandler> _factory;
    }
    
    private final static Feature APPLY_HTTPSERVER = new Feature.AbstractFeature0() {};

    private static final class APPLY_WORKER 
        implements Feature, OnHttpObjectAware {

        @Override
        public ChannelHandler call(final HandlerBuilder builder,
                final ChannelPipeline pipeline) {
            return APPLY.WORKER.applyTo(pipeline, this._onHttpObject);
        }

        @Override
        public void setOnHttpObject(final OnHttpObject onHttpObject) {
            this._onHttpObject = onHttpObject;
        }
        
        private OnHttpObject _onHttpObject;
    }
    
    static {
        _BUILDER = new Class2ApplyBuilder();
        _BUILDER.register(Feature.ENABLE_LOGGING.getClass(), APPLY.LOGGING);
        _BUILDER.register(Feature.ENABLE_COMPRESSOR.getClass(), APPLY.CONTENT_COMPRESSOR);
        _BUILDER.register(Feature.ENABLE_CLOSE_ON_IDLE.class, APPLY.CLOSE_ON_IDLE);
        _BUILDER.register(Feature.ENABLE_SSL.class, APPLY.SSL);
        _BUILDER.register(APPLY_HTTPSERVER.getClass(), APPLY.HTTPSERVER);
        
    }
}
