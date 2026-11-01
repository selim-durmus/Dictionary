// Standalone build (not part of the app): run from the repo root with
//   ./gradlew -p data-pipeline/zemberek run --args="<in.txt> <out.tsv>"
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Zemberek isn't on Maven Central; its author publishes it from this GitHub repo.
        maven("https://raw.githubusercontent.com/ahmetaa/maven-repo/master")
    }
}
rootProject.name = "zemberek-forms"
