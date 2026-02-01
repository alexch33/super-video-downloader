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
        versionCode = 169
        versionName = "0.8.11"

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
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.webkit)
    implementation(libs.coreKtx)
    implementation(libs.coreSplashscreen)
    implementation(libs.legacySupportV4)

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
// GO REPRODUCIBLE BUILD SETUP (Multi-Architecture)
// =========================================================================
val execOps = project.serviceOf<ExecOperations>()

// V2Ray Repository Configuration
val v2rayRepo = "https://github.com/2dust/AndroidLibXrayLite.git"
val v2rayCommit = "93a711245dec705be8dd6aa6a47f8aafa7898c40"
val buildDirV2ray = file("${project.rootDir}/build/v2ray")

// Go Executable Detection
val goExecutable = run {
    val envOverride = System.getenv("GO_EXECUTABLE")
    if (envOverride != null && file(envOverride).exists()) return@run envOverride

    val propOverride = project.findProperty("GO_EXECUTABLE")?.toString()
    if (propOverride != null && file(propOverride).exists()) return@run propOverride

    val candidates = listOf(
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

    val prebuiltChildren = prebuiltToolchainsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
    if (prebuiltChildren.isEmpty()) {
        throw GradleException("✗ No prebuilt toolchain directory found under ${prebuiltToolchainsDir}")
    }

    val ndkPrebuiltFolder = (prebuiltChildren.find { it.name.contains("darwin") } ?: prebuiltChildren[0]).name
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
    val target: String
)

val archConfigs = listOf(
    ArchConfig("arm64-v8a", "arm64", "aarch64-linux-android"),
    ArchConfig("armeabi-v7a", "arm", "armv7a-linux-androideabi"),
    ArchConfig("x86_64", "amd64", "x86_64-linux-android"),
    ArchConfig("x86", "386", "i686-linux-android")
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
    } catch (e: Exception) {
        throw GradleException(
            "✗ Go executable not found or failed to run at: $executablePath\n" +
            "  Install Go or set:\n" +
            "  - Environment: GO_EXECUTABLE=/path/to/go\n" +
            "  - Property: -PGO_EXECUTABLE=/path/to/go\n" +
            "  Error: ${e.message}"
        )
    }
}

fun createGoModule(builderDir: File) {
    val goModFile = file("${builderDir}/go.mod")
    goModFile.writeText(
        """
        module builder
        go 1.25.0

        // This rule is copied from the AndroidLibXrayLite go.mod file.
        // It forces the entire build to use the one, correct version of gvisor.
        replace gvisor.dev/gvisor => gvisor.dev/gvisor v0.0.0-20250606001031-fa4c4dd86b43
        """.trimIndent()
    )
    file("${builderDir}/go.sum").delete()
}

fun vendorGoDependencies(builderDir: File, executablePath: String) {
    val goEnv = mapOf("GOPROXY" to "https://proxy.golang.org,direct")

    // Add the replace directive for our local clone
    execOps.exec {
        workingDir(builderDir)
        environment(goEnv)
        commandLine(
            executablePath, "mod", "edit",
            "-replace=github.com/2dust/AndroidLibXrayLite=../../../../../build/v2ray/src"
        )
    }

    // Tidy the module
    execOps.exec {
        workingDir(builderDir)
        environment(goEnv)
        commandLine(executablePath, "mod", "tidy")
    }

    // Create the vendor directory
    execOps.exec {
        workingDir(builderDir)
        environment(goEnv)
        commandLine(executablePath, "mod", "vendor")
    }
}

// =========================================================================
// GO BUILD TASKS
// =========================================================================

// Task 1: Clone V2Ray source code and checkout specific commit
tasks.register<DefaultTask>("cloneV2raySource") {
    group = "Go Setup"
    description = "Clones V2Ray source and checks out a specific commit."

    val srcDir = file("${buildDirV2ray}/src")
    outputs.dir(srcDir)
    onlyIf { !srcDir.exists() }

    doLast {
        println("\n╔════════════════════════════════════════════════════════╗")
        println("║  CLONING V2RAY REPOSITORY                              ║")
        println("╚════════════════════════════════════════════════════════╝")

        // Clone the default branch with a shallow history
        println("→ Cloning repository with depth=1...")
        execOps.exec {
            workingDir(project.rootDir)
            commandLine(gitExecutable, "clone", "--depth=1", v2rayRepo, srcDir.absolutePath)
        }

        // Fetch the specific commit from the origin
        println("→ Fetching specific commit: $v2rayCommit...")
        execOps.exec {
            workingDir(srcDir)
            commandLine(gitExecutable, "fetch", "origin", v2rayCommit)
        }

        // Checkout the fetched commit
        println("→ Checking out commit...")
        execOps.exec {
            workingDir(srcDir)
            commandLine(gitExecutable, "checkout", v2rayCommit)
        }
        println("✓ V2Ray repository ready\n")
    }
}

// Task 2: Prepare Go module and create vendor directory
tasks.register<DefaultTask>("vendorGoDependencies") {
    group = "Go Setup"
    description = "Initializes main go.mod and creates a vendor directory."

    val builderDir = file("src/main/go/builder")
    val vendorDir = file("${builderDir}/vendor")

    dependsOn(tasks.named("cloneV2raySource"))

    inputs.file("${builderDir}/builder.go")
    outputs.dir(vendorDir)

    doFirst {
        println("\n╔════════════════════════════════════════════════════════╗")
        println("║  VERIFYING GO ENVIRONMENT                              ║")
        println("╚════════════════════════════════════════════════════════╝")

        val overrideEnv = System.getenv("GO_EXECUTABLE")
        val overrideProp = project.findProperty("GO_EXECUTABLE")?.toString()
        val goExecCandidate = overrideEnv ?: overrideProp ?: goExecutable

        println("→ Verifying Go executable: $goExecCandidate...")
        verifyGoExecutable(builderDir, goExecCandidate)
        println("✓ Go executable verified\n")
    }

    doLast {
        println("\n╔════════════════════════════════════════════════════════╗")
        println("║  PREPARING GO DEPENDENCIES                             ║")
        println("╚════════════════════════════════════════════════════════╝")

        val builderDirectory = file("src/main/go/builder")
        println("→ Creating go.mod...")
        createGoModule(builderDirectory)

        println("→ Vendoring dependencies...")
        vendorGoDependencies(builderDirectory, goExecutable)
        println("✓ Go dependencies ready\n")
    }
}

// Task 3: Prepare Go build dependencies
val prepareGoBuild = tasks.register("prepareGoBuild") {
    group = "Go Setup"
    description = "Prepares all dependencies for Go library builds."
    dependsOn(tasks.named("vendorGoDependencies"))
}

// Task 4: Aggregate all architecture-specific copy tasks
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
        environment("CGO_LDFLAGS", "--sysroot=${sysroot} -llog -Wl,-z,max-page-size=16384")

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
