-- LuaTool prelude: runs once (as a Lua 5.3 chunk) before every script.
-- Receives: native (internal helpers, not visible to scripts), gg (the GameGuardian-style table).
local native, gg = ...
local log, stopped = native.log, native.stopped
local tostring, select, type, concat = tostring, select, type, table.concat

-- ---------------------------------------------------------------- output
-- print() and io.write() go to the LuaTool log panel (Android has no visible stdout).
function print(...)
  local n = select('#', ...)
  local t = {}
  for i = 1, n do t[i] = tostring((select(i, ...))) end
  log(concat(t, '\t'))
end

do
  local pending = ''
  io.write = function(...)
    local t = {}
    for i = 1, select('#', ...) do t[#t + 1] = tostring((select(i, ...))) end
    pending = pending .. concat(t)
    while true do
      local nl = pending:find('\n', 1, true)
      if not nl then break end
      log(pending:sub(1, nl - 1))
      pending = pending:sub(nl + 1)
    end
    return io.stdout
  end
end

-- ---------------------------------------------------------------- stopping
-- pcall must never swallow the "script stopped" error, otherwise a script could ignore the Stop button.
do
  local _pcall, _xpcall = pcall, xpcall
  local function chk(ok, ...)
    if not ok and stopped() then error('script stopped', 0) end
    return ok, ...
  end
  function pcall(f, ...) return chk(_pcall(f, ...)) end
  function xpcall(f, h, ...) return chk(_xpcall(f, h, ...)) end
end

-- os.exit() ends the script (it must not kill the whole app)
function os.exit()
  native.exit()
  while true do end   -- the stop hook raises "script stopped" within a few thousand instructions
end

-- ---------------------------------------------------------------- Lua 5.1/5.2 compatibility shims
-- GG itself targets Lua 5.3, but many scripts and obfuscators probe for these.
unpack = unpack or table.unpack
loadstring = loadstring or load
table.getn = table.getn or function(t) return #t end
math.pow = math.pow or function(a, b) return a ^ b end
math.mod = math.mod or math.fmod

do
  local function envindex(f)
    local i = 1
    while true do
      local name = debug.getupvalue(f, i)
      if name == '_ENV' then return i end
      if not name then return nil end
      i = i + 1
    end
  end

  if not setfenv then
    function setfenv(f, t)
      if type(f) == 'number' then
        local info = debug.getinfo(f + 1, 'f')
        f = info and info.func
        if not f then error("bad argument #1 to 'setfenv' (invalid level)", 2) end
      end
      local i = envindex(f)
      if i then debug.upvaluejoin(f, i, function() return t end, 1) end
      return f
    end
  end

  if not getfenv then
    function getfenv(f)
      if f == nil then f = 1 end
      if type(f) == 'number' then
        if f == 0 then return _G end
        local info = debug.getinfo(f + 1, 'f')
        f = info and info.func
        if not f then error("bad argument #1 to 'getfenv' (invalid level)", 2) end
      end
      local i = envindex(f)
      if i then
        local _, v = debug.getupvalue(f, i)
        return v
      end
      return _G
    end
  end
end

-- bit32 (normally provided by the Lua 5.3 build via LUA_COMPAT_5_2; this is a safety net if it is missing)
if not bit32 then
  local M = 0xFFFFFFFF
  local function n(x) return math.tointeger(x) or math.tointeger(math.floor(x)) or 0 end
  local function u(x) return n(x) & M end
  local b = {}
  function b.band(...) local r = M for i = 1, select('#', ...) do r = r & u((select(i, ...))) end return r end
  function b.bor(...) local r = 0 for i = 1, select('#', ...) do r = r | u((select(i, ...))) end return r end
  function b.bxor(...) local r = 0 for i = 1, select('#', ...) do r = r ~ u((select(i, ...))) end return r end
  function b.bnot(x) return (~u(x)) & M end
  function b.btest(...) return b.band(...) ~= 0 end
  function b.lshift(x, d) d = n(d) if d >= 32 or d <= -32 then return 0 end if d >= 0 then return (u(x) << d) & M end return u(x) >> -d end
  function b.rshift(x, d) return b.lshift(x, -n(d)) end
  function b.arshift(x, d)
    x = u(x) d = n(d)
    if d <= 0 then return b.lshift(x, -d) end
    if d >= 32 then return (x & 0x80000000 ~= 0) and M or 0 end
    local r = x >> d
    if x & 0x80000000 ~= 0 then r = r | ((M << (32 - d)) & M) end
    return r
  end
  function b.lrotate(x, d) d = n(d) % 32 x = u(x) return ((x << d) | (x >> (32 - d))) & M end
  function b.rrotate(x, d) return b.lrotate(x, -n(d)) end
  function b.extract(x, f, w) w = w or 1 return (u(x) >> n(f)) & ((1 << w) - 1) end
  function b.replace(x, v, f, w)
    w = w or 1
    local mask = ((1 << w) - 1) << n(f)
    return (u(x) & ~mask & M) | ((u(v) << n(f)) & mask)
  end
  bit32 = b
end

-- ---------------------------------------------------------------- environment
do
  local seq = 0
  local tmp = gg.TMP_PATH or '.'
  function os.tmpname()
    seq = seq + 1
    local name = tmp .. '/lua_' .. os.time() .. '_' .. seq
    local f = io.open(name, 'w')
    if f then f:close() end
    return name
  end
end

if gg.FILES_DIR then
  -- `require` can load scripts imported into LuaTool and files next to them
  local d = gg.FILES_DIR .. '/scripts/'
  package.path = d .. '?.lua;' .. d .. '?;' .. package.path
end
math.randomseed(os.time())

-- gg.saveVariable(value, filename): writes `return <value>` so that dofile/loadfile can read it back.
do
  local function ser(v, out, seen)
    local t = type(v)
    if t == 'string' then
      out[#out + 1] = string.format('%q', v)
    elseif t == 'number' then
      if math.type(v) == 'integer' then out[#out + 1] = string.format('%d', v)
      elseif v ~= v then out[#out + 1] = '(0/0)'
      elseif v == math.huge then out[#out + 1] = 'math.huge'
      elseif v == -math.huge then out[#out + 1] = '-math.huge'
      else out[#out + 1] = string.format('%.17g', v) end
    elseif t == 'boolean' then
      out[#out + 1] = tostring(v)
    elseif t == 'table' then
      if seen[v] then error('cannot save a table with cycles', 0) end
      seen[v] = true
      out[#out + 1] = '{'
      for k, x in pairs(v) do
        out[#out + 1] = '['
        ser(k, out, seen)
        out[#out + 1] = ']='
        ser(x, out, seen)
        out[#out + 1] = ','
      end
      out[#out + 1] = '}'
      seen[v] = nil
    else
      out[#out + 1] = 'nil'
    end
  end
  function gg.saveVariable(v, filename)
    local out = { 'return ' }
    ser(v, out, {})
    local f, err = io.open(filename, 'wb')
    if not f then return err end
    f:write(concat(out))
    f:close()
    return true
  end
end
