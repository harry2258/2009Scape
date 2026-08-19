package com.alex.tools.clientCacheUpdater;

import com.alex.loaders.items.ItemDefinitions;
import com.alex.store.Store;

/**
 * Verifies that the cloned smoke-staff item definitions (14659, 14661)
 * decode correctly from the cache after CloneSmokeStaff ran.
 */
public class VerifySmokeStaff {
    public static void main(String[] args) throws Exception {
        String cachePath = "C:/Users/diya0/IdeaProjects/2009scape/Server/data/cache/";
        Store cache = new Store(cachePath);

        int[] ids = {11736, 11738, 14659, 14661};
        for (int id : ids) {
            try {
                ItemDefinitions def = ItemDefinitions.getItemDefinition(cache, id);
                System.out.println("id=" + id
                        + " name=\"" + def.getName() + "\""
                        + " modelId=" + def.modelId
                        + " maleWear(op23)=" + def.opcode23
                        + " femaleWear(op24)=" + def.opcode24
                        + " value=" + def.value);
            } catch (Throwable t) {
                System.out.println("id=" + id + " ERROR: " + t);
            }
        }
    }
}
