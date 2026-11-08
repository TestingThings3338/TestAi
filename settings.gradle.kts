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
        mavenCentral()
    }
}

rootProject.name = "Starwake"

// :core    – engine + game (pure Kotlin/JVM, no Android dependencies, unit-testable)
// :app     – Android shell (Activity, GLSurfaceView, touch/gamepad, display/refresh-rate handling)
// :desktop – headless preview harness (LWJGL + Mesa EGL) that renders the real game to PNGs
include(":core", ":app", ":desktop")
