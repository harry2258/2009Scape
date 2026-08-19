package com.alex.tools.clientCacheUpdater;

import com.alex.store.Store;

/**
 * Clones the Steam battlestaff (11736) -> Smoke battlestaff (14659)
 * and Mystic steam staff (11738) -> Mystic smoke staff (14661)
 * inside index 19 (item definitions) of the 2009scape 530 cache.
 *
 * Performs a raw byte-for-byte copy of the source item's opcode stream
 * to the destination id, then rewrites the index-19 reference table
 * (and the idx255 master entry for index 19) so the new ids are
 * resolvable by both the server's ItemDefinition parser and the
 * client's JS5 on-demand fetcher.
 *
 * Run from the Tools/Frostys Cache Editor directory:
 *   javac -cp bin -d bin src/com/alex/tools/clientCacheUpdater/CloneSmokeStaff.java
 *   java -cp bin com.alex.tools.clientCacheUpdater.CloneSmokeStaff
 *
 * Back up main_file_cache.dat2 / idx19 / idx255 before running.
 */
public class CloneSmokeStaff {

    /** (sourceId, destinationId) pairs to clone. */
    private static final int[][] CLONES = {
            {11736, 14659}, // Steam battlestaff  -> Smoke battlestaff
            {11738, 14661}, // Mystic steam staff -> Mystic smoke staff
    };

    public static void main(String[] args) throws Exception {
        String cachePath = "C:/Users/diya0/IdeaProjects/2009scape/Server/data/cache/";
        Store cache = new Store(cachePath); // opens read/write

        for (int[] pair : CLONES) {
            int src = pair[0];
            int dst = pair[1];
            int srcGroup = src >>> 8;
            int srcFile = src & 0xFF;
            int dstGroup = dst >>> 8;
            int dstFile = dst & 0xFF;

            byte[] data = cache.getIndexes()[19].getFile(srcGroup, srcFile);
            if (data == null) {
                System.out.println("FAIL: source item " + src + " (group " + srcGroup + ", file " + srcFile + ") not found in cache");
                continue;
            }

            // Defer the table rewrite for each file; do one rewriteTable at the end.
            boolean ok = cache.getIndexes()[19].putFile(
                    dstGroup, dstFile, 2, data, null, false, false, -1, -1);
            System.out.println("clone " + src + " -> " + dst
                    + " (g" + srcGroup + "f" + srcFile + " -> g" + dstGroup + "f" + dstFile + ")"
                    + ": putFile=" + ok + ", bytes=" + data.length);
        }

        // Rewrite the index-19 reference table once. This updates idx19's table
        // and writes the new reference table for index 19 into idx255.
        boolean rt = cache.getIndexes()[19].rewriteTable();
        System.out.println("rewriteTable(index19)=" + rt);

        // Sanity: read back the cloned definitions.
        for (int[] pair : CLONES) {
            int dst = pair[1];
            byte[] back = cache.getIndexes()[19].getFile(dst >>> 8, dst & 0xFF);
            System.out.println("readback " + dst + ": " + (back == null ? "NULL" : back.length + " bytes"));
        }

        System.out.println("Done. Restart the server for the changes to take effect.");
    }
}
