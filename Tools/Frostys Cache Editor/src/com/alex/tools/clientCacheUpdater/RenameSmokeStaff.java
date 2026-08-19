package com.alex.tools.clientCacheUpdater;

import com.alex.loaders.items.ItemDefinitions;
import com.alex.store.Store;

import java.nio.charset.StandardCharsets;

/**
 * Patches the in-cache display name of the cloned smoke staves so they
 * read "Smoke battlestaff" / "Mystic smoke staff" instead of the
 * cloned "Steam battlestaff" / "Mystic steam staff".
 *
 * Strategy: do a surgical byte-level edit of the opcode-2 name string
 * inside the raw item-definition bytes, rather than re-encoding the
 * whole def (the Alex encode() uses writeBigSmart for modelId which is
 * not 530-correct, so a full re-encode is unsafe).
 *
 * The name is encoded as: opcode byte 0x02, then the name as a
 * modified-UTF-8 / null-terminated string. We rebuild that segment with
 * the new name and splice it in place of the old segment.
 *
 * Run:
 *   javac -cp bin -d bin src/com/alex/tools/clientCacheUpdater/RenameSmokeStaff.java
 *   java -cp bin com.alex.tools.clientCacheUpdater.RenameSmokeStaff
 */
public class RenameSmokeStaff {

    // (id, oldName, newName)
    private static final String[][] RENAMES = {
            {"14659", "Steam battlestaff",  "Smoke battlestaff"},
            {"14661", "Mystic steam staff", "Mystic smoke staff"},
    };

    public static void main(String[] args) throws Exception {
        String cachePath = "C:/Users/diya0/IdeaProjects/2009scape/Server/data/cache/";
        Store cache = new Store(cachePath);

        for (String[] r : RENAMES) {
            int id = Integer.parseInt(r[0]);
            String oldName = r[1];
            String newName = r[2];

            int group = id >>> 8;
            int file = id & 0xFF;
            byte[] data = cache.getIndexes()[19].getFile(group, file);
            if (data == null) {
                System.out.println("id=" + id + " NOT FOUND in cache");
                continue;
            }

            byte[] oldBytes = encodeNameString(oldName);
            byte[] newBytes = encodeNameString(newName);

            int idx = indexOf(data, oldBytes);
            if (idx < 0) {
                System.out.println("id=" + id + " name segment \"" + oldName + "\" not found (already renamed?)");
                // Verify by decoding
                ItemDefinitions def = ItemDefinitions.getItemDefinition(cache, id);
                System.out.println("  current decoded name: \"" + def.getName() + "\"");
                continue;
            }

            byte[] patched = splice(data, idx, oldBytes.length, newBytes);

            // Defer table rewrite; do once after both.
            boolean ok = cache.getIndexes()[19].putFile(
                    group, file, 2, patched, null, false, false, -1, -1);
            System.out.println("id=" + id + " renamed \"" + oldName + "\" -> \"" + newName + "\""
                    + " (idx=" + idx + ", oldLen=" + oldBytes.length + ", newLen=" + newBytes.length
                    + ", dataLen " + data.length + " -> " + patched.length + ", putFile=" + ok + ")");
        }

        boolean rt = cache.getIndexes()[19].rewriteTable();
        System.out.println("rewriteTable(index19)=" + rt);

        // Verify
        for (String[] r : RENAMES) {
            int id = Integer.parseInt(r[0]);
            ItemDefinitions def = ItemDefinitions.getItemDefinition(cache, id);
            System.out.println("verify id=" + id + " name=\"" + def.getName()
                    + "\" modelId=" + def.modelId + " value=" + def.value);
        }
        System.out.println("Done.");
    }

    /** Encodes the opcode-2 name segment: [0x02][modified-UTF8 name bytes][0x00]. */
    private static byte[] encodeNameString(String name) {
        byte[] str = name.getBytes(StandardCharsets.UTF_8);
        byte[] seg = new byte[1 + str.length + 1];
        seg[0] = 0x02;
        System.arraycopy(str, 0, seg, 1, str.length);
        seg[seg.length - 1] = 0x00; // null terminator
        return seg;
    }

    /** Finds the first occurrence of needle in haystack, returns -1 if absent. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Returns a new array with the region [start, start+len) replaced by insert. */
    private static byte[] splice(byte[] src, int start, int len, byte[] insert) {
        byte[] out = new byte[src.length - len + insert.length];
        System.arraycopy(src, 0, out, 0, start);
        System.arraycopy(insert, 0, out, start, insert.length);
        System.arraycopy(src, start + len, out, start + insert.length, src.length - start - len);
        return out;
    }
}
