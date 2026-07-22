package eu.rekawek.coffeegb.controller.retroachievements

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal interface RetroAchievementsNative : Library {

  fun interface ReadMemoryCallback : Callback {
    fun invoke(address: Int, buffer: Pointer, numBytes: Int, userdata: Pointer?): Int
  }

  fun interface ServerCallCallback : Callback {
    fun invoke(
        url: String,
        postData: String?,
        contentType: String?,
        serverContext: Pointer,
        userdata: Pointer?,
    )
  }

  fun interface OperationCallback : Callback {
    fun invoke(
        requestId: Int,
        result: Int,
        errorMessage: String?,
        username: String?,
        displayName: String?,
        token: String?,
        gameId: Int,
        gameTitle: String?,
        unlockedAchievements: Int,
        totalAchievements: Int,
        userdata: Pointer?,
    )
  }

  fun interface EventCallback : Callback {
    fun invoke(
        eventType: Int,
        id: Int,
        title: String?,
        description: String?,
        detail: String?,
        value: Int,
        userdata: Pointer?,
    )
  }

  fun interface AchievementCallback : Callback {
    fun invoke(
        id: Int,
        title: String?,
        description: String?,
        measuredProgress: String?,
        points: Int,
        state: Byte,
        unlocked: Byte,
        userdata: Pointer?,
    )
  }

  fun coffee_ra_create(
      readMemory: ReadMemoryCallback,
      serverCall: ServerCallCallback,
      operation: OperationCallback,
      event: EventCallback,
      userdata: Pointer?,
  ): Pointer?

  fun coffee_ra_destroy(client: Pointer)

  fun coffee_ra_complete_server_call(
      serverContext: Pointer,
      body: ByteArray,
      bodyLength: Long,
      httpStatusCode: Int,
  )

  fun coffee_ra_begin_login_password(
      client: Pointer,
      username: String,
      password: String,
      requestId: Int,
  )

  fun coffee_ra_begin_login_token(
      client: Pointer,
      username: String,
      token: String,
      requestId: Int,
  )

  fun coffee_ra_logout(client: Pointer)

  fun coffee_ra_begin_load_game(
      client: Pointer,
      consoleId: Int,
      filePath: String,
      data: ByteArray,
      dataSize: Long,
      requestId: Int,
  )

  fun coffee_ra_unload_game(client: Pointer)

  fun coffee_ra_is_game_loaded(client: Pointer): Int

  fun coffee_ra_do_frame(client: Pointer)

  fun coffee_ra_idle(client: Pointer)

  fun coffee_ra_reset(client: Pointer)

  fun coffee_ra_progress_size(client: Pointer): Long

  fun coffee_ra_serialize_progress(client: Pointer, buffer: ByteArray, bufferSize: Long): Int

  fun coffee_ra_deserialize_progress(client: Pointer, buffer: ByteArray, bufferSize: Long): Int

  fun coffee_ra_get_user_agent(client: Pointer, buffer: ByteArray, bufferSize: Long): Long

  fun coffee_ra_get_rich_presence(client: Pointer, buffer: ByteArray, bufferSize: Long): Long

  fun coffee_ra_iterate_achievements(
      client: Pointer,
      callback: AchievementCallback,
      userdata: Pointer?,
  )

  companion object {
    fun load(): RetroAchievementsNative =
        Native.load("coffee_rcheevos", RetroAchievementsNative::class.java)
  }
}
