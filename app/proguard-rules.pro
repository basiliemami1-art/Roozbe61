# gomobile-generated bindings are reached reflectively from Go; never touch them.
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep class com.gozar.app.vpn.** { *; }

# Our classes implementing gomobile reverse-bound interfaces must keep their methods.
-keep class * implements io.nekohasekai.libbox.PlatformInterface { *; }
-keep class * implements io.nekohasekai.libbox.CommandServerHandler { *; }
-keep class * implements io.nekohasekai.libbox.LocalDNSTransport { *; }
-keep class * implements io.nekohasekai.libbox.StringIterator { *; }
-keep class * implements io.nekohasekai.libbox.NetworkInterfaceIterator { *; }
-keep class * implements libv2ray.V2RayVPNServiceSupportsSet { *; }

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# kotlinx.serialization
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
