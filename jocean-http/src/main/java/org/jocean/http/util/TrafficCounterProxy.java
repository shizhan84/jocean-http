/**
 * 
 */
package org.jocean.http.util;

import java.util.concurrent.atomic.AtomicReference;

import org.jocean.http.Feature;
import org.jocean.http.TrafficCounter;

/**
 * @author isdom
 *
 */
class TrafficCounterProxy extends Feature.AbstractFeature0 
    implements Feature.TrafficCounterFeature, TrafficCounterAware {

    @Override
    public String toString() {
        return "TRAFFIC_COUNTER";
    }
    
    /* (non-Javadoc)
     * @see org.jocean.http.client.InteractionMeter#uploadBytes()
     */
    @Override
    public long uploadBytes() {
        final TrafficCounter impl = this._ref.get();
        
        return null != impl ? impl.uploadBytes() : 0;
    }

    /* (non-Javadoc)
     * @see org.jocean.http.client.InteractionMeter#downloadBytes()
     */
    @Override
    public long downloadBytes() {
        final TrafficCounter impl = this._ref.get();
        
        return null != impl ? impl.downloadBytes() : 0;
    }

    public void setTrafficCounter(final TrafficCounter ref) {
        this._ref.set(ref);
    }
    
    private final AtomicReference<TrafficCounter> _ref = 
            new AtomicReference<TrafficCounter>();
}