plugins {
    id("org.openapi.generator") version "7.14.0"
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "market"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set("$rootDir/src/main/resources/openapi/api.yaml")
    outputDir.set(layout.buildDirectory.dir("generated").get().asFile.path)
    apiPackage.set("market.api")
    modelPackage.set("market.model")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "skipDefaultInterface" to "true",
            "useSpringBoot3" to "true",
            "useBeanValidation" to "true",
            "useTags" to "true",
            "modelMutable" to "false",
            "enumPropertyNaming" to "original",
            "annotationLibrary" to "none",
            "documentationProvider" to "none",
            "exceptionHandler" to "false"
        )
    )
    typeMappings.set(mapOf("number+decimal" to "BigDecimal"))
    globalProperties.set(mapOf("apis" to "", "models" to ""))
}

kotlin.sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/src/main/kotlin"))
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

openApiValidate {
    inputSpec.set("$rootDir/src/main/resources/openapi/api.yaml")
}
