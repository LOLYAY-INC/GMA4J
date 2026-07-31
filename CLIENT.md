# GMA4J Client

## Dependencies

`gma4j-client` plus at least one transport:

```xml
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-client</artifactId>
  <version>3.14.60</version>
</dependency>
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-ws</artifactId>   <!-- ws:// and wss:// -->
  <version>3.14.60</version>
</dependency>
<!-- and/or gma4j-netty for gma4j:// (raw TCP) -->
```

## 1. Register the transport(s)

Client transports register manually, once, at startup. Add the factory for each scheme you intend to use:

```java
import io.lolyay.gma4j.net.transport.TransportManager;
import io.lolyay.gma4j.net.transport.ws.WsClientTransportFactory;
import io.lolyay.gma4j.net.transport.netty.NettyClientTransportFactory;

TransportManager.registerClientFactory(new WsClientTransportFactory());     // ws, wss
TransportManager.registerClientFactory(new NettyClientTransportFactory());  // gma4j, gma
```

The scheme in your connect URI selects which transport is used.

## 2. Implement a `ClientEventHandler`

This is your application's view of the connection.

```java
import io.lolyay.gma4j.net.client.ClientEventHandler;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;

public class MyClientHandler implements ClientEventHandler {

    @Override
    public <T extends GMAPacket<T>> boolean handle(T packet) {
        // your application packets arrive here; return true if handled
        return false;
    }

    @Override public void onConnectionEstablished() { }          // socket up (pre-auth)
    @Override public void onAuthSuccess()          { }           // authenticated, ready
    @Override public void onConnectionClosed(String reason) { }
    @Override public void onConnectionError(Throwable e)    { }
}
```

Wait for `onAuthSuccess()` before treating the connection as ready; `onConnectionEstablished()` only means the socket opened, the encryption and auth still has to complete.
**DO NOT Send any packets here, it will invalidate the connection.**

## 3. Describe the connection

`ClientConnectionInfo` carries the target URI, the client-claimed id, and the auth methods you offer. Auth clients are created with `ClientAuth`:

```java
import io.lolyay.gma4j.net.client.ClientConnectionInfo;
import io.lolyay.gma4j.net.codec.auth.client.ClientAuth;
import java.net.URI;

ClientConnectionInfo info = new ClientConnectionInfo(
        "my-client-id",                      // claimed id (sent encrypted during auth)
        URI.create("wss://example.com:8443"),
        ClientAuth.hmac("super-secret-key")
);

// Allows multiple auth methods:
ClientConnectionInfo info = new ClientConnectionInfo(
        "my-client-id",                      // claimed id 
        URI.create("wss://example.com:8443"),
        ClientAuth.hmac("super-secret-key"), ClientAuth.ecc("-----BEGIN PRIVATE KEY-----")
);
```
> **WARNING** Api Key auth is **deprecated** and is susceptible to replay attacks, please use HMAC or ECC instead.

Convenience constructors exist for the no-auth / generated-id cases:
```java
new ClientConnectionInfo(URI.create("gma4j://127.0.0.1:9000"));               // random id, no auth
new ClientConnectionInfo(URI.create("gma4j://127.0.0.1:9000"), "my-client-id"); // no auth
```

Available auth clients:

| Factory                                                   | Auth type                                  |
|-----------------------------------------------------------|--------------------------------------------|
| `ClientAuth.none()`                                       | none (**insecure**, testing)               |
| `ClientAuth.apiKey(String)`                               | API key (**deprecated**, use HMAC instead) |
| `ClientAuth.hmac(String)` / `ClientAuth.hmac(byte[])`     | HMAC-SHA256                                |
| `ClientAuth.ecc(String)` / `ClientAuth.ecc(ECPrivateKey)` | ECDSA                                      |

You may pass several to offer multiple; the server picks the strongest it also supports.

## 4. Certificate pinning (recommended)

By default the client **does not pin certificates** making it **susceptible to MITM Attacks**.
For real deployments use `FileCertificateKeeper` (Or your own implementation), which pins each server's key on first use (TOFU) and rejects a changed key on later connects:

```java
import io.lolyay.gma4j.net.client.cert.FileCertificateKeeper;

FileCertificateKeeper keeper = new FileCertificateKeeper("./gma4j");  // dir; stores ./gma4j/known_certs
```

If a server legitimately rotates its key you re-pin out of band:

```java
keeper.forget("wss://example.com:8443");        // drop the old pin, re-TOFU next connect
keeper.trust("wss://example.com:8443", certBytes); // or pin a known-good cert explicitly
```

A changed key that you have **not** re-pinned aborts the connection.

### Making your own keeper

`FileCertificateKeeper` is just one implementation of `IClientKnownCertificateKeeper`. Implement that interface to store pins wherever you like (a database, an OS keystore, an in-memory map for tests):

```java
import io.lolyay.gma4j.net.codec.encryption.client.IClientKnownCertificateKeeper;

public class MyKeeper implements IClientKnownCertificateKeeper {

    // The pinned SHA-256 hash for this URI, or null if the host is unknown.
    @Override
    public byte[] getKnownCertificateHashForUri(String uri) {
        return ...;
    }

    // Given the cert the server just presented: return true if it differs from the
    // pinned one (the handshake is then aborted). On first sight, store it and
    // return false (trust-on-first-use).
    @Override
    public boolean hasCertificateChangedForUri(String uri, byte[] serverProvidedCertificate) {
        return ...;
    }
}
```

Two rules keep a custom keeper safe:

- `hasCertificateChangedForUri` must **never** silently overwrite an existing pin with a different key, returning `true` is exactly what rejects an impersonating server. Only re-pin through a deliberate, out-of-band action.
- Hash with **SHA-256** so `getKnownCertificateHashForUri` returns 32 bytes; that value is what the client advertises to the server, and anything else will desync the handshake.


## 5. Connect and use

```java
import io.lolyay.gma4j.net.client.GMA4JClient;

GMA4JClient client = new GMA4JClient(new MyClientHandler());
client.setKnownCertificateKeeper(keeper);   // optional; omit for NoOp

client.connect(info);

// once onAuthSuccess() has fired:
client.send(myPacket);

client.isConnected();     // true while the socket is up
client.disconnect();      // clean shutdown
```

## Keepalive and timeouts

The client automatically:

- sends a keepalive ping every `KEEPALIVE_INTERVAL_MS` once authenticated, and drops the connection after `KEEPALIVE_TIMEOUT_MS` of silence.
- drops the connection if authentication is not completed within `AUTH_HANDSHAKE_TIMEOUT_MS` of the socket opening.

Tune these on `io.lolyay.gma4j.net.shared.SharedConfig` (defaults: 15s interval, 45s timeout, 10s auth window).

## Full example

```java
TransportManager.registerClientFactory(new WsClientTransportFactory());

GMA4JClient client = new GMA4JClient(new MyClientHandler());
client.setKnownCertificateKeeper(new FileCertificateKeeper("./gma4j"));

client.connect(new ClientConnectionInfo(
        "client-42",
        URI.create("wss://example.com:8443"),
        ClientAuth.apiKey("super-secret-key")));
```
