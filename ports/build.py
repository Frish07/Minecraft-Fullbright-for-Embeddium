import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from urllib.parse import quote
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build" / "ports"
CACHE = WORK / "data"
OUTPUT = ROOT / "build" / "releases"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_MAVEN = "https://maven.fabricmc.net"
LEGACY_MAVEN = "https://maven.legacyfabric.net"
LEGACY_FABRIC = {"1.8.9", "1.9.4", "1.10.2", "1.11.2", "1.12.2", "1.13.2"}
NAMED_MINECRAFT = {"26.1", "26.1.1", "26.1.2", "26.2", "26.3"}
ASM_VERSION = "9.10.1"
MIXIN_VERSION = "0.17.5+mixin.0.8.7"


def download(url, destination, sha1=None):
    if destination.is_file() and destination.stat().st_size:
        if sha1 is None:
            return destination
        with destination.open("rb") as cached:
            if hashlib.file_digest(cached, "sha1").hexdigest() == sha1:
                return destination
    for attempt in range(3):
        try:
            with urlopen(url, timeout=90) as response:
                payload = response.read()
            if sha1 is not None and hashlib.sha1(payload).hexdigest() != sha1:
                raise ValueError(f"SHA-1 mismatch for {url}")
            temporary = destination.with_suffix(destination.suffix + ".tmp")
            temporary.write_bytes(payload)
            temporary.replace(destination)
            return destination
        except (OSError, ValueError):
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def release_versions():
    data = read_json(download(MANIFEST, CACHE / "versions.json"))
    releases = [version for version in reversed(data["versions"]) if version["type"] == "release"]
    ids = [version["id"] for version in releases]
    return releases[ids.index("1.8.9"):ids.index("26.3") + 1]


def maven(server, group, artifact, version, destination):
    directory = quote(version)
    file = quote(f"{artifact}-{version}.jar")
    return download(f"{server}/{group}/{artifact}/{directory}/{file}", destination)


def prepare_game(game):
    version = game["id"]
    profile = read_json(download(game["url"], CACHE / f"{version}.json", game["sha1"]))
    client = profile["downloads"]["client"]
    download(client["url"], CACHE / f"{version}.jar", client["sha1"])
    if version in LEGACY_FABRIC:
        mode = "legacy-fabric"
        maven(LEGACY_MAVEN, "net/legacyfabric", "intermediary", version, CACHE / f"{mode}-{version}.jar")
    elif version in NAMED_MINECRAFT:
        mode = "fabric-named"
    elif int(version.split(".")[1]) >= 14:
        mode = "fabric"
        maven(FABRIC_MAVEN, "net/fabricmc", "intermediary", version, CACHE / f"{mode}-{version}.jar")
    else:
        mode = "java-agent"
    return version, mode, profile.get("javaVersion", {}).get("majorVersion", 8)


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        suffix = ".exe" if os.name == "nt" else ""
        return str(Path(java_home) / "bin" / (name + suffix))
    return name


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("versions", nargs="*", help="Build specific Minecraft versions for development")
    args = parser.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    releases = release_versions()
    if args.versions:
        releases = [game for game in releases if game["id"] in args.versions]
        unknown = set(args.versions) - {game["id"] for game in releases}
        if unknown:
            parser.error(f"Unknown Minecraft versions: {', '.join(sorted(unknown))}")

    dependencies = []
    for artifact in ("asm", "asm-tree", "asm-commons", "asm-analysis"):
        dependencies.append(maven(FABRIC_MAVEN, "org/ow2/asm", artifact, ASM_VERSION,
                                  CACHE / f"{artifact}-{ASM_VERSION}.jar"))
    dependencies.append(maven(FABRIC_MAVEN, "net/fabricmc", "sponge-mixin", MIXIN_VERSION,
                              CACHE / f"sponge-mixin-{MIXIN_VERSION}.jar"))
    with ThreadPoolExecutor(max_workers=8) as pool:
        builds = list(pool.map(prepare_game, releases))

    (WORK / "build.tsv").write_text("".join(f"{version}\t{mode}\n" for version, mode, _ in builds), encoding="utf-8")
    classes = WORK / "classes"
    classes.mkdir(exist_ok=True)
    source = ROOT / "ports" / "src"
    cp = os.pathsep.join(map(str, dependencies))
    runtime = sorted(p for p in source.rglob("*.java") if p.name != "BuildPorts.java")
    subprocess.run([java_tool("javac"), "--release", "8", "-proc:none", "-cp", cp, "-d", str(classes),
                    *map(str, runtime)], check=True)
    subprocess.run([java_tool("javac"), "--release", "17", "-proc:none", "-cp", cp + os.pathsep + str(classes),
                    "-d", str(classes), str(source / "fullbright" / "port" / "BuildPorts.java")], check=True)
    subprocess.run([java_tool("java"), "-Xmx1g", "-cp", cp + os.pathsep + str(classes), "fullbright.port.BuildPorts",
                    str(WORK), str(OUTPUT)], check=True)

    manifest = []
    for version, mode, java_version in builds:
        artifacts = [f"fullbright-{version}-{mode}.jar"]
        if version == "1.20.1":
            forge = "fullbright-1.20.1-forge.jar"
            shutil.copyfile(ROOT / "fullbright_for_embeddium-1.0.0.jar", OUTPUT / forge)
            artifacts.append(forge)
        manifest.append({"minecraft": version, "mode": mode, "java": java_version, "assets": artifacts})
    (OUTPUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
