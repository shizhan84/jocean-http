package org.jocean.http.endpoint.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jocean.http.Interact;
import org.jocean.http.TypedSPI;
import org.jocean.http.endpoint.Endpoint;
import org.jocean.http.endpoint.EndpointSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;

public class DefaultEndpointSet implements EndpointSet {

    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultEndpointSet.class);

    private static final String[] EMPTY_STRS = new String[0];

    public DefaultEndpointSet(final Collection<Endpoint> endpoints) {
        this._endpoints = endpoints;
    }

    @Override
    public String[] uris(final String type) {
        final List<String> uris = new ArrayList<>();

        for (final Endpoint endpoint : this._endpoints) {
            if (endpoint.type().equals(type)) {
                uris.add(endpoint.uri());
            }
        }

        return uris.toArray(EMPTY_STRS);
    }

    @Override
    public Transformer<Interact, Interact> of(final TypedSPI spi) {
        return new Transformer<Interact, Interact>() {
            @Override
            public Observable<Interact> call(final Observable<Interact> interacts) {
                return interacts.doOnNext(new Action1<Interact>() {
                    @Override
                    public void call(final Interact interact) {
                        final String[] uris = uris(spi.type());
                        if (uris.length == 0) {
                            LOG.warn("no valid endpoint for service [{}]", spi.type());
                            throw new RuntimeException("no valid endpoint for service [" + spi.type() + "]");
                        }
                        interact.uri(selectURI(uris));
                    }});
            }};
    }

    private String selectURI(final String[] uris) {
        return uris[(int)Math.floor(Math.random() * uris.length)];
    }

    private final Collection<Endpoint> _endpoints;
}
