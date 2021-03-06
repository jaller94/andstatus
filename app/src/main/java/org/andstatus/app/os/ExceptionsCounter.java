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

import android.app.Dialog;

import org.andstatus.app.R;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.util.DialogFactory;
import org.andstatus.app.util.StringUtils;

import java.util.concurrent.atomic.AtomicLong;

import androidx.annotation.MainThread;

/**
 * @author yvolk@yurivolkov.com
 */
public class ExceptionsCounter {

    private static final AtomicLong diskIoExceptionsCount = new AtomicLong();
    private static final AtomicLong diskIoExceptionsCountShown = new AtomicLong();
    private static volatile Dialog diskIoDialog = null;

    private ExceptionsCounter() {
        // Empty
    }

    public static long getDiskIoExceptionsCount() {
        return diskIoExceptionsCount.get();
    }

    public static void onDiskIoException() {
        diskIoExceptionsCount.incrementAndGet();
    }

    public static void forget() {
        DialogFactory.dismissSafely(diskIoDialog);
        diskIoExceptionsCount.set(0);
        diskIoExceptionsCountShown.set(0);
    }

    @MainThread
    public static void showErrorDialogIfErrorsPresent() {
        if (diskIoExceptionsCountShown.get() == diskIoExceptionsCount.get() ) return;

        diskIoExceptionsCountShown.set(diskIoExceptionsCount.get());
        DialogFactory.dismissSafely(diskIoDialog);
        final String text = StringUtils.format(MyContextHolder.get().context(), R.string.database_disk_io_error,
                diskIoExceptionsCount.get());
        diskIoDialog = DialogFactory.showOkAlertDialog(ExceptionsCounter.class, MyContextHolder.get().context(),
                R.string.app_name, text);
    }
}
