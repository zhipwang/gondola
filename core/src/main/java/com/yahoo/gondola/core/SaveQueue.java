/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola.core;

import com.yahoo.gondola.*;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * This class parallelizes the insertion of log entries into the storage system. The storage system is assumed to
 * support insertion of records with any index and this class will track the latest contiguous index that raft can use
 * to advance the commit index. <p> N threads are used to write to storage in parallel. When a thread is done, it
 * compares the index that it just stored with savedIndex. If it equals to savedIndex + 1, it advances savedIndex.
 * Otherwise, it will store it's index into saved. <p> After a thread advances savedIndex, it checks if the saved
 * contains savedIndex + 1. If so, it removes the entry from saved and advances savedIndex. It continues checking and
 * removing contiguous entries until there are no more.
 */
public class SaveQueue {

    final static Logger logger = LoggerFactory.getLogger(SaveQueue.class);
    final Gondola gondola;
    final Storage storage;
    final Stats stats;
    final CoreMember cmember;

    // Lock to synchronize saved and savedIndex
    final ReentrantLock lock = new ReentrantLock();

    // Signaled when saved index has been initialized
    final Condition indexInitialized = lock.newCondition();

    // Signaled when items are available in the queue
    final Condition queueEmpty = lock.newCondition();

    // Set to true after initSavedIndex() is called successfully.
    boolean initialized = false;

    // @lock The highest contiguous index that has been saved.
    int savedIndex = 0;

    // @lock Contains all indexes currently being saved
    Set<Integer> saving = new HashSet<>();

    // @lock Contains indices that have been saved before the saved index.
    // It is a concurrent hashmap because various traces dump its contents outside of the lock and would get concurrent
    // modification exception otherwise. <key=index, value=term>
    Map<Integer, Integer> saved = new ConcurrentHashMap<>();

    // @lock The highest term saved so far
    int lastTerm = 0;

    // List of threads running in this class
    List<Thread> threads = new ArrayList<>();

    // The number of workers waiting for a message from the queue
    int numWaiters = 0;

    // Contains log entries that need to be saved
    BlockingQueue<Message> workQueue = new LinkedBlockingQueue<>();

    // Used to parse and process incoming messages
    MessageHandler handler = new MyMessageHandler();

    // Holds the maximum gap that can occur between the last continguous record written and the last record written
    int maxGap;

    // Config variables
    boolean storageTracing;
    int numWorkers;

    SaveQueue(Gondola gondola, CoreMember cmember) throws GondolaException {
        this.gondola = gondola;
        this.cmember = cmember;
        gondola.getConfig().registerForUpdates(configListener);
        storage = gondola.getStorage();
        stats = gondola.getStats();
        numWorkers = gondola.getConfig().getInt("storage.save_queue_workers");

        String address = storage.getAddress(cmember.memberId);
        if (address != null && !address.equals(gondola.getNetwork().getAddress())
                && gondola.getNetwork().isActive(address)) {
            throw new IllegalStateException(String.format("[%s-%s] Process %s at address %s is currently using storage",
                    gondola.getHostId(), cmember.memberId, gondola.getProcessId(),
                    address));
        }
        storage.setAddress(cmember.memberId, gondola.getNetwork().getAddress());
        storage.setPid(cmember.memberId, gondola.getProcessId());

        initSavedIndex();
    }

    /*
     * Called at the time of registration and whenever the config file changes.
     */
    Consumer<Config> configListener = config -> {
        storageTracing = config.getBoolean("gondola.tracing.storage");
    };

    public void start() {
        // Create worker threads
        if (threads.size() > 0) {
            throw new IllegalStateException("start() can only be called once");
        }
        for (int i = 0; i < numWorkers; i++) {
            threads.add(new Worker(i));
        }
        threads.forEach(t -> t.start());
    }

    public boolean stop() {
        savedIndex = 0;
        return Utils.stopThreads(threads);
    }

    /**
     * Adds the message to the queue to be saved. The savedIndex will be advanced if the message is successfully
     * stored.
     *
     * @throw Exception if an exception occurred while inserting a record into storage.
     */
    public void add(Message message) {
        message.acquire();
        lock.lock();
        try {
            workQueue.add(message);
            queueEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically copies the latest saved term and index into ti.
     */
    public void getLatestWait(Rid rid) throws InterruptedException {
        lock.lock();
        try {
            while (!initialized) {
                indexInitialized.await();
            }
            rid.term = lastTerm;
            rid.index = savedIndex;
        } finally {
            lock.unlock();
        }
    }

    public void getLatest(Rid rid) {
        lock.lock();
        try {
            if (!initialized) {
                // The settle() method call must first succeed before this method can be used.
                throw new IllegalStateException("The saved index has not been initialized yet");
            }
            rid.term = lastTerm;
            rid.index = savedIndex;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of messages in the queue to be processed.
     */
    public int size() {
        return workQueue.size();
    }

    /**
     * Called whenever the Member's Raft role changes. Discards any pending operations. Blocks until the operation is
     * complete.
     *
     * @param rid is updated with the latest stored term and index
     */
    public void settle(Rid rid) throws GondolaException {
        logger.info("[{}-{}] Settling storage. workQ={} waiters={} maxGap={}",
                gondola.getHostId(), cmember.memberId, workQueue.size(), numWaiters, maxGap);

        // Wait until all worker threads are blocked on the queue. TODO: handle case where a worker is hung
        lock.lock();
        try {
            // Clear the pending messages
            Message message = null;
            while ((message = workQueue.poll()) != null) {
                message.release();
            }

            // Now wait for all the threads to stop
            while (threads.size() > 0 && numWaiters < numWorkers) {
                queueEmpty.await(100, TimeUnit.MILLISECONDS); // Same as sleep except the lock is released
            }
        } catch (InterruptedException e) {
            return;
        } finally {
            lock.unlock();
        }

        initSavedIndex();
        getLatest(rid);
    }

    /**
     * Deletes all the entries in the log. Used by a member when entering slave mode.
     */
    public void truncate() throws GondolaException {
        logger.info("[{}-{}] Deleting all records in the log", gondola.getHostId(), cmember.memberId);
        LogEntry entry = storage.getLastLogEntry(cmember.memberId);
        if (entry != null) {
            deleteFrom(1, entry.index);
        }

        // Pick up the fresh new log state
        Rid rid = new Rid();
        lastTerm = 0;
        savedIndex = 0;
        settle(rid);
        assert rid.index == 0 && rid.term == 0 : "Slave mode did not successfully clear the log";
    }

    /**
     * Reinitializes the savedIndex based on the entries currently in the log.
     */
    void initSavedIndex() throws GondolaException {
        lock.lock();
        try {
            // Don't update the saved rid in case of errors
            int newLastTerm = lastTerm;
            int newSavedIndex = savedIndex;

            // Get the latest entry, if any
            LogEntry entry = storage.getLastLogEntry(cmember.memberId);
            int lastIndex = 0;
            if (entry != null) {
                newLastTerm = entry.term;
                lastIndex = entry.index;
                entry.release();
            }

            // Quick safety check to see if another process might be sharing the same tables
            String pid = storage.getPid(cmember.memberId);
            if (pid != null && !gondola.getProcessId().equals(pid)) {
                logger.warn("[{}-{}] SaveQueue: another process pid={} may be updating the same tables. Current pid={}",
                        gondola.getHostId(), cmember.memberId, pid, gondola.getProcessId());
            }

            // Get the max gap. The maxGap variable is deliberately not initialized to zero in order to
            // TODO: comment is not complete.
            maxGap = storage.getMaxGap(cmember.memberId);
            logger.info("[{}-{}] Initializing save index with latest=({},{}) maxGap={}",
                    gondola.getHostId(), cmember.memberId, newLastTerm, lastIndex, maxGap);

            // Find latest contiguous index from index 1, by starting from last - maxGap
            // Move back one earlier in case the entry at last - maxGap is missing; we need to get the lastTerm
            int start = Math.max(1, lastIndex - maxGap - 1);
            for (int i = start; i <= lastIndex; i++) {
                entry = storage.getLogEntry(cmember.memberId, i);
                if (entry == null) {
                    // Found a missing entry so previous one becomes the latest
                    logger.info(
                            "[{}-{}] SaveQueue: index={} is missing (last={}). "
                                    + "Setting savedIndex={} and deleting subsequent entries",
                            gondola.getHostId(), cmember.memberId, i, lastIndex, savedIndex);
                    deleteFrom(i + 1, lastIndex);
                    assert i > start;
                    break;
                } else {
                    newLastTerm = entry.term;
                    newSavedIndex = entry.index;
                    entry.release();
                }
            }

            // Check that there are no more gaps or extra entries
            int count = storage.count(cmember.memberId);
            if (count != newSavedIndex) {
                throw new IllegalStateException(String.format("The last index is %d but found %d entries in the log",
                        newSavedIndex, count));
            }

            // Finally update the saved rid
            lastTerm = newLastTerm;
            savedIndex = newSavedIndex;
            workQueue.clear();
            saved.clear();
            saving.clear();

            initialized = true;
            indexInitialized.signalAll();

            // All gaps removed so reset max gap
            storage.setMaxGap(cmember.memberId, 0);
            maxGap = 0;
        } finally {
            lock.unlock();
        }
    }

    // Temp for debugging
    public void verifySavedIndex() throws GondolaException {
        int si = savedIndex; // Capture since it might change while the last entry is being fetched
        LogEntry entry = storage.getLastLogEntry(cmember.memberId);
        if (entry != null && entry.index < si) {
            throw new IllegalStateException(String.format(
                    "Save index is not in-sync with storage: last index (%d) < save index(%d)", entry.index, si));
        }
    }

    class Worker extends Thread {
        Worker(int i) {
            setName("SaveQueueWorker-" + i);
            setDaemon(true);
        }

        public void run() {
            while (true) {
                Message message = null;
                lock.lock();
                try {
                    while ((message = workQueue.poll()) == null) {
                        numWaiters++;
                        queueEmpty.await();
                        numWaiters--;
                    }
                } catch (InterruptedException e) {
                    break;
                } finally {
                    lock.unlock();
                }

                try {
                    // Save the message
                    message.handle(handler);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    cmember.indexUpdated(true, false);
                } finally {
                    message.release();
                }
            }
        }
    }

    /**
     * The only message type that needs to be handled is AppendEntry requests.
     */
    class MyMessageHandler extends MessageHandler {

        @Override
        public boolean appendEntryRequest(Message message, int fromMemberId, int term,
                                          int prevLogTerm, int prevLogIndex, int commitIndex,
                                          boolean isHeartbeat, int entryTerm, byte[] buffer, int bufferOffset,
                                          int bufferLen,
                                          boolean lastCommand) throws InterruptedException, GondolaException {
            // Determine if the any entries need to be deleted
            int deletedCount = 0;
            int index = prevLogIndex + 1;
            lock.lock();
            try {
                if (saving.contains(index)) {
                    if (storageTracing) {
                        logger.info("[{}-{}] SaveQueue: index={} is currently being saved. Ignoring this request.",
                                gondola.getHostId(), cmember.memberId, index);
                    }
                    return true;
                }

                if (index <= savedIndex) {
                    // Possibly overwrite an old entry
                    LogEntry le = storage.getLogEntry(cmember.memberId, index);
                    if (le == null) {
                        throw new IllegalStateException(String.format("[%s] Could not retrieve index=%d. savedIndex=%d",
                                gondola.getHostId(), index, savedIndex));
                    }
                    boolean isContentsEqual = le.equals(buffer, bufferOffset, bufferLen);
                    logger.info(
                            "[{}-{}] SaveQueue: overwriting index={} which is older than the savedIndex={}. "
                                    + "Contents are {}.",
                            gondola.getHostId(), cmember.memberId, index, savedIndex,
                            isContentsEqual ? "identical" : "different");
                    if (isContentsEqual) {
                        // The contents haven't changed so ignore this message
                        return true;
                    } else {
                        savedIndex = index - 1;
                        logger.info("[{}-{}] SaveQueue: Setting savedIndex={} and deleting subsequent entries",
                                gondola.getHostId(), cmember.memberId, savedIndex);

                        // Determine the last entry to delete
                        int lastIndex = -1;
                        if (saving.size() > 0) {
                            lastIndex = saving.stream().max(Integer::compare).get();
                        }

                        deletedCount = deleteFrom(index, lastIndex);
                        if (deletedCount > 0) {
                            cmember.indexUpdated(false, deletedCount > 0);
                        }
                    }
                } else if (saved.containsKey(index)) {
                    // Check if the entry has already been written
                    if (storageTracing) {
                        logger.info("[{}-{}] SaveQueue: index={} has already been saved. Ignoring this request.",
                                gondola.getHostId(), cmember.memberId, index);
                    }
                    return true;
                } else {
                    // Increase maxGap if necessary.
                    int g = Math.max(maxGap, index - savedIndex);
                    if (g > maxGap) {
                        g = ((g - 1) / 10 + 1) * 10; // Round to the next higher 10
                        if (storageTracing || g % 100 == 0) {
                            logger.info("[{}-{}] SaveQueue: increasing maxGap from {} to {}",
                                    gondola.getHostId(), cmember.memberId, maxGap, g);
                        }
                        storage.setMaxGap(cmember.memberId, g);
                        maxGap = g;
                    }
                }
                saving.add(index);
            } finally {
                lock.unlock();
            }

            // Append entry outside the lock
            storage.appendLogEntry(cmember.memberId, entryTerm, index, buffer, bufferOffset, bufferLen);
            if (storageTracing) {
                logger.info("[{}-{}] insert(term={} index={} size={}) waiters={} saved={} contents={}",
                        gondola.getHostId(), cmember.memberId, entryTerm, index,
                        bufferLen, numWaiters, saved.size(), new String(buffer, bufferOffset, bufferLen));
            }
            stats.savedCommand(bufferLen);

            // Update state
            int oldSavedIndex = savedIndex;
            lock.lock();
            try {
                if (!saving.remove(index)) {
                    logger.warn("[{}-{}] SaveQueue: index={} has already been removed",
                            gondola.getHostId(), cmember.memberId, index);
                }
                if (index == savedIndex + 1) {
                    // The savedIndex can be advanced immediately
                    savedIndex++;
                    index++;
                    lastTerm = entryTerm;

                    // Continue to advance savedIndex if possible
                    int start = index;
                    while (saved.containsKey(index)) {
                        lastTerm = saved.get(index);
                        saved.remove(index);
                        savedIndex++;
                        index++;
                    }
                    if (index > start && storageTracing) {
                        logger.info("[{}-{}] SaveQueue: pulled index={} to {} from saved. Remaining={}",
                                gondola.getHostId(), cmember.memberId, start, index - 1, saved.size());
                    }
                } else if (index > savedIndex) {
                    saved.put(index, entryTerm);
                } else {
                    logger.warn("[{}-{}] SaveQueue: savedIndex={} is > index={}",
                            gondola.getHostId(), cmember.memberId, savedIndex, index);
                }
            } finally {
                lock.unlock();
            }

            // Update commit index
            if (deletedCount > 0 || savedIndex > oldSavedIndex) {
                cmember.indexUpdated(false, deletedCount > 0);
            }
            return true;
        }
    }

    /**
     * Deletes entries from [index, lastIndex]. The entries are deleted backwards to avoid having to increase maxGap.
     */
    int deleteFrom(int index, int lastIndex) throws GondolaException {
        int deleted = 0;
        if (lastIndex < 0) {
            lastIndex = savedIndex;
            LogEntry entry = storage.getLastLogEntry(cmember.memberId);
            if (entry != null) {
                lastIndex = entry.index;
            }
        }

        for (int i = lastIndex; i >= index; i--) {
            LogEntry entry = storage.getLogEntry(cmember.memberId, i);
            if (entry != null) {
                logger.info("[{}-{}] SaveQueue: deleting index={}", gondola.getHostId(), cmember.memberId, i);
                storage.delete(cmember.memberId, i);
                deleted++;
            }
        }
        return deleted;
    }
}
