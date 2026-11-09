pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        maven { url = uri("../../build/repository") }
        mavenCentral()
    }
}
rootProject.name = "shareinbox-published-consumer"
