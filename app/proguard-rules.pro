# Compose / Kotlin 默认规则通常够用，这里只放必要的。

# 保留协程与 Flow 的元数据（Room / 序列化会用到）
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlinx.coroutines.**

# ---- kotlinx.serialization ----
#
# ★ 这一组是**必须**的，不是可选的：序列化器是编译期生成的伴生对象，
#   R8 不认识"通过反射/伴生对象找 serializer"这条路径，
#   默认会把它们当无用代码删掉 ⇒ release 包在运行时才炸
#   （debug 不压缩所以看不出问题，这是最典型的"只有 release 崩"）。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.chatmobile.**$$serializer { *; }
-keepclassmembers class com.chatmobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.chatmobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Room ----
#
# 生成的 *Impl 由 Room 在运行时按名字查找，不能被重命名。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- ONNX Runtime ----
#
# native 方法通过 JNI 按类名/方法名绑定，混淆后必然 UnsatisfiedLinkError。
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ---- OkHttp ----
#
# OkHttp 用反射读平台实现类（如 Conscrypt），这些类在部分 Android 版本缺失。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- DataStore / Protobuf ----
-dontwarn androidx.datastore.**
