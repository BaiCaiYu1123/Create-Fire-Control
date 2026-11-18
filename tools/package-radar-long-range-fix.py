"""Replace audited radar class families in an existing complete Fire Control JAR."""
import argparse
import hashlib
import json
from copy import copy
from pathlib import Path
from zipfile import ZipFile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--compiled", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--prefix", action="append", help="Audited class-family path; repeat for each changed family")
    parser.add_argument("--controller-patch", type=Path, help="Controller with exactly one priority call patched")
    parser.add_argument("--class-patch", action="append", default=[], metavar="ENTRY=FILE", help="Separately audited method patch for an existing class")
    args = parser.parse_args()
    if args.out.resolve() in (args.base.resolve(), args.compiled.resolve()):
        raise ValueError("The delivery must be a separate file")
    prefixes = tuple(args.prefix or ["com/hooya/stabilizedturret/content/controller/RadarVisibility"])
    with ZipFile(args.base) as base, ZipFile(args.compiled) as compiled:
        selected = {name: compiled.read(name) for name in compiled.namelist()
                    if name.startswith(prefixes) and name.endswith(".class")}
        original = {name for name in base.namelist() if name.startswith(prefixes) and name.endswith(".class")}
        if not original.issubset(selected) or not selected:
            raise ValueError("Unexpected radar class inventory; review the dependency boundary")
        if args.controller_patch:
            selected["com/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity.class"] = args.controller_patch.read_bytes()
        for patch in args.class_patch:
            entry, path = patch.split("=", 1)
            if entry not in base.namelist() or not entry.endswith(".class") or entry in selected:
                raise ValueError(f"Unexpected class patch target: {entry}")
            selected[entry] = Path(path).read_bytes()
        args.out.parent.mkdir(parents=True, exist_ok=True)
        staged = args.out.with_suffix(args.out.suffix + ".partial")
        with ZipFile(staged, "w") as result:
            for entry in base.infolist():
                result.writestr(copy(entry), selected.get(entry.filename, base.read(entry.filename)))
            for name in sorted(set(selected) - set(base.namelist())):
                result.writestr(copy(compiled.getinfo(name)), selected[name])
        changed = []
        with ZipFile(staged) as result:
            if (set(base.namelist()) | set(selected)) != set(result.namelist()) or result.testzip() is not None:
                raise ValueError("Invalid production JAR")
            for name in base.namelist():
                if base.read(name) != result.read(name):
                    if name not in selected:
                        raise ValueError(f"Unrelated installed content changed: {name}")
                    changed.append(name)
            if any("RadarLongRangeIntegrationChecks" in name or "RadarVisibilityIntegrationChecks" in name
                   for name in result.namelist()):
                raise ValueError("Integration fixtures must not be shipped")
            changed.extend(sorted(set(selected) - set(base.namelist())))
    staged.replace(args.out)
    value = digest(args.out)
    args.out.with_suffix(args.out.suffix + ".sha256").write_text(
        f"{value}  {args.out.name}\n", encoding="utf-8")
    manifest = {"base": args.base.name, "base_sha256": digest(args.base),
                "compiled_sha256": digest(args.compiled), "output": args.out.name,
                "output_sha256": value, "changed_entries": changed,
                "audited_prefixes": prefixes,
                "other_entries_identical": True}
    args.out.with_suffix(args.out.suffix + ".changes.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
