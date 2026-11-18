"""Read-only, dependency-free inspection of Fire Control block-entity records.

Accepts a region directory and prints only weapon bindings and input channels.
Never writes to the game or reports unrelated player/world data.
"""
import gzip
import io
import json
import pathlib
import struct
import sys
import zlib


class NbtReader:
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def number(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.stream.read(size))[0]

    def string(self):
        return self.stream.read(self.number('>H')).decode('utf-8', errors='replace')

    def payload(self, kind):
        if kind in (1, 2, 3, 4, 5, 6):
            return self.number({1: '>b', 2: '>h', 3: '>i', 4: '>q', 5: '>f', 6: '>d'}[kind])
        if kind == 7:
            return self.stream.read(self.number('>i'))
        if kind == 8:
            return self.string()
        if kind == 9:
            element = self.number('>B')
            return [self.payload(element) for _ in range(self.number('>i'))]
        if kind == 10:
            result = {}
            while True:
                child = self.number('>B')
                if child == 0:
                    return result
                name = self.string()
                result[name] = self.payload(child)
        if kind in (11, 12):
            return [self.number('>i' if kind == 11 else '>q') for _ in range(self.number('>i'))]
        raise ValueError(f'Unknown NBT tag {kind}')

    def root(self):
        kind = self.number('>B')
        self.string()
        return self.payload(kind)


def records(path):
    with path.open('rb') as stream:
        locations = stream.read(4096)
        for index in range(1024):
            location = int.from_bytes(locations[index * 4:index * 4 + 4], 'big')
            offset = (location >> 8) * 4096
            if not offset:
                continue
            stream.seek(offset)
            length = int.from_bytes(stream.read(4), 'big')
            compression = stream.read(1)[0]
            payload = stream.read(length - 1)
            if payload.startswith(b'\x1f\x8b') or compression == 1:
                payload = gzip.decompress(payload)
            elif compression == 2:
                payload = zlib.decompress(payload)
            elif compression not in (0, 3):
                continue
            root = NbtReader(payload).root()
            yield from compounds(root)


def compounds(value):
    if isinstance(value, dict):
        if 'id' in value:
            yield value
        for child in value.values():
            yield from compounds(child)
    elif isinstance(value, list):
        for child in value:
            yield from compounds(child)


def main():
    region = pathlib.Path(sys.argv[1]).resolve()
    if region.is_file() and region.suffix == '.dat':
        root = NbtReader(gzip.decompress(region.read_bytes())).root()
        player = root.get('Data', {}).get('Player', root)
        selected = player.get('SelectedItemSlot')
        print(json.dumps({'selected_slot': selected, 'controllers': [item for item in player.get('Inventory', [])
                         if item.get('id', '').startswith(('drivebywire:', 'create_tweaked_controllers:'))
                         or item.get('id') == 'create:linked_controller']}, ensure_ascii=False))
        return
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    files = sorted([*region.glob('*.mca'), *region.glob('*.slvls')],
                   key=lambda path: path.stat().st_mtime, reverse=True)[:limit]
    count = 0
    for path in files:
        for record in records(path):
            if record.get('id') not in ('create_fire_control:stabilizer_controller',
                                       'create_fire_control:connected_display',
                                       'stabilized_turret:stabilizer_controller',
                                       'stabilized_turret:connected_display'):
                continue
            summary = {key: value for key, value in record.items()
                       if any(word in key for word in ('Binding', 'Wireless', 'RemoteKeys',
                                                       'Selected', 'AaLaunchers', 'CruiseLaunchers',
                                                       'BallisticLaunchers', 'RemoteKey'))}
            summary.update({key: record[key] for key in ('id', 'x', 'y', 'z')})
            print(json.dumps(summary, ensure_ascii=False))
            count += 1
    print(f'Inspected {len(files)} region files; found {count} Fire Control records')


if __name__ == '__main__':
    main()
