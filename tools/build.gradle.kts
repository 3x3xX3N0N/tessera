plugins { application }

dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
}

application {
    mainClass.set("tessera.tools.MainKt")
    applicationName = "tessera"
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED")
}
