# ============================================================================
# Silo Android — R8 / ProGuard keep rules, shared by :androidApp + :androidTvApp.
#
# This stack is reflection- and JNI-heavy, so naive R8 minification breaks it at
# RUNTIME (silently). The rules below pin every component that resolves types by
# name / reflection / JNI. Most third-party libs ship their own consumer rules
# (Compose, OkHttp, Okio, Room, Coil3, Media3 core, kotlinx-serialization core) —
# these add the Silo-specific keeps plus the few libraries that need explicit
# help or warning suppression.
#
# IMPORTANT: a successful minified BUILD does NOT prove runtime correctness under
# R8. After any dependency bump or rule change, smoke-test a minified install on
# device: cold boot + Media3 playback + MPV playback + LAN (TLS-PSK) pairing.
# ============================================================================

# --- Attributes: reflection, serialization, and readable crash stacks --------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin coroutines (internal volatile fields are touched reflectively) ----
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- kotlinx.serialization ----------------------------------------------------
# kotlinx-serialization-core ships the generic @Serializable rules; these pin the
# generated serializers + Companions for Silo's own models (all under
# com.continuum.app), which R8 cannot see are reached only reflectively from the
# JSON format.
-keep,includedescriptorclasses class com.continuum.app.**$$serializer { *; }
-keepclasseswithmembers,includedescriptorclasses class com.continuum.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.continuum.app.** {
    *** Companion;
}

# --- Koin DI ------------------------------------------------------------------
# Koin resolves dependencies by KClass. Explicit module definitions keep the
# referenced classes, but ViewModels are resolved purely by type, so keep their
# constructors (R8 can otherwise prune a param list it believes is unused).
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# --- Media3 / ExoPlayer FFmpeg extension renderer -----------------------------
# DefaultRenderersFactory + FfmpegAudioSupport load this renderer by its fully
# qualified name (Class.forName) — a reflection target that must survive verbatim.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**

# --- libmpv (JNI) -------------------------------------------------------------
# MPVLib is native methods plus native-invoked EventObserver callbacks. JNI is
# invisible to R8, so keep the class, its native methods, and the callback
# interface it dispatches through.
-keep class dev.jdtech.mpv.** { *; }
-keepclasseswithmembernames class dev.jdtech.mpv.** { native <methods>; }

# --- BouncyCastle (TLS-PSK LAN companion pairing) -----------------------------
# Providers and algorithms are looked up by name through the JCA SPI; a stripped
# provider class fails silently at handshake time. Keep the whole library — it's
# only on the pairing path, so the size cost is bounded.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# --- Room ---------------------------------------------------------------------
# Room's compiler emits most keeps; pin entities + the generated database impl
# constructor that Room instantiates reflectively.
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Ktor (OkHttp engine resolved via ServiceLoader) --------------------------
-keep class io.ktor.client.engine.okhttp.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# --- OkHttp / Okio (ship their own rules; silence optional platform refs) -----
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Coil3 / zxing (R8-friendly; silence optional desktop/J2SE references) -----
-dontwarn coil3.**
-dontwarn com.google.zxing.**

# --- Enums used reflectively (serialization, MPV/Media3 property tables) -------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
