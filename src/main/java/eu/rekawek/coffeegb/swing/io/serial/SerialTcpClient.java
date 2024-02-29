package eu.rekawek.coffeegb.swing.io.serial;

import eu.rekawek.coffeegb.serial.StreamSerialEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;

public class SerialTcpClient implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(SerialTcpServer.class);
    private final String host;
    private final SerialEndpointWrapper serialEndpointWrapper;
    private Socket clientSocket;
    private StreamSerialEndpoint endpoint;

    public SerialTcpClient(String host, SerialEndpointWrapper serialEndpointWrapper) {
        this.host = host;
        this.serialEndpointWrapper = serialEndpointWrapper;
    }

    @Override
    public void run() {
        try {
            clientSocket = new Socket(host, SerialTcpServer.PORT);
            LOG.info("Connected to {}", clientSocket.getInetAddress());

            endpoint = new StreamSerialEndpoint(clientSocket.getInputStream(), clientSocket.getOutputStream());
            serialEndpointWrapper.setDelegate(endpoint);
            endpoint.run();
        } catch (IOException e) {
            LOG.error("Error in making connection", e);
        }
    }

    public void stop() {
        serialEndpointWrapper.setDelegate(null);
        if (endpoint != null) {
            endpoint.stop();
        }
        try {
            if (clientSocket != null) {
                clientSocket.close();
            }
        } catch (IOException e) {
            LOG.error("Error in closing client socket", e);
        }
    }
}
