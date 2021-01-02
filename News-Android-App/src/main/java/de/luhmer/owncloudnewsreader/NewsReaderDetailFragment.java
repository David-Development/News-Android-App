/**
* Android ownCloud News
*
* @author David Luhmer
* @copyright 2013 David Luhmer david-dev@live.de
*
* This library is free software; you can redistribute it and/or
* modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE
* License as published by the Free Software Foundation; either
* version 3 of the License, or any later version.
*
* This library is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU AFFERO GENERAL PUBLIC LICENSE for more details.
*
* You should have received a copy of the GNU Affero General Public
* License along with this library.  If not, see <http://www.gnu.org/licenses/>.
*
*/

package de.luhmer.owncloudnewsreader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.view.GestureDetectorCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import de.luhmer.owncloudnewsreader.adapter.NewsListRecyclerAdapter;
import de.luhmer.owncloudnewsreader.adapter.ViewHolder;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm.SORT_DIRECTION;
import de.luhmer.owncloudnewsreader.database.model.RssItem;
import de.luhmer.owncloudnewsreader.database.model.RssItemDao;
import de.luhmer.owncloudnewsreader.helper.AsyncTaskHelper;
import de.luhmer.owncloudnewsreader.helper.DatabaseUtils;
import de.luhmer.owncloudnewsreader.helper.PostDelayHandler;
import de.luhmer.owncloudnewsreader.helper.Search;
import de.luhmer.owncloudnewsreader.helper.StopWatch;
import io.reactivex.observers.DisposableObserver;

import static de.luhmer.owncloudnewsreader.ListView.SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.ALL_STARRED_ITEMS;
import static de.luhmer.owncloudnewsreader.ListView.SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.ALL_UNREAD_ITEMS;
import static de.luhmer.owncloudnewsreader.SettingsActivity.SP_SWIPE_LEFT_ACTION;
import static de.luhmer.owncloudnewsreader.SettingsActivity.SP_SWIPE_LEFT_ACTION_DEFAULT;
import static de.luhmer.owncloudnewsreader.SettingsActivity.SP_SWIPE_RIGHT_ACTION;
import static de.luhmer.owncloudnewsreader.SettingsActivity.SP_SWIPE_RIGHT_ACTION_DEFAULT;

/**
 * A fragment representing a single NewsReader detail screen. This fragment is
 * either contained in a {@link NewsReaderListActivity} in two-pane mode (on
 * tablets) or a {@link NewsReaderListActivity} on handsets.
 */
public class NewsReaderDetailFragment extends Fragment {

    private static final String LAYOUT_MANAGER_STATE = "LAYOUT_MANAGER_STATE";

    protected final String TAG = getClass().getCanonicalName();

    @BindView(R.id.pb_loading)
    ProgressBar pbLoading;
    @BindView(R.id.tv_no_items_available)
    View tvNoItemsAvailable;
    @BindView(R.id.list)
    RecyclerView recyclerView;
    @BindView(R.id.swipeRefresh)
    SwipeRefreshLayout swipeRefresh;

    private Long idFeed;
    private Drawable leftSwipeDrawable;
    private Drawable rightSwipeDrawable;
    private String prevLeftAction = "";
    private String prevRightAction = "";
    private int accentColor;
    private Parcelable layoutManagerSavedState;

    // Variables related to mark as read when scrolling
    private boolean mMarkAsReadWhileScrollingEnabled;
    private int previousFirstVisibleItem = -1;

    private Long idFolder;
    private String titel;
    private int onResumeCount = 0;
    private RecyclerView.OnItemTouchListener itemTouchListener;

    protected @Inject SharedPreferences mPrefs;
    protected @Inject PostDelayHandler mPostDelayHandler;

    private PodcastFragmentActivity mActivity;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public NewsReaderDetailFragment() {
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.mActivity = (PodcastFragmentActivity) context;
    }

    @Override
    public void onDetach() {
        this.mActivity = null;
        super.onDetach();
    }

    protected DisposableObserver<List<RssItem>> searchResultObserver = new DisposableObserver<List<RssItem>>() {
        @Override
        public void onNext(List<RssItem> rssItems) {
            loadRssItemsIntoView(rssItems);
        }

        @Override
        public void onError(Throwable e) {
            pbLoading.setVisibility(View.GONE);
            Toast.makeText(mActivity, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
        }

        @Override
        public void onComplete() {
            Log.v(TAG, "Search Completed!");
        }
    };


    public static SORT_DIRECTION getSortDirection(SharedPreferences prefs) {
        return DatabaseUtils.getSortDirectionFromSettings(prefs);
    }

    /**
     * @return the idFeed
     */
    public Long getIdFeed() {
        return idFeed;
    }

    /**
     * @return the idFolder
     */
    public Long getIdFolder() {
        return idFolder;
    }

    /**
     * @return the titel
     */
    public String getTitel() {
        return titel;
    }

    protected void setData(Long idFeed, Long idFolder, String title, boolean updateListView) {
        Log.v(TAG, "Creating new instance");

        this.idFeed = idFeed;
        this.idFolder = idFolder;
        this.titel = title;
        mActivity.getSupportActionBar().setTitle(title);

        if (updateListView) {
            updateCurrentRssView();
        } else {
            refreshCurrentRssView();
        }
    }

    @Override
    public void onResume() {
        Log.v(TAG, "onResume called!");

        mMarkAsReadWhileScrollingEnabled = mPrefs.getBoolean(SettingsActivity.CB_MARK_AS_READ_WHILE_SCROLLING_STRING, false);
        this.initFastDoneAll(this.getView());

        //When the fragment is instantiated by the xml file, onResume will be called twice
        if (onResumeCount >= 2) {
            refreshCurrentRssView();
        }
        onResumeCount++;

        updateSwipeDrawables(false);

        super.onResume();
    }

    protected void updateMenuItemsState() {
        NewsReaderListActivity nla = (NewsReaderListActivity) mActivity;
        if(nla != null && nla.getMenuItemDownloadMoreItems() != null) {
            if (idFolder != null && idFolder == ALL_UNREAD_ITEMS.getValue()) {
                nla.getMenuItemDownloadMoreItems().setEnabled(false);
            } else {
                nla.getMenuItemDownloadMoreItems().setEnabled(true);
            }
        }
    }

    protected void notifyDataSetChangedOnAdapter() {
        NewsListRecyclerAdapter nca = (NewsListRecyclerAdapter) recyclerView.getAdapter();
        if (nca != null) {
            nca.notifyDataSetChanged();
        }
    }

    /**
     * Refreshes the current RSS-View
     */
    protected void refreshCurrentRssView() {
        Log.v(TAG, "refreshCurrentRssView");
        NewsListRecyclerAdapter nra = ((NewsListRecyclerAdapter) recyclerView.getAdapter());

        if (nra != null) {
            nra.refreshAdapterDataAsync(() -> {
                pbLoading.setVisibility(View.GONE);

                if (layoutManagerSavedState != null) {
                    recyclerView.getLayoutManager().onRestoreInstanceState(layoutManagerSavedState);
                    layoutManagerSavedState = null;
                }
            });
        }
    }

    /**
     * Init fast action for mark all as read shown as floating action bar button (fab)
     *
     * @param rootView root view of fragment
     */
    protected void initFastDoneAll(View rootView) {
        FloatingActionButton fab_done_all = rootView.findViewById(R.id.fab_done_all);
        if (mPrefs.getBoolean(SettingsActivity.CB_SHOW_FAST_ACTIONS, true)) {
            fab_done_all.setVisibility(View.VISIBLE);
            fab_done_all.setOnTouchListener(new FastMarkReadMotionListener(rootView));
        } else {
            fab_done_all.setVisibility(View.GONE);
        }
    }

    /**
     * Updates the current RSS-View
     */
    public void updateCurrentRssView() {
        Log.v(TAG, "updateCurrentRssView");
        AsyncTaskHelper.StartAsyncTask(new UpdateCurrentRssViewTask());
    }

    public RecyclerView getRecyclerView() {
        return recyclerView;
    }

    public LinearLayoutManager getLayoutManager() {
        if (recyclerView == null) return null;
        return (LinearLayoutManager) recyclerView.getLayoutManager();
    }

    protected List<RssItem> performSearch(String searchString) {
        Handler mainHandler = new Handler(mActivity.getMainLooper());

        Runnable myRunnable = () -> {
            pbLoading.setVisibility(View.VISIBLE);
            tvNoItemsAvailable.setVisibility(View.GONE);
        };
        mainHandler.post(myRunnable);

        return Search.PerformSearch(mActivity, idFolder, idFeed, searchString, mPrefs);
    }

    void loadRssItemsIntoView(List<RssItem> rssItems) {
        previousFirstVisibleItem = -1;
        try {
            NewsListRecyclerAdapter nra = ((NewsListRecyclerAdapter) recyclerView.getAdapter());
            if (nra == null) {
                nra = new NewsListRecyclerAdapter(mActivity, recyclerView, mActivity, mPostDelayHandler, mPrefs);
                recyclerView.setAdapter(nra);
            }
            nra.updateAdapterData(rssItems);

            pbLoading.setVisibility(View.GONE);
            if (nra.getItemCount() <= 0) {
                tvNoItemsAvailable.setVisibility(View.VISIBLE);
            } else {
                tvNoItemsAvailable.setVisibility(View.GONE);
            }

            recyclerView.scrollToPosition(0);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_newsreader_detail, container, false);

        ButterKnife.bind(this, rootView);

        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(mActivity, RecyclerView.VERTICAL, false));
        recyclerView.setItemAnimator(new DefaultItemAnimator());

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new NewsReaderItemTouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(recyclerView);
        //recyclerView.addItemDecoration(new DividerItemDecoration(mActivity)); // Enable divider line

        /*
        recyclerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ((NewsReaderListActivity) mActivity).clearSearchViewFocus();
                return false;
            }
        });
        */

        swipeRefresh.setColorSchemeColors(accentColor);
        swipeRefresh.setOnRefreshListener((SwipeRefreshLayout.OnRefreshListener) mActivity);

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) { //check for scroll down
                    if (mMarkAsReadWhileScrollingEnabled) {
                        //Log.v(TAG, "Scroll Delta y: " + dy);
                        handleMarkAsReadScrollEvent();
                    }
                }
            }
        });

        itemTouchListener = new RecyclerView.OnItemTouchListener() {
            GestureDetectorCompat detector = new GestureDetectorCompat(mActivity, new RecyclerViewOnGestureListener());

            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                detector.onTouchEvent(e);
                return false;
            }

            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent e) {
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            }
        };

        return rootView;
    }

    private void handleMarkAsReadScrollEvent() {
        LinearLayoutManager linearLayoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
        NewsListRecyclerAdapter adapter = (NewsListRecyclerAdapter) recyclerView.getAdapter();

        int firstVisibleItem = linearLayoutManager.findFirstVisibleItemPosition();
        int lastVisibleItem = linearLayoutManager.findLastVisibleItemPosition();
        int visibleItemCount = lastVisibleItem - firstVisibleItem;
        int totalItemCount = adapter.getItemCount();
        boolean reachedBottom = (lastVisibleItem == (totalItemCount - 1));

        // Exit if the position didn't change.
        if (firstVisibleItem == previousFirstVisibleItem && !reachedBottom) {
            return;
        }
        previousFirstVisibleItem = firstVisibleItem;


        //Log.v(TAG, "First visible: " + firstVisibleItem + " - Last visible: " + lastVisibleItem + " - visible count: " + visibleItemCount + " - total count: " + totalItemCount);

        //Set the item at top to read
        //ViewHolder vh = (ViewHolder) recyclerView.findViewHolderForLayoutPosition(firstVisibleItem);

        // Mark the first two items as read
        final int numberItemsAhead = 1;
        for (int i = firstVisibleItem; i < firstVisibleItem + numberItemsAhead; i++) {
            //Log.v(TAG, "Mark item as read: " + i);

            ViewHolder vh = (ViewHolder) recyclerView.findViewHolderForLayoutPosition(i);
            if (vh != null && !vh.shouldStayUnread()) {
                adapter.changeReadStateOfItem(vh, true);
            }
        }

        //Check if Listview is scrolled to bottom
        if (reachedBottom && visibleItemCount != 0 && //Check if list is empty
                recyclerView.getChildAt(visibleItemCount).getBottom() <= recyclerView.getHeight()) {

            for (int i = firstVisibleItem; i <= lastVisibleItem; i++) {
                RecyclerView.ViewHolder vhTemp = recyclerView.findViewHolderForLayoutPosition(i);

                if (vhTemp instanceof ViewHolder) { //Check for ViewHolder instance because of ProgressViewHolder
                    ViewHolder vh = (ViewHolder) vhTemp;

                    if (!vh.shouldStayUnread()) {
                        adapter.changeReadStateOfItem(vh, true);
                    } else {
                        Log.v(TAG, "shouldStayUnread");
                    }
                }
            }
        }
    }

    @Override
    public void onInflate(Context context, AttributeSet attrs, Bundle savedInstanceState) {
        super.onInflate(context, attrs, savedInstanceState);

        ((NewsReaderApplication) getActivity().getApplication()).getAppComponent().injectFragment(this);

        TypedArray styledAttributes = context.obtainStyledAttributes(attrs, new int[]{R.attr.colorAccent});
        updateSwipeDrawables(true);
        int color = Constants.isNextCloud(mPrefs) ? R.color.nextcloudBlue : R.color.owncloudBlue;
        accentColor = styledAttributes.getColor(2, ContextCompat.getColor(context, color));
        styledAttributes.recycle();
    }

    /**
     *
     * @param forceUpdate force swipe drawables to be reloaded
     */
    private void updateSwipeDrawables(boolean forceUpdate) {
        String leftAction  = mPrefs.getString(SP_SWIPE_LEFT_ACTION, SP_SWIPE_LEFT_ACTION_DEFAULT);
        String rightAction = mPrefs.getString(SP_SWIPE_RIGHT_ACTION, SP_SWIPE_RIGHT_ACTION_DEFAULT);

        if (!forceUpdate && leftAction.equals(prevLeftAction) && rightAction.equals(prevRightAction)) {
            return;
        }

        prevLeftAction  = leftAction;
        prevRightAction = rightAction;
        int leftId  = getLayoutId(leftAction);
        int rightId = getLayoutId(rightAction);

        TypedArray styledAttributes = getContext().obtainStyledAttributes(new int[]{leftId, rightId});
        leftSwipeDrawable = styledAttributes.getDrawable(0);
        rightSwipeDrawable = styledAttributes.getDrawable(1);
        styledAttributes.recycle();
    }

    private int getLayoutId(String action) {
        switch (action) {
            case "0": return R.attr.openinbrowserDrawable;
            case "1": return R.attr.starredDrawable;
            case "2": return R.attr.markasreadDrawable;
            default:
                Log.e(TAG, "Invalid option saved to prefs. This should not happen");
                return Integer.MAX_VALUE;
        }
    }

    @Override
    public void onViewStateRestored(Bundle savedInstanceState) {
        if (savedInstanceState != null)
            layoutManagerSavedState = savedInstanceState.getParcelable(LAYOUT_MANAGER_STATE);
        super.onViewStateRestored(savedInstanceState);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(LAYOUT_MANAGER_STATE, getLayoutManager().onSaveInstanceState());
    }

    public int getFirstVisibleScrollPosition() {
        LinearLayoutManager layoutManager = ((LinearLayoutManager) recyclerView.getLayoutManager());
        return layoutManager.findFirstVisibleItemPosition();
    }

    private class UpdateCurrentRssViewTask extends AsyncTask<Void, Void, List<RssItem>> {

        @Override
        protected void onPreExecute() {
            pbLoading.setVisibility(View.VISIBLE);
            tvNoItemsAvailable.setVisibility(View.GONE);
            super.onPreExecute();
        }

        @Override
        protected List<RssItem> doInBackground(Void... voids) {
            DatabaseConnectionOrm dbConn = new DatabaseConnectionOrm(NewsReaderDetailFragment.this.getContext());
            SORT_DIRECTION sortDirection = getSortDirection(mPrefs);
            boolean onlyUnreadItems = mPrefs.getBoolean(SettingsActivity.CB_SHOWONLYUNREAD_STRING, false);
            boolean onlyStarredItems = false;
            if (idFolder != null && idFolder == ALL_STARRED_ITEMS.getValue())
                onlyStarredItems = true;

            String sqlSelectStatement = null;
            if (idFeed != null) {
                if (idFolder != null && idFolder == ALL_UNREAD_ITEMS.getValue()) {
                    onlyUnreadItems = true;
                }
                sqlSelectStatement = dbConn.getAllItemsIdsForFeedSQL(idFeed, onlyUnreadItems, onlyStarredItems, sortDirection);
            } else if (idFolder != null) {
                if (idFolder == ALL_STARRED_ITEMS.getValue())
                    onlyUnreadItems = false;
                sqlSelectStatement = dbConn.getAllItemsIdsForFolderSQL(idFolder, onlyUnreadItems, sortDirection);
            }
            if (sqlSelectStatement != null) {
                int index = sqlSelectStatement.indexOf("ORDER BY");
                if (index == -1) {
                    index = sqlSelectStatement.length();
                }
                sqlSelectStatement = new StringBuilder(sqlSelectStatement).insert(index, " GROUP BY " + RssItemDao.Properties.Fingerprint.columnName + " ").toString();
                dbConn.insertIntoRssCurrentViewTable(sqlSelectStatement);
            }

            StopWatch sw = new StopWatch();
            sw.start();

            List<RssItem> items = dbConn.getCurrentRssItemView(0);

            sw.stop();
            Log.v(TAG, "Time needed (init loading): " + sw.toString());

            return items;
        }

        @Override
        protected void onPostExecute(List<RssItem> rssItem) {
            loadRssItemsIntoView(rssItem);

            if (rssItem.size() < 10) { // Less than 10 items in the list (usually 3-5 items fit on one screen)
                // There is no API to check, if this listener has already been added. We don't want to
                // add it multiple times, so we take the safe route here by removing it before adding it.
                recyclerView.removeOnItemTouchListener(itemTouchListener);
                recyclerView.addOnItemTouchListener(itemTouchListener);
            } else {
                recyclerView.removeOnItemTouchListener(itemTouchListener);
            }
        }
    }

    private class RecyclerViewOnGestureListener extends GestureDetector.SimpleOnGestureListener {
        private int minLeftEdgeDistance = -1;

        private void initEdgeDistance() {
            if (getResources().getBoolean(R.bool.isTablet)) {
                // if tablet mode enabled, the navigation drawer will always be visible.
                // Therefore we don't need no offset here
                minLeftEdgeDistance = 0;
            } else {
                // otherwise, have left-edge offset to avoid mark-read gesture when user is pulling to open drawer
                minLeftEdgeDistance = ((NewsReaderListActivity) mActivity).getEdgeSizeOfDrawer();
            }
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (minLeftEdgeDistance == -1) { // if not initialized
                initEdgeDistance();
            }

            if (mMarkAsReadWhileScrollingEnabled &&
                    e1.getX() > minLeftEdgeDistance &&   // only if gesture starts a bit away from left window edge
                    (e2.getY() - e1.getY()) < 0) {       // and if swipe direction is upwards
                handleMarkAsReadScrollEvent();
                return true;
            }
            return false;
        }
    }

    // TODO: somehow always cancel item out animation
    private class NewsReaderItemTouchHelperCallback extends ItemTouchHelper.SimpleCallback {
        public NewsReaderItemTouchHelperCallback() {
            super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        }

        @Override
        public float getSwipeThreshold(RecyclerView.ViewHolder viewHolder) {
            return 0.25f;
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public void onSwiped(final RecyclerView.ViewHolder viewHolder, final int direction) {
            final NewsListRecyclerAdapter adapter = (NewsListRecyclerAdapter) recyclerView.getAdapter();

            String swipeAction;
            if (direction == ItemTouchHelper.LEFT)
                swipeAction = mPrefs.getString(SP_SWIPE_LEFT_ACTION, SP_SWIPE_LEFT_ACTION_DEFAULT);
            else
                swipeAction = mPrefs.getString(SP_SWIPE_RIGHT_ACTION, SP_SWIPE_RIGHT_ACTION_DEFAULT);
            switch (swipeAction) {
                case "0": // Open link in browser and mark as read
                    String currentUrl = ((ViewHolder) viewHolder).getRssItem().getLink();
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl));
                    startActivity(browserIntent);
                    adapter.changeReadStateOfItem((ViewHolder) viewHolder, true);
                    break;
                case "1": // Star
                    adapter.toggleStarredStateOfItem((ViewHolder) viewHolder);
                    break;
                case "2": // Read
                    adapter.toggleReadStateOfItem((ViewHolder) viewHolder);
                    break;
                default:
                    Log.e(TAG, "Swipe preferences has an invalid value");
                    break;
            }
            // Hack to reset view, see https://code.google.com/p/android/issues/detail?id=175798
            recyclerView.removeView(viewHolder.itemView);
        }

        @Override
        public void onChildDraw(Canvas c, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            // swipeRefresh cancels swiping left/right when accidentally moving in the y direction;
            swipeRefresh.setEnabled(!isCurrentlyActive);
            if (isCurrentlyActive) {
                Rect viewRect = new Rect();
                viewHolder.itemView.getDrawingRect(viewRect);
                float fractionMoved = Math.abs(dX / viewHolder.itemView.getMeasuredWidth());
                Drawable drawable;
                if (dX < 0) {
                    drawable = leftSwipeDrawable;
                    viewRect.left = (int) dX + viewRect.right;
                } else {
                    drawable = rightSwipeDrawable;
                    viewRect.right = (int) dX - viewRect.left;
                }

                if (fractionMoved > getSwipeThreshold(viewHolder))
                    drawable.setState(new int[]{android.R.attr.state_above_anchor});
                else
                    drawable.setState(new int[]{-android.R.attr.state_above_anchor});

                viewRect.offset(0, viewHolder.itemView.getTop());
                drawable.setBounds(viewRect);
                drawable.draw(c);
            }
        }
    }

    /**
     * MotionListener for Floating Action Bar Button to mark all articles in current
     * news feed as marked without using the menu.
     *
     * A movement up is required to prevent accidentally marking articles as read.
     */
    private class FastMarkReadMotionListener implements View.OnTouchListener {
        private View fabMarkAllAsRead;
        private ImageView targetView;

        private boolean markAsRead = false;
        private float originX,
                      originY;
        private float dx,
                      dy;

        public FastMarkReadMotionListener(View fabMarkAllAsRead) {
            this.fabMarkAllAsRead = fabMarkAllAsRead;
            this.targetView = (ImageView)fabMarkAllAsRead.findViewById(R.id.target_done_all);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    this.startUserInteractionProcess(v, event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    this.moveFAB(v, event);
                    break;
                case MotionEvent.ACTION_UP:
                    this.stopUserInteractionProcess(v);
                    break;
                default:
                    // Do nothing
                    break;
            }
            return true;
        }

        /**
         * Start Animation for user to drag all read button to target.
         * Once the button is moved to the target, a success animation is loaded and shown.
         *
         * @param v FAB moved by the user
         * @param event motion event for v
         */

        private void startUserInteractionProcess(View v, MotionEvent event) {
            // Save start location of movement and button
            this.originX = v.getX();
            this.originY = v.getY();
            this.dx = v.getX() - event.getRawX();
            this.dy = v.getY() - event.getRawY();
            this.markAsRead = false;

            // Start animation of target
            this.targetView.setImageResource(R.drawable.fa_all_read_target);
            this.targetView.setVisibility(View.VISIBLE);
            ((Animatable)this.targetView.getDrawable()).start();
        }

        /**
         * Handle move event of FAB to mark all articles as read
         * Two things are done here:
         *  - button location is changed
         *  - it is checked iv button is moved into target area
         *
         * @param v FAB moved by the user
         * @param event motion event for v
         */
        private void moveFAB(View v, MotionEvent event) {
            v.setX(event.getRawX() + this.dx);
            v.setY(event.getRawY() + this.dy);
            this.checkLocation(event);
        }

        /**
         * Checks if FAB to mark all as read was moved within the shown target area.
         * For location calculation, the actual location of the target view is read
         * and calculated if current move position is within the view area of the target view.
         *
         * @param evt MotionEvent of all read FAB
         */
        private void checkLocation(MotionEvent evt) {
            // Location on screen for target is required as motion event returns location on screen
            int[] location = new int[2];
            this.targetView.getLocationOnScreen(location);

            Rect r = new Rect(location[0], location[1],
                    (location[0] + targetView.getWidth()),
                    (location[1] + targetView.getHeight()));

            if (r.contains((int)evt.getRawX(), (int)evt.getRawY())) {
                if (!this.markAsRead) {
                    this.markAsRead = true;
                    this.targetView.setImageResource(R.drawable.fa_all_read_target_success);
                    ((Animatable) this.targetView.getDrawable()).start();
                }
            } else {
                if (this.markAsRead) {
                    this.markAsRead = false;
                    this.targetView.setImageResource(R.drawable.fa_all_read_target);
                    ((Animatable) this.targetView.getDrawable()).start();

                }
            }
        }

        /**
         * Stops the user interaction
         *  - FAB is animated back to original position
         *  - A success animation is shown of all articles will be marked as read
         *  - Target view is hidden again
         *
         * @param v view of fab
         */
        private void stopUserInteractionProcess(View v) {
            if (this.markAsRead) {
                Animation anim_success = AnimationUtils.loadAnimation(NewsReaderDetailFragment.this.getContext(),
                        R.anim.all_read_success);
                anim_success.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {
                        v.animate().x(originX).y(originY).setDuration(100).setStartDelay(0).start();
                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        ((Animatable)targetView.getDrawable()).stop();
                        targetView.setVisibility(View.INVISIBLE);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {
                        //Nothing to do here for now
                    }
                });
                this.targetView.startAnimation(anim_success);
                this.markAllAsReadForCurrentView();
            } else {
                this.targetView.setVisibility(View.INVISIBLE);
                v.animate().x(this.originX).y(this.originY).setDuration(100).setStartDelay(0).start();
                ((Animatable)this.targetView.getDrawable()).stop();
            }
        }

        /**
         * Mark all articles in current view as read.
         */
        private void markAllAsReadForCurrentView() {
            DatabaseConnectionOrm dbConn2 = new DatabaseConnectionOrm(this.fabMarkAllAsRead.getContext());
            dbConn2.markAllItemsAsReadForCurrentView();
            NewsReaderDetailFragment.this.refreshCurrentRssView();
        }
    }
}
