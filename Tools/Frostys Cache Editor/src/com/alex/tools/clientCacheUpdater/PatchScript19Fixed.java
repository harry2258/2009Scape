package com.alex.tools.clientCacheUpdater;

import com.alex.store.Store;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Re-patches CS2 script 19 from the ORIGINAL (unpatched) version to register
 * the Smoke and Dust staves as rune suppliers, WITH correct relative-jump
 * offset fixups.
 *
 * Root cause of the previous crash: opcodes 6/7/8/9/10/31/32 use RELATIVE
 * jump operands (pc += operand, in instruction counts). Inserting instructions
 * mid-script without adjusting those operands left jump targets pointing at
 * the wrong instructions, eventually reading opcode -1 -> crash.
 *
 * This tool:
 *   1. Reads the ORIGINAL script 19 from a source cache (path given as arg[0],
 *      default /tmp/origcache2/).
 *   2. Parses all 471 instructions into a list (opcode, operand, isString).
 *   3. Inserts staff entries at the correct INSTRUCTION indices:
 *        Air block  (rune 556): insert at instr 30 -> 4 entries (smoke+dust) = 32 instr
 *        Earth block(rune 557): insert at instr 218 -> 2 entries (dust) = 16 instr
 *        Fire block (rune 554): insert at instr 312 -> 2 entries (smoke) = 16 instr
 *   4. Fixes every relative-jump operand whose source/target straddle an
 *      insertion point, using an old-index -> new-index map.
 *   5. Re-encodes the instruction stream + trailer and writes to the LIVE cache.
 *
 * Run:
 *   javac -cp bin -d bin src/com/alex/tools/clientCacheUpdater/PatchScript19Fixed.java
 *   java -cp bin com.alex.tools.clientCacheUpdater.PatchScript19Fixed [origCachePath] [liveCachePath]
 */
public class PatchScript19Fixed {

    static final int SCRIPT_ID = 19;

    // Relative-jump opcodes: operand = instruction count to add to pc.
    static final Set<Integer> REL_JUMPS = new HashSet<>();
    static {
        for (int op : new int[]{6, 7, 8, 9, 10, 31, 32}) REL_JUMPS.add(op);
    }

    // (staffId) entries to insert. Each entry = 8 instructions.
    static final int[][] AIR_ENTRIES = {
            {14659}, {14661}, // smoke
            {14663}, {14665}, // dust
    };
    static final int[][] EARTH_ENTRIES = {
            {14663}, {14665}, // dust
    };
    static final int[][] FIRE_ENTRIES = {
            {14659}, {14661}, // smoke
    };

    // Insertion instruction indices in the ORIGINAL script.
    static final int AIR_INSERT_INSTR = 30;
    static final int EARTH_INSERT_INSTR = 218;
    static final int FIRE_INSERT_INSTR = 312;

    static class Instr {
        int opcode;
        int intOperand;
        String strOperand;
        boolean isString;
        // original instruction index (before insertion)
        int origIdx;
    }

    public static void main(String[] args) throws Exception {
        String origPath = args.length > 0 ? args[0] : "/tmp/origcache2/";
        String livePath = args.length > 1 ? args[1] : "C:/Users/diya0/IdeaProjects/2009scape/Server/data/cache/";

        Store origCache = new Store(origPath);
        byte[] raw = origCache.getIndexes()[12].getFile(SCRIPT_ID, 0);
        if (raw == null) { System.out.println("script " + SCRIPT_ID + " not found in " + origPath); return; }
        int len = raw.length;

        // Parse trailer.
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
        System.out.println("original: len=" + len + " trailerPos=" + trailerPos
                + " instructions=" + instructions + " switches=" + switches);
        if (switches != 0) { System.out.println("ERROR: switch tables not handled"); return; }

        // Parse instructions.
        ByteBuffer b = ByteBuffer.wrap(raw);
        b.position(0);
        // skip name (null-terminated)
        while (b.position() < trailerPos) { if ((b.get() & 0xFF) == 0) break; }

        List<Instr> instrs = new ArrayList<>();
        int idx = 0;
        while (b.position() < trailerPos && idx < instructions) {
            Instr ins = new Instr();
            ins.origIdx = idx;
            int op = b.getShort() & 0xFFFF;
            ins.opcode = op;
            if (op == 3) {
                StringBuilder sb = new StringBuilder();
                while (b.position() < trailerPos) { int ch = b.get() & 0xFF; if (ch == 0) break; sb.append((char) ch); }
                ins.strOperand = sb.toString();
                ins.isString = true;
            } else if (op >= 100 || op == 21 || op == 38 || op == 39) {
                ins.intOperand = b.get() & 0xFF;
            } else {
                ins.intOperand = b.getInt();
            }
            instrs.add(ins);
            idx++;
        }
        System.out.println("parsed " + instrs.size() + " instructions");

        // Build the new instruction list with insertions.
        // We insert at the original instruction indices. Process in order of
        // increasing original index, tracking how many instructions we've added
        // so far (the new-index shift).
        List<Instr> result = new ArrayList<>();
        int[] insertPoints = {AIR_INSERT_INSTR, EARTH_INSERT_INSTR, FIRE_INSERT_INSTR};
        int[][][] entrySets = {AIR_ENTRIES, EARTH_ENTRIES, FIRE_ENTRIES};
        int nextInsert = 0;

        for (Instr ins : instrs) {
            // Check if we should insert before this instruction.
            while (nextInsert < insertPoints.length && ins.origIdx == insertPoints[nextInsert]) {
                int[][] entries = entrySets[nextInsert];
                for (int[] entry : entries) {
                    int staffId = entry[0];
                    for (Instr e : buildStaffEntryInstrs(staffId)) {
                        e.origIdx = -1; // mark as inserted (no original index)
                        result.add(e);
                    }
                }
                nextInsert++;
            }
            result.add(ins);
        }
        int newInstrCount = result.size();
        System.out.println("new instruction count: " + newInstrCount
                + " (was " + instructions + ", +" + (newInstrCount - instructions) + ")");

        // Build old-idx -> new-idx map (for original instructions only).
        int[] oldToNew = new int[instructions];
        for (int i = 0; i < result.size(); i++) {
            Instr ins = result.get(i);
            if (ins.origIdx >= 0) {
                oldToNew[ins.origIdx] = i;
            }
        }

        // Fix relative jumps: for a jump at original instruction S with operand
        // V (target = S + V in the original), the new operand must be
        //   newTarget - newSource = (oldToNew[S+V] - oldToNew[S]).
        int fixed = 0;
        for (Instr ins : result) {
            if (ins.origIdx >= 0 && REL_JUMPS.contains(ins.opcode)) {
                int oldSource = ins.origIdx;
                int oldTarget = oldSource + ins.intOperand;
                int newSource = oldToNew[oldSource];
                int newTarget = (oldTarget < instructions) ? oldToNew[oldTarget] : newInstrCount;
                int newOperand = newTarget - newSource;
                if (newOperand != ins.intOperand) {
                    System.out.println("  fix jump: instr " + oldSource + " (new " + newSource
                            + ") op=" + ins.opcode + " operand " + ins.intOperand + " -> " + newOperand
                            + " (old target " + oldTarget + " -> new " + newTarget + ")");
                    ins.intOperand = newOperand;
                    fixed++;
                }
            }
        }
        System.out.println("fixed " + fixed + " relative jump operands");

        // Re-encode the instruction stream.
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        // name (empty, null-terminated)
        bos.write(0);
        for (Instr ins : result) {
            // opcode: u2 BE
            bos.write((ins.opcode >> 8) & 0xFF);
            bos.write(ins.opcode & 0xFF);
            if (ins.isString) {
                byte[] sb = ins.strOperand.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bos.write(sb, 0, sb.length);
                bos.write(0); // null terminator
            } else if (ins.opcode >= 100 || ins.opcode == 21 || ins.opcode == 38 || ins.opcode == 39) {
                // u1 operand
                bos.write(ins.intOperand & 0xFF);
            } else {
                // u4 BE operand
                bos.write((ins.intOperand >> 24) & 0xFF);
                bos.write((ins.intOperand >> 16) & 0xFF);
                bos.write((ins.intOperand >> 8) & 0xFF);
                bos.write(ins.intOperand & 0xFF);
            }
        }
        byte[] instrBytes = bos.toByteArray();
        System.out.println("encoded instruction stream: " + instrBytes.length + " bytes");

        // Build trailer (same format, updated instruction count).
        java.io.ByteArrayOutputStream trailer = new java.io.ByteArrayOutputStream();
        writeU4(trailer, newInstrCount);
        writeU2(trailer, intLocals);
        writeU2(trailer, stringLocals);
        writeU2(trailer, intArgs);
        writeU2(trailer, stringArgs);
        trailer.write(switches); // u1
        // no switch tables (switches=0)
        byte[] trailerBytes = trailer.toByteArray();

        // Final script = instrBytes + trailerBytes + trailerLen(u2)
        // trailerLen = trailerBytes.length (the "trailer length" field counts the
        // bytes between trailerPos and the final u2, exclusive of the final u2
        // itself per the client decoder: trailerPos = len - trailerLen - 12 - 2).
        // The 12 = the fixed fields (instructions u4 + 4x u2 = 4+8=12). The 2 =
        // the trailerLen u2 itself. So trailerLen = switches-size-only portion = 0
        // when switches=0. Let's verify: original trailerLen=1, switches=0... but
        // 1 != 0. Let me re-derive.
        // Original: len=2641, trailerLen=1, trailerPos = 2641-1-12-2 = 2626.
        //   bytes 2626..2641 = 15 bytes. trailer fields = 4+2+2+2+2+1 = 13 bytes
        //   (instructions+intLocals+stringLocals+intArgs+stringArgs+switches).
        //   Then 15-13 = 2 bytes = the final trailerLen u2. So trailerLen=1 means
        //   1 byte of switch-table data? No, switches=0 means no switch tables.
        //   Actually trailerLen counts something else. Let me just preserve the
        //   original trailer byte-for-byte except the instruction count, since
        //   the non-instruction trailer fields don't change.
        byte[] newScript = new byte[instrBytes.length + (len - trailerPos)];
        System.arraycopy(instrBytes, 0, newScript, 0, instrBytes.length);
        // Copy the original trailer bytes (from trailerPos to end), but patch the
        // instruction count (first u4).
        System.arraycopy(raw, trailerPos, newScript, instrBytes.length, len - trailerPos);
        // Patch instruction count at the new trailerPos.
        int newTrailerPos = instrBytes.length;
        newScript[newTrailerPos]     = (byte)((newInstrCount >> 24) & 0xFF);
        newScript[newTrailerPos + 1] = (byte)((newInstrCount >> 16) & 0xFF);
        newScript[newTrailerPos + 2] = (byte)((newInstrCount >> 8) & 0xFF);
        newScript[newTrailerPos + 3] = (byte)(newInstrCount & 0xFF);

        System.out.println("new script: " + newScript.length + " bytes"
                + " (was " + len + "), newTrailerPos=" + newTrailerPos);

        // Write to LIVE cache.
        Store liveCache = new Store(livePath);
        boolean ok = liveCache.getIndexes()[12].putFile(SCRIPT_ID, 0, 2, newScript, null, true, true, -1, -1);
        System.out.println("putFile(live script19)=" + ok);

        // Verify: re-read and decode count.
        byte[] back = liveCache.getIndexes()[12].getFile(SCRIPT_ID, 0);
        ByteBuffer vb = ByteBuffer.wrap(back);
        vb.position(back.length - 2);
        int tlen = vb.getShort() & 0xFFFF;
        int tpos = back.length - tlen - 12 - 2;
        vb.position(tpos);
        System.out.println("verify: len=" + back.length + " trailerPos=" + tpos
                + " instructions=" + vb.getInt());
        System.out.println("Done.");
    }

    /** Builds the 8 instructions for a staff entry (opcode/operand only). */
    static List<Instr> buildStaffEntryInstrs(int staffId) {
        List<Instr> list = new ArrayList<>();
        list.add(mk(0, 94));
        list.add(mk(0, staffId));
        list.add(mk(40, 1));
        list.add(mk(0, 0));
        list.add(mk(10, 1));
        list.add(mk(6, 2));
        list.add(mk(0, 99999999));
        list.add(mk(21, 0));
        return list;
    }

    static Instr mk(int opcode, int operand) {
        Instr ins = new Instr();
        ins.opcode = opcode;
        ins.intOperand = operand;
        ins.isString = false;
        return ins;
    }

    static void writeU2(java.io.ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }
    static void writeU4(java.io.ByteArrayOutputStream o, int v) {
        o.write((v >> 24) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }
}
