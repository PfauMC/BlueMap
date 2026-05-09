plugins {
    bluemap.java
}

dependencies {
    api ( "de.bluecolored:bluemap-api" )

    api ( libs.aircompressor )
    api ( libs.bluenbt )
    api ( libs.caffeine )
    api ( libs.commons.dbcp2 )
    api ( libs.configurate.hocon )
    api ( libs.configurate.gson )
    api ( libs.lz4 )

    testImplementation ( libs.testcontainers.minio )
    testImplementation ( libs.testcontainers.junit.jupiter )
}

tasks.register("zipResourceExtensions", type = Zip::class) {
    from(fileTree("src/main/resourceExtensions"))
    archiveFileName = "resourceExtensions.zip"
    destinationDirectory = file("src/main/resources/de/bluecolored/bluemap/")
}

tasks.processResources {
    dependsOn("zipResourceExtensions")

    from("src/main/resources") {
        include("de/bluecolored/bluemap/version.json")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        expand (
            "version" to project.version,
            "gitHash" to gitHash() + if (gitClean()) "" else " (dirty)",
        )
    }
}

tasks.getByName("sourcesJar") {
    dependsOn("zipResourceExtensions")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "bluemap-${project.name}"
            version = project.version.toString()

            from(components["java"])

            versionMapping {
                usage("java-api") {
                    fromResolutionResult()
                }
            }
        }
    }
}

tasks.register("checkS3RuntimeDependencies") {
    group = "verification"
    description = "Fails if AWS SDK, MinIO client, or third-party HTTP libraries appear on runtimeClasspath."
    val forbidden = listOf(
        "software.amazon.awssdk", "com.amazonaws", "io.minio",
        "com.squareup.okhttp", "com.squareup.okhttp3", "com.squareup.okio",
        "okhttp3", "org.apache.httpcomponents"
    )
    doLast {
        val rt = configurations.getByName("runtimeClasspath")
        val matches = rt.resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id.toString() }
            .filter { id -> forbidden.any { id.startsWith(it + ":") || id.contains(":" + it + ":") } }
        if (matches.isNotEmpty()) {
            throw GradleException(
                "Forbidden runtime dependency on the S3 storage path:\n  " + matches.joinToString("\n  ") +
                "\nThe S3 backend is required to use only the JDK standard library."
            )
        }
    }
}
tasks.named("check") { dependsOn("checkS3RuntimeDependencies") }
