import org.gradle.api.tasks.testing.logging.TestExceptionFormat

dependencies { implementation(project(":core")); implementation(project(":native")) }

// The same tests with the native datapath required (-Dtessera.native=on: NativeUdpIo + Gf256Native kernel).
// `test` is untouched: it runs with the default `auto` selection.
val nativeTest by tasks.registering(Test::class) {
    description = "Runs the transport tests with -Dtessera.native=on (NativeUdpIo + native GF256 kernel)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("tessera.native", "on")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

/**
 * The wall-clock tests, run alone and serially. They measure real elapsed time across simulated links, so they are
 * meaningful only on a host that is not otherwise busy — run this task deliberately, not as part of a parallel build.
 */
val timingTest by tasks.registering(Test::class) {
    description = "Runs the real-time tests (tagged \"timing\") alone: paced streams, outage recovery."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // the tag filter is set centrally in the root build, where withType<Test> would otherwise cancel it
    maxParallelForks = 1
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
