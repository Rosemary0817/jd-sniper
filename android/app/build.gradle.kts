plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lcs.jdsniper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lcs.jdsniper"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

// 单一事实源：../config/app-config.json -> assets/app-config.json
val sharedConfigFile = rootProject.file("../config/app-config.json")

val copySharedConfig by tasks.registering(Copy::class) {
    from(sharedConfigFile)
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "app-config.json" }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(copySharedConfig)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
