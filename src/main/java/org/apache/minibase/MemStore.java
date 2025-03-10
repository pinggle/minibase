package org.apache.minibase;

import org.apache.log4j.Logger;
import org.apache.minibase.DiskStore.MultiIter;
import org.apache.minibase.MStore.SeekIter;
import org.apache.minibase.MiniBase.Flusher;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** 内存存储 */
public class MemStore implements Closeable {

  private static final Logger LOG = Logger.getLogger(MemStore.class);

  // 内存中的活跃跳表占用的空间大小;
  private final AtomicLong dataSize = new AtomicLong();

  // 活跃跳表;
  private volatile ConcurrentSkipListMap<KeyValue, KeyValue> kvMap;
  // 不可变跳表;
  private volatile ConcurrentSkipListMap<KeyValue, KeyValue> snapshot;

  // 更新锁;
  private final ReentrantReadWriteLock updateLock = new ReentrantReadWriteLock();
  // 是否正在从内存刷入磁盘中;
  private final AtomicBoolean isSnapshotFlushing = new AtomicBoolean(false);
  private ExecutorService pool;

  private Config conf;
  private Flusher flusher;

  public MemStore(Config conf, Flusher flusher, ExecutorService pool) {
    this.conf = conf;
    this.flusher = flusher;
    this.pool = pool;

    dataSize.set(0);
    this.kvMap = new ConcurrentSkipListMap<>();
    this.snapshot = null;
  }

  /**
   * 往内存块中写入数据;
   * @param kv  待写入的数据;
   * @throws IOException
   */
  public void add(KeyValue kv) throws IOException {
    // 是否触发刷内存数据到磁盘;(内存块满+正在刷盘+必须阻塞=>抛异常告警)
    flushIfNeeded(true);
    updateLock.readLock().lock();
    try {
      KeyValue prevKeyValue;
      if ((prevKeyValue = kvMap.put(kv, kv)) == null) {
        // 内存跳表中,没有该key;
        dataSize.addAndGet(kv.getSerializeSize());
      } else {
        // 内存跳表中,有该key的历史数据;
        dataSize.addAndGet(kv.getSerializeSize() - prevKeyValue.getSerializeSize());
      }
    } finally {
      updateLock.readLock().unlock();
    }
    // 是否触发刷内存数据到磁盘;(内存块满+正在刷盘+必须阻塞=>抛异常告警)
    flushIfNeeded(false);
  }

  // 是否触发刷内存数据到磁盘;(内存块满+正在刷盘+必须阻塞=>抛异常告警)
  private void flushIfNeeded(boolean shouldBlocking) throws IOException {
    if (getDataSize() > conf.getMaxMemstoreSize()) {
      if (isSnapshotFlushing.get() && shouldBlocking) {
        // 如果正在刷盘中,内存块又写满了,则抛异常;
        throw new IOException(
            "Memstore is full, currentDataSize="
                + dataSize.get()
                + "B, maxMemstoreSize="
                + conf.getMaxMemstoreSize()
                + "B, please wait until the flushing is finished.");
      } else if (isSnapshotFlushing.compareAndSet(false, true)) {
        pool.submit(new FlusherTask());
      }
    }
  }

  public long getDataSize() {
    return dataSize.get();
  }

  public boolean isFlushing() {
    return this.isSnapshotFlushing.get();
  }

  @Override
  public void close() throws IOException {}

  /**
   * 采用双缓存Buffer方式,将内存块数据刷入磁盘;
   */
  private class FlusherTask implements Runnable {
    @Override
    public void run() {
      //TODO 步骤1:内存快照;
      // Step.1 memstore snpashot
      updateLock.writeLock().lock();
      try {
        snapshot = kvMap;
        // TODO MemStoreIter may find the kvMap changed ? should synchronize ?
        kvMap = new ConcurrentSkipListMap<>();
        dataSize.set(0);
      } finally {
        updateLock.writeLock().unlock();
      }

      //TODO 步骤2:刷内存块数据到磁盘;
      // Step.2 Flush the memstore to disk file.
      boolean success = false;
      for (int i = 0; i < conf.getFlushMaxRetries(); i++) {
        try {
          flusher.flush(new IteratorWrapper(snapshot));
          success = true;
        } catch (IOException e) {
          LOG.error(
              "Failed to flush memstore, retries="
                  + i
                  + ", maxFlushRetries="
                  + conf.getFlushMaxRetries(),
              e);
          if (i >= conf.getFlushMaxRetries()) {
            break;
          }
        }
      }

      //TODO 步骤3:清理内存快照数据;
      // Step.3 clear the snapshot.
      if (success) {
        // TODO MemStoreIter may get a NPE because we set null here ? should synchronize ?
        snapshot = null;
        isSnapshotFlushing.compareAndSet(true, false);
      }
    }
  }

  public SeekIter<KeyValue> createIterator() throws IOException {
    return new MemStoreIter(kvMap, snapshot);
  }

  public static class IteratorWrapper implements SeekIter<KeyValue> {

    private SortedMap<KeyValue, KeyValue> sortedMap;
    private Iterator<KeyValue> it;

    public IteratorWrapper(SortedMap<KeyValue, KeyValue> sortedMap) {
      this.sortedMap = sortedMap;
      this.it = sortedMap.values().iterator();
    }

    @Override
    public boolean hasNext() throws IOException {
      return it != null && it.hasNext();
    }

    @Override
    public KeyValue next() throws IOException {
      return it.next();
    }

    @Override
    public void seekTo(KeyValue kv) throws IOException {
      it = sortedMap.tailMap(kv).values().iterator();
    }
  }

  private class MemStoreIter implements SeekIter<KeyValue> {

    private MultiIter it;

    public MemStoreIter(
        NavigableMap<KeyValue, KeyValue> kvSet, NavigableMap<KeyValue, KeyValue> snapshot)
        throws IOException {
      List<IteratorWrapper> inputs = new ArrayList<>();
      if (kvSet != null && kvSet.size() > 0) {
        inputs.add(new IteratorWrapper(kvMap));
      }
      if (snapshot != null && snapshot.size() > 0) {
        inputs.add(new IteratorWrapper(snapshot));
      }
      it = new MultiIter(inputs.toArray(new IteratorWrapper[0]));
    }

    @Override
    public boolean hasNext() throws IOException {
      return it.hasNext();
    }

    @Override
    public KeyValue next() throws IOException {
      return it.next();
    }

    @Override
    public void seekTo(KeyValue kv) throws IOException {
      it.seekTo(kv);
    }
  }
}
