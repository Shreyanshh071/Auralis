# Release installer shrinking. Only unused library code is removed (mostly the Material icons):
# no obfuscation or optimisation, and anything reached through reflection or native code is kept.
-dontobfuscate
-dontoptimize

# Auralis itself (small; JNA callbacks and data classes are reached reflectively).
-keep class com.auralis.music.** { *; }

# libVLC through VLCJ and JNA (native callbacks, structures).
-keep class uk.co.caprica.vlcj.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }

# YouTube extraction: NewPipe, its Rhino JavaScript engine, protobuf, jsoup, nanojson.
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.grack.nanojson.** { *; }

# Networking and JSON.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.json.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# Image loading and the Swing main dispatcher (found through ServiceLoader).
-keep class coil3.** { *; }
-keep class kotlinx.coroutines.swing.** { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Optional dependencies these libraries probe for at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn android.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.mozilla.javascript.**
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
