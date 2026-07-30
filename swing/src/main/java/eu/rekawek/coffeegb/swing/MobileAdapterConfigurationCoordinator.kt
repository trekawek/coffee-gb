package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfiguration
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationSaveResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationStore
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterNetworkPolicy
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterTransport
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterDestinationPolicy
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterDestinationRule
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterDnsResolver
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterIpv4Address
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterNetworkBackend
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterRuntimeAuthorization
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterTransportProtocol
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterTransportTarget
import eu.rekawek.coffeegb.core.events.EventBus
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Runtime-only authorization view; neither flag is accepted by the durable codec. */
internal data class MobileAdapterRuntimeUiState(
    val revision: Long,
    val configuration: MobileAdapterConfiguration,
    val networkConsent: Boolean,
    val privateLocalDevelopment: Boolean,
)

/**
 * Owns the launcher-loaded private policy and the two non-persistent runtime authorization gates.
 *
 * Disk writes run on one daemon worker. A policy edit first revokes the currently attached
 * backend, then becomes visible to endpoint preparation only after the owner-only record commits.
 */
internal class MobileAdapterConfigurationCoordinator(
    initialConfiguration: MobileAdapterConfiguration,
    private val store: MobileAdapterConfigurationStore,
) : AutoCloseable {
  /** Orders each runtime mutation with its synchronous cancel/refresh publication. */
  private val transitionPublicationLock = Any()
  private val authorityLock = Any()
  private var coordinatorClosed = false
  private val revision = AtomicLong(1)
  private val current =
      AtomicReference(
          MobileAdapterRuntimeUiState(
              revision = revision.get(),
              configuration = initialConfiguration,
              networkConsent = false,
              privateLocalDevelopment = false,
          ))
  private val durableConfiguration = AtomicReference(initialConfiguration)
  private val activeBackends = ConcurrentHashMap.newKeySet<MobileAdapterNetworkBackend>()
  private val writer: ExecutorService =
      ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          ArrayBlockingQueue(MAX_PENDING_POLICY_WRITES),
          { task -> Thread(task, "mobile-adapter-configuration").apply { isDaemon = true } },
          ThreadPoolExecutor.AbortPolicy(),
      )

  val provider =
      Controller.MobileAdapterConfigurationProvider {
        synchronized(authorityLock) {
          check(!coordinatorClosed) { "Mobile Adapter configuration is closed" }
          val snapshot = current.get()
          val policy = snapshot.configuration.networkPolicy
          val backend =
              if (policy == MobileAdapterNetworkPolicy.Offline) {
                null
              } else {
                createBackend(snapshot, policy)
              }
          Controller.MobileAdapterConfiguration(
              snapshot.configuration.deviceId,
              snapshot.configuration.configurationBytes(),
              snapshot.revision,
              backend,
              snapshot.networkConsent && backend != null,
              snapshot.privateLocalDevelopment && backend != null,
          )
        }
      }

  fun snapshot(): MobileAdapterRuntimeUiState = current.get()

  /** Applies session-only gates and replaces the attached backend at the next frame boundary. */
  fun applyRuntimeAuthorization(
      expectedRevision: Long,
      networkConsent: Boolean,
      privateLocalDevelopment: Boolean,
      eventBus: EventBus,
  ): Boolean =
      synchronized(transitionPublicationLock) {
        val replacement =
            synchronized(authorityLock) {
              check(!coordinatorClosed) { "Mobile Adapter configuration is closed" }
              val before = current.get()
              if (before.revision != expectedRevision) {
                null
              } else {
                val custom =
                    before.configuration.networkPolicy is MobileAdapterNetworkPolicy.CustomServer
                val updated =
                    before.copy(
                        revision = nextRevision(),
                        networkConsent = custom && networkConsent,
                        privateLocalDevelopment =
                            custom && networkConsent && privateLocalDevelopment,
                    )
                revokePreparedBackends()
                current.set(updated)
                updated
              }
            }
        if (replacement == null) {
          false
        } else {
          postRevocationAndRefresh(eventBus, replacement.revision)
          true
        }
      }

  /**
   * When [expectedRevision] is current, revokes runtime gates immediately and saves a validated
   * policy away from the EDT. Stale and queue-full results may complete on the caller; scheduled
   * results complete on the writer. Every callback carries only a stable typed result.
   */
  fun savePolicy(
      expectedRevision: Long,
      replacementPolicy: MobileAdapterNetworkPolicy,
      eventBus: EventBus,
      completed: (MobileAdapterConfigurationSaveResult) -> Unit,
  ) {
    var immediateResult: MobileAdapterConfigurationSaveResult? = null
    synchronized(transitionPublicationLock) {
      val prepared =
          synchronized(authorityLock) {
            check(!coordinatorClosed) { "Mobile Adapter configuration is closed" }
            val before = current.get()
            if (before.revision != expectedRevision) {
              null
            } else {
              val updated =
                  before.copy(
                      revision = nextRevision(),
                      networkConsent = false,
                      privateLocalDevelopment = false,
                  )
              revokePreparedBackends()
              current.set(updated)
              MobileAdapterConfiguration(
                  before.configuration.deviceId,
                  before.configuration.configurationBytes(),
                  replacementPolicy,
              ) to updated
            }
          }
      if (prepared == null) {
        immediateResult =
            MobileAdapterConfigurationSaveResult(
                saved = false,
                error = MobileAdapterConfigurationError.CONFIGURATION_STALE,
            )
      } else {
        val (candidate, revoked) = prepared
        // Cancellation only rotates the attached backend generation; it does not mutate that
        // backend's authorization. Replace it immediately with the old policy plus revoked runtime
        // gates, so no new guest request can reuse consent while the owner-only write is pending or
        // after a failed write.
        postRevocationAndRefresh(eventBus, revoked.revision)

        try {
          // Submission shares the transition lock with publication so concurrent saves enter the
          // single writer in the same order as their revisions and controller notifications.
          writer.execute {
            val result = store.save(candidate)
            // Saves are serialized, but callers can enqueue another edit or grant runtime consent
            // on the interim revision before this one finishes. Reconcile after every result
            // (including failure) and revoke again at that authority boundary, so the runtime can
            // never lag behind the last durable commit or retain interim authorization.
            val committedConfiguration =
                if (result.saved) {
                  durableConfiguration.set(candidate)
                  candidate
                } else {
                  durableConfiguration.get()
                }
            synchronized(transitionPublicationLock) {
              val reconciled =
                  synchronized(authorityLock) {
                    if (coordinatorClosed) {
                      null
                    } else {
                      val value =
                          MobileAdapterRuntimeUiState(
                              revision = nextRevision(),
                              configuration = committedConfiguration,
                              networkConsent = false,
                              privateLocalDevelopment = false,
                          )
                      revokePreparedBackends()
                      current.set(value)
                      value
                    }
                  }
              reconciled?.let { postRevocationAndRefresh(eventBus, it.revision) }
            }
            completeSafely(completed, result)
          }
        } catch (_: RejectedExecutionException) {
          immediateResult =
              MobileAdapterConfigurationSaveResult(
                  saved = false,
                  error = MobileAdapterConfigurationError.CONFIGURATION_BUSY,
              )
        }
      }
    }
    immediateResult?.let { completeSafely(completed, it) }
  }

  override fun close() {
    val closeDeadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_TIMEOUT_MILLIS)
    val closingBackends =
        synchronized(authorityLock) {
          if (!coordinatorClosed) {
            coordinatorClosed = true
            revokePreparedBackends()
          }
          activeBackends.toList()
        }
    closingBackends.forEach(MobileAdapterNetworkBackend::close)
    writer.shutdown()
    try {
      val gracefulMillis =
          minOf(SHUTDOWN_GRACE_MILLIS, remainingShutdownMillis(closeDeadlineNanos))
      if (!writer.awaitTermination(gracefulMillis, TimeUnit.MILLISECONDS)) {
        writer.shutdownNow()
        if (!writer.awaitTermination(
                remainingShutdownMillis(closeDeadlineNanos),
                TimeUnit.MILLISECONDS,
            )) {
          throw IllegalStateException(
              "Mobile Adapter configuration writer did not stop before the shutdown deadline")
        }
      }
      closingBackends.forEach { backend ->
        if (!backend.awaitTermination(
                remainingShutdownMillis(closeDeadlineNanos),
                TimeUnit.MILLISECONDS,
            )) {
          throw IllegalStateException(
              "Mobile Adapter network backend did not stop before the shutdown deadline")
        }
      }
    } catch (interrupted: InterruptedException) {
      writer.shutdownNow()
      Thread.currentThread().interrupt()
      throw IllegalStateException(
          "Interrupted while stopping the Mobile Adapter configuration writer",
          interrupted,
      )
    }
  }

  private fun remainingShutdownMillis(deadlineNanos: Long): Long {
    val remaining = deadlineNanos - System.nanoTime()
    return if (remaining <= 0) 0 else maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining))
  }

  private fun nextRevision(): Long =
      revision.updateAndGet { value -> Math.addExact(value, 1) }

  private fun createBackend(
      snapshot: MobileAdapterRuntimeUiState,
      policy: MobileAdapterNetworkPolicy,
  ): MobileAdapterNetworkBackend {
    if (activeBackends.size >= MAX_TRACKED_NETWORK_BACKENDS) {
      throw Controller.SerialPeripheralPreparationException(
          Controller.SerialPeripheralError.ENDPOINT_UNAVAILABLE)
    }
    val backend =
        MobileAdapterNetworkBackend(
            destinationPolicy(snapshot.revision, policy),
            MobileAdapterRuntimeAuthorization(
                snapshot.networkConsent,
                snapshot.privateLocalDevelopment,
            ),
            onClosed = { backend -> activeBackends.remove(backend) },
        )
    activeBackends.add(backend)
    // The owner can fail and notify before this add. Only completed termination is safe to remove;
    // logical close begins before its sockets and selector have necessarily been released.
    if (backend.isTerminated()) activeBackends.remove(backend)
    return backend
  }

  /** Direct monotonic revocation is the authority boundary; event delivery is only handoff/UI. */
  private fun revokePreparedBackends() {
    activeBackends.forEach(MobileAdapterNetworkBackend::revokeAuthorization)
  }

  /**
   * Both ownership messages are attempted even when an unrelated synchronous subscriber fails.
   * Authority has already been revoked directly; these messages request endpoint cleanup and
   * presentation only. Subscriber exception text is deliberately neither logged nor returned.
   */
  private fun postRevocationAndRefresh(eventBus: EventBus, revision: Long) {
    try {
      eventBus.post(Controller.CancelMobileAdapterNetworkEvent)
    } catch (_: RuntimeException) {
      // The replacement below is the second, independently owned revocation path.
    }
    val stillCurrent =
        synchronized(authorityLock) {
          !coordinatorClosed && current.get().revision == revision
        }
    if (!stillCurrent) return
    postRefreshSafely(eventBus, revision)
  }

  private fun postRefreshSafely(eventBus: EventBus, revision: Long) {
    try {
      eventBus.post(Controller.RefreshMobileAdapterConfigurationEvent(revision))
    } catch (_: RuntimeException) {
      // Direct backend revocation remains authoritative when event delivery is unavailable.
    }
  }

  private fun completeSafely(
      completed: (MobileAdapterConfigurationSaveResult) -> Unit,
      result: MobileAdapterConfigurationSaveResult,
  ) {
    try {
      completed(result)
    } catch (_: RuntimeException) {
      // UI callback failures cannot roll back an owner-only commit or revocation.
    }
  }

  companion object {
    const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L
    const val MAX_PENDING_POLICY_WRITES = 1
    const val MAX_TRACKED_NETWORK_BACKENDS = 2
    private const val SHUTDOWN_GRACE_MILLIS = SHUTDOWN_TIMEOUT_MILLIS / 2

    internal fun destinationPolicy(
        revision: Long,
        policy: MobileAdapterNetworkPolicy,
    ): MobileAdapterDestinationPolicy =
        when (policy) {
          MobileAdapterNetworkPolicy.Offline -> MobileAdapterDestinationPolicy.offline(revision)
          is MobileAdapterNetworkPolicy.CustomServer -> {
            val target = MobileAdapterTransportTarget.parse(policy.dnsQueryName)
            val rules =
                policy.portMappings.map { mapping ->
                  MobileAdapterDestinationRule(
                      policy.dnsQueryName,
                      target,
                      when (mapping.transport) {
                        MobileAdapterTransport.TCP -> MobileAdapterTransportProtocol.TCP
                        MobileAdapterTransport.UDP -> MobileAdapterTransportProtocol.UDP
                      },
                      mapping.guestPort,
                      mapping.targetPort,
                  )
                }
            MobileAdapterDestinationPolicy(
                revision,
                MobileAdapterDnsResolver(
                    MobileAdapterIpv4Address.parse(policy.resolverIpv4Address),
                    policy.resolverPort,
                ),
                rules,
            )
          }
        }

    fun stableSaveError(result: MobileAdapterConfigurationSaveResult): MobileAdapterConfigurationError =
        checkNotNull(result.error) { "A successful Mobile Adapter save has no error" }
  }
}
