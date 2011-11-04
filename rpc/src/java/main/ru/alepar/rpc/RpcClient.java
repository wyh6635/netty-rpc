package ru.alepar.rpc;

public interface RpcClient extends ProxyFactory {

    <T> void addImplementation(Class<T> interfaceClass, T implObject);

    void addExceptionListener(ExceptionListener listener);

    void shutdown();

    interface ExceptionListener {
        void onExceptionCaught(Exception e);
    }
}
