# Keep rules staged for when R8/minification is enabled (currently off in the release build type).
# The JS runtimes and Room/Media3 reach a lot of code reflectively, so it must survive shrinking.

# QuickJS (Mangayomi/AnymeX JS engine) — both the wrapper package and the native bridge classes.
-keep class com.whl.quickjs.** { *; }
-keep class wang.harlon.quickjs.** { *; }

# Mozilla Rhino (scripted sources engine).
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# org.json — used by every parser and reflected into by the JS host bridges.
-keep class org.json.** { *; }

# Room — generated DAOs/implementations and entity constructors.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Media3/ExoPlayer — renderers, extractors, and datasource factories are loaded reflectively.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Jsoup (Document bridge exposed to extensions).
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Kotlin metadata + coroutines internals that reflection-based libraries expect.
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# The app's OWN JS host-API / bridge classes. QuickJS (Mangayomi/AnymeX) and Rhino (scripted
# sources) call these Kotlin methods by name across the JS↔native boundary via reflection, so R8
# would otherwise rename or strip them and break every installed source at runtime. These are the
# keeps that make enabling minification safe — see docs/enabling-r8.md before flipping the switch.
-keep class com.otakustream.core.sources.scripting.** { *; }
-keep class com.otakustream.core.sources.mangayomi.runtime.** { *; }
# Mangayomi data/DTO types passed across the boundary (kept broad; trim if you profile the mapping).
-keep class com.otakustream.core.sources.mangayomi.model.** { *; }
