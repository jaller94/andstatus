package org.andstatus.app.note;

import android.content.Intent;
import android.provider.BaseColumns;
import android.view.View;

import org.andstatus.app.ActivityTestHelper;
import org.andstatus.app.R;
import org.andstatus.app.account.MyAccount;
import org.andstatus.app.activity.ActivityViewItem;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.context.TestSuite;
import org.andstatus.app.data.DownloadStatus;
import org.andstatus.app.data.MyQuery;
import org.andstatus.app.data.OidEnum;
import org.andstatus.app.database.table.ActivityTable;
import org.andstatus.app.database.table.NoteTable;
import org.andstatus.app.net.http.HttpReadResult;
import org.andstatus.app.net.social.Actor;
import org.andstatus.app.service.MyServiceTestHelper;
import org.andstatus.app.timeline.ListActivityTestHelper;
import org.andstatus.app.timeline.TimelineActivity;
import org.andstatus.app.timeline.TimelineActivityTest;
import org.andstatus.app.timeline.meta.TimelineType;
import org.andstatus.app.util.MyLog;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import androidx.test.espresso.action.TypeTextAction;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.andstatus.app.context.DemoData.demoData;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UnsentNotesTest extends TimelineActivityTest<ActivityViewItem> {
    private final MyServiceTestHelper mService = new MyServiceTestHelper();

    @Override
    protected Intent getActivityIntent() {
        TestSuite.initializeWithAccounts(this);

        mService.setUp(null);
        MyAccount ma = demoData.getGnuSocialAccount();
        assertTrue(ma.isValid());
        MyContextHolder.get().accounts().setCurrentAccount(ma);

        return new Intent(Intent.ACTION_VIEW,
                MyContextHolder.get().timelines().get(TimelineType.EVERYTHING, Actor.EMPTY, ma.getOrigin()).getUri());
    }

    @After
    public void tearDown() {
        mService.tearDown();
    }

    @Test
    public void testEditUnsentNote() throws InterruptedException {
        final String method = "testEditUnsentNote";
        String step = "Start editing a note";
        MyLog.v(this, method + " started");
        ActivityTestHelper<TimelineActivity> helper = new ActivityTestHelper<>(getActivity());
        View editorView = getActivity().findViewById(R.id.note_editor);
        helper.clickMenuItem(method + "; " + step, R.id.createNoteButton);
        ActivityTestHelper.waitViewVisible(method + "; " + step, editorView);

        final String suffix = "unsent" + demoData.testRunUid;
        String body = "Test unsent note, which we will try to edit " + suffix;
        TestSuite.waitForIdleSync();
        onView(withId(R.id.noteBodyEditText)).perform(new TypeTextAction(body));
        TestSuite.waitForIdleSync();

        mService.serviceStopped = false;
        step = "Sending note";
        helper.clickMenuItem(method + "; " + step, R.id.noteSendButton);
        ActivityTestHelper.waitViewInvisible(method + "; " + step, editorView);

        mService.waitForServiceStopped(false);

        String condition = NoteTable.CONTENT + " LIKE('%" + suffix + "%')";
        long unsentMsgId = MyQuery.conditionToLongColumnValue(NoteTable.TABLE_NAME, BaseColumns._ID, condition);
        step = "Unsent note " + unsentMsgId;
        assertTrue(method + "; " + step + ": " + condition, unsentMsgId != 0);
        assertEquals(method + "; " + step, DownloadStatus.SENDING, DownloadStatus.load(
                MyQuery.noteIdToLongColumnValue(NoteTable.NOTE_STATUS, unsentMsgId)));

        step = "Start editing unsent note " + unsentMsgId ;
        getActivity().getNoteEditor().startEditingNote(NoteEditorData.load(MyContextHolder.get(), unsentMsgId));
        ActivityTestHelper.waitViewVisible(method + "; " + step, editorView);
        TestSuite.waitForIdleSync();

        step = "Saving previously unsent note " + unsentMsgId + " as a draft";
        helper.clickMenuItem(method + "; " + step, R.id.saveDraftButton);
        ActivityTestHelper.waitViewInvisible(method + "; " + step, editorView);

        assertEquals(method + "; " + step, DownloadStatus.DRAFT, DownloadStatus.load(
                MyQuery.noteIdToLongColumnValue(NoteTable.NOTE_STATUS, unsentMsgId)));

        MyLog.v(this, method + " ended");
    }

    @Test
    public void testGnuSocialReblog() throws InterruptedException {
        final String method = "testGnuSocialReblog";
        MyLog.v(this, method + " started");
        TestSuite.waitForListLoaded(getActivity(), 1);
        ListActivityTestHelper<TimelineActivity> helper = new ListActivityTestHelper<>(getActivity());
        long itemId = helper.getListItemIdOfLoadedReply();
        long noteId = MyQuery.activityIdToLongColumnValue(ActivityTable.NOTE_ID, itemId);
        String noteOid = MyQuery.idToOid(getActivity().getMyContext(), OidEnum.NOTE_OID, noteId, 0);
        String logMsg = MyQuery.noteInfoForLog(getActivity().getMyContext(), noteId);
        assertTrue(logMsg, helper.invokeContextMenuAction4ListItemId(method, itemId, NoteContextMenuItem.ANNOUNCE,
                R.id.note_wrapper));
        mService.serviceStopped = false;
        TestSuite.waitForIdleSync();
        mService.waitForServiceStopped(false);

        List<HttpReadResult> results = mService.getHttp().getResults();
        assertTrue("No results in " + mService.getHttp().toString() + "\n" + logMsg, !results.isEmpty());
        boolean urlFound = results.stream().anyMatch(result -> (result.getUrl().contains("retweet") &&
                result.getUrl().contains(noteOid)));
        assertTrue("No URL contain note oid " + logMsg + "\nResults: " + results, urlFound);

        MyLog.v(this, method + " ended");
    }

}
