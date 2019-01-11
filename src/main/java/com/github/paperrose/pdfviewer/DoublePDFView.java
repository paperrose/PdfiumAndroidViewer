/**
 * Copyright 2016 Bartosz Schiller
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.paperrose.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.widget.RelativeLayout;

import com.github.paperrose.pdfviewer.exception.FileNotFoundException;
import com.github.paperrose.pdfviewer.listener.OnDrawListener;
import com.github.paperrose.pdfviewer.listener.OnErrorListener;
import com.github.paperrose.pdfviewer.listener.OnLoadCompleteListener;
import com.github.paperrose.pdfviewer.listener.OnPageChangeListener;
import com.github.paperrose.pdfviewer.listener.OnPageScrollListener;
import com.github.paperrose.pdfviewer.model.DoublePagePart;
import com.github.paperrose.pdfviewer.scroll.DoubleScrollHandle;
import com.github.paperrose.pdfviewer.util.ArrayUtils;
import com.github.paperrose.pdfviewer.util.Constants;
import com.github.paperrose.pdfviewer.util.MathUtils;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public class DoublePDFView extends RelativeLayout {

    private static final String TAG = DoublePDFView.class.getSimpleName();

    public static final float DEFAULT_MAX_SCALE = 3.0f;
    public static final float DEFAULT_MID_SCALE = 1.75f;
    public static final float DEFAULT_MIN_SCALE = 1.0f;

    private float minZoom = DEFAULT_MIN_SCALE;
    private float midZoom = DEFAULT_MID_SCALE;
    private float maxZoom = DEFAULT_MAX_SCALE;

    public interface OnZoomListener {
        void onZoom(float zoom);
    }

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    enum ScrollDir {
        NONE, START, END
    }

    private ScrollDir scrollDir = ScrollDir.NONE;

    DoubleCacheManager cacheManager;

    /**
     * Animation manager manage all offset and zoom animation
     */
    private DoubleAnimationManager animationManager;

    /**
     * Drag manager manage all touch events
     */
    private DoubleDragPinchManager dragPinchManager;

    /**
     * The pages the user want to display in order
     * (ex: 0, 2, 2, 8, 8, 1, 1, 1)
     */
    private int[] originalUserPages;

    /**
     * The same pages but with a filter to avoid repetition
     * (ex: 0, 2, 8, 1)
     */
    private int[] filteredUserPages;

    /**
     * The same pages but with a filter to avoid repetition
     * (ex: 0, 1, 1, 2, 2, 3, 3, 3)
     */
    private int[] filteredUserPageIndexes;

    /**
     * Number of pages in the loaded PDF document
     */
    private int documentPageCount;

    /**
     * The index of the current sequence
     */
    private int currentPage;

    /**
     * The index of the current sequence
     */
    private int currentFilteredPage;

    /**
     * The actual width and height of the pages in the PDF document
     */
    private int pageWidth, pageRightWidth, pageHeight;

    /**
     * The optimal width and height of the pages to fit the component size
     */
    private float optimalPageWidth, optimalPageRightWidth, optimalPageHeight;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentXOffset = 0;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentYOffset = 0;

    /**
     * The zoom level, always >= 1
     */
    private float zoom = 1f;

    /**
     * True if the PDFView has been recycled
     */
    private boolean recycled = true;

    /**
     * Current state of the view
     */
    private State state = State.DEFAULT;

    /**
     * Async task used during the loading phase to decode a PDF document
     */
    private DoubleDecodingAsyncTask decodingAsyncTask;

    /**
     * Async task always playing in the background and proceeding rendering tasks
     */
    DoubleRenderingAsyncTask renderingAsyncTask;

    private DoublePagesLoader pagesLoader;

    /**
     * Call back object to call when the PDF is loaded
     */
    private OnLoadCompleteListener onLoadCompleteListener;
    private OnLoadCompleteListener onDrawBitmapCompleteListener;

    private Bitmap readyBitmap;

    private OnErrorListener onErrorListener;

    /**
     * Call back object to call when the page has changed
     */
    private OnPageChangeListener onPageChangeListener;

    /**
     * Call back object to call when the page is scrolled
     */
    private OnPageScrollListener onPageScrollListener;

    /**
     * Call back object to call when the above layer is to drawn
     */
    private OnDrawListener onDrawListener;

    /**
     * Paint object for drawing
     */
    private Paint paint;

    /**
     * Paint object for drawing debug stuff
     */
    private Paint debugPaint;

    private int defaultPage = 0;

    /**
     * True if should scroll through pages vertically instead of horizontally
     */
    private boolean swipeVertical = true;

    /**
     * Pdfium core for loading and rendering PDFs
     */
    private PdfiumCore pdfiumCore;

    private PdfDocument pdfDocument;


    private PdfDocument pdfRightDocument;

    private DoubleScrollHandle scrollHandle;

    private boolean isScrollHandleInit = false;

    DoubleScrollHandle getScrollHandle() {
        return scrollHandle;
    }

    /**
     * True if bitmap should use ARGB_8888 format and take more memory
     * False if bitmap should be compressed by using RGB_565 format and take less memory
     */
    private boolean bestQuality = false;

    /**
     * True if annotations should be rendered
     * False otherwise
     */
    private boolean annotationRendering = false;

    /**
     * Construct the initial view
     */
    public DoublePDFView(Context context, AttributeSet set) {
        super(context, set);

        if (isInEditMode()) {
            return;
        }

        cacheManager = new DoubleCacheManager();
        animationManager = new DoubleAnimationManager(this);
        dragPinchManager = new DoubleDragPinchManager(this, animationManager);

        paint = new Paint();
        debugPaint = new Paint();
        debugPaint.setStyle(Style.STROKE);

        pdfiumCore = new PdfiumCore(context);
        setWillNotDraw(false);
    }

    private void load(String path, String rpath, boolean isAsset, String password, OnLoadCompleteListener listener, OnLoadCompleteListener onDrawBitmapCompleteListener, OnErrorListener onErrorListener) {
        load(path, rpath, isAsset, password, listener, onDrawBitmapCompleteListener, onErrorListener, null);
    }

    private void load(byte[] fileBytes, String password, OnLoadCompleteListener onLoadCompleteListener, OnLoadCompleteListener onDrawBitmapCompleteListener, OnErrorListener onErrorListener) {

        if (!recycled) {
            throw new IllegalStateException("Don't call load on a PDF View without recycling it first.");
        }

        this.onLoadCompleteListener = onLoadCompleteListener;
        this.onDrawBitmapCompleteListener = onDrawBitmapCompleteListener;
        this.onErrorListener = onErrorListener;

        recycled = false;
        // Start decoding document

        if (DOWNLOAD_THREAD_POOL_EXECUTOR.getQueue().size() > 0) {
            clearThreads();
            final byte[] fBytes = fileBytes;
            final String fPassword = password;
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    decodingAsyncTask = new DoubleDecodingAsyncTask(fBytes, fPassword, DoublePDFView.this, pdfiumCore);
                    decodingAsyncTask.executeOnExecutor(DOWNLOAD_THREAD_POOL_EXECUTOR);
                }
            }, 50);
        } else {
            try {
                decodingAsyncTask = new DoubleDecodingAsyncTask(fileBytes, password, this, pdfiumCore);
                decodingAsyncTask.executeOnExecutor(DOWNLOAD_THREAD_POOL_EXECUTOR);
            } catch (IllegalStateException e) {
                clearThreads();
                final byte[] fBytes = fileBytes;
                final String fPassword = password;
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        decodingAsyncTask = new DoubleDecodingAsyncTask(fBytes, fPassword, DoublePDFView.this, pdfiumCore);
                        decodingAsyncTask.executeOnExecutor(DOWNLOAD_THREAD_POOL_EXECUTOR);
                    }
                }, 50);
            }
        }
    }

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final int KEEP_ALIVE_SECONDS = 30;
    private static final BlockingQueue<Runnable> sPoolWorkQueue =
            new LinkedBlockingQueue<Runnable>(128);

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "AsyncTask Download #" + mCount.getAndIncrement());
        }
    };

    public static ThreadPoolExecutor DOWNLOAD_THREAD_POOL_EXECUTOR;

    static {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                sPoolWorkQueue, sThreadFactory);
        threadPoolExecutor.allowCoreThreadTimeOut(true);

        DOWNLOAD_THREAD_POOL_EXECUTOR = threadPoolExecutor;

    }

    public static void clearThreads() {
        DOWNLOAD_THREAD_POOL_EXECUTOR.shutdownNow();
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                sPoolWorkQueue, sThreadFactory);
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        DOWNLOAD_THREAD_POOL_EXECUTOR = threadPoolExecutor;
    }


    private void load(byte[] leftBytes, byte[] rightBytes, String password, OnLoadCompleteListener onLoadCompleteListener, OnLoadCompleteListener onDrawBitmapCompleteListener, OnErrorListener onErrorListener) {

        if (!recycled) {
            throw new IllegalStateException("Don't call load on a PDF View without recycling it first.");
        }

        this.onLoadCompleteListener = onLoadCompleteListener;
        this.onDrawBitmapCompleteListener = onDrawBitmapCompleteListener;
        this.onErrorListener = onErrorListener;

        recycled = false;
        // Start decoding document
        decodingAsyncTask = new DoubleDecodingAsyncTask(leftBytes, rightBytes, password, this, pdfiumCore);
        decodingAsyncTask.executeOnExecutor(DOWNLOAD_THREAD_POOL_EXECUTOR);
    }

    private void load(Bitmap readyBitmap, OnLoadCompleteListener listener, OnLoadCompleteListener onDrawBitmapCompleteListener) {
        this.onLoadCompleteListener = listener;
        this.onDrawBitmapCompleteListener = onDrawBitmapCompleteListener;
        this.readyBitmap = readyBitmap;
        recycled = false;
        state = State.LOADED;
        this.pageWidth = readyBitmap.getWidth();
        this.pageHeight = readyBitmap.getHeight();
        documentPageCount = 1;
        calculateOptimalWidthAndHeight();
        moveTo(0, 0, true);
        redraw();
    }

    private void load(String path, String rpath, boolean isAsset, String password, OnLoadCompleteListener onLoadCompleteListener, OnLoadCompleteListener onDrawBitmapCompleteListener, OnErrorListener onErrorListener, int[] userPages) {

        if (!recycled) {
            throw new IllegalStateException("Don't call load on a PDF View without recycling it first.");
        }

        // Manage UserPages if not null
        if (userPages != null) {
            this.originalUserPages = userPages;
            this.filteredUserPages = ArrayUtils.deleteDuplicatedPages(originalUserPages);
            this.filteredUserPageIndexes = ArrayUtils.calculateIndexesInDuplicateArray(originalUserPages);
        }

        this.onLoadCompleteListener = onLoadCompleteListener;
        this.onDrawBitmapCompleteListener = onDrawBitmapCompleteListener;
        this.onErrorListener = onErrorListener;

        recycled = false;
        // Start decoding document
        if (rpath != null) {
            decodingAsyncTask = new DoubleDecodingAsyncTask(path, rpath, isAsset, password, this, pdfiumCore);
        } else {
            decodingAsyncTask = new DoubleDecodingAsyncTask(path, isAsset, password, this, pdfiumCore);
        }
        decodingAsyncTask.executeOnExecutor(DOWNLOAD_THREAD_POOL_EXECUTOR);
    }

    public void addAdditionalSingleTapListener(OnClickListener listener) {
        if (dragPinchManager != null)
            dragPinchManager.setAdditionalSingleTapDetector(listener);
    }

    public void addAdditionalZoomListener(OnZoomListener listener) {
        if (dragPinchManager != null)
            dragPinchManager.setAdditionalZoomDetector(listener);
    }

    /**
     * Go to the given page.
     *
     * @param page Page index.
     */
    public void jumpTo(int page, boolean withAnimation) {
        if (swipeVertical) {
            float toY = -page * toCurrentScale(optimalPageHeight);
            if (withAnimation) {
                animationManager.startYAnimation(currentYOffset, toY);
            } else {
                moveTo(currentXOffset, toY);
            }
        } else {
            float toX = -page * toCurrentScale(optimalPageWidth);
            if (withAnimation) {
                animationManager.startXAnimation(currentXOffset, toX);
            } else {
                moveTo(toX, currentYOffset);
            }
        }
        showPage(page);
    }

    public void jumpTo(int page) {
        jumpTo(page, false);
    }

    void showPage(int pageNb) {
        if (readyBitmap != null) return;
        if (recycled) {
            return;
        }
        state = State.SHOWN;

        // Check the page number and makes the
        // difference between UserPages and DocumentPages
        pageNb = determineValidPageNumberFrom(pageNb);
        currentPage = pageNb;
        currentFilteredPage = pageNb;
        if (filteredUserPageIndexes != null) {
            if (pageNb >= 0 && pageNb < filteredUserPageIndexes.length) {
                pageNb = filteredUserPageIndexes[pageNb];
                currentFilteredPage = pageNb;
            }
        }

        loadPages();

        if (scrollHandle != null && !documentFitsView()) {
            scrollHandle.setPageNum(currentPage + 1);
        }

        if (onPageChangeListener != null) {
            onPageChangeListener.onPageChanged(currentPage, getPageCount());
        }
    }

    /**
     * Get current position as ratio of document length to visible area.
     * 0 means that document start is visible, 1 that document end is visible
     *
     * @return offset between 0 and 1
     */
    public float getPositionOffset() {
        float offset;
        if (swipeVertical) {
            offset = -currentYOffset / (getPageCount() * toCurrentScale(optimalPageHeight) - getHeight());
        } else {
            offset = -currentXOffset / (getPageCount() * toCurrentScale(optimalPageWidth) - getWidth());
        }
        return MathUtils.limit(offset, 0, 1);
    }

    /**
     * @param progress   must be between 0 and 1
     * @param moveHandle whether to move scroll handle
     * @see DoublePDFView#getPositionOffset()
     */
    public void setPositionOffset(float progress, boolean moveHandle) {
        if (swipeVertical) {
            moveTo(currentXOffset, (-getPageCount() * toCurrentScale(optimalPageHeight) + getHeight()) * progress, moveHandle);
        } else {
            moveTo((-getPageCount() * toCurrentScale(optimalPageWidth) + getWidth()) * progress, currentYOffset, moveHandle);
        }
        loadPageByOffset();
    }

    public void setPositionOffset(float progress) {
        setPositionOffset(progress, true);
    }

    public void stopFling() {
        animationManager.stopFling();
    }

    public int getPageCount() {
        if (originalUserPages != null) {
            return originalUserPages.length;
        }
        return documentPageCount;
    }

    public void enableSwipe(boolean enableSwipe) {
        dragPinchManager.setSwipeEnabled(enableSwipe);
    }

    public void enableDoubletap(boolean enableDoubletap) {
        this.dragPinchManager.enableDoubletap(enableDoubletap);
    }

    private void setOnPageChangeListener(OnPageChangeListener onPageChangeListener) {
        this.onPageChangeListener = onPageChangeListener;
    }

    OnPageChangeListener getOnPageChangeListener() {
        return this.onPageChangeListener;
    }

    private void setOnPageScrollListener(OnPageScrollListener onPageScrollListener) {
        this.onPageScrollListener = onPageScrollListener;
    }

    OnPageScrollListener getOnPageScrollListener() {
        return this.onPageScrollListener;
    }

    private void setOnDrawListener(OnDrawListener onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public void recycle() {

        animationManager.stopAll();

        // Stop tasks
        if (renderingAsyncTask != null) {
            renderingAsyncTask.cancel(true);
        }
        if (decodingAsyncTask != null) {
            decodingAsyncTask.cancel(true);
        }

        // Clear caches
        cacheManager.recycle();

        if (scrollHandle != null && isScrollHandleInit) {
            scrollHandle.destroyLayout();
        }

        if (pdfiumCore != null && pdfDocument != null) {
            pdfiumCore.closeDocument(pdfDocument);
        }
        if (pdfiumCore != null && pdfRightDocument != null) {
            pdfiumCore.closeDocument(pdfRightDocument);
        }

        originalUserPages = null;
        filteredUserPages = null;
        filteredUserPageIndexes = null;
        pdfDocument = null;
        pdfRightDocument = null;
        scrollHandle = null;
        isScrollHandleInit = false;
        currentXOffset = currentYOffset = 0;
        zoom = 1f;
        recycled = true;
        state = State.DEFAULT;
    }

    public boolean isRecycled() {
        return recycled;
    }

    @Override
    protected void onDetachedFromWindow() {
        recycle();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (isInEditMode()) {
            return;
        }
        animationManager.stopAll();
        calculateOptimalWidthAndHeight();
        loadPages();
        if (swipeVertical)
            moveTo(currentXOffset, calculateCenterOffsetForPage(currentFilteredPage));
        else
            moveTo(calculateCenterOffsetForPage(currentFilteredPage), currentYOffset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isInEditMode()) {
            return;
        }
        // As I said in this class javadoc, we can think of this canvas as a huge
        // strip on which we draw all the images. We actually only draw the rendered
        // parts, of course, but we render them in the place they belong in this huge
        // strip.

        // That's where Canvas.translate(x, y) becomes very helpful.
        // This is the situation :
        //  _______________________________________________
        // |   			 |					 			   |
        // | the actual  |					The big strip  |
        // |	canvas	 | 								   |
        // |_____________|								   |
        // |_______________________________________________|
        //
        // If the rendered part is on the bottom right corner of the strip
        // we can draw it but we won't see it because the canvas is not big enough.

        // But if we call translate(-X, -Y) on the canvas just before drawing the object :
        //  _______________________________________________
        // |   			  					  _____________|
        // |   The big strip     			 |			   |
        // |		    					 |	the actual |
        // |								 |	canvas	   |
        // |_________________________________|_____________|
        //
        // The object will be on the canvas.
        // This technique is massively used in this method, and allows
        // abstraction of the screen position when rendering the parts.

        // Draws background
        Drawable bg = getBackground();
        if (bg == null) {
            canvas.drawColor(Color.WHITE);
        } else {
            bg.draw(canvas);
        }


        if (recycled) {
            return;
        }

        if (state != State.SHOWN && readyBitmap == null) {
            return;
        }

        // Moves the canvas before drawing any element
        float currentXOffset = this.currentXOffset;
        float currentYOffset = this.currentYOffset;
        canvas.translate(currentXOffset, currentYOffset);
        if (readyBitmap == null) {
            // Draws thumbnails
            for (DoublePagePart part : cacheManager.getThumbnails()) {
                drawPart(canvas, part);
            }

            // Draws parts
            if (!cacheManager.getPageParts().isEmpty()) {
                bitmapRatio = ((float) cacheManager.getPageParts().get(0).getRenderedBitmap().getHeight()) /
                        ((float) cacheManager.getPageParts().get(0).getRenderedBitmap().getWidth());
            }
            if (onDrawBitmapCompleteListener != null) {
                onDrawBitmapCompleteListener.loadComplete(0);
                onDrawBitmapCompleteListener = null;
            }
            for (DoublePagePart part : cacheManager.getPageParts()) {
                drawPart(canvas, part);
            }

        } else {
            drawBitmap(canvas, readyBitmap);
        }
        // Draws the user layer
        if (onDrawListener != null) {
            canvas.translate(toCurrentScale(currentFilteredPage * optimalPageWidth), 0);

            onDrawListener.onLayerDrawn(canvas, //
                    toCurrentScale(optimalPageWidth), //
                    toCurrentScale(optimalPageHeight),
                    currentPage);

            canvas.translate(-toCurrentScale(currentFilteredPage * optimalPageWidth), 0);
        }

        // Restores the canvas position
        canvas.translate(-currentXOffset, -currentYOffset);
    }


    public float getBitmapRatio() {
        return bitmapRatio;
    }
    public boolean twoPageMode = false;
    private float bitmapRatio = 0;


    /**
     * Draw a given PagePart on the canvas
     */
    Set<Pair<Boolean, Pair<Integer, Integer>>> pairs = new HashSet<>();

    private void drawPart(Canvas canvas, DoublePagePart part) {
        // Can seem strange, but avoid lot of calls
        RectF pageRelativeBounds = part.getPageRelativeBounds();
        Bitmap renderedBitmap = part.getRenderedBitmap();

        if (renderedBitmap.isRecycled()) {
            return;
        }

        pairs.add(new Pair<>(part.isRightPage(), new Pair<>(part.row, part.col)));

        // Move to the target page
        float localTranslationX = 0;
        float localTranslationY = 0;
        if (swipeVertical)
            localTranslationY = toCurrentScale(part.getUserPage() * optimalPageHeight);
        else
            localTranslationX = toCurrentScale(part.getUserPage() * optimalPageWidth);
        canvas.translate(localTranslationX, localTranslationY);

        Rect srcRect = new Rect(0, 0, renderedBitmap.getWidth(),
                renderedBitmap.getHeight());

        float offsetX = twoPageMode
                ? toCurrentScale(pageRelativeBounds.left * optimalPageWidth/2 + (part.isRightPage()? 1 : 0)*(optimalPageWidth/2))
                : toCurrentScale(pageRelativeBounds.left * optimalPageWidth);
        float offsetY = toCurrentScale(pageRelativeBounds.top * optimalPageHeight);
        float width = twoPageMode? toCurrentScale(pageRelativeBounds.width() * optimalPageWidth/2) :
                toCurrentScale(pageRelativeBounds.width() * optimalPageWidth);
        float height = toCurrentScale(pageRelativeBounds.height() * optimalPageHeight);

        // If we use float values for this rectangle, there will be
        // a possible gap between page parts, especially when
        // the zoom level is high.
        RectF dstRect = new RectF((int) offsetX, (int) offsetY,
                (int) (offsetX + width),
                (int) (offsetY + height));

        // Check if bitmap is in the screen

        float translationX = currentXOffset + localTranslationX;
        float translationY = currentYOffset + localTranslationY;

        if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 ||
                translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
            canvas.translate(-localTranslationX, -localTranslationY);

            return;
        }

        canvas.drawBitmap(renderedBitmap, srcRect, dstRect, paint);

        if (Constants.DEBUG_MODE) {
            debugPaint.setColor(part.getUserPage() % 2 == 0 ? Color.RED : Color.BLUE);
            canvas.drawRect(dstRect, debugPaint);
        }

        // Restore the canvas position
        canvas.translate(-localTranslationX, -localTranslationY);

    }


    private void drawBitmap(Canvas canvas, Bitmap bitmap) {
        // Can seem strange, but avoid lot of calls
        RectF pageRelativeBounds = new RectF(0f, 0f, 1f, 1f);
        Bitmap renderedBitmap = bitmap;

        if (renderedBitmap.isRecycled()) {
            return;
        }
        bitmapRatio = (1.0f / MathUtils.ceil(getOptimalPageHeight() / 256.0f)) / (1.0f / MathUtils.ceil(getOptimalPageWidth() / 256.0f));
        // Move to the target page
        float localTranslationX = 0;
        float localTranslationY = 0;
        if (swipeVertical)
            localTranslationY = toCurrentScale(0 * optimalPageHeight);
        else
            localTranslationX = toCurrentScale(0 * optimalPageWidth);
        canvas.translate(localTranslationX, localTranslationY);

        Rect srcRect = new Rect(0, 0, renderedBitmap.getWidth(),
                renderedBitmap.getHeight());

        float offsetX = toCurrentScale(pageRelativeBounds.left * optimalPageWidth);
        float offsetY = toCurrentScale(pageRelativeBounds.top * optimalPageHeight);
        float width = toCurrentScale(pageRelativeBounds.width() * optimalPageWidth);
        float height = toCurrentScale(pageRelativeBounds.height() * optimalPageHeight);

        // If we use float values for this rectangle, there will be
        // a possible gap between page parts, especially when
        // the zoom level is high.
        RectF dstRect = new RectF((int) offsetX, (int) offsetY,
                (int) (offsetX + width),
                (int) (offsetY + height));

        // Check if bitmap is in the screen
        float translationX = currentXOffset + localTranslationX;
        float translationY = currentYOffset + localTranslationY;
        if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 ||
                translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
            canvas.translate(-localTranslationX, -localTranslationY);
            return;
        }

        canvas.drawBitmap(renderedBitmap, srcRect, dstRect, paint);

        // Restore the canvas position
        canvas.translate(-localTranslationX, -localTranslationY);
        if (onLoadCompleteListener != null) {
            onLoadCompleteListener.loadComplete(0);
        }

    }

    /**
     * Load all the parts around the center of the screen,
     * taking into account X and Y offsets, zoom level, and
     * the current page displayed
     */
    public void loadPages() {
        if (readyBitmap != null) return;
        if (optimalPageWidth == 0 || optimalPageHeight == 0) {
            return;
        }

        // Cancel all current tasks
        renderingAsyncTask.removeAllTasks();
        cacheManager.makeANewSet();

        pagesLoader.loadPages(twoPageMode);
        redraw();
    }

    /**
     * Called when the PDF is loaded
     */
    public void loadCompleteWithCheck(PdfDocument pdfDocument, PdfDocument pdfRightDocument, boolean allPages) {
        if (DOWNLOAD_THREAD_POOL_EXECUTOR.getQueue().size() > 0) {
            clearThreads();
            final PdfDocument fpdfDocument = pdfDocument;
            final PdfDocument fpdfRightDocument = pdfRightDocument;
            final boolean fallPages = allPages;
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    loadComplete(fpdfDocument, fpdfRightDocument, fallPages);
                }
            }, 50);
        } else {
            loadComplete(pdfDocument, pdfRightDocument, allPages);
        }
    }

    public void loadComplete(PdfDocument pdfDocument, PdfDocument pdfRightDocument, boolean allPages) {
        state = State.LOADED;
        this.documentPageCount = pdfiumCore.getPageCount(pdfDocument);

        int firstPageIdx = 0;
        if (originalUserPages != null) {
            firstPageIdx = originalUserPages[0];
        }

        // We assume all the pages are the same size
        this.pdfDocument = pdfDocument;
        this.pdfRightDocument = pdfRightDocument;
        if (allPages)
            pdfiumCore.openPage(pdfDocument, 0, this.documentPageCount - 1);
        else
            pdfiumCore.openPage(pdfDocument, firstPageIdx);
        this.pageWidth = pdfiumCore.getPageWidth(pdfDocument, firstPageIdx);
        this.pageHeight = pdfiumCore.getPageHeight(pdfDocument, firstPageIdx);

        if (pdfRightDocument != null) {
            this.twoPageMode = true;
            pdfiumCore.openPage(pdfRightDocument, 0, this.documentPageCount - 1);
            this.pageRightWidth = pdfiumCore.getPageWidth(pdfRightDocument, firstPageIdx);
        }

        calculateOptimalWidthAndHeight();

        pagesLoader = new DoublePagesLoader(this);

        bitmapRatio = (1.0f / MathUtils.ceil(getOptimalPageHeight() / 256.0f)) / (1.0f / MathUtils.ceil(getOptimalPageWidth() / 256.0f));


        renderingAsyncTask = new DoubleRenderingAsyncTask(this, pdfiumCore, pdfDocument, pdfRightDocument);
        renderingAsyncTask.executeOnExecutor(DOWNLOAD_THREAD_POOL_EXECUTOR);

      /*  int w = Math.round(renderingAsyncTask.width);
        int h = Math.round(renderingAsyncTask.height);
        Bitmap render = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        calculateBounds(w, h, renderingTask.bounds);*/

        if (scrollHandle != null) {
            scrollHandle.setupLayout(this);
            isScrollHandleInit = true;
        }

        // Notify the listener
        jumpTo(defaultPage, false);
        if (onLoadCompleteListener != null) {
            onLoadCompleteListener.loadComplete(documentPageCount);
        }
    }

    public void loadError(Throwable t) {
        state = State.ERROR;
        recycle();
        invalidate();
        if (this.onErrorListener != null) {
            this.onErrorListener.onError(t);
        } else {
            Log.e("PDFView", "load pdf error", t);
        }
    }

    void redraw() {
        invalidate();
    }

    /**
     * Called when a rendering task is over and
     * a PagePart has been freshly created.
     *
     * @param part The created PagePart.
     */
    public void onBitmapRendered(DoublePagePart part) {
        if (part.isThumbnail()) {
            cacheManager.cacheThumbnail(part);
        } else {
            cacheManager.cachePart(part);
        }
        redraw();
    }

    /**
     * Given the UserPage number, this method restrict it
     * to be sure it's an existing page. It takes care of
     * using the user defined pages if any.
     *
     * @param userPage A page number.
     * @return A restricted valid page number (example : -2 => 0)
     */
    private int determineValidPageNumberFrom(int userPage) {
        if (userPage <= 0) {
            return 0;
        }
        if (originalUserPages != null) {
            if (userPage >= originalUserPages.length) {
                return originalUserPages.length - 1;
            }
        } else {
            if (userPage >= documentPageCount) {
                return documentPageCount - 1;
            }
        }
        return userPage;
    }

    /**
     * Calculate the x/y-offset needed to have the given
     * page centered on the screen. It doesn't take into
     * account the zoom level.
     *
     * @param pageNb The page number.
     * @return The x/y-offset to use to have the pageNb centered.
     */
    private float calculateCenterOffsetForPage(int pageNb) {
        if (swipeVertical) {
            float imageY = -(pageNb * optimalPageHeight);
            imageY += getHeight() / 2 - optimalPageHeight / 2;
            return imageY;
        } else {
            float imageX = -(pageNb * optimalPageWidth);
            imageX += getWidth() / 2 - optimalPageWidth / 2;
            return imageX;
        }
    }

    /**
     * Calculate the optimal width and height of a page
     * considering the area width and height
     */
    private void calculateOptimalWidthAndHeight() {
        if (state == State.DEFAULT || getWidth() == 0) {
            return;
        }

        float maxWidth = getWidth(), maxHeight = getHeight();
        float w = pageWidth, h = pageHeight;
        w += pageRightWidth;
        float ratio = w / h;
        w = maxWidth;
        h = (float) Math.floor(maxWidth / ratio);
        if (h > maxHeight) {
            h = maxHeight;
            w = (float) Math.floor(maxHeight * ratio);
        }

        optimalPageWidth = w;
        optimalPageHeight = h;

    }

    public void moveTo(float offsetX, float offsetY) {
        moveTo(offsetX, offsetY, true);
    }

    /**
     * Move to the given X and Y offsets, but check them ahead of time
     * to be sure not to go outside the the big strip.
     *
     * @param offsetX    The big strip X offset to use as the left border of the screen.
     * @param offsetY    The big strip Y offset to use as the right border of the screen.
     * @param moveHandle whether to move scroll handle or not
     */
    public void moveTo(float offsetX, float offsetY, boolean moveHandle) {
        if (swipeVertical) {
            // Check X offset
            if (toCurrentScale(optimalPageWidth) < getWidth()) {
                offsetX = getWidth() / 2 - toCurrentScale(optimalPageWidth) / 2;
            } else {
                if (offsetX > 0) {
                    offsetX = 0;
                } else if (offsetX + toCurrentScale(optimalPageWidth) < getWidth()) {
                    offsetX = getWidth() - toCurrentScale(optimalPageWidth);
                }
            }

            // Check Y offset
            if (getPageCount() * toCurrentScale(optimalPageHeight) < getHeight()) { // whole document height visible on screen
                offsetY = (getHeight() - getPageCount() * toCurrentScale(optimalPageHeight)) / 2;
            } else {
                if (offsetY > 0) { // top visible
                    offsetY = 0;
                } else if (offsetY + toCurrentScale(getPageCount() * optimalPageHeight) < getHeight()) { // bottom visible
                    offsetY = -toCurrentScale(getPageCount() * optimalPageHeight) + getHeight();
                }
            }

            if (offsetY < currentYOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetY > currentYOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        } else {
            // Check Y offset
            if (toCurrentScale(optimalPageHeight) < getHeight()) {
                offsetY = getHeight() / 2 - toCurrentScale(optimalPageHeight) / 2;
            } else {
                if (offsetY > 0) {
                    offsetY = 0;
                } else if (offsetY + toCurrentScale(optimalPageHeight) < getHeight()) {
                    offsetY = getHeight() - toCurrentScale(optimalPageHeight);
                }
            }

            // Check X offset
            if (getPageCount() * toCurrentScale(optimalPageWidth) < getWidth()) { // whole document width visible on screen
                offsetX = (getWidth() - getPageCount() * toCurrentScale(optimalPageWidth)) / 2;
            } else {
                if (offsetX > 0) { // left visible
                    offsetX = 0;
                } else if (offsetX + toCurrentScale(getPageCount() * optimalPageWidth) < getWidth()) { // right visible
                    offsetX = -toCurrentScale(getPageCount() * optimalPageWidth) + getWidth();
                }
            }

            if (offsetX < currentXOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetX > currentXOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        }

        currentXOffset = offsetX;
        currentYOffset = offsetY;
        float positionOffset = getPositionOffset();

        if (moveHandle && scrollHandle != null && !documentFitsView()) {
            scrollHandle.setScroll(positionOffset);
        }

        if (onPageScrollListener != null) {
            onPageScrollListener.onPageScrolled(getCurrentPage(), positionOffset);
        }

        redraw();
    }

    ScrollDir getScrollDir() {
        return scrollDir;
    }

    void loadPageByOffset() {
        float offset, optimal;
        if (readyBitmap != null) return;
        if (swipeVertical) {
            offset = currentYOffset;
            optimal = optimalPageHeight;
        } else {
            offset = currentXOffset;
            optimal = optimalPageWidth;
        }
        int page = (int) Math.floor((Math.abs(offset) + getHeight() / 5) / toCurrentScale(optimal));

        if (page >= 0 && page <= getPageCount() - 1 && page != getCurrentPage()) {
            showPage(page);
        } else {
            loadPages();
        }
    }

    int[] getFilteredUserPages() {
        return filteredUserPages;
    }

    int getDocumentPageCount() {
        return documentPageCount;
    }

    /**
     * Move relatively to the current position.
     *
     * @param dx The X difference you want to apply.
     * @param dy The Y difference you want to apply.
     * @see #moveTo(float, float)
     */
    public void moveRelativeTo(float dx, float dy) {
        moveTo(currentXOffset + dx, currentYOffset + dy);
    }

    /**
     * Change the zoom level
     */
    public void zoomTo(float zoom) {
        this.zoom = zoom;
    }

    /**
     * Change the zoom level, relatively to a pivot point.
     * It will call moveTo() to make sure the given point stays
     * in the middle of the screen.
     *
     * @param zoom  The zoom level.
     * @param pivot The point on the screen that should stays.
     */
    public void zoomCenteredTo(float zoom, PointF pivot) {
        float dzoom = zoom / this.zoom;
        zoomTo(zoom);
        float baseX = currentXOffset * dzoom;
        float baseY = currentYOffset * dzoom;
        baseX += (pivot.x - pivot.x * dzoom);
        baseY += (pivot.y - pivot.y * dzoom);
        moveTo(baseX, baseY);
    }

    /**
     * @see #zoomCenteredTo(float, PointF)
     */
    public void zoomCenteredRelativeTo(float dzoom, PointF pivot) {
        zoomCenteredTo(zoom * dzoom, pivot);
    }

    /**
     * Checks if whole document can be displayed on screen, doesn't include zoom
     *
     * @return true if whole document can displayed at once, false otherwise
     */
    public boolean documentFitsView() {
        if (swipeVertical) {
            return getPageCount() * optimalPageHeight < getHeight();
        } else {
            return getPageCount() * optimalPageWidth < getWidth();
        }
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public float getCurrentXOffset() {
        return currentXOffset;
    }

    public float getCurrentYOffset() {
        return currentYOffset;
    }

    public float toRealScale(float size) {
        return size / zoom;
    }

    public float toCurrentScale(float size) {
        return size * zoom;
    }

    public float getZoom() {
        return zoom;
    }

    public boolean isZooming() {
        return zoom != minZoom;
    }

    public float getOptimalPageWidth() {
        return optimalPageWidth;
    }

    public float getOptimalPageHeight() {
        return optimalPageHeight;
    }

    private void setDefaultPage(int defaultPage) {
        this.defaultPage = defaultPage;
    }

    public void resetZoom() {
        zoomTo(minZoom);
    }

    public void resetZoomWithAnimation() {
        zoomWithAnimation(minZoom);
    }

    public void zoomWithAnimation(float centerX, float centerY, float scale) {
        animationManager.startZoomAnimation(centerX, centerY, zoom, scale);
    }

    public void zoomWithAnimation(float scale) {
        animationManager.startZoomAnimation(getWidth() / 2, getHeight() / 2, zoom, scale);
    }

    private void setScrollHandle(DoubleScrollHandle scrollHandle) {
        this.scrollHandle = scrollHandle;
    }

    /**
     * Get page number at given offset
     *
     * @param positionOffset scroll offset between 0 and 1
     * @return page number at given offset, starting from 0
     */
    public int getPageAtPositionOffset(float positionOffset) {
        float optimalSize, viewDimension;
        int direction = scrollDir == ScrollDir.END ? 1 : -1;
        if (swipeVertical) {
            optimalSize = toCurrentScale(optimalPageHeight);
            viewDimension = getHeight() * direction;
        } else {
            optimalSize = toCurrentScale(optimalPageWidth);
            viewDimension = getWidth() * direction;
        }

        return (int) Math.floor((getPageCount() * positionOffset) + (viewDimension / 5 / optimalSize));
    }

    public float getMinZoom() {
        return minZoom;
    }

    public void setMinZoom(float minZoom) {
        this.minZoom = minZoom;
    }

    public float getMidZoom() {
        return midZoom;
    }

    public void setMidZoom(float midZoom) {
        this.midZoom = midZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public void setMaxZoom(float maxZoom) {
        this.maxZoom = maxZoom;
    }

    public void useBestQuality(boolean bestQuality) {
        this.bestQuality = bestQuality;
    }

    public boolean isBestQuality() {
        return bestQuality;
    }

    public boolean isSwipeVertical() {
        return swipeVertical;
    }

    public void setSwipeVertical(boolean swipeVertical) {
        this.swipeVertical = swipeVertical;
    }

    public void enableAnnotationRendering(boolean annotationRendering) {
        this.annotationRendering = annotationRendering;
    }

    public boolean isAnnotationRendering() {
        return annotationRendering;
    }

    public PdfDocument.Meta getDocumentMeta() {
        if (pdfDocument == null) {
            return null;
        }
        return pdfiumCore.getDocumentMeta(pdfDocument);
    }

    public List<PdfDocument.Bookmark> getTableOfContents() {
        if (pdfDocument == null) {
            return new ArrayList<>();
        }
        return pdfiumCore.getTableOfContents(pdfDocument);
    }

    /**
     * Use an asset file as the pdf source
     */
    public Configurator fromAsset(String assetName) {
        InputStream stream = null;
        try {
            stream = getContext().getAssets().open(assetName);
            return new Configurator(assetName, true);
        } catch (IOException e) {
            throw new FileNotFoundException(assetName + " does not exist.", e);
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException e) {

            }
        }
    }

    public Configurator fromAsset(String assetName, String rightAssetName) {
        InputStream stream = null;
        InputStream rstream = null;
        try {
            stream = getContext().getAssets().open(assetName);
            rstream = getContext().getAssets().open(rightAssetName);
            return new Configurator(assetName, rightAssetName, true);
        } catch (IOException e) {
            throw new FileNotFoundException(assetName + " does not exist.", e);
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
                if (rstream != null) {
                    rstream.close();
                }
            } catch (IOException e) {

            }
        }
    }

    /**
     * Use a file as the pdf source
     */
    public Configurator fromFile(File file) {
        if (!file.exists()) {
            throw new FileNotFoundException(file.getAbsolutePath() + " does not exist.");
        }
        return new Configurator(file.getAbsolutePath(), false);
    }


    public Configurator fromBitmap(Bitmap bitmap) {
        this.pageWidth = bitmap.getWidth();
        this.pageHeight = bitmap.getHeight();
        return new Configurator(bitmap);
    }

    /**
     * Use bytes as the pdf source
     */
    public Configurator fromBytes(byte[] bytes, String password) {
        return new Configurator(bytes).password(password);
    }

    public Configurator fromBytes(byte[] leftBytes, byte[] rightBytes, String password) {
        twoPageMode = true;
        return new Configurator(leftBytes, rightBytes).password(password);
    }

    /**
     * Use Uri as the pdf source, for use with content provider
     */
    public Configurator fromUri(Uri uri) {
        return new Configurator(uri.toString(), false);
    }

    private enum State {DEFAULT, LOADED, SHOWN, ERROR}

    public class Configurator {

        private final String path;

        private String rpath = null;

        private boolean twoPageMode;

        private final boolean isAsset;

        private int[] pageNumbers = null;

        private byte[] fileBytes = null;

        private byte[] fileRightBytes = null;

        private Bitmap readyBitmap = null;

        private boolean enableSwipe = true;

        private boolean enableDoubletap = true;

        private OnDrawListener onDrawListener;

        private OnLoadCompleteListener onLoadCompleteListener;

        private OnLoadCompleteListener onDrawBitmapCompleteListener;

        private OnErrorListener onErrorListener;

        private OnPageChangeListener onPageChangeListener;

        private OnPageScrollListener onPageScrollListener;

        private int defaultPage = 0;

        private boolean swipeHorizontal = false;

        private boolean annotationRendering = false;

        private String password = null;

        private DoubleScrollHandle scrollHandle = null;

        private Configurator(String path, boolean isAsset) {
            this.path = path;
            this.isAsset = isAsset;
        }

        private Configurator(String path, String rpath, boolean isAsset) {
            this.path = path;
            this.rpath = rpath;
            this.twoPageMode = true;
            this.isAsset = isAsset;
        }

        private Configurator(byte[] fileBytes) {
            this.path = "";
            this.isAsset = false;
            this.fileBytes = fileBytes;
        }

        private Configurator(byte[] fileBytes, byte[] fileRightBytes) {
            this.path = "";
            this.isAsset = false;
            this.fileBytes = fileBytes;
            this.fileRightBytes = fileRightBytes;
            this.twoPageMode = true;
        }

        private Configurator(Bitmap readyBitmap) {
            this.path = "";
            this.isAsset = false;
            this.readyBitmap = readyBitmap;
        }

        public Configurator pages(int... pageNumbers) {
            this.pageNumbers = pageNumbers;
            return this;
        }

        public Configurator enableSwipe(boolean enableSwipe) {
            this.enableSwipe = enableSwipe;
            return this;
        }

        public Configurator enableDoubletap(boolean enableDoubletap) {
            this.enableDoubletap = enableDoubletap;
            return this;
        }

        public Configurator enableAnnotationRendering(boolean annotationRendering) {
            this.annotationRendering = annotationRendering;
            return this;
        }

        public Configurator onDraw(OnDrawListener onDrawListener) {
            this.onDrawListener = onDrawListener;
            return this;
        }

        public Configurator onLoad(OnLoadCompleteListener onLoadCompleteListener) {
            this.onLoadCompleteListener = onLoadCompleteListener;
            return this;
        }

        public Configurator onDrawComplete(OnLoadCompleteListener onDrawBitmapCompleteListener) {
            this.onDrawBitmapCompleteListener = onDrawBitmapCompleteListener;
            return this;
        }


        public Configurator onPageScroll(OnPageScrollListener onPageScrollListener) {
            this.onPageScrollListener = onPageScrollListener;
            return this;
        }

        public Configurator onError(OnErrorListener onErrorListener) {
            this.onErrorListener = onErrorListener;
            return this;
        }

        public Configurator onPageChange(OnPageChangeListener onPageChangeListener) {
            this.onPageChangeListener = onPageChangeListener;
            return this;
        }

        public Configurator defaultPage(int defaultPage) {
            this.defaultPage = defaultPage;
            return this;
        }

        public Configurator swipeHorizontal(boolean swipeHorizontal) {
            this.swipeHorizontal = swipeHorizontal;
            return this;
        }

        public Configurator password(String password) {
            this.password = password;
            return this;
        }

        public Configurator scrollHandle(DoubleScrollHandle scrollHandle) {
            this.scrollHandle = scrollHandle;
            return this;
        }

        public void load() {
            DoublePDFView.this.recycle();
            DoublePDFView.this.setOnDrawListener(onDrawListener);
            DoublePDFView.this.setOnPageChangeListener(onPageChangeListener);
            DoublePDFView.this.setOnPageScrollListener(onPageScrollListener);
            DoublePDFView.this.enableSwipe(enableSwipe);
            DoublePDFView.this.enableDoubletap(enableDoubletap);
            DoublePDFView.this.setDefaultPage(defaultPage);
            DoublePDFView.this.setSwipeVertical(!swipeHorizontal);
            DoublePDFView.this.enableAnnotationRendering(annotationRendering);
            DoublePDFView.this.setScrollHandle(scrollHandle);
            DoublePDFView.this.dragPinchManager.setSwipeVertical(swipeVertical);
            if (fileBytes != null) {
                if (twoPageMode) {
                    DoublePDFView.this.load(fileBytes, fileRightBytes, password, onLoadCompleteListener, onDrawBitmapCompleteListener, onErrorListener);
                } else {
                    DoublePDFView.this.load(fileBytes, password, onLoadCompleteListener, onDrawBitmapCompleteListener, onErrorListener);
                }
            } else if (readyBitmap != null) {
                DoublePDFView.this.load(readyBitmap, onLoadCompleteListener, onDrawBitmapCompleteListener);
            } else if (pageNumbers != null) {
                DoublePDFView.this.load(path, rpath, isAsset, password, onLoadCompleteListener, onDrawBitmapCompleteListener, onErrorListener, pageNumbers);
            } else {
                DoublePDFView.this.load(path, rpath, isAsset, password, onLoadCompleteListener, onDrawBitmapCompleteListener, onErrorListener);
            }
        }
    }
}
