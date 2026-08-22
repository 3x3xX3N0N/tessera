plugins { kotlin("jvm") version "2.1.20" apply false }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    dependencies {
        "testImplementation"(kotlin("test"))
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
