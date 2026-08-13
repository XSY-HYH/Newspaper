plugins {
    // Shadow plugin must NOT be declared in root project for Minecraft 26.1+.
    // Root-level plugin declarations with旧版ASM依赖会固定ASM版本到整个项目,
    // 导致Loom无法处理Java 25的class文件 (CFV 96).
    // Shadow插件在paper子项目中单独声明即可。
}

allprojects {
    group = "com.newspaper"
    version = "2.0.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
    }
}

subprojects {
    if (project.name != "fabric" && project.name != "fabric-legacy") {
        plugins.withId("java") {
            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            }
            tasks.withType<JavaCompile> {
                options.encoding = "UTF-8"
                options.release.set(21)
            }
        }
    }
}
