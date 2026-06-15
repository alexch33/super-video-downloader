import com.android.build.api.variant.FilterConfiguration
import org.gradle.kotlin.dsl.support.serviceOf
import java.util.Properties
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.coveralls)
    kotlin("kapt")
    id("jacoco")
}

// =========================================================================
// KOTLIN & DEPENDENCY CONFIGURATIONS
// =========================================================================

configurations.configureEach {
    resolutionStrategy {
        force(libs.kotlin.stdlib)
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

allOpen {
    annotation("com.myAllVideoBrowser.OpenForTesting")
}

jacoco {
    version = "0.8.1"
}

// =========================================================================
// BUILD CONFIGURATION VARIABLES
// =========================================================================

val splitApks = System.getenv("SPLITS_INCLUDE")?.toBoolean() ?: true
val abiFilterList = (project.findProperty("ABI_FILTERS") as? String ?: "").split(';')
val abiCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4
)

// =========================================================================
// ANDROID CONFIGURATION
// =========================================================================

android {
    namespace = "com.myAllVideoBrowser"
    compileSdk = libs.versions.targetSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    // Compile Options
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    // Dependencies Info
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Packaging Options
    packaging {
        resources {
            excludes += listOf(
                "mozilla/public-suffix-list.txt",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libffmpeg.zip.so",
                "**/libpython.zip.so",
                "**/libffmpeg.so",
                "**/libffprobe.so",
                "**/libgojni.so",
                "**/libpython.so",
                "**/libqjs.so"
            )
        }
    }

    // Signing Configurations
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    // Default Config
    defaultConfig {
        applicationId = "com.myAllVideoBrowser"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 322
        versionName = "0.8.20.8"

        if (splitApks) {
            splits {
                abi {
                    isEnable = true
                    reset()
                    include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
                    isUniversalApk = true
                }
            }
        } else {
            ndk {
                abiFilters.addAll(abiFilterList)
            }
        }
    }

    // Build Types
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
        }
        release {
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Data Binding & Build Features
    dataBinding {
        enable = true
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Test Options
    testOptions {
        unitTests.all {
            it.exclude("**/*")
        }
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // Android Components - Version Code Adjustment
    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                val name = if (splitApks) {
                    output.filters.find {
                        it.filterType == FilterConfiguration.FilterType.ABI
                    }?.identifier
                } else {
                    abiFilterList.getOrNull(0)
                }

                val baseAbiCode = abiCodes[name]
                if (baseAbiCode != null) {
                    output.versionCode.set(baseAbiCode + (output.versionCode.get()))
                }
            }
        }
    }

    // Lint Options
    lint {
        abortOnError = false
    }

    // Source Sets
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

// =========================================================================
// DEPENDENCIES
// =========================================================================

dependencies {
    println("\n📦 Resolving Dependencies...")

    // Core Android Libraries
    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.webkit)
    implementation(libs.coreKtx)
    implementation(libs.coreSplashscreen)
    implementation(libs.legacySupportV4)

    // should fix ssl crashes on old devices
    implementation(libs.conscrypt.android)

    // Kotlin
    implementation(libs.kotlin.stdlib)

    // Coroutines & Work Manager
    implementation(libs.workRuntimeKtx)
    implementation(libs.workRxjava3)
    implementation(libs.workMultiprocess)
    implementation(libs.fragmentKtx)
    implementation(libs.concurrentFuturesKtx)

    // Lifecycle Components
    implementation(libs.lifecycleExtensions)
    implementation(libs.lifecycleCommonJava8)
    implementation(libs.lifecycleLivedata)
    implementation(libs.lifecycleViewmodel)

    // Room Database
    implementation(libs.roomRuntime)
    implementation(libs.roomKtx)
    implementation(libs.roomRxjava3)
    implementation(libs.roomGuava)
    ksp(libs.roomCompiler)

    // Key value DB
    implementation(libs.mmkv)

    implementation(libs.kotlinx.coroutines.rx3)

    // Dagger 2 - Dependency Injection
    implementation(libs.daggerRuntime)
    implementation(libs.daggerAndroid)
    implementation(libs.daggerAndroidSupport)
    ksp(libs.daggerCompiler)
    ksp(libs.daggerAndroidProcessor)

    // Network - OkHttp & Retrofit
    implementation(libs.okHttpRuntime)
    implementation(libs.okHttpLogging)
    implementation(libs.retrofitRuntime)
    implementation(libs.retrofitGson)
    implementation(libs.retrofitRxjava3)
    implementation(libs.persistentCookieJar)

    // RxJava 3
    implementation(libs.rxjava3)
    implementation(libs.rxandroid3)

    // Media & Video Processing
    implementation(libs.youtubedl)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.ffmpegKit)
    implementation(libs.media3Exoplayer)
    implementation(libs.media3ExoplayerDash)
    implementation(libs.media3ExoplayerHls)
    implementation(libs.media3ExoplayerRtsp)
    implementation(libs.media3Ui)
    implementation(libs.media3Extractor)
    implementation(libs.media3Database)
    implementation(libs.media3Decoder)
    implementation(libs.media3Datasource)
    implementation(libs.media3Common)
    implementation(libs.media3DatasourceOkhttp)

    // Image Loading
    implementation(libs.glideRuntime)

    // Utilities
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.kotlinxSerializationCore)
    implementation(libs.jsoup)
    implementation(libs.timeago)

    // Desugar for Java 8+ APIs
    coreLibraryDesugaring(libs.desugarJdk)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockitoCore)
    testImplementation(libs.mockitoKotlin)
    androidTestImplementation(libs.testRunner)
    androidTestImplementation(libs.mockitoAndroid)
    androidTestImplementation(libs.espressoCore)
    androidTestImplementation(libs.espressoIntents)

    println("✓ Dependencies resolved\n")
}

// =========================================================================
// KSP CONFIGURATION
// =========================================================================

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

// =========================================================================
// COVERALLS CONFIGURATION
// =========================================================================

tasks.named("coveralls") {
    dependsOn("check")
    onlyIf { System.getenv("COVERALLS_REPO_TOKEN") != null }
}

// =========================================================================
// RUST ADBLOCK BUILD SETUP (Multi-Architecture)
// =========================================================================
val rustProjectDir = file("${project.rootDir}/rust_adblock")

val jniLibsDir = file("src/main/jniLibs")

val buildRustAdblock = tasks.register("buildRustAdblock") {
    group = "Go Build"
    description = "Compiles Rust Adblock library for all Android targets"

    doLast {
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val executableSuffix = if (isWindows) ".exe" else ""

        val toolchainPath = "${ndkPath}/toolchains/llvm/prebuilt/${ndkPrebuiltFolder}/bin"
        val apiLevel = "24"

        archConfigs.forEach { arch ->
            println("🦀 Compiling Rust for ${arch.abi}...")

            val linkerBinary = if (arch.abi == "armeabi-v7a") {
                "armv7a-linux-androideabi$apiLevel-clang$executableSuffix"
            } else {
                "${arch.target}$apiLevel-clang$executableSuffix"
            }

            val linkerPath = "$toolchainPath/$linkerBinary"

            if (!file(linkerPath).exists()) {
                throw GradleException("✗ Rust Linker not found at: $linkerPath")
            }

            execOps.exec {
                workingDir = rustProjectDir

                val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
                val cargoCmd: String
                val cargoBinDir: String?
                if (isWindows) {
                    cargoBinDir = "${System.getenv("USERPROFILE")}\\.cargo\\bin"
                    cargoCmd = "$cargoBinDir\\cargo.exe"
                } else {
                    val homeCargoBin = "${System.getenv("HOME")}/.cargo/bin"
                    cargoBinDir = if (file("$homeCargoBin/cargo").exists()) homeCargoBin else null
                    cargoCmd = if (cargoBinDir != null) "$cargoBinDir/cargo" else "cargo"
                }

                val pathSeparator = if (isWindows) ";" else ":"
                val currentPath = System.getenv("PATH") ?: ""
                val newPath = if (cargoBinDir != null) {
                    "$cargoBinDir$pathSeparator$toolchainPath$pathSeparator$currentPath"
                } else {
                    "$toolchainPath$pathSeparator$currentPath"
                }

                environment("PATH", newPath)

                val targetEnvVar =
                    "CARGO_TARGET_${arch.rustTarget.replace("-", "_").toUpperCase()}_LINKER"
                environment(targetEnvVar, linkerPath)

                environment("CC", linkerPath)
                environment("CXX", linkerPath.replace("clang", "clang++"))

                val rustFlags = listOf(
                    "-C link-arg=-z",
                    "-C link-arg=max-page-size=16384",
                    "--remap-path-prefix=${rustProjectDir.absolutePath}=/rust_build"
                ).joinToString(" ")

                environment("RUSTFLAGS", rustFlags)

                commandLine(
                    cargoCmd,
                    "build",
                    "--target",
                    arch.rustTarget,
                    "--release"
                )
            }

            // Copy the compiled .so to jniLibs
            val soName = "libadblock_rust_jni.so"
            val sourceSo = file("$rustProjectDir/target/${arch.rustTarget}/release/$soName")

            // src/main/jniLibs/arm64-v8a
            val abiDestDir = File(jniLibsDir, arch.abi)

            if (sourceSo.exists()) {
                abiDestDir.mkdirs()

                // Copy to src/main/jniLibs/[ABI]/libadblock_rust_jni.so
                sourceSo.copyTo(File(abiDestDir, soName), overwrite = true)
                println("✓ Copied ${arch.abi} Adblock binary to ${abiDestDir.absolutePath}")
            } else {
                throw GradleException("✗ Rust build failed to produce $soName for ${arch.abi}")
            }
        }
    }
}

// =========================================================================
// GO REPRODUCIBLE BUILD SETUP (Multi-Architecture)
// =========================================================================
val execOps = project.serviceOf<ExecOperations>()

// V2Ray Repository Configuration
val v2rayRepo = "https://github.com/2dust/AndroidLibXrayLite.git"
val v2rayCommit = "d783dc8ea75afa0ff8fc9dcd51a426a9a67f6a70"
val buildDirV2ray = file("${project.rootDir}/build/v2ray")

// Go Executable Detection
val goExecutable = run {
    val envOverride = System.getenv("GO_EXECUTABLE")
    if (envOverride != null && file(envOverride).exists()) return@run envOverride

    val propOverride = project.findProperty("GO_EXECUTABLE")?.toString()
    if (propOverride != null && file(propOverride).exists()) return@run propOverride

    val candidates = listOf(
        "/opt/go-bin/go",
        "/opt/homebrew/bin/go",
        "/usr/local/go/bin/go",
        "/usr/local/bin/go",
        "/usr/bin/go"
    )
    candidates.find { file(it).exists() } ?: "go"
}

// Git Executable Detection
val gitExecutable = if (file("/usr/bin/git").exists()) "/usr/bin/git" else "git"

// =========================================================================
// NDK PATH DETECTION & VALIDATION
// =========================================================================

fun findNdkPath(): String {
    val envVar = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
    if (!envVar.isNullOrEmpty()) {
        println("✓ Found NDK path in environment variable: $envVar")
        return envVar
    }

    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val properties = Properties()
        localPropertiesFile.inputStream().use { properties.load(it) }
        val propVar = properties.getProperty("ndk.dir")
        if (propVar != null) {
            println("✓ Found NDK path in local.properties: $propVar")
            return propVar
        }
    }

    throw GradleException(
        "✗ NDK path not found. Please define one of:\n" +
                "  1. Environment: ANDROID_NDK_HOME or ANDROID_NDK_ROOT\n" +
                "  2. Property: ndk.dir in local.properties"
    )
}

fun validateNdkPath(ndkPath: String): String {
    val prebuiltToolchainsDir = file("${ndkPath}/toolchains/llvm/prebuilt")

    if (!prebuiltToolchainsDir.exists()) {
        throw GradleException(
            "✗ NDK toolchains prebuilt directory not found at: ${prebuiltToolchainsDir}\n" +
                    "  Verify your NDK installation and configuration."
        )
    }

    val prebuiltChildren =
        prebuiltToolchainsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    if (prebuiltChildren.isEmpty()) {
        throw GradleException("✗ No prebuilt toolchain directory found under ${prebuiltToolchainsDir}")
    }

    val ndkPrebuiltFolder =
        (prebuiltChildren.find { it.name.contains("darwin") } ?: prebuiltChildren[0]).name
    println("✓ Using NDK prebuilt folder: $ndkPrebuiltFolder")
    return ndkPrebuiltFolder
}

val ndkPath = findNdkPath()
val ndkPrebuiltFolder = validateNdkPath(ndkPath)

// =========================================================================
// ARCHITECTURE CONFIGURATIONS
// =========================================================================

data class ArchConfig(
    val abi: String,
    val goArch: String,
    val target: String,
    val rustTarget: String
)

val archConfigs = listOf(
    ArchConfig("arm64-v8a", "arm64", "aarch64-linux-android", "aarch64-linux-android"),
    ArchConfig("armeabi-v7a", "arm", "armv7a-linux-androideabi", "armv7-linux-androideabi"),
    ArchConfig("x86_64", "amd64", "x86_64-linux-android", "x86_64-linux-android"),
    ArchConfig("x86", "386", "i686-linux-android", "i686-linux-android")
)

// =========================================================================
// GO BUILD HELPER FUNCTIONS
// =========================================================================

fun verifyGoExecutable(builderDir: File, executablePath: String) {
    try {
        execOps.exec {
            workingDir(builderDir)
            commandLine(executablePath, "version")
        }
    } catch (e: Throwable) {
        throw GradleException(
            "✗ Go executable not found or failed to run at: $executablePath\n" +
                    "  Install Go or set:\n" +
                    "  - Environment: GO_EXECUTABLE=/path/to/go\n" +
                    "  - Property: -PGO_EXECUTABLE=/path/to/go\n" +
                    "  Error: ${e.message}"
        )
    }
}

// =========================================================================
// GO BUILD TASKS
// =========================================================================

// verify the submodule and vendor exist
tasks.register<DefaultTask>("verifyOfflineSources") {
    group = "Go Setup"
    val v2raySrc = file("${project.rootDir}/v2ray-src")
    val vendorDir = file("${project.rootDir}/app/src/main/go/builder/vendor")

    doLast {
        if (!v2raySrc.exists() || v2raySrc.listFiles()?.isEmpty() == true) {
            throw GradleException("✗ V2Ray submodule missing. Run: git submodule update --init --recursive")
        }
        if (!vendorDir.exists()) {
            throw GradleException("✗ Vendor directory missing. Run 'go mod vendor' locally and commit it.")
        }
        println("✓ Offline sources verified for F-Droid compliance.")
    }
}

val prepareGoBuild = tasks.register("prepareGoBuild") {
    dependsOn(tasks.named("verifyOfflineSources"))
}

val copyAllGoSharedLibs = tasks.register("copyAllGoSharedLibs") {
    group = "Go Build"
    description = "Copies Go shared libraries for all architectures to jniLibs."
}

// =========================================================================
// GENERATE ARCHITECTURE-SPECIFIC BUILD & COPY TASKS
// =========================================================================

archConfigs.forEach { arch ->
    // Build task for current architecture
    val buildTask = tasks.register<Exec>("buildGoSharedLib_${arch.abi}") {
        dependsOn(prepareGoBuild)
        group = "Go Build"
        description = "Builds Go shared library (${arch.abi})"

        val builderDir = file("src/main/go/builder")
        val outputDir = file("${layout.buildDirectory.get().asFile}/generated/go_build/${arch.abi}")
        val outputSO = file("${outputDir}/libgojni.so")

        inputs.dir(builderDir)
        outputs.file(outputSO)
        workingDir(builderDir)

        val apiLevel = 21
        val toolchainPath = "${ndkPath}/toolchains/llvm/prebuilt/${ndkPrebuiltFolder}"
        val compiler = "${toolchainPath}/bin/${arch.target}${apiLevel}-clang"
        val sysroot = "${toolchainPath}/sysroot"

        environment("CGO_ENABLED", "1")
        environment("GOOS", "android")
        environment("GOARCH", arch.goArch)
        environment("CC", compiler)
        environment("CGO_CFLAGS", "--sysroot=${sysroot}")

        val v2rayLibPath = "${project.rootDir}/v2ray-src/libs"
        environment(
            "CGO_LDFLAGS",
            "--sysroot=${sysroot} -llog -Wl,-z,max-page-size=16384 -L$v2rayLibPath"
        )

        doFirst {
            println("\n>>> Building Go library for ${arch.abi}...")
            if (!file(compiler).exists()) {
                throw GradleException(
                    "✗ C compiler for ${arch.abi} not found at: $compiler\n" +
                            "  Verify NDK installation and configuration."
                )
            }
        }

        doLast {
            println("✓ Built ${arch.abi}")
        }

        commandLine(
            goExecutable, "build",
            "-mod=vendor",
            "-buildmode=c-shared",
            "-trimpath",
            "-ldflags", "-s -w -buildid=",
            "-o", outputSO.absolutePath,
            "."
        )
    }

    // Copy task for current architecture
    val copyTask = tasks.register<Copy>("copyGoSharedLib_${arch.abi}") {
        dependsOn(buildTask)
        group = "Go Build"
        description = "Copies Go library to jniLibs (${arch.abi})"

        from(buildTask.map { it.outputs.files })
        into("src/main/jniLibs/${arch.abi}")

        doFirst {
            val sourceFile = buildTask.get().outputs.files.singleFile
            if (!sourceFile.exists()) {
                throw InvalidUserDataException(
                    "✗ Go build failed: ${sourceFile.path} not created for ${arch.abi}"
                )
            }
            println(">>> Copying library to jniLibs (${arch.abi})...")
        }

        doLast {
            println("✓ Copied ${arch.abi}")
        }
    }

    // Add copy task to aggregator
    copyAllGoSharedLibs.configure {
        dependsOn(copyTask)
    }
}

// =========================================================================
// BUILD LIFECYCLE HOOKS
// =========================================================================

// Hook Go build into Android build lifecycle
project.afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(copyAllGoSharedLibs)
        dependsOn(buildRustAdblock)
    }

    // Add summary task for all Go builds
    tasks.register("buildAllGoLibraries") {
        group = "Go Build"
        description = "Builds and copies all Go shared libraries"
        dependsOn(copyAllGoSharedLibs)

        doLast {
            println("\n╔════════════════════════════════════════════════════════╗")
            println("║  ✓ ALL GO LIBRARIES BUILT SUCCESSFULLY                 ║")
            println("║  Ready for Android APK build                           ║")
            println("╚════════════════════════════════════════════════════════╝\n")
        }
    }
}
