plugins {
    id("kotlinx-serialization")
    id("org.openjfx.javafxplugin") version "0.0.8"
    id("kotlin")
    id("java")
    id("com.jfrog.bintray")
    `maven-publish`
}

javafx {
    version = "13.0.2"
    modules = listOf("javafx.controls")
    //mainClassName = "Application"
}


/*
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>() {
    manifest {
        attributes["Main-Class"] = "net.mamoe.mirai.console.graphical.MiraiGraphicalLoader"
    }
}
 */

repositories {
    maven(url = "https://dl.bintray.com/karlatemp/unsafe-accessor/")
}

dependencies {
    compileOnly("net.mamoe:mirai-core:${Versions.core}")
    implementation(project(":mirai-console"))

    api(group = "no.tornado", name = "tornadofx", version = "1.7.19")
    api(group = "com.jfoenix", name = "jfoenix", version = "9.0.8")
    implementation("io.github.karlatemp:unsafe-accessor:1.0.0")

    testApi(project(":mirai-console"))
    testApi(kotlinx("coroutines-core", Versions.coroutines))
    testApi(group = "org.yaml", name = "snakeyaml", version = "1.25")
    testApi("net.mamoe:mirai-core:${Versions.core}")
    testApi("net.mamoe:mirai-core-qqandroid:${Versions.core}")
}

kotlin {
    target.compilations.all {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
        }
    }
    sourceSets {
        all {
            languageSettings.enableLanguageFeature("InlineClasses")

            languageSettings.useExperimentalAnnotation("kotlin.Experimental")
            languageSettings.useExperimentalAnnotation("kotlin.RequiresOptIn")
            languageSettings.progressiveMode = true
            languageSettings.useExperimentalAnnotation("net.mamoe.mirai.utils.MiraiInternalAPI")
            languageSettings.useExperimentalAnnotation("net.mamoe.mirai.utils.MiraiExperimentalAPI")
            languageSettings.useExperimentalAnnotation("net.mamoe.mirai.console.util.ConsoleExperimentalApi")
            languageSettings.useExperimentalAnnotation("net.mamoe.mirai.console.ConsoleFrontEndImplementation")
            languageSettings.useExperimentalAnnotation("kotlin.ExperimentalUnsignedTypes")
            languageSettings.useExperimentalAnnotation("kotlin.experimental.ExperimentalTypeInference")
            languageSettings.useExperimentalAnnotation("kotlin.contracts.ExperimentalContracts")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

@Suppress("DEPRECATION")
val sourcesJar by tasks.registering(Jar::class) {
    classifier = "sources"
    from(sourceSets.main.get().allSource)
}

version = Versions.consoleGraphical

description = "Graphical frontend for mirai-console"

setupPublishing("mirai-console-graphical", bintrayPkgName = "mirai-console-graphical")
