package com.pr0gramm.app.ui.fragments

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ScrollView
import com.github.salomonbrys.kodein.instance
import com.google.android.gms.ads.MobileAds
import com.google.common.base.CharMatcher
import com.google.common.base.MoreObjects.firstNonNull
import com.google.common.base.Objects.equal
import com.google.common.base.Throwables
import com.google.gson.JsonSyntaxException
import com.pr0gramm.app.BuildConfig
import com.pr0gramm.app.R
import com.pr0gramm.app.R.id.empty
import com.pr0gramm.app.Settings
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.feed.*
import com.pr0gramm.app.feed.ContentType.SFW
import com.pr0gramm.app.feed.FeedService
import com.pr0gramm.app.services.*
import com.pr0gramm.app.services.config.Config
import com.pr0gramm.app.services.preloading.PreloadManager
import com.pr0gramm.app.services.preloading.PreloadService
import com.pr0gramm.app.ui.*
import com.pr0gramm.app.ui.ScrollHideToolbarListener.ToolbarActivity
import com.pr0gramm.app.ui.back.BackAwareFragment
import com.pr0gramm.app.ui.base.BaseFragment
import com.pr0gramm.app.ui.dialogs.ErrorDialogFragment
import com.pr0gramm.app.ui.dialogs.PopupPlayerFactory
import com.pr0gramm.app.ui.views.CustomSwipeRefreshLayout
import com.pr0gramm.app.ui.views.SearchOptionsView
import com.pr0gramm.app.ui.views.UserInfoCell
import com.pr0gramm.app.ui.views.UserInfoFoundView
import com.pr0gramm.app.util.*
import com.squareup.picasso.Picasso
import org.slf4j.LoggerFactory
import rx.Observable
import rx.android.schedulers.AndroidSchedulers.mainThread
import rx.subjects.PublishSubject
import java.net.ConnectException
import java.util.*

/**
 */
class FeedFragment : BaseFragment("FeedFragment"), FilterFragment, BackAwareFragment {
    private val settings = Settings.get()

    private val feedService: FeedService by instance()
    private val picasso: Picasso by instance()
    private val seenService: SeenService by instance()
    private val bookmarkService: BookmarkService by instance()
    private val userService: UserService by instance()
    private val singleShotService: SingleShotService by instance()
    private val inMemoryCacheService: InMemoryCacheService by instance()
    private val preloadManager: PreloadManager by instance()
    private val inboxService: InboxService by instance()
    private val recentSearchesServices: RecentSearchesServices by instance()
    private val followService: StalkService by instance()
    private val adService: AdService by instance()

    private val recyclerView: RecyclerView by bindView(R.id.list)
    private val swipeRefreshLayout: CustomSwipeRefreshLayout by bindView(R.id.refresh)
    private val noResultsView: View by bindView(empty)
    private val searchContainer: ScrollView by bindView(R.id.search_container)
    private val searchView: SearchOptionsView by bindView(R.id.search_options)

    private var quickPeekDialog: Dialog? = null

    private val adViewAdapter = AdViewAdapter()
    private val doIfAuthorizedHelper = LoginActivity.helper(this)

    private var seenIndicatorStyle = IndicatorStyle.NONE
    private var userInfoCommentsOpen: Boolean = false
    private var bookmarkable: Boolean = false
    private var autoScrollOnLoad: Long? = null
    private var autoOpenOnLoad: ItemWithComment? = null

    private lateinit var loader: FeedManager

    private val feedAdapter: FeedAdapter = FeedAdapter()
    private var scrollToolbar: Boolean = false

    private var activeUsername: String? = null

    /**
     * Initialize a new feed fragment.
     */
    init {
        setHasOptionsMenu(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // initialize auto opening
        val start = arguments.getParcelable<ItemWithComment>(ARG_FEED_START)
        if (start != null) {
            autoScrollOnLoad = start.itemId
            autoOpenOnLoad = start
        }

        this.scrollToolbar = useToolbarTopMargin()

        val bundle = savedInstanceState?.getBundle("feed")
        val previousFeed = bundle?.let { Feed.restore(it) }

        val feed = previousFeed ?: Feed(filterArgument, selectedContentType)
        loader = FeedManager(feedService, feed)
        if (previousFeed == null) {
            loader.restart(around = autoOpenOnLoad?.itemId)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        return inflater.inflate(R.layout.fragment_feed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        seenIndicatorStyle = settings.seenIndicatorStyle

        // prepare the list of items
        val columnCount = thumbnailColumns
        val layoutManager = GridLayoutManager(activity, columnCount)
        recyclerView.layoutManager = layoutManager
        recyclerView.itemAnimator = null

        initializeMergeAdapter()
        subscribeToFeedUpdates()

        // we can still swipe up if we are not at the start of the feed.
        swipeRefreshLayout.canSwipeUpPredicate = {
            !feedAdapter.feed.isAtStart
        }

        swipeRefreshLayout.setOnRefreshListener {
            if (feedAdapter.feed.isAtStart) {
                refreshFeed()
            } else {
                // do not refresh
                swipeRefreshLayout.isRefreshing = false
            }
        }

        if (useToolbarTopMargin()) {
            // use height of the toolbar to configure swipe refresh layout.
            val abHeight = AndroidUtility.getActionBarContentOffset(activity)
            val offset = AndroidUtility.getStatusBarHeight(activity)
            swipeRefreshLayout.setProgressViewOffset(false, offset, (offset + 1.5 * (abHeight - offset)).toInt())
        }

        swipeRefreshLayout.setColorSchemeResources(ThemeHelper.accentColor)

        resetToolbar()

        createRecyclerViewClickListener()
        recyclerView.addOnScrollListener(onScrollListener)

        // observe changes so we can update the menu
        followService.changes()
                .observeOn(mainThread())
                .compose(bindToLifecycle<String>())
                .filter { name -> name.equals(activeUsername, ignoreCase = true) }
                .subscribe { activity.supportInvalidateOptionsMenu() }

        // execute a search when we get a search term
        searchView.searchQuery().compose(bindToLifecycle()).subscribe { this.performSearch(it) }
        searchView.searchCanceled().compose(bindToLifecycle()).subscribe { hideSearchContainer() }
        searchView.setupAutoComplete(recentSearchesServices)

        // restore open search
        if (savedInstanceState != null && savedInstanceState.getBoolean("searchContainerVisible")) {
            showSearchContainer(false)
        }

        // close search on click into the darkened area.
        searchContainer.setOnTouchListener(DetectTapTouchListener.withConsumer { hideSearchContainer() })

        // start showing ads.
        adService.enabledForType(Config.AdType.FEED)
                .observeOn(mainThread())
                .compose(bindToLifecycle())
                .subscribe { show ->
                    adViewAdapter.showAds = show && AndroidUtility.screenIsPortrait(activity)
                    updateSpanSizeLookup()
                }
    }

    private fun subscribeToFeedUpdates() {
        loader.updates.compose(bindToLifecycleAsync()).subscribe { update ->
            logger.info("Got update {}", update)

            when (update) {
                is FeedManager.Update.NewFeed -> {
                    feedAdapter.feed = update.feed
                    updateNoResultsTextView(update.remote && update.feed.isEmpty())
                }

                is FeedManager.Update.Error ->
                    onFeedError(update.err)

                is FeedManager.Update.LoadingStarted ->
                    swipeRefreshLayout.isRefreshing = true

                is FeedManager.Update.LoadingStopped ->
                    swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (view != null) {
            outState.putBundle("feed", loader.feed.persist(0))
            outState.putLong("autoScrollOnLoad", findLastVisibleFeedItem()?.id ?: -1L)
            outState.putBoolean("searchContainerVisible", searchContainerIsVisible())
        }
    }

    private fun initialSearchViewState(): Bundle? {
        var state = arguments.getBundle(ARG_SEARCH_QUERY_STATE)
        if (state == null) {
            val tags = currentFilter.tags.orNull()
            if (tags != null) {
                state = SearchOptionsView.ofQueryTerm(tags)
            }
        }

        return state
    }

    private fun initializeMergeAdapter() {
        val merged = MergeRecyclerAdapter()
        if (useToolbarTopMargin()) {
            merged.addAdapter(SingleViewAdapter.of { context ->
                View(context).apply {
                    val height = AndroidUtility.getActionBarContentOffset(context)
                    layoutParams = ViewGroup.LayoutParams(1, height)
                }
            })
        }

        merged.addAdapter(adViewAdapter)
        merged.addAdapter(feedAdapter)

        recyclerView.adapter = merged

        updateSpanSizeLookup()

        if (isNormalMode) {
            queryUserInfo().take(1)
                    .compose(bindToLifecycleAsync())
                    .subscribe({ presentUserInfo(it) }, {})
        }
    }

    private fun useToolbarTopMargin(): Boolean {
        return isNormalMode
    }

    private val isNormalMode: Boolean get() {
        return arguments?.getBoolean(ARG_NORMAL_MODE, true) ?: true
    }

    private fun presentUserInfo(value: EnhancedUserInfo) {
        if (currentFilter.tags.isPresent) {
            presentUserUploadsHint(value.info)
        } else {
            presentUserInfoCell(value)
        }
    }

    private fun presentUserInfoCell(info: EnhancedUserInfo) {
        val messages = UserCommentsAdapter(activity)
        val comments = info.comments

        if (userInfoCommentsOpen) {
            messages.setComments(info.info.user, comments)
        }

        val view = UserInfoCell(activity, info.info, doIfAuthorizedHelper)

        view.userActionListener = object : UserInfoCell.UserActionListener {
            override fun onWriteMessageClicked(userId: Int, name: String) {
                startActivity(WriteMessageActivity.intent(activity, userId.toLong(), name))
            }

            override fun onUserFavoritesClicked(name: String) {
                val filter = currentFilter.basic().withLikes(name)
                if (filter != currentFilter) {
                    (activity as MainActionHandler).onFeedFilterSelected(filter)
                }

                showUserInfoComments(listOf())
            }

            override fun onShowUploadsClicked(id: Int, name: String) {
                val filter = currentFilter.basic().withFeedType(FeedType.NEW).withUser(name)
                if (filter != currentFilter) {
                    (activity as MainActionHandler).onFeedFilterSelected(filter)
                }

                showUserInfoComments(listOf())
            }

            override fun onShowCommentsClicked() {
                showUserInfoComments(if (messages.itemCount == 0) comments else listOf())
            }

            private fun showUserInfoComments(comments: List<Api.UserComments.UserComment>) {
                userInfoCommentsOpen = comments.isNotEmpty()
                messages.setComments(info.info.user, comments)
                updateSpanSizeLookup()
            }
        }

        view.writeMessageEnabled = !isSelfInfo(info.info)
        view.showCommentsEnabled = !comments.isEmpty()

        appendUserInfoAdapters(
                SingleViewAdapter.ofView(view),
                messages,
                SingleViewAdapter.ofLayout(R.layout.user_info_footer))

        // we are showing a user.
        activeUsername = info.info.user.name
        activity.supportInvalidateOptionsMenu()
    }

    private fun presentUserUploadsHint(info: Api.Info) {
        if (isSelfInfo(info))
            return

        val view = UserInfoFoundView(activity, info)
        view.uploadsClickedListener = { _, name ->
            val newFilter = currentFilter.basic()
                    .withFeedType(FeedType.NEW).withUser(name)

            (activity as MainActionHandler).onFeedFilterSelected(newFilter)
        }

        appendUserInfoAdapters(SingleViewAdapter.ofView(view))
    }

    private fun appendUserInfoAdapters(vararg adapters: RecyclerView.Adapter<*>) {
        if (adapters.isEmpty()) {
            return
        }

        val mainAdapter = mainAdapter
        if (mainAdapter != null) {
            var offset = 0
            val subAdapters = mainAdapter.getAdapters()
            for (idx in subAdapters.indices) {
                offset = idx

                val subAdapter = subAdapters[idx]
                if (subAdapter is FeedAdapter || subAdapter is AdViewAdapter) {
                    break
                }
            }

            for (idx in adapters.indices) {
                mainAdapter.addAdapter(offset + idx, adapters[idx])
            }

            updateSpanSizeLookup()
        }
    }

    private fun queryUserInfo(): Observable<EnhancedUserInfo> {
        val filter = filterArgument

        val queryString = filter.username.or(filter.tags).or(filter.likes).orNull()

        if (queryString != null && queryString.matches("[A-Za-z0-9]{2,}".toRegex())) {
            val contentTypes = selectedContentType
            val cached = inMemoryCacheService.getUserInfo(contentTypes, queryString)

            if (cached != null) {
                return Observable.just(cached)
            }

            val first = userService
                    .info(queryString, selectedContentType)
                    .doOnNext { info -> followService.markAsFollowing(info.user.name, info.following()) }
                    .onErrorResumeEmpty()

            val second = inboxService
                    .getUserComments(queryString, contentTypes)
                    .map { it.comments }
                    .onErrorReturn { listOf() }

            return Observable.zip(first, second, ::EnhancedUserInfo)
                    .doOnNext { info -> inMemoryCacheService.cacheUserInfo(contentTypes, info) }

        } else {
            return Observable.empty()
        }
    }

    internal fun updateSpanSizeLookup() {
        val mainAdapter = mainAdapter
        val layoutManager = recyclerViewLayoutManager
        if (mainAdapter != null && layoutManager != null) {
            val adapters = mainAdapter.getAdapters()

            val itemCount = adapters
                    .takeWhile { it !is FeedAdapter }
                    .sumBy { it.itemCount }

            // skip items!
            val columnCount = layoutManager.spanCount
            layoutManager.spanSizeLookup = NMatchParentSpanSizeLookup(itemCount, columnCount)
        }
    }

    private val mainAdapter: MergeRecyclerAdapter?
        get() = recyclerView.adapter as? MergeRecyclerAdapter

    override fun onDestroyView() {
        recyclerView.removeOnScrollListener(onScrollListener)
        adViewAdapter.destroy()

        super.onDestroyView()
    }

    private fun resetToolbar() {
        val activity = activity
        if (activity is ToolbarActivity) {
            activity.scrollHideToolbarListener.reset()
        }
    }

    private fun hideToolbar() {
        val activity = activity
        if (activity is ToolbarActivity) {
            activity.scrollHideToolbarListener.hide()
        }
    }

    private fun onBookmarkableStateChanged(bookmarkable: Boolean) {
        this.bookmarkable = bookmarkable
        activity.supportInvalidateOptionsMenu()
    }

    private val filterArgument: FeedFilter
        get() = arguments.getParcelable<FeedFilter>(ARG_FEED_FILTER)

    private val selectedContentType: EnumSet<ContentType> get() {
        if (!userService.isAuthorized)
            return EnumSet.of(SFW)

        return settings.contentType
    }

    override fun onResume() {
        super.onResume()

        Track.screen("Feed")

        // check if we should show the pin button or not.
        if (settings.showPinButton) {
            bookmarkService.isBookmarkable(currentFilter)
                    .toObservable()
                    .compose(bindToLifecycleAsync<Boolean>())
                    .subscribe({ onBookmarkableStateChanged(it) }, {})
        }

        recheckContentTypes()

        // set new indicator style
        if (seenIndicatorStyle !== settings.seenIndicatorStyle) {
            seenIndicatorStyle = settings.seenIndicatorStyle
            feedAdapter.notifyDataSetChanged()
        }

        // Observe all preloaded items to get them into the cache and to show the
        // correct state in the ui once they are loaded
        preloadManager.all()
                .compose(bindToLifecycleAsync())
                .doOnEach { feedAdapter.notifyDataSetChanged() }
                .subscribe({}, {})
    }

    private fun recheckContentTypes() {
        // check if content type has changed, and reload if necessary
        val feedFilter = loader.feed.filter
        val newContentType = selectedContentType
        if (loader.feed.contentType != newContentType) {
            autoScrollOnLoad = autoOpenOnLoad?.itemId ?: findLastVisibleFeedItem(newContentType)?.id

            // set a new adapter if we have a new content type
            loader.reset(Feed(feedFilter, newContentType))
            loader.restart(around = autoScrollOnLoad)

            activity.supportInvalidateOptionsMenu()
        }
    }

    /**
     * Finds the first item in the proxy, that is visible and of one of the given content type.

     * @param contentType The target-content type.
     */
    private fun findLastVisibleFeedItem(contentType: Set<ContentType> = ContentType.AllSet): FeedItem? {
        val items = feedAdapter.feed

        recyclerViewLayoutManager?.let { layoutManager ->
            val adapter = recyclerView.adapter as MergeRecyclerAdapter
            val offset = firstNonNull(adapter.getOffset(feedAdapter), 0)

            // if the first row is visible, skip this stuff.
            if (layoutManager.findFirstCompletelyVisibleItemPosition() == 0)
                return null

            val idx = layoutManager.findLastCompletelyVisibleItemPosition() - offset
            if (idx != RecyclerView.NO_POSITION && idx > 0 && idx < items.size) {
                return items.subList(0, idx).asReversed()
                        .firstOrNull { contentType.contains(it.contentType) }
            }
        }

        return null
    }

    /**
     * Depending on whether the screen is landscape or portrait, and how large
     * the screen is, we show a different number of items per row.
     */
    private val thumbnailColumns: Int get() {
        val config = resources.configuration
        val portrait = config.screenWidthDp < config.screenHeightDp

        val screenWidth = config.screenWidthDp
        return Math.min((screenWidth / 120.0 + 0.5).toInt(), if (portrait) 5 else 7)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_feed, menu)

        // hide search item, if we are not searchable
        menu.findItem(R.id.action_search)?.let { item ->
            item.isVisible = currentFilter.feedType.searchable
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        if (activity == null)
            return

        val filter = currentFilter
        val feedType = filter.feedType

        menu.findItem(R.id.action_refresh)
                ?.isVisible = settings.showRefreshButton

        menu.findItem(R.id.action_pin)
                ?.isVisible = bookmarkable

        menu.findItem(R.id.action_preload)
                ?.isVisible = feedType.preloadable && !AndroidUtility.isOnMobile(activity)

        menu.findItem(R.id.action_ad_info)
                ?.isVisible = BuildConfig.DEBUG

        menu.findItem(R.id.action_feedtype)?.let { item ->
            item.isVisible = !filter.isBasic && EnumSet.of(FeedType.PROMOTED, FeedType.NEW, FeedType.PREMIUM).contains(feedType)

            item.setTitle(if (switchFeedTypeTarget(filter) === FeedType.PROMOTED)
                R.string.action_switch_to_top else R.string.action_switch_to_new)
        }

        menu.findItem(R.id.action_change_content_type)?.let { item ->
            if (userService.isAuthorized) {
                val icon = ContentTypeDrawable(activity, selectedContentType)
                icon.textSize = resources.getDimensionPixelSize(
                        R.dimen.feed_content_type_action_icon_text_size).toFloat()

                item.icon = icon
                item.isVisible = true

                updateContentTypeItems(menu)

            } else {
                item.isVisible = false
            }
        }


        val follow = menu.findItem(R.id.action_follow)
        val unfollow = menu.findItem(R.id.action_unfollow)
        val bookmark = menu.findItem(R.id.action_pin)
        if (follow != null && unfollow != null && bookmark != null) {
            // go to default state.
            follow.isVisible = false
            unfollow.isVisible = false

            activeUsername?.let { activeUsername ->
                if (userService.isPremiumUser) {
                    val following = followService.isFollowing(activeUsername)
                    follow.isVisible = !following
                    unfollow.isVisible = following
                }

                // never bookmark a user
                bookmark.isVisible = false
            }
        }
    }

    private fun switchFeedTypeTarget(filter: FeedFilter): FeedType {
        return if (filter.feedType !== FeedType.PROMOTED) FeedType.PROMOTED else FeedType.NEW
    }

    private fun updateContentTypeItems(menu: Menu) {
        // only one content type selected?
        val withoutImplicits = settings.contentType.withoutImplicit()
        val single = withoutImplicits.size == 1

        val types = mapOf(
                R.id.action_content_type_sfw to settings.contentTypeSfw,
                R.id.action_content_type_nsfw to settings.contentTypeNsfw,
                R.id.action_content_type_nsfl to settings.contentTypeNsfl)

        for ((key, value) in types) {
            menu.findItem(key)?.let { item ->
                item.isChecked = value
                item.isEnabled = !single || !value
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val contentTypes = mapOf(
                R.id.action_content_type_sfw to "pref_feed_type_sfw",
                R.id.action_content_type_nsfw to "pref_feed_type_nsfw",
                R.id.action_content_type_nsfl to "pref_feed_type_nsfl")

        if (contentTypes.containsKey(item.itemId)) {
            val newState = !item.isChecked
            settings.edit {
                putBoolean(contentTypes[item.itemId], newState)
            }

            // this applies the new content types and refreshes the menu.
            recheckContentTypes()
            return true
        }

        return OptionMenuHelper.dispatch(this, item) || super.onOptionsItemSelected(item)
    }

    @OnOptionsItemSelected(R.id.action_feedtype)
    fun switchFeedType() {
        var filter = currentFilter
        filter = filter.withFeedType(switchFeedTypeTarget(filter))
        (activity as MainActionHandler).onFeedFilterSelected(filter, initialSearchViewState())
    }

    @OnOptionsItemSelected(R.id.action_refresh)
    fun refreshFeed() {
        resetToolbar()
        loader.reset()
        loader.restart()
    }

    @OnOptionsItemSelected(R.id.action_pin)
    fun pinCurrentFeedFilter() {
        // not bookmarkable anymore.
        onBookmarkableStateChanged(false)

        val filter = currentFilter
        val title = FeedFilterFormatter.format(context, filter).singleline
        (activity as MainActionHandler).pinFeedFilter(filter, title)
    }

    @OnOptionsItemSelected(R.id.action_preload)
    fun preloadCurrentFeed() {
        if (AndroidUtility.isOnMobile(activity)) {
            showDialog(activity) {
                content(R.string.preload_not_on_mobile)
                positive()
            }

            return
        }

        val intent = PreloadService.newIntent(activity, feedAdapter.feed)
        activity.startService(intent)

        Track.preloadCurrentFeed(feedAdapter.feed.size)

        if (singleShotService.isFirstTime("preload_info_hint")) {
            DialogBuilder.start(activity)
                    .content(R.string.preload_info_hint)
                    .positive()
                    .show()
        }
    }

    @OnOptionsItemSelected(R.id.action_follow)
    fun onFollowClicked() {
        activeUsername?.let { name ->
            followService.follow(name)
                    .subscribeOn(BackgroundScheduler.instance())
                    .subscribe({}, {})
        }
    }

    @OnOptionsItemSelected(R.id.action_unfollow)
    fun onUnfollowClicked() {
        activeUsername?.let { name ->
            followService.unfollow(name)
                    .subscribeOn(BackgroundScheduler.instance())
                    .subscribe({}, {})
        }
    }

    @OnOptionsItemSelected(R.id.action_ad_info)
    fun onOpenAdInfoClicked() {
        MobileAds.openDebugMenu(context, getString(R.string.banner_ad_unit_id))
    }

    fun performSearch(query: SearchOptionsView.SearchQuery) {
        hideSearchContainer()

        val current = currentFilter
        var filter = current.withTagsNoReset(query.combined)

        // do nothing, if the filter did not change
        if (equal(current, filter))
            return

        var startAt: ItemWithComment? = null
        if (query.combined.trim().matches("[1-9][0-9]{5,}|id:[0-9]+".toRegex())) {
            filter = filter.withTags("")
            startAt = ItemWithComment(java.lang.Long.parseLong(
                    CharMatcher.digit().retainFrom(query.combined)), null)
        }

        val searchQueryState = searchView.currentState()
        (activity as MainActionHandler).onFeedFilterSelected(filter, searchQueryState, startAt)

        // store the term for later
        if (query.queryTerm.isNotBlank()) {
            recentSearchesServices.storeTerm(query.queryTerm)
        }

        Track.search(query.combined)
    }

    private fun onItemClicked(idx: Int, commentId: Long? = null, preview: ImageView? = null) {
        // reset auto open.
        autoOpenOnLoad = null

        val feed = feedAdapter.feed
        if (idx < 0 || idx >= feed.size)
            return

        try {
            val fragment = PostPagerFragment.newInstance(feed, idx, commentId)

            if (preview != null) {
                // pass pixels info to target fragment.
                val image = preview.drawable
                val item = feed[idx]
                fragment.setPreviewInfo(PreviewInfo.of(context, item, image))
            }

            activity.supportFragmentManager.beginTransaction()
                    .setAllowOptimization(false)
                    .replace(R.id.content, fragment)
                    .addToBackStack("Post" + idx)
                    .commit()

        } catch (error: Exception) {
            logger.warn("Error while showing post", error)
        }

    }

    /**
     * Gets the current filter from this feed.

     * @return The filter this feed uses.
     */
    override val currentFilter: FeedFilter
        get() = loader.feed.filter

    internal fun isSeen(item: FeedItem): Boolean {
        return seenService.isSeen(item)
    }

    private fun createRecyclerViewClickListener() {
        val listener = RecyclerItemClickListener(activity, recyclerView)

        listener.itemClicked()
                .map { extractFeedItemHolder(it) }
                .filter { it != null }
                .compose(bindToLifecycle<FeedItemViewHolder>())
                .subscribe { holder -> onItemClicked(holder.index, preview = holder.image) }

        listener.itemLongClicked()
                .map { extractFeedItemHolder(it) }
                .filter { it != null }
                .compose(bindToLifecycle<FeedItemViewHolder>())
                .subscribe { holder -> holder.item?.let { openQuickPeekDialog(it) } }

        listener.itemLongClickEnded()
                .compose(bindToLifecycle())
                .subscribe { dismissQuickPeekDialog() }

        settings.changes()
                .startWith("")
                .compose(bindToLifecycle())
                .subscribe { listener.enableLongClick(settings.enableQuickPeek) }
    }

    private fun openQuickPeekDialog(item: FeedItem) {
        dismissQuickPeekDialog()

        // check that the activity is not zero. Might happen, as this method might
        // get called shortly after detaching the activity - which sucks. thanks android.
        val activity = activity
        if (activity != null) {
            quickPeekDialog = PopupPlayerFactory.newInstance(activity, item)
            swipeRefreshLayout.isEnabled = false
            Track.quickPeek()
        }
    }

    private fun dismissQuickPeekDialog() {
        swipeRefreshLayout.isEnabled = true

        quickPeekDialog?.dismiss()
        quickPeekDialog = null
    }

    private inner class FeedAdapter : RecyclerView.Adapter<FeedItemViewHolder>() {
        val usersFavorites: Boolean = false

        val userFavorites = cached<Boolean> {
            feed.filter.likes
                    .map { name -> name.equals(userService.name.orNull(), ignoreCase = true) }
                    .or(false)
        }

        init {
            setHasStableIds(true)
        }

        var feed: Feed by observeChangeEx(Feed()) { old, new ->
            userFavorites.invalidate()

            val oldIds = old.map { new.feedTypeId(it) }
            val newIds = new.map { new.feedTypeId(it) }

            if (oldIds == newIds) {
                logger.info("No change in feed items.")
                return@observeChangeEx
            }

            logger.info("Feed before update: {} items, oldest={}, newest={}",
                    old.size, old.oldest?.id, old.newest?.id)

            logger.info("Feed after update: {} items, oldest={}, newest={}",
                    new.size, new.oldest?.id, new.newest?.id)

            notifyDataSetChanged()

            // load meta data for the items.
            (newIds - oldIds).min()?.let { newestItemId ->
                refreshRepostInfos(newestItemId, new.filter)
            }

            performAutoOpen()
        }

        @SuppressLint("InflateParams")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedItemViewHolder {
            val inflater = LayoutInflater.from(activity)
            val view = inflater.inflate(R.layout.feed_item_view, null)
            return FeedItemViewHolder(view)
        }

        override fun onBindViewHolder(holder: FeedItemViewHolder,
                                      @SuppressLint("RecyclerView") position: Int) {

            val item = feed[position]

            val imageUri = UriHelper.of(context).thumbnail(item)
            picasso.load(imageUri)
                    .config(Bitmap.Config.RGB_565)
                    .placeholder(ColorDrawable(0xff333333.toInt()))
                    .into(holder.image)

            holder.itemView.tag = holder
            holder.index = position
            holder.item = item

            // show preload-badge
            holder.setIsPreloaded(preloadManager.exists(item.id()))

            // check if this item was already seen.
            if (inMemoryCacheService.isRepost(item.id())) {
                holder.setIsRepost()

            } else if (seenIndicatorStyle === IndicatorStyle.ICON && !usersFavorites && isSeen(item)) {
                holder.setIsSeen()

            } else {
                holder.clear()
            }
        }

        override fun getItemCount(): Int {
            return feed.size
        }

        override fun getItemId(position: Int): Long {
            return feed[position].id
        }
    }

    internal fun showWrongContentTypeInfo() {
        val context = activity
        if (context != null) {
            showDialog(context) {
                content(R.string.hint_wrong_content_type)
                positive()
            }
        }
    }

    internal fun refreshRepostInfos(id: Long, filter: FeedFilter) {
        if (filter.feedType !== FeedType.NEW && filter.feedType !== FeedType.PROMOTED)
            return

        // check if it is possible to get repost info.
        val queryTooLong = filter.tags.or("").split("\\s+".toRegex())
                .dropLastWhile(String::isEmpty).size >= 5

        if (queryTooLong)
            return

        val queryTerm = filter.tags.map { tags -> tags + " repost" }.or("repost")
        val query = FeedService.FeedQuery(
                filter.withTags(queryTerm),
                selectedContentType, null, id, null)

        refreshRepostsCache(feedService, inMemoryCacheService, query)
                .observeOn(mainThread())
                .compose(bindToLifecycle())
                .subscribe({ feedAdapter.notifyDataSetChanged() }, {})
    }

    internal fun onFeedError(error: Throwable) {
        logger.error("Error loading the feed", error)

        if (autoOpenOnLoad != null) {
            ErrorDialogFragment.showErrorString(fragmentManager,
                    getString(R.string.could_not_load_feed_nsfw))

        } else if (error is JsonSyntaxException) {
            // show a special error
            ErrorDialogFragment.showErrorString(fragmentManager,
                    getString(R.string.could_not_load_feed_json))

        } else if (Throwables.getRootCause(error) is ConnectException && settings.useHttps) {
            ErrorDialogFragment.showErrorString(fragmentManager,
                    getString(R.string.could_not_load_feed_https))

        } else {
            ErrorDialogFragment.defaultOnError().call(error)
        }
    }

    private fun updateNoResultsTextView(feedIsEmpty: Boolean) {
        noResultsView.visible = feedIsEmpty
    }


    @OnOptionsItemSelected(R.id.action_search)
    fun resetAndShowSearchContainer() {
        searchView.applyState(initialSearchViewState())
        showSearchContainer(true)
    }

    private fun showSearchContainer(animated: Boolean) {
        if (searchContainerIsVisible())
            return

        val view = view ?: return

        view.post { this.hideToolbar() }

        // prepare search view
        val typeName = FeedFilterFormatter.feedTypeToString(context, currentFilter.withTagsNoReset("dummy"))
        searchView.queryHint = getString(R.string.action_search, typeName)
        searchView.setPadding(0, AndroidUtility.getStatusBarHeight(context), 0, 0)

        searchContainer.visibility = View.VISIBLE

        if (animated) {
            searchContainer.alpha = 0f

            searchContainer.animate()
                    .setListener(endAction { searchView.requestSearchFocus() })
                    .alpha(1f)

            searchView.translationY = (-(0.1 * view.height).toInt()).toFloat()

            searchView.animate()
                    .setInterpolator(DecelerateInterpolator())
                    .translationY(0f)
        } else {
            searchContainer.animate().cancel()
            searchContainer.alpha = 1f

            searchView.animate().cancel()
            searchView.translationY = 0f
        }
    }

    override fun onBackButton(): Boolean {
        if (searchContainerIsVisible()) {
            hideSearchContainer()
            return true
        }

        return false
    }

    fun searchContainerIsVisible(): Boolean {
        return view != null && searchContainer.visible
    }

    internal fun hideSearchContainer() {
        if (!searchContainerIsVisible())
            return

        val container = searchContainer
        container.animate()
                .setListener(endAction { container.visible = false })
                .alpha(0f)

        val height = view?.height ?: 0
        searchView.animate().translationY((-(0.1 * height).toInt()).toFloat())

        resetToolbar()

        AndroidUtility.hideSoftKeyboard(searchView)
    }

    internal fun performAutoOpen() {
        val feed = feedAdapter.feed

        val autoScroll = autoScrollOnLoad
        if (autoScroll != null) {
            findItemIndexById(autoScroll)?.let { idx ->
                // over scroll a bit
                val scrollTo = Math.max(idx + thumbnailColumns, 0)
                recyclerView.scrollToPosition(scrollTo)
            }
        }

        val autoLoad = autoOpenOnLoad
        if (autoLoad != null) {
            feed.indexById(autoLoad.itemId)?.let { idx ->
                onItemClicked(idx, autoLoad.commentId)
            }
        }

        autoScrollOnLoad = null
    }

    /**
     * Returns the item id of the index in the recycler views adapter.
     */
    private fun findItemIndexById(id: Long): Int? {
        val offset = (recyclerView.adapter as MergeRecyclerAdapter).getOffset(feedAdapter) ?: 0

        val index = feedAdapter.feed.indexOfFirst { it.id == id }
        if (index == -1)
            return null

        return index + offset
    }

    private val recyclerViewLayoutManager: GridLayoutManager?
        get() = recyclerView.layoutManager as? GridLayoutManager

    private fun isSelfInfo(info: Api.Info): Boolean {
        return info.user.name.equals(userService.name.orNull(), ignoreCase = true)
    }

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
            if (scrollToolbar && activity is ToolbarActivity) {
                val activity = activity as ToolbarActivity
                activity.scrollHideToolbarListener.onScrolled(dy)
            }

            recyclerViewLayoutManager?.let { layoutManager ->
                if (loader.isLoading)
                    return@let

                val feed = feedAdapter.feed
                val totalItemCount = layoutManager.itemCount

                if (dy > 0 && !feed.isAtEnd) {
                    val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                    if (totalItemCount > 12 && lastVisibleItem >= totalItemCount - 12) {
                        logger.info("Request next page now")
                        loader.next()
                    }
                }

                if (dy < 0 && !feed.isAtStart) {
                    val firstVisibleItem = layoutManager.findFirstVisibleItemPosition()
                    if (totalItemCount > 12 && firstVisibleItem < 12) {
                        logger.info("Request previous page now")
                        loader.previous()
                    }
                }
            }
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                if (activity is ToolbarActivity) {
                    val y = ScrollHideToolbarListener.estimateRecyclerViewScrollY(recyclerView).or(Integer.MAX_VALUE)

                    val activity = activity as ToolbarActivity
                    activity.scrollHideToolbarListener.onScrollFinished(y)
                }
            }
        }
    }

    companion object {
        internal val logger = LoggerFactory.getLogger("FeedFragment")

        private const val ARG_FEED_FILTER = "FeedFragment.filter"
        private const val ARG_FEED_START = "FeedFragment.start.id"
        private const val ARG_NORMAL_MODE = "FeedFragment.simpleMode"
        private const val ARG_SEARCH_QUERY_STATE = "FeedFragment.searchQueryState"

        /**
         * Creates a new [FeedFragment] for the given feed type.

         * @param feedFilter A query to use for getting data
         * *
         * @return The type new fragment that can be shown now.
         */
        fun newInstance(feedFilter: FeedFilter,
                        start: ItemWithComment?,
                        searchQueryState: Bundle?): FeedFragment {

            val arguments = newArguments(feedFilter, true, start, searchQueryState)

            val fragment = FeedFragment()
            fragment.arguments = arguments
            return fragment
        }

        fun newArguments(feedFilter: FeedFilter, normalMode: Boolean,
                         start: ItemWithComment?,
                         searchQueryState: Bundle?): Bundle {

            val arguments = Bundle()
            arguments.putParcelable(ARG_FEED_FILTER, feedFilter)
            arguments.putParcelable(ARG_FEED_START, start)
            arguments.putBoolean(ARG_NORMAL_MODE, normalMode)
            arguments.putBundle(ARG_SEARCH_QUERY_STATE, searchQueryState)
            return arguments
        }

        private fun extractFeedItemHolder(view: View): FeedItemViewHolder? {
            return view.tag as? FeedItemViewHolder?
        }

        private fun refreshRepostsCache(
                feedService: FeedService, cacheService: InMemoryCacheService, query: FeedService.FeedQuery): Observable<List<Long>> {

            val subject = PublishSubject.create<List<Long>>()

            // refresh happens completely in background to let the query run even if the
            // fragments lifecycle is already destroyed.
            feedService.getFeedItems(query)
                    .subscribeOn(BackgroundScheduler.instance())
                    .doAfterTerminate { subject.onCompleted() }
                    .subscribe({ items ->
                        if (items.items.size > 0) {
                            val ids = items.items.map { it.id }
                            cacheService.cacheReposts(ids)
                            subject.onNext(ids)
                            subject.onCompleted()
                        }
                    }, {})

            return subject
        }
    }
}