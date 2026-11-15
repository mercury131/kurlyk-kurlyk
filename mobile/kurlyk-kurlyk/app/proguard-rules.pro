-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# kotlinx.serialization
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** { *** Companion; }
-keepclasseswithmembers class com.example.** { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class io.getstream.webrtc.** { *; }
-dontwarn io.getstream.webrtc.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
