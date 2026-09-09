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
        // Ask Rhino to call observeInstructionCount every so often, which is the only way to take a
        // running script's execution back off it.
        instructionObserverThreshold = INSTRUCTION_OBSERVER_THRESHOLD
    }

    // Aborts a script that has been running too long.
    //
    // This is what stops one bad extension disabling its source for the life of the process. Every
    // ScriptedVideoSource entry point holds a mutex across a *blocking* Rhino call, and cancelling
    // the coroutine cannot interrupt one — so `withTimeoutOrNull` around the call returned on
    // schedule while the interpreter kept running, the lock was never released, and every later
    // search or episode resolve for that source blocked forever. The source reported "timed out"
    // on everything, permanently, and retrying could not help because the retry queued behind the
    // same lock.
    //
    // A wall-clock deadline rather than an instruction budget: what matters is how long the user
    // has been waiting, and instruction counts differ by orders of magnitude between a tight loop
    // and one doing real parsing work. Throwing unwinds the interpreter, which releases the mutex.
    override fun observeInstructionCount(context: Context, instructionCount: Int) {
        val deadline = context.getThreadLocal(DEADLINE_KEY) as? Long ?: return
        if (System.nanoTime() > deadline) {
            throw ScriptDeadlineError()
        }
    }
}

// Two types for one event, and the split is load-bearing.
//
// ScriptDeadlineError is what the instruction observer throws, and it extends Error because Rhino
// will not deliver an Error to a script's own `catch`. A RuntimeException is delivered, and that
// defeats the entire mechanism: a source written as
//
//     try { while (true) {} } catch (e) {}
//
// catches the timeout, resumes the loop, gets interrupted again at the next 10,000-instruction
// checkpoint, catches again, and never stops — with the mutex still held, which is the exact
// permanent wedge the deadline exists to prevent. Rhino's own ContextFactory documentation
// specifies an Error subclass here so the script "never gets control back through catch or
// finally". This was shipped as a RuntimeException and had to be corrected.
//
// ScriptTimeoutException is what callers see, and it stays a RuntimeException because the app's
// error handling is built on Exception: ScriptedSourceBootstrapper deliberately catches Exception
// rather than Throwable so a genuine VM error propagates instead of being filed as "this script was
// malformed". Letting an Error out of ScriptEngine would crash the app on a slow script during
// bootstrap — trading a wedged source for a crash.
//
// So the Error is confined to the span Rhino controls, and ScriptEngine converts at the boundary.
internal class ScriptDeadlineError : Error(
    "The source script took too long and was stopped.",
)

// A script that ran past its deadline. Distinct from a script that threw, because the two mean
// different things to the user: one source is broken, the other is stuck.
class ScriptTimeoutException(cause: Throwable? = null) : RuntimeException(
    "The source script took too long and was stopped.",
    cause,
)

// Roughly how often Rhino checks in. Small enough that a runaway loop is caught within
// milliseconds of the deadline, large enough that the check is not a measurable share of the work.
private const val INSTRUCTION_OBSERVER_THRESHOLD = 10_000

private const val DEADLINE_KEY = "otaku.script.deadline"

// How long any one script call may run. Generous: a catalog page can legitimately parse a large
// document. It exists to bound the pathological case, not to police slow-but-working sources.
//
// Kept strictly above HttpBridge's SCRIPT_CALL_TIMEOUT_SECONDS. The two are one mechanism: a script
// blocked on a socket runs no instructions, so the observer cannot see it, and the request timing
// out first is what hands control back for the observer to act on. If this ever drops to or below
// the call timeout, a stalled fetch stops being interruptible at all.
internal const val SCRIPT_DEADLINE_MS = 20_000L

class ScriptEngine @Inject constructor(
    private val httpBridge: HttpBridge,
) {
    // Overridable so the timeout tests don't have to spend the real deadline three times over to
    // prove a runaway script is stopped. Not injected: nothing in the app should be choosing a
    // different value, and a constructor default would put it in Hilt's graph for no reason.
    internal var deadlineMs: Long = SCRIPT_DEADLINE_MS

    fun load(source: String, scriptName: String): ScriptScope {
        val context = SandboxedContextFactory.enterContext()
        // Top-level script bodies get the same deadline: an extension whose *load* never returns
        // wedges the install, not just a call.
        context.putThreadLocal(DEADLINE_KEY, System.nanoTime() + deadlineMs * NANOS_PER_MS)
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
        } catch (deadline: ScriptDeadlineError) {
            // Converted here, at the edge of the span Rhino controls — see ScriptDeadlineError.
            // The Error is kept as the cause: its stack is the only record of where inside the
            // interpreter the script was stuck, which is the one useful thing in the log.
            throw ScriptTimeoutException(deadline)
        } finally {
            Context.exit()
        }
    }

    fun call(scope: ScriptScope, functionName: String, vararg args: Any?): String {
        val context = SandboxedContextFactory.enterContext()
        // Per-call, on the Context, because a Context is per-thread and one script call is what is
        // being bounded — not the engine's lifetime.
        context.putThreadLocal(DEADLINE_KEY, System.nanoTime() + deadlineMs * NANOS_PER_MS)
        try {
            val function = scope.scriptable.get(functionName, scope.scriptable) as? Function
                ?: error("Script does not define function '$functionName'")
            val result = function.call(context, scope.scriptable, scope.scriptable, args)
            return Context.toString(result)
        } catch (deadline: ScriptDeadlineError) {
            throw ScriptTimeoutException(deadline)
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

            // The deadline, checked here because this is where a runaway script actually spends its
            // time and the instruction observer cannot see it.
            //
            // The observer runs between *interpreter* instructions, and a native call is one
            // instruction however long it takes. So `while (true) { httpGet(url) }` advances the
            // counter only by the handful the loop back-edge costs — tens per iteration against a
            // 10,000 threshold — and performs several hundred fetches, each up to the call timeout,
            // before the observer is consulted even once. With ScriptedVideoSource's mutex held
            // throughout, that is hours, not the twenty seconds the deadline claims. HomeViewModel
            // records the same gap from the other end: "a script with no instruction budget still
            // holds its source's mutex forever… that is a separate change, in the engines."
            //
            // Checked before the request rather than after, so a loop stops issuing traffic the
            // moment it is over budget instead of one fetch later. What is left is bounded: a fetch
            // begun just inside the deadline still runs to its own call timeout, so a call can
            // overrun by that much and no more — seconds, against no bound at all.
            //
            // ScriptDeadlineError for the same reason the observer throws it: an Error is not
            // delivered to a script's own catch, so `try { while (true) { httpGet(u) } } catch (e)
            // {}` cannot swallow this and resume. ScriptEngine converts it at the boundary.
            val deadline = cx?.getThreadLocal(DEADLINE_KEY) as? Long
            if (deadline != null && System.nanoTime() > deadline) {
                throw ScriptDeadlineError()
            }

            // Rhino's interpreter unwinds with `throw (Error) throwable` for anything that is not a
            // RuntimeException, so a *checked* exception thrown by a host function escapes as a
            // ClassCastException rather than reaching the script. httpGet's most ordinary failure —
            // OkHttp's IOException for a host that is down — is exactly that, which meant a source
            // could not catch its own network errors: `try { httpGet(url) } catch (e) { ... }` never
            // ran its catch block. throwAsScriptRuntimeEx converts any throwable into the
            // RuntimeException Rhino expects, so the failure arrives as a normal script error.
            return try {
                bridge.httpGet(url, headersJson)
            } catch (e: Exception) {
                throw Context.throwAsScriptRuntimeEx(e)
            }
        }
    }
}

private const val NANOS_PER_MS = 1_000_000L
