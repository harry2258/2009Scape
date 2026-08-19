"""Standalone reader for the RuneScape JS5 disk cache (dat2 + idxN + idx255).

Read-only introspection: master index group names, raw group bytes, container
decompression, and old/new group-file splitting. Used to dump the world map
underlay colors from the rt4 client cache.
"""

import gzip
import io
import os
import struct
import zlib

try:
    import bz2
except ImportError:  # pragma: no cover
    bz2 = None

CACHE_DIR = r"C:\Users\diya0\IdeaProjects\rt4-client\cache"


def name_hash(name):
    """Jagex's djb2-style name hash used in the master index."""
    h = 0
    for c in name:
        h = (h * 61 + ord(c) - 32) & 0xFFFFFFFF
    return h


class FileStore:
    def __init__(self, cache_dir=CACHE_DIR):
        self.cache_dir = cache_dir
        self.dat2 = open(os.path.join(cache_dir, "main_file_cache.dat2"), "rb")

    def read_index(self, idx):
        """Return list of (size, sector) per archive from idx file."""
        with open(os.path.join(self.cache_dir, f"main_file_cache.idx{idx}"), "rb") as f:
            raw = f.read()
        out = []
        for i in range(len(raw) // 6):
            entry = raw[i * 6:i * 6 + 6]
            size = int.from_bytes(entry[:3], "big")
            sector = int.from_bytes(entry[3:], "big")
            out.append((size, sector))
        return out

    def read_archive(self, idx, archive_id):
        entries = self.read_index(idx)
        size, sector = entries[archive_id]
        data = bytearray()
        chunk = 0
        read = 0
        while read < size and sector != 0:
            self.dat2.seek(sector * 520)
            header = self.dat2.read(8)
            next_sector, _part_idx, _file_id, _chunk = struct.unpack(">IBBB", header)
            block = self.dat2.read(512)
            take = min(512, size - read)
            data += block[:take]
            read += take
            sector = next_sector
        return bytes(data)


def uncontainer(raw):
    """Strip container header: compression u8, size i32, then optional crc."""
    compression = raw[0]
    length = struct.unpack(">I", raw[1:5])[0]
    body = raw[5:]
    if compression == 0:
        data = body[:length]
    elif compression == 1:
        data = bz2.decompress(body)[:length]
    elif compression == 2:
        data = gzip.decompress(body)[:length]
    else:
        raise ValueError(f"compression {compression}")
    return data


def split_group(data, new_format):
    """Split a decompressed group into files. Returns list of bytes."""
    if new_format:
        count = data[0]
        off = 1
    else:
        count = struct.unpack(">H", data[0:2])[0]
        off = 2
    ids = [struct.unpack(">H", data[off + i * 2:off + i * 2 + 2])[0] for i in range(count)]
    off += count * 2
    stretches = []
    if new_format:
        for _ in range(count):
            stretch = 0
            while True:
                b = data[off]; off += 1
                stretch = (stretch << 7) | (b & 0x7F)
                if b < 0x80:
                    break
            stretches.append(stretch)
    else:
        for _ in range(count):
            stretches.append(data[off]); off += 1
    files = {}
    pos = off
    for fid, stretch in zip(ids, stretches):
        files[fid] = data[pos:pos + stretch]
        pos += stretch
    return files


def read_master_index(store):
    """Parse idx255 master index. Returns dict idx -> {archive: name_str_or_None}."""
    raw = uncontainer(store.read_archive(255, 0))
    count = struct.unpack(">H", raw[0:2])[0]
    off = 2
    names = {i: {} for i in range(28)}
    revisions = {}
    for idx in range(count):
        name_hash_ = struct.unpack(">i", raw[off:off + 4])[0]; off += 4
        _crc = struct.unpack(">I", raw[off:off + 4])[0]; off += 4
        _rev = struct.unpack(">i", raw[off:off + 4])[0]; off += 4
        file_count = struct.unpack(">H", raw[off:off + 2])[0]; off += 2
        has_names = raw[off]; off += 1
        if has_names:
            off += file_count * 4
        file_ids = [struct.unpack(">H", raw[off + i * 2:off + i * 2 + 2])[0]
                    for i in range(file_count)]
        off += file_count * 2
        names[idx] = {"name_hash": name_hash_, "files": len(file_ids)}
    return names


if __name__ == "__main__":
    store = FileStore()
    master = read_master_index(store)
    for idx, info in master.items():
        if info:
            print(f"index {idx:2d}: name_hash={info['name_hash'] & 0xFFFFFFFF:10d} files={info['files']}")
