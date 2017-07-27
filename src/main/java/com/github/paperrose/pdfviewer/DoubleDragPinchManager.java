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

import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.github.paperrose.pdfviewer.scroll.DoubleScrollHandle;

import java.util.HashSet;
import java.util.Set;

import static android.view.View.*;
import static com.github.paperrose.pdfviewer.util.Constants.Pinch.MAXIMUM_ZOOM;
import static com.github.paperrose.pdfviewer.util.Constants.Pinch.MINIMUM_ZOOM;

/**
 * This Manager takes care of moving the PDFView,
 * set its zoom track user actions.
 */
class DoubleDragPinchManager implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener, OnTouchListener {

    private DoublePDFView pdfView;
    private DoubleAnimationManager animationManager;

    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;


    private Set<OnClickListener> additionalTapListeners = new HashSet<>();
    private Set<DoublePDFView.OnZoomListener> additionalZoomListeners = new HashSet<>();

    private boolean isSwipeEnabled;

    private boolean swipeVertical;

    private boolean scrolling = false;

    public DoubleDragPinchManager(DoublePDFView pdfView, DoubleAnimationManager animationManager) {
        this.pdfView = pdfView;
        this.animationManager = animationManager;
        this.isSwipeEnabled = false;
        this.swipeVertical = pdfView.isSwipeVertical();
        gestureDetector = new GestureDetector(pdfView.getContext(), this);
        scaleGestureDetector = new ScaleGestureDetector(pdfView.getContext(), this);
        pdfView.setOnTouchListener(this);
    }

    public void enableDoubletap(boolean enableDoubletap) {
        if (enableDoubletap) {
            gestureDetector.setOnDoubleTapListener(this);
        } else {
            gestureDetector.setOnDoubleTapListener(null);
        }
    }

    public boolean isZooming() {
        return pdfView.isZooming();
    }

    private boolean isPageChange(float distance) {
        return Math.abs(distance) > Math.abs(pdfView.toCurrentScale(swipeVertical ? pdfView.getOptimalPageHeight() : pdfView.getOptimalPageWidth()) / 2);
    }

    public void setSwipeEnabled(boolean isSwipeEnabled) {
        this.isSwipeEnabled = isSwipeEnabled;
    }

    public void setSwipeVertical(boolean swipeVertical) {
        this.swipeVertical = swipeVertical;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        for (OnClickListener view : additionalTapListeners)
            view.onClick(null);
        DoubleScrollHandle ps = pdfView.getScrollHandle();
        if (ps != null && !pdfView.documentFitsView()) {
            if (!ps.shown()) {
                ps.show();
            } else {
                ps.hide();
            }
        }
        return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        float zoom;
        if (pdfView.getZoom() < pdfView.getMidZoom()) {
            zoom = pdfView.getMidZoom();
            pdfView.zoomWithAnimation(e.getX(), e.getY(), pdfView.getMidZoom());
        } else if (pdfView.getZoom() < pdfView.getMaxZoom()) {
            zoom = pdfView.getMaxZoom();
            pdfView.zoomWithAnimation(e.getX(), e.getY(), pdfView.getMaxZoom());
        } else {
            zoom = 1f;
            pdfView.resetZoomWithAnimation();
        }
        for (DoublePDFView.OnZoomListener view : additionalZoomListeners)
            view.onZoom(zoom);
        return true;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        animationManager.stopFling();
        return true;
    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        scrolling = true;
        if (isZooming() || isSwipeEnabled) {
            pdfView.moveRelativeTo(-distanceX, -distanceY);
        }
        pdfView.loadPageByOffset();

        return true;
    }

    public void onScrollEnd(MotionEvent event) {
        pdfView.loadPages();
        hideHandle();
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        int xOffset = (int) pdfView.getCurrentXOffset();
        int yOffset = (int) pdfView.getCurrentYOffset();
        animationManager.startFlingAnimation(xOffset,
                yOffset, (int) (velocityX),
                (int) (velocityY),
                xOffset * (swipeVertical ? 2 : pdfView.getPageCount()), 0,
                yOffset * (swipeVertical ? pdfView.getPageCount() : 2), 0);

        return true;
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        float dr = detector.getScaleFactor();
        float wantedZoom = pdfView.getZoom() * dr;
        if (wantedZoom < MINIMUM_ZOOM) {
            dr = MINIMUM_ZOOM / pdfView.getZoom();
        } else if (wantedZoom > MAXIMUM_ZOOM) {
            dr = MAXIMUM_ZOOM / pdfView.getZoom();
        }
        pdfView.zoomCenteredRelativeTo(dr, new PointF(detector.getFocusX(), detector.getFocusY()));
        return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        pdfView.loadPages();
        for (DoublePDFView.OnZoomListener view : additionalZoomListeners)
            view.onZoom(pdfView.getZoom());
        hideHandle();
    }

    public void setAdditionalSingleTapDetector(OnClickListener additionalDetector) {
        this.additionalTapListeners.add(additionalDetector);
    }

    public void removeAdditionalSingleTapDetector(OnClickListener additionalDetector) {
        this.additionalTapListeners.remove(additionalDetector);
    }

    public void setAdditionalZoomDetector(DoublePDFView.OnZoomListener additionalDetector) {
        this.additionalZoomListeners.add(additionalDetector);
    }

    public void removeAdditionalZoomDetector(DoublePDFView.OnZoomListener additionalDetector) {
        this.additionalZoomListeners.remove(additionalDetector);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        boolean retVal = scaleGestureDetector.onTouchEvent(event);
    //    for (View view : additionalDetectors)
     //       view.onTouchEvent(event);
        retVal = gestureDetector.onTouchEvent(event) || retVal;

        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (scrolling) {
                scrolling = false;
                onScrollEnd(event);
            }
        }
        return retVal;
    }

    private void hideHandle() {
        if (pdfView.getScrollHandle() != null && pdfView.getScrollHandle().shown()) {
            pdfView.getScrollHandle().hideDelayed();
        }
    }
}
