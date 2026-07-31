package io.lolyay.gma4j.net.transport;

import lombok.experimental.UtilityClass;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@UtilityClass
public class TransportManager {

    private final Map<String, IClientTransportFactory> clientFactories = new ConcurrentHashMap<>();
    private final Map<String, IServerTransportFactory> serverFactories = new ConcurrentHashMap<>();

    public void registerClientFactory(IClientTransportFactory factory) {
        for (String scheme : factory.schemes()) {
            clientFactories.put(normalize(scheme), factory);
        }
    }

    public void registerServerFactory(IServerTransportFactory factory) {
        for (String scheme : factory.schemes()) {
            serverFactories.put(normalize(scheme), factory);
        }
    }

    public IClientTransportFactory clientFactoryFor(String scheme) {
        IClientTransportFactory factory = clientFactories.get(normalize(scheme));
        if (factory == null) {
            throw new IllegalArgumentException("No client transport registered for scheme '" + scheme + "'");
        }
        return factory;
    }

    public IClientTransportFactory clientFactoryFor(URI uri) {
        return clientFactoryFor(uri.getScheme());
    }

    public IServerTransportFactory serverFactoryFor(String scheme) {
        IServerTransportFactory factory = serverFactories.get(normalize(scheme));
        if (factory == null) {
            throw new IllegalArgumentException("No server transport registered for scheme '" + scheme + "'");
        }
        return factory;
    }

    private String normalize(String scheme) {
        if (scheme == null) {
            throw new IllegalArgumentException("scheme must not be null");
        }
        return scheme.toLowerCase(Locale.ROOT);
    }
}
