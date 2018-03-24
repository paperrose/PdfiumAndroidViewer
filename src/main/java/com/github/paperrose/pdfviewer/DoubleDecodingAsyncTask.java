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

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;

import com.github.paperrose.pdfviewer.util.FileUtils;
import com.shockwave.pdfium.CryptLab;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;
import java.io.IOException;

class DoubleDecodingAsyncTask extends AsyncTask<Void, Void, Throwable> {

    private boolean cancelled;

    private String path;
    private String rpath = null;

    private boolean isAsset;

    private DoublePDFView pdfView;
    private boolean isByteArray;
    private byte[] fileBytes;
    private byte[] rightBytes;
    private boolean twoPageMode = false;


    private Context context;
    private PdfiumCore pdfiumCore;
    private PdfDocument pdfDocument;
    private PdfDocument pdfRightDocument;
    private String password;

    public DoubleDecodingAsyncTask(String path, boolean isAsset, String password, DoublePDFView pdfView, PdfiumCore pdfiumCore) {
        this.cancelled = false;
        this.pdfView = pdfView;
        this.isAsset = isAsset;
        this.isByteArray = false;
        this.password = password;
        this.pdfiumCore = pdfiumCore;
        this.path = path;
        context = pdfView.getContext();
    }

    public DoubleDecodingAsyncTask(String lpath, String rpath, boolean isAsset, String password, DoublePDFView pdfView, PdfiumCore pdfiumCore) {
        this.cancelled = false;
        this.pdfView = pdfView;
        this.isAsset = isAsset;
        this.isByteArray = false;
        this.twoPageMode = true;
        this.password = password;
        this.pdfiumCore = pdfiumCore;
        this.path = lpath;
        this.rpath = rpath;
        context = pdfView.getContext();
    }

    public DoubleDecodingAsyncTask(byte[] fileBytes, String password, DoublePDFView pdfView, PdfiumCore pdfiumCore) {
        this.cancelled = false;
        this.pdfView = pdfView;
        this.password = password;
        this.pdfiumCore = pdfiumCore;
        this.isByteArray = true;
        this.fileBytes = fileBytes;
        context = pdfView.getContext();
    }

    public DoubleDecodingAsyncTask(byte[] leftBytes, byte[] rightBytes, String password, DoublePDFView pdfView, PdfiumCore pdfiumCore) {
        this.cancelled = false;
        this.twoPageMode = true;
        this.pdfView = pdfView;
        this.password = password;
        this.pdfiumCore = pdfiumCore;
        this.isByteArray = true;
        this.fileBytes = leftBytes;
        this.rightBytes = rightBytes;
        context = pdfView.getContext();
    }


    @Override
    protected Throwable doInBackground(Void... params) {
        try {
            if (cancelled)
                return null;
            if (isByteArray) {
                if (password != null) {
                    fileBytes = CryptLab.decodeAES(fileBytes, password);
                    if (cancelled)
                        return null;
                    if (twoPageMode) {
                        rightBytes = CryptLab.decodeAES(rightBytes, password);
                        if (cancelled)
                            return null;
                    }
                    
                }
                pdfDocument = pdfiumCore.newDocument(fileBytes);
                if (twoPageMode) {
                    pdfRightDocument = pdfiumCore.newDocument(rightBytes);
                }
            } else {
                if (isAsset) {
                    path = FileUtils.fileFromAsset(context, path).getAbsolutePath();
                }

                pdfDocument = pdfiumCore.newDocument(getSeekableFileDescriptor(path), password);
                if (twoPageMode) {
                    rpath = FileUtils.fileFromAsset(context, rpath).getAbsolutePath();
                    pdfRightDocument = pdfiumCore.newDocument(getSeekableFileDescriptor(rpath), password);
                }
            }

            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    protected ParcelFileDescriptor getSeekableFileDescriptor(String path) throws IOException {
        ParcelFileDescriptor pfd;

        File pdfCopy = new File(path);
        if (pdfCopy.exists()) {
            pfd = ParcelFileDescriptor.open(pdfCopy, ParcelFileDescriptor.MODE_READ_ONLY);
            return pfd;
        }

        if (!path.contains("://")) {
            path = String.format("file://%s", path);
        }

        Uri uri = Uri.parse(path);
        pfd = context.getContentResolver().openFileDescriptor(uri, "r");

        if (pfd == null) {
            throw new IOException("Cannot get FileDescriptor for " + path);
        }

        return pfd;
    }

    @Override
    protected void onPostExecute(Throwable t) {
        if (t != null) {
            pdfView.loadError(t);
            return;
        }
        if (!cancelled) {
            pdfView.loadComplete(pdfDocument, pdfRightDocument, true);
        }
    }

    @Override
    protected void onCancelled() {
        cancelled = true;
    }
}
