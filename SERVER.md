# GMA4J Server

## Dependency

```xml
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-server</artifactId>
  <version>3.14.60</version>
</dependency>
```

The server-side WebSocket and Netty transports ship inside `gma4j-server` and are registered automatically on `start()`.

## 1. Provide a host key (`IServerCertificateProvider`)

The server proves its identity by signing the handshake with an EC (P-256) private key; clients pin the matching public key. You have to persist the private key somewhere.

GMA4J ships a built-in `FileServerCertificateProvider` that does exactly this.

```java
import io.lolyay.gma4j.net.server.cert.FileServerCertificateProvider;
import java.nio.file.Path;

IServerCertificateProvider certProvider = new FileServerCertificateProvider(Path.of("./gma4j-server"));
// or let GMA4JServer wire it for you:  new GMA4JServer(new MyServerHandler(), Path.of("./gma4j-server"))
```

Provide your own `IServerCertificateProvider` only if the key lives in a KMS/HSM or an existing keystore. To build one by hand:
```java
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
kpg.initialize(new ECGenParameterSpec("secp256r1"));
KeyPair hostKey = kpg.generateKeyPair();   // load from disk in production

IServerCertificateProvider certProvider = new IServerCertificateProvider() {
    @Override public byte[] getCertificate() { return hostKey.getPublic().getEncoded(); } // X.509 SubjectPublicKeyInfo
    @Override public PrivateKey getSigningKey() { return hostKey.getPrivate(); }
};
```

## 2. Implement a `ServerEventHandler`

You get the client context (`ClientOnServer`) with every callback, so you know who a packet belongs to.

```java
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;

public class MyServerHandler implements ServerEventHandler {

    @Override
    public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
        // application packets from this client; return true if handled
        return false;
    }

    @Override public void onClientConnected(ClientOnServer client)    { } // socket up (pre-auth)
    @Override public void onClientAuthenticated(ClientOnServer client) {  // ready
        System.out.println(client.getClaimedClientId() + " -> " + client.getAssignedId());
    }
    @Override public void onClientDisconnected(ClientOnServer client, String reason) { }
    @Override public void onClientError(ClientOnServer client, Throwable e) { }
}
```

Only send packets once `onClientAuthenticated` has fired.

## 3. Auth

Pick one or more auth backends, or make your own auth (see README.md).

```java
import io.lolyay.gma4j.net.codec.auth.server.GmaApiKeyAuthServer;

GmaApiHmacAuthServer hmacAuth = new GmaApiHmacAuthServer("super-secret-key");
```

| Backend                       | Auth type                          | Client counterpart       |
|-------------------------------|------------------------------------|--------------------------|
| `GmaNoAuthServer`             | none (insecure, testing)           | `ClientAuth.none()`      |
| `GmaApiKeyAuthServer(apiKey)` | API key (**DEPRECATED**), Use HMAC | `ClientAuth.apiKey(...)` |
| `GmaApiHmacAuthServer`        | HMAC-SHA256                        | `ClientAuth.hmac(...)`   |
| `GmaApiECCAuthServer`         | ECDSA                              | `ClientAuth.ecc(...)`    |

## 4. Bind and start


```java
import io.lolyay.gma4j.net.server.GMA4JServer;
import io.lolyay.gma4j.net.server.ServerBindInfo;
import java.net.URI;

GMA4JServer server = new GMA4JServer(new MyServerHandler(), certProvider);

server.start(new ServerBindInfo(
        "0.0.0.0", // host
        1234, // port
        apiKeyAuth, hmacAuth));
```

## 5. Working with clients

Inside your handler, each `ClientOnServer` exposes:

```java
client.getAssignedId();      // UUID assigned by the server (unique
client.getClaimedClientId(); // the id the client claimed (unique)
client.isAuthenticated();
client.send(packet);        
client.disconnect("reason");
```

Server-wide:

```java
server.broadcast(packet);                
server.getNetServer().getClient(uuid);    // look a client up by assigned UUID
server.stop();
```

> Each client has a **claimed id**, and a **UUID** assigned by the server.

## Full example

```java
KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
kpg.initialize(new ECGenParameterSpec("secp256r1"));
KeyPair hostKey = kpg.generateKeyPair(); // should be persisted

IServerCertificateProvider certProvider = new IServerCertificateProvider() {
    public byte[] getCertificate() { return hostKey.getPublic().getEncoded(); }
    public PrivateKey getSigningKey() { return hostKey.getPrivate(); }
};

GMA4JServer server = new GMA4JServer(new MyServerHandler(), certProvider);
server.start(new ServerBindInfo("127.0.0.1", 9000, new GmaApiHmacAuthServer("super-secret-key")));
```



### Custom `IServerCertificateProvider`
```java
KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
kpg.initialize(new ECGenParameterSpec("secp256r1"));
KeyPair hostKey = kpg.generateKeyPair(); // should be persisted

IServerCertificateProvider certProvider = new IServerCertificateProvider() {
    public byte[] getCertificate() { return hostKey.getPublic().getEncoded(); }
    public PrivateKey getSigningKey() { return hostKey.getPrivate(); }
};

GMA4JServer server = new GMA4JServer(new MyServerHandler(), certProvider);
server.start(new ServerBindInfo("127.0.0.1", 9000, new GmaApiHmacAuthServer("super-secret-key")));
```
