package eu.rekawek.coffeegb.controller.network.v9

import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections

/** Stable local invitation failures. These values are deliberately not peer wire errors. */
enum class V9InvitationError {
  INV_TOO_LONG,
  INV_CONTROL,
  INV_ENCODING,
  INV_FRAGMENT,
  INV_SCHEME,
  INV_PATH,
  INV_AUTHORITY,
  INV_HOST,
  INV_PORT,
  INV_DUPLICATE,
  INV_UNKNOWN,
  INV_MISSING,
  INV_QUERY_ORDER,
  INV_VERSION,
  INV_MODE,
  INV_SLOT,
  INV_EXPIRY,
  INV_TOKEN,
}

class V9InvitationParseException(
    val reason: V9InvitationError,
) : IllegalArgumentException(reason.name)

/**
 * Pure, canonical protocol-v9 invitation value.
 *
 * Token bytes are deep-owned and intentionally absent from [toString], equality, diagnostics, and
 * ordinary accessors. [render] is the one explicit disclosure used by copy/paste UI.
 */
class V9Invitation private constructor(
    val host: String,
    val port: Int,
    val mode: V9LinkMode,
    val slot: Int,
    val displayExpiryUtcSeconds: Long,
    token: ByteArray,
) {
  private val token = token.copyOf()

  init {
    require(canonicalHost(host))
    require(port in 1..65_535)
    require(validSlot(mode, slot))
    require(displayExpiryUtcSeconds in 1L..MAX_EXPIRY_UTC_SECONDS)
    require(this.token.size == V9Limit.INVITATION_TOKEN_BYTES.value.toInt())
  }

  /** Explicitly renders the transferable secret; callers must not log or persist this value. */
  fun render(): String {
    val authority = if (':' in host) "[$host]" else host
    val modeName = if (mode == V9LinkMode.NORMAL) "normal" else "four"
    return "coffeegb://$authority:$port/join?v=9&mode=$modeName&slot=$slot" +
        "&exp=$displayExpiryUtcSeconds&token=${encodeToken(token)}"
  }

  override fun toString(): String = "V9Invitation([redacted])"

  internal fun copyToken(): ByteArray = token.copyOf()

  companion object {
    private const val MAX_EXPIRY_UTC_SECONDS = 253_402_300_799L
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{22}")
    private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val DECIMAL = Regex("0|[1-9][0-9]*")
    private val QUERY_NAMES = listOf("v", "mode", "slot", "exp", "token")

    @JvmStatic
    fun parse(value: String): V9Invitation {
      val utf8 = value.toByteArray(StandardCharsets.UTF_8)
      if (utf8.size > V9Limit.INVITATION_URI_BYTES.value) {
        fail(V9InvitationError.INV_TOO_LONG)
      }
      if (value.any { it.code < 0x20 || it.code == 0x7f || it.isWhitespace() }) {
        fail(V9InvitationError.INV_CONTROL)
      }
      if ('%' in value) fail(V9InvitationError.INV_ENCODING)
      if ('#' in value) fail(V9InvitationError.INV_FRAGMENT)
      if (!value.startsWith("coffeegb://")) fail(V9InvitationError.INV_SCHEME)

      val remainder = value.substring("coffeegb://".length)
      val pathIndex = remainder.indexOf("/join?")
      if (pathIndex < 0) {
        fail(if ('/' in remainder) V9InvitationError.INV_PATH else V9InvitationError.INV_AUTHORITY)
      }
      val authority = remainder.substring(0, pathIndex)
      val query = remainder.substring(pathIndex + "/join?".length)
      if ('@' in authority) fail(V9InvitationError.INV_AUTHORITY)
      val parsedAuthority = parseAuthority(authority)
      if (parsedAuthority == null) {
        fail(
            if (!authority.contains(':') || authority.substringAfterLast(':').isEmpty()) {
              V9InvitationError.INV_PORT
            } else {
              V9InvitationError.INV_AUTHORITY
            },
        )
      }
      val (host, portText) = parsedAuthority
      if (!canonicalHost(host)) fail(V9InvitationError.INV_HOST)
      val port = canonicalUnsigned(portText, 65_535L)?.toInt()
          ?: fail(V9InvitationError.INV_PORT)
      if (port == 0) fail(V9InvitationError.INV_PORT)

      val pieces = query.split('&')
      val pairs = pieces.map { it.substringBefore('=') to it.substringAfter('=', "") }
      val counts = pairs.map { it.first }.groupingBy { it }.eachCount()
      if (counts.values.any { it > 1 }) fail(V9InvitationError.INV_DUPLICATE)
      if (pairs.any { it.first !in QUERY_NAMES }) fail(V9InvitationError.INV_UNKNOWN)
      if (!pairs.map { it.first }.containsAll(QUERY_NAMES)) fail(V9InvitationError.INV_MISSING)
      if (pairs.map { it.first } != QUERY_NAMES) fail(V9InvitationError.INV_QUERY_ORDER)
      val values = pairs.toMap()
      if (values.getValue("v") != "9") fail(V9InvitationError.INV_VERSION)
      val mode =
          when (values.getValue("mode")) {
            "normal" -> V9LinkMode.NORMAL
            "four" -> V9LinkMode.FOUR_PLAYER
            else -> fail(V9InvitationError.INV_MODE)
          }
      val slot = canonicalUnsigned(values.getValue("slot"), 3)?.toInt()
          ?: fail(V9InvitationError.INV_SLOT)
      if (!validSlot(mode, slot)) fail(V9InvitationError.INV_SLOT)
      val expiry = canonicalUnsigned(values.getValue("exp"), MAX_EXPIRY_UTC_SECONDS)
          ?: fail(V9InvitationError.INV_EXPIRY)
      if (expiry == 0L) fail(V9InvitationError.INV_EXPIRY)
      val token = decodeToken(values.getValue("token"))
          ?: fail(V9InvitationError.INV_TOKEN)

      return V9Invitation(host, port, mode, slot, expiry, token).also {
        if (it.render() != value) fail(V9InvitationError.INV_HOST)
      }
    }

    internal fun create(
        host: String,
        port: Int,
        mode: V9LinkMode,
        slot: Int,
        displayExpiryUtcSeconds: Long,
        token: ByteArray,
    ): V9Invitation =
        V9Invitation(host, port, mode, slot, displayExpiryUtcSeconds, token)

    private fun parseAuthority(authority: String): Pair<String, String>? {
      if (authority.startsWith('[')) {
        val end = authority.indexOf(']')
        if (end < 0 || end + 1 >= authority.length || authority[end + 1] != ':') return null
        return authority.substring(1, end) to authority.substring(end + 2)
      }
      if (authority.count { it == ':' } != 1) return null
      return authority.substringBefore(':') to authority.substringAfter(':')
    }

    private fun canonicalHost(host: String): Boolean {
      if (host.isEmpty() || host.length > 253 || host.any { it.code > 0x7f }) return false
      if (':' in host) return canonicalIpv6(host) == host
      val parts = host.split('.')
      if (parts.size == 4 && parts.all { part -> part.all(Char::isDigit) }) {
        return parts.all {
          val number = canonicalUnsigned(it, 255)
          number != null
        }
      }
      return !host.endsWith('.') &&
          parts.all { it.length in 1..63 && DNS_LABEL.matches(it) }
    }

    private fun canonicalIpv6(input: String): String? {
      if (input.any { it in 'A'..'F' } ||
          '%' in input ||
          '.' in input ||
          input.count { it == ':' } < 2 ||
          input.indexOf("::") != input.lastIndexOf("::")) {
        return null
      }
      val halves = input.split("::", limit = 2)
      fun parseGroups(value: String): List<Int>? {
        if (value.isEmpty()) return emptyList()
        return value.split(':').map { part ->
          if (part.isEmpty() ||
              part.length > 4 ||
              !part.all { it.isDigit() || it in 'a'..'f' }) {
            return null
          }
          part.toInt(16)
        }
      }
      val left = parseGroups(halves[0]) ?: return null
      val right = if (halves.size == 2) parseGroups(halves[1]) ?: return null else emptyList()
      val missing = 8 - left.size - right.size
      if (halves.size == 1 && missing != 0 || halves.size == 2 && missing < 1) return null
      val groups = left + List(if (halves.size == 2) missing else 0) { 0 } + right
      if (groups.size != 8) return null

      var bestStart = -1
      var bestLength = 0
      var index = 0
      while (index < groups.size) {
        if (groups[index] != 0) {
          index++
          continue
        }
        var end = index
        while (end < groups.size && groups[end] == 0) end++
        if (end - index >= 2 && end - index > bestLength) {
          bestStart = index
          bestLength = end - index
        }
        index = end
      }
      if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
      val leftText = groups.take(bestStart).joinToString(":") { it.toString(16) }
      val rightText =
          groups.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
      return when {
        leftText.isEmpty() && rightText.isEmpty() -> "::"
        leftText.isEmpty() -> "::$rightText"
        rightText.isEmpty() -> "$leftText::"
        else -> "$leftText::$rightText"
      }
    }

    private fun canonicalUnsigned(value: String, maximum: Long): Long? {
      if (!DECIMAL.matches(value) || value.length > 1 && value.startsWith('0')) return null
      val parsed = value.toLongOrNull() ?: return null
      return parsed.takeIf { it in 0..maximum }
    }

    private fun decodeToken(value: String): ByteArray? {
      if (!TOKEN_PATTERN.matches(value)) return null
      val decoded =
          try {
            Base64.getUrlDecoder().decode(value)
          } catch (_: IllegalArgumentException) {
            return null
          }
      if (decoded.size != V9Limit.INVITATION_TOKEN_BYTES.value.toInt() ||
          encodeToken(decoded) != value) {
        return null
      }
      return decoded
    }

    private fun encodeToken(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun validSlot(mode: V9LinkMode, slot: Int): Boolean =
        mode == V9LinkMode.NORMAL && slot == 1 ||
            mode == V9LinkMode.FOUR_PLAYER && slot in 1..3

    private fun fail(reason: V9InvitationError): Nothing =
        throw V9InvitationParseException(reason)
  }
}

fun interface V9SecureRandom {
  fun nextBytes(target: ByteArray)

  companion object {
    val SYSTEM = V9SecureRandom(SecureRandom()::nextBytes)
  }
}

fun interface V9UtcSeconds {
  fun nowSeconds(): Long

  companion object {
    val SYSTEM = V9UtcSeconds { System.currentTimeMillis() / 1_000L }
  }
}

/**
 * Host-owned invitation, rate-limit, and slot ledger.
 *
 * Proof admission, one-use consumption, and slot reservation share one monitor. Tokens are wiped
 * on use, replacement, expiry cleanup, and stop. Closing a reservation releases only its slot and
 * never resurrects the consumed invitation.
 */
class V9InvitationHost(
    val mode: V9LinkMode,
    private val clock: V9MonotonicClock = V9MonotonicClock.SYSTEM,
    private val utcSeconds: V9UtcSeconds = V9UtcSeconds.SYSTEM,
    private val random: V9SecureRandom = V9SecureRandom.SYSTEM,
    scheduler: V9DeadlineScheduler? = null,
) : AutoCloseable {
  private val scheduler = scheduler ?: V9SystemDeadlineScheduler(clock)
  private val ownedScheduler = if (scheduler == null) this.scheduler as Closeable else null
  private val invitations = mutableMapOf<Int, HostedInvitation>()
  private val occupied = mutableMapOf<Int, Reservation>()
  private var stopped = false
  private var failureWindowStart = clock.nowMillis()
  private var failures = 0

  @Synchronized
  fun createInvitation(
      host: String,
      port: Int,
      slot: Int,
      ttlSeconds: Long = V9Limit.INVITATION_EXPIRY_DEFAULT_SECONDS.value,
  ): V9Invitation {
    check(!stopped) { "v9 invitation host is stopped" }
    if (ttlSeconds !in
        V9Limit.INVITATION_EXPIRY_MIN_SECONDS.value..
            V9Limit.INVITATION_EXPIRY_MAX_SECONDS.value) {
      throw V9InvitationParseException(V9InvitationError.INV_EXPIRY)
    }
    if (!validSlot(slot)) throw V9InvitationParseException(V9InvitationError.INV_SLOT)
    val lifetimeMillis = Math.multiplyExact(ttlSeconds, 1_000L)
    val monotonicExpiry = Math.addExact(clock.nowMillis(), lifetimeMillis)
    val displayExpiry = Math.addExact(utcSeconds.nowSeconds(), ttlSeconds)
    val token = ByteArray(V9Limit.INVITATION_TOKEN_BYTES.value.toInt())
    val generated =
        try {
          random.nextBytes(token)
          V9Invitation.create(host, port, mode, slot, displayExpiry, token) to
              HostedInvitation(token, monotonicExpiry)
        } finally {
          token.fill(0)
        }
    val (value, hosted) = generated
    invitations.remove(slot)?.destroy()
    invitations[slot] = hosted
    hosted.expiryTask =
        scheduler.schedule(monotonicExpiry) {
          synchronized(this) {
            if (invitations[slot] === hosted && clock.nowMillis() >= monotonicExpiry) {
              invitations.remove(slot)
              hosted.destroy()
            }
          }
        }
    return value
  }

  @Synchronized
  internal fun rejectMalformedAdmission(): V9Authentication {
    val now = clock.nowMillis()
    rollFailureWindow(now)
    if (!stopped && failures < V9Limit.AUTH_FAILURES_PER_WINDOW.value) failures++
    return V9Authentication.Failed
  }

  @Synchronized
  internal fun authenticate(
      auth: V9Auth,
      serverNonce: ByteArray,
      clientNonce: ByteArray,
  ): V9Authentication {
    val now = clock.nowMillis()
    rollFailureWindow(now)
    if (stopped || failures >= V9Limit.AUTH_FAILURES_PER_WINDOW.value) {
      return V9Authentication.Failed
    }
    val invitation = invitations[auth.slot]
    val proofMatches =
        if (invitation == null) {
          false
        } else {
          val key = invitation.copyToken()
          try {
            MessageDigest.isEqual(
                V9AuthCodec.proof(key, serverNonce, clientNonce, auth.slot),
                auth.proof(),
            )
          } finally {
            key.fill(0)
          }
        }
    if (invitation == null ||
        invitation.used ||
        now >= invitation.expiresAtMillis ||
        !validSlot(auth.slot) ||
        !proofMatches) {
      failures++
      if (invitation != null && now >= invitation.expiresAtMillis) {
        invitations.remove(auth.slot)?.destroy()
      }
      return V9Authentication.Failed
    }
    if (occupied.containsKey(auth.slot)) return V9Authentication.SlotFull

    invitation.used = true
    invitation.destroy()
    val reservation = Reservation(this, auth.slot)
    occupied[auth.slot] = reservation
    return V9Authentication.Accepted(reservation)
  }

  @Synchronized
  fun outstandingInvitations(): Int = invitations.values.count { !it.used }

  @Synchronized
  fun occupiedSlots(): Set<Int> =
      Collections.unmodifiableSet(occupied.keys.toSet())

  @Synchronized
  fun failedAdmissionsInWindow(): Int = failures

  @Synchronized
  override fun close() {
    if (stopped) return
    stopped = true
    invitations.values.forEach(HostedInvitation::destroy)
    invitations.clear()
    occupied.values.toList().forEach(Reservation::invalidate)
    occupied.clear()
    failures = 0
    ownedScheduler?.close()
  }

  @Synchronized
  private fun release(reservation: Reservation) {
    if (occupied[reservation.slot] === reservation) occupied.remove(reservation.slot)
  }

  private fun rollFailureWindow(now: Long) {
    val elapsed =
        try {
          Math.subtractExact(now, failureWindowStart)
        } catch (_: ArithmeticException) {
          Long.MAX_VALUE
        }
    if (elapsed >= V9Limit.AUTH_FAILURE_WINDOW_MILLIS.value) {
      failureWindowStart = now
      failures = 0
    }
  }

  private fun validSlot(slot: Int): Boolean =
      mode == V9LinkMode.NORMAL && slot == 1 ||
          mode == V9LinkMode.FOUR_PLAYER && slot in 1..3

  class Reservation internal constructor(
      private val owner: V9InvitationHost,
      val slot: Int,
  ) : AutoCloseable {
    @Volatile private var open = true

    @Synchronized
    fun isOpen(): Boolean = open

    override fun close() {
      synchronized(owner) {
        if (!open) return
        open = false
        owner.release(this)
      }
    }

    internal fun invalidate() {
      open = false
    }

    override fun toString(): String = "V9SlotReservation(slot=$slot)"
  }

  private class HostedInvitation(
      token: ByteArray,
      val expiresAtMillis: Long,
  ) {
    private val token = token.copyOf()
    var used = false
    var expiryTask: Closeable? = null

    fun copyToken(): ByteArray = token.copyOf()

    fun destroy() {
      expiryTask?.close()
      expiryTask = null
      token.fill(0)
    }
  }
}

sealed class V9Authentication {
  class Accepted(val reservation: V9InvitationHost.Reservation) : V9Authentication()
  object Failed : V9Authentication()
  object SlotFull : V9Authentication()
}
