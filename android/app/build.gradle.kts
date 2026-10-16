plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.pact.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pact.app"
        minSdk = 26
        targetSdk = 35
        // CI passes these; versionCode must grow for Android to accept an update.
        versionCode = System.getenv("PACT_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("PACT_VERSION_NAME")?.removePrefix("v") ?: "0.1.0-dev"
    }

    // Release signing comes from the environment (GitHub secrets in CI).
    // Without it, release builds are left unsigned.
    val keystore = System.getenv("PACT_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("PACT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PACT_KEY_ALIAS")
                keyPassword = System.getenv("PACT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // themes.json is shared with the desktop app; nlp_cases.json feeds the unit tests.
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../spec")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "plugin.properties")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jgit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
