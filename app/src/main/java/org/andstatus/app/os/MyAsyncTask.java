/*
 * Copyright (c) 2016 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.os;

import android.os.AsyncTask;
import android.support.annotation.NonNull;

import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.RelativeTime;

/**
 * @author yvolk@yurivolkov.com
 */
public abstract class MyAsyncTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result> {
    private final String taskId;
    protected final long createdAt = MyLog.uniqueCurrentTimeMS();
    protected volatile long backgroundStartedAt;
    protected volatile long backgroundEndedAt;
    private boolean singleInstance = true;

    public boolean isSingleInstance() {
        return singleInstance;
    }

    public void setSingleInstance(boolean singleInstance) {
        this.singleInstance = singleInstance;
    }

    public MyAsyncTask() {
        this.taskId = this.getClass().getName();
    }

    public MyAsyncTask(@NonNull String taskId) {
        this.taskId = taskId;
    }

    @Override
    protected final Result doInBackground(Params... params) {
        backgroundStartedAt = System.currentTimeMillis();
        try {
            if (isCancelled()) {
                return null;
            } else {
                return doInBackground2(params);
            }
        } finally {
            backgroundEndedAt = System.currentTimeMillis();
        }
    }

    protected abstract Result doInBackground2(Params... params);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MyAsyncTask<?, ?, ?> that = (MyAsyncTask<?, ?, ?>) o;

        return taskId.equals(that.taskId);
    }

    @Override
    public int hashCode() {
        return taskId.hashCode();
    }

    public boolean isBackgroundStarted() {
        return backgroundStartedAt > 0;
    }

    public boolean isBackgroundCompleted() {
        return backgroundEndedAt > 0;
    }

    @Override
    public String toString() {
        return taskId
                + "; age " + RelativeTime.secondsAgo(createdAt) + "sec"
                + "; " + stateSummary()
                + "; " + super.toString();
    }

    private String stateSummary() {
        if (getStatus() != Status.RUNNING) {
            return getStatus().name();
        }
        if (backgroundStartedAt == 0) {
            return "QUEUED";
        }
        if (backgroundEndedAt == 0) {
            return "RUNNING " + RelativeTime.secondsAgo(backgroundStartedAt) + "sec";
        }
        return "FINISHING";
    }

    public boolean needsBackgroundWork() {
        if (isCancelled()) {
            return false;
        }
        switch (getStatus()) {
            case PENDING:
                return true;
            case FINISHED:
                return false;
            default:
                return backgroundEndedAt == 0;
        }
    }
}
