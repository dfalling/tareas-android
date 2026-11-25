# kotlinx.serialization ships R8 consumer rules; these cover reflection edges.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# OkHttp probes optional TLS providers that are absent on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
