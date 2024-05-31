rootProject.name = "SamsungHealthStack"
val buildAppsWithSDKProject: String by settings

if (buildAppsWithSDKProject.toBoolean()) {
    include("starter-app")
    project(":starter-app").projectDir = file("samples/starter-app/app")
}
include(":kit")
include(":healthdata-link:interface")
include(":healthdata-link:healthconnect")
include(":backend-integration:interface")
include(":backend-integration:healthstack-adapter")
include(":resources:korean")
include(":app-support")
include(":common")
include(":samples:wearable")
include(":wearable-kit")
include(":wearable-support")
