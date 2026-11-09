package com.renotube.tv.data.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager

/**
 * ContentProvider for Amazon Fire TV and Android TV Global Voice Search.
 *
 * When the user presses the microphone button on the Fire TV Stick remote,
 * Fire OS queries this provider to display video results and suggestions directly
 * on the Fire TV global search screen.
 */
class RenoTubeSearchSuggestionProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.amazon.firetv.youtube.search"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/search_suggest_query")

        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_DURATION
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val query = when {
            uri.pathSegments.size > 1 && uri.pathSegments[0] == "search_suggest_query" -> uri.lastPathSegment ?: ""
            selectionArgs != null && selectionArgs.isNotEmpty() -> selectionArgs[0]
            else -> uri.getQueryParameter("q") ?: uri.lastPathSegment ?: ""
        }

        val cursor = MatrixCursor(COLUMNS)
        if (query.isBlank() || query == "search_suggest_query") {
            return cursor
        }

        try {
            val service = YouTubeServiceManager.instance().contentService ?: return cursor

            // 1. Fetch top video search results for the voice query
            val searchGroups = service.getSearch(query)
            var idCounter = 1L

            searchGroups?.flatMap { it.mediaItems ?: emptyList() }
                ?.filter { !it.videoId.isNullOrBlank() }
                ?.take(12)
                ?.forEach { item ->
                    val videoId = item.videoId ?: return@forEach
                    val title = item.title ?: "Video"
                    val author = item.author ?: ""
                    val durationMs = item.durationMs
                    val thumb = item.cardImageUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                    cursor.addRow(
                        arrayOf(
                            idCounter++,
                            title,
                            if (author.isNotBlank()) "FreeTube • $author" else "FreeTube",
                            thumb,
                            thumb,
                            "freetube://video/$videoId",
                            Intent.ACTION_VIEW,
                            "video/*",
                            if (durationMs > 0) durationMs else null
                        )
                    )
                }

            // 2. If no direct video hits or to complement suggestions, append autocomplete tags
            if (cursor.count < 3) {
                val tags = service.getSearchTags(query)
                tags?.take(5)?.forEach { tag ->
                    cursor.addRow(
                        arrayOf(
                            idCounter++,
                            tag,
                            "Search on FreeTube",
                            null,
                            null,
                            "freetube://search?q=${java.net.URLEncoder.encode(tag, "UTF-8")}",
                            Intent.ACTION_VIEW,
                            "text/plain",
                            null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SearchProvider", "Error querying voice search suggestions for '$query': ${e.message}")
        }

        return cursor
    }

    override fun getType(uri: Uri): String {
        return SearchManager.SUGGEST_MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
