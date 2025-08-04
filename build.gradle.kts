plugins {
    groovy // Enables Groovy support for writing QuPath extensions
    id("com.gradleup.shadow") version "8.3.5" // Bundles dependencies into a fat JAR
    id("qupath-conventions") // Applies QuPath's Gradle extension conventions

}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}


// TODO: Configure your extension here (please change the defaults!)
qupathExtension {
    name = "cell-search-engine-extension"
    group = "io.github.qupath"
    version = "1.0.0"
    description = "A QuPath Cell search engine extension"
    automaticModule = "io.github.qupath.extension.template"
}
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "mysql" && requested.name == "mysql-connector-java") {
            useVersion("8.0.33") // Force upgrade
            because("Fixing CVE-2023-22102 vulnerability")
        }
    }
}
tasks.withType<JavaExec> {
    systemProperty("java.util.logging.config.file", "logging.properties")
}
tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "qupath.ext.template.MySQLDebugger" // ✅ Corrected Main-Class
    }
}


// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    // Apache Commons Math dependency (for EuclideanDistance)
    shadow("org.apache.commons:commons-math3:3.6.1")

    shadow("mysql:mysql-connector-java:8.0.33") // ✅ Ensures MySQL is bundled
    implementation("mysql:mysql-connector-java:8.0.33")
    runtimeOnly("mysql:mysql-connector-java:8.0.33")
    // If you aren't using Groovy, this can be removed

    shadow(libs.bundles.groovy)
    
    // Add JSON support
    implementation("com.google.code.gson:gson:2.10.1")
    shadow("com.google.code.gson:gson:2.10.1")

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

}
