cmake_minimum_required(VERSION 3.22.1)
project(auralis_discord_bridge)

add_library(auralis_discord_bridge SHARED DiscordSocialBridge.cpp)

add_library(discord_partner_sdk SHARED IMPORTED)
set_target_properties(discord_partner_sdk PROPERTIES
    IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libdiscord_partner_sdk.so"
)

target_include_directories(auralis_discord_bridge PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/discord")

find_library(log-lib log)

target_link_libraries(
    auralis_discord_bridge
    PRIVATE
    discord_partner_sdk
    ${log-lib}
)
