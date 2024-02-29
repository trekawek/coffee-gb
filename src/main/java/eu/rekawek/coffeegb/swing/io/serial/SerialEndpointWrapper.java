package eu.rekawek.coffeegb.swing.io.serial;

import eu.rekawek.coffeegb.serial.SerialEndpoint;

public class SerialEndpointWrapper implements SerialEndpoint {

    private int sb;

    private SerialEndpoint delegate;

    public void setDelegate(SerialEndpoint delegate) {
        this.delegate = delegate;
        setSb(sb);
    }

    @Override
    public void setSb(int sb) {
        this.sb = sb;
        if (delegate != null) {
            delegate.setSb(sb);
        }
    }

    @Override
    public int recvBit() {
        if (delegate != null) {
            return delegate.recvBit();
        }
        return -1;
    }

    @Override
    public void startSending() {
        if (delegate != null) {
            delegate.startSending();
        }
    }

    @Override
    public int sendBit() {
        if (delegate != null) {
            return delegate.sendBit();
        }
        return 1;
    }
}
