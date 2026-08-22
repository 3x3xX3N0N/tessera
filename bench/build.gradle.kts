plugins { application }
dependencies {
    implementation(project(":core")); implementation(project(":transport"))
}
application { mainClass.set("aether.bench.MainKt"); applicationDefaultJvmArgs = listOf("-XX:+UseZGC","-XX:+ZGenerational") }
