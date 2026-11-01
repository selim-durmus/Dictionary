plugins {
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    implementation("zemberek-nlp:zemberek-morphology:0.17.1")
}

application {
    mainClass.set("ZemberekForms")
    applicationDefaultJvmArgs = listOf("-Xmx3g", "-Dfile.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir.parentFile
}
