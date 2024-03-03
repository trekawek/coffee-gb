package eu.rekawek.coffeegb.swing.io.serial

import eu.rekawek.coffeegb.serial.SerialEndpoint

class SerialEndpointWrapper : SerialEndpoint {
    private var sb = 0

    private var delegate: SerialEndpoint? = null

    fun setDelegate(delegate: SerialEndpoint?) {
        this.delegate = delegate
        setSb(sb)
    }

    override fun setSb(sb: Int) {
        this.sb = sb
        if (delegate != null) {
            delegate!!.setSb(sb)
        }
    }

    override fun recvBit(): Int {
        if (delegate != null) {
            return delegate!!.recvBit()
        }
        return -1
    }

    override fun recvByte(): Int {
        if (delegate != null) {
            return delegate!!.recvByte()
        }
        return -1
    }

    override fun startSending() {
        if (delegate != null) {
            delegate!!.startSending()
        }
    }

    override fun sendBit(): Int {
        if (delegate != null) {
            return delegate!!.sendBit()
        }
        return 1
    }

    override fun sendByte(): Int {
        if (delegate != null) {
            return delegate!!.sendByte()
        }
        return 0xFF;
    }
}
