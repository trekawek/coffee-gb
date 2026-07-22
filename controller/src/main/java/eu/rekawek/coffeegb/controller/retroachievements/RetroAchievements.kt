package eu.rekawek.coffeegb.controller.retroachievements

import eu.rekawek.coffeegb.core.events.Event

object RetroAchievements {

  class RequestStatusEvent : Event

  data class LoginEvent(val username: String, val apiKey: CharArray) : Event

  class LogoutEvent : Event

  class RequestAchievementListEvent : Event

  data class StatusEvent(
      val available: Boolean,
      val loggedIn: Boolean,
      val username: String? = null,
      val gameTitle: String? = null,
      val unlockedAchievements: Int = 0,
      val totalAchievements: Int = 0,
      val message: String? = null,
  ) : Event

  data class Achievement(
      val id: Int,
      val title: String,
      val description: String,
      val points: Int,
      val unlocked: Boolean,
  )

  data class AchievementListEvent(
      val gameTitle: String?,
      val achievements: List<Achievement>,
      val error: String? = null,
  ) : Event
}
