package ru.alepar.rpc.api;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.jboss.netty.handler.codec.serialization.ClassResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alepar.rpc.common.BossThreadFactory;
import ru.alepar.rpc.common.PrimitiveTypesClassResolver;
import ru.alepar.rpc.common.Validator;
import ru.alepar.rpc.common.WorkerThreadFactory;
import ru.alepar.rpc.server.FactoryServerProvider;
import ru.alepar.rpc.server.InjectingServerProvider;
import ru.alepar.rpc.server.NettyRpcServer;
import ru.alepar.rpc.server.ServerProvider;
import ru.alepar.rpc.server.SimpleServerProvider;

import static java.util.Collections.unmodifiableMap;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.jboss.netty.handler.codec.serialization.ClassResolvers.softCachingConcurrentResolver;

public class NettyRpcServerBuilder {

    private final InetSocketAddress bindAddress;

    private final Validator validator = new Validator();
    private final Map<Class<?>, ServerProvider<?>> implementations = new HashMap<Class<?>, ServerProvider<?>>();
    private final List<ExceptionListener> exceptionListeners = new ArrayList<ExceptionListener>();
    private final List<ClientListener> clientListeners = new ArrayList<ClientListener>();

    private ClassResolver classResolver = softCachingConcurrentResolver(null);
    private ExecutorService bossExecutor = newCachedThreadPool(new BossThreadFactory());
    private ExecutorService workerExecutor = newCachedThreadPool(new WorkerThreadFactory());
    private long keepAlive = 30000l;

    /**
     * @param bindAddress local address to bind to
     */
    public NettyRpcServerBuilder(InetSocketAddress bindAddress) {
        this.bindAddress = bindAddress;

        new ExceptionListener() {
            private final Logger log = LoggerFactory.getLogger(NettyRpcServer.class);
            @Override
            public void onExceptionCaught(Remote remote, Exception e) {
                log.error("server caught an exception from " + remote.toString(), e);
            }
        };
    }

    /**
     * add object, that will serve client requests <br/>
     * this instance will be shared by all clients, meaning that this object should be multithread-safe <br/>
     * @param interfaceClass interface that will be exposed on remote side
     * @param implementingObject object, that will handle all remote invocations
     * @param <T> interface, all parameters in all methods must be serializable, return type must be void
     * @return this builder
     */
    public <T> NettyRpcServerBuilder addObject(Class<T> interfaceClass, T implementingObject) {
        validator.validateInterface(interfaceClass);
        implementations.put(interfaceClass, new SimpleServerProvider<T>(implementingObject));
        return this;
    }

    /**
     * add class, that will serve client requests <br/>
     * each client will get it's own instance of this class <br/>
     * so you don't need to worry about multithread-safeness <br/>
     * and you can keep some state related to current client here <br/>
     * <br/>
     * you can also define constructor, which accepts Remote param, annotated with @Inject <br/>
     * this way you can get Remote for communicating with client which is served by current instance <br/>
     * e.g.: <br/>
     * <blockquote><pre>
     * public SomeImplClass(@Inject Remote remote) {
     *     this.remote = remote
     * }</blockquote></pre><br/>
     * @param interfaceClass interface that will be exposed on remote side
     * @param implClass class, that will be instantiated to serve client's requests
     * @param <T> interface, all parameters in all methods must be serializable, return type must be void
     * @return this builder
     */
    public <T> NettyRpcServerBuilder addClass(Class<T> interfaceClass, Class<? extends T> implClass) {
        validator.validateInterface(interfaceClass);
        implementations.put(interfaceClass, new InjectingServerProvider<T>(implClass));
        return this;
    }

    /**
     * add factory, which will create objects, that will serve client requests <br/>
     * each client will get it's own instance, created by this factory <br/>
     * so you don't need to worry about multithread-safeness of instances, created by this factory <br/>
     * and you can keep some state related to current client there <br/>
     * @param interfaceClass interface that will be exposed on remote side
     * @param factory factory, which will create objects, that will serve client requests
     * @param <T> interface, all parameters in all methods must be serializable, return type must be void
     * @return this builder
     */
    public <T> NettyRpcServerBuilder addFactory(Class<T> interfaceClass, ImplementationFactory<? extends T> factory) {
        validator.validateInterface(interfaceClass);
        implementations.put(interfaceClass, new FactoryServerProvider<T>(factory));
        return this;
    }

    /**
     * this listener will be called if TransportException / RemoteException caught
     * @param listener to add
     * @return this builder
     */
    public NettyRpcServerBuilder addExceptionListener(ExceptionListener listener) {
        exceptionListeners.add(listener);
        return this;
    }

    /**
     * this listener will be called on any client connects\disconnects
     * @param listener to add
     * @return this builder
     */
    public NettyRpcServerBuilder addClientListener(ClientListener listener) {
        clientListeners.add(listener);
        return this;
    }

    /**
     * sets interval at which KeepAlive packets will be sent to remote clients
     *
     * setting it to zero will effectively disable KeepAlive
     * this is not recommended - you most probably will miss abrupt disconnects
     * @param interval interval in milliseconds, if zero - keepAlive will be disabled
     * @return this builder
     */
    public NettyRpcServerBuilder setKeepAlive(long interval) {
        this.keepAlive = interval;
        return this;
    }

    /**
     * sets classResolver that will be used by this RpcServer <br/>
     * see {@link org.jboss.netty.handler.codec.serialization.ClassResolvers ClassResolvers} for available implementations
     * @param classResolver to be used, default is {@link org.jboss.netty.handler.codec.serialization.ClassResolvers#softCachingConcurrentResolver(java.lang.ClassLoader) softCachingConcurrentResolver}
     * @return this builder
     */
    public NettyRpcServerBuilder setClassResolver(ClassResolver classResolver) {
        this.classResolver = classResolver;
        return this;
    }

    /**
     * set executor, which will take care of all socket.accept() routine
     * by default, netty takes only one thread from this
     * default executor is {@link java.util.concurrent.Executors#newCachedThreadPool() newCachedThreadPool}(new {@link ru.alepar.rpc.common.BossThreadFactory BossThreadFactory}())
     * @param bossExecutor executor to be used as boss
     */
    public void setBossExecutor(ExecutorService bossExecutor) {
        this.bossExecutor = bossExecutor;
    }

    /**
     * set executor, which will take care of all remote calls
     * default executor is {@link java.util.concurrent.Executors#newCachedThreadPool() newCachedThreadPool}(new {@link ru.alepar.rpc.common.WorkerThreadFactory WorkerThreadFactory}())
     * @param workerExecutor executor to be used as boss
     */
    public void setWorkerExecutor(ExecutorService workerExecutor) {
        this.workerExecutor = workerExecutor;
    }

    /**
     * @return configured RpcServer
     */
    public RpcServer build() {
        return new NettyRpcServer(
                bindAddress,
                unmodifiableMap(implementations), 
                exceptionListeners.toArray(new ExceptionListener[exceptionListeners.size()]),
                clientListeners.toArray(new ClientListener[clientListeners.size()]),
                new PrimitiveTypesClassResolver(classResolver),
                keepAlive,
                bossExecutor,
                workerExecutor
        );
    }
}
