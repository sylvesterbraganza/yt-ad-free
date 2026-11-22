// Top-level build file. Plugin versions live here; module config lives in app/build.gradle.kts.
plugins {
    // Android Gradle Plugin 9.x has Kotlin support built in, so no separate Kotlin plugin is needed.
    id("com.android.application") version "9.5.2" apply false
    // Declaring the Kotlin plugin here (not applied) pins the compiler version AGP's built-in Kotlin
    // uses. GeckoView pulls in the Kotlin 2.4 standard library, which needs a 2.4 compiler.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
