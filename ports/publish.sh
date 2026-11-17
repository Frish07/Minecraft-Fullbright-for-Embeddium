#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
directory=build/releases
target="$(git rev-parse HEAD)"
manifest="$directory/manifest.json"

jq -e 'length == 66 and .[0].minecraft == "1.8.9" and .[-1].minecraft == "26.3"' "$manifest" >/dev/null
while IFS= read -r asset; do
  unzip -tq "$directory/$asset" >/dev/null
done < <(jq -r '.[].assets[]' "$manifest")

while IFS= read -r row; do
  version="$(jq -r '.minecraft' <<<"$row")"
  mode="$(jq -r '.mode' <<<"$row")"
  java="$(jq -r '.java' <<<"$row")"
  tag="mc-$version"
  mapfile -t asset_names < <(jq -r '.assets[]' <<<"$row")
  assets=("${asset_names[@]/#/$directory/}")

  case "$mode" in
    fabric|fabric-named)
      instructions="Install Fabric Loader for Minecraft $version and put the Fabric JAR in the instance's mods folder." ;;
    legacy-fabric)
      instructions="Install Legacy Fabric for Minecraft $version and put the JAR in the instance's mods folder." ;;
    java-agent)
      instructions="Use an unmodified Minecraft $version client and add -javaagent:<absolute path to ${asset_names[0]}> to its JVM arguments. Quote the path if it contains spaces." ;;
    *)
      echo "Unsupported distribution mode: $mode" >&2
      exit 1 ;;
  esac

  if [[ "$version" == "1.20.1" ]]; then
    instructions+=$'\n\nFor Forge 1.20.1 with Embeddium, use the separate Forge JAR in the mods folder.'
  fi
  notes="$(printf '%s\n\nJava %s. Press G to toggle fullbright.\n' "$instructions" "$java")"
  latest=false
  if [[ "$version" == "26.3" ]]; then
    latest=true
  fi

  if result="$(gh release view "$tag" --json isDraft,assets --jq '.')" ; then
    if [[ "$(jq -r '.isDraft' <<<"$result")" == "true" ]]; then
      gh release upload "$tag" "${assets[@]}" --clobber
      gh release edit "$tag" --draft=false --latest="$latest"
    else
      for name in "${asset_names[@]}"; do
        jq -e --arg name "$name" '.assets | any(.name == $name and .size > 0)' <<<"$result" >/dev/null
      done
    fi
  else
    gh release create "$tag" "${assets[@]}" --target "$target" \
      --title "Fullbright for Minecraft $version" --notes "$notes" --latest="$latest"
  fi
done < <(jq -c '.[]' "$manifest")
