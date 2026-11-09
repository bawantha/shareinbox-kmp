plugins { kotlin("multiplatform") version "2.4.0" }
kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { implementation("io.github.bawantha:shareinbox:0.1.0-alpha01") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
