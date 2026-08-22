import org.gradle.api.tasks.testing.logging.TestExceptionFormat

dependencies { implementation(project(":core")); implementation(project(":native")) }

// The same tests with the native datapath required (-Daether.native=on: NativeUdpIo + Gf256Native kernel).
// `test` is untouched: it runs with the default `auto` selection.
val nativeTest by tasks.registering(Test::class) {
    description = "Runs the transport tests with -Daether.native=on (NativeUdpIo + native GF256 kernel)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("aether.native", "on")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
