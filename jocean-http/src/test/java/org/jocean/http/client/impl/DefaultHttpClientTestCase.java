package org.jocean.http.client.impl;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jocean.http.Feature.ENABLE_LOGGING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLException;

import org.jocean.http.Feature.ENABLE_SSL;
import org.jocean.http.client.Outbound.InteractionMeterFeature;
import org.jocean.http.server.HttpTestServer;
import org.jocean.http.server.HttpTestServerHandler;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.OnNextSensor;
import org.jocean.idiom.rx.RxFunctions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Subscription;
import rx.functions.Func0;
import rx.observers.TestSubscriber;

public class DefaultHttpClientTestCase {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultHttpClientTestCase.class);

    final static SslContext sslCtx;
    static {
        sslCtx = initSslCtx();
    }

    @SuppressWarnings("deprecation")
    private static SslContext initSslCtx() {
        try {
            return SslContext.newClientContext(InsecureTrustManagerFactory.INSTANCE);
        } catch (SSLException e) {
            return null;
        }
    }
    
    private HttpTestServer createTestServerWithDefaultHandler(
            final boolean enableSSL, 
            final String acceptId) 
            throws Exception {
        return new HttpTestServer(
                enableSSL, 
                new LocalAddress(acceptId), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                HttpTestServer.DEFAULT_NEW_HANDLER);
    }

    private HttpTestServer createTestServerWith(
            final boolean enableSSL, 
            final String acceptId,
            final Func0<ChannelInboundHandler> newHandler) 
            throws Exception {
        return new HttpTestServer(
                enableSSL, 
                new LocalAddress(acceptId), 
                new LocalEventLoopGroup(1), 
                new LocalEventLoopGroup(),
                LocalServerChannel.class,
                newHandler);
    }
    
    private DefaultFullHttpRequest fullHttpRequest() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    }

    //  Happy Path
    @Test
    public void testHttpHappyPathOnce() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), ENABLE_LOGGING);
        try {
        
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(fullHttpRequest()))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttpHappyPathOnceAndCheckRefCount() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final ByteBuf content = Unpooled.buffer(0);
        content.writeBytes("test content".getBytes("UTF-8"));
        final DefaultFullHttpRequest request = 
                new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", content);
        
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), 
                ENABLE_LOGGING);
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(request))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            ReferenceCountUtil.release(request);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.stop();
        }
        
        assertEquals(0, request.refCnt());
    }
    
    @Test
    public void testHttpsHappyPathOnce() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");

        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), 
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(fullHttpRequest()))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpHappyPathKeepAliveReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final TestChannelCreator creator = new TestChannelCreator();
    
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannels(1);
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpHappyPathKeepAliveReuseConnectionTwice() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final TestChannelCreator creator = new TestChannelCreator();
    
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannelsAndReset(1, 1);
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannelsAndReset(1, 1);
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
            // third
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannelsAndReset(1, 1);
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpsHappyPathKeepAliveReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");

        final TestChannelCreator creator = new TestChannelCreator();
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        
        try {
            // first 
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                //  await for 1 second
                pool.awaitRecycleChannels(1);
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            //  try wait for close
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpOnErrorBeforeSend1stAnd2ndHappyPathKeepAliveReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final TestChannelCreator creator = new TestChannelCreator();
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        
        try {
            // first 
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                try {
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.<HttpObject>error(new RuntimeException("test error")))
                    .compose(RxNettys.objects2httpobjs())
                    .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                    .subscribe(testSubscriber);
                    unsubscribed.await();
                    
                    //  await for 1 second
                    pool.awaitRecycleChannels(1);
                } finally {
                    assertEquals(1, testSubscriber.getOnErrorEvents().size());
                    assertEquals(RuntimeException.class, 
                            testSubscriber.getOnErrorEvents().get(0).getClass());
                    assertEquals(0, testSubscriber.getOnCompletedEvents().size());
                    assertEquals(0, testSubscriber.getOnNextEvents().size());
                }
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttpSendingError1stAnd2ndHappyPathNotReuseConnection() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        /*
            createTestServerWith(false, "test", new Func0<ChannelInboundHandler>() {
            @Override
            public ChannelInboundHandler call() {
                return new HttpTestServerHandler() {
                    @Override
                    public void channelActive(final ChannelHandlerContext ctx)
                            throws Exception {
                        ctx.channel().close();
                    }

                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx,
                            HttpObject msg) throws Exception {
                    }};
            }});
            */

        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setWriteException(new RuntimeException("write error"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        
        try {
            // first 
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                try {
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                    .subscribe(testSubscriber);
                    unsubscribed.await();
                    
                    //  await for 1 second
                    pool.awaitRecycleChannels(1);
                } finally {
                    assertEquals(1, testSubscriber.getOnErrorEvents().size());
                    assertEquals(RuntimeException.class, 
                            testSubscriber.getOnErrorEvents().get(0).getClass());
                    assertEquals(0, testSubscriber.getOnCompletedEvents().size());
                    assertEquals(0, testSubscriber.getOnNextEvents().size());
                }
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
            creator.reset();
            //  reset write exception
            creator.setWriteException(null);
            
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(2, creator.getChannels().size());
            creator.getChannels().get(1).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
        }
    }
    
    //  all kinds of exception
    //  Not connected
    @Test
    public void testHttpForNotConnected() throws Exception {
        
        final CountDownLatch unsubscribed = new CountDownLatch(1);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator, ENABLE_LOGGING);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            client.defineInteraction(new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            
            unsubscribed.await();
            
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }

    @Test
    public void testInteractionMeterWhenHttpAndNotConnected() throws Exception {
        
        final CountDownLatch unsubscribed = new CountDownLatch(1);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator, ENABLE_LOGGING);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final InteractionMeterFeature meter = HttpClientUtil.buildInteractionMeter();
            
            client.defineInteraction(new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor),
                meter
                )
                .compose(RxNettys.objects2httpobjs())
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
            
            unsubscribed.await();
            
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
            assertEquals(0, meter.uploadBytes());
            assertEquals(0, meter.downloadBytes());
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testInteractionMeterWhenHttpHappyPathOnce() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");

        final InteractionMeterFeature meter = HttpClientUtil.buildInteractionMeter();
        final DefaultHttpClient client = new DefaultHttpClient(new TestChannelCreator(), 
                ENABLE_LOGGING,
                meter);
        try {
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(fullHttpRequest()))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            assertTrue(0 < meter.uploadBytes());
            assertTrue(0 < meter.downloadBytes());
            LOG.debug("meter.uploadBytes: {}", meter.uploadBytes());
            LOG.debug("meter.downloadBytes: {}", meter.downloadBytes());
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpForUnsubscribedBeforeSubscribe() throws Exception {
        
        final CountDownLatch unsubscribed = new CountDownLatch(1);
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator, ENABLE_LOGGING);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            testSubscriber.unsubscribe();
            
            client.defineInteraction(new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            
            unsubscribed.await();
            
            assertEquals(0, creator.getChannels().size());
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }

    @Test
    public void testHttpsNotConnected() throws Exception {
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        //  NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            // await for unsubscribed
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            nextSensor.assertNotCalled();
        }
    }

    @Test
    public void testHttpsNotShakehand() throws Exception {
        // http server
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.stop();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(NotSslRecordException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testHttpEmitExceptionWhenConnecting() throws Exception {
        final String errorMsg = "connecting failure";
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setConnectException(new RuntimeException(errorMsg));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator);
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            assertFalse(creator.getChannels().get(0).isActive());
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(errorMsg, 
                    testSubscriber.getOnErrorEvents().get(0).getMessage());
            //  channel not connected, so no message send
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testHttpsEmitExceptionWhenConnecting() throws Exception {
        final String errorMsg = "connecting failure";
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setConnectException(new RuntimeException(errorMsg));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                new ENABLE_SSL(sslCtx));
        //    NOT setup server for local channel
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(errorMsg, 
                    testSubscriber.getOnErrorEvents().get(0).getMessage());
            //  channel not connected, so no message send
            nextSensor.assertNotCalled();
        }
    }
    
    //  connected but meet error
    @Test
    public void testHttpDisconnectFromServerAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Func0<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() {
                        return new HttpTestServerHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    ctx.close();
                                }
                            }
                        };
                    }});
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.stop();
            testSubscriber.assertTerminalEvent();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }
    
    @Test
    public void testHttpsDisconnectFromServerAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWith(true, "test",
                new Func0<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() {
                        return new HttpTestServerHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    ctx.close();
                                }
                            }
                        };
                    }});
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.stop();
            testSubscriber.assertTerminalEvent();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }
    
    @Test
    public void testHttpClientCanceledAfterConnected() throws Exception {
        final CountDownLatch serverRecvd = new CountDownLatch(1);
        final HttpTestServer server = createTestServerWith(false, "test",
                new Func0<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() {
                        return new HttpTestServerHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    LOG.debug("recv request {}, and do nothing.", msg);
                                    serverRecvd.countDown();
                                    //  never send response
                                }
                            }
                        };
                    }});
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final Subscription subscription = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
                .compose(RxNettys.objects2httpobjs())
                .subscribe(testSubscriber);
            
            serverRecvd.await();
            
            //  server !NOT! send back
            subscription.unsubscribe();
            
            // test if close method has been called.
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.stop();
            testSubscriber.assertNoErrors();
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }

    @Test
    public void testHttpsClientCanceledAfterConnected() throws Exception {
        final CountDownLatch serverRecvd = new CountDownLatch(1);
        final HttpTestServer server = createTestServerWith(true, "test",
                new Func0<ChannelInboundHandler> () {
                    @Override
                    public ChannelInboundHandler call() {
                        return new HttpTestServerHandler() {
                            @Override
                            protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                                    throws Exception {
                                if (msg instanceof HttpRequest) {
                                    LOG.debug("recv request {}, and do nothing.", msg);
                                    serverRecvd.countDown();
                                    //  never send response
                                }
                            }
                        };
                    }});
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final Subscription subscription = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
                .compose(RxNettys.objects2httpobjs())
                .subscribe(testSubscriber);
            
            serverRecvd.await();
            
//            assertEquals(1, client.getActiveChannelCount());
            //  server !NOT! send back
            subscription.unsubscribe();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            // test if close method has been called.
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            // 注意: 一个 try-with-resources 语句可以像普通的 try 语句那样有 catch 和 finally 块。
            //  在try-with-resources 语句中, 任意的 catch 或者 finally 块都是在声明的资源被关闭以后才运行。
            client.close();
            server.stop();
//            assertEquals(0, client.getActiveChannelCount());
            testSubscriber.assertNoErrors();
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  channel connected, so message has been send
            nextSensor.assertCalled();
        }
    }
    
    @Test
    public void testHttpRequestEmitErrorAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>error(new RuntimeException("test error")))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
        }
    }

    @Test
    public void testHttpsRequestEmitErrorAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>error(new RuntimeException("test error")))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
        }
    }
    
    @Test
    public void testHttpRequestEmitErrorAfterConnectedAndReuse2nd() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            //  first
            {
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>error(new RuntimeException("test error")))
                .compose(RxNettys.objects2httpobjs())
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
                unsubscribed.await();
                testSubscriber.awaitTerminalEvent();
                
                //  await for 1 second
                pool.awaitRecycleChannels(1);
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttpsRequestEmitErrorAfterConnectedAndReuse2nd() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            //  first
            {
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>error(new RuntimeException("test error")))
                .compose(RxNettys.objects2httpobjs())
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
                unsubscribed.await();
                testSubscriber.awaitTerminalEvent();
                
                //  await for 1 second
                pool.awaitRecycleChannels(1);
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            // second
            {
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            }
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertNotClose(1);
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttpClientWriteAndFlushExceptionAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            //  no response received
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  message has been write to send queue
            nextSensor.assertCalled();
        }
    }

    @Test
    public void testHttpsClientWriteAndFlushExceptionAfterConnected() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(SSLException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            //  no response received
            assertEquals(0, testSubscriber.getOnNextEvents().size());
            //  message has been write to send queue
            nextSensor.assertNotCalled();
        }
    }
    
    @Test
    public void testHttpClientWriteAndFlushExceptionAfterConnectedAndNewConnection2nd() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(false, "test");
        
        final TestChannelCreator creator = new TestChannelCreator();
        creator.setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING);
            try {
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
                // first
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
                .compose(RxNettys.objects2httpobjs())
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
                unsubscribed.await();
                testSubscriber.awaitTerminalEvent();
                
                //  await for 1 second
                pool.awaitRecycleChannels(1);
                
                assertEquals(1, creator.getChannels().size());
                creator.getChannels().get(0).assertClosed(1);
                assertEquals(1, testSubscriber.getOnErrorEvents().size());
                assertEquals(RuntimeException.class, 
                        testSubscriber.getOnErrorEvents().get(0).getClass());
                assertEquals(0, testSubscriber.getOnCompletedEvents().size());
                //  no response received
                assertEquals(0, testSubscriber.getOnNextEvents().size());
                //  message has been write to send queue
                nextSensor.assertCalled();
            }
            // reset creator
            creator.reset();
            creator.setWriteException(null);
            
            {
                // second
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                assertEquals(2, creator.getChannels().size());
                creator.getChannels().get(1).assertNotClose(1);
            }
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttpsClientWriteAndFlushExceptionAfterConnectedAndNewConnection2nd() throws Exception {
        final HttpTestServer server = createTestServerWithDefaultHandler(true, "test");
        
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setWriteException(new RuntimeException("doWrite Error for test"));
        
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        try {
            {
                final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
                final OnNextSensor<HttpObject> nextSensor = new OnNextSensor<HttpObject>();
                final CountDownLatch unsubscribed = new CountDownLatch(1);
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.<HttpObject>just(fullHttpRequest()).doOnNext(nextSensor))
                .compose(RxNettys.objects2httpobjs())
                .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
                .subscribe(testSubscriber);
                unsubscribed.await();
                testSubscriber.awaitTerminalEvent();
                
                //  await for 1 second
                pool.awaitRecycleChannels(1);
                
                assertEquals(1, creator.getChannels().size());
                creator.getChannels().get(0).assertClosed(1);
                assertEquals(1, testSubscriber.getOnErrorEvents().size());
                assertEquals(SSLException.class, 
                        testSubscriber.getOnErrorEvents().get(0).getClass());
                assertEquals(0, testSubscriber.getOnCompletedEvents().size());
                //  no response received
                assertEquals(0, testSubscriber.getOnNextEvents().size());
                //  message has been write to send queue
                nextSensor.assertNotCalled();
            }
            // reset creator
            creator.setWriteException(null);
            {
                // second
                final Iterator<HttpObject> itr = 
                    client.defineInteraction(
                        new LocalAddress("test"), 
                        Observable.just(fullHttpRequest()))
                    .compose(RxNettys.objects2httpobjs())
                    .map(RxNettys.<HttpObject>retainer())
                    .toBlocking().toIterable().iterator();
                
                final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
                
                assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
                assertEquals(2, creator.getChannels().size());
                creator.getChannels().get(1).assertNotClose(1);
            }
        } finally {
            client.close();
            server.stop();
        }
    }
    
    @Test
    public void testHttp10ConnectionCloseHappyPath() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Func0<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_0, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  missing Content-Length
//                            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                };
            }});

        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(request))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).awaitClosed();
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttp10ConnectionCloseBadCaseMissingPartContent() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Func0<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_0, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  BAD Content-Length, actual length + 1
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, 
                                    response.content().readableBytes() + 1);
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                };
            }});
        
        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING);
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(request))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertTrue(testSubscriber.getOnNextEvents().size()>=1);
        }
    }
    
    @Test
    public void testHttps10ConnectionCloseHappyPath() throws Exception {
        final HttpTestServer server = createTestServerWith(true, "test",
                new Func0<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_0, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  missing Content-Length
//                            response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                };
            }});

        final TestChannelCreator creator = new TestChannelCreator();
        final DefaultHttpClient client = new DefaultHttpClient(creator,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        try {
            final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
            request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
            
            final Iterator<HttpObject> itr = 
                client.defineInteraction(
                    new LocalAddress("test"), 
                    Observable.just(request))
                .compose(RxNettys.objects2httpobjs())
                .map(RxNettys.<HttpObject>retainer())
                .toBlocking().toIterable().iterator();
            
            final byte[] bytes = RxNettys.httpObjectsAsBytes(itr);
            
            assertTrue(Arrays.equals(bytes, HttpTestServer.CONTENT));
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).awaitClosed();
        } finally {
            client.close();
            server.stop();
        }
    }

    @Test
    public void testHttps10ConnectionCloseBadCaseMissingPartContent() throws Exception {
        final HttpTestServer server = createTestServerWith(true, "test",
                new Func0<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_0, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  BAD Content-Length, actual length + 1
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, 
                                    response.content().readableBytes() + 1);
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                };
            }});
        
        final TestChannelCreator creator = new TestChannelCreator();
        final TestChannelPool pool = new TestChannelPool(1);
        final DefaultHttpClient client = new DefaultHttpClient(creator, pool,
                ENABLE_LOGGING,
                new ENABLE_SSL(sslCtx));
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            final CountDownLatch unsubscribed = new CountDownLatch(1);
            client.defineInteraction(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(request))
            .compose(RxNettys.objects2httpobjs())
            .compose(RxFunctions.<HttpObject>countDownOnUnsubscribe(unsubscribed))
            .subscribe(testSubscriber);
            unsubscribed.await();
            testSubscriber.awaitTerminalEvent();
            
            //  await for 1 second
            pool.awaitRecycleChannels(1);
            
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed(1);
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertTrue(testSubscriber.getOnNextEvents().size()>=1);
        }
    }
    
    /*
    @Test
    public void testHttp11KeeypAliveBadCaseMissingPartContent() throws Exception {
        final HttpTestServer server = createTestServerWith(false, "test",
                new Callable<ChannelInboundHandler> () {
            @Override
            public ChannelInboundHandler call() throws Exception {
                return new HttpTestServerHandler() {
                    @Override
                    protected void channelRead0(final ChannelHandlerContext ctx, final HttpObject msg) 
                            throws Exception {
                        if (msg instanceof HttpRequest) {
                            //  for HTTP 1.0 Connection: Close response behavior
                            final FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1, OK, 
                                    Unpooled.wrappedBuffer(HttpTestServer.CONTENT));
                            response.headers().set(CONTENT_TYPE, "text/plain");
                            //  BAD Content-Length, actual length + 1
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, 
                                    response.content().readableBytes() + 1);
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
                            ctx.write(response);
                        }
                    }
                };
            }});
        
        final CountDownLatch pauseConnecting = new CountDownLatch(1);
        @SuppressWarnings("resource")
        final TestChannelCreator creator = new TestChannelCreator()
            .setPauseConnecting(pauseConnecting);
        final DefaultHttpClient client = new DefaultHttpClient(creator);
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        
        final TestSubscriber<HttpObject> testSubscriber = new TestSubscriber<HttpObject>();
        try {
            client.sendRequest(
                new LocalAddress("test"), 
                Observable.<HttpObject>just(request),
                Feature.DisableCompress)
            .subscribe(testSubscriber);
            new TestSubscription() {{
                testSubscriber.add(this);
                pauseConnecting.countDown();
                //  wait for ever // TO fix
            }}.awaitUnsubscribed();
            testSubscriber.awaitTerminalEvent();
            assertEquals(1, creator.getChannels().size());
            creator.getChannels().get(0).assertClosed();
        } finally {
            client.close();
            server.stop();
            assertEquals(1, testSubscriber.getOnErrorEvents().size());
            assertEquals(RuntimeException.class, 
                    testSubscriber.getOnErrorEvents().get(0).getClass());
            assertEquals(0, testSubscriber.getOnCompletedEvents().size());
            assertTrue(testSubscriber.getOnNextEvents().size()>=1);
        }
    }
    */
    
    // TODO, 增加 transfer request 时, 调用 response subscriber.unsubscribe 后，write future是否会被正确取消。
}
