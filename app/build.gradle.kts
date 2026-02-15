plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(project(":utils"))
    implementation(libs.bundles.kotlinxEcosystem)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "eval.MainKt"
}
