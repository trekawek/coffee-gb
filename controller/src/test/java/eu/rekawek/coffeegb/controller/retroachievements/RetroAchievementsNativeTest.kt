package eu.rekawek.coffeegb.controller.retroachievements

import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class RetroAchievementsNativeTest {

  @Test
  fun bundledRuntimeCanBeCreatedAndDestroyed() {
    val runtime = RetroAchievementsNative.load()
    val client =
        runtime.coffee_ra_create(
            RetroAchievementsNative.ReadMemoryCallback { _, _, _, _ -> 0 },
            RetroAchievementsNative.ServerCallCallback { _, _, _, _, _ -> },
            RetroAchievementsNative.OperationCallback { _, _, _, _, _, _, _, _, _, _, _ -> },
            RetroAchievementsNative.EventCallback { _, _, _, _, _, _, _ -> },
            null,
        )
    assertNotNull(client)

    try {
      val buffer = ByteArray(128)
      assertTrue(runtime.coffee_ra_get_user_agent(client, buffer, buffer.size.toLong()) > 0)
      assertTrue(buffer.toNullTerminatedString().startsWith("rcheevos/12.3"))
    } finally {
      runtime.coffee_ra_destroy(client)
    }
  }

  private fun ByteArray.toNullTerminatedString(): String {
    val end = indexOf(0).let { if (it < 0) size else it }
    return decodeToString(0, end)
  }
}
