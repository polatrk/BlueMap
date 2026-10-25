logger.lifecycle("""
## Building BlueMap ...
Java: ${System.getProperty("java.version")}
JVM: ${System.getProperty("java.vm.version")} (${System.getProperty("java.vendor")})
Arch: ${System.getProperty("os.arch")} 
""")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven ("https://maven.minecraftforge.net" )
        maven ("https://maven.fabricmc.net/" )
        maven ("https://maven.neoforged.net/releases" )
    }
}

rootProject.name = "bluemap"

includeBuild("api")

include(":core")
include(":common")

implementation("cli")
implementation("bedrock")
implementation("fabric")
implementation("forge")
implementation("neoforge")
implementation("paper")
implementation("spigot")
implementation("sponge")

fun implementation(name: String) {
    // -PonlyImplementations=cli,bedrock skips the other platforms (they need their own toolchains).
    val only = providers.gradleProperty("onlyImplementations").orNull?.split(",")?.map { it.trim() }
    if (only != null && name !in only) return
    val project = ":$name"
    include(project)
    project(project).projectDir = file("implementations/$name")
}
