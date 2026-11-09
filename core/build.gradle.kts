import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
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

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // -Pnoea: run without escape analysis, so the zero-allocation tests see what ART would allocate
    if (project.hasProperty("noea")) jvmArgs("-XX:-DoEscapeAnalysis")
    // ShipModelTest reads the converted Roblox ships from the app's assets (../app/src/main/assets)
    inputs.dir(rootProject.file("app/src/main/assets/ships")).withPathSensitivity(PathSensitivity.RELATIVE)
    // the station tests read the converted Roblox stations (tools/roblox/convert_stations.py) and the font
    inputs.dir(rootProject.file("app/src/main/assets/stations")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("app/src/main/assets/font")).withPathSensitivity(PathSensitivity.RELATIVE)
}
