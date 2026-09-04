# NewPipeExtractor uses Rhino JavaScript engine for player deciphering
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

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

