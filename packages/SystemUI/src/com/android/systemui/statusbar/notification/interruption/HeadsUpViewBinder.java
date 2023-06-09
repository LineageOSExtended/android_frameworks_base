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

package com.android.systemui.statusbar.notification.interruption;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP;

import android.util.ArrayMap;

import androidx.annotation.Nullable;
import androidx.core.os.CancellationSignal;

import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;

import java.util.Map;

import javax.inject.Inject;

/**
 * Wrapper around heads up view binding logic. {@link HeadsUpViewBinder} is responsible for
 * figuring out the right heads up inflation parameters and inflating/freeing the heads up
 * content view.
 *
 * TODO: This should be moved into {@link HeadsUpCoordinator} when the old pipeline is deprecated.
 */
@SysUISingleton
public class HeadsUpViewBinder {
    private final RowContentBindStage mStage;
    private final NotificationMessagingUtil mNotificationMessagingUtil;
    private final Map<NotificationEntry, CancellationSignal> mOngoingBindCallbacks =
            new ArrayMap<>();
    private final HeadsUpViewBinderLogger mLogger;

    private NotificationPresenter mNotificationPresenter;

    @Inject
    HeadsUpViewBinder(
            NotificationMessagingUtil notificationMessagingUtil,
            RowContentBindStage bindStage, HeadsUpViewBinderLogger logger) {
        mNotificationMessagingUtil = notificationMessagingUtil;
        mStage = bindStage;
        mLogger = logger;
    }

    /**
     * Set notification presenter to determine parameters for heads up view inflation.
     */
    public void setPresenter(NotificationPresenter presenter) {
        mNotificationPresenter = presenter;
    }

    /**
     * Bind heads up view to the notification row.
     * @param callback callback after heads up view is bound
     */
    public void bindHeadsUpView(NotificationEntry entry, @Nullable BindCallback callback) {
        RowContentBindParams params = mStage.getStageParams(entry);
        final boolean isImportantMessage = mNotificationMessagingUtil.isImportantMessaging(
                entry.getSbn(), entry.getImportance());
        final boolean useIncreasedHeadsUp = isImportantMessage
                && !mNotificationPresenter.isPresenterFullyCollapsed();
        params.setUseIncreasedHeadsUpHeight(useIncreasedHeadsUp);
        params.requireContentViews(FLAG_CONTENT_VIEW_HEADS_UP);
        CancellationSignal signal = mStage.requestRebind(entry, en -> {
            mLogger.entryBoundSuccessfully(entry);
            en.getRow().setUsesIncreasedHeadsUpHeight(params.useIncreasedHeadsUpHeight());
            // requestRebing promises that if we called cancel before this callback would be
            // invoked, then we will not enter this callback, and because we always cancel before
            // adding to this map, we know this will remove the correct signal.
            mOngoingBindCallbacks.remove(entry);
            if (callback != null) {
                callback.onBindFinished(en);
            }
        });
        abortBindCallback(entry);
        mLogger.startBindingHun(entry);
        mOngoingBindCallbacks.put(entry, signal);
    }

    /**
     * Abort any callbacks waiting for heads up view binding to finish for a given notification.
     * @param entry notification with bind in progress
     */
    public void abortBindCallback(NotificationEntry entry) {
        CancellationSignal ongoingBindCallback = mOngoingBindCallbacks.remove(entry);
        if (ongoingBindCallback != null) {
            mLogger.currentOngoingBindingAborted(entry);
            ongoingBindCallback.cancel();
        }
    }

    /**
     * Unbind the heads up view from the notification row.
     */
    public void unbindHeadsUpView(NotificationEntry entry) {
        abortBindCallback(entry);

        // params may be null if the notification was already removed from the collection but we let
        // it stick around during a launch animation. In this case, the heads up view has already
        // been unbound, so we don't need to unbind it.
        // TODO(b/253081345): Change this back to getStageParams and remove null check.
        RowContentBindParams params = mStage.tryGetStageParams(entry);
        if (params == null) {
            mLogger.entryBindStageParamsNullOnUnbind(entry);
            return;
        }

        params.markContentViewsFreeable(FLAG_CONTENT_VIEW_HEADS_UP);
        mLogger.entryContentViewMarkedFreeable(entry);
        mStage.requestRebind(entry, e -> mLogger.entryUnbound(e));
    }
}
