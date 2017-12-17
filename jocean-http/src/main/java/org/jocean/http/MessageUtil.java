package org.jocean.http;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

import org.jocean.http.client.HttpClient;
import org.jocean.http.client.HttpClient.HttpInitiator;
import org.jocean.http.client.HttpClient.InitiatorBuilder;
import org.jocean.http.util.Nettys;
import org.jocean.http.util.ParamUtil;
import org.jocean.http.util.RxNettys;
import org.jocean.idiom.DisposableWrapper;
import org.jocean.idiom.DisposableWrapperUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.ReflectUtils;
import org.jocean.idiom.Terminable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.util.ReferenceCountUtil;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;

public class MessageUtil {
    private static final Logger LOG =
            LoggerFactory.getLogger(MessageUtil.class);
    
    private MessageUtil() {
        throw new IllegalStateException("No instances!");
    }

    public interface InteractionBuilder {
        
        public InteractionBuilder method(final HttpMethod method);
        
        public InteractionBuilder uri(final String uri);
        
        public InteractionBuilder path(final String path);
        
        public InteractionBuilder paramAsQuery(final String key, final String value);
        
        public InteractionBuilder reqbean(final Object... reqbeans);
        
        public InteractionBuilder feature(final Feature... features);
        
        public <RESP> Observable<? extends RESP> responseAs(final Class<RESP> resptype, Func2<ByteBuf, Class<RESP>, RESP> decoder);
        public Observable<? extends DisposableWrapper<HttpObject>> responseAs(final Terminable terminable);
    }
    
    public static InteractionBuilder interaction(final HttpClient client) {
        final InitiatorBuilder _initiatorBuilder = client.initiator();
        final AtomicBoolean _isAddrSetted = new AtomicBoolean(false);
        final AtomicReference<Observable<Object>> _obsreqRef = new AtomicReference<>(
                fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET));
        final List<String> _nvs = new ArrayList<>();
        final AtomicReference<URI> uriRef = new AtomicReference<>();
        
        return new InteractionBuilder() {
            private void updateObsRequest(final Action1<Object> action) {
                _obsreqRef.set(_obsreqRef.get().doOnNext(action));
            }

            private void addQueryParams() {
                if (!_nvs.isEmpty()) {
                    updateObsRequest(MessageUtil.paramsAsQuery(_nvs.toArray(new String[0])));
                }
            }
            
            private void extractUriWithHost(final Object...reqbeans) {
                if (null == uriRef.get()) {
                    for (Object bean : reqbeans) {
                        try {
                            final Path path = bean.getClass().getAnnotation(Path.class);
                            if (null != path) {
                                final URI uri = new URI(path.value());
                                if (null != uri.getHost()) {
                                    uriRef.set(uri);
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            LOG.warn("exception when extract uri from bean {}, detail: {}", 
                                    bean, ExceptionUtils.exception2detail(e));
                        }
                    }
                }
            }

            private Observable<? extends HttpInitiator> initiator() {
                if (_isAddrSetted.get()) {
                    return _initiatorBuilder.build();
                } else {
                    if (null != uriRef.get()) {
                        return _initiatorBuilder.remoteAddress(uri2addr(uriRef.get())).build();
                    } else {
                        return Observable.error(new RuntimeException("remote address not set."));
                    }
                }
            }
            
            @Override
            public InteractionBuilder method(final HttpMethod method) {
                updateObsRequest(MessageUtil.method(method));
                return this;
            }

            @Override
            public InteractionBuilder uri(final String uriAsString) {
                try {
                    final URI uri = new URI(uriAsString);
                    _initiatorBuilder.remoteAddress(uri2addr(uri));
                    _isAddrSetted.set(true);
                    updateObsRequest(MessageUtil.host(uri));
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                return this;
            }

            @Override
            public InteractionBuilder path(final String path) {
                updateObsRequest(MessageUtil.path(path));
                return this;
            }

            @Override
            public InteractionBuilder paramAsQuery(final String name, final String value) {
                _nvs.add(name);
                _nvs.add(value);
                return this;
            }

            @Override
            public InteractionBuilder reqbean(final Object... reqbeans) {
                updateObsRequest(MessageUtil.request(reqbeans));
                extractUriWithHost(reqbeans);
                return this;
            }

            @Override
            public InteractionBuilder feature(final Feature... features) {
                _initiatorBuilder.feature(features);
                return this;
            }

            @Override
            public <RESP> Observable<? extends RESP> responseAs(final Class<RESP> resptype,
                    final Func2<ByteBuf, Class<RESP>, RESP> decoder) {
                addQueryParams();
                return initiator().flatMap(new Func1<HttpInitiator, Observable<? extends RESP>>() {
                    @Override
                    public Observable<? extends RESP> call(final HttpInitiator initiator) {
                        return initiator.defineInteraction(_obsreqRef.get())
                                .compose(RxNettys.message2fullresp(initiator, true))
                                .map(new Func1<DisposableWrapper<FullHttpResponse>, RESP>() {
                                    @Override
                                    public RESP call(final DisposableWrapper<FullHttpResponse> dwresp) {
                                        try {
                                            return decoder.call(dwresp.unwrap().content(), resptype);
                                        } finally {
                                            dwresp.dispose();
                                        }
                                    }
                                }).doOnUnsubscribe(initiator.closer());
                    }
                });
            }

            @Override
            public Observable<? extends DisposableWrapper<HttpObject>> responseAs(final Terminable terminable) {
                addQueryParams();
                return initiator().flatMap(new Func1<HttpInitiator, Observable<? extends DisposableWrapper<HttpObject>>>() {
                            @Override
                            public Observable<? extends DisposableWrapper<HttpObject>> call(final HttpInitiator initiator) {
                                terminable.doOnTerminate(initiator.closer());
                                return initiator.defineInteraction(_obsreqRef.get());
                            }
                        });
            }};
    }
    
    public static SocketAddress uri2addr(final URI uri) {
        final int port = -1 == uri.getPort() ? ( "https".equals(uri.getScheme()) ? 443 : 80 ) : uri.getPort();
        return new InetSocketAddress(uri.getHost(), port);
    }

    public static SocketAddress bean2addr(final Object bean) {
        final Path path = bean.getClass().getAnnotation(Path.class);
        if (null!=path) {
            try {
                return uri2addr(new URI(path.value()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("bean class ("+ bean.getClass() +") without @Path annotation");
    }
    
    public static Action1<Object> method(final HttpMethod method) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    ((HttpRequest)obj).setMethod(method);
                }
            }};
    }
    
    public static Action1<Object> host(final URI uri) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (null != uri && null != uri.getHost() && obj instanceof HttpRequest) {
                    ((HttpRequest)obj).headers().set(HttpHeaderNames.HOST, uri.getHost());
                }
            }};
    }
    
    public static Action1<Object> path(final String path) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (null != path && !path.isEmpty() && obj instanceof HttpRequest) {
                    ((HttpRequest)obj).setUri(path);
                }
            }};
    }
    
    public static Action1<Object> paramsAsQuery(final String... nvs) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    final HttpRequest request = (HttpRequest)obj;
                    if (nvs.length > 0) {
                        final QueryStringEncoder encoder = new QueryStringEncoder(request.uri());
                        int idx = 0;
                        while (idx+1 < nvs.length) {
                            encoder.addParam(nvs[idx], nvs[idx+1]);
                        }
                        request.setUri(encoder.toString());
                    }
                }
            }};
    }
    
    public static Action1<Object> request(final Object... beans) {
        return new Action1<Object>() {
            @Override
            public void call(final Object obj) {
                if (obj instanceof HttpRequest) {
                    final HttpRequest request = (HttpRequest)obj;
                    for (Object bean : beans) {
                        setUriToRequest(request, bean);
                        addQueryParams(request, bean);
                        addHeaderParams(request, bean);
                    }
                }
            }};
    }
    
    static void setUriToRequest(final HttpRequest request, final Object bean) {
        final Path apath = bean.getClass().getAnnotation(Path.class);
        if (null!=apath) {
            try {
                final URI uri = new URI(apath.value());
                if (null != uri.getHost() && null == request.headers().get(HttpHeaderNames.HOST)) {
                    request.headers().set(HttpHeaderNames.HOST, uri.getHost());
                }
                
                if (null != uri.getRawPath() && request.uri().isEmpty()) {
                    request.setUri(uri.getRawPath());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void addHeaderParams(final HttpRequest request, final Object bean) {
        final Field[] headerFields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), HeaderParam.class);
        if ( headerFields.length > 0 ) {
            for ( Field field : headerFields ) {
                try {
                    final Object value = field.get(bean);
                    if ( null != value ) {
                        final String headername = field.getAnnotation(HeaderParam.class).value();
                        request.headers().set(headername, value);
                    }
                } catch (Exception e) {
                    LOG.warn("exception when get value from field:[{}], detail:{}",
                            field, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    private static void addQueryParams(final HttpRequest request, final Object bean) {
        final Field[] queryFields = ReflectUtils.getAnnotationFieldsOf(bean.getClass(), QueryParam.class);
        if ( queryFields.length > 0 ) {
            final QueryStringEncoder encoder = new QueryStringEncoder(request.uri());
            for (Field field : queryFields) {
                try {
                    final Object value = field.get(bean);
                    if ( null != value ) {
                        final String paramkey = field.getAnnotation(QueryParam.class).value();
                        encoder.addParam(paramkey, String.valueOf(value));
                    }
                }
                catch (Exception e) {
                    LOG.warn("exception when get field({})'s value, detail:{}", 
                            field, ExceptionUtils.exception2detail(e));
                }
            }
            
            request.setUri(encoder.toString());
        }
    }

    public static Observable<Object> fullRequestWithoutBody(final HttpVersion version, final HttpMethod method) {
        return Observable.<Object>just(new DefaultHttpRequest(version, method, ""), LastHttpContent.EMPTY_LAST_CONTENT);
    }
    
    public static Observable<Object> fullRequest(final Object... beans) {
        return fullRequestWithoutBody(HttpVersion.HTTP_1_1, HttpMethod.GET).doOnNext(MessageUtil.request(beans));
    }
    
    private final static Transformer<DisposableWrapper<HttpObject>, MessageBody> _AS_BODY = new Transformer<DisposableWrapper<HttpObject>, MessageBody>() {
        @Override
        public Observable<MessageBody> call(final Observable<DisposableWrapper<HttpObject>> dwhs) {
            final Observable<? extends DisposableWrapper<HttpObject>> cached = dwhs.cache();
            return cached.map(DisposableWrapperUtil.<HttpObject>unwrap()).compose(RxNettys.asHttpMessage())
                    .map(new Func1<HttpMessage, MessageBody>() {
                        @Override
                        public MessageBody call(final HttpMessage msg) {
                            return new MessageBody() {
                                @Override
                                public String contentType() {
                                    return msg.headers().get(HttpHeaderNames.CONTENT_TYPE);
                                }

                                @Override
                                public int contentLength() {
                                    return HttpUtil.getContentLength(msg, -1);
                                }

                                @Override
                                public Observable<? extends DisposableWrapper<ByteBuf>> content() {
                                    return cached.flatMap(RxNettys.message2body());
                                }
                            };
                        }
                    });
        }
    };
        
    public static Transformer<DisposableWrapper<HttpObject>, MessageBody> asMessageBody() {
        return _AS_BODY;
    }
    
    public static <T> Observable<? extends T> decodeAs(final MessageBody body, final Class<T> type) {
        if (null != body.contentType()) {
            if (body.contentType().startsWith(HttpHeaderValues.APPLICATION_JSON.toString())) {
                return decodeJsonAs(body, type);
            } else if (body.contentType().startsWith("application/xml") || body.contentType().startsWith("text/xml")) {
                return decodeXmlAs(body, type);
            }
        }
        return Observable.error(new RuntimeException("can't decodeAs type:" + type));
    }

    public static <T> Observable<? extends T> decodeJsonAs(final MessageBody body, final Class<T> type) {
        return decodeContentAs(body.content(), new Func2<ByteBuf, Class<T>, T>() {
            @Override
            public T call(final ByteBuf buf, Class<T> clazz) {
                return ParamUtil.parseContentAsJson(buf, clazz);
            }
        }, type);
    }

    public static <T> Observable<? extends T> decodeXmlAs(final MessageBody body, final Class<T> type) {
        return decodeContentAs(body.content(), new Func2<ByteBuf, Class<T>, T>() {
            @Override
            public T call(final ByteBuf buf, Class<T> clazz) {
                return ParamUtil.parseContentAsXml(buf, clazz);
            }
        }, type);
    }

    // @Override
    // public <T> Observable<? extends T> decodeFormAs(final MessageUnit mu,
    // final Class<T> type) {
    // return Observable.error(new UnsupportedOperationException());
    // }
    private static <T> Observable<? extends T> decodeContentAs(
            final Observable<? extends DisposableWrapper<ByteBuf>> content, final Func2<ByteBuf, Class<T>, T> func,
            final Class<T> type) {
        return content.map(DisposableWrapperUtil.<ByteBuf>unwrap()).toList().map(new Func1<List<ByteBuf>, T>() {
            @Override
            public T call(final List<ByteBuf> bufs) {
                final ByteBuf buf = Nettys.composite(bufs);
                try {
                    return func.call(buf, type);
                } finally {
                    ReferenceCountUtil.release(buf);
                }
            }
        });
    }
}
