import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

// :native — Rust cdylib (native/rust) + Panama FFM bindings (aether.native.*).
plugins { `java-library` }

dependencies { api(project(":core")) }

// Host platform -> resource directory name. Must match aether.native.NativeLib.
val hostOs: String = System.getProperty("os.name").lowercase(Locale.ROOT).let {
    when {
        it.contains("win") -> "windows"
        it.contains("mac") || it.contains("darwin") -> "macos"
        else -> "linux"
    }
}
val hostArch: String = when (val a = System.getProperty("os.arch").lowercase(Locale.ROOT)) {
    "amd64", "x86_64", "x64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> a
}
val nativeLibName: String = when (hostOs) {
    "windows" -> "aether_native.dll"
    "macos" -> "libaether_native.dylib"
    else -> "libaether_native.so"
}
val rustDir = layout.projectDirectory.dir("rust")

// cargo: $CARGO, then $CARGO_HOME/bin or ~/.cargo/bin, then whatever is on PATH.
val cargoHome = File(System.getenv("CARGO_HOME") ?: (System.getProperty("user.home") + "/.cargo"))
val cargoBin = File(cargoHome, "bin")
val cargoExe: String = System.getenv("CARGO")
    ?: listOf("cargo.exe", "cargo").map { File(cargoBin, it) }.firstOrNull { it.isFile }?.absolutePath
    ?: "cargo"

val cargoBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds native/rust (aether_native) with `cargo build --release` and copies the library into build/libs/."
    workingDir = rustDir.asFile
    executable = cargoExe
    args("build", "--release")
    // Make the rustup proxies discoverable even when the daemon's PATH lacks ~/.cargo/bin.
    val pathKey = System.getenv().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    environment(pathKey, cargoBin.absolutePath + File.pathSeparator + (System.getenv(pathKey) ?: ""))

    inputs.files(fileTree(rustDir) { include("Cargo.toml", "Cargo.lock", "build.rs", "src/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("platform", "$hostOs-$hostArch")
    val built = rustDir.file("target/release/$nativeLibName").asFile
    val dest = layout.buildDirectory.file("libs/$nativeLibName")
    outputs.file(dest)
    doLast {
        val target = dest.get().asFile
        target.parentFile.mkdirs()
        Files.copy(built.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

// The library ships inside the module's resources as /native/<os>-<arch>/<lib>.
tasks.processResources {
    dependsOn(cargoBuild)
    from(cargoBuild) { into("native/$hostOs-$hostArch") }
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        // Keep the benchmark line visible under `gradle -q`.
        quiet {
            showStandardStreams = true
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
