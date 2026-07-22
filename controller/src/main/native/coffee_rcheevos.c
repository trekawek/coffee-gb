#include "coffee_rcheevos.h"

#include "rc_api_request.h"
#include "rc_client.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

struct coffee_ra_client {
  rc_client_t* client;
  coffee_ra_read_memory_callback read_memory;
  coffee_ra_server_call_callback server_call;
  coffee_ra_operation_callback operation;
  coffee_ra_event_callback event;
  void* userdata;
  unsigned int pending_server_calls;
  int destroyed;
};

struct coffee_ra_server_context {
  coffee_ra_client* wrapper;
  rc_client_server_callback_t callback;
  void* callback_data;
};

static void coffee_ra_maybe_free(coffee_ra_client* wrapper) {
  if (wrapper->destroyed && wrapper->pending_server_calls == 0)
    free(wrapper);
}

static uint32_t coffee_ra_read_memory_adapter(
    uint32_t address, uint8_t* buffer, uint32_t num_bytes, rc_client_t* client) {
  coffee_ra_client* wrapper = (coffee_ra_client*)rc_client_get_userdata(client);
  if (!wrapper || wrapper->destroyed || !wrapper->read_memory)
    return 0;
  return wrapper->read_memory(address, buffer, num_bytes, wrapper->userdata);
}

static void coffee_ra_server_call_adapter(
    const rc_api_request_t* request, rc_client_server_callback_t callback,
    void* callback_data, rc_client_t* client) {
  coffee_ra_client* wrapper = (coffee_ra_client*)rc_client_get_userdata(client);
  struct coffee_ra_server_context* context;

  if (!wrapper || wrapper->destroyed || !wrapper->server_call)
    return;

  context = (struct coffee_ra_server_context*)malloc(sizeof(*context));
  if (!context) {
    rc_api_server_response_t response;
    memset(&response, 0, sizeof(response));
    response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
    callback(&response, callback_data);
    return;
  }

  context->wrapper = wrapper;
  context->callback = callback;
  context->callback_data = callback_data;
  ++wrapper->pending_server_calls;
  wrapper->server_call(request->url, request->post_data, request->content_type,
      context, wrapper->userdata);
}

static void coffee_ra_operation_adapter(
    int result, const char* error_message, rc_client_t* client, void* callback_userdata) {
  coffee_ra_client* wrapper = (coffee_ra_client*)rc_client_get_userdata(client);
  const rc_client_user_t* user;
  const rc_client_game_t* game;
  rc_client_user_game_summary_t summary;
  int request_id = (int)((intptr_t)callback_userdata - 1);

  if (!wrapper || wrapper->destroyed || !wrapper->operation)
    return;

  user = rc_client_get_user_info(client);
  game = rc_client_get_game_info(client);
  memset(&summary, 0, sizeof(summary));
  if (game && game->id != 0)
    rc_client_get_user_game_summary(client, &summary);

  wrapper->operation(request_id, result, error_message,
      user ? user->username : NULL,
      user ? user->display_name : NULL,
      user ? user->token : NULL,
      game ? game->id : 0,
      game ? game->title : NULL,
      summary.num_unlocked_achievements,
      summary.num_core_achievements,
      wrapper->userdata);
}

static void coffee_ra_event_adapter(const rc_client_event_t* event, rc_client_t* client) {
  coffee_ra_client* wrapper = (coffee_ra_client*)rc_client_get_userdata(client);
  uint32_t id = 0;
  uint32_t value = 0;
  const char* title = NULL;
  const char* description = NULL;
  const char* detail = NULL;

  if (!wrapper || wrapper->destroyed || !wrapper->event)
    return;

  if (event->achievement) {
    id = event->achievement->id;
    value = event->achievement->points;
    title = event->achievement->title;
    description = event->achievement->description;
    detail = event->achievement->measured_progress;
  } else if (event->leaderboard) {
    id = event->leaderboard->id;
    title = event->leaderboard->title;
    description = event->leaderboard->description;
    detail = event->leaderboard->tracker_value;
  } else if (event->leaderboard_tracker) {
    id = event->leaderboard_tracker->id;
    detail = event->leaderboard_tracker->display;
  } else if (event->leaderboard_scoreboard) {
    id = event->leaderboard_scoreboard->leaderboard_id;
    title = event->leaderboard_scoreboard->submitted_score;
    description = event->leaderboard_scoreboard->best_score;
    value = event->leaderboard_scoreboard->new_rank;
  } else if (event->server_error) {
    id = event->server_error->related_id;
    title = event->server_error->api;
    description = event->server_error->error_message;
    value = (uint32_t)event->server_error->result;
  } else if (event->subset) {
    id = event->subset->id;
    title = event->subset->title;
  }

  wrapper->event(event->type, id, title, description, detail, value, wrapper->userdata);
}

coffee_ra_client* coffee_ra_create(
    coffee_ra_read_memory_callback read_memory,
    coffee_ra_server_call_callback server_call,
    coffee_ra_operation_callback operation,
    coffee_ra_event_callback event,
    void* userdata) {
  coffee_ra_client* wrapper = (coffee_ra_client*)calloc(1, sizeof(*wrapper));
  if (!wrapper)
    return NULL;

  wrapper->read_memory = read_memory;
  wrapper->server_call = server_call;
  wrapper->operation = operation;
  wrapper->event = event;
  wrapper->userdata = userdata;
  wrapper->client = rc_client_create(coffee_ra_read_memory_adapter, coffee_ra_server_call_adapter);
  if (!wrapper->client) {
    free(wrapper);
    return NULL;
  }

  rc_client_set_userdata(wrapper->client, wrapper);
  rc_client_set_event_handler(wrapper->client, coffee_ra_event_adapter);
  rc_client_set_allow_background_memory_reads(wrapper->client, 0);
  /* Coffee GB initially exposes the fully supported softcore feature set. */
  rc_client_set_hardcore_enabled(wrapper->client, 0);
  return wrapper;
}

void coffee_ra_destroy(coffee_ra_client* wrapper) {
  if (!wrapper || wrapper->destroyed)
    return;
  wrapper->destroyed = 1;
  if (wrapper->client) {
    rc_client_destroy(wrapper->client);
    wrapper->client = NULL;
  }
  coffee_ra_maybe_free(wrapper);
}

void coffee_ra_complete_server_call(
    void* server_context, const uint8_t* body, uint64_t body_length, int http_status_code) {
  struct coffee_ra_server_context* context =
      (struct coffee_ra_server_context*)server_context;
  coffee_ra_client* wrapper;
  rc_api_server_response_t response;

  if (!context)
    return;
  wrapper = context->wrapper;
  if (!wrapper->destroyed && wrapper->client) {
    memset(&response, 0, sizeof(response));
    response.body = (const char*)body;
    response.body_length = (size_t)body_length;
    response.http_status_code = http_status_code;
    context->callback(&response, context->callback_data);
  }
  free(context);
  --wrapper->pending_server_calls;
  coffee_ra_maybe_free(wrapper);
}

void coffee_ra_begin_login_password(
    coffee_ra_client* wrapper, const char* username, const char* password, int request_id) {
  if (wrapper && wrapper->client)
    rc_client_begin_login_with_password(wrapper->client, username, password,
        coffee_ra_operation_adapter, (void*)(intptr_t)(request_id + 1));
}

void coffee_ra_begin_login_token(
    coffee_ra_client* wrapper, const char* username, const char* token, int request_id) {
  if (wrapper && wrapper->client)
    rc_client_begin_login_with_token(wrapper->client, username, token,
        coffee_ra_operation_adapter, (void*)(intptr_t)(request_id + 1));
}

void coffee_ra_logout(coffee_ra_client* wrapper) {
  if (wrapper && wrapper->client)
    rc_client_logout(wrapper->client);
}

void coffee_ra_begin_load_game(
    coffee_ra_client* wrapper, uint32_t console_id, const char* file_path,
    const uint8_t* data, uint64_t data_size, int request_id) {
  if (wrapper && wrapper->client)
    rc_client_begin_identify_and_load_game(wrapper->client, console_id, file_path,
        data, (size_t)data_size, coffee_ra_operation_adapter,
        (void*)(intptr_t)(request_id + 1));
}

void coffee_ra_unload_game(coffee_ra_client* wrapper) {
  if (wrapper && wrapper->client)
    rc_client_unload_game(wrapper->client);
}

int coffee_ra_is_game_loaded(coffee_ra_client* wrapper) {
  return wrapper && wrapper->client ? rc_client_is_game_loaded(wrapper->client) : 0;
}

void coffee_ra_do_frame(coffee_ra_client* wrapper) {
  if (wrapper && wrapper->client)
    rc_client_do_frame(wrapper->client);
}

void coffee_ra_idle(coffee_ra_client* wrapper) {
  if (wrapper && wrapper->client)
    rc_client_idle(wrapper->client);
}

void coffee_ra_reset(coffee_ra_client* wrapper) {
  if (wrapper && wrapper->client)
    rc_client_reset(wrapper->client);
}

uint64_t coffee_ra_progress_size(coffee_ra_client* wrapper) {
  return wrapper && wrapper->client ? (uint64_t)rc_client_progress_size(wrapper->client) : 0;
}

int coffee_ra_serialize_progress(
    coffee_ra_client* wrapper, uint8_t* buffer, uint64_t buffer_size) {
  return wrapper && wrapper->client
      ? rc_client_serialize_progress_sized(wrapper->client, buffer, (size_t)buffer_size)
      : -25;
}

int coffee_ra_deserialize_progress(
    coffee_ra_client* wrapper, const uint8_t* buffer, uint64_t buffer_size) {
  return wrapper && wrapper->client
      ? rc_client_deserialize_progress_sized(wrapper->client, buffer, (size_t)buffer_size)
      : -25;
}

uint64_t coffee_ra_get_user_agent(
    coffee_ra_client* wrapper, char* buffer, uint64_t buffer_size) {
  return wrapper && wrapper->client
      ? (uint64_t)rc_client_get_user_agent_clause(wrapper->client, buffer, (size_t)buffer_size)
      : 0;
}

uint64_t coffee_ra_get_rich_presence(
    coffee_ra_client* wrapper, char* buffer, uint64_t buffer_size) {
  return wrapper && wrapper->client
      ? (uint64_t)rc_client_get_rich_presence_message(wrapper->client, buffer, (size_t)buffer_size)
      : 0;
}

void coffee_ra_iterate_achievements(
    coffee_ra_client* wrapper, coffee_ra_achievement_callback callback, void* userdata) {
  rc_client_achievement_list_t* list;
  uint32_t bucket_index;

  if (!wrapper || !wrapper->client || !callback)
    return;

  list = rc_client_create_achievement_list(wrapper->client,
      RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
  if (!list)
    return;

  for (bucket_index = 0; bucket_index < list->num_buckets; ++bucket_index) {
    const rc_client_achievement_bucket_t* bucket = &list->buckets[bucket_index];
    uint32_t achievement_index;
    for (achievement_index = 0; achievement_index < bucket->num_achievements;
         ++achievement_index) {
      const rc_client_achievement_t* achievement = bucket->achievements[achievement_index];
      callback(achievement->id, achievement->title, achievement->description,
          achievement->measured_progress, achievement->points,
          achievement->state, achievement->unlocked, userdata);
    }
  }
  rc_client_destroy_achievement_list(list);
}
