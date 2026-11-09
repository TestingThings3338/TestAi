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
    // ShipModelTest reads the converted Roblox ships from the app's assets (../app/src/main/assets)
    inputs.dir(rootProject.file("app/src/main/assets/ships")).withPathSensitivity(PathSensitivity.RELATIVE)
}
