package com.alex.tools.clientCacheUpdater;

import com.alex.store.Store;

import java.nio.ByteBuffer;

/**
 * Patches CS2 script 19 (the spellbook staff->rune mapping) to make the
 * Smoke battlestaff (14659) and Mystic smoke staff (14661) supply
 * fire+air runes, so the client UI shows "infinite" fire+air and stops
 * greying out smoke/fire spells when either staff is equipped.
 *
 * The script has 4 rune-blocks (air/water/earth/fire), each listing the
 * staff item ids that supply that rune. We insert two 45-byte staff
 * entries (14659 and 14661) into the AIR block and two into the FIRE
 * block, using the exact byte template of the existing entries:
 *
 *   00 00 00 00 00 5e          op=0  operand=94
 *   00 00 00 00 <staffId u4>   op=0  operand=<staffId>
 *   00 28 00 00 00 01          op=40 operand=1 (u1)
 *   00 00 00 00 00 00          op=0  operand=0
 *   00 0a 00 00 00 01          op=10 operand=1 (u1)
 *   00 06 00 00 00 02          op=6  operand=2 (u1)
 *   00 00 05 f5 e0 ff          op=0  operand=99999999
 *   00 15 00                   op=21 operand=0 (u1)
 *
 * Each entry = 45 bytes, 8 instructions. We add 4 entries total
 * (2 in air, 2 in fire) = 180 bytes, 32 instructions.
 *
 * The trailer's instruction count (u4 at trailerPos) is incremented by 32.
 * No switch tables exist (switches=0), so no offset fixups are needed.
 * The trailer length field (last u2) is unchanged (trailer size doesn't
 * change; only the instruction stream grows, which shifts trailerPos).
 *
 * Run:
 *   javac -cp bin -d bin src/com/alex/tools/clientCacheUpdater/PatchScript19.java
 *   java -cp bin com.alex.tools.clientCacheUpdater.PatchScript19
 */
public class PatchScript19 {

    static final int SMOKE_BATTLESTAFF = 14659;
    static final int MYSTIC_SMOKE_STAFF = 14661;
    static final int SCRIPT_ID = 19;

    public static void main(String[] args) throws Exception {
        String cachePath = "C:/Users/diya0/IdeaProjects/2009scape/Server/data/cache/";
        Store cache = new Store(cachePath);
        byte[] raw = cache.getIndexes()[12].getFile(SCRIPT_ID, 0);
        if (raw == null) { System.out.println("script " + SCRIPT_ID + " not found"); return; }
        int len = raw.length;

        // Parse trailer (same as client decoder).
        ByteBuffer tb = ByteBuffer.wrap(raw);
        tb.position(len - 2);
        int trailerLen = tb.getShort() & 0xFFFF;
        int trailerPos = len - trailerLen - 12 - 2;
        tb.position(trailerPos);
        int instructions = tb.getInt();
        int intLocals = tb.getShort() & 0xFFFF;
        int stringLocals = tb.getShort() & 0xFFFF;
        int intArgs = tb.getShort() & 0xFFFF;
        int stringArgs = tb.getShort() & 0xFFFF;
        int switches = tb.get() & 0xFF;
        System.out.println("before: len=" + len + " trailerLen=" + trailerLen
                + " trailerPos=" + trailerPos + " instructions=" + instructions
                + " switches=" + switches);
        if (switches != 0) { System.out.println("ERROR: switch tables present, offset fixups needed (not handled)"); return; }

        // Locate the insertion points by re-decoding instructions to find the
        // byte offset right after the last staff entry in the AIR and FIRE blocks.
        // AIR block: last staff is "Mystic air staff" (1405); block ends before the
        //   "op=0 93" instruction that follows it.
        // FIRE block: last staff is "Mystic steam staff" (11738); block ends before
        //   the "op=0 93" instruction that follows it.
        // We find these by scanning for the staff ids.
        int airInsertOff = -1;  // offset of the instruction right after the last air staff entry
        int fireInsertOff = -1; // offset of the instruction right after the last fire staff entry
        ByteBuffer ib = ByteBuffer.wrap(raw);
        ib.position(0);
        // skip name (null-terminated)
        while (ib.position() < trailerPos) { if ((ib.get() & 0xFF) == 0) break; }
        int prevStaffOff = -1;
        int blockRune = -1;
        int lastStaffEntryEndOff = -1;
        // We track, per block, the offset where the next non-staff instruction begins.
        // Simpler: find the offset of the "op=0 93" instruction that comes right
        // after the air staffs, and the same for fire staffs.
        // Air staffs: 1381,1397,1405 ; Fire staffs: 1387,1393,1401,3053,3054,11736,11738
        // The block terminator is "op=0 93" (the value 93 is a marker).
        // We scan instructions; when we see op=0 operand=<runeId> we note a block start;
        // within a block, staff entries are "op=0 94" followed by "op=0 <staffId>".
        // The instruction right after the last staff entry's 8 instrs is the insert point.
        int instrCount = 0;
        int[] blockStartRuneOff = new int[4]; // air,water,earth,fire block rune offset
        int[] blockInsertOff = new int[4];
        java.util.Map<Integer,Integer> runeToBlock = new java.util.HashMap<>();
        runeToBlock.put(556, 0); // air
        runeToBlock.put(555, 1); // water
        runeToBlock.put(557, 2); // earth
        runeToBlock.put(554, 3); // fire
        int currentBlock = -1;
        int lastStaffIdOff = -1; // offset of the "op=0 <staffId>" instruction
        int entriesInBlock = 0;
        while (ib.position() < trailerPos && instrCount < instructions) {
            int instrOff = ib.position();
            int op = ib.getShort() & 0xFFFF;
            int operand;
            if (op == 3) { while (ib.position() < trailerPos) { if ((ib.get() & 0xFF) == 0) break; } operand = 0; }
            else if (op >= 100 || op == 21 || op == 38 || op == 39) { operand = ib.get() & 0xFF; }
            else { operand = ib.getInt(); }

            if (op == 0 && runeToBlock.containsKey(operand) && instrCount > 0 && entriesInBlock > 0) {
                // We hit the next block's rune (or a re-reference); the previous block ended.
                // Actually the rune appears at block start AND later; use a simpler heuristic below.
            }
            if (op == 0 && runeToBlock.containsKey(operand)) {
                // could be block start; remember
            }
            if (op == 0 && (operand == SMOKE_BATTLESTAFF || operand == MYSTIC_SMOKE_STAFF)) {
                System.out.println("ALREADY PRESENT: staff " + operand + " at off " + instrOff);
            }
            instrCount++;
        }
        // The above scan is complex; instead, hardcode the offsets we already decoded:
        // Air block last staff (Mystic air staff 1405) entry: op=0 94 at off 124,
        //   8 instructions ending at off 169 (op=0 93). So air insert = 169.
        // Fire block last staff (Mystic steam staff 11738) entry ends at off 1726
        //   (op=0 93). So fire insert = 1726.
        airInsertOff = 169;
        fireInsertOff = 1726;

        System.out.println("airInsertOff=" + airInsertOff + " fireInsertOff=" + fireInsertOff);

        // Build the 45-byte staff entry for a given staff id.
        byte[] entrySmoke = buildStaffEntry(SMOKE_BATTLESTAFF);
        byte[] entryMystic = buildStaffEntry(MYSTIC_SMOKE_STAFF);

        // Insert into AIR block: two entries (90 bytes) at airInsertOff.
        // Insert into FIRE block: two entries (90 bytes) at fireInsertOff + 90 (shifted by air insertion).
        int fireInsertOffShifted = fireInsertOff + 90;

        int grow = 90 + 90; // 180 bytes
        byte[] out = new byte[len + grow];

        // Copy [0 .. airInsertOff)
        System.arraycopy(raw, 0, out, 0, airInsertOff);
        // Insert air entries
        System.arraycopy(entrySmoke, 0, out, airInsertOff, 45);
        System.arraycopy(entryMystic, 0, out, airInsertOff + 45, 45);
        int p = airInsertOff + 90;
        // Copy [airInsertOff .. fireInsertOff)
        int segLen = fireInsertOff - airInsertOff;
        System.arraycopy(raw, airInsertOff, out, p, segLen);
        p += segLen;
        // Insert fire entries
        System.arraycopy(entrySmoke, 0, out, p, 45);
        System.arraycopy(entryMystic, 0, out, p + 45, 45);
        p += 90;
        // Copy [fireInsertOff .. end)
        System.arraycopy(raw, fireInsertOff, out, p, len - fireInsertOff);
        p += (len - fireInsertOff);
        // p should == out.length
        if (p != out.length) { System.out.println("ERROR: length mismatch p=" + p + " out.len=" + out.length); return; }

        // Update the trailer's instruction count (+32) at the new trailerPos.
        // trailerPos shifts by `grow`.
        int newTrailerPos = trailerPos + grow;
        ByteBuffer ob = ByteBuffer.wrap(out);
        ob.position(newTrailerPos);
        ob.putInt(instructions + 32);
        // remaining trailer fields unchanged (intLocals, stringLocals, intArgs, stringArgs, switches)
        // The trailer length field (last u2) is unchanged since trailer size is the same.

        System.out.println("after: len=" + out.length + " newTrailerPos=" + newTrailerPos
                + " instructions=" + (instructions + 32));

        // Write back to cache archive 12.
        boolean ok = cache.getIndexes()[12].putFile(SCRIPT_ID, 0, 2, out, null, true, true, -1, -1);
        System.out.println("putFile(script19)=" + ok);

        // Verify by re-reading and decoding count.
        byte[] back = cache.getIndexes()[12].getFile(SCRIPT_ID, 0);
        ByteBuffer vb = ByteBuffer.wrap(back);
        vb.position(back.length - 2);
        int tlen = vb.getShort() & 0xFFFF;
        int tpos = back.length - tlen - 12 - 2;
        vb.position(tpos);
        int newInstr = vb.getInt();
        System.out.println("verify: len=" + back.length + " trailerPos=" + tpos + " instructions=" + newInstr);
        System.out.println("Done. Restart server (and client will fetch updated script 19 via JS5).");
    }

    /** Builds the 45-byte staff entry for the given staff item id. */
    static byte[] buildStaffEntry(int staffId) {
        byte[] e = new byte[45];
        int p = 0;
        // op=0 operand=94
        e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0x5e;
        // op=0 operand=staffId (u4 BE)
        e[p++]=0; e[p++]=0; e[p++]=(byte)((staffId>>24)&0xFF); e[p++]=(byte)((staffId>>16)&0xFF); e[p++]=(byte)((staffId>>8)&0xFF); e[p++]=(byte)(staffId&0xFF);
        // op=40 (0x28) operand=1 (u1)
        e[p++]=0; e[p++]=0x28; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=1;
        // op=0 operand=0
        e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=0;
        // op=10 (0x0a) operand=1 (u1)
        e[p++]=0; e[p++]=0x0a; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=1;
        // op=6 operand=2 (u1)
        e[p++]=0; e[p++]=6; e[p++]=0; e[p++]=0; e[p++]=0; e[p++]=2;
        // op=0 operand=99999999 (0x05F5E0FF)
        e[p++]=0; e[p++]=0; e[p++]=5; e[p++]=(byte)0xf5; e[p++]=(byte)0xe0; e[p++]=(byte)0xff;
        // op=21 (0x15) operand=0 (u1)
        e[p++]=0; e[p++]=0x15; e[p++]=0;
        if (p != 45) throw new RuntimeException("entry length " + p);
        return e;
    }
}
