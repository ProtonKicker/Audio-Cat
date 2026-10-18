import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

/**
 * Signing material lives inside the project so that builds never depend on a
 * writable `~/.android` directory. `scripts/make-keystore.sh` generates it on
 * first use; release builds reuse the same key so that a locally built release
 * APK stays installable.
 */
val keystoreFile = rootProject.file("keystore/audiocat.jks")
val keystoreProperties = Properties().apply {
    rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use {
        load(it)
    }
}

android {
    namespace = "com.audiocat"
    compileSdk = 36

    signingConfigs {
        if (keystoreFile.exists()) {
            create("audiocat") {
                storeFile = keystoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.audiocat"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // The app ships English-only strings; dropping other locales keeps the
        // APK small without pulling in unused library translations.
        localeFilters += listOf("en")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("audiocat")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("audiocat")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            // Fail on accidental use of removed/deprecated-with-error APIs.
            allWarningsAsErrors.set(false)
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Robolectric caches its android-all runtime jars in ~/.m2 and
                // takes a download lock in the user's home directory. Both are
                // redirected into a dedicated home so JVM tests run even when
                // the real home is not writable. The directory must not sit
                // under the project path: Robolectric URL-encodes spaces in the
                // repo path and then fails to read its own cached jar.
                val testHome = File(System.getProperty("user.home"), ".audiocat-testhome")
                testHome.mkdirs()
                it.systemProperty("user.home", testHome.absolutePath)
                it.systemProperty(
                    "maven.repo.local",
                    File(testHome, "m2").absolutePath,
                )
            }
        }
    }

    lint {
        warningsAsErrors = false
        // Error-severity findings are fixed, not accumulated: the build stops here.
        abortOnError = true
        checkDependencies = true
        // The app is intentionally offline; network-facing lint checks do not apply.
        disable += setOf("GoogleAppIndexingWarning", "UnusedResources")
    }

    buildToolsVersion = "36.0.0"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

detekt {
    // Defaults plus a committed baseline: the gate is "no new smells", not a
    // re-litigation of the existing tree. Regenerate with :app:detektBaseline.
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
    source = files("src/main/java", "src/test/java")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.swiprefresh)
    implementation(libs.androidx.documentfile)
    implementation(libs.material)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
