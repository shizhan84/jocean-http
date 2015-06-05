package org.jocean.http.client.impl;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;

import java.net.SocketAddress;

import org.jocean.http.client.Outbound.Feature;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.OneshotSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;

public abstract class ChannelPoolImpl implements ChannelPool {
    
    @SuppressWarnings("unused")
    private static final Logger LOG =
            LoggerFactory.getLogger(ChannelPoolImpl.class);

    public void setIsReady(final Func1<Channel, Boolean> isReady) {
        this._isReady = isReady;
    }
    
    @Override
    public Observable<Channel> retainChannel(
            final SocketAddress address, 
            final Feature[] features) {
        return Observable.create(new OnSubscribe<Channel>() {
            @Override
            public void call(final Subscriber<? super Channel> subscriber) {
                doRetainChannel(address, subscriber);
            }});
    }
    
    private void doRetainChannel(final SocketAddress address,
            final Subscriber<? super Channel> subscriber) {
        if (!subscriber.isUnsubscribed()) {
            try {
                final Channel channel = reuseChannel(address);
                if (null!=channel) {
                    subscriber.add(recycleChannelSubscription(channel));
                    final Runnable runnable = buildOnNextRunnable(address, subscriber, channel);
                    if (channel.eventLoop().inEventLoop()) {
                        runnable.run();
                    } else {
                        RxNettys.<Future<?>,Channel>emitErrorOnFailure()
                            .call(channel.eventLoop().submit(runnable))
                            .subscribe(subscriber);
                    }
                } else {
                    subscriber.onError(new RuntimeException("Nonreused Channel"));
                }
            } catch (Throwable e) {
                subscriber.onError(e);
            }
        }
    }

    private Runnable buildOnNextRunnable(
            final SocketAddress address, 
            final Subscriber<? super Channel> subscriber,
            final Channel channel) {
        return new Runnable() {
            @Override
            public void run() {
                if (channel.isActive()) {
                    subscriber.onNext(channel);
                    subscriber.onCompleted();
                } else {
                    channel.close();
                    doRetainChannel(address, subscriber);
                }
            }};
    }

    private Subscription recycleChannelSubscription(final Channel channel) {
        return new OneshotSubscription() {
            @Override
            protected void doUnsubscribe() {
                if (channel.eventLoop().inEventLoop()) {
                    recycleChannel(channel);
                } else {
                    channel.eventLoop().submit(new Runnable() {
                        @Override
                        public void run() {
                            recycleChannel(channel);
                        }});
                }
            }};
    }
    
    @Override
    public boolean recycleChannel(final Channel channel) {
        return this._isReady.call(channel);
    }
    
    protected abstract Channel reuseChannel(final SocketAddress address);
    
    private Func1<Channel, Boolean> _isReady;
}
