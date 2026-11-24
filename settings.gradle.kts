pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }; mavenCentral() } }
rootProject.name = "HoyoCookieManager"
include(":app")
