plugins { id("com.android.application") }
android {
    namespace = "dev.shareinbox.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.shareinbox.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(project(":shareinbox"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
