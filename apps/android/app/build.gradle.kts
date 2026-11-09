import java.util.Properties

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
        versionCode = 69
        versionName = "0.5.64"
        // Servidor da API; para testar contra outro: ./gradlew assembleDebug -PhugoApiUrl=http://10.0.2.2:8080
        val apiUrl = (project.findProperty("hugoApiUrl") as String?) ?: "https://api-hugo.streamflixvip.online"
        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
        // false no APK do site / Fire TV; true só no build "play" (Google Play)
        buildConfigField("boolean", "PLAY_STORE", "false")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../hugo-release.keystore")
            storePassword = "hugoplayer2026"
            keyAlias = "hugo"
            keyPassword = "hugoplayer2026"
        }
        // Google Play: chave de UPLOAD (fora do repositório). Caminho do .properties em
        // -PhugoPlaySigning=/caminho/hugo-play-upload.properties (storeFile, storePassword, keyAlias, keyPassword).
        // Sem isso o build "play" sai sem assinatura (assina-se depois com jarsigner/apksigner).
        val playProps = (project.findProperty("hugoPlaySigning") as String?)?.let { file(it) }
        if (playProps != null && playProps.canRead()) {
            val p = Properties().apply { playProps.inputStream().use { load(it) } }
            create("playUpload") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
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
        // Build separado para a Google Play (./gradlew bundlePlay). Não muda debug/release (APK do site).
        // Sem compra, sem autoatualização por APK, sem entrada de link M3U; pacote próprio (com.hugo.player.play).
        create("play") {
            initWith(getByName("release"))
            applicationIdSuffix = ".play"
            versionNameSuffix = "-play"
            signingConfig = signingConfigs.findByName("playUpload")
            buildConfigField("boolean", "PLAY_STORE", "true")
            matchingFallbacks += listOf("release")
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

// versionCode próprio do build "play" (pacote com.hugo.player.play é separado na Play Store):
// sobe a cada AAB enviado ao Play Console; não mexe no versionCode do APK do site.
val playVersionCode = 70
androidComponents {
    onVariants(selector().withBuildType("play")) { v ->
        v.outputs.forEach { it.versionCode.set(playVersionCode) }
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
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    // DNS seguro (DoH Cloudflare/Google quando a operadora bloqueia o endereço)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
