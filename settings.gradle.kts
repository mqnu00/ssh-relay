// Repositories are pinned to mirrors first: this box cannot reach maven.google.com
// directly (it times out), and dl.google.com DNS is flaky. aliyun's `google`
// mirror proxies the Android/AGP artifacts and answers in ~0.3s.
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "ssh-relay"

include(":app")
include(":core-forward")
include(":core-ssh")
include(":tools:test-sshd")
