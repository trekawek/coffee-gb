package eu.rekawek.coffeegb.serial;

public interface ByteReceiver {
    void onNewByte(int receivedByte);
}
