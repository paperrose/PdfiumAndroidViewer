/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.paperrose.pdfviewer;

import android.graphics.RectF;
import android.support.annotation.Nullable;

import com.github.paperrose.pdfviewer.model.DoublePagePart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static com.github.paperrose.pdfviewer.util.Constants.Cache.CACHE_SIZE;
import static com.github.paperrose.pdfviewer.util.Constants.Cache.THUMBNAILS_CACHE_SIZE;

class DoubleCacheManager {

    private final PriorityQueue<DoublePagePart> passiveCache;

    private final PriorityQueue<DoublePagePart> activeCache;

    public final List<DoublePagePart> thumbnails;

    public final Object passiveActiveLock = new Object();

    private final PagePartComparator comparator = new PagePartComparator();

    public DoubleCacheManager() {
        activeCache = new PriorityQueue<>(CACHE_SIZE, comparator);
        passiveCache = new PriorityQueue<>(CACHE_SIZE, comparator);
        thumbnails = new ArrayList<>();
    }

    public void cachePart(DoublePagePart part) {
        synchronized (passiveActiveLock) {
            // If cache too big, remove and recycle
            makeAFreeSpace();

            // Then add part
            activeCache.offer(part);
        }
    }

    public void makeANewSet() {
        synchronized (passiveActiveLock) {
            passiveCache.addAll(activeCache);
            activeCache.clear();
        }
    }

    private void makeAFreeSpace() {
        synchronized (passiveActiveLock) {
            while ((activeCache.size() + passiveCache.size()) >= CACHE_SIZE &&
                    !passiveCache.isEmpty()) {
                DoublePagePart part = passiveCache.poll();
                part.getRenderedBitmap().recycle();
            }

            while ((activeCache.size() + passiveCache.size()) >= CACHE_SIZE &&
                    !activeCache.isEmpty()) {
                activeCache.poll().getRenderedBitmap().recycle();
            }
        }
    }

    public void cacheThumbnail(DoublePagePart part) {
        synchronized (thumbnails) {
            // If cache too big, remove and recycle
            if (thumbnails.size() >= THUMBNAILS_CACHE_SIZE) {
                thumbnails.remove(0).getRenderedBitmap().recycle();
            }

            // Then add thumbnail
            thumbnails.add(part);
        }

    }

    public boolean upPartIfContained(int userPage, int page, float width, float height, RectF pageRelativeBounds, int toOrder, boolean rightPage) {
        DoublePagePart fakePart = new DoublePagePart(userPage, page, null, width, height, pageRelativeBounds, false, 0, rightPage);

        DoublePagePart found;
        synchronized (passiveActiveLock) {
            if ((found = find(passiveCache, fakePart)) != null) {
                passiveCache.remove(found);
                found.setCacheOrder(toOrder);
                activeCache.offer(found);
                return true;
            }

            return find(activeCache, fakePart) != null;
        }
    }

    /**
     * Return true if already contains the described PagePart
     */
    public boolean containsThumbnail(int userPage, int page, float width, float height, RectF pageRelativeBounds, boolean rightPage) {
        DoublePagePart fakePart = new DoublePagePart(userPage, page, null, width, height, pageRelativeBounds, true, 0, rightPage);
        synchronized (thumbnails) {
            for (DoublePagePart part : thumbnails) {
                if (part.equals(fakePart)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Nullable
    private static DoublePagePart find(PriorityQueue<DoublePagePart> vector, DoublePagePart fakePart) {
        for (DoublePagePart part : vector) {
            if (part.equals(fakePart)) {
                return part;
            }
        }
        return null;
    }

    public List<DoublePagePart> getPageParts() {
        synchronized (passiveActiveLock) {
            List<DoublePagePart> parts = new ArrayList<>(passiveCache);
            parts.addAll(activeCache);
            return parts;
        }
    }

    public List<DoublePagePart> getThumbnails() {
        synchronized (thumbnails) {
            return thumbnails;
        }
    }

    public void recycle() {
        synchronized (passiveActiveLock) {
            for (DoublePagePart part : passiveCache) {
                part.getRenderedBitmap().recycle();
            }
            passiveCache.clear();
            for (DoublePagePart part : activeCache) {
                part.getRenderedBitmap().recycle();
            }
            activeCache.clear();
        }
        synchronized (thumbnails) {
            for (DoublePagePart part : thumbnails) {
                part.getRenderedBitmap().recycle();
            }
            thumbnails.clear();
        }
    }

    class PagePartComparator implements Comparator<DoublePagePart> {
        @Override
        public int compare(DoublePagePart part1, DoublePagePart part2) {
            if (part1.getCacheOrder() == part2.getCacheOrder()) {
                return 0;
            }
            return part1.getCacheOrder() > part2.getCacheOrder() ? 1 : -1;
        }
    }

}
