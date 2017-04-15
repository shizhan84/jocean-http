package org.jocean.http;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.impl.DefaultHttpClient;
import org.jocean.http.util.APPLY;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import rx.Observable;
import rx.functions.Action0;
import rx.functions.Func1;

public class SslDemo {

    private static final Logger LOG =
            LoggerFactory.getLogger(SslDemo.class);
    
    public static void main(String[] args) throws Exception {
        
        final SslContext sslCtx = SslContextBuilder.forClient().build();
        final Feature sslfeature = new Feature.ENABLE_SSL(sslCtx);
        
        try (final HttpClient client = new DefaultHttpClient()) {
            {
                final String host = "www.sina.com.cn";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
//                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);
              
                final String content = sendRequestAndRecv(client, request, host,
//                        , sslfeature
                        Feature.ENABLE_LOGGING,
                        Feature.ENABLE_COMPRESSOR
                        );
//                LOG.info("recv:{}", content);
            }
            /*
            {
                final String host = "www.alipay.com";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);
              
                LOG.info("recv:{}", sendRequestAndRecv(client, request, host, sslfeature));
            }
            */
            /*
            {
                final String host = "github.com";

                final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_0, HttpMethod.GET, "/isdom");
                HttpUtil.setKeepAlive(request, true);
                request.headers().set(HttpHeaderNames.HOST, host);

                LOG.debug("send request:{}", request);
              
                LOG.info("recv:{}", sendRequestAndRecv(client, request, host, sslfeature));
            }
            */
        }
    }

    private static String sendRequestAndRecv(final HttpClient client,
            final DefaultFullHttpRequest request, 
            final String host,
            final Feature... features) {
        final HttpInitiator initiator =
                client.initiator().remoteAddress(new InetSocketAddress(host, 80 /*443*/))
                .feature(features)
                .build()
                .toBlocking()
                .single();
        
        final TrafficCounter counter = initiator.enable(APPLY.TRAFFICCOUNTER);
        final String resp1 = sendAndRecv(initiator, request).toBlocking().single();
        LOG.debug("1 interaction: {}", resp1);
        
        final String resp2 = sendAndRecv(initiator, request).toBlocking().single();
        LOG.debug("2 interaction: {}", resp2);
        
        LOG.debug("upload {}/download {}", counter.outboundBytes(), counter.inboundBytes());
        initiator.close();
        return resp1 /* + resp2 */;
        
//        .flatMap(new Func1<HttpInitiator, Observable<String>>() {
//            @Override
//            public Observable<String> call(final HttpInitiator initiator) {
//                final TrafficCounter counter = initiator.enable(APPLY.TRAFFICCOUNTER);
//                
//                final Observable<String> respContent = sendAndRecv(initiator, request);
//                return respContent.doOnUnsubscribe(new Action0() {
//                    @Override
//                    public void call() {
//                        LOG.debug("upload {}/download {}", counter.outboundBytes(), counter.inboundBytes());
////                        initiator.close();
//                    }});
//            }})
//        .toBlocking().single();
    }

    private static Observable<String> sendAndRecv(
            final HttpInitiator initiator,
            final DefaultFullHttpRequest request) {
        final HttpMessageHolder holder = new HttpMessageHolder();
        initiator.doOnTerminate(holder.closer());
        return initiator.defineInteraction(Observable.just(request))
            .compose(holder.assembleAndHold())
            .last().map(new Func1<HttpObject, String>() {
                @Override
                public String call(HttpObject t) {
                    final FullHttpResponse resp = holder.fullOf(RxNettys.BUILD_FULL_RESPONSE).call();
                    try {
                        return new String(Nettys.dumpByteBufAsBytes(resp.content()), Charsets.UTF_8);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return null;
                    } finally {
                        resp.release();
                    }
                }});
    }

}
