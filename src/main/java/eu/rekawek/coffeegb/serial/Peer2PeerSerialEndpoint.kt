package eu.rekawek.coffeegb.serial

import eu.rekawek.coffeegb.cpu.BitUtils
import eu.rekawek.coffeegb.memento.Memento
import eu.rekawek.coffeegb.memento.Originator
import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger

class Peer2PeerSerialEndpoint() :
    SerialEndpoint, Serializable, Originator<Peer2PeerSerialEndpoint> {

  @Transient private lateinit var peer: Peer2PeerSerialEndpoint

  private var sb: Int = 0xFF

  private var bitsReceived = AtomicInteger()

  private var bitIndex: Int = 7

  fun init(peer: Peer2PeerSerialEndpoint) {
    this.peer = peer
    peer.peer = this
  }

  override fun setSb(sb: Int) {
    this.sb = sb
  }

  override fun startSending() {
    bitIndex = 7
    peer.bitsReceived.set(0)
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

  override fun saveToMemento(): Memento<Peer2PeerSerialEndpoint> {
    return Peer2PeerSerialEndpointMemento(sb, bitsReceived.get(), bitIndex)
  }

  override fun restoreFromMemento(memento: Memento<Peer2PeerSerialEndpoint>) {
    if (memento is Peer2PeerSerialEndpointMemento) {
      sb = memento.sb
      bitsReceived.set(memento.bitsReceived)
      bitIndex = memento.bitIndex
    } else {
      throw IllegalArgumentException("Invalid memento type")
    }
  }

  private data class Peer2PeerSerialEndpointMemento(
      val sb: Int,
      val bitsReceived: Int,
      val bitIndex: Int
  ) : Memento<Peer2PeerSerialEndpoint>
}
