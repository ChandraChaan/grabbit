# Keep the JavaScript bridge so the injected sniffer can call it.
-keepclassmembers class dev.chandra.grabbit.sniffer.SnifferBridge { public *; }
-keepattributes JavascriptInterface
# Crashlytics: readable stack traces
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
