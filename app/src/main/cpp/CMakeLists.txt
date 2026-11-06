cmake_minimum_required(VERSION 3.10)
project(lua53jni C)

# Real PUC-Rio Lua 5.3.x sources live in ./lua (fetched by the GitHub workflow or by fetch_lua.sh).
set(LUA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lua)
if(NOT EXISTS ${LUA_DIR}/lua.h)
    message(FATAL_ERROR "Lua sources not found in ${LUA_DIR}. Run app/src/main/cpp/fetch_lua.sh (the GitHub workflow does it for you).")
endif()

file(GLOB LUA_SRC ${LUA_DIR}/*.c)
list(FILTER LUA_SRC EXCLUDE REGEX ".*/(lua|luac)\\.c$")

add_library(lua53jni SHARED luabridge.c ${LUA_SRC})
target_include_directories(lua53jni PRIVATE ${LUA_DIR})
# POSIX: io.popen, mkstemp, fseeko.  COMPAT_5_2: bit32, math.pow & co (as in the stock Lua 5.3 build).
target_compile_definitions(lua53jni PRIVATE LUA_USE_POSIX LUA_COMPAT_5_2)
target_compile_options(lua53jni PRIVATE -O2)
