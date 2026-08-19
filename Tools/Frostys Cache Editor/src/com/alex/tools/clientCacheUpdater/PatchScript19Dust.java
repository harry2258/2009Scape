package com.alex.tools.clientCacheUpdater;

import com.alex.store.Store;

import java.nio.ByteBuffer;

/**
 * Patches CS2 script 19 to register the Dust battlestaff (14663) and
 * Mystic dust staff (14665) as suppliers of air+earth runes (dust = air+earth),
 * mirroring the smoke-staff patch that added 14659/14661 to air+fire.
 *
 * The current script already contains the smoke-staff entries (14659/14661)
 * in the air and fire blocks. We insert two 45-byte entries (14663, 14665)
 * into the AIR block (at byte offset 259, right before the block terminator
 * "op=0 93") and two into the EARTH block (at byte offset 1297, before its
 * terminator). The earth insertion offset is shifted by +90 to account for
 * the bytes added to the air block.
 *
 * Each entry uses the same 8-instruction / 45-byte template as existing staves:
 *   op=0 94 -> op=0 <staffId> -> op=40 1 -> op=0 0 -> op=10 1 -> op=6 2
 *   -> op=0 99999999 -> op=21 0
 *
 * Instruction count grows by 32 (8 instr/entry x 4 entries), trailer u4 updated.
 * No switch-table fixups (switches=0).
 *
 * Run:
 *   javac -cp bin -d bin src/com/alex/tools/clientCacheUpdater/PatchScript19Dust.java
 *   java -cp bin com.alex.tools.clientCacheUpdater.PatchScript19Dust
 */
public class PatchScript19Dust {

    static final int DUST_BATTLESTAFF = 14663;
    static final int MYSTIC_DUST_STAFF = 14665;
    static final int SCRIPT_ID = 19;

    public static void main(String[] args) throws Exception {
        String cachePath = "C:/Users/diya0/IdeaProjects/2009scape/Server/data/cache/";
        Store cache = new Store(cachePath);
        byte[] raw = cache.getIndexes()[12].getFile(SCRIPT_ID, 0);
        if (raw == null) { System.out.println("script " + SCRIPT_ID + " not found"); return; }
        int len = raw.length;

        ByteBuffer tb = ByteBuffer.wrap(raw);
        tb.position(len - 2);
        int trailerLen = tb.getShort() & 0xFFFF;
        int trailerPos = len - trailerLen - 12 - 2;
        tb.position(trailerPos);
        int instructions = tb.getInt();
        tb.getShort(); tb.getShort(); tb.getShort(); tb.getShort(); // intLocals..stringArgs
        int switches = tb.get() & 0xFF;
        System.out.println("before: len=" + len + " trailerPos=" + trailerPos
                + " instructions=" + instructions + " switches=" + switches);
        if (switches != 0) { System.out.println("ERROR: switch tables present (not handled)"); return; }

        // Guard: don't double-patch.
        if (containsStaffId(raw, trailerPos, DUST_BATTLESTAFF)) {
            System.out.println("dust staff already present in script; aborting to avoid double-patch");
            return;
        }

        int airInsertOff = 259;   // before air-block terminator (op=0 93)
        int earthInsertOff = 1297; // before earth-block terminator (op=0 93)

        byte[] entryDust = buildStaffEntry(DUST_BATTLESTAFF);
        byte[] entryMystic = buildStaffEntry(MYSTIC_DUST_STAFF);

        int grow = 90 + 90; // 180 bytes
        byte[] out = new byte[len + grow];

        // Copy [0 .. airInsertOff)
        System.arraycopy(raw, 0, out, 0, airInsertOff);
        // Air dust entries
        System.arraycopy(entryDust, 0, out, airInsertOff, 45);
        System.arraycopy(entryMystic, 0, out, airInsertOff + 45, 45);
        int p = airInsertOff + 90;
        // Copy [airInsertOff .. earthInsertOff)
        int segLen = earthInsertOff - airInsertOff;
        System.arraycopy(raw, airInsertOff, out, p, segLen);
        p += segLen;
        // Earth dust entries
        System.arraycopy(entryDust, 0, out, p, 45);
        System.arraycopy(entryMystic, 0, out, p + 45, 45);
        p += 90;
        // Copy [earthInsertOff .. end)
        System.arraycopy(raw, earthInsertOff, out, p, len - earthInsertOff);
        p += (len - earthInsertOff);
        if (p != out.length) { System.out.println("ERROR length mismatch p=" + p + " out=" + out.length); return; }

        // Update trailer instruction count (+32) at new trailerPos.
        int newTrailerPos = trailerPos + grow;
        ByteBuffer ob = ByteBuffer.wrap(out);
        ob.position(newTrailerPos);
        ob.putInt(instructions + 32);

        System.out.println("after: len=" + out.length + " newTrailerPos=" + newTrailerPos
                + " instructions=" + (instructions + 32));

        boolean ok = cache.getIndexes()[12].putFile(SCRIPT_ID, 0, 2, out, null, true, true, -1, -1);
        System.out.println("putFile(script19)=" + ok);

        // Verify count.
        byte[] back = cache.getIndexes()[12].getFile(SCRIPT_ID, 0);
        ByteBuffer vb = ByteBuffer.wrap(back);
        vb.position(back.length - 2);
        int tlen = vb.getShort() & 0xFFFF;
        int tpos = back.length - tlen - 12 - 2;
        vb.position(tpos);
        System.out.println("verify: len=" + back.length + " trailerPos=" + tpos + " instructions=" + vb.getInt());
        System.out.println("Done.");
    }

    static boolean containsStaffId(byte[] data, int trailerPos, int staffId) {
        byte[] needle = new byte[]{0, 0, 0, 0,
            (byte)((staffId >> 24) & 0xFF), (byte)((staffId >> 16) & 0xFF),
            (byte)((staffId >> 8) & 0xFF), (byte)(staffId & 0xFF)};
        outer:
        for (int i = 0; i + 6 <= trailerPos; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    static byte[] buildStaffEntry(int staffId) {
        byte[] e = new byte[45];
        int p = 0;
        e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0x5e;
        e[p++]=0; e[p++]=0; e[p++]=(byte)((staffId>>24)&0xFF); e[p++]=(byte)((staffId>>16)&0xFF); e[p++]=(byte)((staffId>>8)&0xFF); e[p++]=(byte)(staffId&0xFF);
        e[p++]=0; e[p++]=0x28; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=1;
        e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0;
        e[p++]=0; e[p++]=0x0a; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=1;
        e[p++]=0; e[p++]=6; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=2;
        e[p++]=0; e[p++]=0; e[p++]=5; e[p++]=(byte)0xf5; e[p++]=(byte)0xe0; e[p++]=(byte)0xff;
        e[p++]=0; e[p++]=0x15; e[p++]=0;
        if (p != 45) throw new RuntimeException("entry len " + p);
        return e;
    }
}