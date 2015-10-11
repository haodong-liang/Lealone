/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.lealone.storage;

import java.util.ArrayList;
import java.util.List;

import org.lealone.common.util.DataUtils;
import org.lealone.storage.btree.BTreeChunk;
import org.lealone.storage.btree.BTreeMap;
import org.lealone.storage.rtree.RTreeMap;
import org.lealone.storage.rtree.SpatialKey;
import org.lealone.storage.type.StringDataType;

public class AOStorageTest extends TestBase {
    public static void main(String[] args) {
        new AOStorageTest().run();
    }

    public static void p(Object o) {
        System.out.println(o);
    }

    public static void p() {
        System.out.println();
    }

    AOStorage storage;
    BTreeMap<String, String> map;
    RTreeMap<String> rmap;
    String storageName = TestBase.TEST_DIR + "aose";

    void run() {
        testPagePos();
        openStorage();
        try {
            openMap();
            // testPut();
            // testSplit();
            // testGet();
            // testCompact();
            // testUnusedChunks();

            // testOpenVersion();

            // testPrintPage();

            testBTreeMap();

            // openRTreeMap();
            // testRTreePut();
        } finally {
            storage.close();
        }
    }

    void testBTreeMap() {
        Object v = null;
        map.clear();

        v = map.put("10", "a");
        p(v);

        v = map.putIfAbsent("10", "a1");
        p(v);

        v = map.putIfAbsent("20", "b");
        p(v);

        v = map.get("20");
        p(v);

        map.clear();

        for (int i = 100; i < 300; i++) {
            map.put("" + i, "value" + i);
        }

        v = map.get("105");
        p(v);

        v = map.getKey(-1);
        p(v);

        v = map.getKey(400);
        p(v);

        v = map.getKey(0); // 第一个
        p(v);

        v = map.getKey(100);
        p(v);

        v = map.getKey(200);
        p(v);

        v = map.getKey(199); // 这才是最后一个，下标从0开始
        p(v);

        v = map.getKeyIndex("100");
        p(v);

        v = map.getKeyIndex("299");
        p(v);

        v = map.getKeyIndex("100a");
        p(v); // -2，因为找不到时，假想它放在"100"之后，并且位置从1开始，此时它就是放在第2个位置，并且取负号
        v = map.getKeyIndex("103a");
        p(v); // -5，因为前面有"100"、"101"、"102"、"103"，占4个位置了，放在"103"之后变-5

        List<String> keyList = map.keyList();
        v = keyList.get(0); // 相当于map.getKey(0);
        p(v);

        v = keyList.indexOf("100"); // 相当于map.getKeyIndex("100");
        p(v);

        try {
            keyList.add("aaa"); // 返回的keyList是只读的
        } catch (Exception e) {
            p(e);
        }

        v = map.firstKey();
        p(v);
        v = map.lastKey();
        p(v);

        v = map.higherKey("101"); // >"101"的最小key
        p(v);
        v = map.ceilingKey("101"); // >="101"的最小key
        p(v);

        v = map.lowerKey("101"); // <"101"的最大key
        p(v);
        v = map.floorKey("101"); // <="101"的最大key
        p(v);

        v = map.replace("100", "value100a", "value100");
        p(v);
        v = map.replace("100", "value100", "value100a");
        p(v);
        v = map.replace("100", "value100a", "value100");
        p(v);

        v = map.replace("100a", "value100a");
        p(v);
        v = map.replace("100", "value100a");
        p(v);
        v = map.get("100");
        p(v);
        v = map.replace("100", "value100");
        p(v);
        v = map.get("100");
        p(v);

        map.clear();
    }

    void testOpenVersion() {
        map.put("10", "a");
        long version = map.commit();
        map.put("10", "b");
        version = map.commit();

        // version和version - 1得到的值一样，因为version此时是最新的版本，它要比root page中的version要大1
        BTreeMap<String, String> m = map.openVersion(version);
        p(m.get("10"));

        m = map.openVersion(version - 1);
        p(m.get("10"));

        m = map.openVersion(version - 2);
        p(m.get("10"));

        m = map.openVersion(1);
        p(m.get("10"));
    }

    void testUnusedChunks() {
        ArrayList<BTreeChunk> unusedChunks = map.getStorage().findUnusedChunks();
        for (BTreeChunk c : unusedChunks)
            p("id=" + c.id + ", pageCountLive=" + c.pageCountLive);

        map.getStorage().freeUnusedChunks();
    }

    void testCompact() {
        map.getStorage().compactRewriteFully();
    }

    void testPagePos() {
        int chunkId = 2;
        int offset = 10;
        int length = 30;
        int type = 1;

        long pos = DataUtils.getPagePos(chunkId, offset, length, type);
        int pageMaxLength = DataUtils.getPageMaxLength(pos);
        assertEquals(chunkId, DataUtils.getPageChunkId(pos));
        assertEquals(offset, DataUtils.getPageOffset(pos));
        assertEquals(32, pageMaxLength);
        assertEquals(type, DataUtils.getPageType(pos));
    }

    void openStorage() {
        AOStorageBuilder builder = new AOStorageBuilder();
        builder.storageName(storageName);
        builder.compress();
        builder.autoCommitDisabled();
        builder.pageSplitSize(1024);
        builder.encryptionKey("mykey".toCharArray());
        // builder.inMemory();
        storage = builder.openStorage();
    }

    void openMap() {
        map = storage.openBTreeMap("test", StringDataType.INSTANCE, StringDataType.INSTANCE);
        p(storage.getMapNames());
    }

    void openRTreeMap() {
        rmap = storage.openRTreeMap("rtest", StringDataType.INSTANCE, 3);
        p(storage.getMapNames());
    }

    void testRTreePut() {
        for (int i = 10; i < 100; i++) {
            rmap.put(new SpatialKey(i, i * 1.0F, i * 2.0F), "value" + i); // TODO 还有bug，h2也有
        }
        rmap.commit();

        p(rmap.size());
        p(new SpatialKey(1, 1 * 1.0F, 1 * 2.0F));
    }

    void testPrintPage() {
        for (int i = 10; i < 200; i++) {
            map.put("" + i, "value" + i);
        }
        map.commit();

        map.printPage();
    }

    void testSplit() {
        map.clear();

        map.put("10", "value10");
        map.put("50", "value50");

        for (int i = 51; i < 100; i += 2) {
            map.put("" + i, "value" + i);
        }
        map.commit();

        for (int i = 11; i < 50; i += 2) {
            map.put("" + i, "value" + i);
        }
        map.commit();

        // for (int i = 10; i < 100; i += 2) {
        // map.put("" + i, "value" + i);
        // }
        //
        // map.commit();

        // for (int i = 11; i < 100; i += 2) {
        // map.put("" + i, "value" + i);
        // }
        //
        // map.commit();
    }

    void testPut() {
        for (int i = 10; i < 100; i++) {
            map.put("" + i, "value" + i);
        }
        long version = map.commit();

        map.commit();

        p("version=" + version);

        // for (int i = 100; i < 200; i++) {
        // map.put("" + i, "value" + i);
        // }

        map.put("" + 10, "value" + 10);

        map.put("" + 30, "value" + 30);

        version = map.commit();
        p("version=" + version);

        map.put("" + 30, "value" + 40);

        map.rollback();

        p(map.get("" + 30));

        // map.rollbackTo(version - 2);

        //
        // map.remove("10");
        p(map.size());
    }

    void testGet() {
        p(map.get("50"));

        String key = map.firstKey();
        p(key);

        key = map.lastKey();
        p(key);

        key = map.getKey(20);
        p(key);

        long index = map.getKeyIndex("30");
        p(index);

        index = map.getKeyIndex("300");
        p(index);

        key = map.higherKey("30");
        p(key);
        key = map.ceilingKey("30");
        p(key);

        key = map.floorKey("30");
        p(key);
        key = map.lowerKey("30");
        p(key);

        // for (String k : map.keyList())
        // p(k);
    }
}