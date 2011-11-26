package ru.alepar.rpc.server;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.serialization.ObjectDecoder;
import org.jboss.netty.handler.codec.serialization.ObjectEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alepar.rpc.api.Remote;
import ru.alepar.rpc.api.RpcServer;
import ru.alepar.rpc.api.exception.TransportException;
import ru.alepar.rpc.common.NettyRemote;
import ru.alepar.rpc.common.message.*;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;

import static ru.alepar.rpc.common.Util.invokeMethod;

public class NettyRpcServer implements RpcServer {

    private final Logger log = LoggerFactory.getLogger(NettyRpcServer.class);

    private final ExceptionListener[] exceptionListeners;
    private final ClientListener[] clientListeners;

    private final Map<Class<?>, ServerProvider<?>> implementations;
    private final ServerBootstrap bootstrap;

    private final ClientRepository clients = new ClientRepository();
    private final Channel acceptChannel;
    private ServerKeepAliveThread keepAliveThread;

    public NettyRpcServer(final InetSocketAddress bindAddress, Map<Class<?>, ServerProvider<?>> implementations, ExceptionListener[] exceptionListeners, ClientListener[] clientListeners, final long keepalivePeriod) {
        this.exceptionListeners = exceptionListeners;
        this.clientListeners = clientListeners;
        this.implementations = implementations;
        bootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(
                        new ObjectEncoder(),
                        new ObjectDecoder(),
                        new RpcHandler());
            }
        });
        
        keepAliveThread = new ServerKeepAliveThread(clients, keepalivePeriod);
        acceptChannel = bootstrap.bind(bindAddress);
    }

    @Override
    public void shutdown() {
        try {
            keepAliveThread.safeInterrupt();

            // close main channel
            acceptChannel.close().await();

            // send close message to all clients
            List<ChannelFuture> futures = new LinkedList<ChannelFuture>();
            for(Channel c: clients.getChannels()) {
                if (c.isOpen()) {
                    futures.add(c.close());
                }
            }

            // wait for close to complete
            for (ChannelFuture future : futures) {
                future.await();
            }

            // release executors
            bootstrap.releaseExternalResources();
        } catch (InterruptedException e) {
            throw new RuntimeException("failed to shutdown properly", e);
        }
    }

    @Override
    public Remote getClient(Remote.Id clientId) {
        return clients.getClient(clientId);
    }

    @Override
    public Collection<Remote> getClients() {
        return clients.getClients();
    }

    private void fireException(Remote remote, Exception exc) {
        for (ExceptionListener listener : exceptionListeners) {
            try {
                listener.onExceptionCaught(remote, exc);
            } catch (Exception e) {
                log.error("exception listener " + listener + " threw exception", e);
            }
        }
    }

    private void fireClientConnect(Remote remote) {
        for (ClientListener listener : clientListeners) {
            try {
                listener.onClientConnect(remote);
            } catch (Exception e) {
                log.error("remote listener " + listener + " threw exception", e);
            }
        }
    }

    private void fireClientDisconnect(Remote remote) {
        for (ClientListener listener : clientListeners) {
            try {
                listener.onClientDisconnect(remote);
            } catch (Exception e) {
                log.error("remote listener " + listener + " threw exception", e);
            }
        }
    }

    private class RpcHandler extends SimpleChannelHandler implements RpcMessage.Visitor {

        private final ConcurrentMap<Class<?>, Object> cache = new ConcurrentHashMap<Class<?>, Object> ();

        private NettyRemote remote;

        @Override
        public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            remote = new NettyRemote(ctx.getChannel());
            clients.addClient(remote);
            fireClientConnect(remote);
        }

        @Override
        public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            fireClientDisconnect(remote);
            clients.removeClient(remote);
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            RpcMessage message = (RpcMessage) e.getMessage();
            log.debug("server got message {} from {}", message.toString(), ctx.getChannel().toString());
            message.visit(this);
        }

        @Override
        public void acceptExceptionNotify(ExceptionNotify msg) {
            fireException(remote, msg.exc);
        }

        @Override
        public void acceptHandshakeFromClient(HandshakeFromClient msg) {
            remote.getChannel().write(new HandshakeFromServer(remote.getId()));
        }

        @Override
        public void acceptHandshakeFromServer(HandshakeFromServer msg) {
            // ignore // shudn't happen
        }

        @Override
        public void acceptInvocationRequest(InvocationRequest msg) {
            try {
                Class clazz = Class.forName(msg.className);
                Object impl = getImplementation(clazz);
                invokeMethod(msg, clazz, impl);
            } catch (Exception exc) {
                log.error("caught exception while trying to invoke implementation", exc);
                remote.getChannel().write(new ExceptionNotify(exc));
            }
        }

        @Override
        public void acceptKeepAlive(KeepAlive msg) {
            // ignore
        }

        private Object getImplementation(Class clazz) {
            Object impl = cache.get(clazz);
            if (impl == null) {
                impl = createImplementation(clazz);
                cache.put(clazz, impl);
            }
            return impl;
        }

        private Object createImplementation(Class clazz) {
            ServerProvider<?> provider = implementations.get(clazz);
            if(provider == null) {
                throw new RuntimeException("interface is not registered on server: " + clazz.getCanonicalName());
            }
            return provider.provideFor(remote.getChannel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
            fireException(remote, new TransportException(e.getCause()));
        }
    }

}