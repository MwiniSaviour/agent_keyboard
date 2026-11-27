# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Agent Keyboard ---
# SQLCipher uses JNI callbacks into these classes.
-keep class net.zetetic.database.** { *; }
# AIDL stubs are referenced reflectively by the binder framework.
-keep class com.agentkeyboard.app.agent.IAgentService** { *; }
-keep class com.agentkeyboard.app.agent.IAgentCallback** { *; }
# Capacitor discovers plugins by annotation.
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
# sherpa-onnx's JNI reads its config classes' fields by name (GetFieldID): they must keep their names.
-keep class com.k2fsa.sherpa.onnx.** { *; }
