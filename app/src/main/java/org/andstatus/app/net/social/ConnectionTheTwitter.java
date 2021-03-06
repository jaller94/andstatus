/*
 * Copyright (C) 2014 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.net.social;

import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;

import org.andstatus.app.net.http.ConnectionException;
import org.andstatus.app.net.http.HttpConnection;
import org.andstatus.app.net.http.HttpReadResult;
import org.andstatus.app.origin.OriginConfig;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.StringUtils;
import org.andstatus.app.util.UriUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.andstatus.app.context.MyPreferences.BYTES_IN_MB;

/**
 * Implementation of current API of the twitter.com
 * https://dev.twitter.com/rest/public
 */
public class ConnectionTheTwitter extends ConnectionTwitterLike {
    private static final String ATTACHMENTS_FIELD_NAME = "media";
    private static final String SENSITIVE_PROPERTY = "possibly_sensitive";

    @NonNull
    @Override
    protected String getApiPathFromOrigin(ApiRoutineEnum routine) {
        String url;
        switch(routine) {
            case ACCOUNT_RATE_LIMIT_STATUS:
                url = "application/rate_limit_status.json";
                break;
            case LIKE:
                url = "favorites/create.json?tweet_mode=extended";
                break;
            case UNDO_LIKE:
                url = "favorites/destroy.json?tweet_mode=extended";
                break;
            case PRIVATE_NOTES:
                url = "direct_messages.json?tweet_mode=extended";
                break;
            case LIKED_TIMELINE:
                // https://dev.twitter.com/rest/reference/get/favorites/list
                url = "favorites/list.json?tweet_mode=extended";
                break;
            case GET_FOLLOWERS:
                // https://dev.twitter.com/rest/reference/get/followers/list
                url = "followers/list.json";
                break;
            case GET_FRIENDS:
                // https://dev.twitter.com/docs/api/1.1/get/friends/list
                url = "friends/list.json";
                break;
            case GET_NOTE:
                url = "statuses/show.json" + "?id=%noteId%&tweet_mode=extended";
                break;
            case HOME_TIMELINE:
                url = "statuses/home_timeline.json?tweet_mode=extended";
                break;
            case NOTIFICATIONS_TIMELINE:
                // https://dev.twitter.com/docs/api/1.1/get/statuses/mentions_timeline
                url = "statuses/mentions_timeline.json?tweet_mode=extended";
                break;
            case UPDATE_PRIVATE_NOTE:
                url = "direct_messages/new.json?tweet_mode=extended";
                break;
            case UPDATE_NOTE:
                url = "statuses/update.json?tweet_mode=extended";
                break;
            case ANNOUNCE:
                url = "statuses/retweet/%noteId%.json?tweet_mode=extended";
                break;
            case UPLOAD_MEDIA:
                // Trying to allow setting alternative Twitter host...
                if (http.data.originUrl.getHost().equals("api.twitter.com")) {
                    url = "https://upload.twitter.com/1.1/media/upload.json";
                } else {
                    url = "media/upload.json";
                }
                break;
            case SEARCH_NOTES:
                // https://dev.twitter.com/docs/api/1.1/get/search/tweets
                url = "search/tweets.json?tweet_mode=extended";
                break;
            case SEARCH_ACTORS:
                url = "users/search.json?tweet_mode=extended";
                break;
            case ACTOR_TIMELINE:
                url = "statuses/user_timeline.json?tweet_mode=extended";
                break;
            default:
                url = "";
                break;
        }
        if (StringUtils.isEmpty(url)) {
            return super.getApiPathFromOrigin(routine);
        }
        return partialPathToApiPath(url);
    }

    /**
     * https://developer.twitter.com/en/docs/tweets/post-and-engage/api-reference/post-statuses-update
     */
    @Override
    protected AActivity updateNote2(Note note, String inReplyToOid, Attachments attachments) throws ConnectionException {
        JSONObject obj = new JSONObject();
        try {
            super.updateNoteSetFields(note, inReplyToOid, obj);
            if (note.isSensitive()) {
                obj.put(SENSITIVE_PROPERTY, note.isSensitive());
            }
            List<String> ids = new ArrayList<>();
            for (Attachment attachment : attachments.list) {
                if (UriUtils.isDownloadable(attachment.uri)) {
                    MyLog.i(this, "Skipped downloadable " + attachment);
                } else {
                    // https://developer.twitter.com/en/docs/media/upload-media/api-reference/post-media-upload
                    JSONObject mediaObject = uploadMedia(attachment.uri);
                    if (mediaObject != null && mediaObject.has("media_id_string")) {
                        ids.add(mediaObject.get("media_id_string").toString());
                    }
                }
            };
            if (!ids.isEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    obj.put("media_ids", String.join(",", ids));
                } else {
                    obj.put("media_ids", ids.stream().collect(Collectors.joining(",")));
                }
            }
        } catch (JSONException e) {
            throw ConnectionException.hardConnectionException("Exception while preparing post params " + note, e);
        }
        return postRequest(ApiRoutineEnum.UPDATE_NOTE, obj)
                .map(HttpReadResult::getJsonObject)
                .map(this::activityFromJson).getOrElseThrow(ConnectionException::of);
    }

    private JSONObject uploadMedia(Uri mediaUri) throws ConnectionException {
        JSONObject formParams = new JSONObject();
        try {
            formParams.put(HttpConnection.KEY_MEDIA_PART_NAME, "media");
            formParams.put(HttpConnection.KEY_MEDIA_PART_URI, mediaUri.toString());
            return postRequest(ApiRoutineEnum.UPLOAD_MEDIA, formParams)
                    .map(HttpReadResult::getJsonObject)
                    .filter(Objects::nonNull)
                    .onSuccess(jso -> {
                        if (MyLog.isVerboseEnabled()) {
                            MyLog.v(this, "uploaded '" + mediaUri.toString() + "' " + jso.toString());
                        }
                    }).getOrElseThrow(ConnectionException::of);
        } catch (JSONException e) {
            throw ConnectionException.loggedJsonException(this, "Error uploading '" + mediaUri + "'", e, formParams);
        }
    }

    @Override
    public OriginConfig getConfig() throws ConnectionException {
        // There is https://developer.twitter.com/en/docs/developer-utilities/configuration/api-reference/get-help-configuration
        // but it doesn't have this 280 chars limit...
        return new OriginConfig(280, 5 * BYTES_IN_MB);
    }

    @Override
    public AActivity like(String noteOid) throws ConnectionException {
        JSONObject out = new JSONObject();
        try {
            out.put("id", noteOid);
        } catch (JSONException e) {
            MyLog.e(this, e);
        }
        return postRequest(ApiRoutineEnum.LIKE, out)
            .map(HttpReadResult::getJsonObject)
            .map(this::activityFromJson).getOrElseThrow(ConnectionException::of);
    }

    @Override
    public AActivity undoLike(String noteOid) throws ConnectionException {
        JSONObject out = new JSONObject();
        try {
            out.put("id", noteOid);
        } catch (JSONException e) {
            MyLog.e(this, e);
        }
        return postRequest(ApiRoutineEnum.UNDO_LIKE, out)
            .map(HttpReadResult::getJsonObject)
            .map(this::activityFromJson).getOrElseThrow(ConnectionException::of);
    }

    @NonNull
    @Override
    public List<AActivity> searchNotes(TimelinePosition youngestPosition,
                                       TimelinePosition oldestPosition, int limit, String searchQuery)
            throws ConnectionException {
        ApiRoutineEnum apiRoutine = ApiRoutineEnum.SEARCH_NOTES;
        Uri.Builder builder = getApiPath(apiRoutine).buildUpon();
        if (!StringUtils.isEmpty(searchQuery)) {
            builder.appendQueryParameter("q", searchQuery);
        }
        appendPositionParameters(builder, youngestPosition, oldestPosition);
        builder.appendQueryParameter("count", strFixedDownloadLimit(limit, apiRoutine));
        JSONArray jArr = getRequestArrayInObject(builder.build(), "statuses");
        return jArrToTimeline("", jArr, apiRoutine, builder.build());
    }

    @NonNull
    @Override
    public List<Actor> searchActors(int limit, String searchQuery) throws ConnectionException {
        ApiRoutineEnum apiRoutine = ApiRoutineEnum.SEARCH_ACTORS;
        Uri.Builder builder = getApiPath(apiRoutine).buildUpon();
        if (!StringUtils.isEmpty(searchQuery)) {
            builder.appendQueryParameter("q", searchQuery);
        }
        builder.appendQueryParameter("count", strFixedDownloadLimit(limit, apiRoutine));
        return jArrToActors(http.getRequestAsArray(builder.build()), apiRoutine, builder.build());
    }

    @Override
    @NonNull
    AActivity activityFromJson2(JSONObject jso) throws ConnectionException {
        if (jso == null) return AActivity.EMPTY;

        AActivity activity = super.activityFromJson2(jso);
        Note note =  activity.getNote();
        note.setSensitive(jso.optBoolean(SENSITIVE_PROPERTY));
        if (!addAttachmentsFromJson(jso, activity, "extended_entities")) {
            // See https://dev.twitter.com/docs/entities
            addAttachmentsFromJson(jso, activity, "entities");
        }
        return activity;
    }

    private boolean addAttachmentsFromJson(JSONObject jso, AActivity activity, String sectionName) {
        final String method = "addAttachmentsFromJson";
        try {
            JSONObject entities = jso.optJSONObject(sectionName);
            if (entities != null && entities.has(ATTACHMENTS_FIELD_NAME)) {
                JSONArray jArr = entities.getJSONArray(ATTACHMENTS_FIELD_NAME);
                for (int ind = 0; ind < jArr.length(); ind++) {
                    Attachment attachment = Attachment.fromUri(
                            UriUtils.fromAlternativeTags((JSONObject) jArr.get(ind),
                                    "media_url_https", "media_url_http"));
                    if (attachment.isValid()) {
                        activity.addAttachment(attachment);
                    } else {
                        MyLog.d(this, method + "; invalid attachment #" + ind + "; " + jArr.toString());
                    }
                }
                return true;
            }
        } catch (JSONException e) {
            MyLog.d(this, method, e);
        }
        return false;
    }

    @Override
    protected void setNoteBodyFromJson(Note note, JSONObject jso) throws JSONException {
        boolean bodyFound = false;
        if (!jso.isNull("full_text")) {
            note.setContentPosted(jso.getString("full_text"));
            bodyFound = true;
        }
        if (!bodyFound) {
            super.setNoteBodyFromJson(note, jso);
        }
    }

    @Override
    List<Actor> getActors(Actor actor, ApiRoutineEnum apiRoutine) throws ConnectionException {
        Uri.Builder builder = getApiPath(apiRoutine).buildUpon();
        int limit = 200;
        if (!StringUtils.isEmpty(actor.oid)) {
            builder.appendQueryParameter("user_id", actor.oid);
        }
        builder.appendQueryParameter("count", strFixedDownloadLimit(limit, apiRoutine));
        return jArrToActors(http.getRequestAsArray(builder.build()), apiRoutine, builder.build());
    }

}
