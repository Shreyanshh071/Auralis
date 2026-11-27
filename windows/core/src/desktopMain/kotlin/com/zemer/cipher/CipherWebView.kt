package com.zemer.cipher

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Desktop port of zemer-cipher's CipherWebView: YouTube's player script, with the signature and
 * `n` functions exported to `window` (same injection as upstream), loaded into an unseen
 * [HeadlessChromium] tab instead of an Android WebView. Calls are plain awaited evaluations, so the
 * upstream JS bridge and continuation slots aren't needed. The name stays for the shared callers.
 */
class CipherWebView private constructor(
    private val page: HeadlessChromium.Page,
    private val sigInfo: FunctionNameExtractor.SigFunctionInfo?,
) {
    @Volatile var nFunctionAvailable = false
        private set
    @Volatile private var closed = false

    /** The tab crashed, timed out or the browser closed: drop this instance and build a new one. */
    val isDead: Boolean get() = closed || page.dead

    suspend fun deobfuscateSignature(obfuscatedSig: String): String {
        if (sigInfo == null) throw CipherException("Signature function info not available")
        val constArg = sigInfo.constantArg?.toString() ?: "null"
        val result = call("""(function(){
            var f = window._cipherSigFunc;
            if (typeof f !== 'function') throw new Error('Sig func not found on window (type: ' + typeof f + ')');
            var c = $constArg, s = ${JSONObject.quote(obfuscatedSig)};
            var r = (f.length === 1 || c === null) ? f(s) : f(c, s);
            if (r === undefined || r === null) throw new Error('Function returned null/undefined');
            return String(r);
        })()""", "Sig deobfuscation")
        return result
    }

    suspend fun transformN(nValue: String): String {
        if (!nFunctionAvailable) throw CipherException("N-transform function not discovered")
        return call("""(function(){
            var f = window._nTransformFunc;
            if (typeof f !== 'function') throw new Error('N-transform func not available');
            var r = f(${JSONObject.quote(nValue)});
            if (r === undefined || r === null) throw new Error('N-transform returned null/undefined');
            return String(r);
        })()""", "N-transform")
    }

    private suspend fun call(expression: String, what: String): String {
        if (isDead) throw CipherRendererGoneException("Cipher tab is gone — instance must be recreated")
        return try {
            page.evaluate(expression, EVAL_TIMEOUT_MS)?.toString() ?: throw CipherException("$what returned nothing")
        } catch (e: CancellationException) {
            throw e
        } catch (e: HeadlessChromium.JsException) {
            if (page.dead) throw CipherRendererGoneException("$what: ${e.message}")
            throw CipherException("$what failed: ${e.message}")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { page.close() }
    }

    companion object {
        private const val TAG = "Zemer_CipherWebView"
        private const val ORIGIN = "https://www.youtube.com/auralis-cipher/"
        private const val CREATE_TIMEOUT_MS = 30_000L
        private const val EVAL_TIMEOUT_MS = 15_000L

        /** Upstream's n-function check: an exported function that changes a sample into URL-safe text. */
        private val DISCOVERY_JS = """(function(){
            var nName = '', info = '';
            var sig = typeof window._cipherSigFunc === 'function';
            function plausible(input, out) { return typeof out === 'string' && out !== input && out.length >= 5 && /^[a-zA-Z0-9_-]+$/.test(out); }
            if (typeof window._nTransformFunc === 'function') {
                try { var t = window._nTransformFunc('KdrqFlzJXl9EcCwlmEy');
                    if (plausible('KdrqFlzJXl9EcCwlmEy', t)) { nName = 'exported_n_func'; info = 'export_valid'; }
                    else { info = 'export_bad_result'; window._nTransformFunc = null; }
                } catch (e) { info = 'export_threw:' + e; window._nTransformFunc = null; }
            }
            if (!nName) {
                var input = 'T2Xw3pWQ_Wk0xbOg', keys = Object.getOwnPropertyNames(window), tested = 0;
                for (var i = 0; i < keys.length && !nName; i++) {
                    var key = keys[i];
                    if (key.startsWith('webkit') || key.startsWith('on') || key === '_cipherSigFunc' || key === '_nTransformFunc' || key === 'window' || key === 'self') continue;
                    try { var fn = window[key]; if (typeof fn !== 'function' || fn.length !== 1) continue; tested++;
                        if (plausible(input, fn(input))) { window._nTransformFunc = fn; nName = key; }
                    } catch (e) {}
                }
                info = 'brute_force:tested=' + tested + (nName ? ',found' : '');
            }
            return JSON.stringify({ sig: sig, n: nName, info: info });
        })()"""

        suspend fun create(
            playerJs: String,
            sigInfo: FunctionNameExtractor.SigFunctionInfo?,
            nFuncInfo: FunctionNameExtractor.NFunctionInfo? = null,
        ): CipherWebView {
            val modifiedJs = buildModifiedPlayerJsImpl(playerJs, sigInfo, nFuncInfo)
            val page = try {
                HeadlessChromium.openPage()
            } catch (e: HeadlessChromium.ChromiumUnavailable) {
                throw CipherException(e.message ?: "Chromium unavailable")
            }
            try {
                page.serve("${ORIGIN}index.html", "<!DOCTYPE html><html><head><script src=\"player.js\"></script></head><body></body></html>", "text/html; charset=utf-8")
                page.serve("${ORIGIN}player.js", modifiedJs, "text/javascript; charset=utf-8")
                page.navigate("${ORIGIN}index.html", CREATE_TIMEOUT_MS)
                val found = JSONObject(page.evaluate(DISCOVERY_JS, CREATE_TIMEOUT_MS)?.toString() ?: "{}")
                Timber.tag(TAG).d("Discovery: sig=${found.optBoolean("sig")}, n=${found.optString("n")}, ${found.optString("info")}")
                if (!found.optBoolean("sig") && found.optString("n").isBlank()) throw CipherException("Player JS exported neither function")
                return CipherWebView(page, sigInfo).apply { nFunctionAvailable = found.optString("n").isNotBlank() }
            } catch (e: Throwable) {
                runCatching { page.close() }
                if (e is HeadlessChromium.JsException) throw CipherRendererGoneException("Cipher tab init failed: ${e.message}")
                throw e
            }
        }

        /** Upstream's export injection, unchanged: exposes the sig/n functions on `window`. */
        private fun buildModifiedPlayerJsImpl(
            playerJs: String,
            sigInfo: FunctionNameExtractor.SigFunctionInfo?,
            nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
        ): String {
            val sigFuncName = sigInfo?.name
            val nFuncName = nFuncInfo?.name
            val nArrayIdx = nFuncInfo?.arrayIndex
            val isHardcoded = sigInfo?.isHardcoded == true || nFuncInfo?.isHardcoded == true

            val exports = buildList {
                val sigJsExpr = sigInfo?.jsExpression
                if (sigJsExpr != null) {
                    val expr = sigJsExpr.replace("INPUT", "sig")
                    add("window._cipherSigFunc = function(sig) { try { return $expr; } catch(e) { return null; } };")
                } else if (sigFuncName != null) {
                    val sigConstArgs = sigInfo.constantArgs
                    val preprocessFunc = sigInfo.preprocessFunc
                    val preprocessArgs = sigInfo.preprocessArgs
                    if (!sigConstArgs.isNullOrEmpty() && preprocessFunc != null && !preprocessArgs.isNullOrEmpty()) {
                        val mainArgsStr = sigConstArgs.joinToString(", ")
                        val prepArgsStr = preprocessArgs.joinToString(", ")
                        add("window._cipherSigFunc = function(sig) { return $sigFuncName($mainArgsStr, $preprocessFunc($prepArgsStr, sig)); };")
                    } else if (!sigConstArgs.isNullOrEmpty()) {
                        add("window._cipherSigFunc = function(sig) { return $sigFuncName(${sigConstArgs.joinToString(", ")}, sig); };")
                    } else {
                        add("window._cipherSigFunc = typeof $sigFuncName !== 'undefined' ? $sigFuncName : null;")
                    }
                }
                val nJsExpr = nFuncInfo?.jsExpression
                if (nJsExpr != null) {
                    val expr = nJsExpr.replace("INPUT", "n")
                    add("window._nTransformFunc = function(n) { try { return $expr; } catch(e) { return n; } };")
                } else if (nFuncName != null) {
                    val nConstArgs = nFuncInfo.constantArgs
                    if (!nConstArgs.isNullOrEmpty()) {
                        add("window._nTransformFunc = function(n) { return $nFuncName(${nConstArgs.joinToString(", ")}, n); };")
                    } else {
                        val nExpr = if (nArrayIdx != null) "$nFuncName[$nArrayIdx]" else nFuncName
                        add("window._nTransformFunc = typeof $nFuncName !== 'undefined' ? $nExpr : null;")
                    }
                }
            }
            Timber.tag(TAG).d("Export mode: ${if (isHardcoded) "HARDCODED" else "EXTRACTED"}, ${exports.size} exports")
            if (exports.isEmpty()) return playerJs
            val exportCode = "; " + exports.joinToString(" ")
            val modified = playerJs.replace("})(_yt_player);", "$exportCode })(_yt_player);")
            return if (modified == playerJs) playerJs + "\n" + exportCode else modified
        }
    }
}

class CipherException(message: String) : Exception(message)

/**
 * The cipher tab crashed or stopped responding (or the browser closed). The instance is unusable;
 * callers drop it and decide whether recreating is worth it (see [RendererRecoveryPolicy]).
 */
class CipherRendererGoneException(message: String) : Exception(message)
