-- Grouped search for an exact, packed byte sequence in the selected process.
--
-- Your notation:   2:0  2:0  2:65535  2:65535  2:257  1:0-255  w:35  2:16128  1:0
-- (size:value -> 2 = WORD (2 bytes), 1 = BYTE, w = WORD, 0-255 = any byte value)
-- Note: I read "2:655352:257" as two values: 2:65535 and 2:257.
--
-- Same thing in GameGuardian group syntax (W = word, B = byte, a~b = range).
-- The values must follow each other directly in memory, in this order (16 bytes total):
local GROUP = "0W;0W;65535W;65535W;257W;0~255B;35W;16128W;0B"

if gg.getTargetInfo() == nil then
  gg.alert("No process selected.\nPick a process in the LuaTool panel first.")
  return
end

-- all writable memory regions
gg.setRanges(gg.REGION_ANONYMOUS + gg.REGION_C_ALLOC + gg.REGION_C_HEAP + gg.REGION_C_DATA
           + gg.REGION_C_BSS + gg.REGION_JAVA_HEAP + gg.REGION_STACK + gg.REGION_ASHMEM)
gg.clearResults()
gg.toast("Searching...")
gg.searchNumber(GROUP, gg.TYPE_BYTE)

local n = gg.getResultsCount()
local msg
if n == 0 then
  msg = "0 such values found."
elseif n == 1 then
  msg = "1 value found (exact match)"
else
  msg = n .. " such values found"
end

-- list the first addresses in the panel log
local res = gg.getResults(10)
for i, v in ipairs(res) do
  print(i .. ": address " .. tostring(v.address))
end

gg.toast(msg)
gg.alert(msg)
