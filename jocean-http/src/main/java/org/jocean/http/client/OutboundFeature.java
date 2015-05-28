package org.jocean.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;

import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.http.util.Nettys.ToOrdinal;
import org.jocean.http.util.Oneoff;
import org.jocean.idiom.InterfaceUtils;
import org.jocean.idiom.JOArrays;
import org.jocean.idiom.rx.RxFunctions;

import rx.Subscription;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.FuncN;
import rx.functions.Functions;

public enum OutboundFeature {
    LOGGING(RxFunctions.<ChannelHandler>fromConstant(new LoggingHandler())),
    PROGRESSIVE(Functions.fromFunc(Nettys.PROGRESSIVE_FUNC3)),
    CLOSE_ON_IDLE(Functions.fromFunc(Nettys.CLOSE_ON_IDLE_FUNC2)),
    ENABLE_SSL(Functions.fromFunc(Nettys.SSL_FUNC2)),
    HTTPCLIENT_CODEC(Nettys.HTTPCLIENT_CODEC_FUNCN),
    CONTENT_DECOMPRESSOR(Nettys.CONTENT_DECOMPRESSOR_FUNCN),
    CHUNKED_WRITER(Nettys.CHUNKED_WRITER_FUNCN),
    READY4INTERACTION_NOTIFIER(Functions.fromFunc(Nettys.READY4INTERACTION_NOTIFIER_FUNC3)),
    WORKER(Functions.fromFunc(Nettys.HTTPCLIENT_WORK_FUNC3)),
    LAST_FEATURE(null)
    ;
    
    public static void applyNononeoffFeatures(
            final Channel channel,
            final Applicable[] features) {
        final Applicable applicable = 
                InterfaceUtils.compositeExcludeType(features, 
                        Applicable.class, OneoffApplicable.class);
        if (null!=applicable) {
            applicable.call(channel);
        }
    }

    public static Subscription applyOneoffFeatures(
            final Channel channel,
            final Applicable[] features) {
        final Func0<String[]> diff = Nettys.namesDifferenceBuilder(channel);
        final Applicable applicable = 
                InterfaceUtils.compositeIncludeType(features, OneoffApplicable.class);
        if (null!=applicable) {
            applicable.call(channel);
        }
        return RxNettys.removeHandlersSubscription(channel, diff.call());
    }

    public static boolean isSSLEnabled(final ChannelPipeline pipeline) {
        return (pipeline.names().indexOf(ENABLE_SSL.name()) > -1);
    }
    
    public static boolean isReadyForInteraction(final ChannelPipeline pipeline) {
        return (pipeline.names().indexOf(READY4INTERACTION_NOTIFIER.name()) == -1);
    }
    
    public interface Applicable extends Func1<Channel, ChannelHandler> {
    };
    
    public interface OneoffApplicable extends Applicable, Oneoff {
    };
    
    public static final Applicable[] EMPTY_APPLICABLES = new Applicable[0];

    public interface ApplyToRequest {
        public void applyToRequest(final HttpRequest request);
    }
    
    private abstract static class CLS_APPLY_CONTENT_DECOMPRESSOR implements OneoffApplicable, ApplyToRequest {
    }
    
    public static final Applicable APPLY_CONTENT_DECOMPRESSOR = new CLS_APPLY_CONTENT_DECOMPRESSOR() {
        @Override
        public ChannelHandler call(final Channel channel) {
            return CONTENT_DECOMPRESSOR.applyTo(channel);
        }
        @Override
        public void applyToRequest(final HttpRequest request) {
            HttpHeaders.addHeader(request,
                    HttpHeaders.Names.ACCEPT_ENCODING, 
                    HttpHeaders.Values.GZIP + "," + HttpHeaders.Values.DEFLATE);
        }
    };
    
    public static final Applicable APPLY_LOGGING = new OneoffApplicable() {
        @Override
        public ChannelHandler call(final Channel channel) {
            return LOGGING.applyTo(channel);
        }
    };
            
    public static final class APPLY_SSL implements Applicable {
        public APPLY_SSL(final SslContext sslCtx) {
            this._sslCtx = sslCtx;
        }
        
        @Override
        public ChannelHandler call(final Channel channel) {
            return ENABLE_SSL.applyTo(channel, this._sslCtx);
        }
        
        private final SslContext _sslCtx;
    }
    
    public static final class APPLY_CLOSE_ON_IDLE implements OneoffApplicable {
        public APPLY_CLOSE_ON_IDLE(final int allIdleTimeout) {
            this._allIdleTimeout = allIdleTimeout;
        }
        
        @Override
        public ChannelHandler call(final Channel channel) {
            return CLOSE_ON_IDLE.applyTo(channel, this._allIdleTimeout);
        }
        
        private final int _allIdleTimeout;
    }
    
    public ChannelHandler applyTo(final Channel channel, final Object ... args) {
        if (null==this._factory) {
            throw new UnsupportedOperationException("ChannelHandler's factory is null");
        }
        return Nettys.insertHandler(
            channel.pipeline(),
            this.name(), 
            this._factory.call(JOArrays.addFirst(args, channel, Object[].class)), 
            TO_ORDINAL);
    }

    public static final ToOrdinal TO_ORDINAL = Nettys.ordinal(OutboundFeature.class);
    
    private OutboundFeature(final FuncN<ChannelHandler> factory) {
        this._factory = factory;
    }

    private final FuncN<ChannelHandler> _factory;
}
