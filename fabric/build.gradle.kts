plugins {
    `java-library`
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

// MC 26.2 requires Java 25; override the root project's Java 21 setting
extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

loom {
    mods {
        create("newspaper") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    // Mojang stopped obfuscating the game jar from 26.1+, so no mappings are needed.
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.157.0+26.2")
    implementation(project(":common"))
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand(project.properties)
    }
}
