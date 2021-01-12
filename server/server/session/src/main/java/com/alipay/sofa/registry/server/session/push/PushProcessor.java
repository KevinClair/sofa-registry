/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.server.session.push;

import com.alipay.remoting.rpc.exception.InvokeTimeoutException;
import com.alipay.sofa.registry.common.model.dataserver.Datum;
import com.alipay.sofa.registry.common.model.store.BaseInfo;
import com.alipay.sofa.registry.common.model.store.Subscriber;
import com.alipay.sofa.registry.core.model.AssembleType;
import com.alipay.sofa.registry.core.model.ScopeEnum;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.remoting.CallbackHandler;
import com.alipay.sofa.registry.remoting.Channel;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.node.service.ClientNodeService;
import com.alipay.sofa.registry.server.shared.util.DatumUtils;
import com.alipay.sofa.registry.task.KeyedThreadPoolExecutor;
import com.alipay.sofa.registry.task.MetricsableThreadPoolExecutor;
import com.alipay.sofa.registry.trace.TraceID;
import com.alipay.sofa.registry.util.ConcurrentUtils;
import com.alipay.sofa.registry.util.WakeupLoopRunnable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class PushProcessor {
    private static final Logger                 LOGGER               = LoggerFactory
                                                                         .getLogger(PushProcessor.class);

    private KeyedThreadPoolExecutor             pushExecutor;
    private final Map<PendingTaskKey, PushTask> pendingTasks         = Maps.newConcurrentMap();
    private final Lock                          pendingLock          = new ReentrantLock();

    private final Map<PushingTaskKey, PushTask> pushingTasks         = Maps.newConcurrentMap();

    @Autowired
    private SessionServerConfig                 sessionServerConfig;

    @Autowired
    private PushDataGenerator                   pushDataGenerator;

    @Autowired
    private ClientNodeService                   clientNodeService;

    private final WatchDog                      watchDog             = new WatchDog();

    private final ThreadPoolExecutor            pushCallbackExecutor = MetricsableThreadPoolExecutor
                                                                         .newExecutor(
                                                                             "PushCallbackExecutor",
                                                                             2, 1000,
                                                                             new CallRunHandler());

    @PostConstruct
    public void init() {
        pushExecutor = new KeyedThreadPoolExecutor("PushExecutor",
            sessionServerConfig.getPushTaskExecutorPoolSize(),
            sessionServerConfig.getPushTaskExecutorQueueSize());
        ConcurrentUtils.createDaemonThread("PushWatchDog", watchDog).start();
    }

    private boolean firePush(PushTask pushTask) {
        PendingTaskKey key = pushTask.pendingKeyOf();
        if (pendingTasks.putIfAbsent(key, pushTask) == null) {
            // fast path
            return true;
        }
        boolean conflict = false;
        PushTask prev = null;
        pendingLock.lock();
        try {
            prev = pendingTasks.get(key);
            if (prev == null) {
                pendingTasks.put(key, pushTask);
            } else if (pushTask.afterThan(prev)) {
                // update the expireTimestamp as prev's, avoid the push block by the continues fire
                pushTask.expireTimestamp = prev.expireTimestamp;
                pendingTasks.put(key, pushTask);
            } else {
                conflict = true;
            }
        } finally {
            pendingLock.unlock();
        }
        if (!conflict) {
            if (pushTask.noDelay) {
                watchDog.wakeup();
            }
            return true;
        } else {
            LOGGER.info("[ConflictPending] {}, {}, prev {} > {}",
                pushTask.subscriber.getDataInfoId(), key, prev.fetchSeqEnd, pushTask.fetchSeqStart);
            return false;
        }
    }

    protected List<PushTask> createPushTask(boolean noDelay, long pushVersion, String dataCenter,
                                            InetSocketAddress addr,
                                            Map<String, Subscriber> subscriberMap,
                                            Map<String, Datum> datumMap, long fetchStartSeq,
                                            long fetchEndSeq) {
        PushTask pushTask = new PushTask(noDelay, pushVersion, dataCenter, addr, subscriberMap,
            datumMap, fetchStartSeq, fetchEndSeq);
        // wait to merge to debouncing
        pushTask.expireAfter(sessionServerConfig.getPushDataTaskDebouncingMillis());
        return Collections.singletonList(pushTask);
    }

    void firePush(boolean noDelay, long pushVersion, String dataCenter, InetSocketAddress addr, Map<String, Subscriber> subscriberMap,
                  Map<String, Datum> datumMap, long fetchSeqStart, long fetchSeqEnd) {
        List<PushTask> fires = createPushTask(noDelay, pushVersion, dataCenter, addr, subscriberMap, datumMap, fetchSeqStart, fetchSeqEnd);
        fires.forEach(t -> firePush(t));
    }

    private boolean commitTask(PushTask task) {
        try {
            // keyed by pushingKey: client.addr && dataInfoId
            pushExecutor.execute(task.pushingKeyOf(), task);
            return true;
        } catch (Throwable e) {
            LOGGER.error("failed to exec push task {}", task.pendingKeyOf(), e);
            return false;
        }
    }

    private final class WatchDog extends WakeupLoopRunnable {

        @Override
        public void runUnthrowable() {
            List<PushTask> pending = transferAndMerge();
            if (sessionServerConfig.isStopPushSwitch()) {
                return;
            }
            if (pending.isEmpty()) {
                return;
            }
            LOGGER.info("process push tasks {}", pending.size());
            for (PushTask task : pending) {
                commitTask(task);
            }
        }

        @Override
        public int getWaitingMillis() {
            return 100;
        }
    }

    private List<PushTask> transferAndMerge() {
        List<PushTask> pending = Lists.newArrayList();
        final long now = System.currentTimeMillis();
        pendingLock.lock();
        try {
            final Iterator<Map.Entry<PendingTaskKey, PushTask>> it = pendingTasks.entrySet()
                .iterator();
            while (it.hasNext()) {
                Map.Entry<PendingTaskKey, PushTask> e = it.next();
                PushTask task = e.getValue();
                if (task.noDelay || task.expireTimestamp <= now) {
                    pending.add(task);
                    it.remove();
                }
            }
        } finally {
            pendingLock.unlock();
        }
        return pending;
    }

    private boolean checkPushing(PushTask task, PushingTaskKey pushingTaskKey) {
        // check the pushing task
        final PushTask prev = pushingTasks.get(pushingTaskKey);
        if (prev == null) {
            // check the subcriber version
            for (Subscriber subscriber : task.subscriberMap.values()) {
                if (!subscriber.checkVersion(task.dataCenter, task.fetchSeqStart)) {
                    LOGGER.warn("conflict push, subscriber={}, {}", subscriber, task);
                    return false;
                }
            }
            return true;
        }
        if (!task.afterThan(prev)) {
            LOGGER.warn("prev push is newly, prev={}, now={}", prev, task);
            return false;
        }
        final long span = prev.spanMillis();
        if (span > sessionServerConfig.getClientNodeExchangeTimeOut() * 2) {
            // force to remove the prev task
            pushingTasks.remove(pushingTaskKey);
            LOGGER.warn("[prevRunTooLong] prev={}, now={}", prev, task);
            return true;
        }
        // task after the prev, but prev.pushclient not callback, retry
        retry(task, "waiting");
        return false;
    }

    private boolean retry(PushTask task, String reason) {
        final int retry = task.retryCount.incrementAndGet();
        if (retry <= sessionServerConfig.getPushTaskRetryTimes()) {
            final int backoffMillis = getRetryBackoffTime(retry);
            task.expireAfter(backoffMillis);
            if (firePush(task)) {
                LOGGER.info("add retry for {}, {}, retry={}, backoff={}", reason, task.taskID,
                    retry, backoffMillis);
                return true;
            }
        }
        LOGGER.info("skip retry for {}, {}, retry={}", reason, task.taskID, retry);
        return false;
    }

    class PushTask implements Runnable {
        final TraceID                 taskID;
        final long                    createTimestamp = System.currentTimeMillis();
        volatile long                 expireTimestamp;
        volatile long                 pushTimestamp;

        final boolean                 noDelay;
        final long                    fetchSeqStart;
        final long                    fetchSeqEnd;
        final String                  dataCenter;
        final long                    pushVersion;
        final Map<String, Datum>      datumMap;
        final InetSocketAddress       addr;
        final Map<String, Subscriber> subscriberMap;
        final Subscriber              subscriber;
        final AtomicInteger           retryCount      = new AtomicInteger();

        PushTask(boolean noDelay, long pushVersion, String dataCenter, InetSocketAddress addr,
                 Map<String, Subscriber> subscriberMap, Map<String, Datum> datumMap,
                 long fetchSeqStart, long fetchSeqEnd) {
            this.taskID = TraceID.newTraceID();
            this.noDelay = noDelay;
            this.dataCenter = dataCenter;
            this.pushVersion = pushVersion;
            this.datumMap = datumMap;
            this.addr = addr;
            this.subscriberMap = subscriberMap;
            this.fetchSeqStart = fetchSeqStart;
            this.fetchSeqEnd = fetchSeqEnd;
            this.subscriber = subscriberMap.values().iterator().next();
        }

        protected Object createPushData() {
            Datum merged = pushDataGenerator.mergeDatum(subscriber, dataCenter, datumMap);
            return pushDataGenerator.createPushData(merged, subscriberMap, pushVersion);
        }

        void expireAfter(long intervalMs) {
            this.expireTimestamp = System.currentTimeMillis() + intervalMs;
        }

        void updatePushTimestamp() {
            this.pushTimestamp = System.currentTimeMillis();
        }

        long spanMillis() {
            return System.currentTimeMillis() - pushTimestamp;
        }

        @Override
        public void run() {
            if (sessionServerConfig.isStopPushSwitch()) {
                return;
            }
            PushingTaskKey pushingTaskKey = null;
            try {
                pushingTaskKey = this.pushingKeyOf();
                if (!checkPushing(this, pushingTaskKey)) {
                    return;
                }
                Object data = createPushData();
                updatePushTimestamp();
                pushingTasks.put(pushingTaskKey, this);
                clientNodeService.pushWithCallback(data, subscriber.getSourceAddress(),
                    new PushClientCallback(this, pushingTaskKey));
                LOGGER.info("{}, pushing {}, subscribers={}", taskID, pushingTaskKey,
                    subscriberMap.size());
            } catch (Throwable e) {
                // try to delete self
                boolean cleaned = false;
                if (pushingTaskKey != null) {
                    cleaned = pushingTasks.remove(pushingTaskKey) != null;
                }
                LOGGER.error("{}, failed to pushing, cleaned={}, {}", taskID, cleaned, this, e);
            }
        }

        boolean afterThan(PushTask t) {
            return fetchSeqStart >= t.fetchSeqEnd;
        }

        PendingTaskKey pendingKeyOf() {
            return new PendingTaskKey(dataCenter, addr, subscriberMap.keySet());
        }

        PushingTaskKey pushingKeyOf() {
            return new PushingTaskKey(subscriber.getDataInfoId(), addr, subscriber.getScope(),
                subscriber.getAssembleType(), subscriber.getClientVersion());
        }

        @Override
        public String toString() {
            return "PushTask{" + "taskID=" + taskID + ", createTimestamp=" + createTimestamp
                   + ", expireTimestamp=" + expireTimestamp + ", pushTimestamp=" + pushTimestamp
                   + ", fetchSeqStart=" + fetchSeqStart + ", fetchSeqEnd=" + fetchSeqEnd
                   + ", dataCenter='" + dataCenter + ", pushVersion=" + pushVersion + ", addr="
                   + addr + ", subscriber=" + subscriber + ", retryCount=" + retryCount + '}';
        }
    }

    private final class PushClientCallback implements CallbackHandler {
        final PushTask       pushTask;
        final PushingTaskKey pushingTaskKey;

        PushClientCallback(PushTask pushTask, PushingTaskKey pushingTaskKey) {
            this.pushTask = pushTask;
            this.pushingTaskKey = pushingTaskKey;
        }

        @Override
        public void onCallback(Channel channel, Object message) {
            boolean cleaned = false;
            try {
                final Map<String, Long> versions = DatumUtils.getVesions(pushTask.datumMap);
                for (Subscriber subscriber : pushTask.subscriberMap.values()) {
                    if (!subscriber.checkAndUpdateVersion(pushTask.dataCenter,
                        pushTask.pushVersion, versions, pushTask.fetchSeqStart,
                        pushTask.fetchSeqEnd)) {
                        LOGGER.warn("Push success, but failed to updateVersion, {}", pushTask);
                    }
                }
            } catch (Throwable e) {
                LOGGER.error("error push.onCallback, {}", pushTask, e);
            } finally {
                // TODO should use remove(k, exceptV). but in some case,
                // after removed=true, the value aslo in the map
                cleaned = pushingTasks.remove(pushingTaskKey) != null;
            }
            LOGGER.info("Push success, clean record={}, span={}, {}", cleaned,
                pushTask.spanMillis(), pushTask);
        }

        @Override
        public void onException(Channel channel, Throwable exception) {
            boolean cleaned = false;
            try {
                // TODO should use remove(k, exceptV). but in some case,
                // after removed=true, the value aslo in the map
                cleaned = pushingTasks.remove(pushingTaskKey) != null;
                if (channel.isConnected()) {
                    retry(pushTask, "callbackErr");
                } else {
                    LOGGER.warn("{}, push.onException, channel is closed, {}", pushTask.taskID,
                        pushingTaskKey);
                }
            } catch (Throwable e) {
                LOGGER.error("error push.onException, {}", pushTask, e);
            }
            if (exception instanceof InvokeTimeoutException) {
                LOGGER.error("Push error timeout, clean record={}, span={}, {}", cleaned,
                    pushTask.spanMillis(), pushTask);
            } else {
                LOGGER.error("Push error, clean record={}, span={}, {}", cleaned,
                    pushTask.spanMillis(), pushTask, exception);
            }
        }

        @Override
        public Executor getExecutor() {
            return pushCallbackExecutor;
        }
    }

    private static final class PendingTaskKey {
        final String            dataCenter;
        final InetSocketAddress addr;
        final Set<String>       subscriberIds;

        PendingTaskKey(String dataCenter, InetSocketAddress addr, Set<String> subscriberIds) {
            this.dataCenter = dataCenter;
            this.addr = addr;
            this.subscriberIds = subscriberIds;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            PendingTaskKey pendingTaskKey = (PendingTaskKey) o;
            return Objects.equals(addr, pendingTaskKey.addr)
                   && Objects.equals(dataCenter, pendingTaskKey.dataCenter)
                   && Objects.equals(subscriberIds, pendingTaskKey.subscriberIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dataCenter, addr, subscriberIds);
        }

        @Override
        public String toString() {
            return "PendingTaskKey{" + "dataCenter='" + dataCenter + '\'' + ", addr=" + addr
                   + ", subscriberIds=" + subscriberIds + '}';
        }
    }

    private static final class PushingTaskKey {
        final InetSocketAddress      addr;
        final String                 dataInfoId;
        final ScopeEnum              scopeEnum;
        final AssembleType           assembleType;
        final BaseInfo.ClientVersion clientVersion;

        PushingTaskKey(String dataInfoId, InetSocketAddress addr, ScopeEnum scopeEnum,
                       AssembleType assembleType, BaseInfo.ClientVersion clientVersion) {
            this.dataInfoId = dataInfoId;
            this.addr = addr;
            this.scopeEnum = scopeEnum;
            this.assembleType = assembleType;
            this.clientVersion = clientVersion;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            PushingTaskKey that = (PushingTaskKey) o;
            return Objects.equals(addr, that.addr) && Objects.equals(dataInfoId, that.dataInfoId)
                   && scopeEnum == that.scopeEnum && assembleType == that.assembleType
                   && clientVersion == that.clientVersion;
        }

        @Override
        public int hashCode() {
            return Objects.hash(addr, dataInfoId, scopeEnum, assembleType, clientVersion);
        }

        @Override
        public String toString() {
            return "PushingTaskKey{" + "addr=" + addr + ", dataInfoId='" + dataInfoId + '\''
                   + ", scopeEnum=" + scopeEnum + '}';
        }
    }

    private static final class CallRunHandler extends ThreadPoolExecutor.CallerRunsPolicy {
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            super.rejectedExecution(r, e);
            LOGGER.warn("push callback busy");
        }
    }

    private int getRetryBackoffTime(int retry) {
        final int initialSleepTime = sessionServerConfig.getPushDataTaskRetryFirstDelayMillis();
        if (retry == 0) {
            return initialSleepTime;
        }
        int increment = sessionServerConfig.getPushDataTaskRetryIncrementDelayMillis();
        int result = initialSleepTime + (increment * (retry - 1));
        return result >= 0L ? result : 0;
    }

}
