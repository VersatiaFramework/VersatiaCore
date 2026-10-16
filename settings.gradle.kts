rootProject.name = "VersatiaCore"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // VersatiaAPI is consumed from the local Maven repository (`./gradlew publishToMavenLocal` there)
        mavenLocal { content { includeGroup("com.github.marcoral.versatia") } }
        mavenCentral()
        // Needed to resolve the Paper API that VersatiaAPI exports
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
