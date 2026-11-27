package dev.mattest.supplychain.service.impl;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缩略图内存缓存。
 *
 * <p>按字节总量淘汰而非条目数：缩略图大小差异很大（20 KB 到 300 KB），按条数限制
 * 会让一轮大图把堆占满。访问顺序为 LRU，超限时从最久未使用的开始丢。
 *
 * <p>故意不引入 Caffeine：一个 40 行的有界 Map 已足够，省一个依赖就少一处构建风险。
 */
@Component
public class ThumbnailCache {
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(256, 0.75f, true);
    private long totalBytes;

    public synchronized byte[] get(String key) {
        return entries.get(key);
    }

    public synchronized void put(String key, byte[] value) {
        if (value == null) {
            return;
        }
        byte[] previous = entries.put(key, value);
        if (previous != null) {
            totalBytes -= previous.length;
        }
        totalBytes += value.length;
        Iterator<Map.Entry<String, byte[]>> iterator = entries.entrySet().iterator();
        while (totalBytes > MAX_BYTES && iterator.hasNext()) {
            Map.Entry<String, byte[]> eldest = iterator.next();
            totalBytes -= eldest.getValue().length;
            iterator.remove();
        }
    }

    public synchronized long cachedBytes() {
        return totalBytes;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
        totalBytes = 0;
    }
}
