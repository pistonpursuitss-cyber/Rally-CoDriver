plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val mapsApiKey = providers.gradleProperty("MAPS_API_KEY")
    .orElse(providers.environmentVariable("MAPS_API_KEY"))
    .getOrElse("MISSING_API_KEY")

android {
    namespace = "com.rallycodriver"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rallycodriver"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"${mapsApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.maps.android:maps-compose:8.4.0")
    implementation("androidx.core:core-ktx:1.16.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
