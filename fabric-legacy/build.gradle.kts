plugins {
    `java-library`
    id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT"
}

extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

loom {
    mods {
        create("newspaper") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.1")
    mappings("net.fabricmc:yarn:1.21.1+build.3:v2")
    modImplementation("net.fabricmc:fabric-loader:0.16.0")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.102.0+1.21.1")
    implementation(project(":common"))
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand(project.properties)
    }
}
