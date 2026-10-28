# Keep the JavaScript bridge so the injected sniffer can call it.
-keepclassmembers class dev.chandra.grabbit.sniffer.SnifferBridge { public *; }
-keepattributes JavascriptInterface
# Crashlytics: readable stack traces
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# NewPipeExtractor + Rhino (JS signature deciphering uses reflection)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.jsoup.**
