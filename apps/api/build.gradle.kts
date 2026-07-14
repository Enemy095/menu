import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "com.salamu.menu"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val acceptance = sourceSets.create("acceptance") {
    java.srcDir("src/acceptance/java")
    resources.srcDir("src/acceptance/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[acceptance.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[acceptance.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation(platform(libs.aws.sdk.bom))
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(libs.aws.sdk.s3)
    implementation(libs.bucket4j.core)
    implementation(libs.caffeine)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.insight)

    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("user.timezone", "UTC")
}

tasks.named<BootJar>("bootJar") {
    archiveFileName = "menu-platform-api.jar"
}

val acceptanceBootJar = tasks.register<BootJar>("acceptanceBootJar") {
    group = "verification"
    description = "Builds a local-only API jar with deterministic no-network acceptance support."
    archiveFileName = "menu-platform-api-acceptance.jar"
    mainClass = "com.salamu.menu.MenuPlatformApplication"
    targetJavaVersion = JavaVersion.VERSION_25
    classpath(acceptance.runtimeClasspath)
}

tasks.register<JavaExec>("verifyAcceptanceArtifactIsolation") {
    group = "verification"
    description = "Proves local acceptance support is absent from the production executable jar."
    dependsOn(tasks.named("bootJar"), acceptanceBootJar)
    classpath = acceptance.runtimeClasspath
    mainClass = "com.salamu.menu.acceptance.AcceptanceArtifactVerifier"
    args(
        layout.buildDirectory.file("libs/menu-platform-api.jar").get().asFile.absolutePath,
        layout.buildDirectory.file("libs/menu-platform-api-acceptance.jar").get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("runDataImport") {
    group = "application"
    description = "Validates or applies the pinned owner-approved Stambul dataset."
    workingDir(rootProject.projectDir)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.salamu.menu.dataimport.api.StambulDataImportCommand"
}
