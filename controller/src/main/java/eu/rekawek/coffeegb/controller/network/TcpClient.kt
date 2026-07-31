package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.Volatile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class TcpClient private constructor(
    private val host: String,
    private val eventBus: EventBus,
    private val attemptId: Long,
    private val lifecyclePublisher: (Event) -> Unit,
    private val socketFactory: () -> Socket,
    private val addressResolver: (String) -> InetAddress,
    private val resolutionTimeoutMillis: Int,
    private val connectTimeoutMillis: Int,
) : Runnable {
  constructor(
      host: String,
      eventBus: EventBus,
      attemptId: Long = ConnectionController.LEGACY_ATTEMPT,
      lifecyclePublisher: (Event) -> Unit = { eventBus.post(it) },
  ) : this(
      host,
      eventBus,
      attemptId,
      lifecyclePublisher,
      { Socket() },
      { InetAddress.getByName(it) },
      RESOLUTION_TIMEOUT_MILLIS,
      CONNECT_TIMEOUT_MILLIS,
  )

  @Volatile private var doStop = false

  @Volatile private var clientSocket: Socket? = null

  @Volatile private var pendingResolution: FutureTask<InetAddress>? = null

  private val terminalPublished = AtomicBoolean()

  override fun run() {
    try {
      if (doStop) return
      val socket = socketFactory()
      clientSocket = socket
      configure(socket)
      if (doStop) {
        socket.close()
        return
      }
      connect(socket)
      if (doStop) {
        socket.close()
        return
      }
      LOG.atInfo().log("Connected to netplay peer")
      Connection(
          socket.getInputStream(),
          socket.getOutputStream(),
          eventBus,
          false,
          cancelTransport = { socket.close() },
          attemptId = attemptId,
          lifecyclePublisher = lifecyclePublisher,
      )
          .use {
            lifecyclePublisher(
                ConnectionController.ClientHandshakeCompletedEvent(it.mode, it.player, attemptId))
            it.run()
          }
      LOG.atInfo().log("Disconnected from netplay peer")
    } catch (e: Connection.ConnectionRejectedException) {
      LOG.info("Connection rejected: {}", e.reason.userMessage)
      lifecyclePublisher(
          ConnectionController.ClientConnectionRejectedEvent(e.reason.userMessage, attemptId))
    } catch (e: Connection.ProtocolException) {
      val message = safePeerDiagnostic(e.message, e.reason.userMessage)
      LOG.info("Netplay protocol error: {}", message)
      lifecyclePublisher(ConnectionController.ClientProtocolErrorEvent(message, attemptId))
    } catch (e: Connection.CompatibilityException) {
      val message = safePeerDiagnostic(e.message, "Incompatible peer")
      LOG.info("Netplay compatibility error: {}", message)
      lifecyclePublisher(
          ConnectionController.ClientProtocolErrorEvent(
              message,
              attemptId,
          ))
    } catch (e: SocketException) {
      if (doStop || e.message == "Socket closed") {
        LOG.atInfo().log("Disconnected from server")
      } else {
        LOG.info("Netplay connection failed: {}", netplaySocketFailureSummary(e))
      }
    } catch (e: IOException) {
      if (doStop) LOG.atInfo().log("Disconnected from server")
      else LOG.info("Netplay connection failed: {}", netplaySocketFailureSummary(e))
    } finally {
      pendingResolution?.cancel(true)
      pendingResolution = null
      try {
        clientSocket?.close()
      } catch (e: IOException) {
        LOG.debug("Unable to close netplay client transport ({})", e.javaClass.simpleName)
      }
      clientSocket = null
      publishTerminalOnce()
    }
  }

  /**
   * Cancels transport and publishes the matching terminal lifecycle callback synchronously before
   * returning. Production event dispatch is synchronous, so a Netplay Cancel action leaves its
   * stopping state in the same command dispatch. A platform DNS call may ignore interruption, but
   * it runs on a daemon behind a canceled future and cannot publish into the retired generation.
   */
  fun stop() {
    doStop = true
    pendingResolution?.cancel(true)
    val socket = clientSocket
    try {
      socket?.close()
    } catch (e: IOException) {
      LOG.debug("Unable to close netplay client transport ({})", e.javaClass.simpleName)
    } finally {
      publishTerminalOnce()
    }
  }

  private fun publishTerminalOnce() {
    if (terminalPublished.compareAndSet(false, true)) {
      lifecyclePublisher(ConnectionController.ClientDisconnectedFromServerEvent(attemptId))
    }
  }

  private fun connect(socket: Socket) {
    val destination = parseDestination(host)
    val address = resolve(destination.host)
    if (doStop) throw SocketException("Netplay connection canceled")
    socket.connect(InetSocketAddress(address, destination.port), connectTimeoutMillis)
  }

  private fun resolve(host: String): InetAddress {
    val task = FutureTask { addressResolver(host) }
    pendingResolution = task
    if (doStop) task.cancel(true)
    Thread(task, resolverThreadName(attemptId)).apply {
      isDaemon = true
      start()
    }
    return try {
      task.get(resolutionTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
    } catch (_: CancellationException) {
      throw SocketException("Netplay host resolution canceled")
    } catch (_: TimeoutException) {
      task.cancel(true)
      throw SocketTimeoutException("Netplay host resolution timed out")
    } catch (e: InterruptedException) {
      task.cancel(true)
      Thread.currentThread().interrupt()
      throw SocketException("Netplay host resolution interrupted")
    } catch (e: ExecutionException) {
      val cause = e.cause
      if (cause is IOException) throw cause
      throw IOException("Netplay host resolution failed", cause)
    } finally {
      if (pendingResolution === task) pendingResolution = null
    }
  }

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(TcpClient::class.java)
    internal const val RESOLUTION_TIMEOUT_MILLIS = 5_000
    internal const val CONNECT_TIMEOUT_MILLIS = 5_000

    private fun parseDestination(value: String): Destination =
        if (value.contains(":")) {
          Destination(value.substringBefore(":"), value.substringAfter(":").toInt())
        } else {
          Destination(value, TcpServer.PORT)
        }

    private fun resolverThreadName(attemptId: Long): String =
        if (attemptId == ConnectionController.LEGACY_ATTEMPT) "netplay-resolver"
        else "netplay-resolver-$attemptId"

    internal fun forTest(
        host: String,
        eventBus: EventBus,
        attemptId: Long,
        lifecyclePublisher: (Event) -> Unit = { eventBus.post(it) },
        socketFactory: () -> Socket = { Socket() },
        addressResolver: (String) -> InetAddress = { InetAddress.getByName(it) },
        resolutionTimeoutMillis: Int = RESOLUTION_TIMEOUT_MILLIS,
        connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    ): TcpClient =
        TcpClient(
            host,
            eventBus,
            attemptId,
            lifecyclePublisher,
            socketFactory,
            addressResolver,
            resolutionTimeoutMillis,
            connectTimeoutMillis,
        )

    /**
     * The link exchanges tiny input packets whose delivery time directly affects the
     * emulation sync: disable Nagle's algorithm so they are not held back waiting for
     * ACKs, and enable keepalive so a vanished peer eventually fails the blocking read.
     */
    fun configure(socket: Socket) {
      socket.tcpNoDelay = true
      socket.keepAlive = true
    }
  }

  private data class Destination(val host: String, val port: Int)
}

internal fun netplaySocketFailureSummary(failure: IOException): String =
    when (failure) {
      is UnknownHostException -> "host resolution failed"
      is SocketTimeoutException -> "connection timed out"
      is ConnectException -> "connection refused"
      is SocketException -> "socket I/O failed"
      else -> "network I/O failed"
    }

internal fun safePeerDiagnostic(message: String?, fallback: String): String =
    message
        ?.let(NetplayDiagnosticSanitizer::redact)
        ?.takeIf(String::isNotBlank)
        ?: fallback
