package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource

/**
 * Owner-thread physical input source used by deterministic replay playback.
 *
 * [sample] returns a retained immutable snapshot, so an emulated tick with no recorded input
 * transition allocates nothing. A new snapshot is built only when a replay record changes one
 * player's absolute mask.
 */
internal class ReplayInputSource(
    initialSnapshot: PlayerInputSnapshot = PlayerInputSnapshot.released(),
) : PlayerInputSource {
  private val masks = IntArray(PlayerInputSource.PLAYER_COUNT)

  private var snapshot = PlayerInputSnapshot.released()

  init {
    reset(initialSnapshot)
  }

  override fun sample(): PlayerInputSnapshot = snapshot

  fun apply(player: Int, absoluteMask: Int) {
    requirePlayer(player)
    require(absoluteMask in 0..JoypadButtonMask.ALL) {
      "Replay input mask must be an unsigned eight-bit value"
    }
    if (masks[player] == absoluteMask) {
      return
    }
    masks[player] = absoluteMask
    snapshot =
        PlayerInputSnapshot.of(
            masks.map { mask -> JoypadButtonMask.toButtons(mask) },
        )
  }

  fun mask(player: Int): Int {
    requirePlayer(player)
    return masks[player]
  }

  /** Repositions all four source masks before an isolated replay restores its first checkpoint. */
  fun reset(initialSnapshot: PlayerInputSnapshot) {
    for (player in 0 until PlayerInputSource.PLAYER_COUNT) {
      masks[player] = JoypadButtonMask.fromButtons(initialSnapshot.buttons(player))
    }
    snapshot = initialSnapshot
  }

  private fun requirePlayer(player: Int) {
    require(player in 0 until PlayerInputSource.PLAYER_COUNT) {
      "Replay player index must be in 0..${PlayerInputSource.PLAYER_COUNT - 1}"
    }
  }
}
