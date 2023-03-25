package eu.rekawek.coffeegb.serial;

public class ByteReceivingSerialEndpoint implements SerialEndpoint {

    private final ByteReceiver byteReceiver;
    private int receivedBits;
    private int currentByte;

    public ByteReceivingSerialEndpoint(ByteReceiver byteReceiver) {
        this.byteReceiver = byteReceiver;
    }

    @Override
    public int receive(int bitToTransfer) {
        receivedBits++;
        currentByte = currentByte << 1 | (bitToTransfer & 1);
        if (receivedBits == 8) {
            byteReceiver.onNewByte(currentByte);
            currentByte = 0;
            receivedBits = 0;
        }
        return 0;
    }

    @Override
    public int send(int bitToTransfer) {
        return receive(bitToTransfer);
    }
}
