import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    java
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.goforge"
version = "0.1.0-dev"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        plugin("com.redhat.devtools.lsp4ij", providers.gradleProperty("lsp4ijVersion").get())
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.11")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Compile against the Kotlin API supplied by platform 252; do not ship another stdlib.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

intellijPlatform {
    providers.gradleProperty("goforgeSandboxDirectory").orNull?.let {
        sandboxContainer.set(layout.projectDirectory.dir(it))
    }
    pluginConfiguration {
        id = "dev.goforge.ide"
        name = "GoForge"
        version = project.version.toString()
        ideaVersion { sinceBuild = "252"; untilBuild = "252.*" }
        vendor { name = "GoForge" }
    }
}

tasks.test {
    useJUnit()
    // This opt-in experiment reproduces a known upstream diagnostic refresh race.
    // Its assertions establish the defect mechanism, not product correctness.
    if (providers.gradleProperty("goforgeDiagnosticExperiment").orNull == "true") {
        include("**/LspDiagnosticsRefreshRaceTest.class")
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/diagnostic-experiment"))
        reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/diagnostic-experiment"))
    } else {
        exclude("**/LspDiagnosticsRefreshRaceTest.class")
    }
}

// Apply the pinned metadata fix only after Gradle has copied the dependency.
// The downloaded cache remains pristine; application assembly uses the same script.
tasks.withType<PrepareSandboxTask>().configureEach {
    val patchScript = layout.projectDirectory.file("scripts/patch-lsp4ij-metadata.py")
    val lifecycleScript = layout.projectDirectory.file("scripts/patch-lsp4ij-lifecycle.py")
    inputs.file(patchScript)
    inputs.file(lifecycleScript)
    inputs.dir(layout.projectDirectory.dir("patches/lsp4ij-0.21.0/lifecycle"))
    doLast {
        val dependency = sandboxPluginsDirectory.get().asFile.resolve("lsp4ij")
        val python = providers.environmentVariable("GOFORGE_PYTHON").orElse(
            if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        ).get()
        val result = providers.exec {
            isIgnoreExitValue = true
            commandLine(python, patchScript.asFile.absolutePath,
                "--plugin-dir", dependency.absolutePath)
        }
        logger.lifecycle(result.standardOutput.asText.get())
        val metadataError = result.standardError.asText.get()
        if (metadataError.isNotBlank()) logger.error(metadataError)
        result.result.get().assertNormalExitValue()
        val platformHome = sourceSets.main.get().compileClasspath.files.single {
            it.name == "app-client.jar"
        }.parentFile.parentFile
        val javac = javaToolchains.compilerFor(java.toolchain).get().executablePath.asFile
        val lifecycleResult = providers.exec {
            isIgnoreExitValue = true
            commandLine(python, lifecycleScript.asFile.absolutePath,
                "--plugin-dir", dependency.absolutePath,
                "--platform-home", platformHome.absolutePath, "--javac", javac.absolutePath)
        }
        logger.lifecycle(lifecycleResult.standardOutput.asText.get())
        val lifecycleError = lifecycleResult.standardError.asText.get()
        if (lifecycleError.isNotBlank()) logger.error(lifecycleError)
        lifecycleResult.result.get().assertNormalExitValue()
    }
}

// Explicit real-tool verification. Kept separate from deterministic product tests.
tasks.register<JavaExec>("verifyNavigationProtocol") {
    dependsOn(tasks.testClasses)
    // IntelliJ and LSP4IJ are provided dependencies, hence absent from runtimeClasspath.
    // A flat JavaExec classpath also contains IntelliJ's own LSP4J. Exercise the
    // exact two protocol JARs shipped with the pinned LSP4IJ dependency first.
    val provided = sourceSets.test.get().compileClasspath
    val protocol = provided.filter {
        it.name == "org.eclipse.lsp4j-1.0.0.jar" || it.name == "org.eclipse.lsp4j.jsonrpc-1.0.0.jar"
    }
    classpath = protocol + sourceSets.test.get().runtimeClasspath + provided
    mainClass.set("dev.goforge.navigation.GoplsNavigationProtocolProbe")
    workingDir(layout.projectDirectory)
}

tasks.register<JavaExec>("verifyCoverageProtocol") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.test.get().compileClasspath
    mainClass.set("dev.goforge.coverage.GoCoverageProtocolProbe")
    workingDir(layout.projectDirectory)
}

// Real gopls extract/inline previews and generated Go compilation, separate from JUnit totals.
tasks.register<JavaExec>("verifyRefactoringProtocol") {
    dependsOn(tasks.testClasses)
    val provided = sourceSets.test.get().compileClasspath
    val protocol = provided.filter {
        it.name == "org.eclipse.lsp4j-1.0.0.jar" || it.name == "org.eclipse.lsp4j.jsonrpc-1.0.0.jar"
    }
    classpath = protocol + sourceSets.test.get().runtimeClasspath + provided
    mainClass.set("dev.goforge.refactor.GoRefactorProtocolProbe")
    workingDir(layout.projectDirectory)
}
