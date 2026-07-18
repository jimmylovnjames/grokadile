# Keep line numbers for readable crash reports, hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization -----------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers,allowshrinking class * {
    @kotlinx.serialization.Serializable <methods>;
}
# Keep @Serializable models in our DTO package.
-keep,includedescriptorclasses class com.grokadile.data.remote.dto.** { *; }

# Retrofit / OkHttp ---------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation interface retrofit2.Call
-keep,allowobfuscation class retrofit2.Response

# Hilt / Dagger generated code is handled by the plugins; nothing extra needed.

# Room entities are referenced reflectively by generated code.
-keep class com.grokadile.data.local.entity.** { *; }
