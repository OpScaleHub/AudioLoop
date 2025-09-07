plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.audioloop.audioloop" // Assuming this is your namespace
    compileSdk = 34

    defaultConfig {
        applicationId = "com.audioloop.audioloop"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8 // Or JavaVersion.VERSION_17 if you configure your project for it
        targetCompatibility = JavaVersion.VERSION_1_8 // Or JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "1.8" // Or "17"
    }
    buildFeatures {
        viewBinding = true // If you use ViewBinding
        // compose = true // If you use Jetpack Compose
    }
    // packagingOptions { // Only if you have issues with duplicate files from libraries
    //     resources {
    //         excludes += "/META-INF/{AL2.0,LGPL2.1}"
    //     }
    // }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4") // If you use ConstraintLayout

    // Hilt for Dependency Injection
    implementation("com.google.dagger:hilt-android:2.51")
    kapt("com.google.dagger:hilt-compiler:2.51")

    // AndroidX Lifecycle (ViewModel, LiveData) - useful for Foreground Services
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")


    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

kapt {
    correctErrorTypes = true
    // These arguments are often needed for Kapt with JDK 9+
    // They might be less necessary if Gradle is strictly using JDK 17 and AGP/Kotlin plugins are up-to-date
    // but keeping them is safer for Kapt's known issues.
    javacOptions {
        option("--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
        option("--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
        option("--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED")
        option("--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED")
        option("--add-opens=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED")
        option("--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
        option("--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED")
        option("--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED")
        option("--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED")
    }
}
