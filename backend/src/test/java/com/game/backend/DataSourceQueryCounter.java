package com.game.backend;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class DataSourceQueryCounter {
    private final AtomicInteger queryCount = new AtomicInteger();

    public DataSource wrap(DataSource original) {
        Class<?>[] ifaces = collectInterfaces(original);
        return (DataSource) Proxy.newProxyInstance(
                original.getClass().getClassLoader(),
                ifaces,
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection") && (args == null || args.length == 0)) {
                        Connection realConn = (Connection) method.invoke(original, args);
                        return proxyConnection(realConn);
                    }
                    return method.invoke(original, args);
                }
        );
    }

    private Connection proxyConnection(Connection realConn) {
        Class<?>[] ifaces = collectInterfaces(realConn);
        return (Connection) Proxy.newProxyInstance(
                realConn.getClass().getClassLoader(),
                ifaces,
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ((name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createStatement"))
                            && method.getParameterCount() > 0 && method.getParameterTypes()[0] == String.class) {
                        queryCount.incrementAndGet();
                    }
                    return method.invoke(realConn, args);
                }
        );
    }

    public int getQueryCount() {
        return queryCount.get();
    }

    public void reset() {
        queryCount.set(0);
    }

    private static Class<?>[] collectInterfaces(Object obj) {
        Set<Class<?>> ifaces = new LinkedHashSet<>();
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            ifaces.addAll(Arrays.asList(clazz.getInterfaces()));
            clazz = clazz.getSuperclass();
        }
        return ifaces.toArray(new Class<?>[0]);
    }
}
