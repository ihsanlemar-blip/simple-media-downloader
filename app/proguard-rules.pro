# NewPipeExtractor uses Rhino JavaScript engine for player deciphering
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# OkHttp optional platform security providers
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room 2.8 supplies its own consumer rule for RoomDatabase subclasses. Compose code is linked
# statically by the Compose compiler and does not require application-wide keep rules.

# Strip Java/Kotlin Logcat calls from minified release builds, including dependency logging.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# RegisterNatives uses this exact class and the four isolated JNI methods.
-keep class com.example.simplemediadownloader.Mp3EncoderBridge {
    private native <methods>;
}
