# GMA4J

Secure, transport-agnostic messaging for Java (with JS and Python clients planned). 

GMA4J gives you auth, end-to-end encryption, with pluggable transports (WebSocket or raw TCP(Netty)), all in one **modular** library.

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
  <version>3.14.60</version>
</dependency>
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-ws</artifactId>
  <version>3.14.60</version>
</dependency>

<!-- Server -->
<dependency>
  <groupId>io.lolyay.gma4j</groupId>
  <artifactId>gma4j-server</artifactId>
  <version>3.14.60</version>
</dependency>
```

## Imeplementation details

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
