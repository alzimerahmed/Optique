// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.hiltAndroid) apply false
    alias(libs.plugins.kspAndroid) apply false
    alias(libs.plugins.roomPlugin) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.baselineProfilePlugin) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.detekt) apply false
}

// --- Static analysis -------------------------------------------------------
// detekt runs per-module with a baseline at config/detekt/<module>-baseline.xml.
// Regenerate after intentional changes: ./gradlew :<module>:detektBaseline
// ktlint runs repo-wide via the CLI so it can honor a baseline at
// config/ktlint/baseline.xml. Regenerate: ./gradlew ktlintBaseline
// NOTE: ktlint baselines are line/column-anchored — any edit that shifts lines in a
// baselined file resurfaces its suppressed violations; re-run ktlintBaseline then.

subprojects {
    listOf("com.android.application", "com.android.library", "com.android.test").forEach { id ->
        pluginManager.withPlugin(id) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")
        }
    }
    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            baseline = rootProject.file("config/detekt/${project.name}-baseline.xml")
            parallel = true
        }
    }
}

val ktlintCli by configurations.creating {
    // Select the shaded fat-jar variant so the CLI is runnable via JavaExec.
    attributes {
        attribute(
            Attribute.of("org.gradle.dependency.bundling", String::class.java),
            "shadowed"
        )
    }
}

dependencies {
    ktlintCli(libs.ktlint.cli)
}

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Runs ktlint over all Kotlin sources, honoring config/ktlint/baseline.xml"
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    args(
        "--baseline=${rootDir}/config/ktlint/baseline.xml",
        "**/src/**/*.kt", "**/src/**/*.kts"
    )
}

val ktlintBaselineFile = layout.projectDirectory.file("config/ktlint/baseline.xml")

tasks.register<Delete>("ktlintBaselineClean") {
    delete(ktlintBaselineFile)
}

tasks.register<JavaExec>("ktlintBaseline") {
    group = "verification"
    description = "Regenerates config/ktlint/baseline.xml from the current sources"
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    dependsOn("ktlintBaselineClean")
    // ktlint exits non-zero when violations exist; that is expected while regenerating.
    isIgnoreExitValue = true
    doLast {
        check(ktlintBaselineFile.asFile.exists()) {
            "ktlint did not produce a baseline"
        }
    }
    args(
        "--baseline=${rootDir}/config/ktlint/baseline.xml",
        "**/src/**/*.kt", "**/src/**/*.kts"
    )
}
