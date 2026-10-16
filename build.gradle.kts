import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

group = "com.github.marcoral.versatia"
version = providers.gradleProperty("versatiaVersion").get()

// Paper API and the Kotlin standard library are inherited from VersatiaAPI; see docs/build.md
dependencies {
    implementation(libs.versatia.api)
    // VersatiaCore enhances itself, so its descriptor is generated like a plugin's; see docs/build.md
    ksp(libs.versatia.ksp)
    // The CommandAPI plugin is a soft dependency on the server; only its Kotlin DSL ships in this JAR
    compileOnly(libs.commandapi.paper.core)
    implementation(libs.commandapi.kotlin.paper)

    testImplementation(kotlin("test"))
    // Compiles sample plugins with the real KSP processor inside tests; see docs/runtime.md
    testImplementation(libs.versatia.ksp)
    testImplementation(libs.kctfork.ksp)
    // Tests fake a Bukkit Plugin, so the API must be on the test runtime classpath
    testImplementation(libs.paper.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Paper 1.21.4 requires Java 21
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // Framework code may use the plumbing marked @VersatiaInternalApi; see docs/api.md of VersatiaAPI
        optIn.add("com.github.marcoral.versatia.VersatiaInternalApi")
    }
}

tasks.test {
    useJUnitPlatform()
}

// Expands ${version} and ${kotlinVersion} in plugin.yml
tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "kotlinVersion" to getKotlinPluginVersion(),
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// VersatiaAPI classes and the CommandAPI Kotlin DSL ship inside this JAR; see docs/build.md
val archives = serviceOf<ArchiveOperations>()
val bundledClasses = configurations.runtimeClasspath.map { classpath ->
    classpath.filter { it.name.startsWith("VersatiaAPI-") || it.name.startsWith("commandapi-kotlin-") }.map { archives.zipTree(it) }
}

tasks.jar {
    from(bundledClasses)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.kotlin_module")
}

// Machine-specific settings; see docs/build.md
val localProperties = providers
    .fileContents(layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) } }

val pluginsDir: Provider<String> = providers.gradleProperty("pluginsDir")
    .orElse(localProperties.map { it.getProperty("pluginsDir") })

val deployPlugin = tasks.register<Copy>("deployPlugin") {
    group = "versatia"
    description = "Copies the plugin JAR to the server plugins/ directory (pluginsDir from local.properties)."
    // Local copy: the onlyIf lambda must not capture script fields (configuration cache)
    val targetDir = pluginsDir
    onlyIf("pluginsDir is not set - see local.properties.example") { targetDir.isPresent }
    from(tasks.jar)
    into(targetDir.orElse(layout.buildDirectory.dir("deploy").map { it.asFile.path }))
}

tasks.build {
    finalizedBy(deployPlugin)
}
