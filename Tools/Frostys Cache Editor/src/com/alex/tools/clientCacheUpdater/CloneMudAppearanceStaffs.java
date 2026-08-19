package com.alex.tools.clientCacheUpdater;

import com.alex.loaders.items.ItemDefinitions;
import com.alex.store.Store;

import java.nio.charset.StandardCharsets;

/**
 * Repoints the smoke staff's appearance to the Mud staff's model, and adds
 * the Dust staff (air+earth) also using the Mud staff's appearance.
 *
 * Operates on cache index 19 (item definitions). Each new/updated def is a
 * raw byte-clone of the Mud battlestaff (6562) or Mystic mud staff (6563)
 * definition, with surgical byte-patches to:
 *   - opcode 2 (name): rename to the smoke/dust name
 *   - opcode 97 (switchNoteItemId): repoint the unnoted<->noted link
 *
 * Item ID plan:
 *   14659 Smoke battlestaff      <- clone of 6562 (Mud battlestaff), rename, link->14660
 *   14660 Smoke battlestaff (n)  <- clone of 6726 (Mud noted), link->14659
 *   14661 Mystic smoke staff     <- clone of 6563 (Mystic mud), rename, link->14662
 *   14662 Mystic smoke staff (n) <- clone of 6727 (Mystic mud noted), link->14661
 *   14663 Dust battlestaff       <- clone of 6562, rename, link->14664
 *   14664 Dust battlestaff (n)   <- clone of 6726, link->14663
 *   14665 Mystic dust staff      <- clone of 6563, rename, link->14666
 *   14666 Mystic dust staff (n)  <- clone of 6727, link->14665
 *
 * Run:
 *   javac -cp bin -d bin src/com/alex/tools/clientCacheUpdater/CloneMudAppearanceStaffs.java
 *   java -cp bin com.alex.tools.clientCacheUpdater.CloneMudAppearanceStaffs
 */
public class CloneMudAppearanceStaffs {

    // (srcUnnoted, srcNoted, dstUnnoted, dstNoted, newName)
    private static final Object[][] PLAN = {
            {6562, 6726, 14659, 14660, "Smoke battlestaff"},
            {6563, 6727, 14661, 14662, "Mystic smoke staff"},
            {6562, 6726, 14663, 14664, "Dust battlestaff"},
            {6563, 6727, 14665, 14666, "Mystic dust staff"},
    };

    public static void main(String[] args) throws Exception {
        String cachePath = "C:/Users/diya0/IdeaProjects/2009scape/Server/data/cache/";
        Store cache = new Store(cachePath);

        for (Object[] p : PLAN) {
            int srcUnnoted = (int) p[0];
            int srcNoted   = (int) p[1];
            int dstUnnoted = (int) p[2];
            int dstNoted   = (int) p[3];
            String newName = (String) p[4];

            // 1. Clone the unnoted def from the mud unnoted src, rename, repoint link -> dstNoted.
            byte[] unnoted = cache.getIndexes()[19].getFile(srcUnnoted >>> 8, srcUnnoted & 0xFF);
            // First strip any old opcode-97 link from the src (mud links to 6726/6727), then set new.
            unnoted = replaceOrAddOpcode97(unnoted, srcNoted, dstNoted);
            unnoted = patchName(unnoted, mudNameOf(srcUnnoted), newName);
            put(cache, dstUnnoted, unnoted);

            // 2. Clone the noted def from the mud noted src, repoint its link -> dstUnnoted.
            byte[] noted = cache.getIndexes()[19].getFile(srcNoted >>> 8, srcNoted & 0xFF);
            noted = replaceOrAddOpcode97(noted, srcUnnoted, dstUnnoted);
            put(cache, dstNoted, noted);

            System.out.println("cloned " + srcUnnoted + "/" + srcNoted
                    + " -> " + dstUnnoted + "/" + dstNoted + " name=\"" + newName + "\"");
        }

        boolean rt = cache.getIndexes()[19].rewriteTable();
        System.out.println("rewriteTable(index19)=" + rt);

        // Verify all 8.
        int[] ids = {14659, 14660, 14661, 14662, 14663, 14664, 14665, 14666};
        for (int id : ids) {
            byte[] data = cache.getIndexes()[19].getFile(id >>> 8, id & 0xFF);
            ItemDefinitions def = ItemDefinitions.getItemDefinition(cache, id);
            System.out.printf("id=%d bytes=%s name=\"%s\" modelId=%d value=%d notedItemId=%d switchNoteItemId=%d%n",
                id, data==null?"NULL":data.length, def.getName(), def.modelId, def.value,
                def.notedItemId, def.switchNoteItemId);
        }
        System.out.println("Done.");
    }

    static void put(Store cache, int id, byte[] data) {
        boolean ok = cache.getIndexes()[19].putFile(id >>> 8, id & 0xFF, 2, data, null, false, false, -1, -1);
        if (!ok) System.out.println("WARN putFile false for id=" + id);
    }

    /** The source mud-staff names we expect to replace. */
    static String mudNameOf(int srcUnnoted) {
        if (srcUnnoted == 6562) return "Mud battlestaff";
        if (srcUnnoted == 6563) return "Mystic mud staff";
        return "";
    }

    /** Patches the opcode-2 name segment [0x02][oldName UTF-8][0x00] -> newName. */
    static byte[] patchName(byte[] data, String oldName, String newName) {
        if (oldName == null || oldName.isEmpty()) return data;
        byte[] oldSeg = encodeNameSegment(oldName);
        byte[] newSeg = encodeNameSegment(newName);
        int idx = indexOf(data, oldSeg);
        if (idx < 0) {
            // Name may already be patched (re-run); try the current name.
            System.out.println("  note: old name \"" + oldName + "\" not found (maybe already renamed)");
            return data;
        }
        return splice(data, idx, oldSeg.length, newSeg);
    }

    static byte[] encodeNameSegment(String name) {
        byte[] str = name.getBytes(StandardCharsets.UTF_8);
        byte[] seg = new byte[1 + str.length + 1];
        seg[0] = 0x02;
        System.arraycopy(str, 0, seg, 1, str.length);
        seg[seg.length - 1] = 0x00;
        return seg;
    }

    /** Replaces the opcode-97 link [0x61][oldLink u2 BE] with [0x61][newLink u2 BE]. */
    static byte[] replaceOrAddOpcode97(byte[] data, int oldLink, int newLink) {
        byte[] oldSeg = new byte[]{0x61, (byte)((oldLink >> 8) & 0xFF), (byte)(oldLink & 0xFF)};
        byte[] newSeg = new byte[]{0x61, (byte)((newLink >> 8) & 0xFF), (byte)(newLink & 0xFF)};
        int idx = indexOf(data, oldSeg);
        if (idx >= 0) return splice(data, idx, 3, newSeg);
        // Old link not present; replace any existing opcode-97 segment.
        for (int i = 0; i + 2 < data.length; i++) {
            if (data[i] == 0x61) return splice(data, i, 3, newSeg);
        }
        // None present: append before the trailing 0x00 terminator.
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