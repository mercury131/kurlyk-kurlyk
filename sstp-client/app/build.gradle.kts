plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.securegate.sstp"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.securegate.sstp"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // no release keystore in the repo: release builds are signed with the debug key
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

tasks.withType<Test>().configureEach {
  testLogging {
    events("passed", "failed", "skipped")
    showStandardStreams = true
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.security.crypto)
  testImplementation(libs.junit)
}
