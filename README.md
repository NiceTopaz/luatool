# LuaTool

A GameGuardian-style tool: pick a process, run `.lua` scripts against its memory, floating overlay UI.
No root needed inside a virtual environment (BlackBox etc.): the cloned game and this tool run under the same uid.

## What changed in 1.1
* **Real Lua 5.3** (PUC-Rio 5.3.6, compiled from C with the NDK) is now the main engine - the same language level as
  current GameGuardian: 64-bit integers, `// | & ~ << >>`, `goto`, `utf8`, `string.pack`, `bit32` (compat), exact
  float/integer printing, Lua 5.3 **bytecode** (`luac 5.3`, `string.dump`). Obfuscated/encrypted scripts are plain Lua
  (or bytecode) and run on it at native speed.
* **Scripts are loaded as bytes, never as text.** Before, the file was decoded as UTF-8, which destroyed bytecode and
  any string with non-UTF-8 bytes - that is why obfuscated scripts failed.
* **Lua 5.2 bytecode** (older GG scripts) still runs: it is detected from the file header and sent to the LuaJ engine.
* Scripts no longer need a selected process (memory functions then raise "no process selected").
* `os.exit()` stops the script (it can't kill the app), `print`/`io.write` go to the log, `pcall` can't swallow Stop,
  Stop also works inside coroutines.
* Compatibility shims for scripts written for other Lua versions: `unpack`, `loadstring`, `setfenv`/`getfenv`,
  `math.pow`, `bit32`.
* More of the `gg` API (see below).

## How to use
1. Install the APK, open it, tap "Overlay permission" (inside a clone: allow "Display over other apps" for the host app).
2. "Start floating menu" -> a draggable "Lua" bubble appears over other apps. Tap it to open the panel.
3. "Import .lua file" (file picker) or "Paste script from clipboard" (text scripts only - **binary/bytecode files must be
   imported with the file picker**, never pasted or copied through a text editor/chat).
4. Pick a process (optional) and a script, Run. Stop ends the script and un-freezes values.
5. Run `sample-scripts/lua53_selftest.lua` once: every line should say PASS and the first line must be `Lua 5.3`.

## Running obfuscated / encrypted scripts
* Source-obfuscated scripts (Luraph and similar) are normal Lua text: import the file and run it.
* Bytecode scripts: Lua 5.3 bytecode runs on the 5.3 engine, Lua 5.2 bytecode on the 5.2 engine. Lua 5.1 and 5.4 bytecode
  can't be loaded (different format) - the log says so.
* "the file starts like Lua bytecode, but its header is damaged" means the file was corrupted before it reached the app
  (zero bytes dropped / bytes replaced by U+FFFD by a text pipeline). Re-copy it as a binary, e.g. inside a .zip.
* Scripts that check `gg.VERSION`/`gg.BUILD` see GG-like values (`101.1`, `16142`); `gg.LUATOOL` identifies this app.
* Things LuaTool deliberately does not do: bypass anything, un-protect scripts, or emulate GG-only native internals.

## Lua API (GameGuardian `gg`)
UI: alert, toast, choice, multiChoice, prompt, isVisible, setVisible, showUiButton, hideUiButton, isClickedUiButton
Memory: setRanges, getRanges, getRangesList, searchNumber, refineNumber, getResultsCount, getResults, clearResults,
editAll, getValues, setValues
List/freeze: addListItems (freeze=true), getListItems, removeListItems, clearList
Misc: sleep, getTargetInfo, getTargetPackage, processPause/Resume/Kill, copyText, getFile, skipRestoreState, require,
getLocale, numberFromLocale/ToLocale, bytes, isPackageInstalled, makeRequest (HTTP GET/POST), saveVariable, getSpeed/setSpeed (no speedhack)
Constants: TYPE_*, REGION_*, SIGN_*, LOAD_*, SAVE_*, PROT_*, ASM_*, VERSION, BUILD, FILES_DIR, TMP_PATH, EXT_STORAGE, ...

Calling something GG has but LuaTool doesn't (fuzzy search, pointer/breakpoint tools, dumpMemory, ...) raises
`gg.<name> is not supported by LuaTool` instead of a vague "attempt to call a nil value".
Search values accept `100`, a range `10~20`, or a GG-style group like `0W;65535W;0~255B`.
Scripts can still use io/os, so only run scripts you trust.

## Building (GitHub Actions)
Push the project (or just `luatool.zip` in the repo root) and run the "Build LuaTool APK" workflow. It downloads the
official Lua 5.3.6 sources (`app/src/main/cpp/fetch_lua.sh`), installs NDK 26 + CMake, builds the debug APK for
armeabi-v7a, arm64-v8a, x86, x86_64, and checks that `liblua53jni.so` is inside it.
If the Lua sources are missing the app still builds, but only with the old Lua 5.2 engine (the log says so).
Local build: run `sh app/src/main/cpp/fetch_lua.sh` once, then `gradle assembleDebug`.

## Layout
* `app/src/main/cpp/` - C bridge (`luabridge.c`) + CMake; Lua sources are fetched into `cpp/lua/`
* `app/src/main/assets/prelude.lua` - Lua-side compatibility layer, runs before every script
* `GgCore.java` - the `gg` API (engine independent), `Wire.java` - value codec, `LuaVM.java` - JNI wrapper,
  `ScriptRunner.java` - picks the engine from the file header, `LuaJEngine.java` - Lua 5.2 fallback

LuaJ (MIT) is vendored under app/src/main/java/org/luaj - see LICENSE-luaj.txt. Lua is MIT licensed (lua.org).
