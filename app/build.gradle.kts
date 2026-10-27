import java.net.URI
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Public, build-time connection settings only. Never put a model Key in Gradle properties or BuildConfig.
val liveVerificationEnabled = when (providers.gradleProperty("yingji.ai.liveVerificationEnabled").orElse("false").get()) {
    "true" -> true
    "false" -> false
    else -> throw GradleException("yingji.ai.liveVerificationEnabled must be true or false")
}
val directVerificationEnabled = when (providers.gradleProperty("yingji.ai.directVerificationEnabled").orElse("false").get()) {
    "true" -> true
    "false" -> false
    else -> throw GradleException("yingji.ai.directVerificationEnabled must be true or false")
}
// Legacy Worker properties remain for the existing regression fixtures; app wiring no longer uses them.
val workerOriginInput = providers.gradleProperty("yingji.ai.workerOrigin").orElse("").get().trim()
val workerOrigin = if (workerOriginInput.isEmpty()) "" else try {
    val uri = URI(workerOriginInput)
    val host = uri.host?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException()
    require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") && uri.port in listOf(-1,443))
    require(host.endsWith(".workers.dev") && host.split('.').all { Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it) })
    "https://" + host
} catch (_: Exception) { throw GradleException("Worker origin must be a controlled HTTPS workers.dev origin without credentials, query or path") }
if (liveVerificationEnabled && workerOrigin.isEmpty()) throw GradleException("Live verification requires an explicitly configured Worker origin")

android {
    namespace = "com.yingji.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yingji.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "3.0.0"

        buildConfigField("boolean", "AI_DIRECT_VERIFICATION_ENABLED", directVerificationEnabled.toString())
        buildConfigField("String", "AI_WORKER_ORIGIN", "\"$workerOrigin\"")
        buildConfigField("boolean", "AI_LIVE_VERIFICATION_ENABLED", liveVerificationEnabled.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("com.vanniktech:android-image-cropper:4.7.0")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
