package com.otakustream.core.sources.scripting

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import javax.inject.Inject

class ScriptScope internal constructor(internal val scriptable: ScriptableObject)

// Rhino must run in pure-interpreted mode on Android (optimizationLevel = -1) — its default
// mode compiles scripts to JVM bytecode classes at runtime, which ART cannot load.
class ScriptEngine @Inject constructor(
    private val httpBridge: HttpBridge,
) {
    fun load(source: String, scriptName: String): ScriptScope {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()
            ScriptableObject.putProperty(scope, httpBridge.functionName, httpBridge)
            context.evaluateString(scope, source, scriptName, 1, null)
            return ScriptScope(scope)
        } finally {
            Context.exit()
        }
    }

    fun call(scope: ScriptScope, functionName: String, vararg args: Any?): String {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
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
}
