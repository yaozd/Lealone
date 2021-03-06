/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.codefollower.lealone.omid.tso;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class Uncommited {
    private static final Log LOG = LogFactory.getLog(Uncommited.class);

    private static final int BKT_NUMBER = 1 << 10; // 2 ^ 10

    private Bucket buckets[] = new Bucket[BKT_NUMBER];
    private int firstUncommitedBucket = 0;
    private long firstUncommitedAbsolute = 0;
    private int lastOpenedBucket = 0;

    public Uncommited(long startTimestamp) {
        lastOpenedBucket = firstUncommitedBucket = getRelativePosition(startTimestamp);
        firstUncommitedAbsolute = getAbsolutePosition(startTimestamp);
        long ts = startTimestamp & ~(Bucket.getBucketSize() - 1);

        if (LOG.isDebugEnabled()) {
            LOG.debug("Start TS : " + startTimestamp + " firstUncom: " + firstUncommitedBucket + " Mask:" + ts);
            LOG.debug("BKT_NUMBER : " + BKT_NUMBER + " BKT_SIZE: " + Bucket.getBucketSize());
        }
        for (; ts <= startTimestamp; ++ts)
            commit(ts);
    }

    public synchronized void commit(long id) {
        int position = getRelativePosition(id);
        Bucket bucket = buckets[position];
        if (bucket == null) {
            bucket = new Bucket(getAbsolutePosition(id));
            buckets[position] = bucket;
            lastOpenedBucket = position;
        }
        bucket.commit(id);
        if (bucket.allCommited()) {
            buckets[position] = null;
            increaseFirstUncommitedBucket();
        }
    }

    public void abort(long id) {
        commit(id);
    }

    public boolean isUncommited(long id) {
        Bucket bucket = buckets[getRelativePosition(id)];
        if (bucket == null) {
            return false;
        }
        return bucket.isUncommited(id);
    }

    public Set<Long> raiseLargestDeletedTransaction(long id) {
        if (firstUncommitedAbsolute > getAbsolutePosition(id))
            return Collections.emptySet();
        int maxBucket = getRelativePosition(id);
        Set<Long> aborted = new TreeSet<Long>();
        for (int i = firstUncommitedBucket; i != maxBucket; i = (i + 1) % BKT_NUMBER) {
            Bucket bucket = buckets[i];
            if (bucket != null) {
                aborted.addAll(bucket.abortAllUncommited());
                buckets[i] = null;
            }
        }

        Bucket bucket = buckets[maxBucket];
        if (bucket != null) {
            aborted.addAll(bucket.abortUncommited(id));
        }

        increaseFirstUncommitedBucket();

        return aborted;
    }

    public synchronized long getFirstUncommitted() {
        return buckets[firstUncommitedBucket].getFirstUncommitted();
    }

    private synchronized void increaseFirstUncommitedBucket() {
        while (firstUncommitedBucket != lastOpenedBucket && buckets[firstUncommitedBucket] == null) {
            firstUncommitedBucket = (firstUncommitedBucket + 1) % BKT_NUMBER;
            firstUncommitedAbsolute++;
        }
    }

    private int getRelativePosition(long id) {
        return ((int) (id / Bucket.getBucketSize())) % BKT_NUMBER;
    }

    private int getAbsolutePosition(long id) {
        return (int) (id / Bucket.getBucketSize());
    }
}
