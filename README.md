# Fullbright for Minecraft

Fullbright is a client-side brightness toggle. Press **G** to turn it on or off.

Download the JAR for your exact Minecraft version from [GitHub Releases](https://github.com/Frish07/Minecraft-Fullbright-for-Embeddium/releases). Each release lists the installation method and Java version.

| JAR suffix | Installation |
| --- | --- |
| `fabric` or `fabric-named` | Install Fabric Loader and put the JAR in the instance's `mods` directory. |
| `legacy-fabric` | Install Legacy Fabric and put the JAR in the instance's `mods` directory. |
| `java-agent` | In a vanilla installation, add `-javaagent:"<absolute path to the JAR>"` to the Minecraft launcher's JVM arguments. |
| `forge` | Use the Forge JAR with Minecraft 1.20.1 and Embeddium in the instance's `mods` directory. |

## Build

Use Python 3.11 or newer and JDK 17. Run `python ports/build.py` to build every stable Java Edition version from 1.8.9 through 26.3 or pass specific versions to build a subset. The script verifies the hooks against the official client bytecode and puts the distributable JARs and version manifest in `build/releases/`.

The separate Forge 1.20.1 source still builds with `./gradlew build`. The release catalog includes the existing Forge JAR from the repository.

The **Build and release Fullbright** workflow builds the complete catalog. Run it with **publish** selected from `main` to create a separate release per Minecraft version in publication order. GitHub shows the newest releases first.
