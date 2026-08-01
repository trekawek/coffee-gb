package eu.rekawek.coffeegb.controller.mobile.config

import java.util.concurrent.TimeUnit

/**
 * One complete configuration image accepted from a committed Mobile Adapter attachment.
 *
 * The 256-byte image may contain account or dial data. It is detached at both API boundaries and
 * deliberately omitted from diagnostics.
 */
class MobileAdapterGuestConfigurationWrite(
    val attachmentId: Long,
    val mutationRevision: Long,
    configuration: ByteArray,
) {
  private val ownedConfiguration: ByteArray

  init {
    require(attachmentId > 0) { "Mobile Adapter attachment ID must be positive" }
    require(mutationRevision > 0) { "Mobile Adapter mutation revision must be positive" }
    require(configuration.size == MobileAdapterConfiguration.CONFIGURATION_SIZE) {
      "Mobile Adapter guest configuration must contain exactly 256 bytes"
    }
    ownedConfiguration = configuration.clone()
  }

  fun configurationCopy(): ByteArray = ownedConfiguration.clone()

  override fun toString(): String =
      "MobileAdapterGuestConfigurationWrite(" +
          "attachmentId=$attachmentId, mutationRevision=$mutationRevision, " +
          "configuration=[redacted])"
}

enum class MobileAdapterGuestConfigurationOfferResult {
  ACCEPTED,
  STALE_ATTACHMENT,
  CLOSED,
}

enum class MobileAdapterGuestConfigurationPersistencePhase {
  PENDING,
  SAVED,
  SUPERSEDED,
  FAILED,
}

/** Presentation-safe result from the bounded configuration writer. */
data class MobileAdapterGuestConfigurationPersistenceStatus(
    val sequence: Long,
    val attachmentId: Long,
    val mutationRevision: Long,
    val phase: MobileAdapterGuestConfigurationPersistencePhase,
    val error: MobileAdapterConfigurationError? = null,
) {
  init {
    require(sequence > 0) { "Mobile Adapter persistence sequence must be positive" }
    require(attachmentId > 0) { "Mobile Adapter attachment ID must be positive" }
    require(mutationRevision > 0) { "Mobile Adapter mutation revision must be positive" }
    require((phase == MobileAdapterGuestConfigurationPersistencePhase.FAILED) == (error != null)) {
      "Only a failed Mobile Adapter persistence status carries an error"
    }
    require(
        error == null ||
            error == MobileAdapterConfigurationError.NON_REGULAR_FILE ||
            error == MobileAdapterConfigurationError.STORAGE_WRITE_FAILED ||
            error == MobileAdapterConfigurationError.PERMISSION_HARDENING_FAILED) {
          "A guest Mobile Adapter persistence status must carry a storage error"
        }
  }
}

/**
 * Privileged nonblocking bridge from the emulation owner to private desktop persistence.
 *
 * [offer] must retain or reject a detached write without blocking on filesystem work. [flush] is
 * called only after the controller timing thread is frozen and participates in its retryable close
 * barrier. Raw configuration bytes never cross the application event tree.
 */
interface MobileAdapterGuestConfigurationSink {
  fun attachmentCommitted(attachmentId: Long)

  fun offer(
      write: MobileAdapterGuestConfigurationWrite
  ): MobileAdapterGuestConfigurationOfferResult

  fun pollStatus(): MobileAdapterGuestConfigurationPersistenceStatus?

  fun flush(timeout: Long, unit: TimeUnit): MobileAdapterConfigurationSaveResult

  companion object {
    @JvmField
    val NO_OP: MobileAdapterGuestConfigurationSink =
        object : MobileAdapterGuestConfigurationSink {
          override fun attachmentCommitted(attachmentId: Long) {
            require(attachmentId > 0) { "Mobile Adapter attachment ID must be positive" }
          }

          override fun offer(
              write: MobileAdapterGuestConfigurationWrite
          ): MobileAdapterGuestConfigurationOfferResult =
              // A controller without a privileged owner must not acknowledge durability it does
              // not provide. The live endpoint keeps the image and the controller retries or
              // exposes its normal persistence barrier.
              MobileAdapterGuestConfigurationOfferResult.CLOSED

          override fun pollStatus(): MobileAdapterGuestConfigurationPersistenceStatus? = null

          override fun flush(
              timeout: Long,
              unit: TimeUnit,
          ): MobileAdapterConfigurationSaveResult {
            require(timeout >= 0) { "Mobile Adapter flush timeout must not be negative" }
            unit.toNanos(timeout)
            return MobileAdapterConfigurationSaveResult(saved = true)
          }
        }
  }
}
