package eu.rekawek.coffeegb.core.serial;

public interface ByteReceiver {
    void onNewByte(int receivedByte);
}
