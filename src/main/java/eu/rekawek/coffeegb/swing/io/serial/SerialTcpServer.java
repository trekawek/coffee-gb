package eu.rekawek.coffeegb.swing.io.serial;

import eu.rekawek.coffeegb.serial.StreamSerialEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class SerialTcpServer implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(SerialTcpServer.class);
    static final int PORT = 6688;
    private final SerialEndpointWrapper serialEndpointWrapper;
    private ServerSocket serverSocket;
    private volatile boolean doStop;
    private StreamSerialEndpoint endpoint;

    public SerialTcpServer(SerialEndpointWrapper serialEndpointWrapper) {
        this.serialEndpointWrapper = serialEndpointWrapper;
    }

    @Override
    public void run() {
        doStop = false;
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        while (!doStop) {
            Socket socket;
            try {
                socket = serverSocket.accept();
                LOG.info("Got new connection: {}", socket.getInetAddress());
                endpoint = new StreamSerialEndpoint(socket.getInputStream(), socket.getOutputStream());
                serialEndpointWrapper.setDelegate(endpoint);
                endpoint.run();
            } catch (IOException e) {
                LOG.error("Error in accepting connection", e);
            }
        }
    }

    public void stop() {
        serialEndpointWrapper.setDelegate(null);
        doStop = true;
        if (endpoint != null) {
            endpoint.stop();
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.error("Error in closing server socket", e);
        }
    }
}
