/*
 * Copyright (C) 2019 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.data;

import android.content.Context;
import android.net.Uri;
import android.text.format.Formatter;

import org.andstatus.app.context.MyContext;
import org.andstatus.app.database.table.DownloadTable;
import org.andstatus.app.graphics.CacheName;
import org.andstatus.app.util.IsEmpty;
import org.andstatus.app.util.MyStringBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class AttachedImageFiles implements IsEmpty {
    public final static AttachedImageFiles EMPTY = new AttachedImageFiles(Collections.emptyList());

    public final List<AttachedImageFile> list;

    public AttachedImageFiles(List<AttachedImageFile> imageFiles) {
        list = imageFiles;
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public int size() {
        return list.size();
    }

    public static AttachedImageFiles load(MyContext myContext, long noteId) {
        final String sql = "SELECT *" +
                " FROM " + DownloadTable.TABLE_NAME + 
                " WHERE " + DownloadTable.NOTE_ID + "=" + noteId +
                " AND " + DownloadTable.DOWNLOAD_TYPE + "=" + DownloadType.ATTACHMENT.save() + 
                " AND " + DownloadTable.CONTENT_TYPE +
                " IN(" + MyContentType.IMAGE.save() + ", " + MyContentType.VIDEO.save() + ")" +
                " ORDER BY " + DownloadTable.DOWNLOAD_NUMBER;
        List<AttachedImageFile> imageFiles1 = MyQuery.getList(myContext, sql, AttachedImageFile::fromCursor);
        List<AttachedImageFile> imageFiles2 = foldPreviews(imageFiles1);
        return new AttachedImageFiles(imageFiles2);
    }

    private static List<AttachedImageFile> foldPreviews(List<AttachedImageFile> imageFiles) {
        List<AttachedImageFile> out = new ArrayList<>();
        List<Long> toSkip = imageFiles.stream().map(i -> i.previewOfDownloadId).filter(i -> i != 0)
                .collect(Collectors.toList());
        for(AttachedImageFile imageFile: imageFiles) {
            if (imageFile.isEmpty() || toSkip.contains(imageFile.downloadId)) continue;

            if (imageFile.previewOfDownloadId == 0) {
                out.add(imageFile);
            } else {
                AttachedImageFile fullImage = AttachedImageFile.EMPTY;
                for(AttachedImageFile other: imageFiles) {
                    if (other.downloadId == imageFile.previewOfDownloadId) {
                        fullImage = other;
                        break;
                    }
                }
                out.add(new AttachedImageFile(imageFile, fullImage));
            }
        }
        return out;
    }

    public boolean imageOrLinkMayBeShown() {
        for (AttachedImageFile imageFile: list) {
            if (imageFile.imageOrLinkMayBeShown()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return MyStringBuilder.formatKeyValue(this, list);
    }

    public void preloadImagesAsync() {
        for (AttachedImageFile imageFile: list) {
            if (imageFile.contentType == MyContentType.IMAGE) {
                imageFile.preloadImageAsync(CacheName.ATTACHED_IMAGE);
            }
        }
    }

    public String toMediaSummary(Context context) {
        MyStringBuilder builder = new MyStringBuilder();
        list.forEach( item -> {
            builder.withComma(
            item.mediaMetadata.toDetails() + " "
                    + Formatter.formatShortFileSize(context, item.downloadFile.getSize()));
        });
        return builder.toString();
    }

    public Optional<AttachedImageFile> tooLargeAttachment(long maxBytes) {
        return list.stream().filter(item -> item.downloadFile.getSize() > maxBytes).findAny();
    }

    public Optional<AttachedImageFile> forUri(Uri uri) {
        return list.stream().filter(item -> uri.equals(item.uri)).findAny();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AttachedImageFiles that = (AttachedImageFiles) o;

        return list.equals(that.list);
    }

    @Override
    public int hashCode() {
        return list.hashCode();
    }
}
