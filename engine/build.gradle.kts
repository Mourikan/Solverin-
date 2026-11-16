plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

/** Regenerates the bundled 169x169 preflop equity table (Monte Carlo, deterministic). */
tasks.register<JavaExec>("generateEquityTable") {
    group = "solverin"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.solverin.engine.eval.EquityTable")
    args = listOf(
        layout.projectDirectory.file("src/main/resources/solverin/equity169.bin").asFile.absolutePath,
        (project.findProperty("samples") ?: "50000").toString(),
    )
}
