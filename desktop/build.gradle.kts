import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Headless preview / test harness: renders the real :core engine offscreen through Mesa's
// software rasterizer (EGL surfaceless platform + llvmpipe) and writes PNG screenshots.
//
//   ./gradlew :desktop:run --args="--scenario smoke"
//   ./gradlew :desktop:run --args="--scenario all --size 2400x1080"
//   ./gradlew :desktop:perf                  (perf scenario with -XX:-DoEscapeAnalysis)
//   ./gradlew :desktop:run -Pnoea --args="--scenario perf"   (same, via the run task)

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val lwjglVersion = "3.3.6"
val lwjglNatives = "natives-linux"

dependencies {
    implementation(project(":core"))

    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-egl:$lwjglVersion") // Java-only, binds the system libEGL
    implementation("org.lwjgl:lwjgl-opengles:$lwjglVersion")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengles:$lwjglVersion:$lwjglNatives")
}

application {
    mainClass.set("com.starwake.desktop.MainKt")
}

/** Settings shared by every JavaExec task of the harness. */
fun JavaExec.harnessDefaults() {
    // asset paths (app/src/main/assets) and the default output dir (desktop/out) are repo-relative
    workingDir = rootProject.projectDir
    // force Mesa's software path even if a render node shows up in the container
    environment("EGL_PLATFORM", "surfaceless")
    environment("LIBGL_ALWAYS_SOFTWARE", "1")
    environment("GALLIUM_DRIVER", "llvmpipe")
    jvmArgs("-Xmx2g")
    standardInput = System.`in`
}

tasks.named<JavaExec>("run") {
    harnessDefaults()
    // -Pnoea: disable escape analysis so allocations that ART would make stay visible
    if (project.hasProperty("noea")) jvmArgs("-XX:-DoEscapeAnalysis")
}

tasks.register<JavaExec>("perf") {
    group = "application"
    description = "Runs the perf scenario (CPU ms per frame, bytes allocated per frame) with escape analysis disabled."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.starwake.desktop.MainKt")
    harnessDefaults()
    jvmArgs("-XX:-DoEscapeAnalysis")
    val extra = (project.findProperty("perfArgs") as String?)?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
    args(listOf("--scenario", "perf") + extra)
}
