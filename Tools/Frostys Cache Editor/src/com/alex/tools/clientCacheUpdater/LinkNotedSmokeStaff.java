package com.alex.tools.clientCacheUpdater;

import com.alex.loaders.items.ItemDefinitions;
import com.alex.store.Store;

/**
 * Creates the noted variants of the smoke staves and fixes the
 * unnoted<->noted link (opcode 97 = switchNoteItemId) so that:
 *   14659 (Smoke battlestaff)      <-> 14660 (noted)
 *   14661 (Mystic smoke staff)     <-> 14662 (noted)
 *
 * Noted defs are cloned from the steam-staff noted defs (11737/11739),
 * which already carry the correct note-template structure
 * (notedItemId=799 etc.). Only the switchNoteItemId link is repointed.
 *
 * Opcode 97 segment: [0x61][2-byte big-endian short].
 *
 * Run:
 *   javac -cp bin -d bin src/com/alex/tools/clientCacheUpdater/LinkNotedSmokeStaff.java
 *   java -cp bin com.alex.tools.clientCacheUpdater.LinkNotedSmokeStaff
 */
public class LinkNotedSmokeStaff {
    public static void main(String[] args) throws Exception {
        String cachePath = "C:/Users/diya0/IdeaProjects/2009scape/Server/data/cache/";
        Store cache = new Store(cachePath);

        // (srcNoted, dstNoted, dstUnnoted) -- clone noted def, repoint its link to the dst unnoted.
        int[][] notedClones = {
                {11737, 14660, 14659}, // Steam-battlestaff noted -> Smoke-battlestaff noted
                {11739, 14662, 14661}, // Mystic-steam-staff noted -> Mystic-smoke-staff noted
        };
        // (unnotedId, oldLink, newLink) -- repoint the unnoted def's switchNoteItemId.
        int[][] unnotedFixes = {
                {14659, 11737, 14660},
                {14661, 11739, 14662},
        };

        // 1. Clone noted defs and repoint their switchNoteItemId -> dst unnoted.
        for (int[] c : notedClones) {
            int src = c[0], dst = c[1], linkTo = c[2];
            byte[] data = cache.getIndexes()[19].getFile(src >>> 8, src & 0xFF);
            if (data == null) { System.out.println("src noted " + src + " NULL"); continue; }
            byte[] patched = patchOpcode97(data, src, linkTo);
            boolean ok = cache.getIndexes()[19].putFile(dst >>> 8, dst & 0xFF, 2, patched, null, false, false, -1, -1);
            System.out.println("clone noted " + src + " -> " + dst + " link->" + linkTo + " putFile=" + ok);
        }

        // 2. Repoint the unnoted defs' switchNoteItemId -> dst noted.
        for (int[] f : unnotedFixes) {
            int unnoted = f[0], oldLink = f[1], newLink = f[2];
            byte[] data = cache.getIndexes()[19].getFile(unnoted >>> 8, unnoted & 0xFF);
            if (data == null) { System.out.println("unnoted " + unnoted + " NULL"); continue; }
            byte[] patched = patchOpcode97(data, oldLink, newLink);
            boolean ok = cache.getIndexes()[19].putFile(unnoted >>> 8, unnoted & 0xFF, 2, patched, null, false, false, -1, -1);
            System.out.println("fix unnoted " + unnoted + " link " + oldLink + " -> " + newLink + " putFile=" + ok);
        }

        boolean rt = cache.getIndexes()[19].rewriteTable();
        System.out.println("rewriteTable(index19)=" + rt);

        // Verify all four.
        int[] ids = {14659, 14660, 14661, 14662};
        for (int id : ids) {
            byte[] data = cache.getIndexes()[19].getFile(id >>> 8, id & 0xFF);
            ItemDefinitions def = ItemDefinitions.getItemDefinition(cache, id);
            System.out.println("id=" + id + " rawBytes=" + (data==null?"NULL":data.length)
                    + " name=\"" + def.getName() + "\""
                    + " notedItemId=" + def.notedItemId
                    + " switchNoteItemId=" + def.switchNoteItemId
                    + " modelId=" + def.modelId);
        }
        System.out.println("Done.");
    }

    /**
     * Replaces the opcode-97 segment [0x61][oldLink as BE short] with
     * [0x61][newLink as BE short] inside the raw def bytes.
     * If no opcode-97 segment is found (e.g. noted def has none), inserts one.
     */
    static byte[] patchOpcode97(byte[] data, int oldLink, int newLink) {
        byte[] oldSeg = new byte[]{0x61, (byte)((oldLink >> 8) & 0xFF), (byte)(oldLink & 0xFF)};
        byte[] newSeg = new byte[]{0x61, (byte)((newLink >> 8) & 0xFF), (byte)(newLink & 0xFF)};
        int idx = indexOf(data, oldSeg);
        if (idx >= 0) {
            return splice(data, idx, oldSeg.length, newSeg);
        }
        // Old link not present; try to find any opcode-97 segment and replace it.
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0x61) {
                return splice(data, i, 3, newSeg);
            }
        }
        // None present: append before the trailing 0x00 terminator.
        // Find last 0x00 (terminator) -- the def ends with 0x00.
        int end = data.length;
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == 0x00) { end = i; break; }
        }
        byte[] out = new byte[data.length + 3];
        System.arraycopy(data, 0, out, 0, end);
        System.arraycopy(newSeg, 0, out, end, 3);
        System.arraycopy(data, end, out, end + 3, data.length - end);
        return out;
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    static byte[] splice(byte[] src, int start, int len, byte[] insert) {
        byte[] out = new byte[src.length - len + insert.length];
        System.arraycopy(src, 0, out, 0, start);
        System.arraycopy(insert, 0, out, start, insert.length);
        System.arraycopy(src, start + len, out, start + insert.length, src.length - start - len);
        return out;
    }
}
