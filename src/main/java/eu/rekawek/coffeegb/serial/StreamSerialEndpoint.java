package eu.rekawek.coffeegb.serial;

import eu.rekawek.coffeegb.cpu.BitUtils;
import java.io.*;
import java.nio.ByteBuffer;

public class StreamSerialEndpoint implements SerialEndpoint, Runnable {
    private enum Command {
        SET_SB, SEND_BIT
    }

    private final InputStream is;
    private final OutputStream os;
    private volatile int remoteSb = 0xFF;
    private volatile boolean bitReceived = false;
    private int getBitIndex = 7;
    private volatile boolean doStop = false;

    public StreamSerialEndpoint(InputStream is, OutputStream os) {
        this.is = is;
        this.os = os;
    }

    @Override
    public void setSb(int sb) {
        sendCommand(Command.SET_SB, sb);
    }

    @Override
    public int recvBit() {
        if (!bitReceived) {
            return -1;
        }
        bitReceived = false;
        return shift();
    }

    @Override
    public void startSending() {
        getBitIndex = 7;
        bitReceived = false;
    }

    @Override
    public int sendBit() {
        sendCommand(Command.SEND_BIT, 0);
        return shift();
    }

    private int shift() {
        int bit = BitUtils.getBit(remoteSb, getBitIndex) ? 1 : 0;
        if (--getBitIndex == -1) {
            getBitIndex = 7;
        }
        return bit;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[5];
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        while (!doStop) {
            try {
                is.readNBytes(buffer, 0, 5);
                byteBuffer.rewind();
                handlePacket(byteBuffer);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void stop() {
        doStop = true;
    }

    private void sendCommand(Command command, int argument) {
        byte[] buffer = new byte[5];
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        createPacket(byteBuffer, command, argument);
        try {
            os.write(buffer);
            os.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createPacket(ByteBuffer buffer, Command command, int argument) {
        buffer.put((byte) command.ordinal());
        buffer.putInt(argument);
    }

    private void handlePacket(ByteBuffer buffer) {
        Command command = Command.values()[buffer.get()];
        int argument = buffer.getInt();
        switch (command) {
            case SET_SB -> remoteSb = argument;
            case SEND_BIT -> bitReceived = true;
        }
    }
}
