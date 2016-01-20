/**
 * 
 */
package org.jocean.http.client.impl;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.client.HttpClient;
import org.jocean.http.client.InteractionMeter;
import org.jocean.http.client.Outbound.ApplyToRequest;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.Nettys.ChannelAware;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subscriptions.Subscriptions;

/**
 * @author isdom
 *
 */
public class DefaultHttpClient implements HttpClient {
    
    //放在最顶上，以让NETTY默认使用SLF4J
    static {
        if (!(InternalLoggerFactory.getDefaultFactory() instanceof Slf4JLoggerFactory)) {
            InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory());
        }
    }
    
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClient.class);
    
    /* (non-Javadoc)
     * @see org.jocean.http.client.HttpClient#sendRequest(java.net.URI, rx.Observable)
     * eg: new SocketAddress(this._uri.getHost(), this._uri.getPort()))
     */
    @Override
    public Observable<? extends HttpObject> defineInteraction(
            final SocketAddress remoteAddress,
            final Observable<? extends Object> request,
            final Feature... features) {
        final Feature[] applyFeatures = cloneFeatures(features.length > 0 ? features : this._defaultFeatures);
        return Observable.create(new OnSubscribe<HttpObject>() {
            @Override
            public void call(final Subscriber<? super HttpObject> responseSubscriber) {
                if (!responseSubscriber.isUnsubscribed()) {
                    try {
                        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<Subscription>();
                        final Action1<Subscription> add4release = new Action1<Subscription>() {
                            @Override
                            public void call(final Subscription subscription) {
                                if ( null == subscriptionRef.get()) {
                                    subscriptionRef.set(subscription);
                                } else {
                                    subscriptionRef.set(Subscriptions.from(subscriptionRef.get(), subscription));
                                }
                            }};
                        
                        final Feature[] fullFeatures = 
                                JOArrays.addFirst(Feature[].class, 
                                        applyFeatures, 
                                        HttpClientConstants.APPLY_HTTPCLIENT);
//                        buildFeatures(applyFeatures, responseSubscriber);
                        _channelPool.retainChannel(remoteAddress, add4release)
                            .doOnNext(prepareReuseChannel(fullFeatures, add4release))
                            .onErrorResumeNext(createChannel(remoteAddress, fullFeatures, add4release))
                            .doOnNext(attachSubscriberToChannel(responseSubscriber, add4release))
                            .doOnNext(fillChannelAware(applyFeatures))
                            .doOnNext(hookInteractionMeter(applyFeatures, add4release))
                            .flatMap(doTransferRequest(request, applyFeatures))
                            .flatMap(RxNettys.<ChannelFuture, HttpObject>emitErrorOnFailure())
//                            .doOnNext(new Action1<ChannelFuture>() {
//                                @Override
//                                public void call(final ChannelFuture future) {
//                                    responseSubscriber.add(Subscriptions.from(future));
//                                    future.addListener(RxNettys.makeFailure2ErrorListener(responseSubscriber));
//                                }})
                            .doOnUnsubscribe(new Action0() {
                                @Override
                                public void call() {
                                    final Subscription subscription = subscriptionRef.getAndSet(null);
                                    if (null!=subscription) {
                                        subscription.unsubscribe();
                                    }
                                }})
                            .subscribe(responseSubscriber);
                    } catch (final Throwable e) {
                        responseSubscriber.onError(e);
                    }
                } else {
                    LOG.warn("defineInteraction: responseSubscriber {} has unsubscribe", responseSubscriber);
                }
            }});
    }

    /*
    private Feature[] buildFeatures(
            Feature[] features,
            final Subscriber<Object> responseSubscriber) {
        features = JOArrays.addFirst(Feature[].class, features, HttpClientConstants.APPLY_HTTPCLIENT);
        final ResponseSubscriberAware responseSubscriberAware = 
                InterfaceUtils.compositeIncludeType(ResponseSubscriberAware.class, (Object[])features);
        if (null!=responseSubscriberAware) {
            responseSubscriberAware.setResponseSubscriber(responseSubscriber);
        }
        return features;
    }
    */

    private Action1<Channel> fillChannelAware(final Feature[] features) {
        final ChannelAware channelAware = 
            InterfaceUtils.compositeIncludeType(ChannelAware.class, (Object[])features);
        
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                if (null!=channelAware) {
                    try {
                        channelAware.setChannel(channel);
                    } catch (Exception e) {
                        LOG.warn("exception when invoke setChannel for channel ({}), detail: {}",
                                channel, ExceptionUtils.exception2detail(e));
                    }
                }
            }};
    }

    private Action1<? super Channel> hookInteractionMeter(
            final Feature[] features, final Action1<Subscription> add4release) {
        final InteractionMeterAware interactionMeterAware = 
                InterfaceUtils.compositeIncludeType(InteractionMeterAware.class, (Object[])features);
            
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                if (null!=interactionMeterAware) {
                    try {
                        interactionMeterAware.setInteractionMeter(buildInteractionMeter(channel, add4release));
                    } catch (Exception e) {
                        LOG.warn("exception when invoke setInteractionMeter for channel ({}), detail: {}",
                                channel, ExceptionUtils.exception2detail(e));
                    }
                }
            }};
    }

    private InteractionMeter buildInteractionMeter(final Channel channel, 
            final Action1<Subscription> add4release) {
        final InteractionMeterHandler handler = 
                (InteractionMeterHandler)HttpClientConstants.APPLY.INTERACTIONMETER.applyTo(channel.pipeline());
        
        add4release.call(
            Subscriptions.create(
                new Action0() {
                    @Override
                    public void call() {
                        final ChannelPipeline pipeline = channel.pipeline();
                        if (pipeline.context(handler) != null) {
                            pipeline.remove(handler);
                        }
                    }}));
        return handler;
    }

    private Action1<Channel> prepareReuseChannel(
            final Feature[] features,
            final Action1<Subscription> add4release) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                add4release.call(recycleChannelSubscription(channel));
                add4release.call(applyInteractionFeatures(channel, features));
            }};
    }

    private static void applyChannelFeatures(
            final Channel channel,
            final Feature[] features) {
        InterfaceUtils.combineImpls(Feature.class, features)
            .call(HttpClientConstants._APPLY_BUILDER_PER_CHANNEL, channel.pipeline());
    }

    private static Subscription applyInteractionFeatures(
            final Channel channel,
            final Feature[] features) {
        final Func0<String[]> diff = Nettys.namesDifferenceBuilder(channel);
        InterfaceUtils.combineImpls(Feature.class, features)
            .call(HttpClientConstants._APPLY_BUILDER_PER_INTERACTION, channel.pipeline());
        return RxNettys.removeHandlersSubscription(channel, diff.call());
    }
    
    private static boolean isSSLEnabled(final Feature[] features) {
        if (null == features) {
            return false;
        }
        for (Feature feature : features) {
            if (feature instanceof ENABLE_SSL) {
                return true;
            }
        }
        return false;
    }

    private Action1<? super Channel> attachSubscriberToChannel(
            final Subscriber<? super HttpObject> responseSubscriber,
            final Action1<Subscription> add4release) {
        return new Action1<Channel>() {
            @Override
            public void call(final Channel channel) {
                final ChannelHandler handler = new OnSubscribeHandler(responseSubscriber);
                channel.pipeline().addLast(handler);
                
                add4release.call(
                    Subscriptions.create(
                        new Action0() {
                            @Override
                            public void call() {
                                final ChannelPipeline pipeline = channel.pipeline();
                                if (pipeline.context(handler) != null) {
                                    pipeline.remove(handler);
                                }
                            }}));
            }};
    }

    private Func1<Channel, Observable<ChannelFuture>> doTransferRequest(
            final Observable<? extends Object> request,
            final Feature[] features) {
        final ApplyToRequest applyToRequest = 
                InterfaceUtils.compositeIncludeType(
                    ApplyToRequest.class,
                    InterfaceUtils.compositeBySource(
                        ApplyToRequest.class, HttpClientConstants._CLS_TO_APPLY2REQ, features),
                    InterfaceUtils.compositeIncludeType(
                        ApplyToRequest.class, (Object[])features));
        return new Func1<Channel, Observable<ChannelFuture>> () {
            @Override
            public Observable<ChannelFuture> call(final Channel channel) {
                return request.doOnNext(doApplyToRequest(applyToRequest))
                        .doOnNext(doForChannelPool(channel))
                        .map(RxNettys.<Object>sendMessage(channel));
            }
        };
    }

    private Action1<Object> doApplyToRequest(final ApplyToRequest applyToRequest) {
        return new Action1<Object> () {
            @Override
            public void call(final Object msg) {
                if (msg instanceof HttpRequest && null!=applyToRequest) {
                    applyToRequest.call((HttpRequest) msg);
                }
            }
        };
    }
    
    private final Action1<Object> doForChannelPool(final Channel channel) {
        return new Action1<Object> () {
            @Override
            public void call(final Object msg) {
                if (msg instanceof HttpRequest) {
                    _channelPool.beforeSendRequest(channel, (HttpRequest)msg);
                }
            }
        };
    }

    private Observable<? extends Channel> createChannel(
            final SocketAddress remoteAddress, 
            final Feature[] features, 
            final Action1<Subscription> add4release) {
        Observable<? extends Channel> channelObservable = Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> channelSubscriber) {
                if (!channelSubscriber.isUnsubscribed()) {
                    final ChannelFuture future = _channelCreator.newChannel();
                    ChannelPool.Util.attachChannelPool(future.channel(), _channelPool);
                    add4release.call(recycleChannelSubscription(future.channel()));
                    add4release.call(Subscriptions.from(future));
                    future.addListener(RxNettys.makeFailure2ErrorListener(channelSubscriber));
                    future.addListener(RxNettys.makeSuccess2NextCompletedListener(channelSubscriber));
                } else {
                    LOG.warn("newChannel: channelSubscriber {} has unsubscribe", channelSubscriber);
                }
            }})
            .flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                @Override
                public Observable<? extends Channel> call(final Channel channel) {
                    return Observable.create(new OnSubscribe<Channel>() {
                        @Override
                        public void call(final Subscriber<? super Channel> channelSubscriber) {
                            if (!channelSubscriber.isUnsubscribed()) {
                                applyChannelFeatures(channel, features);
                                add4release.call(applyInteractionFeatures(channel, features));
                                final ChannelFuture future = channel.connect(remoteAddress);
                                add4release.call(Subscriptions.from(future));
                                future.addListener(RxNettys.makeFailure2ErrorListener(channelSubscriber));
                                future.addListener(RxNettys.makeSuccess2NextCompletedListener(channelSubscriber));
                            } else {
                                LOG.warn("applyFeatures: channelSubscriber {} has unsubscribe", channelSubscriber);
                            }
                        }});
                }});
        if (isSSLEnabled(features)) {
            channelObservable = channelObservable.flatMap(new Func1<Channel, Observable<? extends Channel>> () {
                @Override
                public Observable<? extends Channel> call(final Channel channel) {
                    return Observable.create(new OnSubscribe<Channel>() {
                        @Override
                        public void call(final Subscriber<? super Channel> channelSubscriber) {
                            if (!channelSubscriber.isUnsubscribed()) {
                                channel.pipeline().addLast(new SslHandshakeNotifier(channelSubscriber));
                            } else {
                                LOG.warn("SslHandshakeNotifier: channelSubscriber {} has unsubscribe", channelSubscriber);
                            }
                        }});
                }});
        } else {
            channelObservable = channelObservable.doOnNext(new Action1<Channel>() {
                @Override
                public void call(final Channel channel) {
                    ChannelPool.Util.setChannelReady(channel);
                }});
        }
        return channelObservable;
    }

    private Feature[] cloneFeatures(final Feature[] features) {
        final Feature[] cloned = new Feature[features.length];
        for (int idx = 0; idx < cloned.length; idx++) {
            if (features[idx] instanceof Cloneable) {
                cloned[idx] = ReflectUtils.invokeClone(features[idx]);
            } else {
                cloned[idx] = features[idx];
            }
        }
        return cloned;
    }

    private Subscription recycleChannelSubscription(final Channel channel) {
        return Subscriptions.create(new Action0() {
            @Override
            public void call() {
                if (channel.eventLoop().inEventLoop()) {
                    ChannelPool.Util.getChannelPool(channel).recycleChannel(channel);
                } else {
                    channel.eventLoop().submit(new Runnable() {
                        @Override
                        public void run() {
                            ChannelPool.Util.getChannelPool(channel).recycleChannel(channel);
                        }});
                }
            }
        });
    }
    
    public DefaultHttpClient(final int processThreadNumber) {
        this(processThreadNumber, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpClient() {
        this(0, Feature.EMPTY_FEATURES);
    }
    
    public DefaultHttpClient(final Feature... defaultFeatures) {
        this(0, defaultFeatures);
    }
    
    public DefaultHttpClient(final int processThreadNumber,
            final Feature... defaultFeatures) {
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap
                .group(new NioEventLoopGroup(processThreadNumber))
                .channel(NioSocketChannel.class);
            }},
            new DefaultChannelPool(), 
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final Class<? extends Channel> channelType,
            final Feature... defaultFeatures) { 
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channel(channelType);
            }},
            new DefaultChannelPool(),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final EventLoopGroup eventLoopGroup,
            final ChannelFactory<? extends Channel> channelFactory,
            final Feature... defaultFeatures) { 
        this(new AbstractChannelCreator() {
            @Override
            protected void initializeBootstrap(final Bootstrap bootstrap) {
                bootstrap.group(eventLoopGroup).channelFactory(channelFactory);
            }},
            new DefaultChannelPool(),
            defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final Feature... defaultFeatures) {
        this(channelCreator, new DefaultChannelPool(), defaultFeatures);
    }
    
    public DefaultHttpClient(
            final ChannelCreator channelCreator,
            final ChannelPool channelPool,
            final Feature... defaultFeatures) {
        this._channelCreator = channelCreator;
        this._channelPool = channelPool;
        this._defaultFeatures = (null != defaultFeatures) ? defaultFeatures : Feature.EMPTY_FEATURES;
    }
    
    /* (non-Javadoc)
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        // Shut down executor threads to exit.
        this._channelCreator.close();
    }
    
    private final ChannelPool _channelPool;
    private final ChannelCreator _channelCreator;
    private final Feature[] _defaultFeatures;
}
