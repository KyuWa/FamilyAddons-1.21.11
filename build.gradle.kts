plugins {
    kotlin("jvm") version "2.3.20"
    id("fabric-loom") version "1.13-SNAPSHOT"
}

version = "1.0.0"
group = "org.kyowa"

base {
    archivesName = "FamilyAddons"
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://jitpack.io")
    maven { url = uri("https://maven.notenoughupdates.org/releases/") }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.4:v2")
    modImplementation("net.fabricmc:fabric-loader:0.18.1")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.2+1.21.11")
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.10+kotlin.2.3.20")
    modCompileOnly("com.terraformersmc:modmenu:13.0.0")
    modImplementation("org.notenoughupdates.moulconfig:modern-1.21.11:4.5.0")
    include("org.notenoughupdates.moulconfig:modern-1.21.11:4.5.0")
}

loom {
    accessWidenerPath = file("src/main/resources/familyaddons.accesswidener")
    mixin {
        defaultRefmapName.set("familyaddons.refmap.json")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.remapJar {
    archiveVersion = "1.21.11"
}
