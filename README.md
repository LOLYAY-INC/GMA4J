# GMA4J

Secure, transport-agnostic messaging for Java (Clients available in different languages).

GMA4J gives you auth, end-to-end encryption and pluggable transports (WebSocket or raw TCP via Netty) in one **modular** library.
> The main goal of this project is to be **modular**: if a feature is missing you can usually implement it yourself, or open a GitHub issue / PR.

## What you get

- **End-to-end encryption** negotiated on connect: ephemeral ECDH (P-256) key agreement, AES-256-GCM.
- **Trust-on-first-use server pinning** (SSH `known_hosts` style), so a swapped server key is detected and rejected. (Customizable)
- **Pluggable authentication** after encryption is up: none, API key, HMAC-SHA256, ECDSA, JWT, or your own.
- **Runtime connection modes**: low latency (no compression, TCP_NODELAY, urgent sends) and big size (packets beyond the 1 MiB base limit, budgeted server-side), negotiated per connection.
- **Clients available in different Languages**: Java, [JS (WebSockets)](https://github.com/LOLYAY-INC/gma4j-js), Python (Planned), Rust (Work in Progress)
- **Optional durable delivery**: persisted evidence, receipt ACKs, replay and deduplication.

## Modules and install

| Artifact         | Purpose                                                   | Depends on |
|------------------|-----------------------------------------------------------|------------|
| `gma4j-shared`   | Codec, encryption, auth primitives, the whole protocol    | (base)     |
| `gma4j-client`   | Client stack                                              | shared     |
| `gma4j-server`   | Server stack plus both server-side transports             | shared     |
| `gma4j-ws`       | Client WebSocket transport (`ws`, `wss`)                  | shared     |
| `gma4j-netty`    | Client TCP transport (`gma4j`, `gma`)                     | shared     |
| `gma4j-delivery` | Optional durable evidence receipts, replay, deduplication | shared, H2 |

A client app depends on `gma4j-client` plus `gma4j-ws` and/or `gma4j-netty`; a server app on `gma4j-server` (and optionally on `gma4j-delivery`). Requires **Java 21+**.

The artifacts ship on Maven Central and on the lolyay repo under the same coordinate; pick either.

**Maven Central** (no repository declaration needed):

```xml
<dependency>
  <groupId>dev.lolyay.gma4j</groupId>
  <artifactId>gma4j-client</artifactId>   <!-- or gma4j-server, gma4j-ws, gma4j-netty, gma4j-delivery -->
  <version>3.18.1</version>
</dependency>
```

**lolyay repo**:

```xml
<repositories>
  <repository>
    <id>lolyay</id>
    <url>https://maven.lolyay.dev/releases</url>
  </repository>
</repositories>

<dependency>
  <groupId>dev.lolyay.gma4j</groupId>
  <artifactId>gma4j-client</artifactId>   <!-- or gma4j-server, gma4j-ws, gma4j-netty, gma4j-delivery -->
  <version>3.18.1</version>
</dependency>
```

## Client

### Register the transport(s)

Client transports register once at startup; the scheme of the connect URI selects the transport.

```java
import io.lolyay.gma4j.net.transport.TransportManager;
import io.lolyay.gma4j.net.transport.ws.WsClientTransportFactory;
import io.lolyay.gma4j.net.transport.netty.NettyClientTransportFactory;

TransportManager.registerClientFactory(new WsClientTransportFactory());     // ws, wss
TransportManager.registerClientFactory(new NettyClientTransportFactory());  // gma4j, gma
```

### Implement a `ClientEventHandler`

```java
import io.lolyay.gma4j.net.client.ClientEventHandler;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;

public class MyClientHandler implements ClientEventHandler {

    @Override
    public <T extends GMAPacket<T>> boolean handle(T packet) {
        // your application packets arrive here; return true if handled
        return false;
    }

    @Override public void onConnectionEstablished() { }          // socket up (pre-auth), DO NOT send here
    @Override public void onAuthSuccess()          { }           // authenticated, ready
    @Override public void onConnectionClosed(String reason) { }
    @Override public void onConnectionError(Throwable e)    { }
    @Override public void onModesChanged(boolean lowLatency, boolean bigSize, int maxPacketSize) { }
}
```

Only send after `onAuthSuccess()`; `onConnectionEstablished()` means the socket opened, encryption and auth still have to complete. Sending there invalidates the connection.

### Describe the connection

`ClientConnectionInfo` carries the target URI, the client-claimed id (sent encrypted during auth) and the auth methods you offer; the server picks the strongest it also supports.

```java
import io.lolyay.gma4j.net.client.ClientConnectionInfo;
import io.lolyay.gma4j.net.codec.auth.client.ClientAuth;
import java.net.URI;

ClientConnectionInfo info = new ClientConnectionInfo(
        "my-client-id",
        URI.create("wss://example.com:8443"),
        ClientAuth.hmac("super-secret-key"), ClientAuth.ecc("-----BEGIN PRIVATE KEY-----"));

new ClientConnectionInfo(URI.create("gma4j://127.0.0.1:9000"));                 // random id, no auth
new ClientConnectionInfo(URI.create("gma4j://127.0.0.1:9000"), "my-client-id"); // no auth
```

| Client factory                          | Server backend                                                             | Auth type                                      |
|-----------------------------------------|----------------------------------------------------------------------------|------------------------------------------------|
| `ClientAuth.none()`                     | `GmaNoAuthServer`                                                          | none (**insecure**, testing)                   |
| `ClientAuth.apiKey(String)`             | `GmaApiKeyAuthServer(apiKey)`                                              | API key (**deprecated**, replayable, use HMAC) |
| `ClientAuth.hmac(String / byte[])`      | `GmaApiHmacAuthServer`, `GmaMultiClientHmacAuthServer` (key per client id) | HMAC-SHA256                                    |
| `ClientAuth.ecc(String / ECPrivateKey)` | `GmaApiECCAuthServer`, `GmaMultiClientECCAuthServer` (key per client id)   | ECDSA                                          |
| `ClientAuth.jwt(String)`                | `GmaJWTAuthServer` (optionally requires the client id in `aud`)            | JWT                                            |

### Certificate pinning (recommended)

By default the client **does not pin** the server key and is open to MITM. `FileCertificateKeeper` pins each server's key on first use and rejects a changed key on later connects:

```java
import io.lolyay.gma4j.net.client.cert.FileCertificateKeeper;

FileCertificateKeeper keeper = new FileCertificateKeeper("./gma4j");   // stores ./gma4j/known_certs
keeper.forget("wss://example.com:8443");                               // drop a pin, re-TOFU next connect
keeper.trust("wss://example.com:8443", certBytes);                     // or pin a known-good cert
```

`FileCertificateKeeper` is one implementation of `IClientKnownCertificateKeeper`; implement it to store pins in a database, keystore or memory:

```java
import io.lolyay.gma4j.net.codec.encryption.client.IClientKnownCertificateKeeper;

public class MyKeeper implements IClientKnownCertificateKeeper {
    // pinned SHA-256 hash for this URI, null if unknown; must be 32 bytes, it is advertised to the server
    @Override public byte[] getKnownCertificateHashForUri(String uri) { return ...; }

    // true if the presented cert differs from the pin (handshake aborts); on first sight store it and return false
    @Override public boolean hasCertificateChangedForUri(String uri, byte[] serverProvidedCertificate) { return ...; }
}
```

Never let `hasCertificateChangedForUri` overwrite an existing pin with a different key; re-pin only through a deliberate out-of-band action.

### Connect and use

```java
import io.lolyay.gma4j.net.client.GMA4JClient;

GMA4JClient client = new GMA4JClient(new MyClientHandler());
client.setKnownCertificateKeeper(keeper);   // optional; omit for NoOp
client.connect(info);

client.send(myPacket);                      // once onAuthSuccess() fired
client.sendUrgent(myPacket);                // bypasses coalescing, needs low latency mode
client.requestModes(true, true, 3 * 1024 * 1024); // lowLatency, bigSize, requested max packet size
client.isConnected();
client.disconnect();
```

## Server

Both server-side transports ship inside `gma4j-server` and are registered automatically on `start()`.

### Host key

The server signs the handshake with an EC (P-256) private key that clients pin, so it has to be persisted. `FileServerCertificateProvider` does that; implement `IServerCertificateProvider` yourself only if the key lives in a KMS/HSM or keystore.

```java
import io.lolyay.gma4j.net.server.cert.FileServerCertificateProvider;

IServerCertificateProvider certProvider = new FileServerCertificateProvider(Path.of("./gma4j-server"));
// or: new GMA4JServer(new MyServerHandler(), Path.of("./gma4j-server"))

IServerCertificateProvider custom = new IServerCertificateProvider() {
    @Override public byte[] getCertificate() { return hostKey.getPublic().getEncoded(); } // X.509 SubjectPublicKeyInfo
    @Override public PrivateKey getSigningKey() { return hostKey.getPrivate(); }
};
```

### Implement a `ServerEventHandler`

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
    @Override public void onClientAuthenticated(ClientOnServer client) { } // ready, send from here on
    @Override public void onClientDisconnected(ClientOnServer client, String reason) { }
    @Override public void onClientError(ClientOnServer client, Throwable e) { }
    @Override public void onClientModeChanged(ClientOnServer client) { }
}
```

### Bind and use

```java
import io.lolyay.gma4j.net.server.GMA4JServer;
import io.lolyay.gma4j.net.server.ServerBindInfo;
import io.lolyay.gma4j.net.codec.auth.server.GmaApiHmacAuthServer;

GMA4JServer server = new GMA4JServer(new MyServerHandler(), certProvider);
server.start(new ServerBindInfo("0.0.0.0", 9000, new GmaApiHmacAuthServer("super-secret-key")));

server.broadcast(packet);
server.getNetServer().getClient(uuid);   // by assigned UUID
server.stop();
```

Inside the handler each `ClientOnServer` exposes `getAssignedId()` (server UUID), `getClaimedClientId()`, `isAuthenticated()`, `send(packet)`, `setModes(lowLatency, bigSize, maxPacketSize)` and `disconnect("reason")`.

### TLS (wss) and Origin checking

The WebSocket transport can terminate TLS itself and gate browser handshakes by `Origin`, so a
reverse proxy is optional. Pass a `WsServerSecurity` to `start`: a non-null `SSLContext` enables
`wss`, and a non-empty origin allowlist rejects browser handshakes whose `Origin` is not listed.

```java
SSLContext tls = SSLContext.getInstance("TLS");
tls.init(keyManagers, null, null);   // your server cert/key

server.start(
        new ServerBindInfo("0.0.0.0", 8443, "wss", new GmaApiHmacAuthServer("super-secret-key")),
        new WsServerSecurity(tls, Set.of("https://app.example.com")));
```

`SSLContext` and the origin set are independent: pass `new WsServerSecurity(tls, null)` for TLS with
no origin gate, or `new WsServerSecurity(null, Set.of(...))` to gate origins on plaintext `ws`.
Origins match case-insensitively; a request with **no** `Origin` header is allowed, because native
(non-browser) GMA4J clients never send one, so the check only constrains browsers.

On the client, `wss://` uses the system CA trust by default. For a self-signed or internal-CA server,
set a custom `SSLContext` before connecting:

```java
client.setSslContext(myTrustingSslContext);   // omit for public-CA servers
client.connect(new ClientConnectionInfo("client-1", URI.create("wss://app.example.com:8443"), auth));
```

This TLS layer is separate from GMA4J's own ECDH + AES-GCM encryption and the certificate pinning in
[Certificate pinning](#certificate-pinning-recommended); wss adds standard transport TLS and browser
compatibility on top.

## Rolling your own auth

Implement a matching pair on `GmaAuthType.CUSTOM`: a `GmaAuthServer` on the server and a `GmaAuthClient` on the client. The server issues a challenge, the client answers it, the server verifies. **Always fold the `stateHash` (the handshake transcript) into the answer**, so a captured response cannot be replayed on another session.

```text
 1.  C -> S   extraAuthData()           client's opaque extra data
 2.  C <- S   createChallenge(extra)    server builds a challenge (may use the extra data)
 3.  C -> S   auth(challenge)           client answers the challenge
 4.       S   verifyClientResponse(..)  server accepts or rejects the answer
```

```java
public class MyAuthServer implements GmaAuthServer {
    private final byte[] secret;
    public MyAuthServer(byte[] secret) { this.secret = secret; }

    @Override public GmaAuthType authType() { return GmaAuthType.CUSTOM; }

    @Override public byte[] createChallenge(UUID clientId, String claimedClientId, byte[] clientExtraData) {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        return nonce; // stored per connection and passed back to verify
    }

    @Override public boolean verifyClientResponse(byte[] challenge, byte[] response,
                                                  UUID clientId, String claimedClientId, byte[] stateHash) {
        return MessageDigest.isEqual(response, hmac(secret, concat(challenge, stateHash)));
    }
}

public class MyAuthClient implements GmaAuthClient {
    private final byte[] secret;
    public MyAuthClient(byte[] secret) { this.secret = secret; }

    @Override public GmaAuthType authType() { return GmaAuthType.CUSTOM; }
    @Override public byte[] extraAuthData() { return new byte[0]; }

    @Override public byte[] auth(byte[] challenge, UUID internalClientId, String clientId, byte[] stateHash) {
        return hmac(secret, concat(challenge, stateHash));
    }
}
```

Register `new MyAuthServer(secret)` in `ServerBindInfo` and `new MyAuthClient(secret)` in `ClientConnectionInfo`. `verifyClientResponse` does not receive the earlier `extraAuthData`; keep attempt context in the backend or key it by the claimed client id.

## Connection modes

After `onAuthSuccess()` a client may request **low latency** and/or **big size**; the answer arrives in `onModesChanged`, so read the granted values there. The server can also change a client's modes with `client.setModes(..)`; `onClientModeChanged` fires after a grant is applied.

- **Low latency** disables outgoing compression, sets `TCP_NODELAY` and unlocks `sendUrgent`.
- **Big size** raises the per-packet limit above the base `SharedConfig.MAX_PACKET_SIZE` (1 MiB). Grants are clamped by `MAX_BIG_PACKET_SIZE` (32 MiB per client), drawn from the server-wide `BIG_SIZE_TOTAL_BUDGET` (256 MiB, returned on disconnect), and on both sides to what the outbound queue can hold twice (`MAX_PENDING_OUTBOUND_BYTES / 2` minus framing, about 4 MiB by default). The WebSocket transport enforces the current allowance per connection from the frame header, so an unauthenticated peer cannot make the server buffer more than the base size.
- `ALLOW_LOW_LATENCY_MODE` / `ALLOW_BIG_SIZE_MODE` switch each mode off globally. Requests are rate limited (`MODE_CHANGE_MIN_INTERVAL_MS`, 1s); flooding them disconnects the client.

## Packet ids and heterogeneous nodes

Packet ids are assigned at `warmup()` in a deterministic order derived from the registered set, so two
nodes that register the same packets get the same ids. By default a client whose set differs from the
server's is rejected at the handshake ("Codec hash mismatch"), which is the safe choice when every node
runs the same code.

Set `SharedConfig.IGNORE_CODEC_HASH = true` (on the server) to let nodes with **different packet sets**
talk, for example a proxy and backends that each run different plugins. The server is the id authority:
after auth it sends its id table (`S2CCodecStateUpdatePacket`) and the client remaps onto it, so the
wire format is unchanged and matching-set clients pay nothing. Then:

- a packet the server never registered cannot be sent from that client (`send` fails with a clear error);
- a server packet the client does not know is dropped with a warning, the stream stays aligned;
- the table lands before `onAuthSuccess`, so the remap is in place before you can send.

Give shared packets a **namespace** so they match unambiguously across nodes regardless of class name
or shape: `new PacketType<>(1, codec, "myplugin")`. `("myplugin", 1)` and `("otherplugin", 1)` are
different packets; the same pair on two nodes is the same packet. Without a namespace, matching falls
back to the class fingerprint, then the class simple name (logged, since the shape may differ).

Remap fixes *identity*, not *shape*: if a packet's fields changed between two nodes it still decodes
wrong, and those count as decode errors. Bump the packet's id when you change its fields.

## Limits and timeouts

All knobs live on `io.lolyay.gma4j.net.shared.SharedConfig`; set them before opening connections.

- **Keepalive**: the client pings every `KEEPALIVE_INTERVAL_MS` (15s) once authenticated and drops after `KEEPALIVE_TIMEOUT_MS` (45s) of silence.
- **Handshake**: both sides drop a connection that is not authenticated within `AUTH_HANDSHAKE_TIMEOUT_MS` (10s) of the socket opening. This covers the GMA4J handshake, not a proxy's TLS or HTTP upgrade.
- **Session bounds**: each direction closes at `MAX_SESSION_PACKETS` (2^28) and the connection closes once the key is older than `MAX_SESSION_AGE_MS` (24h), keeping the random AES-GCM IVs far from their collision bound and the sequence counters from wrapping. Both peers enforce this; expect a clean `onConnectionClosed` on long-lived connections and reconnect for fresh keys.
- **Admission**: the server admits at most `MAX_CONNECTIONS` (1024) transport connections.
- **Outbound queues**: `MAX_PENDING_OUTBOUND_BYTES` (8 MiB) and `MAX_PENDING_OUTBOUND_PACKETS` (256) per connection. TCP counts pending writes including length prefixes and releases capacity as writes finish; WebSocket counts queued frames plus a maximum-size frame reserved for the writer thread. Overflow or a failed write closes the connection instead of leaving a sequence gap. Kernel socket buffers are not counted, and a completed local write is not a remote receipt.
- **Inbound processing**: packet handlers run on the `NETWORK_THREADS` (4, must be positive) transport threads, so at most that many frames are decoded at once. `MAX_INBOUND_PROCESSING_BYTES` (256 MiB) caps the frame bytes in decode or dispatch across all server connections; a frame that does not fit drops its connection. Decoding holds a few copies of a frame transiently and decompression may expand it up to the connection's receive limit, so plan heap for a small multiple of this cap.
- **Frames**: length prefixes and WebSocket headers over the connection limit are refused before any payload is buffered. JSON packets are parsed strictly (UTF-8, grammar, nesting `MAX_JSON_NESTING_DEPTH`, no trailing data).

## Durable evidence delivery

The optional `gma4j-delivery` module adds an H2-backed inbox and outbox. An ACK means the receiver committed the evidence to its inbox; it does not mean an application transaction completed and it is not an independently verifiable receipt. Ordinary `send` stays non-durable.

Both peers register the delivery packet types before connecting or starting the server. Attach a session only after authentication, bound to an identity the auth backend actually ties to credentials (not a bare claimed name or the per-connection UUID). The application still owns reconnects; reattaching resends pending evidence with its original transfer id through the new connection.

```java
DeliveryPackets.register(CodecRegistry.getInstance());
H2DeliveryStore store = H2DeliveryStore.open(Path.of("./gma4j/evidence"), DeliveryLimits.defaults());
DurableDeliverySession delivery = new DurableDeliverySession("trusted-peer", store);
UUID transferId = delivery.enqueue(evidenceBytes);   // works offline, commits before returning
```

| Callback or operation             | Integration                                                                                            |
|-----------------------------------|--------------------------------------------------------------------------------------------------------|
| Client `onAuthSuccess`            | `delivery.attachAuthenticated(connectionToken, client::send)`                                          |
| Server `onClientAuthenticated`    | `delivery.attachAuthenticated(client, client::send)` on that peer's session                            |
| Incoming `DeliveryTransferPacket` | `delivery.handleTransfer(connectionToken, transfer)`                                                   |
| Incoming `DeliveryAckPacket`      | `delivery.handleAck(connectionToken, ack)`                                                             |
| Disconnect or connection error    | `delivery.detach(connectionToken)`                                                                     |
| Application retry timer           | `delivery.retry()` periodically, e.g. once per second; there is no internal retry thread               |
| Process pending inbox             | `delivery.pollInbox(limit)`, commit application work, then `delivery.markProcessed(item.transferId())` |
| Application shutdown              | stop retry tasks, detach connections, then `store.close()`                                             |


> **Identical duplicates are ACKed without a second inbox entry.**
> 
> Processing an item removes its payload but keeps its digest tombstone for dedup. Tombstones are bounded: only the `maxProcessedTombstones` most-recent processed receipts are kept, older ones are compacted away so they cannot exhaust `maxRecords` on a long-running system. A resend older than that window is accepted again as fresh evidence rather than deduplicated. 
> Full stores reject new evidence without ACKing it, so monitor capacity; quotas bound logical data, not the H2 file size. 
> Evidence is plaintext on disk, so protect the database and backups; restoring an old receiver backup can discard receipt history, and log flushing cannot recover a lost disk. 
> Application side effects still need their own idempotency.

## Integration responsibilities

- Packet direction and role authorization are enforced by your handlers.
- Transport reconnects and committing application side effects stay with the application.
- The server WebSocket transport can terminate TLS and enforce an `Origin` allowlist itself (see [TLS (wss) and Origin checking](#tls-wss-and-origin-checking)); a reverse proxy for those is optional.

Security issues: see [SECURITY.md](SECURITY.md).
