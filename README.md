# GMA4J

Secure, transport-agnostic messaging for Java (with JS and Python clients planned). 

GMA4J gives you auth, end-to-end encryption, with pluggable transports (WebSocket or raw TCP(Netty)), all in one **modular** library.
> The main goal for this Project is to be **modular** so if you do not find a feature you need, most of the time you can just implement it yourself, or just create a github issue / pr!

## What you get

- **End-to-end encryption** negotiated on connect: ephemeral ECDH (P-256) key agreement, AES-256-GCM.
- **Trust-on-first-use server pinning** (SSH `known_hosts` style), so a swapped server key is detected and rejected.
- **Pluggable authentication** after encryption is up: none, API key, HMAC-SHA256, or ECDSA, all **modular**.
- **Modular**: depend only on the client and the transport(s) you need.

## Modules

| Artifact | Purpose                                                                 | Depends on |
|---|-------------------------------------------------------------------------|---|
| `gma4j-shared` | Codec, encryption, auth primitives, Contains every part of the protocol | (base) |
| `gma4j-client` | Client stack                                                            | shared |
| `gma4j-server` | Server stack + server-side transports                                   | shared |
| `gma4j-ws` | Client WebSocket transport (`ws`, `wss`)                                | shared |
| `gma4j-netty` | Client TCP transport (`gma4j`, `gma`)                                   | shared |
| `gma4j-delivery` | Optional durable evidence receipts, replay, and deduplication | shared, H2 |

A typical client app depends on `gma4j-client` plus `gma4j-ws` and/or `gma4j-netty`. A server app depends on `gma4j-server`.

## Requirements

- **Java 21+**

## Install

```xml
<repositories>
  <repository>
    <id>lolyay</id>
    <url>https://maven.lolyay.dev/releases</url>
  </repository>
</repositories>

<!-- Client over WebSocket -->
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-client</artifactId>
  <version>3.16.0</version>
</dependency>
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-ws</artifactId>
  <version>3.16.0</version>
</dependency>

<!-- Server -->
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-server</artifactId>
  <version>3.16.0</version>
</dependency>
```

## Integration responsibilities

GMA4J keeps these application concerns outside the base client and server modules:

- `GmaApiECCAuthServer` verifies one fixed public key. Resolve per-user keys with a custom `GmaAuthServer`.
- `GmaAuthServer.verifyClientResponse` does not receive the earlier `extraAuthData`; use the claimed client ID or retain attempt context in the backend.
- Packet direction and role authorization must be enforced by the application handler.
- Reconnecting the transport and committing application side effects remain application responsibilities. The optional [`gma4j-delivery` module](#durable-evidence-delivery) persists evidence before sending, ACKs durable receipt, replays unacknowledged transfers, and deduplicates incoming transfers across restarts.
- Ordinary `send` does not prove remote receipt. Both transports enforce per-connection outbound byte and packet limits, closing on overflow or write failure rather than silently skipping encoded packets. Durable transfers stay in the outbox until a matching receipt ACK.
- The server WebSocket transport does not configure TLS or validate `Origin`. Terminate TLS and enforce allowed origins in a proxy, or provide a custom transport.

## Outbound limits

Set `SharedConfig.MAX_PENDING_OUTBOUND_BYTES` and `MAX_PENDING_OUTBOUND_PACKETS` before opening connections. Defaults are 8 MiB and 256 packets per connection. TCP counts pending writes including length prefixes, releasing capacity when each write finishes. WebSocket counts queued frame capacities plus a conservative maximum-size frame reserved for the writer, so it requires at least two slots and enough bytes for that reserve plus a new frame. Its minimum viable byte limit is the reserve plus an empty frame; larger packets still need their own capacity.

These limits do not bound kernel socket buffers or writes made directly through the underlying transport library. A rejected encoded packet or failed write closes the connection to avoid leaving a sequence gap. Ordinary sends have no remote completion guarantee.

## Durable evidence delivery

The optional `gma4j-delivery` module adds a file-backed inbox and outbox. An ACK means the receiver committed the evidence to its inbox. It does not mean an application transaction completed, and it is not an independently verifiable receipt.

```xml
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-delivery</artifactId>
  <version>3.16.0</version>
</dependency>
```

Both peers must register the delivery packet types before connecting or starting the server. Attach delivery only after authentication, using a stable identity that the auth backend binds to credentials. The application still owns reconnects. Reattaching resends pending evidence with its original transfer ID through the new connection's encoder.

```java
DeliveryPackets.register(CodecRegistry.getInstance());
H2DeliveryStore store = H2DeliveryStore.open(Path.of("./gma4j/evidence"), DeliveryLimits.defaults());
DurableDeliverySession delivery = new DurableDeliverySession("trusted-peer", store);
UUID transferId = delivery.enqueue(evidenceBytes);
```

These types are in `io.lolyay.gma4j.net.delivery`. Enqueue works offline and commits before returning. Keep the store and session for the application's lifetime. Use a new connection token for every authenticated connection; an old disconnect callback must not detach its replacement.

| Callback or operation | Integration |
|---|---|
| Client `onAuthSuccess` | `delivery.attachAuthenticated(connectionToken, client::send)` |
| Server `onClientAuthenticated` | Attach that peer's session with `delivery.attachAuthenticated(client, client::send)` |
| Incoming `DeliveryTransferPacket transfer` | `delivery.handleTransfer(connectionToken, transfer)` |
| Incoming `DeliveryAckPacket ack` | `delivery.handleAck(connectionToken, ack)` |
| Disconnect or connection error | `delivery.detach(connectionToken)` |
| Application retry timer | Call `delivery.retry()` periodically, for example once per second. No internal retry thread or transport reconnect is started. |
| Process pending inbox | Read `delivery.pollInbox(limit)`, commit application work, then call `delivery.markProcessed(item.transferId())`. |
| Application shutdown | Stop retry tasks, detach connections, then call `store.close()`, preferably through try-with-resources. |

On the server, use the `ClientOnServer` instance as the token for both incoming packets and disconnects. The store can hold several peers, each with a separate session. Treat delivery/storage exceptions as failures: detach and close the affected connection, then investigate rather than reporting receipt. See the [TCP/WS integration test](integration-tests/src/test/java/io/lolyay/gma4j/it/DurableDeliveryIntegrationTest.java) for a complete authenticated, certificate-pinned example.

The receiver ACKs identical duplicates without creating another inbox entry. Processing an inbox item removes its payload but retains its digest tombstone. Tombstones do not expire automatically. Full stores reject new evidence without ACKing it, so applications must monitor capacity. Payload and record quotas bound logical data, not the exact H2 file size.

Evidence is plaintext on disk. Protect the database and backups. Immediate database log flushing requests crash durability from the OS and storage device; it cannot recover a lost disk. Restoring an old receiver backup can discard receipt history. Application side effects still need their own idempotency or transaction handling.

## Implementation details

- **[CLIENT.md](CLIENT.md)** — connecting, authenticating, sending packets, certificate pinning.
- **[SERVER.md](SERVER.md)** — binding, host keys, auth backends, per-client handling.


### Rolling your own auth

Implement a matching pair on `GmaAuthType.CUSTOM`: a `GmaAuthServer` on the server and a `GmaAuthClient` on the client:
> The server issues a challenge, the client answers it, the server verifies.
> **Always fold the `stateHash` (the handshake transcript) into the answer**, so a captured response can't be replayed on another session.

#### Auth handshake

```text
       C = client                                     S = server

 1.  C -> S   extraAuthData()           client's opaque extra data
 2.  C <- S   createChallenge(extra)    server builds a challenge (may use the extra data)
 3.  C -> S   auth(challenge)           client answers the challenge
 4.       S    verifyClientResponse(…)   server accepts or rejects the answer
```

Server side:
```java
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;

public class MyAuthServer implements GmaAuthServer {
    private final byte[] secret;
    public MyAuthServer(byte[] secret) { this.secret = secret; }

    @Override public GmaAuthType authType() { return GmaAuthType.CUSTOM; }

    @Override public byte[] createChallenge(UUID clientId, String claimedClientId, byte[] clientExtraData) {
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        return nonce; // stored per-connection and passed back to verify
    }

    @Override public boolean verifyClientResponse(byte[] challenge, byte[] response,
                                                  UUID clientId, String claimedClientId, byte[] stateHash) {
        return MessageDigest.isEqual(response, hmac(secret, concat(challenge, stateHash)));
    }
}
```

Client side (mirror):
```java
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;

public class MyAuthClient implements GmaAuthClient {
    private final byte[] secret;
    public MyAuthClient(byte[] secret) { this.secret = secret; }

    @Override public GmaAuthType authType() { return GmaAuthType.CUSTOM; }
    
    @Override public byte[] extraAuthData(){
        return new byte[0]; // You can add extra data here the server will receive when issuing a challenge
    }

    @Override public byte[] auth(byte[] challenge, UUID internalClientId, String clientId, byte[] stateHash) {
        return hmac(secret, concat(challenge, stateHash));
    }
}
```


Then register `new MyAuthServer(secret)` in `ServerBindInfo` and `new MyAuthClient(secret)` in `ClientConnectionInfo`.
