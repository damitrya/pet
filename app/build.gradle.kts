plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.damitrya.mimio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.damitrya.mimio"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        // State logic tests run on the JVM; android.jar stubs return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.airbnb.android:lottie:6.3.0")
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    testImplementation("junit:junit:4.13.2")
}

// RuStore rejects APKs that request privileged permissions. Check the merged release manifest:
// it also carries the permissions of the libraries.
tasks.matching { it.name == "processReleaseMainManifest" }.configureEach {
    doLast {
        val forbidden = listOf("android.permission.MEDIA_CONTENT_CONTROL")
        outputs.files.asFileTree.matching { include("**/AndroidManifest.xml") }.forEach { manifest ->
            val text = manifest.readText()
            forbidden.filter { text.contains("\"$it\"") }.forEach {
                throw GradleException("$it must not be in the release manifest (${manifest.path})")
            }
        }
    }
}
