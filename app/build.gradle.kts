plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(project(":utils"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.bundles.ktorClient)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "eval.MainKt"
}
