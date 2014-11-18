/*
 * Copyright 2013 Rackspace
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.rackspacecloud.blueflood.service;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.google.common.base.Ticker;
import com.rackspacecloud.blueflood.exceptions.GranularityException;
import com.rackspacecloud.blueflood.rollup.Granularity;
import com.rackspacecloud.blueflood.utils.Metrics;
import com.rackspacecloud.blueflood.utils.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ShardStateManager {
    private static final Logger log = LoggerFactory.getLogger(ShardStateManager.class);
    private static final Set<Integer> ALL_SHARDS = new HashSet<Integer>(Util.parseShards("ALL"));
    final Set<Integer> shards; // Managed shards
    final Map<Integer, ShardToGranularityMap> shardToGranularityStates = new HashMap<Integer, ShardToGranularityMap>();
    private final Ticker serverTimeMillisecondTicker;

    private static final Histogram timeSinceUpdate = Metrics.histogram(RollupService.class, "Shard Slot Time Elapsed scheduleSlotsOlderThan");
    // todo: CM_SPECIFIC verify changing metric class name doesn't break things.
    private static final Meter updateStampMeter = Metrics.meter(ShardStateManager.class, "Shard Slot Update Meter");
    private final Meter parentBeforeChild = Metrics.meter(RollupService.class, "Parent slot executed before child");
    private static final Meter reRollupData = Metrics.meter(RollupService.class, "Re-rolling up a slot because of new data");

    protected ShardStateManager(Collection<Integer> shards, Ticker ticker) {
        this.shards = new HashSet<Integer>(shards);
        for (Integer shard : ALL_SHARDS) { // Why not just do this for managed shards?
            shardToGranularityStates.put(shard, new ShardToGranularityMap(shard));
        }
        this.serverTimeMillisecondTicker = ticker;
    }

    protected Collection<Integer> getManagedShards() {
        return Collections.unmodifiableCollection(this.shards);
    }

    protected Boolean contains(int shard) {
        return shards.size() != 0 && shards.contains(shard);
    }

    protected void add(int shard) {
        if (contains(shard))
            return;
        shards.add(shard);
    }

    protected void remove(int shard) {
        if (!contains(shard))
            return;
        this.shards.remove(shard);
    }

    public SlotStateManager getSlotStateManager(int shard, Granularity granularity) {
        return shardToGranularityStates.get(shard).granularityToSlots.get(granularity);
    }

    protected UpdateStamp getUpdateStamp(int shard, Granularity gran, int slot) {
        return this.getSlotStateManager(shard, gran).slotToUpdateStampMap.get(slot);
    }

    // Side effect: mark dirty slots as clean
    protected Map<Granularity, Map<Integer, UpdateStamp>> getDirtySlotsToPersist(int shard) {
        Map<Granularity, Map<Integer, UpdateStamp>> slotTimes = new HashMap<Granularity, Map<Integer, UpdateStamp>>();
        int numUpdates = 0;
        for (Granularity gran : Granularity.rollupGranularities()) {
            Map<Integer, UpdateStamp> dirty = getSlotStateManager(shard, gran).getDirtySlotStampsAndMarkClean();
            slotTimes.put(gran, dirty);

            if (dirty.size() > 0) {
                numUpdates += dirty.size();
            }
        }
        if (numUpdates > 0) {
            // for updates that come by way of scribe, you'll typically see 5 as the number of updates (one for
            // each granularity).  On rollup slaves the situation is a bit different. You'll see only the slot
            // of the granularity just written to marked dirty (so 1).
            log.debug("Found {} dirty slots for shard {}", new Object[]{numUpdates, shard});
            return slotTimes;
        }
        return null;
    }

    public void updateSlotOnRead(int shard, SlotState slotState) {
        getSlotStateManager(shard, slotState.getGranularity()).updateSlotOnRead(slotState);
    }

    public void setAllCoarserSlotsDirtyForSlot(int shard, Granularity suppliedGranularity,
                                               int suppliedSlot) {
        boolean done = false;
        Granularity coarserGran = suppliedGranularity;
        int coarserSlot = suppliedSlot;

        while (!done) {
            try {
                coarserGran = coarserGran.coarser();
                coarserSlot = coarserGran.slotFromFinerSlot(coarserSlot);
                ConcurrentMap<Integer, UpdateStamp> updateStampsBySlotMap = getSlotStateManager(shard, coarserGran).slotToUpdateStampMap;
                UpdateStamp coarseSlotStamp = updateStampsBySlotMap.get(coarserSlot);

                if (coarseSlotStamp == null) {
                    log.debug("No stamp for coarser slot: " + coarserGran.formatLocatorKey(coarserSlot, shard) +
                        " ; supplied slot: " + suppliedGranularity.formatLocatorKey(suppliedSlot, shard));
                    updateStampsBySlotMap.putIfAbsent(coarserSlot,
                            new UpdateStamp(serverTimeMillisecondTicker.read(), UpdateStamp.State.Active, true));
                    continue;
                }

                UpdateStamp.State coarseSlotState = coarseSlotStamp.getState();
                if (coarseSlotState != UpdateStamp.State.Active) {
                    parentBeforeChild.mark();
                    log.debug("Coarser slot not in active state when finer slot "
                            + suppliedGranularity.formatLocatorKey(suppliedSlot, shard)
                            + " just got rolled up. Marking coarser slot "
                            + coarserGran.formatLocatorKey(coarserSlot, shard) + " dirty");
                    coarseSlotStamp.setState(UpdateStamp.State.Active);
                    coarseSlotStamp.setDirty(true);
                    coarseSlotStamp.setTimestamp(serverTimeMillisecondTicker.read());
                }
            } catch (GranularityException ex) {
                done = true;
            }
        }
    }

    private class ShardToGranularityMap {
        final int shard;
        final Map<Granularity, SlotStateManager> granularityToSlots = new HashMap<Granularity, SlotStateManager>();

        protected ShardToGranularityMap(int shard) {
            this.shard = shard;

            for (Granularity granularity : Granularity.rollupGranularities()) {
                granularityToSlots.put(granularity, new SlotStateManager(shard, granularity));
            }
        }
    }

    protected class SlotStateManager {
        private final int shard;
        final Granularity granularity;
        final ConcurrentMap<Integer, UpdateStamp> slotToUpdateStampMap;

        protected SlotStateManager(int shard, Granularity granularity) {
            this.shard = shard;
            this.granularity = granularity;
            slotToUpdateStampMap = new ConcurrentHashMap<Integer, UpdateStamp>(granularity.numSlots());
        }
        /**
          Imagine metrics are flowing in from multiple ingestor nodes. The ingestion path updates schedule context while writing metrics to cassandra.(See BatchWriter)
          We cannot make any ordering guarantees on the metrics. So every metric that comes in updates the slot state to its collection time.

          This state gets pushed in cassandra by ShardStatePusher and read on the rollup slave. Rollup slave is going to update its state to ACTIVE as long as the timestamp does not match.
          Rollup slave shard map can be in 3 states: 1) Active 2) Rolled 3) Running.
          Every ACTIVE update is taken for Rolled and Running states, but if the shard map is already in an ACTIVE state, then the update happens only if the timestamp of update coming in
          if greater than what we have.
          On Rollup slave it means eventually when it rolls up data for the ACTIVE slot, it will be marked with the collection time belonging to a metric which was generated later.

          For a case of multiple ingestors, it means eventually higher timestamp will win, and will be updated even if that ingestor did not receive metric with that timestamp and will stop
          triggering the state to ACTIVE on rollup host. After this convergence is reached the last rollup time match with the last active times on all ingestor nodes.
         */
        protected void updateSlotOnRead(SlotState slotState) {
            final int slot = slotState.getSlot();
            final long timestamp = slotState.getTimestamp();
            UpdateStamp.State state = slotState.getState();
            UpdateStamp stamp = slotToUpdateStampMap.get(slot);
            if (stamp == null) {
                // haven't seen this slot before, take the update. This happens when a blueflood service is just started.
                slotToUpdateStampMap.put(slot, new UpdateStamp(timestamp, state, false));
            } else if (stamp.getTimestamp() != timestamp && state.equals(UpdateStamp.State.Active)) {
                // 1) new update coming in. We can be in 3 states 1) Active 2) Rolled 3) Running. Apply the update in all cases except when we are already active and
                //    the triggering timestamp we have is greater or the stamp in memory is yet to be persisted i.e still dirty
                if (!(stamp.getState().equals(UpdateStamp.State.Active) && (stamp.getTimestamp() > timestamp || stamp.isDirty()))) {
                    slotToUpdateStampMap.put(slot, new UpdateStamp(timestamp, state, false));
                } else {
                    stamp.setDirty(true); // This is crucial for convergence, we need to superimpose a higher timestamp which can be done only if we set it to dirty
                }
            } else if (stamp.getTimestamp() == timestamp && state.equals(UpdateStamp.State.Rolled)) {
                // 2) if current value is same but value being applied is a remove, remove wins.
                stamp.setState(UpdateStamp.State.Rolled);
            }
        }

        protected void createOrUpdateForSlotAndMillisecond(int slot, long millis) {
            if (slotToUpdateStampMap.containsKey(slot)) {
                UpdateStamp stamp = slotToUpdateStampMap.get(slot);
                stamp.setTimestamp(millis);
                if (stamp.getState().equals(UpdateStamp.State.Rolled)) {
                    reRollupData.mark();
                    log.info("Re-rolling slot " + slot + " for shard " + shard + " for granularity " + granularity);
                }
                stamp.setState(UpdateStamp.State.Active);
                stamp.setDirty(true);
            } else {
                slotToUpdateStampMap.put(slot, new UpdateStamp(millis, UpdateStamp.State.Active, true));
            }
            updateStampMeter.mark();
        }

        protected Map<Integer, UpdateStamp> getDirtySlotStampsAndMarkClean() {
            HashMap<Integer, UpdateStamp> dirtySlots = new HashMap<Integer, UpdateStamp>();
            for (Map.Entry<Integer, UpdateStamp> entry : slotToUpdateStampMap.entrySet()) {
                if (entry.getValue().isDirty()) {
                    dirtySlots.put(entry.getKey(), entry.getValue());
                    entry.getValue().setDirty(false);
                }
            }
            return dirtySlots;
        }

        protected UpdateStamp getAndSetState(int slot, UpdateStamp.State state) {
            UpdateStamp stamp = slotToUpdateStampMap.get(slot);
            stamp.setState(state);
            return stamp;
        }

        // gets a snapshot of the last updates
        protected Map<Integer, UpdateStamp> getSlotStamps() {
            // essentially a copy on read map.
            return Collections.unmodifiableMap(slotToUpdateStampMap);
        }

        protected List<Integer> getSlotsOlderThan(long now, long maxAgeMillis) {
            List<Integer> outputKeys = new ArrayList<Integer>();
            for (Map.Entry<Integer, UpdateStamp> entry : slotToUpdateStampMap.entrySet()) {
                final UpdateStamp update = entry.getValue();
                final long timeElapsed = now - update.getTimestamp();
                timeSinceUpdate.update(timeElapsed);
                if (update.getState() == UpdateStamp.State.Rolled) {
                    continue;
                }
                if (timeElapsed <= maxAgeMillis) {
                    continue;
                }
                outputKeys.add(entry.getKey());
            }
            return outputKeys;
        }

        protected Collection<String> getChildAndSelfKeysForSlot(int slot) {
            Collection<String> keys = new ArrayList<String>();
            keys.addAll(this.granularity.getChildrenKeys(slot, shard));
            keys.add(granularity.formatLocatorKey(slot, shard));
            return keys;
        }
    }
}


