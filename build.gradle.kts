// Top-level build file. Per-module config lives in app/build.gradle.kts.
//
// Toolchain pinned to AGP 7.4.2 / Gradle 7.6.4 / Kotlin 1.9.24 — all JDK
// 11-compatible — rather than the AGP 8.x / Kotlin 2.x / JDK 17 stack this
// project started on. See docs/adr/0006-jdk11-toolchain-downgrade.md: this
// build machine's JDK 17+ cannot open an NIO Selector at all (a broken
// AF_UNIX loopback connect() at the Windows level, reproducible with zero
// Gradle code involved), which makes Gradle's daemon IPC unusable on any
// JDK 17+ regardless of Gradle/AGP version. AGP 8.0+ hard-requires JDK 17
// to run, so the only way to get a real, verified compile on this machine
// is to stay on the last JDK-11-compatible AGP line.
plugins {
    id("com.android.application") version "7.4.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
