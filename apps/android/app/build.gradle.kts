plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hugo.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hugo.player"
        minSdk = 24
        targetSdk = 35
        versionCode = 49
        versionName = "0.5.44"
        // Servidor da API; para testar contra outro: ./gradlew assembleDebug -PhugoApiUrl=http://10.0.2.2:8080
        val apiUrl = (project.findProperty("hugoApiUrl") as String?) ?: "https://api-hugo.streamflixvip.online"
        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../hugo-release.keystore")
            storePassword = "hugoplayer2026"
            keyAlias = "hugo"
            keyPassword = "hugoplayer2026"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.kotlinx.coroutines.android)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
