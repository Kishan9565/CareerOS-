plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.3.12"
    id("com.google.dagger.hilt.android") version "2.60.1"
}
android {
    namespace = "com.kishan.careeros"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kishan.careeros"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("schemas")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mediapipe:tasks-text:1.0.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.8.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.ktor:ktor-client-okhttp:3.3.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Propagate an explicitly configured build trust store to JVM tests (never to the APK).
tasks.withType<Test>().configureEach {
    listOf("javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    providers.gradleProperty("liveBoardsDir").orNull?.let { systemProperty("careeros.liveBoardsDir", it) }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
