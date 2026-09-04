buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.11.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.20")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.1.20-2.0.1")
    }
}
