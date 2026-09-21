pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Real, on-device-confirmed bug (docs/adr/0017-cedar-cross-compile.md's
        // "Guava variant selection" note): Guava's "33.4.0-jre" coordinate
        // publishes Gradle Module Metadata with an internal
        // `jreRuntimeElements`/`androidRuntimeElements` variant split, and
        // every AGP module's consumer attributes force-select the android
        // variant regardless of the "-jre" label or any resolutionStrategy
        // override — attribute overrides in `configurations.all` don't
        // stick because AGP re-applies its own attributes afterward.
        // `ignoreGradleMetadataRedirection()` makes Gradle resolve an
        // artifact from its plain POM only, which has no variant concept
        // at all — Guava's POM for "33.4.0-jre" points directly at the
        // actual jre-compiled jar, exactly like a Maven consumer (e.g.
        // cedar-java itself) sees it.
        //
        // Scoped to ONLY `com.google.guava:guava` via `content{}` content
        // filtering — applying this project-wide (the first attempt) broke
        // Kotlin Multiplatform's own use of Gradle Module Metadata to
        // redirect `kotlinx-coroutines-core` to its platform-specific
        // `-jvm` artifact, causing a real `mergeDebugJavaResource` failure
        // ("2 files found with path
        // 'META-INF/kotlinx_coroutines_core.version'") — so the normal,
        // metadata-aware `mavenCentral()` below still handles everything
        // else.
        mavenCentral {
            content { excludeModule("com.google.guava", "guava") }
        }
        mavenCentral {
            metadataSources {
                mavenPom()
                artifact()
                ignoreGradleMetadataRedirection()
            }
            content { includeModule("com.google.guava", "guava") }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SankatSetu"
include(":app")
