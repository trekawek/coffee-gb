package eu.rekawek.coffeegb.swing.io.serial

import eu.rekawek.coffeegb.cpu.BitUtils
import eu.rekawek.coffeegb.serial.SerialEndpoint
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

class StreamSerialEndpoint(private val inputStream: InputStream, private val outputStream: OutputStream) :
    SerialEndpoint, Runnable {

    private var localSb = 0xFF

    @Volatile
    private var remoteSb = 0xFF
    private val bitsReceived = AtomicInteger()
    private var getBitIndex = 7

    @Volatile
    private var doStop = false

    override fun setSb(sb: Int) {
        if (localSb != sb) {
            sendCommand(Command.SET_SB, sb)
            localSb = sb
        }
    }

    override fun recvBit(): Int {
        if (bitsReceived.get() == 0) {
            return -1
        }
        bitsReceived.decrementAndGet()
        return shift()
    }

    override fun recvByte(): Int {
        if (bitsReceived.get() < 8) {
            return -1
        }
        bitsReceived.addAndGet(-8)
        return remoteSb
    }

    override fun startSending() {
        getBitIndex = 7
        bitsReceived.set(0)
    }

    override fun sendBit(): Int {
        sendCommand(Command.SEND_BIT, 1)
        return shift()
    }

    override fun sendByte(): Int {
        sendCommand(Command.SEND_BIT, 8)
        return remoteSb
    }

    private fun shift(): Int {
        val bit = if (BitUtils.getBit(remoteSb, getBitIndex)) 1 else 0
        if (--getBitIndex == -1) {
            getBitIndex = 7
        }
        return bit
    }

    override fun run() {
        val buffer = ByteArray(5)
        val byteBuffer = ByteBuffer.wrap(buffer)
        while (!doStop) {
            try {
                if (inputStream.readNBytes(buffer, 0, 5) < 5) {
                    break
                }
                byteBuffer.rewind()
                handlePacket(byteBuffer)
            } catch (e: IOException) {
                LOG.error("Can't read the input stream", e)
                break
            }
        }
    }

    fun stop() {
        doStop = true
        inputStream.close()
    }

    private fun sendCommand(command: Command, argument: Int) {
        val buffer = ByteArray(5)
        val byteBuffer = ByteBuffer.wrap(buffer)
        createPacket(byteBuffer, command, argument)
        try {
            outputStream.write(buffer)
            outputStream.flush()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun createPacket(buffer: ByteBuffer, command: Command, argument: Int) {
        buffer.put(command.ordinal.toByte())
        buffer.putInt(argument)
    }

    private fun handlePacket(buffer: ByteBuffer) {
        val command = Command.entries[buffer.get().toInt()]
        val argument = buffer.getInt()
        when (command) {
            Command.SET_SB -> remoteSb = argument
            Command.SEND_BIT -> bitsReceived.addAndGet(argument)
        }
    }

    private enum class Command {
        SET_SB, SEND_BIT
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(StreamSerialEndpoint::class.java)
    }
}
