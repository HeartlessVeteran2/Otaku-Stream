package com.otakustream.core.sources.scripting

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import javax.inject.Inject

class ScriptScope internal constructor(internal val scriptable: ScriptableObject)

// Refuses to make any Java class visible to script.
//
// Scripts get exactly one capability — the `httpGet` global — and that is injected as a Rhino
// BaseFunction, not as a wrapped Java object. So no script has a legitimate reason to name a Java
// class, and the correct answer for every name is "no".
private object DenyAllClassShutter : ClassShutter {
    override fun visibleToScripts(fullClassName: String): Boolean = false
}

// Every Context used to run a source script is built here, so the sandbox settings can't be
// forgotten at a call site.
//
// The ClassShutter has to be installed via the factory rather than after Context.enter(): a Context
// accepts setClassShutter exactly once, and Context.enter() reuses the current thread's Context if
// one is already active, so a second call would throw. makeContext() runs once per Context, before
// any script can execute.
private object SandboxedContextFactory : ContextFactory() {
    override fun makeContext(): Context = super.makeContext().apply {
        // Rhino must run in pure-interpreted mode on Android — its default mode compiles scripts to
        // JVM bytecode classes at runtime, which ART cannot load. Set here rather than per-call
        // because the setter throws once a context is executing.
        optimizationLevel = -1
        setClassShutter(DenyAllClassShutter)
    }
}

class ScriptEngine @Inject constructor(
    private val httpBridge: HttpBridge,
) {
    fun load(source: String, scriptName: String): ScriptScope {
        val context = SandboxedContextFactory.enterContext()
        try {
            // initSafeStandardObjects, not initStandardObjects. The latter installs Rhino's Java
            // interop into the scope — `Packages`, `java`, `javax`, `org`, `com`, `net`,
            // `JavaAdapter` and `getClass` — which hands any installed source full reflection inside
            // the app process: read app-private files, reach an Application via
            // ActivityThread.currentApplication(), open the encrypted prefs and take the AniList
            // token, then exfiltrate it through the httpGet global it already has. The docs have
            // always promised no Java interop; this is what makes that true.
            //
            // Deliberately not sealed. Sealing protects shared standard objects from one script
            // poisoning another's prototypes, and every script already gets its own scope — so it
            // would buy nothing here while breaking legitimate polyfills.
            val scope = context.initSafeStandardObjects()
            // A fresh BaseFunction per scope — BaseFunction carries Rhino scope/prototype state
            // that must not be shared across scripts, unlike the stateless HttpBridge it delegates to.
            ScriptableObject.putProperty(scope, "httpGet", httpGetFunctionFor(httpBridge))
            context.evaluateString(scope, source, scriptName, 1, null)
            return ScriptScope(scope)
        } finally {
            Context.exit()
        }
    }

    fun call(scope: ScriptScope, functionName: String, vararg args: Any?): String {
        val context = SandboxedContextFactory.enterContext()
        try {
            val function = scope.scriptable.get(functionName, scope.scriptable) as? Function
                ?: error("Script does not define function '$functionName'")
            val result = function.call(context, scope.scriptable, scope.scriptable, args)
            return Context.toString(result)
        } finally {
            Context.exit()
        }
    }

    fun readString(scope: ScriptScope, globalName: String, default: String): String {
        val value = ScriptableObject.getProperty(scope.scriptable, globalName)
        return if (value == Scriptable.NOT_FOUND) default else Context.toString(value)
    }

    private fun httpGetFunctionFor(bridge: HttpBridge): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
            val urlArg = args?.getOrNull(0)
            if (urlArg == null || urlArg == Undefined.instance) {
                error("httpGet requires a url argument")
            }
            val url = Context.toString(urlArg)

            val headersArg = args.getOrNull(1)
            val headersJson = if (headersArg != null && headersArg != Undefined.instance) {
                Context.toString(headersArg)
            } else {
                null
            }

            return bridge.httpGet(url, headersJson)
        }
    }
}
