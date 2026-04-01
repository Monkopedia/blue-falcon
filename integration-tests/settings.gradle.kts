pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.android") {
                useModule("com.android.tools.build:gradle:8.2.2")
            }
        }
    }
}

// The blue-falcon library must be published to mavenLocal before building:
//   cd ../library && ./gradlew publishToMavenLocal
rootProject.name = "bf-integration-tests"
