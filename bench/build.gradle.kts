plugins { application }
dependencies {
    implementation(project(":core")); implementation(project(":transport"))
    // Bench-only comparator for `bench vs`: an independent Java QUIC implementation (spec-derived, not a
    // Google-lineage codebase). It is a measurement baseline, not a source of mechanisms — NOTICE records it.
    implementation("tech.kwik:kwik:0.10.1")
}
configurations.all {
    resolutionStrategy.dependencySubstitution {
        // kwik 0.10.1 declares siphash "2.0.0-auto-module", a version that does not exist on Maven Central;
        // the real artifact is 2.0.0 (the -auto-module rename never shipped there)
        substitute(module("io.whitfin:siphash:2.0.0-auto-module")).using(module("io.whitfin:siphash:2.0.0"))
    }
}
application { mainClass.set("tessera.bench.MainKt"); applicationDefaultJvmArgs = listOf("-XX:+UseZGC","-XX:+ZGenerational") }
