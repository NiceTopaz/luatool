#!/bin/sh
# Downloads the official Lua 5.3.6 sources into app/src/main/cpp/lua (needs internet access).
set -e
cd "$(dirname "$0")"
rm -rf lua _lua_tmp
mkdir lua
if git clone --depth 1 --branch v5.3.6 https://github.com/lua/lua.git _lua_tmp 2>/dev/null; then
  if [ -f _lua_tmp/lua.h ]; then SRC=_lua_tmp; else SRC=_lua_tmp/src; fi
else
  curl -fsSL https://www.lua.org/ftp/lua-5.3.6.tar.gz -o lua.tgz
  tar xzf lua.tgz
  SRC=lua-5.3.6/src
fi
cp "$SRC"/*.c "$SRC"/*.h lua/
rm -rf _lua_tmp lua-5.3.6 lua.tgz
grep -q '"Lua 5.3' lua/lua.h || { echo "unexpected Lua version"; exit 1; }
echo "Lua sources ready in $(pwd)/lua"
