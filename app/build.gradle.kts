plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.muc.fluocolorquant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.muc.fluocolorquant"
        minSdk = 22
        targetSdk = 34
        versionCode = 4
        versionName = "2.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86")
            abiFilters.add("x86_64")
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
    
    packaging {
        resources {
            pickFirsts.add("META-INF/INDEX.LIST")
            pickFirsts.add("META-INF/io.netty.versions.properties")
            pickFirsts.add("META-INF/LICENSE")
            excludes.add("META-INF/LICENSE.txt")
            excludes.add("META-INF/NOTICE.txt")
            excludes.add("META-INF/DEPENDENCIES")
            
            // 解决本机库冲突
//            pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
//            pickFirsts.add("lib/armeabi-v7a/libc++_shared.so")
//            pickFirsts.add("lib/x86/libc++_shared.so")
//            pickFirsts.add("lib/x86_64/libc++_shared.so")
        }
        jniLibs {
            useLegacyPackaging = true
            // 添加以下行来解决 libc++_shared.so 冲突
            // 这会告诉 Gradle 优先选择它遇到的第一个 libc++_shared.so 文件
            pickFirsts.add("lib/*/libc++_shared.so")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.core.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    kapt(libs.hilt.compiler)
    implementation(libs.coil.compose)
    implementation("org.pytorch:pytorch_android_lite:1.13.1")
    implementation("org.pytorch:pytorch_android_torchvision_lite:1.13.1")
//    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.ucrop)
    implementation(libs.androidx.appcompat)
    
    // 使用com.github.quickbirdstudios:opencv-android依赖
    implementation("com.quickbirdstudios:opencv:4.5.3.0")

    // 添加Apache Commons Math3库 - 用于曲线拟合计算
    implementation("org.apache.commons:commons-math3:3.6.1")

    // 添加这一行来引入AndroidMath库
    implementation("com.github.gregcockroft:AndroidMath:ALPHA")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}