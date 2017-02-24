/**
 * 
 */
package org.jocean.http.server.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jocean.http.InboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.http.server.HttpServerBuilder.HttpTrade;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.InboundEndpointSupport;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.FuncSelector;
import org.jocean.idiom.TerminateAwareSupport;
import org.jocean.idiom.rx.Func1_N;
import org.jocean.idiom.rx.RxActions;
import org.jocean.idiom.rx.RxFunctions;
import org.jocean.idiom.rx.RxSubscribers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
class DefaultHttpTrade extends DefaultAttributeMap 
    implements HttpTrade,  Comparable<DefaultHttpTrade>  {
    
    private static final AtomicInteger _IDSRC = new AtomicInteger(0);
    
    private final int _id = _IDSRC.getAndIncrement();
    
    @Override
    public int compareTo(final DefaultHttpTrade o) {
        return this._id - o._id;
    }
    
    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + _id;
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DefaultHttpTrade other = (DefaultHttpTrade) obj;
        if (_id != other._id)
            return false;
        return true;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("DefaultHttpTrade [create at:")
                .append(new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date(this._createTimeMillis)))
                .append(", request subscriber count=").append(_inboundSupport.subscribersCount())
                .append(", isRequestReceived=").append(_isRequestReceived.get())
                .append(", requestMethod=").append(_requestMethod)
                .append(", requestUri=").append(_requestUri)
                .append(", isRequestCompleted=").append(_isRequestCompleted.get())
                .append(", isResponseSetted=").append(_isResponseSetted.get())
                .append(", isResponseSended=").append(_isResponseSended.get())
                .append(", isResponseCompleted=").append(_isResponseCompleted.get())
                .append(", isKeepAlive=").append(_isKeepAlive.get())
                .append(", isActive=").append(isActive())
                .append(", channel=").append(_channel)
                .append("]");
        return builder.toString();
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpTrade.class);
    
    private final FuncSelector<DefaultHttpTrade> _funcSelector = 
            new FuncSelector<>(this);
    
    @SafeVarargs
    DefaultHttpTrade(
        final Channel channel, 
        final Action1<HttpTrade> ... onTerminates) {
        this(channel, 0, onTerminates);
    }
    
    @SafeVarargs
    DefaultHttpTrade(
        final Channel channel, 
        final int inboundBlockSize,
        final Action1<HttpTrade> ... onTerminates) {
        
        this._terminateAwareSupport = 
                new TerminateAwareSupport<HttpTrade, DefaultHttpTrade>(
                    this, _funcSelector);
        this._channel = channel;
        
        final HttpMessageHolder holder = new HttpMessageHolder(inboundBlockSize);
        doOnTerminate(holder.release());
        
        final Observable<? extends HttpObject> cachedInbound = 
                RxNettys.inboundFromChannel(channel, onTerminate())
                .compose(holder.<HttpObject>assembleAndHold())
                .compose(markInboundStateAndCloseOnError())
                .cache()
                .compose(RxNettys.duplicateHttpContent())
                ;
        
        cachedInbound.subscribe(
            RxSubscribers.ignoreNext(),
            new Action1<Throwable>() {
                @Override
                public void call(final Throwable e) {
                    LOG.warn("Trade({})'s internal request subscriber invoke with onError {}", 
                            DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
                }});
        
        for (Action1<HttpTrade> onTerminate : onTerminates) {
            doOnTerminate(onTerminate);
        }
        
        //  在 HTTPOBJ_SUBSCRIBER 添加到 channel.pipeline 后, 再添加 channelInactive 的处理 Handler
        this._trafficCounter = RxNettys.applyToChannelWithUninstall(channel, 
                onTerminate(), 
                APPLY.TRAFFICCOUNTER);
        //  TODO, test for channel already inactive
        RxNettys.applyToChannelWithUninstall(channel, 
                onTerminate(), 
                APPLY.ON_CHANNEL_INACTIVE,
                new Action0() {
                    @Override
                    public void call() {
                        doClose();
                    }});
        
        this._inboundSupport = 
            new InboundEndpointSupport<DefaultHttpTrade>(
                _funcSelector,
                channel,
                cachedInbound,
                holder,
                _trafficCounter,
                onTerminate());
    }

    private Transformer<HttpObject, HttpObject> markInboundStateAndCloseOnError() {
        return new Transformer<HttpObject, HttpObject>() {
            @Override
            public Observable<HttpObject> call(final Observable<HttpObject> src) {
                return src.doOnNext(new Action1<HttpObject>() {
                    @Override
                    public void call(final HttpObject msg) {
                        if (msg instanceof HttpRequest) {
                            _requestMethod = ((HttpRequest)msg).method().name();
                            _requestUri = ((HttpRequest)msg).uri();
                            _isRequestReceived.compareAndSet(false, true);
                            _isKeepAlive.set(HttpUtil.isKeepAlive((HttpRequest)msg));
                        } else if (!_isRequestReceived.get()) {
                            LOG.warn("trade {} missing http request and recv httpobj {}", 
                                DefaultHttpTrade.this, msg);
                        }
                    }})
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        _isRequestCompleted.set(true);
                    }})
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable e) {
                        doClose();
                    }});
            }};
    }
    
    @Override
    public TrafficCounter trafficCounter() {
        return this._trafficCounter;
    }
    
    @Override
    public boolean isActive() {
        return this._funcSelector.isActive();
    }

    public boolean isEndedWithKeepAlive() {
        return (this._isRequestCompleted.get() 
            && this._isResponseCompleted.get()
            && this._isKeepAlive.get());
    }

    @Override
    public void abort() {
        doAbort();
    }

    @Override
    public Object transport() {
        return this._channel;
    }
    
    @Override
    public InboundEndpoint inbound() {
        return this._inboundSupport;
    }

    @Override
    public Subscription outboundResponse(final Observable<? extends HttpObject> response) {
        return this._doSetOutboundResponse.call(response);
    }
    
    private final Func1<Observable<? extends HttpObject>, Subscription> 
        _doSetOutboundResponse = 
            RxFunctions.toFunc1(
                this._funcSelector.callWhenActive(RxFunctions.<DefaultHttpTrade,Subscription>toFunc1_N(
                        DefaultHttpTrade.class, "doSetOutboundResponse"))
                .callWhenDestroyed(Func1_N.Util.<DefaultHttpTrade,Subscription>returnNull()));
    
    @SuppressWarnings("unused")
    private Subscription doSetOutboundResponse(
        final Observable<? extends HttpObject> response) {
        if (this._isResponseSetted.compareAndSet(false, true)) {
            return response.subscribe(
                    actionResponseOnNext(),
                    actionResponseOnError(),
                    actionResponseOnCompleted());
        } else {
            LOG.warn("trade({}) 's outboundResponse has setted, ignore new response({})",
                    this, response);
            return null;
        }
    }

    private final Action0 actionResponseOnCompleted() {
        return RxActions.toAction0(
            this._funcSelector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnCompleted")));
    }

    @SuppressWarnings("unused")
    private void respOnCompleted() {
        this._isResponseCompleted.compareAndSet(false, true);
        this._channel.writeAndFlush(Unpooled.EMPTY_BUFFER)
        .addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future)
                    throws Exception {
                //  TODO, flush success or failed
                try {
                    doClose();
                } catch (Exception e) {
                    LOG.warn("exception when ({}).doClose, detail:{}",
                            this, ExceptionUtils.exception2detail(e));
                }
            }});
    }

    private final Action1<HttpObject> actionResponseOnNext() {
        return RxActions.<HttpObject>toAction1(
            this._funcSelector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnNext")));
    }
    
    @SuppressWarnings("unused")
    private void respOnNext(final HttpObject msg) {
        this._isResponseSended.compareAndSet(false, true);
        this._channel.write(ReferenceCountUtil.retain(msg));
    }

    private final Action1<Throwable> actionResponseOnError() {
        return RxActions.<Throwable>toAction1(
            this._funcSelector.submitWhenActive(RxActions.toAction1_N(DefaultHttpTrade.class, "respOnError")));
    }
    
    @SuppressWarnings("unused")
    private void respOnError(final Throwable e) {
        LOG.warn("trade({})'s responseObserver.onError, default action is invoke doAbort() and detail:{}",
                DefaultHttpTrade.this, ExceptionUtils.exception2detail(e));
        doAbort();
    }
        
    @Override
    public boolean readyforOutboundResponse() {
        return this._funcSelector.isActive() && !this._isResponseSetted.get();
    }
    
    @Override
    public Action1<Action0> onTerminate() {
        return this._terminateAwareSupport.onTerminate();
    }

    @Override
    public Action1<Action1<HttpTrade>> onTerminateOf() {
        return this._terminateAwareSupport.onTerminateOf();
    }

    @Override
    public Action0 doOnTerminate(Action0 onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(onTerminate);
    }

    @Override
    public Action0 doOnTerminate(Action1<HttpTrade> onTerminate) {
        return this._terminateAwareSupport.doOnTerminate(onTerminate);
    }
    
    private void doAbort() {
        this._funcSelector.destroy(RxActions.toAction1_N(DefaultHttpTrade.class, "closeChannelAndFireDoOnClosed"));
    }
    
    @SuppressWarnings("unused")
    private void closeChannelAndFireDoOnClosed() {
        this._channel.close();
        fireDoOnClosed();
    }
    
    private void doClose() {
        this._funcSelector.destroy(RxActions.toAction1_N(DefaultHttpTrade.class, "fireDoOnClosed"));
    }

    private void fireDoOnClosed() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("closing active trade[channel: {}] with isResponseCompleted({})/isEndedWithKeepAlive({})", 
                    this._channel, this._isResponseCompleted.get(), this.isEndedWithKeepAlive());
        }
        this._inboundSupport.fireAllSubscriberUnactive();
        this._terminateAwareSupport.fireAllTerminates();
    }

    private final TerminateAwareSupport<HttpTrade, DefaultHttpTrade> 
        _terminateAwareSupport;
    private final InboundEndpointSupport<DefaultHttpTrade> 
        _inboundSupport;
    
    private final long _createTimeMillis = System.currentTimeMillis();
    private final Channel _channel;
    private final TrafficCounter _trafficCounter;
    private String _requestMethod;
    private String _requestUri;
    private final AtomicBoolean _isRequestReceived = new AtomicBoolean(false);
    private final AtomicBoolean _isRequestCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSetted = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseSended = new AtomicBoolean(false);
    private final AtomicBoolean _isResponseCompleted = new AtomicBoolean(false);
    private final AtomicBoolean _isKeepAlive = new AtomicBoolean(false);
}
