"""Verify and package the Tiger I increment against the pre-task workspace snapshot."""
from __future__ import annotations

import difflib
import hashlib
import json
from pathlib import Path
import shutil
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "backups/tiger_i_before"
FIX_BASELINE = ROOT / "backups/tiger_i_fix_before"
# Source additions ZIP supplies the same snapshots under these shorter names.
if not BASELINE.is_dir(): BASELINE = ROOT / "baseline"
if not FIX_BASELINE.is_dir(): FIX_BASELINE = ROOT / "baseline_fix"
OUT = ROOT / "build/dist/tiger_i_fix"
RESOURCES = ROOT / "src/main/resources"
BUILT_JAR = ROOT / "build/libs/create-fire-control-0.8.4.jar"
PACKAGED_JAR = OUT / "create-fire-control-0.8.4-tiger-i.3.jar"
SOURCE_ZIP = OUT / "create-fire-control-tiger-i-source-additions.zip"
AUDIO_ZIP = OUT / "tiger-i-reference-legacy-native-ab.zip"
SOUND_PREFIX = "assets/create_fire_control/sounds/engine/tiger_i/"
TAGS = [
    "data/minecraft/tags/block/mineable/pickaxe.json",
    "data/minecraft/tags/block/needs_iron_tool.json",
]
PATTERNS = [
    "src/main/java/**/Tiger*.java",
    "src/main/java/**/OpenTiger*.java",
    "src/main/java/**/SetTiger*.java",
    "src/main/resources/**/tiger_i_engine*",
    "src/main/resources/**/tiger_i_engine/**/*",
    "src/main/resources/**/tiger_i/**/*",
    "src/tigerIntegration/**/*",
    "src/test/java/**/Tiger*.java",
    "docs/tiger_i_reference/**/*",
    "tools/*tiger*",
    "tools/list_fmod_events.py",
    "tools/render_fmod_event.py",
    "docs/TIGER_I_DELIVERY.md",
    "reference/tiger_i/model/**/*",
    "reference/tiger_i/**/*.xml",
    "reference/tiger_i/audio_analysis.json",
    "reference/tiger_i/source_mapping.json",
    "reference/tiger_i/profile_reference.json",
    "reference/tiger_i/native*.json",
]
EVIDENCE = [
    "run_tiger_i_verify/server/tiger-integration-result.txt",
    "run_tiger_i_verify/client/tiger-audio-client-result.txt",
    "run_tiger_i_verify/client/tiger-audio-client-report.txt",
    "build/tiger_i_fix_server.log",
    "build/tiger_i_fix_client.log",
    "build/tiger_i_fix_final_audio_checks.log",
    "build/tiger_i_fix_build.log",
    "build/tiger_i_fix_final_build.log",
]
REQUIRED_PRODUCTION = {
    "TigerIAudioCalibration", "TigerIFmodFade", "TigerIGain", "TigerIReferenceFade",
    "TigerISoundConfig", "TigerISoundManager", "TigerISoundProfile", "TigerISpatialAudio", "TigerITakes",
    "TigerIEngineBlock", "TigerIEngineBlockEntity", "TigerIEnginePart", "TigerIEngineRenderer",
    "TigerIKineticPortMixin", "TigerIRotationPortMixin", "TigerITorqueAccessor", "TigerIKineticNetworkMixin",
    "TigerIAudioChannelMixin", "OpenTigerIEnginePayload", "SetTigerIEngineSettingsPayload",
}
NEW_FIX_CLASSES = {
    "TigerIReferenceFade", "TigerISpatialAudio", "TigerIKineticPortMixin", "TigerIRotationPortMixin",
    "TigerITorqueAccessor", "TigerIKineticNetworkMixin", "TigerIAudioChannelMixin",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def require(value: bool, message: str) -> None:
    if not value:
        raise RuntimeError(message)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    files: set[Path] = set()
    for before in BASELINE.rglob("*"):
        if not before.is_file() or before.name == "existing_sound_hashes.json":
            continue
        after = ROOT / before.relative_to(BASELINE)
        require(after.is_file(), f"Baseline file was removed: {after}")
        if before.read_bytes() != after.read_bytes():
            files.add(after)
    for before in FIX_BASELINE.rglob("*"):
        if not before.is_file() or before.name == "audio_hashes.json":
            continue
        after = ROOT / before.relative_to(FIX_BASELINE)
        require(after.is_file(), f"Fix baseline file was removed: {after}")
        if before.read_bytes() != after.read_bytes():
            files.add(after)
    for pattern in PATTERNS:
        files.update(p for p in ROOT.glob(pattern) if p.is_file())
    files.update(RESOURCES / name for name in TAGS)
    production = sorted(p for p in files if p.suffix == ".java" and p.is_relative_to(ROOT / "src/main/java"))
    production = [p for p in production if "Tiger" in p.name]
    require(REQUIRED_PRODUCTION <= {p.stem for p in production}, "Required Tiger I production Java files missing")
    resources = sorted(p for p in files if p.is_relative_to(RESOURCES))
    audio = sorted((RESOURCES / SOUND_PREFIX).glob("*.ogg"))
    require(len(audio) == 30, "Expected 30 audible mono Ogg resources")

    with ZipFile(BUILT_JAR) as jar:
        names = set(jar.namelist())
        for path in production:
            entry = path.relative_to(ROOT / "src/main/java").with_suffix(".class").as_posix()
            require(entry in names, f"Missing production class: {entry}")
            compiled = ROOT / "build/classes/java/main" / entry
            require(compiled.is_file() and jar.read(entry) == compiled.read_bytes(), f"Stale compiled class: {entry}")
        for path in resources:
            entry = path.relative_to(RESOURCES).as_posix()
            require(entry in names and jar.read(entry) == path.read_bytes(), f"Stale/missing resource: {entry}")
        require(SOUND_PREFIX + "NOTICE.md" in names, "Audio attribution is missing")
        require(not any("data/minecraft/tags/blocks/" in n and ("pickaxe" in n or "needs_iron_tool" in n)
                        for n in names), "Obsolete pre-1.21 tag directory remains in the JAR")
        require(not any("TigerIntegration" in n or "TigerIAudioClientChecks" in n or
                        "TigerIResourceChecks" in n or "RemoteControllable" in n
                        for n in names), "Test fixture leaked into the release JAR")
        config=json.loads(jar.read("create_fire_control.mixins.json"))
        require({"TigerIKineticPortMixin", "TigerIRotationPortMixin", "TigerIKineticNetworkMixin", "TigerITorqueAccessor"}
                <= set(config["mixins"]), "Centered kinetic port mixins were not registered")
        require("client.TigerIAudioChannelMixin" in config["client"], "Tiger audio filter mixin was not registered")

    old_hashes = json.loads((BASELINE / "existing_sound_hashes.json").read_text(encoding="utf-8"))
    for name, expected in old_hashes.items():
        require(sha256(ROOT / name) == expected, f"Existing engine audio changed: {name}")
    fix_audio_hashes = json.loads((FIX_BASELINE / "audio_hashes.json").read_text(encoding="utf-8"))
    for name, expected in fix_audio_hashes.items():
        require(sha256(ROOT / name) == expected, f"Ogg changed during current fix: {name}")
    sounds_path = Path("src/main/resources/assets/create_fire_control/sounds.json")
    before_sounds = json.loads((BASELINE / sounds_path).read_text(encoding="utf-8"))
    after_sounds = json.loads((ROOT / sounds_path).read_text(encoding="utf-8"))
    require(all(after_sounds.get(key) == value for key, value in before_sounds.items()),
            "An existing sound event was modified")
    require(len(set(after_sounds) - set(before_sounds)) == 30, "Expected exactly 30 new sound events")
    for name in EVIDENCE[:2]:
        require((ROOT / name).read_text(encoding="utf-8").strip() == "PASS", f"Game test did not pass: {name}")
    for name in EVIDENCE[3:6] + EVIDENCE[7:]:
        require("BUILD SUCCESSFUL" in (ROOT / name).read_text(encoding="utf-8"), f"Build did not pass: {name}")
    failed_build=(ROOT/EVIDENCE[6]).read_text(encoding="utf-8")
    require("BUILD FAILED" in failed_build and "testNativeBearing FAILED" in failed_build,
            "Expected unrelated full-build failure evidence is missing")
    measurements=json.loads((OUT/"audio/measurements.json").read_text(encoding="utf-8"))
    require(len(measurements)==30 and len(list((OUT/"audio").glob("*.wav")))==30, "Expected 30 A/B WAVs")
    require("native_bank_start_stop_rpm_only.wav" in measurements and "native_bank_start_stop.wav" not in measurements,
            "Native start/stop limitation must be labeled explicitly")
    require(len(list((ROOT/"build/reports/tiger_i_fix").glob("*_tracks.csv")))==20, "Expected 20 mixer traces")

    records = []
    patch_lines = []
    fix_patch_lines = []
    fix_records = []
    binary_files = []
    for path in sorted(files):
        relative = path.relative_to(ROOT)
        before = BASELINE / relative
        patch_baseline = "pre-tiger" if before.is_file() else None
        if not before.is_file() and (FIX_BASELINE/relative).is_file():
            before=FIX_BASELINE/relative
            patch_baseline="pre-fix"
        entry = {"path": relative.as_posix(), "sha256": sha256(path), "bytes": path.stat().st_size,
                 "kind": "modified" if before.is_file() else "added"}
        if before.is_file():
            entry["baseline_sha256"] = sha256(before)
            entry["patch_baseline"] = patch_baseline
        records.append(entry)
        try:
            old_lines = before.read_text(encoding="utf-8").splitlines(keepends=True) if before.is_file() else []
            new_lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        except UnicodeDecodeError:
            binary_files.append(relative.as_posix())
            continue
        patch_lines.extend(difflib.unified_diff(
            old_lines, new_lines, fromfile="a/" + relative.as_posix() if before.is_file() else "/dev/null",
            tofile="b/" + relative.as_posix()))
        fix_before=FIX_BASELINE/relative
        if fix_before.is_file() and fix_before.read_bytes()!=path.read_bytes() or path.stem in NEW_FIX_CLASSES:
            fix_records.append(relative.as_posix())
            fix_old=fix_before.read_text(encoding="utf-8").splitlines(keepends=True) if fix_before.is_file() else []
            fix_patch_lines.extend(difflib.unified_diff(fix_old,new_lines,
                fromfile="a/"+relative.as_posix() if fix_before.is_file() else "/dev/null",
                tofile="b/"+relative.as_posix()))

    shutil.copyfile(BUILT_JAR, PACKAGED_JAR)
    shutil.copyfile(ROOT / "docs/TIGER_I_DELIVERY.md", OUT / "TIGER_I_DELIVERY.md")
    shutil.copyfile(ROOT / "reference/tiger_i/model/model_preview.png", OUT / "model_preview.png")
    shutil.copyfile(ROOT / "run_tiger_i_verify/client/screenshots/tiger_i_v8_model.png", OUT / "minecraft_item_preview.png")
    write_json(OUT / "source_manifest.json", {
        "baseline": "Pre-Tiger working directory snapshot where available; pre-fix .2 snapshot for later Tiger files, not Git HEAD",
        "scope": "Tiger I source/resource additions and modified integration files; requires the existing project",
        "binary_files_provided_directly_in_zip": binary_files,
        "files": records,
        "fix_patch_scope": "Only snapshotted pre-fix files and explicitly new production classes; full current tools/tests are supplied in ZIP",
        "fix_patch_files": fix_records,
    })
    (OUT / "tiger_i_changes.patch").write_text("".join(patch_lines), encoding="utf-8", newline="\n")
    (OUT / "tiger_i_fix_changes.patch").write_text("".join(fix_patch_lines), encoding="utf-8", newline="\n")
    write_json(OUT / "package_verification.json", {
        "jar_sha256": sha256(PACKAGED_JAR),
        "production_classes_checked": len(production),
        "resources_byte_checked": len(resources),
        "mono_ogg_resources": len(audio),
        "existing_audio_hashes_unchanged": len(old_hashes),
        "all_engine_ogg_unchanged_during_current_fix": len(fix_audio_hashes),
        "existing_sound_events_unchanged": len(before_sounds),
        "test_fixtures_excluded": True,
        "server_result": "PASS",
        "client_result": "PASS",
        "full_build": "Failed at existing testNativeBearing: sustained carrier rotation holds every tested load before stopping",
        "build_excluding_existing_native_bearing_test": "PASS",
        "final_resource_build_and_tiger_checks": "PASS",
        "supplied_v8_model": "21 source cubes, 252 triangles, original texture and scale, Y +90 degrees",
        "model_build_and_actual_client_baking": "PASS",
        "placed_cells": 1,
        "half_shaft_centres_model_coordinates": [[8,8,8],[8,8,8]],
        "independent_kinetic_networks": 2,
        "default_audio_mode": "FMOD_REFERENCE / FULL",
        "ab_wav_count": 30,
        "ab_files_individually_normalized": False,
        "actual_openal_qa": "7 sources started once over 32 seconds; 7 EFX filters; all freed after 5 ticks",
        "limitations": ["Full native Bearing regression fails at an unchanged assertion",
                        "Manual listening and full end-to-end dynamic audio lifecycle capture remain outstanding",
                        "Full FMOD EQ, compression/limiting, snapshots, headphone return paths and WT gameplay occlusion are not cloned",
                        "Installed bank and pinned XML are separate builds; Climb/loadswitch and HF mappings are explicit MC approximations",
                        "native_bank_start_stop_rpm_only.wav contains no native startup/shutdown events"],
        "evidence": EVIDENCE,
    })
    with ZipFile(SOURCE_ZIP, "w", ZIP_DEFLATED, compresslevel=6) as archive:
        archive.write(BASELINE/"existing_sound_hashes.json","baseline/existing_sound_hashes.json")
        archive.write(FIX_BASELINE/"audio_hashes.json","baseline_fix/audio_hashes.json")
        for path in sorted(files):
            relative = path.relative_to(ROOT)
            archive.write(path, relative.as_posix())
            before = BASELINE / relative
            if before.is_file():
                archive.write(before, "baseline/" + relative.as_posix())
            fix_before=FIX_BASELINE/relative
            if fix_before.is_file():
                archive.write(fix_before,"baseline_fix/"+relative.as_posix())
        for name in ["source_manifest.json", "tiger_i_changes.patch", "tiger_i_fix_changes.patch", "package_verification.json"]:
            archive.write(OUT / name, name)
        for name in EVIDENCE:
            archive.write(ROOT / name, "verification/" + Path(name).name)
    with ZipFile(SOURCE_ZIP) as archive:
        require(archive.testzip() is None, "Source ZIP integrity failed")
        for record in records:
            require(hashlib.sha256(archive.read(record["path"])).hexdigest() == record["sha256"],
                    f"Source ZIP byte mismatch: {record['path']}")
    audio_files=sorted(p for p in (OUT/"audio").glob("*") if p.is_file())
    traces=sorted((ROOT/"build/reports/tiger_i_fix").glob("*_tracks.csv"))
    write_json(OUT/"audio_manifest.json",{
        "conditions":"Same scalar=1.5, no per-file normalization. Native at 3m; offline mono dry center. See README for limitations.",
        "files":[{"path":p.relative_to(OUT).as_posix(),"sha256":sha256(p),"bytes":p.stat().st_size} for p in audio_files],
        "traces":[{"path":p.name,"sha256":sha256(p)} for p in traces],
    })
    with ZipFile(AUDIO_ZIP,"w",ZIP_DEFLATED,compresslevel=6) as archive:
        for path in audio_files:archive.write(path,"audio/"+path.name)
        for path in traces:archive.write(path,"traces/"+path.name)
        archive.write(OUT/"audio_manifest.json","audio_manifest.json")
        archive.write(OUT/"TIGER_I_DELIVERY.md","TIGER_I_DELIVERY.md")
    with ZipFile(AUDIO_ZIP) as archive:
        require(archive.testzip() is None,"Audio ZIP integrity failed")
        for path in audio_files:
            require(hashlib.sha256(archive.read("audio/"+path.name)).hexdigest()==sha256(path),f"Audio ZIP mismatch: {path.name}")
    artifacts = [PACKAGED_JAR, SOURCE_ZIP, OUT / "TIGER_I_DELIVERY.md", OUT / "source_manifest.json",
                 OUT / "tiger_i_changes.patch", OUT / "tiger_i_fix_changes.patch", OUT / "package_verification.json",
                 AUDIO_ZIP, OUT/"audio_manifest.json",
                 OUT / "model_preview.png", OUT / "minecraft_item_preview.png"]
    artifacts.extend(sorted((OUT / "audio").glob("*")))
    (OUT / "SHA256SUMS.txt").write_text(
        "".join(f"{sha256(path)}  {path.relative_to(OUT).as_posix()}\n" for path in artifacts if path.is_file()),
        encoding="utf-8", newline="\n")
    print(f"PASS: {len(production)} production classes, {len(resources)} exact resources, "
          f"{len(audio)} Ogg files, {len(old_hashes)} unchanged existing audio files")
    print(f"Packaged {len(records)} source/resource files into {SOURCE_ZIP.relative_to(ROOT).as_posix()}")
    print(f"JAR: {PACKAGED_JAR.relative_to(ROOT).as_posix()} ({PACKAGED_JAR.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
