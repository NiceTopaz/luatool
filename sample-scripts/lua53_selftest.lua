-- LuaTool engine self-test. Import it, press Run (no process needs to be selected).
-- Every line should say PASS. Tells you whether the real Lua 5.3 engine is active.
local pass, fail = 0, 0
local function t(name, ok)
  if ok then pass = pass + 1 else fail = fail + 1 end
  print((ok and "PASS " or "FAIL ") .. name)
end

print(_VERSION)
t("_VERSION is Lua 5.3", _VERSION == "Lua 5.3")
t("integer division", 7 // 2 == 3 and math.type(7 // 2) == "integer")
t("bitwise operators", (5 & 3) == 1 and (5 | 3) == 7 and (5 ~ 3) == 6 and (1 << 40) == 1099511627776 and (~0) == -1)
t("64-bit integers wrap", math.maxinteger + 1 == math.mininteger)
t("float vs integer", math.type(1.0) == "float" and tostring(2.0) == "2.0" and 3 | 0 == 3)
t("string.pack/unpack", string.unpack("<i4", string.pack("<i4", -2)) == -2)
t("utf8 library", utf8.len("h\u{E9}llo") == 5 and utf8.char(8364) == "\u{20AC}")
t("goto", (function() local n = 0 ::top:: n = n + 1 if n < 3 then goto top end return n end)() == 3)
t("table.move", table.concat(table.move({1, 2, 3}, 1, 3, 2, {9}), ",") == "9,1,2,3")
t("load with env", load("return x", "c", "t", {x = 42})() == 42)
t("bytecode round trip", load(string.dump(function(a) return a * 2 end), "c", "b")(21) == 42)
t("coroutines", coroutine.wrap(function() coroutine.yield(1) return 2 end)() == 1)
t("string escapes", "\x41\65\u{41}" == "AAA" and #"\z
      abc" == 3)
t("integer for loop", (function() local s = 0 for i = 10, 1, -3 do s = s + i end return s end)() == 22)
t("pcall + error objects", select(2, pcall(error, {code = 7})).code == 7)
t("bit32 (compat)", bit32 ~= nil and bit32.band(12, 10) == 8)
t("5.1 compat shims", unpack ~= nil and loadstring ~= nil and setfenv ~= nil)
t("raw bytes in strings", #"\255\0\128" == 3)
t("gg table", type(gg) == "table" and gg.TYPE_DWORD == 4 and type(gg.alert) == "function")

local msg = string.format("%d passed, %d failed", pass, fail)
print(msg)
gg.alert("LuaTool self-test\n" .. _VERSION .. "\n" .. msg)
