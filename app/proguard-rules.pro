# Obfuscate and flatten non-manifest packages to harden DEX against inspection
-repackageclasses 'r'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute P

# Preserve annotations required by Room and Jetpack Compose
-keepattributes *Annotation*

# Room database & entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep AndroidManifest entry components by name while obfuscating internal helpers
-keep public class com.example.RCProxyApplication
-keep public class com.example.MainActivity
-keep public class com.example.vpn.ProxyVpnService
-keep public class com.example.vpn.FloatingBubbleService
-keep public class com.example.vpn.VpnConsentActivity

# Silence third-party optional annotation warnings in OkHttp/Okio
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.**
-dontwarn okio.**
