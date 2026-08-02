package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfiguration
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationImageReader
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationSaveResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationStore
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationOfferResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationPersistencePhase
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationPersistenceStatus
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationSink
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationWrite
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
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeoutException
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
    private val imageReader: MobileAdapterConfigurationImageReader =
        MobileAdapterConfigurationImageReader(),
) : AutoCloseable, MobileAdapterGuestConfigurationSink {
  /** Orders each runtime mutation with its synchronous cancel/refresh publication. */
  private val transitionPublicationLock = Any()
  private val authorityLock = Any()
  private val guestPersistenceLock = Object()
  @Volatile private var coordinatorClosed = false
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
  private var committedAttachmentId = 0L
  private var acceptedMutationRevision = 0L
  private var guestImageGeneration = 0L
  private var guestPersistenceSequence = 0L
  private var completedGuestAttempt = 0L
  private var guestTaskScheduled = false
  private var pendingGuestWrite: PendingGuestWrite? = null
  private var lastGuestAttempt: CompletedGuestAttempt? = null
  private val guestStatuses = ArrayBlockingQueue<MobileAdapterGuestConfigurationPersistenceStatus>(
      MAX_PENDING_GUEST_STATUSES)
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

  /**
   * Commits the controller-owned attachment generation used to fence delayed old-endpoint writes.
   * Attachment IDs are monotonic for one controller lifetime, so an older delayed notification
   * cannot reopen a retired attachment.
   */
  override fun attachmentCommitted(attachmentId: Long) {
    require(attachmentId > 0) { "Mobile Adapter attachment ID must be positive" }
    synchronized(guestPersistenceLock) {
      if (coordinatorClosed || attachmentId <= committedAttachmentId) return
      committedAttachmentId = attachmentId
      acceptedMutationRevision = 0
    }
  }

  /**
   * Retains a detached latest image without waiting for filesystem or executor capacity.
   *
   * The live configuration changes synchronously because the guest has already acknowledged the
   * write. Device identity, owner policy, and both runtime authorization gates remain unchanged.
   */
  override fun offer(
      write: MobileAdapterGuestConfigurationWrite
  ): MobileAdapterGuestConfigurationOfferResult =
      synchronized(transitionPublicationLock) {
        if (coordinatorClosed) {
          MobileAdapterGuestConfigurationOfferResult.CLOSED
        } else {
          val disposition =
              synchronized(guestPersistenceLock) {
                when {
                  coordinatorClosed -> GuestOfferDisposition.CLOSED
                  write.attachmentId != committedAttachmentId -> GuestOfferDisposition.STALE
                  write.mutationRevision <= acceptedMutationRevision ->
                      GuestOfferDisposition.DUPLICATE
                  else -> {
                    val configuration = write.configurationCopy()
                    acceptedMutationRevision = write.mutationRevision
                    guestImageGeneration = incrementExact(guestImageGeneration)
                    guestPersistenceSequence = incrementExact(guestPersistenceSequence)
                    val retained =
                        PendingGuestWrite(
                            sequence = guestPersistenceSequence,
                            attachmentId = write.attachmentId,
                            mutationRevision = write.mutationRevision,
                            imageGeneration = guestImageGeneration,
                            configuration = configuration,
                        )
                    pendingGuestWrite = retained
                    publishGuestStatusLocked(
                        retained,
                        MobileAdapterGuestConfigurationPersistencePhase.PENDING,
                    )
                    GuestOfferDisposition.Accepted(configuration)
                  }
                }
              }
          when (disposition) {
            GuestOfferDisposition.CLOSED -> MobileAdapterGuestConfigurationOfferResult.CLOSED
            GuestOfferDisposition.STALE ->
                MobileAdapterGuestConfigurationOfferResult.STALE_ATTACHMENT
            GuestOfferDisposition.DUPLICATE -> {
              MobileAdapterGuestConfigurationOfferResult.ACCEPTED
            }
            is GuestOfferDisposition.Accepted -> {
              val configuration = disposition.configuration
              synchronized(authorityLock) {
                val before = current.get()
                current.set(
                    before.copy(
                        configuration =
                            MobileAdapterConfiguration(
                                before.configuration.deviceId,
                                configuration,
                                before.configuration.networkPolicy,
                            ),
                    ))
              }
              kickPendingGuestWrite()
              MobileAdapterGuestConfigurationOfferResult.ACCEPTED
            }
          }
        }
      }

  override fun pollStatus(): MobileAdapterGuestConfigurationPersistenceStatus? =
      guestStatuses.poll()

  /**
   * Waits for one retained dirty image to commit, retrying one earlier failed attempt. The caller
   * freezes emulation before entering this close barrier, so no newer guest mutation can race its
   * target.
   */
  override fun flush(
      timeout: Long,
      unit: TimeUnit,
  ): MobileAdapterConfigurationSaveResult {
    require(timeout >= 0) { "Mobile Adapter flush timeout must not be negative" }
    val timeoutNanos = unit.toNanos(timeout)
    val deadline = saturatedDeadline(timeoutNanos)
    val (targetSequence, completedBeforeFlush) =
        synchronized(guestPersistenceLock) {
          val pending = pendingGuestWrite
              ?: return MobileAdapterConfigurationSaveResult(saved = true)
          pending.sequence to completedGuestAttempt
        }

    kickPendingGuestWrite()
    synchronized(guestPersistenceLock) {
      while (true) {
        val pending = pendingGuestWrite
        if (pending == null || pending.sequence != targetSequence) {
          return MobileAdapterConfigurationSaveResult(saved = true)
        }
        val completed = lastGuestAttempt
        if (!guestTaskScheduled &&
            completedGuestAttempt > completedBeforeFlush &&
            completed?.sequence == targetSequence) {
          return completed.result
        }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) {
          throw TimeoutException("Mobile Adapter configuration flush timed out")
        }
        TimeUnit.NANOSECONDS.timedWait(guestPersistenceLock, remaining)
      }
    }
  }

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
              replacementPolicy to updated
            }
          }
      if (prepared == null) {
        immediateResult =
            MobileAdapterConfigurationSaveResult(
                saved = false,
                error = MobileAdapterConfigurationError.CONFIGURATION_STALE,
            )
      } else {
        val (policy, revoked) = prepared
        // Cancellation only rotates the attached backend generation; it does not mutate that
        // backend's authorization. Replace it immediately with the old policy plus revoked runtime
        // gates, so no new guest request can reuse consent while the owner-only write is pending or
        // after a failed write.
        postRevocationAndRefresh(eventBus, revoked.revision)

        try {
          // Submission shares the transition lock with publication so concurrent saves enter the
          // single writer in the same order as their revisions and controller notifications.
          writer.execute {
            // An earlier queued image import may have committed while this policy save waited.
            // Apply the requested policy to the device and image that are durable when the write
            // actually runs, so orthogonal configuration updates cannot overwrite one another.
            val durable = durableConfiguration.get()
            val candidate =
                MobileAdapterConfiguration(
                    durable.deviceId,
                    durable.configurationBytes(),
                    policy,
                )
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
                      val before = current.get()
                      val value =
                          before.copy(
                              revision = nextRevision(),
                              configuration =
                                  MobileAdapterConfiguration(
                                      committedConfiguration.deviceId,
                                      before.configuration.configurationBytes(),
                                      committedConfiguration.networkPolicy,
                                  ),
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
            kickPendingGuestWrite()
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
    immediateResult?.let {
      kickPendingGuestWrite()
      completeSafely(completed, it)
    }
  }

  /**
   * Imports one bounded owner-selected adapter image away from the EDT.
   *
   * The imported image replaces only the game-visible 256-byte configuration. Coffee GB's
   * device ID and structured network policy remain authoritative, and both runtime authorization
   * gates are revoked before the source is read or durable storage is changed.
   */
  fun importConfigurationImage(
      expectedRevision: Long,
      source: Path,
      eventBus: EventBus,
      completed: (MobileAdapterConfigurationSaveResult) -> Unit,
  ) {
    var immediateResult: MobileAdapterConfigurationSaveResult? = null
    synchronized(transitionPublicationLock) {
      val (stale, revoked) =
          synchronized(authorityLock) {
            check(!coordinatorClosed) { "Mobile Adapter configuration is closed" }
            val before = current.get()
            val updated =
                before.copy(
                    revision = nextRevision(),
                    networkConsent = false,
                    privateLocalDevelopment = false,
                )
            revokePreparedBackends()
            current.set(updated)
            (before.revision != expectedRevision) to updated
          }
      // Import selection is itself an authority boundary. Revoke even when the window revision
      // raced stale; the stale result still performs no source read or durable write.
      postRevocationAndRefresh(eventBus, revoked.revision)
      if (stale) {
        immediateResult =
            MobileAdapterConfigurationSaveResult(
                saved = false,
                error = MobileAdapterConfigurationError.CONFIGURATION_STALE,
            )
      } else {
        val importImageGeneration = synchronized(guestPersistenceLock) { guestImageGeneration }
        try {
          writer.execute {
            val sourceError = store.validateImportSource(source)
            val read = if (sourceError == null) imageReader.read(source) else null
            val imported = read?.image()
            val candidate =
                imported?.let { image ->
                  // An earlier queued policy save may have committed while this import waited.
                  // Preserve the configuration that is durable when this write actually runs.
                  val durable = durableConfiguration.get()
                  MobileAdapterConfiguration(
                      durable.deviceId,
                      image,
                      durable.networkPolicy,
                  )
                }
            val result =
                if (sourceError != null) {
                  MobileAdapterConfigurationSaveResult(
                      saved = false,
                      error = sourceError,
                  )
                } else if (candidate == null) {
                  MobileAdapterConfigurationSaveResult(
                      saved = false,
                      error = checkNotNull(checkNotNull(read).error),
                  )
                } else {
                  store.saveImported(source, candidate)
                }
            val committedConfiguration =
                if (result.saved) {
                  durableConfiguration.set(checkNotNull(candidate))
                  candidate
                } else {
                  durableConfiguration.get()
                }
            synchronized(transitionPublicationLock) {
              val importedImageWins =
                  synchronized(guestPersistenceLock) {
                    val wins = result.saved && guestImageGeneration <= importImageGeneration
                    if (wins) {
                      pendingGuestWrite?.let { pending ->
                        if (pending.imageGeneration <= importImageGeneration) {
                          publishGuestStatusLocked(
                              pending,
                              MobileAdapterGuestConfigurationPersistencePhase.SUPERSEDED,
                          )
                          pendingGuestWrite = null
                        }
                      }
                      guestPersistenceLock.notifyAll()
                    }
                    wins
                  }
              val reconciled =
                  synchronized(authorityLock) {
                    if (coordinatorClosed) {
                      null
                    } else {
                      val before = current.get()
                      val visibleImage =
                          if (importedImageWins) {
                            checkNotNull(candidate).configurationBytes()
                          } else {
                            before.configuration.configurationBytes()
                          }
                      val value =
                          before.copy(
                              revision = nextRevision(),
                              configuration =
                                  MobileAdapterConfiguration(
                                      committedConfiguration.deviceId,
                                      visibleImage,
                                      committedConfiguration.networkPolicy,
                                  ),
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
            kickPendingGuestWrite()
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
    immediateResult?.let {
      kickPendingGuestWrite()
      completeSafely(completed, it)
    }
  }

  override fun close() {
    val closeDeadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_TIMEOUT_MILLIS)
    val closingBackends =
        synchronized(transitionPublicationLock) {
          val backends =
              synchronized(authorityLock) {
                if (!coordinatorClosed) {
                  coordinatorClosed = true
                  revokePreparedBackends()
                }
                activeBackends.toList()
              }
          synchronized(guestPersistenceLock) {
            guestPersistenceLock.notifyAll()
          }
          backends
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

  /** Attempts to place one constant-space dirty slot on the existing bounded writer. */
  private fun kickPendingGuestWrite() {
    val submit =
        synchronized(guestPersistenceLock) {
          if (coordinatorClosed || pendingGuestWrite == null || guestTaskScheduled) {
            false
          } else {
            guestTaskScheduled = true
            true
          }
        }
    if (!submit) return

    try {
      writer.execute { persistPendingGuestWrite() }
    } catch (_: RejectedExecutionException) {
      synchronized(guestPersistenceLock) {
        guestTaskScheduled = false
        guestPersistenceLock.notifyAll()
      }
      // Executor pressure never rejects the already acknowledged guest image. The next guest
      // offer, explicit-operation completion, or flush barrier supplies a bounded retry trigger.
    }
  }

  private fun persistPendingGuestWrite() {
    val attempted =
        synchronized(guestPersistenceLock) {
          pendingGuestWrite
              ?: run {
                guestTaskScheduled = false
                guestPersistenceLock.notifyAll()
                return
              }
        }
    val durable = durableConfiguration.get()
    val candidate =
        MobileAdapterConfiguration(
            durable.deviceId,
            attempted.configurationCopy(),
            durable.networkPolicy,
        )
    val result = store.save(candidate)
    if (result.saved) durableConfiguration.set(candidate)

    var retryNewerGuest = false
    synchronized(transitionPublicationLock) {
      // A guest save changes only the image. If an owner operation changed metadata while the
      // save was running, preserve the newest live image and reconcile that metadata separately
      // when the serialized owner operation completes.
      synchronized(guestPersistenceLock) {
        completedGuestAttempt = incrementExact(completedGuestAttempt)
        lastGuestAttempt = CompletedGuestAttempt(attempted.sequence, result)
        guestTaskScheduled = false
        publishGuestStatusLocked(
            attempted,
            if (result.saved) {
              MobileAdapterGuestConfigurationPersistencePhase.SAVED
            } else {
              MobileAdapterGuestConfigurationPersistencePhase.FAILED
            },
            result.error,
        )
        val latest = pendingGuestWrite
        if (result.saved && latest?.sequence == attempted.sequence) {
          pendingGuestWrite = null
        } else if (latest != null && latest.sequence != attempted.sequence) {
          // A later write is itself a retry trigger even when this older attempt failed.
          retryNewerGuest = true
        }
        guestPersistenceLock.notifyAll()
      }
    }
    if (retryNewerGuest) kickPendingGuestWrite()
  }

  private fun publishGuestStatusLocked(
      pending: PendingGuestWrite,
      phase: MobileAdapterGuestConfigurationPersistencePhase,
      error: MobileAdapterConfigurationError? = null,
  ) {
    val status =
        MobileAdapterGuestConfigurationPersistenceStatus(
            sequence = pending.sequence,
            attachmentId = pending.attachmentId,
            mutationRevision = pending.mutationRevision,
            phase = phase,
            error = error,
        )
    while (!guestStatuses.offer(status)) guestStatuses.poll()
  }

  private fun saturatedDeadline(timeoutNanos: Long): Long {
    val now = System.nanoTime()
    return try {
      Math.addExact(now, timeoutNanos)
    } catch (_: ArithmeticException) {
      Long.MAX_VALUE
    }
  }

  private fun remainingShutdownMillis(deadlineNanos: Long): Long {
    val remaining = deadlineNanos - System.nanoTime()
    return if (remaining <= 0) 0 else maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining))
  }

  private fun nextRevision(): Long =
      revision.updateAndGet { value -> Math.addExact(value, 1) }

  private fun incrementExact(value: Long): Long = Math.addExact(value, 1)

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

  private sealed interface GuestOfferDisposition {
    data object CLOSED : GuestOfferDisposition

    data object STALE : GuestOfferDisposition

    data object DUPLICATE : GuestOfferDisposition

    class Accepted(configuration: ByteArray) : GuestOfferDisposition {
      private val ownedConfiguration = configuration.clone()

      val configuration: ByteArray
        get() = ownedConfiguration.clone()

      override fun toString(): String = "GuestOfferDisposition.Accepted(configuration=[redacted])"
    }
  }

  private class PendingGuestWrite(
      val sequence: Long,
      val attachmentId: Long,
      val mutationRevision: Long,
      val imageGeneration: Long,
      configuration: ByteArray,
  ) {
    private val ownedConfiguration = configuration.clone()

    fun configurationCopy(): ByteArray = ownedConfiguration.clone()

    override fun toString(): String =
        "PendingGuestWrite(sequence=$sequence, attachmentId=$attachmentId, " +
            "mutationRevision=$mutationRevision, imageGeneration=$imageGeneration, " +
            "configuration=[redacted])"
  }

  private data class CompletedGuestAttempt(
      val sequence: Long,
      val result: MobileAdapterConfigurationSaveResult,
  )

  companion object {
    const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L
    const val MAX_PENDING_POLICY_WRITES = 1
    const val MAX_TRACKED_NETWORK_BACKENDS = 2
    private const val MAX_PENDING_GUEST_STATUSES = 32
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
                      listOf(policy.dnsQueryName) + policy.additionalDnsQueryNames,
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
