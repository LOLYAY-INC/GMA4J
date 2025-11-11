package io.lolyay.gma4j.net.server;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

/**
 * Simple Jetty WebSocket server.
 */
public class WebSocketServer {
    private final Server server;
    private final int port;

    public WebSocketServer(int port, ServerWebSocketHandler.PacketHandler packetHandler) {
        this.port = port;
        this.server = new Server();
        
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        // Configure WebSocket
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.setMaxTextMessageSize(65536);
            wsContainer.addMapping("/ws", (req, resp) -> new ServerWebSocketHandler(packetHandler));
        });
    }

    public void start() throws Exception {
        server.start();
        System.out.println("WebSocket server started on port " + port);
        System.out.println("Connect to: ws://localhost:" + port + "/ws");
    }

    public void join() throws InterruptedException {
        server.join();
    }

    public void stop() throws Exception {
        server.stop();
    }
}
