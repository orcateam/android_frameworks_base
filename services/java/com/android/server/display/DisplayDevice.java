/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.display;

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.view.Surface;

import java.io.PrintWriter;

/**
 * Represents a physical display device such as the built-in display
 * an external monitor, or a WiFi display.
 * <p>
 * Display devices are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
abstract class DisplayDevice {
    private final DisplayAdapter mDisplayAdapter;
    private final IBinder mDisplayToken;

    // The display device does not manage these properties itself, they are set by
    // the display manager service.  The display device shouldn't really be looking at these.
    private int mCurrentLayerStack = -1;
    private int mCurrentOrientation = -1;
    private Rect mCurrentLayerStackRect;
    private Rect mCurrentDisplayRect;

    // The display device does own its surface texture, but it should only set it
    // within a transaction from performTraversalInTransactionLocked.
    private SurfaceTexture mCurrentSurfaceTexture;

    public DisplayDevice(DisplayAdapter displayAdapter, IBinder displayToken) {
        mDisplayAdapter = displayAdapter;
        mDisplayToken = displayToken;
    }

    /**
     * Gets the display adapter that owns the display device.
     *
     * @return The display adapter.
     */
    public final DisplayAdapter getAdapterLocked() {
        return mDisplayAdapter;
    }

    /**
     * Gets the Surface Flinger display token for this display.
     *
     * @return The display token, or null if the display is not being managed
     * by Surface Flinger.
     */
    public final IBinder getDisplayTokenLocked() {
        return mDisplayToken;
    }

    /**
     * Gets the name of the display device.
     *
     * @return The display device name.
     */
    public final String getNameLocked() {
        return getDisplayDeviceInfoLocked().name;
    }

    /**
     * Gets information about the display device.
     *
     * The information returned should not change between calls unless the display
     * adapter sent a {@link DisplayAdapter#DISPLAY_DEVICE_EVENT_CHANGED} event and
     * {@link #applyPendingDisplayDeviceInfoChangesLocked()} has been called to apply
     * the pending changes.
     *
     * @return The display device info, which should be treated as immutable by the caller.
     * The display device should allocate a new display device info object whenever
     * the data changes.
     */
    public abstract DisplayDeviceInfo getDisplayDeviceInfoLocked();

    /**
     * Applies any pending changes to the observable state of the display device
     * if the display adapter sent a {@link DisplayAdapter#DISPLAY_DEVICE_EVENT_CHANGED} event.
     */
    public void applyPendingDisplayDeviceInfoChangesLocked() {
    }

    /**
     * Gives the display device a chance to update its properties while in a transaction.
     */
    public void performTraversalInTransactionLocked() {
    }

    /**
     * Sets the display layer stack while in a transaction.
     */
    public final void setLayerStackInTransactionLocked(int layerStack) {
        if (mCurrentLayerStack == layerStack) {
            return;
        }
        mCurrentLayerStack = layerStack;
        Surface.setDisplayLayerStack(mDisplayToken, layerStack);
    }

    /**
     * Sets the display projection while in a transaction.
     *
     * @param orientation defines the display's orientation
     * @param layerStackRect defines which area of the window manager coordinate
     *            space will be used
     * @param displayRect defines where on the display will layerStackRect be
     *            mapped to. displayRect is specified post-orientation, that is
     *            it uses the orientation seen by the end-user
     */
    public final void setProjectionInTransactionLocked(int orientation, Rect layerStackRect, Rect displayRect) {
        mCurrentOrientation = orientation;
        if (mCurrentLayerStackRect == null) {
            mCurrentLayerStackRect = new Rect();
        }
        mCurrentLayerStackRect.set(layerStackRect);
        if (mCurrentDisplayRect == null) {
            mCurrentDisplayRect = new Rect();
        }
        mCurrentDisplayRect.set(displayRect);
        Surface.setDisplayProjection(mDisplayToken, orientation, layerStackRect, displayRect);
    }

    /**
     * Sets the surface texture while in a transaction.
     */
    public final void setSurfaceTextureInTransactionLocked(SurfaceTexture surfaceTexture) {
        if (mCurrentSurfaceTexture == surfaceTexture) {
            return;
        }
        mCurrentSurfaceTexture = surfaceTexture;
        Surface.setDisplaySurface(mDisplayToken, surfaceTexture);
    }

    /**
     * Dumps the local state of the display device.
     * Does not need to dump the display device info because that is already dumped elsewhere.
     */
    public void dumpLocked(PrintWriter pw) {
        pw.println("mAdapter=" + mDisplayAdapter.getName());
        pw.println("mCurrentLayerStack=" + mCurrentLayerStack);
        pw.println("mCurrentOrientation=" + mCurrentOrientation);
        pw.println("mCurrentViewport=" + mCurrentLayerStackRect);
        pw.println("mCurrentFrame=" + mCurrentDisplayRect);
        pw.println("mCurrentSurfaceTexture=" + mCurrentSurfaceTexture);
    }
}