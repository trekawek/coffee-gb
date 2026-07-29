package eu.rekawek.coffeegb.controller.mobile.config

/**
 * Immutable inputs used to construct a Mobile Adapter serial endpoint.
 *
 * The configuration address space may contain private dial or account data, so its bytes are
 * detached at both API boundaries and are deliberately omitted from diagnostics.
 */
class MobileAdapterConfiguration(
    val deviceId: Int,
    configurationBytes: ByteArray,
) {
  private val ownedConfiguration: ByteArray

  init {
    require(deviceId in MIN_DEVICE_ID..MAX_DEVICE_ID) {
      "Mobile Adapter device ID must be in 0..127"
    }
    require(configurationBytes.size == CONFIGURATION_SIZE) {
      "Mobile Adapter configuration must contain exactly 256 bytes"
    }
    ownedConfiguration = configurationBytes.clone()
  }

  /** Returns a detached copy suitable for `MobileAdapterSerialEndpoint` construction. */
  fun configurationBytes(): ByteArray = ownedConfiguration.clone()

  override fun equals(other: Any?): Boolean =
      this === other ||
          (other is MobileAdapterConfiguration &&
              deviceId == other.deviceId &&
              ownedConfiguration.contentEquals(other.ownedConfiguration))

  override fun hashCode(): Int = 31 * deviceId + ownedConfiguration.contentHashCode()

  override fun toString(): String =
      "MobileAdapterConfiguration(deviceId=$deviceId, configuration=[redacted])"

  companion object {
    const val CONFIGURATION_SIZE: Int = 256
    const val MIN_DEVICE_ID: Int = 0
    const val MAX_DEVICE_ID: Int = 127
    const val SYNTHETIC_DEVICE_ID: Int = 0x08

    /**
     * Clean-room deterministic configuration used when no valid persisted file exists.
     *
     * These bytes match the synthetic Phase #346 transcript fixture: `MA`, status `81`, zeroed
     * space, and the documented boundary pattern in the second 128-byte page. They contain no
     * server, account, telephone, credential, or captured device data.
     */
    fun syntheticFallback(): MobileAdapterConfiguration {
      val configuration = ByteArray(CONFIGURATION_SIZE)
      configuration[0] = 0x4d
      configuration[1] = 0x41
      configuration[2] = 0x81.toByte()
      for (index in 0 until 128) {
        configuration[128 + index] = index.toByte()
      }
      return MobileAdapterConfiguration(SYNTHETIC_DEVICE_ID, configuration)
    }
  }
}
