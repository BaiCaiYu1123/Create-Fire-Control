import argparse
import csv
import os
import sys

_DLL_DIR = os.add_dll_directory(os.getcwd()) if os.name == "nt" else None

import fsb5


def find_fsb5(data: bytes) -> list[int]:
    offsets = []
    start = 0
    while True:
        offset = data.find(b"FSB5", start)
        if offset < 0:
            return offsets
        offsets.append(offset)
        start = offset + 4


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bank")
    parser.add_argument("output")
    parser.add_argument("--fsb-index", type=int, default=0)
    parser.add_argument("--start", type=int, default=0)
    parser.add_argument("--end", type=int)
    args = parser.parse_args()

    with open(args.bank, "rb") as source:
        bank_data = source.read()

    offsets = find_fsb5(bank_data)
    if not offsets:
        raise ValueError("No FSB5 stream found")
    if args.fsb_index >= len(offsets):
        raise ValueError(f"FSB index {args.fsb_index} is out of range: {offsets}")

    fsb = fsb5.FSB5(bank_data[offsets[args.fsb_index] :])
    end = len(fsb.samples) if args.end is None else min(args.end, len(fsb.samples))
    extension = fsb.get_sample_extension()
    os.makedirs(args.output, exist_ok=True)

    manifest_path = os.path.join(args.output, "manifest.csv")
    with open(manifest_path, "w", newline="", encoding="utf-8") as manifest:
        writer = csv.writer(manifest)
        writer.writerow(
            ["index", "name", "file", "frequency", "channels", "samples", "seconds"]
        )
        for index in range(args.start, end):
            sample = fsb.samples[index]
            filename = f"{index:04d}_{sample.name}.{extension}"
            path = os.path.join(args.output, filename)
            try:
                rebuilt = fsb.rebuild_sample(sample)
            except Exception as error:
                print(f"Failed to rebuild {index}: {error}", file=sys.stderr)
                continue
            with open(path, "wb") as target:
                target.write(rebuilt)
            writer.writerow(
                [
                    index,
                    sample.name,
                    filename,
                    sample.frequency,
                    sample.channels,
                    sample.samples,
                    sample.samples / sample.frequency,
                ]
            )
            if index % 100 == 0:
                print(f"Extracted {index}/{end - 1}", flush=True)

    print(f"Wrote {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
