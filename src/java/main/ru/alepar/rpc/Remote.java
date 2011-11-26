package ru.alepar.rpc;

import java.io.Serializable;

public interface Remote {
    
    Id getId();
    ProxyFactory getProxyFactory();
    String getRemoteAddress();

    public interface Id extends Serializable {}
    
    public interface ProxyFactory {
        <T> T getProxy(Class<T> clazz);
    }
    

}