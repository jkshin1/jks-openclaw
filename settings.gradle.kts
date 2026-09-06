pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                google {
                    name = "GoogleLiteRtLm"
                }
            }
            filter {
                includeGroup("com.google.ai.edge.litertlm")
            }
        }
        google {
            content {
                excludeGroup("com.google.ai.edge.litertlm")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "personal-edge-agent"

include(":app")
include(":core:agent")
include(":core:data")
include(":core:llm")
include(":core:openclaw")
include(":core:tools")
include(":core:diagnostics")
