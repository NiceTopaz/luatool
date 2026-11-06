-- Example for LuaTool (GameGuardian-style API). Lua 5.3 syntax (// | & ~ << >> and 64-bit integers all work).
local r = gg.prompt({"Value to search", "Freeze the first result?"}, {"100", false}, {"number", "checkbox"})
if r == nil then return end

gg.setRanges(gg.REGION_ANONYMOUS + gg.REGION_C_ALLOC + gg.REGION_C_HEAP + gg.REGION_C_DATA)
gg.clearResults()
gg.searchNumber(r[1], gg.TYPE_DWORD)

local n = gg.getResultsCount()
if n == 0 then
  gg.alert("Nothing found for " .. tostring(r[1]))
  return
end

local res = gg.getResults(10)
local lines = {}
for i, v in ipairs(res) do lines[#lines + 1] = string.format("%d: addr %d = %s", i, v.address, tostring(v.value)) end
local pick = gg.alert("Found " .. n .. " results\n\n" .. table.concat(lines, "\n"), "Edit all", "Cancel")
if pick == 1 then
  local e = gg.prompt({"New value"}, {"999"}, {"number"})
  if e then
    gg.editAll(e[1], gg.TYPE_DWORD)
    if r[2] and res[1] then
      gg.addListItems({{address = res[1].address, flags = gg.TYPE_DWORD, value = e[1], freeze = true}})
    end
    gg.toast("Done")
  end
end
