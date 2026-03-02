val versionName = "1.0.0"

val jdkVersion = 21

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(jdkVersion)
}

android {
    namespace = "com.caijunlin.vlcdecoder"
    version = versionName
    compileSdk {
        version = release(35)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            consumerProguardFiles("consumer-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jdkVersion)
        targetCompatibility = JavaVersion.toVersion(jdkVersion)
    }

    publishing {
        singleVariant("release") {
//            withSourcesJar()
        }
    }

    libraryVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (output != null) {
                output.outputFileName = "${android.namespace}_${versionName}.aar"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    api(libs.libvlc)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// 配置 Maven 发布
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.github.caijunlin"
            artifactId = "vlc-decoder"
            version = versionName
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

// 自动发布
tasks.register("createRelease") {
    group = "publishing"
    doLast {
        val checkProcess = ProcessBuilder("gh", "release", "view", versionName)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (checkProcess.waitFor() == 0) {
            println("Release [$versionName] exist")
            return@doLast
        }
        println("Release: $versionName ...")
        ProcessBuilder(
            "gh",
            "release",
            "create",
            versionName,
            "--title",
            versionName,
            "--generate-notes"
        )
            .inheritIO()
            .start()
            .waitFor()
        println("Success!")
    }
}