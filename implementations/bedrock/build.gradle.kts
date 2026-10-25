plugins {
    bluemap.implementation
}

dependencies {
    api ( project( ":common" ) )
    api ( libs.commons.cli )
    implementation ( "net.kyori:adventure-text-serializer-legacy:${libs.versions.adventure.get()}" )
    implementation ( libs.adventure.ansi )
}

tasks.jar {
    manifest.attributes (
        "Main-Class" to "de.bluecolored.bluemap.bedrock.BlueMapBedrock"
    )
}
