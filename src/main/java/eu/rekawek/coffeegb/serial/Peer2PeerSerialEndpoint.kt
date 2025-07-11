package eu.rekawek.coffeegb.serial

import eu.rekawek.coffeegb.cpu.BitUtils
import java.util.concurrent.atomic.AtomicInteger

class Peer2PeerSerialEndpoint(peer: Peer2PeerSerialEndpoint?) : SerialEndpoint {

  private lateinit var peer: Peer2PeerSerialEndpoint

  @Volatile private var sb: Int = 0xFF;

  private var bitsReceived = AtomicInteger()

  private var bitIndex: Int = 7

  init {
    if (peer != null) {
      this.peer = peer
      peer.peer = this
    }
  }

  override fun setSb(sb: Int) {
    this.sb = sb
  }

  override fun startSending() {
    bitIndex = 7
    bitsReceived.set(0)
  }

  override fun recvBit(): Int {
    if (bitsReceived.get() == 0) {
      return -1
    }
    bitsReceived.decrementAndGet()
    return shift()
  }

  override fun sendBit(): Int {
    peer.bitsReceived.incrementAndGet()
    return shift()
  }

  override fun sendByte(): Int {
    throw UnsupportedOperationException()
  }

  override fun recvByte(): Int {
    throw UnsupportedOperationException()
  }

  private fun shift(): Int {
    val bit = if (BitUtils.getBit(peer.sb, bitIndex)) 1 else 0
    if (--bitIndex == -1) {
      bitIndex = 7
    }
    return bit
  }

}
