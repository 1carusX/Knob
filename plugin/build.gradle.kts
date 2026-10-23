import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    // Kotlin 2.1.10 起支持 JDK 25 及更新的版本（AS 的 JBR 25 可用）
    // 版本号统一在仓库根目录 gradle/libs.versions.toml 管理，与 app 共用
    alias(libs.plugins.kotlin.jvm)
}

group = "cn.icarus.knob"
version = "1.0.0"

// Android SDK 目录，按顺序找：本工程或 app 工程 local.properties 的 sdk.dir
// （Android Studio 打开工程时自动写入）→ ANDROID_HOME → ANDROID_SDK_ROOT →
// 各系统默认安装位置。不写死任何本机路径，换电脑不用改这个文件。
val sdkDir: File = run {
    val fromLocal = listOf(file("local.properties"), file("../app/local.properties"))
        .filter { it.exists() }
        .map { f ->
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("sdk.dir")
        }
    val home = System.getProperty("user.home")
    val candidates = fromLocal.filterNotNull() + listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },
        "$home/Android/Sdk",
        "$home/Library/Android/sdk",
    )
    candidates.map(::File).firstOrNull { it.isDirectory }
        ?: throw GradleException("找不到 Android SDK，已尝试: $candidates。请在 local.properties 里写 sdk.dir=<SDK 路径> 或设置 ANDROID_HOME")
}

// compileSdk / buildTools 版本与 app 共用（仓库根目录 gradle/libs.versions.toml）
val compileSdk = libs.versions.compileSdk.get()
// dex 没有 manifest，运行时沿用壳的 minSdk；d8 按同一个值做脱糖（--min-api）。
val minSdk = libs.versions.minSdk.get()
val buildTools = libs.versions.buildTools.get()

// 编译依赖的 android.jar（仅编译用，不打进 dex）
val androidJar = File(sdkDir, "platforms/android-$compileSdk/android.jar").also {
    if (!it.exists()) throw GradleException("缺少 $it，请用 SDK Manager 安装 API $compileSdk 的 SDK Platform")
}.absolutePath

// d8 工具路径（Windows 下是 d8.bat）
val d8Path = File(sdkDir, "build-tools/$buildTools/" +
    if (System.getProperty("os.name").startsWith("Windows")) "d8.bat" else "d8").also {
    if (!it.exists()) throw GradleException("缺少 $it，请用 SDK Manager 安装 Build-Tools $buildTools")
}.absolutePath

// 两份构建变体，共用 src/main/kotlin 里的全部插件代码，只有
// SIMULATE_CAR_CONTROL 这一个值不一样（各自一份 BuildFlags.kt，见
// src/main/flags-real 和 src/main/flags-simulate）：
//   main（默认 source set）    + flags-real      → plugin.dex           真实车控
//   simulate（新增 source set）+ flags-simulate  → plugin-simulate.dex  本地模拟，不碰真车
// 不用手改 SIMULATE_CAR_CONTROL 的值来回切换重新编译，一次构建两份都出。
sourceSets {
    main {
        kotlin.srcDir("src/main/flags-real/kotlin")
    }
    create("simulate") {
        kotlin.srcDir("src/main/kotlin")
        kotlin.srcDir("src/main/flags-simulate/kotlin")
    }
}

dependencies {
    // android.jar：编译 Context 等 Android 类
    compileOnly(files(androidJar))
    // 车机厂商官方车控 SDK（仅编译用，运行时由车机系统提供）
    compileOnly(files("libs/bydauto-openapi.jar"))

    // simulate source set 是新建的，不会自动继承上面这两个 compileOnly——
    // 同样的编译期依赖再给它配一份。
    "simulateCompileOnly"(files(androidJar))
    "simulateCompileOnly"(files("libs/bydauto-openapi.jar"))
}

// 不使用强制 toolchain：让 Kotlin 直接用 Gradle 当前运行的 JDK 编译（本机环境）。
// Kotlin 2.1.10 支持 JDK 17/21/25+。
// 关键：java 和 kotlin 的 JVM-target 必须一致（否则 Gradle 报
// "Inconsistent JVM-target compatibility"）。这里都设为 17 —— 只是字节码目标，
// 不是编译 JDK 版本，所以无论 Gradle 用 JDK 21 还是 25 都能编译。
// 这两个设置是项目级默认值，main/simulate 两个 source set 的编译任务都适用。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 把 jar 转成 dex 的公共逻辑（main/simulate 两个变体共用，避免重复写
// 一遍 d8 调用 + JAVA_HOME/PATH 处理）。两个变体最终都落在同一个
// output/ 目录里（plugin.dex 和 plugin-simulate.dex 并排放），所以
// d8 自己的中间产物（它固定叫 classes.dex）不能直接写到 output/ 里，
// 也不能每次都把 output/ 整个删了重建——否则先跑完的那个变体的 dex
// 会被后跑的那个变体启动时删掉。d8 先写到各自独立的临时目录，
// 再把结果拷贝改名进共享的 output/ 目录。
fun runD8(jarFile: File, finalOutDir: File, dexFileName: String) {
    val tmpDir = file("build/d8-tmp-$dexFileName")
    tmpDir.deleteRecursively()
    tmpDir.mkdirs()

    val d8 = File(d8Path)
    if (!d8.exists()) {
        throw GradleException("d8 不存在: $d8Path，请检查 ANDROID_HOME")
    }
    val cmd = listOf(
        d8.absolutePath,
        "--output", tmpDir.absolutePath,
        "--lib", androidJar,
        "--min-api", minSdk,
        jarFile.absolutePath
    )
    // d8 是 shell 脚本，靠 JAVA_HOME/PATH 找 java。
    // 这里用 Gradle 当前运行 JVM 的 java，不写死任何 JDK 路径，完全跟随本机环境。
    val javaHome = System.getProperty("java.home") // 例如 .../jbr 或 .../jdk-21
    val javaBin = File(javaHome, "bin").absolutePath
    val pb = ProcessBuilder(cmd)
    val env = pb.environment()
    env["JAVA_HOME"] = javaHome
    env["PATH"] = "$javaBin${File.pathSeparator}${env["PATH"] ?: ""}"
    pb.redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    val exit = process.waitFor()
    if (exit != 0) {
        throw GradleException("d8 转换失败:\n$output")
    }

    val classesDex = File(tmpDir, "classes.dex")
    if (!classesDex.exists()) {
        throw GradleException("未找到 classes.dex")
    }
    finalOutDir.mkdirs()
    classesDex.copyTo(File(finalOutDir, dexFileName), overwrite = true)
    println("✅ $dexFileName 生成: ${File(finalOutDir, dexFileName).absolutePath}")
}

// ==================== 真实车控变体：main → plugin.dex ====================

tasks.register<Jar>("pluginJar") {
    archiveFileName.set("plugin.jar")
    from(sourceSets["main"].output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn("compileKotlin")
}

tasks.register("pluginDex") {
    group = "knob"
    description = "编译插件（真实车控）并转成 plugin.dex"
    dependsOn("pluginJar")
    doLast {
        runD8(file("build/libs/plugin.jar"), file("output"), "plugin.dex")
    }
}

// ==================== 模拟变体：simulate → plugin-simulate.dex ====================

tasks.register<Jar>("pluginJarSimulate") {
    archiveFileName.set("plugin-simulate.jar")
    from(sourceSets["simulate"].output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn("compileSimulateKotlin")
}

tasks.register("pluginDexSimulate") {
    group = "knob"
    description = "编译插件（本地模拟，不碰真车）并转成 plugin-simulate.dex"
    dependsOn("pluginJarSimulate")
    doLast {
        runD8(file("build/libs/plugin-simulate.jar"), file("output"), "plugin-simulate.dex")
    }
}

// ==================== 插件 + 固件打成一个 zip（给旋钮 OTA 用） ====================
//
// 壳的「选择插件」导入后原样存成 filesDir/knob/plugin.dex，ART 按文件头认出
// zip、照样加载里面的 classes.dex；插件再自己用 ZipFile 读 firmware/ 下的
// 固件，连上旋钮后版本不一致就 OTA。zip 结构：
//   classes.dex              插件本体（d8 原样产物）
//   firmware/firmware.bin    PlatformIO 编出的固件，原样不动
//   firmware/manifest.json   {"version","board","size","sha256"}
// 产物：output/knob-plugin[-simulate]-<板子>-<固件版本>.zip
//
// 每块板子一个任务，任务名后缀就是板子，要哪块编哪块（打包前先调 PlatformIO
// 编一遍这块板子的固件，保证带的是当前代码）：
//   pluginPackagePcb / pluginPackagePcbV2 / pluginPackageDevkit
//   pluginPackageSimulatePcb / pluginPackageSimulatePcbV2 / pluginPackageSimulateDevkit
// 要几块就在一条命令里列几个任务。参数：
//   -Ppio=<路径>              pio 可执行文件（默认先找 PATH，再找 ~/.platformio/penv）

// 跟 firmware/platformio.ini 里主程序的 env 名一一对应
val knobBoards = listOf("pcb", "pcb_v2", "devkit")

// 只在打包任务真正执行时才找 pio，没装 PlatformIO 的机器照样能跑 pluginDex。
fun findPio(): File {
    (findProperty("pio") as String?)?.let { return File(it) }
    val windows = System.getProperty("os.name").startsWith("Windows")
    val names = if (windows) listOf("pio.exe", "platformio.exe") else listOf("pio", "platformio")
    val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .flatMap { dir -> names.map { File(dir, it) } }
    val home = System.getProperty("user.home")
    val penv = names.map { File("$home/.platformio/penv/" + (if (windows) "Scripts/" else "bin/") + it) }
    return (onPath + penv).firstOrNull { it.isFile && it.canExecute() }
        ?: throw GradleException("找不到 PlatformIO（pio），已尝试 PATH 和 ~/.platformio/penv。可用 -Ppio=<路径> 指定")
}

fun buildFirmware(board: String): File {
    val firmwareDir = file("../firmware")
    val pb = ProcessBuilder(findPio().absolutePath, "run", "-e", board).directory(firmwareDir)
    pb.redirectErrorStream(true)
    val process = pb.start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    if (process.waitFor() != 0) {
        throw GradleException("固件编译失败（pio run -e $board）:\n$output")
    }
    return File(firmwareDir, ".pio/build/$board")
}

fun packagePlugin(dexFile: File, zipName: String, board: String) {
    val fwBuildDir = buildFirmware(board)
    val bin = File(fwBuildDir, "firmware.bin")
    val version = File(fwBuildDir, "fw_version.txt").readText().trim()
    val binBytes = bin.readBytes()
    val sha256 = MessageDigest.getInstance("SHA-256").digest(binBytes)
        .joinToString("") { "%02x".format(it) }
    val manifest = """{"version":"$version","board":"$board","size":${binBytes.size},"sha256":"$sha256"}"""

    val outDir = file("output")
    // 文件名带板子和版本号（两块板子的固件版本号一样，不带板子会互相覆盖、
    // 拷 U 盘时也分不清）。同一变体、同一板子的旧包先删掉，免得越堆越多拷错包；
    // 板子名后面必须紧跟版本号数字，不然 knob-plugin 会误删 knob-plugin-simulate-*。
    val prefix = "$zipName-$board-"
    val oldPackage = Regex("^${Regex.escape(prefix)}\\d.*\\.zip$")
    outDir.listFiles { f -> oldPackage.matches(f.name) }?.forEach { it.delete() }
    val zipFile = File(outDir, "$prefix$version.zip")
    val entries = listOf(
        "classes.dex" to dexFile.readBytes(),
        "firmware/firmware.bin" to binBytes,
        "firmware/manifest.json" to manifest.toByteArray(),
    )
    ZipOutputStream(zipFile.outputStream()).use { out ->
        for ((name, bytes) in entries) {
            out.putNextEntry(ZipEntry(name))
            out.write(bytes)
            out.closeEntry()
        }
    }
    println("✅ ${zipFile.name} 生成: ${zipFile.absolutePath}")
    println("   固件 $version（$board），${binBytes.size} 字节，sha256=$sha256")
}

// pcb_v2 -> PcbV2
for (board in knobBoards) {
    val suffix = board.split("_").joinToString("") { it.replaceFirstChar(Char::uppercase) }
    tasks.register("pluginPackage$suffix") {
        group = "knob"
        description = "插件（真实车控）+ $board 固件打成 knob-plugin-$board-<固件版本>.zip"
        dependsOn("pluginDex")
        doLast { packagePlugin(file("output/plugin.dex"), "knob-plugin", board) }
    }
    tasks.register("pluginPackageSimulate$suffix") {
        group = "knob"
        description = "插件（本地模拟）+ $board 固件打成 knob-plugin-simulate-$board-<固件版本>.zip"
        dependsOn("pluginDexSimulate")
        doLast { packagePlugin(file("output/plugin-simulate.dex"), "knob-plugin-simulate", board) }
    }
}
