package org.jocean.http.client.impl;

import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.jocean.http.util.Nettys;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.rx.DoOnUnsubscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;

public class DefaultChannelPool extends AbstractChannelPool {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultChannelPool.class);

    @Override
    protected Channel reuseChannel(final SocketAddress address) {
        final Queue<Channel> channels = getChannels(address);
        if (null == channels) {
            return null;
        }
        Channel channel = null;
        do {
            channel = channels.poll();
            if (null != channel) {
                if (channel.isActive()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("fetch active channel({}) from pool, try to reuse.", channel);
                    }
                    break;
                } else {
                    LOG.info("fetch inactive channel({}) from pool, drop and fetch next from pool.", channel);
                    channel.close();
                }
            }
        } while (null != channel);
        return channel;
    }

    protected Queue<Channel> getChannels(final SocketAddress address) {
        return this._channels.get(address);
    }
    
    protected Queue<Channel> getOrCreateChannels(final SocketAddress address) {
        final Queue<Channel> channels = this._channels.get(address);
        if (null == channels) {
            final Queue<Channel> newChannels = new ConcurrentLinkedQueue<Channel>();
            final Queue<Channel> previous = this._channels.putIfAbsent(address, newChannels);
            return  null!=previous ? previous : newChannels;
        }
        else {
            return channels;
        }
    }
    
    @Override
    public boolean recycleChannel(final Channel channel) {
        if (channel.isActive() 
            && Nettys.isChannelReady(channel)) {
            final SocketAddress address = channel.remoteAddress();
            if (null!=address) {
                RxNettys.installDoOnUnsubscribe(channel, DoOnUnsubscribe.Util.UNSUBSCRIBE_NOW);
                getOrCreateChannels(address).add(channel);
                LOG.info("recycleChannel: channel({}) save to queue for ({}), can be reused.", channel, address);
                return  true;
            }
        }
        
        channel.close();
        LOG.info("recycleChannel: try recycle channel({}), BUT it has been closed.", channel);
        return false;
    }

    private final ConcurrentMap<SocketAddress, Queue<Channel>> _channels = 
            new ConcurrentHashMap<>();
}
