# Aegis R8/ProGuard — F3-P0 (2026-10-14).
# R8 ya aplica las consumer rules de Retrofit/OkHttp/compose; aqui solo lo
# que R8 no puede inferir: Gson por reflexion + interfaces Retrofit + modelos.

# --- Gson: modelos serializados por reflexion (data/modelos/*, NativeModels,
#     Credentials, BootstrapNative, ProjectsStore, EventStream/OpenCodeApi) ---
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keep class com.aegis.hub.data.modelos.** { *; }
-keep class com.aegis.hub.data.NativeModelsKt { *; }
-keep class com.aegis.hub.data.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.aegis.hub.data.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# --- Retrofit/OkHttp: la interfaz del servicio + metodos anotados ---
-keep interface com.aegis.hub.data.OpenCodeApi { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- AndroidX / Compose: lo cubren sus consumer rules; solo silencio de warns ---
-dontwarn androidx.**
