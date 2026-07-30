package eu.rekawek.coffeegb.controller.headless

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import eu.rekawek.coffeegb.core.sgb.SuperGameboy
import eu.rekawek.coffeegb.core.sound.SoundOutputObserver

/**
 * Owner-thread media tap installed only after machine setup/restore.
 *
 * PCM is deliberately frontend-neutral: it uses the raw APU mix, an exact rational 44.1 kHz
 * phase, integer bucket averaging, and integer PCM16 scaling. Desktop volume, its two-period box
 * filter, and its host-oriented DC blocker are not part of deterministic batch artifacts.
 */
internal class HeadlessMediaCapture private constructor(
    private val session: Session,
    private val captureFrame: Boolean,
    private val pcmCapture: PcmCapture?,
) : AutoCloseable {
  private val ownerThread = Thread.currentThread()
  private var pendingImage: StateImage? = null
  private var latestFrame: HeadlessFrame? = null
  private var observerAttached = false
  private var closed = false

  fun attach() {
    checkOwner()
    check(!closed) { "Headless media capture is closed" }
    if (captureFrame) {
      registerFrameListener()
    }
    val pcm = pcmCapture
    if (pcm != null) {
      check(session.gameboy.sound.attachOutputObserver(pcm)) {
        "The isolated headless machine already has a sound output observer"
      }
      observerAttached = true
    }
  }

  fun onTickCompleted(completedTicks: Long, frame: Long) {
    checkOwner()
    pendingImage?.let { image ->
      latestFrame = HeadlessFrame(completedTicks, frame, image)
      pendingImage = null
    }
  }

  fun latestFrame(): HeadlessFrame? {
    checkOwner()
    return latestFrame
  }

  fun pcm(completedTicks: Long): HeadlessPcm16? {
    checkOwner()
    return pcmCapture?.result(completedTicks)
  }

  override fun close() {
    if (closed) return
    checkOwner()
    if (observerAttached) {
      check(session.gameboy.sound.detachOutputObserver(requireNotNull(pcmCapture))) {
        "Headless sound output observer ownership changed before cleanup"
      }
      observerAttached = false
    }
    closed = true
  }

  private fun registerFrameListener() {
    when (session.config.hardwareProfile.family()) {
      HardwareProfile.Family.DMG ->
          session.eventBus.register(
              { event: Display.DmgFrameReadyEvent ->
                val rgb = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)
                event.toRgb(rgb, false)
                pendingImage = StateImage(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, rgb)
              },
              Display.DmgFrameReadyEvent::class.java,
          )
      HardwareProfile.Family.CGB ->
          session.eventBus.register(
              { event: Display.GbcFrameReadyEvent ->
                val rgb = IntArray(Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT)
                event.toRgb(rgb, false)
                pendingImage = StateImage(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT, rgb)
              },
              Display.GbcFrameReadyEvent::class.java,
          )
      HardwareProfile.Family.SGB ->
          session.eventBus.register(
              { event: SgbDisplay.SgbFrameReadyEvent ->
                val width =
                    if (event.includeBorder()) SuperGameboy.SGB_DISPLAY_WIDTH
                    else Display.DISPLAY_WIDTH
                val height =
                    if (event.includeBorder()) SuperGameboy.SGB_DISPLAY_HEIGHT
                    else Display.DISPLAY_HEIGHT
                val rgb = IntArray(Math.multiplyExact(width, height))
                event.toRgb(rgb, false)
                pendingImage = StateImage(width, height, rgb)
              },
              SgbDisplay.SgbFrameReadyEvent::class.java,
          )
    }
  }

  private fun checkOwner() {
    check(Thread.currentThread() === ownerThread) {
      "Headless media capture may only be used by its owner thread"
    }
  }

  companion object {
    const val PCM_SAMPLE_RATE = 44_100
    private const val PCM_CHANNELS = 2
    private const val BYTES_PER_PCM_FRAME = PCM_CHANNELS * 2

    fun create(
        session: Session,
        options: HeadlessCaptureOptions,
        maximumTicks: Long,
    ): HeadlessMediaCapture? {
      if (!options.latestFrame && !options.pcm16) return null
      val pcm =
          if (options.pcm16) {
            val accumulator =
                session.gameboy.clockSpec.newTickRateAccumulator(PCM_SAMPLE_RATE.toLong())
            val maximumFrames = accumulator.advance(maximumTicks)
            val maximumBytes = Math.multiplyExact(maximumFrames, BYTES_PER_PCM_FRAME.toLong())
            require(maximumBytes <= options.maximumPcmBytes.toLong()) {
              "Headless PCM needs $maximumBytes bytes, exceeding the " +
                  "${options.maximumPcmBytes}-byte request limit"
            }
            PcmCapture(
                session.gameboy.clockSpec,
                ByteArray(Math.toIntExact(maximumBytes)),
            )
          } else {
            null
          }
      return HeadlessMediaCapture(session, options.latestFrame, pcm)
    }
  }

  private class PcmCapture(
      clockSpec: ClockSpec,
      private val output: ByteArray,
  ) : SoundOutputObserver {
    private val accumulator = clockSpec.newTickRateAccumulator(PCM_SAMPLE_RATE.toLong())
    private var sumLeft = 0L
    private var sumRight = 0L
    private var count = 0L
    private var offset = 0

    override fun onSample(left: Int, right: Int) {
      sumLeft += left.toLong()
      sumRight += right.toLong()
      count++
      val produced = accumulator.advance(1L)
      check(produced <= 1L) { "PCM output rate exceeds the emulated tick rate" }
      if (produced == 1L) {
        writeSample(scaleAverage(sumLeft, count))
        writeSample(scaleAverage(sumRight, count))
        sumLeft = 0L
        sumRight = 0L
        count = 0L
      }
    }

    fun result(completedTicks: Long): HeadlessPcm16 {
      val bytes = output.copyOf(offset)
      return HeadlessPcm16(PCM_SAMPLE_RATE, PCM_CHANNELS, completedTicks, bytes)
    }

    private fun writeSample(sample: Int) {
      check(offset <= output.size - 2) { "PCM output exceeded its exact preflight bound" }
      output[offset++] = sample.toByte()
      output[offset++] = (sample shr 8).toByte()
    }

    private fun scaleAverage(sum: Long, count: Long): Int {
      val scaled = sum * 62L / count
      return scaled.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
    }
  }
}
