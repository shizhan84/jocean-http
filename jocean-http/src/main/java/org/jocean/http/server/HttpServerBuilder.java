/**
 * 
 */
package org.jocean.http.server;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;
import org.jocean.http.InboundEndpoint;
import org.jocean.http.OutboundEndpoint;
import org.jocean.http.TrafficCounter;
import org.jocean.http.util.HttpMessageHolder;
import org.jocean.idiom.TerminateAware;

import io.netty.handler.codec.http.HttpObject;
import io.netty.util.AttributeMap;
import rx.Observable;
import rx.Single;
import rx.functions.Func0;

/**
 * @author isdom
 *
 */
public interface HttpServerBuilder extends Closeable {
    
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Feature ... features);
    
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Func0<Feature[]> featuresBuilder);
    
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Func0<Feature[]> featuresBuilder,
            final Feature ... features);
    
    public interface ReadPolicy {
        public Single<?> whenToRead(final HttpTrade trade);
    }
    
    public interface HttpTrade 
        extends AutoCloseable, TerminateAware<HttpTrade>, AttributeMap {
        //  try to abort trade explicit
        public void close();
        
        public TrafficCounter trafficCounter();
        public Object transport();
        public boolean isActive();
        
        public void setReadPolicy(final ReadPolicy readPolicy);
        public long unreadDurationInMs();
        public long readingDurationInMS();
        
        public Observable<? extends HttpObject> inMessage();
        public HttpMessageHolder inMessageHolder();
        
        public OutboundEndpoint outbound();
        public InboundEndpoint inbound();
    }
}
