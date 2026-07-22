#ifndef COFFEE_RCHEEVOS_H
#define COFFEE_RCHEEVOS_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define COFFEE_RA_EXPORT __declspec(dllexport)
#else
#define COFFEE_RA_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct coffee_ra_client coffee_ra_client;

typedef uint32_t (*coffee_ra_read_memory_callback)(
    uint32_t address, uint8_t* buffer, uint32_t num_bytes, void* userdata);

typedef void (*coffee_ra_server_call_callback)(
    const char* url, const char* post_data, const char* content_type,
    void* server_context, void* userdata);

typedef void (*coffee_ra_operation_callback)(
    int request_id, int result, const char* error_message,
    const char* username, const char* display_name, const char* token,
    uint32_t game_id, const char* game_title,
    uint32_t unlocked_achievements, uint32_t total_achievements,
    void* userdata);

typedef void (*coffee_ra_event_callback)(
    uint32_t event_type, uint32_t id,
    const char* title, const char* description, const char* detail,
    uint32_t value, void* userdata);

typedef void (*coffee_ra_achievement_callback)(
    uint32_t id, const char* title, const char* description,
    const char* measured_progress, uint32_t points,
    uint8_t state, uint8_t unlocked, void* userdata);

COFFEE_RA_EXPORT coffee_ra_client* coffee_ra_create(
    coffee_ra_read_memory_callback read_memory,
    coffee_ra_server_call_callback server_call,
    coffee_ra_operation_callback operation,
    coffee_ra_event_callback event,
    void* userdata);

COFFEE_RA_EXPORT void coffee_ra_destroy(coffee_ra_client* wrapper);
COFFEE_RA_EXPORT void coffee_ra_complete_server_call(
    void* server_context, const uint8_t* body, uint64_t body_length, int http_status_code);

COFFEE_RA_EXPORT void coffee_ra_begin_login_password(
    coffee_ra_client* wrapper, const char* username, const char* password, int request_id);
COFFEE_RA_EXPORT void coffee_ra_begin_login_token(
    coffee_ra_client* wrapper, const char* username, const char* token, int request_id);
COFFEE_RA_EXPORT void coffee_ra_logout(coffee_ra_client* wrapper);

COFFEE_RA_EXPORT void coffee_ra_begin_load_game(
    coffee_ra_client* wrapper, uint32_t console_id, const char* file_path,
    const uint8_t* data, uint64_t data_size, int request_id);
COFFEE_RA_EXPORT void coffee_ra_unload_game(coffee_ra_client* wrapper);
COFFEE_RA_EXPORT int coffee_ra_is_game_loaded(coffee_ra_client* wrapper);

COFFEE_RA_EXPORT void coffee_ra_do_frame(coffee_ra_client* wrapper);
COFFEE_RA_EXPORT void coffee_ra_idle(coffee_ra_client* wrapper);
COFFEE_RA_EXPORT void coffee_ra_reset(coffee_ra_client* wrapper);

COFFEE_RA_EXPORT uint64_t coffee_ra_progress_size(coffee_ra_client* wrapper);
COFFEE_RA_EXPORT int coffee_ra_serialize_progress(
    coffee_ra_client* wrapper, uint8_t* buffer, uint64_t buffer_size);
COFFEE_RA_EXPORT int coffee_ra_deserialize_progress(
    coffee_ra_client* wrapper, const uint8_t* buffer, uint64_t buffer_size);

COFFEE_RA_EXPORT uint64_t coffee_ra_get_user_agent(
    coffee_ra_client* wrapper, char* buffer, uint64_t buffer_size);
COFFEE_RA_EXPORT uint64_t coffee_ra_get_rich_presence(
    coffee_ra_client* wrapper, char* buffer, uint64_t buffer_size);
COFFEE_RA_EXPORT void coffee_ra_iterate_achievements(
    coffee_ra_client* wrapper, coffee_ra_achievement_callback callback, void* userdata);

#ifdef __cplusplus
}
#endif

#endif
