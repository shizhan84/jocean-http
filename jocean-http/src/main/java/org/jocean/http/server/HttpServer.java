/**
 * 
 */
package org.jocean.http.server;

import java.io.Closeable;
import java.net.SocketAddress;

import org.jocean.http.Feature;

import rx.Observable;

/**
 * @author isdom
 *
 */
public interface HttpServer extends Closeable {
    public Observable<? extends HttpTrade> defineServer(
            final SocketAddress localAddress, 
            final Feature ... features);
}
