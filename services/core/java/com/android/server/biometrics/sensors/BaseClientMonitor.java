/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.NoSuchElementException;

/**
 * Abstract base class for keeping track and dispatching events from the biometric's HAL to
 * the current client.  Subclasses are responsible for coordinating the interaction with
 * the biometric's HAL for the specific action (e.g. authenticate, enroll, enumerate, etc.).
 */
public abstract class BaseClientMonitor implements IBinder.DeathRecipient {

    private static final String TAG = "BaseClientMonitor";
    protected static final boolean DEBUG = false;

    // Counter used to distinguish between ClientMonitor instances to help debugging.
    private static int sCount = 0;

    private final int mSequentialId;
    @NonNull private final Context mContext;
    private final int mTargetUserId;
    @NonNull private final String mOwner;
    private final int mSensorId; // sensorId as configured by the framework
    @NonNull private final BiometricLogger mLogger;
    @NonNull private final BiometricContext mBiometricContext;

    @Nullable private IBinder mToken;
    private long mRequestId;
    @Nullable private ClientMonitorCallbackConverter mListener;
    // Currently only used for authentication client. The cookie generated by BiometricService
    // is never 0.
    private final int mCookie;
    private boolean mAlreadyDone = false;

    // Use an empty callback by default since delayed operations can receive events
    // before they are started and cause NPE in subclasses that access this field directly.
    @NonNull protected ClientMonitorCallback mCallback = new ClientMonitorCallback() {
        @Override
        public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
            Slog.e(TAG, "mCallback onClientStarted: called before set (should not happen)");
        }

        @Override
        public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                boolean success) {
            Slog.e(TAG, "mCallback onClientFinished: called before set (should not happen)");
        }
    };

    /**
     * @param context    system_server context
     * @param token      a unique token for the client
     * @param listener   recipient of related events (e.g. authentication)
     * @param userId     target user id for operation
     * @param owner      name of the client that owns this
     * @param cookie     BiometricPrompt authentication cookie (to be moved into a subclass soon)
     * @param sensorId   ID of the sensor that the operation should be requested of
     * @param logger     framework stats logger
     * @param biometricContext system context metadata
     */
    public BaseClientMonitor(@NonNull Context context,
            @Nullable IBinder token, @Nullable ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int cookie, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
        mSequentialId = sCount++;
        mContext = context;
        mToken = token;
        mRequestId = -1;
        mListener = listener;
        mTargetUserId = userId;
        mOwner = owner;
        mCookie = cookie;
        mSensorId = sensorId;
        mLogger = logger;
        mBiometricContext = biometricContext;

        try {
            if (token != null) {
                token.linkToDeath(this, 0);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
        }
    }

    /** A ClientMonitorEnum constant defined in biometrics.proto */
    public abstract int getProtoEnum();

    /** True if the ClientMonitor should cancel any current and pending interruptable clients. */
    public boolean interruptsPrecedingClients() {
        return false;
    }

    /**
     * Sets the lifecycle callback before the operation is started via
     * {@link #start(ClientMonitorCallback)} when the client must wait for a cookie before starting.
     *
     * @param callback lifecycle callback (typically same callback used for starting the operation)
     */
    public void waitForCookie(@NonNull ClientMonitorCallback callback) {
        mCallback = callback;
    }

    /**
     * Starts the ClientMonitor's lifecycle.
     * @param callback invoked when the operation is complete (succeeds, fails, etc.)
     */
    public void start(@NonNull ClientMonitorCallback callback) {
        mCallback = wrapCallbackForStart(callback);
        mCallback.onClientStarted(this);
    }

    /**
     * Called during start to provide subclasses a hook for decorating the callback.
     *
     * Returns the original callback unless overridden.
     */
    @NonNull
    protected ClientMonitorCallback wrapCallbackForStart(@NonNull ClientMonitorCallback callback) {
        return callback;
    }

    /** Signals this operation has completed its lifecycle and should no longer be used. */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public void destroy() {
        mAlreadyDone = true;
        if (mToken != null) {
            try {
                mToken.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                // TODO: remove when duplicate call bug is found
                Slog.e(TAG, "destroy(): " + this + ":", new Exception("here"));
            }
            mToken = null;
        }
    }

    /**
     * Call while the operation is still active, but nearly done, to prevent any action
     * upon client death (only needed for authentication clients).
     */
    void markAlreadyDone() {
        Slog.d(TAG, "marking operation as done: " + this);
        mAlreadyDone = true;
    }

    /** If this operation has been marked as completely done (or cancelled). */
    public boolean isAlreadyDone() {
        return mAlreadyDone;
    }

    @Override
    public void binderDied() {
        binderDiedInternal(true /* clearListener */);
    }

    // TODO(b/157790417): Move this to the scheduler
    void binderDiedInternal(boolean clearListener) {
        Slog.e(TAG, "Binder died, operation: " + this);

        if (mAlreadyDone) {
            Slog.w(TAG, "Binder died but client is finished, ignoring");
            return;
        }

        // If the current client dies we should cancel the current operation.
        if (this instanceof Interruptable) {
            Slog.e(TAG, "Binder died, cancelling client");
            ((Interruptable) this).cancel();
        }
        mToken = null;
        if (clearListener) {
            mListener = null;
        }
    }

    /**
     * Only valid for AuthenticationClient.
     * @return true if the client is authenticating for a crypto operation.
     */
    protected boolean isCryptoOperation() {
        return false;
    }

    /** System context that may change during operations. */
    @NonNull
    protected BiometricContext getBiometricContext() {
        return mBiometricContext;
    }

    /** Logger for this client */
    @NonNull
    public BiometricLogger getLogger() {
        return mLogger;
    }

    @NonNull
    public final Context getContext() {
        return mContext;
    }

    @NonNull
    public final String getOwnerString() {
        return mOwner;
    }

    @Nullable
    public final ClientMonitorCallbackConverter getListener() {
        return mListener;
    }

    public int getTargetUserId() {
        return mTargetUserId;
    }

    @Nullable
    public final IBinder getToken() {
        return mToken;
    }

    public int getSensorId() {
        return mSensorId;
    }

    /** Cookie set when this monitor was created. */
    public int getCookie() {
        return mCookie;
    }

    /** Unique request id. */
    public long getRequestId() {
        return mRequestId;
    }

    /** If a unique id has been set via {@link #setRequestId(long)} */
    public boolean hasRequestId() {
        return mRequestId > 0;
    }

    /**
     * A unique identifier used to tie this operation to a request (i.e an API invocation).
     *
     * Subclasses should not call this method if this operation does not have a direct
     * correspondence to a request and {@link #hasRequestId()} will return false.
     */
    protected final void setRequestId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("request id must be positive");
        }
        mRequestId = id;
    }

    @VisibleForTesting
    public ClientMonitorCallback getCallback() {
        return mCallback;
    }

    @Override
    public String toString() {
        return "{[" + mSequentialId + "] "
                + this.getClass().getName()
                + ", proto=" + getProtoEnum()
                + ", owner=" + getOwnerString()
                + ", cookie=" + getCookie()
                + ", requestId=" + getRequestId()
                + ", userId=" + getTargetUserId() + "}";
    }
}
