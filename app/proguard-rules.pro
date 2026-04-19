# Keep our entry point + service
-keep class com.tripleu.mcon2codm.MainActivity { *; }
-keep class com.tripleu.mcon2codm.Mcon2CodmApp { *; }
-keep class com.tripleu.mcon2codm.BridgeService { *; }

# Reflection targets
-keep class rikka.shizuku.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class android.sun.security.** { *; }
-keep class org.conscrypt.** { *; }
-keep class com.android.org.conscrypt.** { *; }

# Shizuku ContentProvider binder
-keep class rikka.shizuku.ShizukuProvider { *; }

# Keep Compose runtime internals that R8 sometimes drops
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable *;
}

# Don't warn about missing JDK optionals / JSR305
-dontwarn javax.**
-dontwarn sun.**
-dontwarn java.lang.invoke.MethodHandle
-dontwarn org.bouncycastle.**

# Conscrypt internal/platform references that may not exist at compile time
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.conscrypt.**

# libadb-android uses reflection internally
-keepclassmembers class io.github.muntashirakon.adb.** { *; }
