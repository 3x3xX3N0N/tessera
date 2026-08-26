plugins { kotlin("jvm") version "2.1.20" apply false }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    dependencies {
        "testImplementation"(kotlin("test"))
    }
    // Real-time tests (paced streams, outage recovery) assert wall-clock behaviour and are unreliable when the
    // suite saturates the host. They are tagged "timing" and excluded here, then run alone by :transport:timingTest.
    // A suite that is habitually red teaches everyone to ignore red, which is how a genuine regression gets waved through.
    // withType applies to every Test task, including the timing one, so the include/exclude decision must be made
    // here or the two filters cancel and the task silently matches nothing.
    tasks.withType<Test> {
        useJUnitPlatform { if (name == "timingTest") includeTags("timing") else excludeTags("timing") }
        // The fuzz sweeps (core/FuzzTest, transport/EndpointFuzzTest) take their iteration count and seed from
        // system properties. The test JVM is forked and does not inherit the Gradle daemon's -D, so forward them
        // explicitly or `-Dtessera.fuzz.iterations=N` silently does nothing and a "large run" is the default run.
        for (k in listOf("tessera.fuzz.iterations", "tessera.fuzz.endpoint.iterations", "tessera.fuzz.seed"))
            System.getProperty(k)?.let { systemProperty(k, it) }
    }
}
