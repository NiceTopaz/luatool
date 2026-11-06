/*
 * JNI bridge between the Java app and the real PUC-Rio Lua 5.3 interpreter.
 *
 *  - Java calls nativeCreate / nativeRun / nativeStop / nativeClose.
 *  - Every gg.* function is a C closure that serialises its Lua arguments into a byte buffer (see Wire.java),
 *    calls the static Java method LuaVM.invoke(int, byte[]) and pushes the decoded results.
 *  - A count hook lets Stop interrupt a running script (the hook is inherited by coroutines).
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

#define MAX_DEPTH 24
#define MAX_NODES (1L << 21)
#define HOOK_COUNT 2000

static JavaVM *g_vm = NULL;
static jclass g_cls = NULL;
static jmethodID g_invoke = NULL;
static volatile int g_stop = 0;
static __thread JNIEnv *t_env = NULL;
static char g_lasterr[512];

typedef struct {
    lua_State *L;
} VM;

/* ------------------------------------------------------------------ growable buffer */

typedef struct {
    unsigned char *p;
    size_t n, cap;
    int oom;
} Buf;

static void bput(Buf *b, const void *d, size_t n) {
    if (b->oom) return;
    if (b->n + n > b->cap) {
        size_t nc = b->cap ? b->cap : 256;
        void *np;
        while (nc < b->n + n) nc *= 2;
        np = realloc(b->p, nc);
        if (!np) { b->oom = 1; return; }
        b->p = (unsigned char *)np;
        b->cap = nc;
    }
    if (n) memcpy(b->p + b->n, d, n);
    b->n += n;
}

static void bput8(Buf *b, unsigned v) {
    unsigned char c = (unsigned char)v;
    bput(b, &c, 1);
}

static void bput32(Buf *b, uint32_t v) {
    unsigned char c[4];
    c[0] = (unsigned char)(v);
    c[1] = (unsigned char)(v >> 8);
    c[2] = (unsigned char)(v >> 16);
    c[3] = (unsigned char)(v >> 24);
    bput(b, c, 4);
}

static void bput64(Buf *b, uint64_t v) {
    unsigned char c[8];
    int i;
    for (i = 0; i < 8; i++) c[i] = (unsigned char)(v >> (8 * i));
    bput(b, c, 8);
}

static void bpatch32(Buf *b, size_t off, uint32_t v) {
    if (b->oom || off + 4 > b->n) return;
    b->p[off] = (unsigned char)(v);
    b->p[off + 1] = (unsigned char)(v >> 8);
    b->p[off + 2] = (unsigned char)(v >> 16);
    b->p[off + 3] = (unsigned char)(v >> 24);
}

/* ------------------------------------------------------------------ Lua -> wire */

static void ser(lua_State *L, int idx, Buf *b, int depth, long *budget) {
    idx = lua_absindex(L, idx);
    if (--(*budget) < 0) { bput8(b, 0); return; }
    switch (lua_type(L, idx)) {
        case LUA_TBOOLEAN:
            bput8(b, lua_toboolean(L, idx) ? 2 : 1);
            break;
        case LUA_TNUMBER:
            if (lua_isinteger(L, idx)) {
                bput8(b, 3);
                bput64(b, (uint64_t)lua_tointeger(L, idx));
            } else {
                double d = (double)lua_tonumber(L, idx);
                uint64_t u;
                memcpy(&u, &d, 8);
                bput8(b, 4);
                bput64(b, u);
            }
            break;
        case LUA_TSTRING: {
            size_t len;
            const char *s = lua_tolstring(L, idx, &len);
            bput8(b, 5);
            bput32(b, (uint32_t)len);
            bput(b, s, len);
            break;
        }
        case LUA_TTABLE: {
            size_t cpos;
            uint32_t cnt = 0;
            if (depth >= MAX_DEPTH || !lua_checkstack(L, 4)) { bput8(b, 0); break; }
            bput8(b, 6);
            cpos = b->n;
            bput32(b, 0);
            lua_pushnil(L);
            while (lua_next(L, idx) != 0) {
                ser(L, -2, b, depth + 1, budget);
                ser(L, -1, b, depth + 1, budget);
                cnt++;
                lua_pop(L, 1);
            }
            bpatch32(b, cpos, cnt);
            break;
        }
        default:
            bput8(b, 0);
            break;
    }
}

/* ------------------------------------------------------------------ wire -> Lua */

static int rd32(const unsigned char **pp, const unsigned char *end, uint32_t *out) {
    const unsigned char *p = *pp;
    if (end - p < 4) return -1;
    *out = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
    *pp = p + 4;
    return 0;
}

static int rd64(const unsigned char **pp, const unsigned char *end, uint64_t *out) {
    const unsigned char *p = *pp;
    uint64_t v = 0;
    int i;
    if (end - p < 8) return -1;
    for (i = 7; i >= 0; i--) v = (v << 8) | p[i];
    *out = v;
    *pp = p + 8;
    return 0;
}

static int deser(lua_State *L, const unsigned char **pp, const unsigned char *end, int depth) {
    int tag;
    if (*pp >= end || depth > 64 || !lua_checkstack(L, 4)) return -1;
    tag = *(*pp)++;
    switch (tag) {
        case 0: lua_pushnil(L); return 0;
        case 1: lua_pushboolean(L, 0); return 0;
        case 2: lua_pushboolean(L, 1); return 0;
        case 3: {
            uint64_t u;
            if (rd64(pp, end, &u)) return -1;
            lua_pushinteger(L, (lua_Integer)(int64_t)u);
            return 0;
        }
        case 4: {
            uint64_t u;
            double d;
            if (rd64(pp, end, &u)) return -1;
            memcpy(&d, &u, 8);
            lua_pushnumber(L, (lua_Number)d);
            return 0;
        }
        case 5: {
            uint32_t len;
            if (rd32(pp, end, &len)) return -1;
            if ((size_t)(end - *pp) < len) return -1;
            lua_pushlstring(L, (const char *)*pp, len);
            *pp += len;
            return 0;
        }
        case 6: {
            uint32_t n, i;
            if (rd32(pp, end, &n)) return -1;
            lua_createtable(L, 0, n < 64 ? (int)n : 64);
            for (i = 0; i < n; i++) {
                int kt;
                if (deser(L, pp, end, depth + 1)) return -1;
                if (deser(L, pp, end, depth + 1)) return -1;
                kt = lua_type(L, -2);
                if (kt == LUA_TNIL || (kt == LUA_TNUMBER && !lua_isinteger(L, -2) &&
                                       lua_tonumber(L, -2) != lua_tonumber(L, -2))) {
                    lua_pop(L, 2); /* invalid key (nil / NaN): drop the pair */
                } else {
                    lua_rawset(L, -3);
                }
            }
            return 0;
        }
        default:
            return -1;
    }
}

/* ------------------------------------------------------------------ gg.* dispatcher */

/*
 * Never longjmp (lua_error) while JNI local references are live: all JNI work happens first, the buffer is
 * copied, the local frame popped, and only then Lua is touched again.
 */
static int l_dispatch(lua_State *L) {
    int id = (int)lua_tointeger(L, lua_upvalueindex(1));
    JNIEnv *env = t_env;
    int nargs = lua_gettop(L);
    Buf b;
    long budget = MAX_NODES;
    int i;
    unsigned char *res = NULL;
    jsize reslen = 0;
    int jfail = 0;

    if (env == NULL) return luaL_error(L, "gg: no JNI environment");

    memset(&b, 0, sizeof(b));
    bput32(&b, (uint32_t)nargs);
    for (i = 1; i <= nargs; i++) ser(L, i, &b, 0, &budget);
    if (b.oom) {
        free(b.p);
        return luaL_error(L, "not enough memory");
    }

    if ((*env)->PushLocalFrame(env, 16) < 0) {
        free(b.p);
        return luaL_error(L, "not enough memory (JNI)");
    }
    {
        jbyteArray arr = (*env)->NewByteArray(env, (jsize)b.n);
        jbyteArray out = NULL;
        if (arr == NULL) {
            jfail = 1;
        } else {
            (*env)->SetByteArrayRegion(env, arr, 0, (jsize)b.n, (const jbyte *)b.p);
            out = (jbyteArray)(*env)->CallStaticObjectMethod(env, g_cls, g_invoke, (jint)id, arr);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                jfail = 1;
            } else if (out == NULL) {
                jfail = 1;
            } else {
                reslen = (*env)->GetArrayLength(env, out);
                res = (unsigned char *)malloc(reslen > 0 ? (size_t)reslen : 1);
                if (res == NULL) jfail = 1;
                else (*env)->GetByteArrayRegion(env, out, 0, reslen, (jbyte *)res);
            }
        }
    }
    (*env)->PopLocalFrame(env, NULL);
    free(b.p);

    if (jfail || reslen < 1) {
        free(res);
        return luaL_error(L, "gg: internal bridge error");
    }

    if (res[0] != 0) { /* error marker: the rest is the message */
        luaL_where(L, 1);
        lua_pushlstring(L, (const char *)res + 1, (size_t)reslen - 1);
        lua_concat(L, 2);
        free(res);
        return lua_error(L);
    }

    {
        const unsigned char *p = res + 1, *end = res + reslen;
        uint32_t n;
        int top = lua_gettop(L), pushed = 0;
        if (rd32(&p, end, &n) || n > 4096 || !lua_checkstack(L, (int)n + 4)) {
            free(res);
            return luaL_error(L, "gg: bad result from bridge");
        }
        for (i = 0; i < (int)n; i++) {
            if (deser(L, &p, end, 0)) {
                lua_settop(L, top);
                free(res);
                return luaL_error(L, "gg: bad result value from bridge");
            }
            pushed++;
        }
        free(res);
        return pushed;
    }
}

static int l_stopped(lua_State *L) {
    lua_pushboolean(L, g_stop);
    return 1;
}

static void stop_hook(lua_State *L, lua_Debug *ar) {
    (void)ar;
    if (g_stop) luaL_error(L, "script stopped");
}

/* ------------------------------------------------------------------ JNI entry points */

static void set_err(const char *m) {
    strncpy(g_lasterr, m ? m : "", sizeof(g_lasterr) - 1);
    g_lasterr[sizeof(g_lasterr) - 1] = 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    jclass c;
    (void)reserved;
    g_vm = vm;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    c = (*env)->FindClass(env, "com/luatool/app/LuaVM");
    if (c == NULL) return JNI_ERR;
    g_cls = (jclass)(*env)->NewGlobalRef(env, c);
    g_invoke = (*env)->GetStaticMethodID(env, c, "invoke", "(I[B)[B");
    if (g_invoke == NULL) return JNI_ERR;
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL Java_com_luatool_app_LuaVM_nativeVersion(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, LUA_RELEASE);
}

JNIEXPORT jstring JNICALL Java_com_luatool_app_LuaVM_nativeLastError(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, g_lasterr);
}

/*
 * ginit  = wire { constants table, array of public function names, array of internal function names }
 * prelude = Lua source (text) run once with (native, gg) as arguments.
 * @return VM handle, or 0 on failure (see nativeLastError).
 */
JNIEXPORT jlong JNICALL Java_com_luatool_app_LuaVM_nativeCreate(JNIEnv *env, jclass cls, jbyteArray ginit,
                                                                jbyteArray prelude) {
    VM *vm;
    lua_State *L;
    jsize ilen, plen;
    unsigned char *ibuf, *pbuf;
    const unsigned char *p, *end;
    uint32_t count;
    int nfn, k, ok = 1;
    (void)cls;

    t_env = env;
    g_stop = 0;
    set_err("");

    ilen = (*env)->GetArrayLength(env, ginit);
    plen = (*env)->GetArrayLength(env, prelude);
    ibuf = (unsigned char *)malloc(ilen > 0 ? (size_t)ilen : 1);
    pbuf = (unsigned char *)malloc(plen > 0 ? (size_t)plen : 1);
    if (!ibuf || !pbuf) { free(ibuf); free(pbuf); set_err("out of memory"); return 0; }
    (*env)->GetByteArrayRegion(env, ginit, 0, ilen, (jbyte *)ibuf);
    (*env)->GetByteArrayRegion(env, prelude, 0, plen, (jbyte *)pbuf);

    L = luaL_newstate();
    if (!L) { free(ibuf); free(pbuf); set_err("cannot create Lua state"); return 0; }
    luaL_openlibs(L);
    lua_sethook(L, stop_hook, LUA_MASKCOUNT, HOOK_COUNT);

    /* decode: [consts][public names][internal names] */
    p = ibuf;
    end = ibuf + ilen;
    if (rd32(&p, end, &count) || count != 3) { ok = 0; set_err("bad init data"); }
    if (ok && deser(L, &p, end, 0)) { ok = 0; set_err("bad init constants"); }          /* gg      @ top-0 */
    if (ok && deser(L, &p, end, 0)) { ok = 0; set_err("bad init names"); }              /* names   */
    if (ok && deser(L, &p, end, 0)) { ok = 0; set_err("bad init internal names"); }     /* internal */

    if (ok) {
        int gg = lua_gettop(L) - 2, names = gg + 1, inames = gg + 2;
        int npub = (int)lua_rawlen(L, names);
        int nint = (int)lua_rawlen(L, inames);

        for (k = 1; k <= npub; k++) {
            lua_rawgeti(L, names, k);
            lua_pushinteger(L, k - 1);
            lua_pushcclosure(L, l_dispatch, 1);
            lua_setfield(L, gg, lua_tostring(L, -2));
            lua_pop(L, 1);
        }
        nfn = npub;

        /* native table for the prelude */
        lua_newtable(L);
        for (k = 1; k <= nint; k++) {
            lua_rawgeti(L, inames, k);
            lua_pushinteger(L, nfn + k - 1);
            lua_pushcclosure(L, l_dispatch, 1);
            lua_setfield(L, -3, lua_tostring(L, -2));
            lua_pop(L, 1);
        }
        lua_pushcfunction(L, l_stopped);
        lua_setfield(L, -2, "stopped");
        /* stack: gg names inames native */

        lua_pushvalue(L, gg);
        lua_setglobal(L, "gg");

        if (luaL_loadbufferx(L, (const char *)pbuf, (size_t)plen, "=prelude", "t") != LUA_OK) {
            ok = 0;
            set_err(lua_tostring(L, -1));
        } else {
            lua_pushvalue(L, lua_gettop(L) - 1); /* native */
            lua_pushvalue(L, gg);                /* gg */
            if (lua_pcall(L, 2, 0, 0) != LUA_OK) {
                ok = 0;
                set_err(lua_tostring(L, -1));
            }
        }
    }
    free(ibuf);
    free(pbuf);

    if (!ok) {
        lua_close(L);
        return 0;
    }
    lua_settop(L, 0);
    vm = (VM *)malloc(sizeof(VM));
    if (!vm) { lua_close(L); set_err("out of memory"); return 0; }
    vm->L = L;
    return (jlong)(intptr_t)vm;
}

/* @return null on success, otherwise the error message bytes. */
JNIEXPORT jbyteArray JNICALL Java_com_luatool_app_LuaVM_nativeRun(JNIEnv *env, jclass cls, jlong h,
                                                                  jbyteArray chunk, jstring jname) {
    VM *vm = (VM *)(intptr_t)h;
    lua_State *L = vm->L;
    jsize len = (*env)->GetArrayLength(env, chunk);
    unsigned char *buf = (unsigned char *)malloc(len > 0 ? (size_t)len : 1);
    const char *cname;
    char name[300];
    int st;
    jbyteArray result = NULL;
    (void)cls;

    if (!buf) return NULL;
    (*env)->GetByteArrayRegion(env, chunk, 0, len, (jbyte *)buf);
    cname = (*env)->GetStringUTFChars(env, jname, NULL);
    strncpy(name, "@", 2);
    if (cname) {
        strncat(name, cname, sizeof(name) - 3);
        (*env)->ReleaseStringUTFChars(env, jname, cname);
    }
    t_env = env;

    st = luaL_loadbufferx(L, (const char *)buf, (size_t)len, name, NULL);
    free(buf);
    if (st == LUA_OK) st = lua_pcall(L, 0, 0, 0);
    if (st != LUA_OK) {
        size_t mlen;
        const char *msg;
        char tmp[96];
        if (lua_type(L, -1) == LUA_TSTRING || lua_type(L, -1) == LUA_TNUMBER) {
            msg = lua_tolstring(L, -1, &mlen);
        } else if (luaL_callmeta(L, -1, "__tostring") && lua_type(L, -1) == LUA_TSTRING) {
            msg = lua_tolstring(L, -1, &mlen);
        } else {
            mlen = (size_t)snprintf(tmp, sizeof(tmp), "(error object is a %s value)", luaL_typename(L, -1));
            msg = tmp;
        }
        result = (*env)->NewByteArray(env, (jsize)mlen);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)mlen, (const jbyte *)msg);
        lua_settop(L, 0);
        return result;
    }
    lua_settop(L, 0);
    return NULL;
}

JNIEXPORT void JNICALL Java_com_luatool_app_LuaVM_nativeStop(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls; (void)h;
    g_stop = 1;
}

JNIEXPORT void JNICALL Java_com_luatool_app_LuaVM_nativeClose(JNIEnv *env, jclass cls, jlong h) {
    VM *vm = (VM *)(intptr_t)h;
    (void)cls;
    t_env = env;
    if (vm) {
        lua_close(vm->L);
        free(vm);
    }
}
