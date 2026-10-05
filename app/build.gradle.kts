plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shehab360.videoplayerpro"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shehab360.videoplayerpro"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 চালু: কোড shrink + resource shrink।
            // Media3 ও AndroidX নিজেদের consumer keep rules সাথে নিয়ে আসে, তাই আলাদা rules লাগে না।
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)

    // Media3: ExoPlayer + PlayerView UI + MediaSession (background play / notification)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    // Network stream: HLS (.m3u8) ও DASH (.mpd)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
