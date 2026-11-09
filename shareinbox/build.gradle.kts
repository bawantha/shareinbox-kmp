import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish") version "0.37.0"
}

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "dev.shareinbox"
        compileSdk = 36
        minSdk = 26
        withHostTestBuilder {}.configure { isIncludeAndroidResources = true }
    }
    val xcframework = XCFramework("ShareInboxKit")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ShareInboxKit"
            isStatic = true
            linkerOpts("-application_extension")
            xcframework.add(this)
        }
    }
    sourceSets {
        androidMain { kotlin.srcDir("src/jvmMain/kotlin") }
        commonMain.dependencies {
            implementation("com.squareup.okio:okio:3.16.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("com.squareup.okio:okio-fakefilesystem:3.16.0")
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation("org.robolectric:robolectric:4.16.1")
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("ShareInbox KMP")
            description.set("Durable incoming shares for Kotlin Multiplatform Android and iOS apps.")
            url.set("https://github.com/bawantha/shareinbox-kmp")
            scm {
                url.set("https://github.com/bawantha/shareinbox-kmp")
                connection.set("scm:git:https://github.com/bawantha/shareinbox-kmp.git")
                developerConnection.set("scm:git:ssh://git@github.com/bawantha/shareinbox-kmp.git")
            }
            developers { developer {
                id.set("bawantha")
                name.set("bawantha")
                url.set("https://github.com/bawantha")
            } }
            licenses { license { name.set("MIT"); url.set("https://opensource.org/license/mit") } }
        }
    }
    repositories { maven { name = "localBuild"; url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI() } }
}

tasks.withType<Test>().configureEach {
    systemProperty("shareinbox.test.classpath", classpath.asPath)
}

// Local builds do not require credentials. Central releases always require signing.
if (providers.gradleProperty("releaseToCentral").orNull == "true") {
    mavenPublishing {
        publishToMavenCentral()
        signAllPublications()
    }
}
