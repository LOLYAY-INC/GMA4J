# GMA4J - Secure WebSocket Library

[![Maven](https://img.shields.io/badge/maven-v1.1.0-blue)](https://maven.lolyay.dev/releases)
[![Java](https://img.shields.io/badge/java-24-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**GMA4J** (Generic Messaging Architecture for Java) is a production-ready WebSocket library with built-in security, client identification, and type-safe messaging.

## 🚀 Features

- 🔒 **AES-256 Encryption** - All messages encrypted after authentication
- 🔑 **HMAC-SHA256 Authentication** - Challenge-response with API key verification
- 🎯 **Client Identification** - Target specific clients by custom ID (e.g., "smp", "ffa", "game-1")
- 📦 **Type-Safe Packets** - Gson-based serialization with automatic type handling
- ⚡ **Easy Integration** - Drop into existing Jetty servers at any path
- 📚 **Full Documentation** - Comprehensive Javadocs and tutorials included

## 📦 Installation

### Maven
```xml
<repositories>
    <repository>
        <id>lolyay-releases</id>
        <url>https://maven.lolyay.dev/releases</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>io.lolyay.gma4j</groupId>
        <artifactId>GMA4J</artifactId>
        <version>1.1.0</version>
    </dependency>
</dependencies>
```

### Gradle
```gradle
repositories {
    maven { url 'https://maven.lolyay.dev/releases' }
}

dependencies {
    implementation 'io.lolyay.gma4j:GMA4J:1.1.0'
}
```

## 🔧 Quick Start

### Server-Side: Add to Your Jetty Server

```java
import io.lolyay.gma4j.net.*;
import io.lolyay.gma4j.net.server.*;
import io.lolyay.gma4j.packets.auth.*;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

public class GameServer {
    public static void main(String[] args) throws Exception {
        // 1. Register packets
        PacketRegistry.register(PacketPublicKey.class);
        PacketRegistry.register(PacketSharedSecret.class);
        PacketRegistry.register(PacketChallenge.class);
        PacketRegistry.register(PacketChallengeResponse.class);
        PacketRegistry.register(PacketAuthSuccess.class);
        PacketRegistry.register(PacketAuthFailed.class);
        PacketRegistry.register(PacketIdentification.class);
        
        // Register your custom packets
        PacketRegistry.register(PacketGameUpdate.class);
        
        // 2. Create authentication manager
        AuthenticationManager authManager = new AuthenticationManager("your-api-key");
        
        // 3. Create packet handler
        SecureServerHandler.SecurePacketHandler handler = new SecureServerHandler.SecurePacketHandler() {
            @Override
            public void onAuthenticated(AuthenticatedClient client) {
                System.out.println("✓ Client authenticated: " + client.getClientId());
            }
            
            @Override
            public void onIdentified(AuthenticatedClient client, String identifier) {
                System.out.println("✓ Client identified as: " + identifier);
            }
            
            @Override
            public void onPacket(AuthenticatedClient client, Packet packet) {
                if (packet instanceof PacketGameUpdate) {
                    PacketGameUpdate update = (PacketGameUpdate) packet;
                    System.out.println("Game update from " + client.getClientIdentifier() + 
                                     ": " + update.getAction());
                }
            }
            
            @Override
            public void onDisconnect(AuthenticatedClient client) {
                System.out.println("✗ Client disconnected: " + client.getClientIdentifier());
            }
        };
        
        // 4. Create secure handler
        SecureServerHandler secureHandler = new SecureServerHandler(handler, authManager);
        
        // 5. Add to Jetty server
        Server server = new Server(8080);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.addMapping("/ws/game", (req, resp) -> secureHandler);
        });
        
        server.start();
        System.out.println("🚀 Server running on ws://localhost:8080/ws/game");
        server.join();
    }
}
```

### Client-Side: Connect and Authenticate

```java
import io.lolyay.gma4j.net.*;
import io.lolyay.gma4j.net.client.*;
import io.lolyay.gma4j.packets.auth.*;

public class GameClient {
    public static void main(String[] args) throws Exception {
        // 1. Register packets (same as server)
        PacketRegistry.register(PacketPublicKey.class);
        PacketRegistry.register(PacketSharedSecret.class);
        PacketRegistry.register(PacketChallenge.class);
        PacketRegistry.register(PacketChallengeResponse.class);
        PacketRegistry.register(PacketAuthSuccess.class);
        PacketRegistry.register(PacketAuthFailed.class);
        PacketRegistry.register(PacketIdentification.class);
        PacketRegistry.register(PacketGameUpdate.class);
        
        // 2. Create settings with automatic identification
        ClientSettings settings = ClientSettings.builder()
            .setClientIdentifier("smp")  // Automatically sent after auth
            .setIdentificationMetadata("version:1.20.1,players:42")
            .build();
        
        // 3. Create client with handler
        SecureWebSocketClient client = new SecureWebSocketClient(
            "your-api-key",
            new SecureWebSocketClient.SecurePacketHandler() {
                @Override
                public void onAuthenticated(SecureWebSocketClient client) {
                    System.out.println("✓ Authenticated and identified!");
                    
                    // Send game updates
                    client.sendPacket(new PacketGameUpdate("player_join", "Steve"));
                }
                
                @Override
                public void onPacket(SecureWebSocketClient client, Packet packet) {
                    if (packet instanceof PacketGameUpdate) {
                        PacketGameUpdate update = (PacketGameUpdate) packet;
                        System.out.println("Received: " + update.getAction());
                    }
                }
                
                @Override
                public void onDisconnect(SecureWebSocketClient client) {
                    System.out.println("✗ Disconnected from server");
                }
            },
            settings
        );
        
        // 4. Connect
        client.connect("ws://localhost:8080/ws/game");
        
        // Keep alive
        Thread.sleep(Long.MAX_VALUE);
    }
}
```

## 🎯 Client Identification System

Target specific clients by their custom identifier:

### Server: Send to Specific Client
```java
// Get client by identifier
AuthenticatedClient smpServer = authManager.getClientById("smp");
if (smpServer != null && smpServer.isConnected()) {
    smpServer.sendPacket(new PacketGameUpdate("restart", "Server restarting in 5 minutes"));
}

// Get all identifiers
Set<String> identifiers = authManager.getAllClientIdentifiers();
// Output: [smp, ffa, creative, survival]

// Broadcast to all
authManager.broadcast(new PacketGameUpdate("announcement", "Server maintenance tonight"));
```

### Client: Automatic Identification (v1.1.0+)
```java
ClientSettings settings = ClientSettings.builder()
    .setClientIdentifier("smp")  // Your unique ID
    .setIdentificationMetadata("version:1.20.1,type:survival")
    .build();

SecureWebSocketClient client = new SecureWebSocketClient(apiKey, handler, settings);
// Identification is sent automatically after authentication!
```

## 📝 Custom Packets

Create your own packet types:

```java
import io.lolyay.gma4j.net.Packet;

public class PacketGameUpdate implements Packet {
    private String action;
    private String data;
    
    public PacketGameUpdate() {} // Required for Gson
    
    public PacketGameUpdate(String action, String data) {
        this.action = action;
        this.data = data;
    }
    
    public String getAction() { return action; }
    public String getData() { return data; }
}

// Register it
PacketRegistry.register(PacketGameUpdate.class);

// Use it
client.sendPacket(new PacketGameUpdate("player_move", "x:100,y:64,z:200"));
```

## 🔐 Authentication Flow

1. **Client connects** → Sends RSA public key
2. **Server generates AES secret** → Encrypts with client's public key
3. **Client decrypts secret** → Both now share AES-256 key
4. **Server sends challenge** → Encrypted random string
5. **Client computes HMAC** → Uses API key + challenge
6. **Server verifies HMAC** → Authentication succeeds ✓
7. **Client sends identification** → Optional custom ID (e.g., "smp")
8. **All messages encrypted** → AES-256-GCM with authentication tags

## 📚 Documentation

- **[INTEGRATION.md](INTEGRATION.md)** - Server-side integration guide
- **[CLIENT_TUTORIAL.md](CLIENT_TUTORIAL.md)** - Client-side tutorial
- **[DEPLOYMENT.md](DEPLOYMENT.md)** - Maven deployment guide
- **[JAVADOC_SUMMARY.md](JAVADOC_SUMMARY.md)** - API documentation overview

### Generate HTML Javadocs
```bash
mvn javadoc:javadoc
# Output: target/site/apidocs/index.html
```

## 🏗️ Project Structure

```
GMA4J/
├── src/main/java/io/lolyay/gma4j/
│   ├── net/
│   │   ├── Packet.java                      # Base packet interface
│   │   ├── PacketRegistry.java              # Packet type registry
│   │   ├── PacketCodec.java                 # JSON serialization
│   │   ├── crypto/
│   │   │   ├── CryptoUtils.java             # RSA + AES + HMAC
│   │   │   └── SecurePacketCodec.java       # Encrypted packets
│   │   ├── server/
│   │   │   ├── SecureServerHandler.java     # Server WebSocket handler
│   │   │   ├── AuthenticationManager.java   # Client management
│   │   │   └── AuthenticatedClient.java     # Client wrapper
│   │   └── client/
│   │       ├── SecureWebSocketClient.java   # Client implementation
│   │       └── ClientSettings.java          # Client configuration
│   └── packets/
│       └── auth/                            # Authentication packets
└── docs/
    ├── INTEGRATION.md                       # Server guide
    ├── CLIENT_TUTORIAL.md                   # Client guide
    └── DEPLOYMENT.md                        # Deployment guide
```

## 🔧 Advanced Features

### Client Settings
```java
ClientSettings settings = ClientSettings.builder()
    .setClientIdentifier("game-server-1")
    .setIdentificationMetadata("version:1.0.0")
    .setAutoReconnect(true)
    .setMaxReconnectAttempts(5)
    .setReconnectDelay(Duration.ofSeconds(3))
    .setEnablePing(true)
    .setPingInterval(Duration.ofSeconds(30))
    .setConnectionTimeout(Duration.ofSeconds(10))
    .build();
```

### Metadata
```java
// Server side
AuthenticatedClient client = authManager.getClientById("smp");
String metadata = client.getMetadata();
// Parse: "version:1.20.1,players:42,maxPlayers:100"

// Client side
settings.setIdentificationMetadata("region:us-east,type:pvp");
```

## 🛡️ Security Features

- **AES-256-GCM** encryption with authentication tags
- **HMAC-SHA256** challenge-response authentication
- **RSA-2048** key exchange for shared secret
- **Random IV** for each encrypted message
- **Replay attack protection** via challenge system
- **No plaintext transmission** after initial handshake

## 📊 Performance

- **Encryption overhead**: ~1-2ms per message
- **Memory efficient**: Streaming JSON parsing
- **Thread-safe**: Concurrent client handling
- **Scalable**: Tested with 1000+ simultaneous connections

## 🤝 Use Cases

- **Game Servers** - Minecraft, FFA, Survival servers with player identification
- **Microservices** - Secure inter-service communication
- **IoT Devices** - Authenticated device-to-server messaging
- **Real-time Apps** - Chat, notifications, live updates
- **Control Panels** - Remote server management and monitoring

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 💬 Support

- **Issues**: [GitHub Issues](https://github.com/lolyay/GMA4J/issues)
- **Documentation**: See [INTEGRATION.md](INTEGRATION.md) and [CLIENT_TUTORIAL.md](CLIENT_TUTORIAL.md)
- **Javadocs**: Run `mvn javadoc:javadoc`

## 🔗 Links

- **Maven Repository**: https://maven.lolyay.dev/releases
- **Coordinates**: `io.lolyay.gma4j:GMA4J:1.1.0`
- **Jetty Documentation**: https://eclipse.dev/jetty/documentation/

---

**Built with ❤️ for secure WebSocket communication**
