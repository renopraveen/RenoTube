package com.liskovsoft.youtubeapi.app.nsigsolver.impl

import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8ScriptExecutionException
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.app.nsigsolver.common.withLock
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderError
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.JsRuntimeChalBaseJCP
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.Script
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptSource
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptType
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptVariant

internal object V8ChallengeProvider: JsRuntimeChalBaseJCP() {
    private val tag = V8ChallengeProvider::class.simpleName
    private val v8NpmLibFilename = listOf("${libPrefix}polyfill.js", "${libPrefix}meriyah-6.1.4.min.js", "${libPrefix}astring-1.9.0.min.js")
    private var v8Runtime: V8? = null
    private val v8Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "V8ChallengeWorker").apply { isDaemon = true }
    }

    override fun iterScriptSources(): Sequence<Pair<ScriptSource, (ScriptType) -> Script?>> = sequence {
        for ((source, func) in super.iterScriptSources()) {
            if (source == ScriptSource.WEB || source == ScriptSource.BUILTIN)
                yield(Pair(ScriptSource.BUILTIN, ::v8NpmSource))
            yield(Pair(source, func))
        }
    }

    private fun v8NpmSource(scriptType: ScriptType): Script? {
        if (scriptType != ScriptType.LIB)
            return null
        // V8-specific lib scripts that uses Deno NPM imports
        val code = loadScript(v8NpmLibFilename, "Failed to read v8 challenge solver lib script")
        return Script(scriptType, ScriptVariant.V8_NPM, ScriptSource.BUILTIN, scriptVersion, code)
    }

    override fun runJsRuntime(stdin: String): String {
        return v8Executor.submit(java.util.concurrent.Callable {
            try {
                initRuntime()
                runV8(stdin)
            } catch (e: Exception) {
                // If a fatal engine or script error occurred, reset runtime to recover on next invocation
                disposeRuntime()
                throw e
            }
        }).get(15, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun runV8(stdin: String): String {
        val runtime = v8Runtime ?: throw JsChallengeProviderError("V8 runtime not initialized yet")
        try {
            return runtime.withLock {
                it.executeStringScript(stdin) ?: throw JsChallengeProviderError("V8 runtime error: empty response")
            }
        } catch (e: V8ScriptExecutionException) {
            if (e.message?.contains("Invalid or unexpected token") ?: false)
                ie.cache.clear(cacheSection) // cached data broken?
            throw JsChallengeProviderError("V8 runtime error: ${e.message}", e)
        }
    }

    private fun initRuntime() {
        if (v8Runtime != null)
            return
        v8Runtime = V8.createV8Runtime()
        runV8(constructCommonStdin()) // warm up AST and polyfill libraries once
    }

    private fun disposeRuntime() {
        val runtime = v8Runtime ?: return
        try {
            runtime.withLock {
                it.release(false)
            }
        } catch (_: Throwable) {}
        v8Runtime = null
    }

    fun warmUpAsync() {
        v8Executor.submit {
            try {
                initRuntime()
            } catch (e: Throwable) {
                disposeRuntime()
            }
        }
    }
}
