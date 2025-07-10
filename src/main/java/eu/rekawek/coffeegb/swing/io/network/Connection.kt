package eu.rekawek.coffeegb.swing.io.network

import java.io.InputStream
import java.io.OutputStream
import java.lang.Thread.sleep
import kotlin.concurrent.Volatile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Connection(
    private val isServer: Boolean,
    private val inputStream: InputStream,
    private val outputStream: OutputStream
) : Runnable {

  @Volatile private var doStop = false

  override fun run() {
    while (!doStop) {
      if (isServer) {
        outputStream.write(128)
        outputStream.flush()
        sleep(500)
      } else {
        val byte = inputStream.read()
        if (byte == -1) {
          break
        }
        LOG.info("got byte: $byte")
      }
    }
  }

  fun stop() {
    doStop = true
    inputStream.close()
  }

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(Connection::class.java)
  }
}
