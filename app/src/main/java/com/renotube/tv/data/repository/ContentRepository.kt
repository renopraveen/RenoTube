package com.renotube.tv.data.repository

import com.liskovsoft.mediaserviceinterfaces.ContentService
import com.liskovsoft.mediaserviceinterfaces.SignInService
import com.renotube.tv.data.model.SectionRowModel
import com.renotube.tv.data.model.VideoCardModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepository @Inject constructor(
    private val contentService: ContentService,
    private val signInService: SignInService
) {
    private val TAG = "ContentRepository"

    val isUserSignedIn: Boolean get() = signInService.isSigned

    private val _signedInState = MutableStateFlow(signInService.isSigned)
    val signedInState: StateFlow<Boolean> = _signedInState.asStateFlow()

    init {
        try {
            signInService.addOnAccountChange { account ->
                val signed = signInService.isSigned
                android.util.Log.i(TAG, "Account changed: ${account?.name}, signedIn=$signed - notifying subscribers")
                _signedInState.value = signed
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to register account change listener: ${e.message}")
        }
    }

    /**
     * Emits home rows progressively as they arrive from YouTube.
     * Uses YouTube's official recommendation and multi-shelf algorithm.
     * When signed in, automatically reflects the user's personal recommendations, history, and subscribed channels.
     */
    fun getHomeRowsFlow(): Flow<List<SectionRowModel>> = callbackFlow {
        val disposables = io.reactivex.disposables.CompositeDisposable()

        if (signInService.isSigned) {
            android.util.Log.i(TAG, "Signed in: loading YouTube personal recommendations and multi-shelves")

            // 1. YouTube authenticated Watch History (Jump Back In)
            val historyDisposable = contentService.getHistoryObserve()
                .subscribe(
                    { group ->
                        if (group != null) {
                            val row = SectionRowModel.fromMediaGroup(group).copy(title = "Watch History")
                            if (row.items.size >= 3) {
                                trySend(listOf(row))
                            }
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Personalized history warning: ${error.message}") }
                )
            disposables.add(historyDisposable)

            // 2. YouTube official Home Multi-Shelf Browse Rows (personal categories, channels, and recommendations)
            val homeDisposable = contentService.getHomeObserve()
                .subscribe(
                    { groups ->
                        val rows = groups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.size >= 4 }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            android.util.Log.d(TAG, "Personalized home multi-shelves emitted ${rows.size} rows")
                            trySend(rows)
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Personalized home multi-shelves warning: ${error.message}") }
                )
            disposables.add(homeDisposable)

            // 3. Subscriptions row
            val subsDisposable = contentService.getSubscriptionsObserve()
                .subscribe(
                    { group ->
                        if (group != null) {
                            val row = SectionRowModel.fromMediaGroup(group).copy(title = "From Your Subscriptions")
                            if (row.items.size >= 4) {
                                trySend(listOf(row))
                            }
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Subscriptions feed warning: ${error.message}") }
                )
            disposables.add(subsDisposable)
        } else {
            android.util.Log.i(TAG, "Guest session: loading default YouTube recommendations and multi-shelves")

            // 1. Official YouTube default Home multi-shelves
            val homeDisposable = contentService.getHomeObserve()
                .subscribe(
                    { groups ->
                        val rows = groups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.size >= 4 }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            trySend(rows)
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Default home warning: ${error.message}") }
                )
            disposables.add(homeDisposable)

            // 2. Official YouTube Trending Shelves
            val trendingDisposable = contentService.getTrendingObserve()
                .subscribe(
                    { groups ->
                        val rows = groups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.size >= 4 }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            trySend(rows)
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Trending feed warning: ${error.message}") }
                )
            disposables.add(trendingDisposable)

            // 3. Official YouTube Music Shelves
            val musicDisposable = contentService.getMusicObserve()
                .subscribe(
                    { groups ->
                        val rows = groups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.size >= 4 }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            trySend(rows)
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Music feed warning: ${error.message}") }
                )
            disposables.add(musicDisposable)

            // 4. Official YouTube Gaming Shelves
            val gamingDisposable = contentService.getGamingObserve()
                .subscribe(
                    { groups ->
                        val rows = groups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.size >= 4 }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            trySend(rows)
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Gaming feed warning: ${error.message}") }
                )
            disposables.add(gamingDisposable)

            // 5. Official YouTube News Shelves
            val newsDisposable = contentService.getNewsObserve()
                .subscribe(
                    { groups ->
                        val rows = groups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.size >= 4 }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            trySend(rows)
                        }
                    },
                    { error -> android.util.Log.w(TAG, "News feed warning: ${error.message}") }
                )
            disposables.add(newsDisposable)
        }

        awaitClose { disposables.dispose() }
    }.flowOn(Dispatchers.IO)

    /**
     * Emits trending rows.
     */
    fun getTrendingRowsFlow(): Flow<List<SectionRowModel>> = callbackFlow {
        val disposables = io.reactivex.disposables.CompositeDisposable()

        val trendingDisposable = contentService.getTrendingObserve()
            .subscribe(
                { groups ->
                    val rows = groups
                        ?.map { SectionRowModel.fromMediaGroup(it) }
                        ?.filter { it.items.isNotEmpty() }
                        ?: emptyList()
                    if (rows.isNotEmpty()) {
                        trySend(rows)
                    }
                },
                { error -> android.util.Log.w(TAG, "Trending observe error: ${error.message}") }
            )
        disposables.add(trendingDisposable)

        // Concurrently fetch high-yield trending topics to guarantee instant rich shelves
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val trendingQueries = listOf("Trending", "Trending Movies & Trailers", "Viral Videos Today", "Global Popular Songs")
            for (query in trendingQueries) {
                try {
                    val searchGroups = contentService.getSearch(query)
                    val rows = searchGroups
                        ?.map { SectionRowModel.fromMediaGroup(it) }
                        ?.filter { it.items.isNotEmpty() }
                        ?.map { it.copy(title = query) }
                        ?: emptyList()
                    if (rows.isNotEmpty()) {
                        trySend(rows)
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to load trending fallback: ${e.message}")
                }
            }
        }

        awaitClose { disposables.dispose() }
    }.flowOn(Dispatchers.IO)

    fun getMusicRowsFlow(): Flow<List<SectionRowModel>> = callbackFlow {
        val disposables = io.reactivex.disposables.CompositeDisposable()

        val disposable = contentService.getMusicObserve()
            .subscribe(
                { groups ->
                    val rows = groups
                        ?.map { SectionRowModel.fromMediaGroup(it) }
                        ?.filter { it.items.isNotEmpty() }
                        ?: emptyList()
                    if (rows.isNotEmpty()) {
                        trySend(rows)
                    }
                },
                { error -> android.util.Log.w(TAG, "Music observe error: ${error.message}") }
            )
        disposables.add(disposable)

        // Expand with rich YouTube music categories for guest sessions
        if (!signInService.isSigned) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val musicQueries = listOf(
                    "Top Music Videos 2024",
                    "Billboard Hot 100",
                    "New Music Releases",
                    "Trending Bollywood Songs",
                    "Lo-Fi Hip Hop Chill Beats",
                    "Global Dance & Pop Hits"
                )
                for (query in musicQueries) {
                    try {
                        val searchGroups = contentService.getSearch(query)
                        val rows = searchGroups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.isNotEmpty() }
                            ?.map { it.copy(title = query) }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            trySend(rows)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to load music query $query: ${e.message}")
                    }
                }
            }
        }

        awaitClose { disposables.dispose() }
    }.flowOn(Dispatchers.IO)

    fun getGamingRowsFlow(): Flow<List<SectionRowModel>> = callbackFlow {
        val disposables = io.reactivex.disposables.CompositeDisposable()

        val disposable = contentService.getGamingObserve()
            .subscribe(
                { groups ->
                    val rows = groups
                        ?.map { SectionRowModel.fromMediaGroup(it) }
                        ?.filter { it.items.isNotEmpty() }
                        ?: emptyList()
                    if (rows.isNotEmpty()) {
                        trySend(rows)
                    }
                },
                { error -> android.util.Log.w(TAG, "Gaming observe error: ${error.message}") }
            )
        disposables.add(disposable)

        // Expand with top YouTube gaming shelves for guest sessions
        if (!signInService.isSigned) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val gamingQueries = listOf(
                    "Top Live Gaming Streams",
                    "Trending Gameplay Videos",
                    "GTA VI & Open World Gaming",
                    "Minecraft Survival & Builds",
                    "Popular Esports Tournaments",
                    "Best Gaming Moments & Clips"
                )
                for (query in gamingQueries) {
                    try {
                        val searchGroups = contentService.getSearch(query)
                        val rows = searchGroups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.isNotEmpty() }
                            ?.map { it.copy(title = query) }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            trySend(rows)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to load gaming query $query: ${e.message}")
                    }
                }
            }
        }

        awaitClose { disposables.dispose() }
    }.flowOn(Dispatchers.IO)

    fun getNewsRowsFlow(): Flow<List<SectionRowModel>> = callbackFlow {
        val disposable = contentService.getNewsObserve()
            .subscribe(
                { groups ->
                    val rows = groups
                        ?.map { SectionRowModel.fromMediaGroup(it) }
                        ?.filter { it.items.isNotEmpty() }
                        ?: emptyList()
                    trySend(rows)
                },
                { error -> close(error) },
                { close() }
            )
        awaitClose { disposable.dispose() }
    }.flowOn(Dispatchers.IO)

    fun getSportsRowsFlow(): Flow<List<SectionRowModel>> = callbackFlow {
        val disposable = contentService.getSportsObserve()
            .subscribe(
                { groups ->
                    val rows = groups
                        ?.map { SectionRowModel.fromMediaGroup(it) }
                        ?.filter { it.items.isNotEmpty() }
                        ?: emptyList()
                    trySend(rows)
                },
                { error -> close(error) },
                { close() }
            )
        awaitClose { disposable.dispose() }
    }.flowOn(Dispatchers.IO)

    fun getLiveRowsFlow(): Flow<List<SectionRowModel>> = callbackFlow {
        val disposable = contentService.getLiveObserve()
            .subscribe(
                { groups ->
                    val rows = groups
                        ?.map { SectionRowModel.fromMediaGroup(it) }
                        ?.filter { it.items.isNotEmpty() }
                        ?: emptyList()
                    trySend(rows)
                },
                { error -> close(error) },
                { close() }
            )
        awaitClose { disposable.dispose() }
    }.flowOn(Dispatchers.IO)

    /**
     * Emits subscriptions feed. Uses getSubscriptionsObserve() which returns Observable<MediaGroup>.
     */
    fun getSubscriptionsFeed(): Flow<SectionRowModel> = callbackFlow {
        val disposable = contentService.getSubscriptionsObserve()
            .subscribe(
                { group ->
                    val model = SectionRowModel.fromMediaGroup(group)
                    if (model.items.isNotEmpty()) {
                        trySend(model)
                    }
                },
                { error -> close(error) },
                { close() }
            )
        awaitClose { disposable.dispose() }
    }.flowOn(Dispatchers.IO)

    fun getSubscriptionsRowsFlow(): Flow<List<SectionRowModel>> = callbackFlow {
        val disposables = io.reactivex.disposables.CompositeDisposable()

        if (signInService.isSigned) {
            android.util.Log.i(TAG, "Signed into Google Account: Loading real YouTube subscriptions & channels")

            // 1. Real YouTube Subscriptions Latest Videos
            val subsDisposable = contentService.getSubscriptionsObserve()
                .subscribe(
                    { group ->
                        if (group != null) {
                            val model = SectionRowModel.fromMediaGroup(group).copy(title = "Subscriptions: Latest")
                            if (model.items.isNotEmpty()) {
                                trySend(listOf(model))
                            }
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Subscriptions error: ${error.message}") }
                )
            disposables.add(subsDisposable)

            // 2. Subscribed channels with new content
            val newContentDisposable = contentService.getSubscribedChannelsByNewContentObserve()
                .subscribe(
                    { group ->
                        if (group != null) {
                            val model = SectionRowModel.fromMediaGroup(group).copy(title = "New from Your Channels")
                            if (model.items.isNotEmpty()) {
                                trySend(listOf(model))
                            }
                        }
                    },
                    { error -> android.util.Log.w(TAG, "New content subs error: ${error.message}") }
                )
            disposables.add(newContentDisposable)

            // 3. User's full subscribed channels list
            val channelsDisposable = contentService.getSubscribedChannelsObserve()
                .subscribe(
                    { group ->
                        if (group != null) {
                            val model = SectionRowModel.fromMediaGroup(group).copy(title = "Subscribed Channels")
                            if (model.items.isNotEmpty()) {
                                trySend(listOf(model))
                            }
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Subscribed channels error: ${error.message}") }
                )
            disposables.add(channelsDisposable)
        } else {
            // Guest session: guest subscription observe + top creator channels fallback
            val disposable = contentService.getSubscriptionsObserve()
                .subscribe(
                    { group ->
                        if (group != null) {
                            val model = SectionRowModel.fromMediaGroup(group)
                            if (model.items.isNotEmpty()) {
                                trySend(listOf(model))
                            }
                        }
                    },
                    { error -> android.util.Log.w(TAG, "Guest subscriptions error: ${error.message}") }
                )
            disposables.add(disposable)

            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val creatorQueries = listOf(
                    "Top YouTube Channels",
                    "Popular Tech Reviews",
                    "Science & Space Documentaries",
                    "Filmmaking & Cinema",
                    "World Travel & Adventure"
                )
                for (query in creatorQueries) {
                    try {
                        val searchGroups = contentService.getSearch(query)
                        val rows = searchGroups
                            ?.map { SectionRowModel.fromMediaGroup(it) }
                            ?.filter { it.items.isNotEmpty() }
                            ?.map { it.copy(title = query) }
                            ?: emptyList()
                        if (rows.isNotEmpty()) {
                            trySend(rows)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        awaitClose { disposables.dispose() }
    }.flowOn(Dispatchers.IO)

    /**
     * Emits recommended videos. Uses getRecommendedObserve() -> Observable<MediaGroup>.
     */
    fun getRecommendedFlow(): Flow<SectionRowModel> = callbackFlow {
        val disposable = contentService.getRecommendedObserve()
            .subscribe(
                { group -> trySend(SectionRowModel.fromMediaGroup(group)) },
                { error -> close(error) },
                { close() }
            )
        awaitClose { disposable.dispose() }
    }.flowOn(Dispatchers.IO)

    /**
     * Emits search tag suggestions. Uses getSearchTagsObserve().
     */
    fun getSearchSuggestionsFlow(query: String): Flow<List<String>> = callbackFlow<List<String>> {
        if (query.isBlank()) {
            trySend(emptyList())
            close()
        } else {
            val disposable = contentService.getSearchTagsObserve(query)
                .subscribe(
                    { tags -> trySend(tags ?: emptyList()) },
                    { error -> close(error) },
                    { close() }
                )
            awaitClose { disposable.dispose() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else contentService.getSearchTags(query) ?: emptyList()
    }

    /**
     * Emits search result rows. Uses getSearchObserve().
     */
    fun searchFlow(query: String): Flow<List<SectionRowModel>> = callbackFlow<List<SectionRowModel>> {
        if (query.isBlank()) {
            trySend(emptyList())
            close()
        } else {
            val disposable = contentService.getSearchObserve(query)
                .subscribe(
                    { groups -> trySend(groups.map { SectionRowModel.fromMediaGroup(it) }) },
                    { error -> close(error) },
                    { close() }
                )
            awaitClose { disposable.dispose() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun search(query: String): List<SectionRowModel> = withContext(Dispatchers.IO) {
        val groups = contentService.getSearch(query) ?: emptyList()
        groups.map { SectionRowModel.fromMediaGroup(it) }
    }

    /** History feed */
    fun getHistoryFlow(): Flow<SectionRowModel> = callbackFlow<SectionRowModel> {
        val disposable = contentService.getHistoryObserve()
            .subscribe(
                { group -> trySend(SectionRowModel.fromMediaGroup(group)) },
                { error -> close(error) },
                { close() }
            )
        awaitClose { disposable.dispose() }
    }.flowOn(Dispatchers.IO)

    /** Channel page rows by channel ID */
    fun getChannelFlow(channelId: String): Flow<List<SectionRowModel>> = callbackFlow<List<SectionRowModel>> {
        val disposable = contentService.getChannelObserve(channelId)
            .subscribe(
                { groups -> trySend(groups.map { SectionRowModel.fromMediaGroup(it) }) },
                { error -> close(error) },
                { close() }
            )
        awaitClose { disposable.dispose() }
    }.flowOn(Dispatchers.IO)

    suspend fun continueSection(nextPageKey: String?): List<VideoCardModel> =
        withContext(Dispatchers.IO) {
            emptyList()
        }
}
