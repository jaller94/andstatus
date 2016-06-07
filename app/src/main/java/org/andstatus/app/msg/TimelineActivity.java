/* 
 * Copyright (c) 2011-2015 yvolk (Yuri Volkov), http://yurivolkov.com
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

package org.andstatus.app.msg;

import android.app.SearchManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.SearchRecentSuggestions;
import android.support.annotation.NonNull;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.CheckBox;
import android.widget.TextView;

import org.andstatus.app.ActivityRequestCode;
import org.andstatus.app.HelpActivity;
import org.andstatus.app.IntentExtra;
import org.andstatus.app.LoadableListActivity;
import org.andstatus.app.MyAction;
import org.andstatus.app.MyActivity;
import org.andstatus.app.R;
import org.andstatus.app.WhichPage;
import org.andstatus.app.account.AccountSelector;
import org.andstatus.app.account.MyAccount;
import org.andstatus.app.context.MyContextHolder;
import org.andstatus.app.context.MyPreferences;
import org.andstatus.app.context.MySettingsActivity;
import org.andstatus.app.data.MatchedUri;
import org.andstatus.app.data.MyQuery;
import org.andstatus.app.data.TimelineSearchSuggestionsProvider;
import org.andstatus.app.data.TimelineType;
import org.andstatus.app.database.UserTable;
import org.andstatus.app.service.CommandData;
import org.andstatus.app.service.CommandEnum;
import org.andstatus.app.service.MyServiceManager;
import org.andstatus.app.service.MyServiceState;
import org.andstatus.app.service.QueueViewer;
import org.andstatus.app.test.SelectorActivityMock;
import org.andstatus.app.util.BundleUtils;
import org.andstatus.app.util.MyLog;
import org.andstatus.app.util.MyUrlSpan;
import org.andstatus.app.util.SharedPreferencesUtil;
import org.andstatus.app.util.TriState;
import org.andstatus.app.util.UriUtils;
import org.andstatus.app.widget.MyBaseAdapter;

/**
 * @author yvolk@yurivolkov.com
 */
public class TimelineActivity extends LoadableListActivity implements
        ActionableMessageList, AbsListView.OnScrollListener {
    public static final String HORIZONTAL_ELLIPSIS = "\u2026";

    /** Parameters for the next page request, not necessarily requested already */
    private TimelineListParameters paramsNew = new TimelineListParameters(this);
    /** Last parameters, requested to load. Thread safe. They are taken by a Loader at some time */
    private volatile TimelineListParameters paramsToLoad;
    private TimelineListParameters paramsLoaded;

    private MessageContextMenu mContextMenu;
    private MessageEditor mMessageEditor;

    private String mTextToShareViaThisApp = "";
    private Uri mMediaToShareViaThisApp = Uri.EMPTY;

    private String mRateLimitText = "";

    DrawerLayout mDrawerLayout;
    ActionBarDrawerToggle mDrawerToggle;
    protected volatile SelectorActivityMock selectorActivityMock;

    /**
     * This method is the first of the whole application to be called 
     * when the application starts for the very first time.
     * So we may put some Application initialization code here. 
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mLayoutId = R.layout.timeline;
        super.onCreate(savedInstanceState);

        showSyncIndicatorSetting = SharedPreferencesUtil.getBoolean(
                MyPreferences.KEY_SYNC_INDICATOR_ON_TIMELINE, true);

        if (HelpActivity.startFromActivity(this)) {
            return;
        }

        paramsNew.setAtHome();
        mContextMenu = new MessageContextMenu(this);
        mMessageEditor = new MessageEditor(this);

        mSwipeLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                manualSyncWithInternet(false, true);
            }
        });

        initializeDrawer();
        getListView().setOnScrollListener(this);

        View view = findViewById(R.id.my_action_bar);
        if (view != null) {
            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onTimelineTitleClick(v);
                }
            });
        }

        if (savedInstanceState != null) {
            restoreActivityState(savedInstanceState);
        } else {
            parseNewIntent(getIntent());
        }
    }

    @Override
    public TimelineAdapter getListAdapter() {
        return (TimelineAdapter) super.getListAdapter();
    }

    @Override
    protected MyBaseAdapter newListAdapter() {
        return new TimelineAdapter(mContextMenu,
                MyPreferences.getShowAvatars() ? R.layout.message_avatar : R.layout.message_basic,
                getListAdapter(),
                ((TimelineLoader) getLoaded()).getPageLoaded());
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    private void initializeDrawer() {
        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mDrawerToggle = new ActionBarDrawerToggle(
                this,
                mDrawerLayout,
                R.string.drawer_open, 
                R.string.drawer_close 
                ) {
        };

        mDrawerLayout.addDrawerListener(mDrawerToggle);
        mDrawerToggle.setHomeAsUpIndicator(MyPreferences.getActionBarTextHomeIconResourceId());
    }

    private void restoreActivityState(@NonNull Bundle savedInstanceState) {
            if (paramsNew.restoreState(savedInstanceState)) {
                mContextMenu.loadState(savedInstanceState);
            }
            if (MyLog.isVerboseEnabled()) {
                MyLog.v(this, "restoreActivityState; " + paramsNew);
            }
    }

    /**
     * View.OnClickListener
     */
    public void onTimelineTitleClick(View item) {
        switch (MyPreferences.getTapOnATimelineTitleBehaviour()) {
            case SWITCH_TO_DEFAULT_TIMELINE:
                if (getParamsLoaded().isAtHome()) {
                    onTimelineTypeButtonClick(item);
                } else {
                    onSwitchToDefaultTimelineButtonClick(item);
                }
                break;
            case GO_TO_THE_TOP:
                onGoToTheTopButtonClick(item);
                break;
            case SELECT_TIMELINE:
                onTimelineTypeButtonClick(item);
                break;
            default:
                break;
        }
    }

    /**
     * View.OnClickListener
     */
    public void onSwitchToDefaultTimelineButtonClick(View item) {
        closeDrawer();
        if (!paramsNew.isAtHome()) {
            paramsNew.setAtHome();
            mContextMenu.switchTimelineActivity(paramsNew.getContentUri());
        }
    }

    /**
     * View.OnClickListener
     */
    public void onGoToTheTopButtonClick(View item) {
        closeDrawer();
        TimelineAdapter adapter = getListAdapter();
        if (adapter == null || adapter.getPages().mayHaveYoungerPage()) {
            showList(WhichPage.TOP);
        } else {
            TimelineListPositionStorage.setPosition(getListView(), 0);
        }
    }

    /**
     * View.OnClickListener
     */
    public void onRefreshButtonClick(View item) {
        closeDrawer();
        TimelineAdapter adapter = getListAdapter();
        if (adapter == null || adapter.getPages().mayHaveYoungerPage()) {
            showList(WhichPage.CURRENT);
        } else {
            showList(WhichPage.TOP);
        }
    }

    /**
     * View.OnClickListener
     */
    public void onCombinedTimelineToggleClick(View item) {
        closeDrawer();
        paramsNew.setTimelineCombined(!isTimelineCombined());
        mContextMenu.switchTimelineActivity(paramsNew.getContentUri());
    }

    private void closeDrawer() {
        ViewGroup mDrawerList = (ViewGroup) findViewById(R.id.navigation_drawer);
        mDrawerLayout.closeDrawer(mDrawerList);
    }

    /**
     * View.OnClickListener
     */
    public void onTimelineTypeButtonClick(View item) {
        TimelineSelector.selectTimeline(this, ActivityRequestCode.SELECT_TIMELINE,
                paramsNew.getMyAccount().getOrigin(), paramsNew.getMyAccount(),
                paramsNew.isTimelineCombined());
        closeDrawer();
    }

    /**
     * View.OnClickListener
     */
    public void onSelectAccountButtonClick(View item) {
        if (MyContextHolder.get().persistentAccounts().size() > 1) {
            AccountSelector.selectAccount(TimelineActivity.this, ActivityRequestCode.SELECT_ACCOUNT, 0);
        }
        closeDrawer();
    }

    /**
     * See <a href="http://developer.android.com/guide/topics/search/search-dialog.html">Creating 
     * a Search Interface</a>
     */
    @Override
    public boolean onSearchRequested() {
        onSearchRequested(false);
        return true;
    }

    private void onSearchRequested(boolean appGlobalSearch) {
        final String method = "onSearchRequested";
        Bundle appSearchData = new Bundle();
        appSearchData.putString(IntentExtra.TIMELINE_URI.key, 
                paramsNew.toTimelineUri(appGlobalSearch).toString());
        appSearchData.putBoolean(IntentExtra.GLOBAL_SEARCH.key, appGlobalSearch);
        MyLog.v(this, method + ": " + appSearchData);
        startSearch(null, false, appSearchData, false);
    }

    @Override
    protected void onResume() {
        String method = "onResume";
        super.onResume();
        if (!mFinishing) {
            if (MyContextHolder.get().persistentAccounts().getCurrentAccount().isValid()) {
                if (isConfigChanged()) {
                    MyLog.v(this, method + "; Restarting this Activity to apply all new changes of configuration");
                    finish();
                    mContextMenu.switchTimelineActivity(paramsNew.getContentUri());
                }
            } else { 
                MyLog.v(this, method + "; Finishing this Activity because there is no Account selected");
                finish();
            }
        }
        if (!mFinishing) {
            mMessageEditor.loadCurrentDraft();
        }
    }

    @Override
    protected void onPause() {
        final String method = "onPause";
        if (MyLog.isVerboseEnabled()) {
            MyLog.v(this, method + "; instanceId=" + mInstanceId);
        }
        hideLoading(method);
        hideSyncing(method);
        crashTest();
        mMessageEditor.saveAsBeingEditedAndHide();
        saveListPosition();
        super.onPause();
    }

    /**
     * Cancel notifications of loading timeline, which were set during Timeline downloading
     */
    private void clearNotifications() {
        MyContextHolder.get().clearNotification(getTimelineType());
        MyServiceManager.sendForegroundCommand(
                CommandData.newCommand(CommandEnum.NOTIFY_CLEAR, paramsNew.getMyAccount()));
    }

    /**
     * May be executed on any thread
     * That advice doesn't fit here:
     * see http://stackoverflow.com/questions/5996885/how-to-wait-for-android-runonuithread-to-be-finished
     */
    protected void saveListPosition() {
        if (getParamsLoaded().isLoaded() && isPositionRestored()) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                        new TimelineListPositionStorage(getListAdapter(), getListView(),
                                getParamsLoaded()).save();
                    }
            };
            runOnUiThread(runnable);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        mContextMenu.onContextItemSelected(item);
        return super.onContextItemSelected(item);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.timeline, menu);
        if (mMessageEditor != null) {
            mMessageEditor.onCreateOptionsMenu(menu);
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MyAccount ma = MyContextHolder.get().persistentAccounts().getCurrentAccount();
        boolean enableSync = isTimelineCombined() || ma.isValidAndSucceeded();
        MenuItem item = menu.findItem(R.id.sync_menu_item);
        item.setEnabled(enableSync);
        item.setVisible(enableSync);

        prepareDrawer();

        if (mContextMenu != null) {
            mContextMenu.setAccountUserIdToActAs(0);
        }

        if (mMessageEditor != null) {
            mMessageEditor.onPrepareOptionsMenu(menu);
        }

        boolean enableGlobalSearch = MyContextHolder.get().persistentAccounts()
                .isGlobalSearchSupported(ma, isTimelineCombined());
        item = menu.findItem(R.id.global_search_menu_id);
        item.setEnabled(enableGlobalSearch);
        item.setVisible(enableGlobalSearch);

        return super.onPrepareOptionsMenu(menu);
    }

    private void prepareDrawer() {
        ViewGroup mDrawerList = (ViewGroup) findViewById(R.id.navigation_drawer);
        if (mDrawerList == null) {
            return;
        }
        TextView item = (TextView) mDrawerList.findViewById(R.id.timelineTypeButton);
        item.setText(timelineTypeButtonText());
        prepareCombinedTimelineToggle(mDrawerList);
        updateAccountButtonText(mDrawerList);
    }

    private void prepareCombinedTimelineToggle(ViewGroup list) {
        CheckBox combinedTimelineToggle = (CheckBox) list.findViewById(R.id.combinedTimelineToggle);
        combinedTimelineToggle.setChecked(isTimelineCombined());
        // Show the "Combined" toggle even for one account to see messages,
        // which are not on the timeline.
        // E.g. messages by users, downloaded on demand.
        MyUrlSpan.showView(combinedTimelineToggle,
                paramsNew.getSelectedUserId() == 0 ||
                        paramsNew.getSelectedUserId() == paramsNew.getMyAccount().getUserId());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                if (mDrawerLayout.isDrawerOpen(Gravity.LEFT)) {
                    mDrawerLayout.closeDrawer(Gravity.LEFT);
                } else {
                    mDrawerLayout.openDrawer(Gravity.LEFT);
                }
                break;
            case R.id.global_search_menu_id:
                onSearchRequested(true);
                break;
            case R.id.search_menu_id:
                onSearchRequested();
                break;
            case R.id.sync_menu_item:
                manualSyncWithInternet(false, true);
                break;
            case R.id.commands_queue_id:
                startActivity(new Intent(getActivity(), QueueViewer.class));
                break;
            case R.id.preferences_menu_id:
                startMyPreferenceActivity();
                break;
            case R.id.help_menu_id:
                onHelp();
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onHelp() {
        Intent intent = new Intent(this, HelpActivity.class);
        intent.putExtra(HelpActivity.EXTRA_HELP_PAGE_INDEX, HelpActivity.PAGE_INDEX_USER_GUIDE);
        startActivity(intent);
    }

    public void onItemClick(TimelineViewItem item) {
        MyAccount ma = MyContextHolder.get().persistentAccounts().getAccountForThisMessage(item.originId,
                item.msgId, item.linkedUserId,
                paramsNew.getMyAccount().getUserId(), false);
        if (MyLog.isVerboseEnabled()) {
            MyLog.v(this,
                    "onItemClick, " + item
                            + "; " + item
                            + " account=" + ma.getAccountName());
        }
        if (item.msgId <= 0) {
            return;
        }
        Uri uri = MatchedUri.getTimelineItemUri(ma.getUserId(),
                paramsNew.getTimelineType(),
                paramsNew.isTimelineCombined(),
                paramsNew.getSelectedUserId(), item.msgId);

        String action = getIntent().getAction();
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            if (MyLog.isLoggable(this, MyLog.DEBUG)) {
                MyLog.d(this, "onItemClick, setData=" + uri);
            }
            setResult(RESULT_OK, new Intent().setData(uri));
        } else {
            if (MyLog.isLoggable(this, MyLog.DEBUG)) {
                MyLog.d(this, "onItemClick, startActivity=" + uri);
            }
            startActivity(MyAction.VIEW_CONVERSATION.getIntent(uri));
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        // Empty
    }

    @Override
    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                         int totalItemCount) {
        TimelineAdapter adapter = getListAdapter();
        if (adapter != null) {
            boolean up = false;
            if (firstVisibleItem == 0) {
                View v = getListView().getChildAt(0);
                int offset = (v == null) ? 0 : v.getTop();
                up = offset == 0;
                if (up && adapter.getPages().mayHaveYoungerPage()) {
                    showList(WhichPage.YOUNGER);
                }
            }
            // Idea from http://stackoverflow.com/questions/1080811/android-endless-list
            if ( !up && (visibleItemCount > 0)
                    && (firstVisibleItem + visibleItemCount >= totalItemCount - 1)
                    && adapter.getPages().mayHaveOlderPage()) {
                MyLog.d(this, "Start Loading older items, rows=" + totalItemCount);
                showList(WhichPage.OLDER);
            }
        }
    }

    private String timelineTypeButtonText() {
        CharSequence timelineName = paramsNew.getTimelineType().getTitle(this);
        return timelineName + (paramsNew.hasSearchQuery() ? " *" : "");
    }

    private void updateAccountButtonText(ViewGroup mDrawerList) {
        TextView selectAccountButton = (TextView) mDrawerList.findViewById(R.id.selectAccountButton);
        String accountButtonText = paramsNew.toAccountButtonText();
        selectAccountButton.setText(accountButtonText);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (MyLog.isVerboseEnabled()) {
            MyLog.v(this, "onNewIntent, instanceId=" + mInstanceId
                    + (mFinishing ? ", Is finishing" : "")
                    );
        }
        if (mFinishing) {
            finish();
            return;
        }
        super.onNewIntent(intent);
        MyContextHolder.initialize(this, this);
        parseNewIntent(intent);
		if (!isPaused() || size() > 0 || isLoading()) {
            showList(paramsNew.whichPage);
		}
    }

    private void parseNewIntent(Intent intentNew) {
        if (MyLog.isVerboseEnabled()) {
            MyLog.v(this, "parseNewIntent:" + intentNew);
        }
        mRateLimitText = "";
        paramsNew.whichPage = WhichPage.load(
                intentNew.getStringExtra(IntentExtra.WHICH_PAGE.key), WhichPage.CURRENT);
        String searchQuery = intentNew.getStringExtra(SearchManager.QUERY);
        if (!parseAppSearchData(intentNew, searchQuery)
                && !paramsNew.parseUri(intentNew.getData(), searchQuery)) {
            paramsNew.setAtHome();
        }

        if (Intent.ACTION_SEND.equals(intentNew.getAction())) {
            shareViaThisApplication(intentNew.getStringExtra(Intent.EXTRA_SUBJECT),
                    intentNew.getStringExtra(Intent.EXTRA_TEXT),
                    (Uri) intentNew.getParcelableExtra(Intent.EXTRA_STREAM));
        }
    }

    private boolean parseAppSearchData(Intent intentNew, String searchQuery) {
        final String method = "parseAppSearchData";
        Bundle appSearchData = intentNew.getBundleExtra(SearchManager.APP_DATA);
        if (appSearchData != null
                && paramsNew.parseUri(Uri.parse(appSearchData.getString(
                        IntentExtra.TIMELINE_URI.key, "")), searchQuery)) {
            if (paramsNew.hasSearchQuery()
                    && appSearchData.getBoolean(IntentExtra.GLOBAL_SEARCH.key, false)) {
                showSyncing(method, "Global search: " + paramsNew.getTimeline().getSearchQuery());
                MyServiceManager.sendManualForegroundCommand(
                        CommandData.newSearch(
                                isTimelineCombined() ? null : paramsNew.getMyAccount(),
                                paramsNew.getTimeline().getSearchQuery()));
            }
            return true;
        }
        return false;
    }

    private void shareViaThisApplication(String subject, String text, Uri mediaUri) {
        if (TextUtils.isEmpty(subject) && TextUtils.isEmpty(text) && UriUtils.isEmpty(mediaUri)) {
            return;
        }
        mTextToShareViaThisApp = "";
        mMediaToShareViaThisApp = mediaUri;
        if (subjectHasAdditionalContent(subject, text)) {
            mTextToShareViaThisApp += subject;
        }
        if (!TextUtils.isEmpty(text)) {
            if (!TextUtils.isEmpty(mTextToShareViaThisApp)) {
                mTextToShareViaThisApp += " ";
            }
            mTextToShareViaThisApp += text;
        }
        MyLog.v(this, "Share via this app " 
                + (!TextUtils.isEmpty(mTextToShareViaThisApp) ? "; text:'" + mTextToShareViaThisApp +"'" : "") 
                + (!UriUtils.isEmpty(mMediaToShareViaThisApp) ? "; media:" + mMediaToShareViaThisApp.toString() : ""));
        AccountSelector.selectAccount(this, ActivityRequestCode.SELECT_ACCOUNT_TO_SHARE_VIA, 0);
    }

    static boolean subjectHasAdditionalContent(String subject, String text) {
        if (TextUtils.isEmpty(subject)) {
            return false;
        }
        if (TextUtils.isEmpty(text)) {
            return true;
        }
        return !text.startsWith(stripEllipsis(stripBeginning(subject)));
    }

    /**
     * Strips e.g. "Message - " or "Message:"
     */
    static String stripBeginning(String textIn) {
        if (TextUtils.isEmpty(textIn)) {
            return "";
        }
        int ind = textIn.indexOf("-");
        if (ind < 0) {
            ind = textIn.indexOf(":");
        }
        if (ind < 0) {
            return textIn;
        }
        String beginningSeparators = "-:;,.[] ";
        while ((ind < textIn.length()) && beginningSeparators.contains(String.valueOf(textIn.charAt(ind)))) {
            ind++;
        }
        if (ind >= textIn.length()) {
            return textIn;
        }
        return textIn.substring(ind);
    }

    static String stripEllipsis(String textIn) {
        if (TextUtils.isEmpty(textIn)) {
            return "";
        }
        int ind = textIn.length() - 1;
        String ellipsis = "… .";
        while (ind >= 0 && ellipsis.contains(String.valueOf(textIn.charAt(ind)))) {
            ind--;
        }
        if (ind < -1) {
            return "";
        }
        return textIn.substring(0, ind+1);
    }

    private void updateScreen() {
        MyServiceManager.setServiceAvailable();
        invalidateOptionsMenu();
        mMessageEditor.updateScreen();
        updateTitle(mRateLimitText);
        mDrawerToggle.setDrawerIndicatorEnabled(!getParamsLoaded().isAtHome());
        MyUrlSpan.showView(
                findViewById(R.id.switchToDefaultTimelineButton), !getParamsLoaded().isAtHome());
    }

    @Override
    protected void updateTitle(String additionalTitleText) {
        new TimelineTitle(getParamsLoaded().getTimelineType() == TimelineType.UNKNOWN ?
                paramsNew : getParamsLoaded(),
                additionalTitleText).updateTitle(this);
    }

    MessageContextMenu getContextMenu() {
        return mContextMenu;
    }

    /** Parameters of currently shown Timeline */
    @NonNull
    private TimelineListParameters getParamsLoaded() {
        return paramsLoaded == null ? paramsNew : paramsLoaded;
    }

    private void setParamsLoaded(TimelineListParameters paramsLoaded) {
        this.paramsLoaded = paramsLoaded;
    }

    static class TimelineTitle {
        private final TimelineListParameters ta;
        private final String additionalTitleText;

        public TimelineTitle(TimelineListParameters ta, String additionalTitleText) {
            this.ta = ta;
            this.additionalTitleText = additionalTitleText;
        }

        private void updateTitle(MyActivity activity) {
            activity.setTitle(ta.toTimelineTitle());
            activity.setSubtitle(ta.toTimelineSubtitle(additionalTitleText));
            if (MyLog.isVerboseEnabled()) {
                MyLog.v(activity, "Title: " + toString());
            }
        }

        @Override
        public String toString() {
            return ta.toTimelineTitleAndSubtitle(additionalTitleText);
        }
    }

    @Override
    protected void showList(WhichPage whichPage) {
        showList(whichPage, TriState.FALSE);
    }

    protected void showList(WhichPage whichPage, TriState chainedRequest) {
        showList(TimelineListParameters.clone(getReferenceParametersFor(whichPage), whichPage),
                chainedRequest);
    }

    @NonNull
    private TimelineListParameters getReferenceParametersFor(WhichPage whichPage) {
        TimelineAdapter adapter = getListAdapter();
        switch (whichPage) {
            case OLDER:
                if (adapter != null && adapter.getPages().getItemsCount() > 0) {
                    return adapter.getPages().list.get(adapter.getPages().list.size()-1).parameters;
                }
                return getParamsLoaded();
            case YOUNGER:
                if (adapter != null && adapter.getPages().getItemsCount() > 0) {
                    return adapter.getPages().list.get(0).parameters;
                }
                return getParamsLoaded();
            case EMPTY:
                return new TimelineListParameters(this);
            default:
                return paramsNew;
        }
    }

    /**
     * Prepare a query to the ContentProvider (to the database) and load the visible List of
     * messages with this data
     * This is done asynchronously.
     * This method should be called from UI thread only.
     */
    protected void showList(TimelineListParameters params, TriState chainedRequest) {
        final String method = "showList";
        if (params.isEmpty()) {
            MyLog.v(this, method + "; ignored empty request");
            return;
        }
        boolean isDifferentRequest = !params.equals(paramsToLoad);
        paramsToLoad = params;
        if (isLoading() && chainedRequest != TriState.TRUE) {
            if(MyLog.isVerboseEnabled()) {
                if (isDifferentRequest) {
                    MyLog.v(this, method + "; different while loading " + params.toSummary());
                } else {
                    MyLog.v(this, method + "; ignored duplicating " + params.toSummary());
                }
            }
        } else {
            MyLog.v(this, method
                    + (chainedRequest == TriState.TRUE ? "; chained" : "")
                    + "; requesting " + (isDifferentRequest ? "" : "duplicating ")
                    + params.toSummary());
            saveListPosition();
            showLoading(method, getText(R.string.loading) + " "
                    + paramsToLoad.toSummary() + HORIZONTAL_ELLIPSIS);
            super.showList(chainedRequest.toBundle(paramsToLoad.whichPage.toBundle(),
                    IntentExtra.CHAINED_REQUEST.key));
        }
    }

    @Override
    protected SyncLoader newSyncLoader(Bundle args) {
        final String method = "newSyncLoader";
        WhichPage whichPage = WhichPage.load(args);
        TimelineListParameters params = paramsToLoad == null
                || whichPage == WhichPage.EMPTY ?
                new TimelineListParameters(this) : paramsToLoad;
        if (params.whichPage != WhichPage.EMPTY) {
            MyLog.v(this, method + ": " + params);
            Intent intent = getIntent();
            if (!params.getContentUri().equals(intent.getData())) {
                intent.setData(params.getContentUri());
            }
            saveSearchQuery();
        }
        return new TimelineLoader(params, BundleUtils.fromBundle(args, IntentExtra.INSTANCE_ID));
    }

    private void saveSearchQuery() {
        if (paramsNew.hasSearchQuery()) {
            // Record the query string in the recent queries
            // of the Suggestion Provider
            SearchRecentSuggestions suggestions = new SearchRecentSuggestions(this,
                    TimelineSearchSuggestionsProvider.AUTHORITY,
                    TimelineSearchSuggestionsProvider.MODE);
            suggestions.saveRecentQuery(paramsNew.getTimeline().getSearchQuery(), null);

        }
    }

    @Override
    public void onLoadFinished(boolean keepCurrentPosition_in) {
        final String method = "onLoadFinished";
        TimelineLoader myLoader = (TimelineLoader) getLoaded();
        setParamsLoaded(myLoader.getParams());
        MyLog.v(this, method + "; " + getParamsLoaded().toSummary());

        // TODO start: Move this inside superclass
        boolean keepCurrentPosition = keepCurrentPosition_in && isPositionRestored()
                && getParamsLoaded().whichPage != WhichPage.TOP;
        super.onLoadFinished(keepCurrentPosition);
        if (getParamsLoaded().whichPage == WhichPage.TOP) {
            TimelineListPositionStorage.setPosition(getListView(), 0);
            getListAdapter().setPositionRestored(true);
        }
        // TODO end: Move this inside superclass

        if (!isPositionRestored()) {
            new TimelineListPositionStorage(getListAdapter(), getListView(), getParamsLoaded())
                    .restore();
        }

        TimelineListParameters anotherParams = paramsToLoad;
        boolean parametersChanged = anotherParams != null && !getParamsLoaded().equals(anotherParams);
        WhichPage anotherPageToRequest = WhichPage.EMPTY;
        if (!parametersChanged) {
            TimelineAdapter adapter = getListAdapter();
            if ( adapter.getCount() == 0) {
                if (adapter.getPages().mayHaveYoungerPage()) {
                    anotherPageToRequest = WhichPage.YOUNGER;
                } else if (adapter.getPages().mayHaveOlderPage()) {
                    anotherPageToRequest = WhichPage.OLDER;
                } else if (!getParamsLoaded().whichPage.isYoungest()) {
                    anotherPageToRequest = WhichPage.YOUNGEST;
                } else if (getParamsLoaded().rowsLoaded == 0) {
                    launchSyncIfNeeded(getParamsLoaded().timelineToSync);
                }
            }
        }
        hideLoading(method);
        updateScreen();
        clearNotifications();
        if (parametersChanged) {
            MyLog.v(this, method + "; Parameters changed, requesting " + anotherParams.toSummary());
            showList(anotherParams, TriState.TRUE);
        } else if (anotherPageToRequest != WhichPage.EMPTY) {
            MyLog.v(this, method + "; Nothing loaded, requesting " + anotherPageToRequest);
            showList(anotherPageToRequest, TriState.TRUE);
        }
    }

    private void launchSyncIfNeeded(TimelineType timelineToSync) {
        switch (timelineToSync) {
            case ALL:
                manualSyncWithInternet(true, false);
                break;
            case UNKNOWN:
                break;
            default:
                manualSyncWithInternet(false, false);
                break;
        }
    }

    /**
     * Ask a service to load data from the Internet for the selected TimelineType
     * Only newer messages (newer than last loaded) are being loaded (synced),
     * older ones are not being synced.
     */
    protected void manualSyncWithInternet(boolean allTimelineTypes, boolean manuallyLaunched) {
        final String method = "manualSync";
        MyAccount ma = paramsNew.getMyAccount();
        TimelineType timelineTypeToSync = TimelineType.HOME;
        long userId = 0;
        switch (paramsNew.getTimelineType()) {
            case DIRECT:
            case MENTIONS:
            case PUBLIC:
            case EVERYTHING:
                timelineTypeToSync = paramsNew.getTimelineType();
                break;
            case USER:
            case FOLLOWERS:
            case FRIENDS:
                timelineTypeToSync = paramsNew.getTimelineType();
                userId = paramsNew.getSelectedUserId();
                break;
            default:
                break;
        }
        boolean allAccounts = paramsNew.isTimelineCombined();
        if (userId != 0) {
            allAccounts = false;
            long originId = MyQuery.userIdToLongColumnValue(UserTable.ORIGIN_ID, userId);
            if (originId == 0) {
                MyLog.e(this, "Unknown origin for userId=" + userId);
                return;
            }
            if (!ma.isValid() || ma.getOriginId() != originId) {
                ma = MyContextHolder.get().persistentAccounts().fromUserId(userId);
                if (!ma.isValid()) {
                    ma = MyContextHolder.get().persistentAccounts().findFirstSucceededMyAccountByOriginId(originId);
                }
            }
        }
        if (!allAccounts && !ma.isValid()) {
            return;
        }

        setCircularSyncIndicator(method, true);
        showSyncing(method, getText(R.string.options_menu_sync));
        MyServiceManager.sendForegroundCommand(
                CommandData.newTimelineCommand(
                        CommandEnum.FETCH_TIMELINE,
                        allAccounts ? null : ma,
                        timelineTypeToSync,
                        userId,
                        userId == 0 ? null : paramsNew.getTimeline().getOrigin()
                ).setManuallyLaunched(manuallyLaunched)
        );

        if (allTimelineTypes && ma.isValid()) {
            ma.requestSync();
        }
    }

    protected void startMyPreferenceActivity() {
        finish();
        startActivity(new Intent(this, MySettingsActivity.class));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        paramsNew.saveState(outState);
        mContextMenu.saveState(outState);
    }

    protected void crashTest() {
        final String CRASH_TEST_STRING = "Crash test 2015-04-10";
        if (MyLog.isVerboseEnabled() && mMessageEditor != null &&
                    mMessageEditor.getData().body.contains(CRASH_TEST_STRING)) {
            MyLog.e(this, "Initiating crash test exception");
            throw new NullPointerException("This is a test crash event");
        }
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        if (selectorActivityMock != null) {
            selectorActivityMock.startActivityForResult(intent, requestCode);
        } else {
            super.startActivityForResult(intent, requestCode);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        MyLog.v(this, "onActivityResult; request:" + requestCode + ", result:" + (resultCode == RESULT_OK ? "ok" : "fail"));
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        switch (ActivityRequestCode.fromId(requestCode)) {
            case SELECT_ACCOUNT:
                accountSelected(data);
                break;
            case SELECT_ACCOUNT_TO_ACT_AS:
                accountToActAsSelected(data);
                break;
            case SELECT_ACCOUNT_TO_SHARE_VIA:
                accountToShareViaSelected(data);
                break;
            case ATTACH:
                attachmentSelected(data);
                break;
            case SELECT_TIMELINE:
                Timeline timeline = MyContextHolder.get().persistentTimelines()
                        .fromId(data.getLongExtra(IntentExtra.TIMELINE_ID.key, 0));
                if (timeline.isValid()) {
                    paramsNew.switchTimeline(timeline);
                    mContextMenu.switchTimelineActivity(paramsNew.getContentUri());
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    private void accountSelected(Intent data) {
        MyAccount ma = MyContextHolder.get().persistentAccounts().fromAccountName(data.getStringExtra(IntentExtra.ACCOUNT_NAME.key));
        if (ma.isValid()) {
            MyLog.v(this, "Restarting the activity for the selected account " + ma.getAccountName());
            finish();
            TimelineType timelineTypeNew = paramsNew.getTimelineType();
            if (paramsNew.getTimelineType() == TimelineType.USER
                    && !MyContextHolder.get().persistentAccounts()
                            .fromUserId(paramsNew.getSelectedUserId()).isValid()) {
                /*  "Other User's timeline" vs "My User's timeline" 
                 * Actually we saw messages of the user, who is not MyAccount,
                 * so let's switch to the HOME
                 * TODO: Open "Other User's timeline" in a separate Activity?!
                 */
                timelineTypeNew = TimelineType.HOME;
            }
            mContextMenu.switchTimelineActivity(ma, timelineTypeNew, paramsNew.isTimelineCombined(), ma.getUserId());
        }
    }

    private void accountToActAsSelected(Intent data) {
        MyAccount ma = MyContextHolder.get().persistentAccounts().fromAccountName(data.getStringExtra(IntentExtra.ACCOUNT_NAME.key));
        if (ma.isValid()) {
            mContextMenu.setAccountUserIdToActAs(ma.getUserId());
            mContextMenu.showContextMenu();
        }
    }

    private void accountToShareViaSelected(Intent data) {
        MyAccount ma = MyContextHolder.get().persistentAccounts().fromAccountName(data.getStringExtra(IntentExtra.ACCOUNT_NAME.key));
        mMessageEditor.startEditingSharedData(ma, mTextToShareViaThisApp, mMediaToShareViaThisApp);
    }

    private void attachmentSelected(Intent data) {
        Uri uri = UriUtils.notNull(data.getData());
        if (!UriUtils.isEmpty(uri)) {
            UriUtils.takePersistableUriPermission(getActivity(), uri, data.getFlags());
            mMessageEditor.startEditingCurrentWithAttachedMedia(uri);
        }
    }

    @Override
    protected boolean isEditorVisible() {
        return getMessageEditor().isVisible();
    }

    @Override
    protected boolean isCommandToShowInSyncIndicator(CommandData commandData) {
        switch (commandData.getCommand()) {
            case FETCH_TIMELINE:
            case SEARCH_MESSAGE:
            case FETCH_ATTACHMENT:
            case FETCH_AVATAR:
            case UPDATE_STATUS:
            case DESTROY_STATUS:
            case CREATE_FAVORITE:
            case DESTROY_FAVORITE:
            case FOLLOW_USER:
            case STOP_FOLLOWING_USER:
            case REBLOG:
            case DESTROY_REBLOG:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean canSwipeRefreshChildScrollUp() {
        if ((mMessageEditor != null && mMessageEditor.isVisible()) ||  super.canSwipeRefreshChildScrollUp()) {
            return true;
        }
        if (getListAdapter() == null || getListAdapter().getPages().mayHaveYoungerPage()) {
            return true;
        }
        return false;
    }

    @Override
    protected void onReceiveAfterExecutingCommand(CommandData commandData) {
        switch (commandData.getCommand()) {
            case RATE_LIMIT_STATUS:
                if (commandData.getResult().getHourlyLimit() > 0) {
                    mRateLimitText = commandData.getResult().getRemainingHits() + "/"
                            + commandData.getResult().getHourlyLimit();
                    updateTitle(mRateLimitText);
                }
                break;
            case UPDATE_STATUS:
                mMessageEditor.loadCurrentDraft();
                break;
            default:
                break;
        }
        if (!TextUtils.isEmpty(syncingText)) {
            if (MyServiceManager.getServiceState() != MyServiceState.RUNNING) {
                hideSyncing("Service is not running");
            } else if (isCommandToShowInSyncIndicator(commandData)) {
                showSyncing("After executing " + commandData.getCommand(), HORIZONTAL_ELLIPSIS);
            }
        }
        super.onReceiveAfterExecutingCommand(commandData);
    }

    @Override
    public boolean isRefreshNeededAfterExecuting(CommandData commandData) {
        boolean needed = super.isRefreshNeededAfterExecuting(commandData);
        switch (commandData.getCommand()) {
            case AUTOMATIC_UPDATE:
            case FETCH_TIMELINE:
                if (!getParamsLoaded().isLoaded()
                        || getParamsLoaded().getTimelineType() != commandData.getTimelineType()) {
                    break;
                }
            case SEARCH_MESSAGE:
                if (commandData.getResult().getDownloadedCount() > 0) {
                    needed = true;
                }
                break;
            default:
                break;
        }
        return needed;
    }

    @Override
    protected boolean isAutoRefreshAllowedAfterExecuting(CommandData commandData) {
        boolean allowed = super.isAutoRefreshAllowedAfterExecuting(commandData)
                && SharedPreferencesUtil.getBoolean(MyPreferences.KEY_REFRESH_TIMELINE_AUTOMATICALLY, true);
        if (allowed) {
            TimelineAdapter adapter = getListAdapter();
            if (adapter == null || adapter.getPages().mayHaveYoungerPage()) {
                // Update a list only if we already show the youngest page
                allowed = false;
            }
        }
        return allowed;
    }

    @Override
    public LoadableListActivity getActivity() {
        return this;
    }

    @Override
    public MessageEditor getMessageEditor() {
        return mMessageEditor;
    }

    @Override
    public void onMessageEditorVisibilityChange() {
        hideSyncing("onMessageEditorVisibilityChange");
        invalidateOptionsMenu();
    }
    
    @Override
    public long getCurrentMyAccountUserId() {
        return paramsNew.getMyAccount().getUserId();
    }

    @Override
    public TimelineType getTimelineType() {
        return paramsNew.getTimelineType();
    }

    @Override
    public boolean isTimelineCombined() {
        return paramsNew.isTimelineCombined();
    }

    @Override
    public long getSelectedUserId() {
        return paramsNew.getSelectedUserId();
    }
}
