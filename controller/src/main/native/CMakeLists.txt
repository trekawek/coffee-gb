cmake_minimum_required(VERSION 3.18)

project(coffee_rcheevos C)

include(FetchContent)

# Keep the runtime revision and archive checksum together. The build never follows a
# moving branch, and the resulting shared library is small enough to ship in the JAR.
FetchContent_Declare(
    rcheevos
    URL https://github.com/RetroAchievements/rcheevos/archive/refs/tags/v12.3.0.tar.gz
    URL_HASH SHA256=bc7ab9985aee7b6a29e3d65492de9371c866236c5873a1c4493ec9cb6d2603d2
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(rcheevos)

set(RCHEEVOS_SOURCES
    ${rcheevos_SOURCE_DIR}/src/rc_client.c
    ${rcheevos_SOURCE_DIR}/src/rc_compat.c
    ${rcheevos_SOURCE_DIR}/src/rc_util.c
    ${rcheevos_SOURCE_DIR}/src/rc_version.c
    ${rcheevos_SOURCE_DIR}/src/rapi/rc_api_common.c
    ${rcheevos_SOURCE_DIR}/src/rapi/rc_api_editor.c
    ${rcheevos_SOURCE_DIR}/src/rapi/rc_api_info.c
    ${rcheevos_SOURCE_DIR}/src/rapi/rc_api_runtime.c
    ${rcheevos_SOURCE_DIR}/src/rapi/rc_api_user.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/alloc.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/condition.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/condset.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/consoleinfo.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/format.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/lboard.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/memref.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/operand.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/rc_validate.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/richpresence.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/runtime.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/runtime_progress.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/trigger.c
    ${rcheevos_SOURCE_DIR}/src/rcheevos/value.c
    ${rcheevos_SOURCE_DIR}/src/rhash/hash.c
    ${rcheevos_SOURCE_DIR}/src/rhash/hash_rom.c
    ${rcheevos_SOURCE_DIR}/src/rhash/md5.c
)

add_library(coffee_rcheevos SHARED coffee_rcheevos.c ${RCHEEVOS_SOURCES})
target_include_directories(coffee_rcheevos PRIVATE
    ${rcheevos_SOURCE_DIR}/include
    ${rcheevos_SOURCE_DIR}/src/rcheevos
)
target_compile_definitions(coffee_rcheevos PRIVATE
    RC_CLIENT_SUPPORTS_HASH
    RC_DISABLE_LUA
    RC_HASH_NO_DISC
    RC_HASH_NO_ZIP
    RC_HASH_NO_ENCRYPTED
)
set_target_properties(coffee_rcheevos PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED TRUE
    OUTPUT_NAME coffee_rcheevos
)

if(NOT MSVC)
  target_compile_options(coffee_rcheevos PRIVATE -fvisibility=hidden)
endif()

if(UNIX AND NOT APPLE)
  target_link_libraries(coffee_rcheevos PRIVATE m)
endif()

install(TARGETS coffee_rcheevos
    RUNTIME DESTINATION .
    LIBRARY DESTINATION .
)
