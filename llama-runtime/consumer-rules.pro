# R8 rules any app minifying this library gets (consumerProguardFiles).
#
# llama_jni.cpp calls back into Kotlin by NAME, three times:
#   env->GetMethodID(env->GetObjectClass(callback), "onToken", "(Ljava/lang/String;)V")
# Nothing on the Kotlin side calls onToken() itself, so R8 renames or removes
# it, and the first local generation dies with NoSuchMethodError (IntelliVerse
# release #160: "no non-static method Lod/e;.onToken").
-keep interface ai.localstudio.app.llama.LlamaBridge$TokenSink {
    void onToken(java.lang.String);
}
-keepclassmembers class * implements ai.localstudio.app.llama.LlamaBridge$TokenSink {
    void onToken(java.lang.String);
}

# The JNI symbols are named after the class and its native methods
# (Java_ai_localstudio_app_llama_LlamaBridge_native*): both keep their names,
# whatever an app's own rules say.
-keepclasseswithmembernames class ai.localstudio.app.llama.LlamaBridge {
    native <methods>;
}
