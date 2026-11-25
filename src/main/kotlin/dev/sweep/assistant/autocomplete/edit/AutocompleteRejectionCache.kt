package dev.sweep.assistant.autocomplete.edit

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.sweep.assistant.services.FeatureFlagService
import dev.sweep.assistant.utils.EvictingQueue

@Service(Service.Level.PROJECT)
class AutocompleteRejectionCache(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): AutocompleteRejectionCache = project.getService(AutocompleteRejectionCache::class.java)

        private const val REJECTION_THRESHOLD_FLAG = "autocomplete-rejection-cache-threshold-ms"

        // Closing characters IntelliJ inserts by itself after the user types the opening one.
        private val AUTO_CLOSED_CHARS = charArrayOf(')', ']', '}', '"', '\'', '>', '`')
    }

    private val shownCache = EvictingQueue<Pair<String, Long>>(maxSize = 20) // Cache for shown suggestions

    private val rejectionCache = EvictingQueue<Pair<String, Long>>(maxSize = 20) // Cache for rejected suggestions. The first item is the suggestion string, and the second is the timestamp

    // Cache for accepted suggestions. This is used so accepted suggestions don't get added to rejection cache
    private val acceptanceCache = EvictingQueue<Pair<String, Long>>(maxSize = 20)

    fun checkIfSuggestionShouldBeShown(suggestion: AutocompleteSuggestion): Boolean {
        // First check if any entries have timed out
        val currentTime = System.currentTimeMillis()
        val autoCompleteRejectionCacheThresholdMs =
            FeatureFlagService.getInstance(project).getNumericFeatureFlag(REJECTION_THRESHOLD_FLAG, 30_000).toLong()
        // If the suggestion was rejected
        // previous rejection is a >80% substring of the current suggestion this handles deletions
        val suggestionInPreviousRejections =
            rejectionCache.any {
                currentTime - it.second < autoCompleteRejectionCacheThresholdMs &&
                    (
                        it.first == suggestion.rejectionCacheKey() ||
                            (
                                suggestion.rejectionCacheKey().contains(it.first) &&
                                    it.first.length.toDouble() / suggestion.rejectionCacheKey().length.toDouble() > 0.8
                            )
                    )
            }
        if (suggestionInPreviousRejections) {
            return false
        }
        // also don't show if we've shown this suggestion 2x already, and it's not a ghost text
        val maxShowCount = FeatureFlagService.getInstance(project).getNumericFeatureFlag("autocomplete_rejection_cache_max_show_count", 2)
        return shownCache.count { it.first == suggestion.rejectionCacheKey() } < maxShowCount
    }

    fun tryAddingRejectionToCache(
        suggestion: AutocompleteSuggestion,
        reason: AutocompleteDisposeReason,
        typedText: String? = null,
    ) {
        // ACCEPTED - do not add to rejection cache
        // IMPORT_FIX_SHOWN - do not add to rejection cache (import suggestions are context-specific)
        // AUTOCOMPLETE_DISPOSED - esc also maps to this
        // CLEARING_PREVIOUS_AUTOCOMPLETE - soft rejection, usually means that user typed
        // ESCAPE_PRESSED - always add to rejection cache
        // EDITOR_LOST_FOCUS - soft rejection
        // CARET_POSITION_CHANGED - soft rejection
        if (reason in
            listOf(
                AutocompleteDisposeReason.ACCEPTED,
                AutocompleteDisposeReason.IMPORT_FIX_SHOWN,
            )
        ) {
            acceptanceCache.add(Pair(suggestion.rejectionCacheKey(), System.currentTimeMillis()))
            return
        }
        var shouldAddRejectionToCache = false
        if (suggestion.rejectionCacheKey() in acceptanceCache.map { it.first }) {
            return
        }

        // Sweeper fork: the user is typing the suggestion by hand - not a rejection, and it
        // shouldn't count towards the max-show limit either.
        if (reason == AutocompleteDisposeReason.CLEARING_PREVIOUS_AUTOCOMPLETE &&
            typedText != null &&
            isTypedThroughSuggestion(suggestion, typedText)
        ) {
            return
        }

        // Add if the suggestion was shown, and it's not already in the map, and it's not a ghost text
        // And it's been shown for more than 300ms
        if (suggestion.getLifespan() > 300L) {
            shownCache.add(Pair(suggestion.rejectionCacheKey(), System.currentTimeMillis()))
        }

        if (reason in
            listOf(
                // Soft rejections
                AutocompleteDisposeReason.CARET_POSITION_CHANGED,
                AutocompleteDisposeReason.CLEARING_PREVIOUS_AUTOCOMPLETE,
            )
        ) {
            if (suggestion.type == AutocompleteSuggestion.SuggestionType.JUMP_TO_EDIT) {
                shouldAddRejectionToCache = suggestion.getLifespan() > 500L
            } else {
                shouldAddRejectionToCache = suggestion.getLifespan() > 750L
            }
        } else if (reason in
            listOf(
                // Hard rejections
                AutocompleteDisposeReason.EDITOR_LOST_FOCUS,
                AutocompleteDisposeReason.AUTOCOMPLETE_DISPOSED,
            )
        ) {
            if (suggestion.type == AutocompleteSuggestion.SuggestionType.JUMP_TO_EDIT) {
                shouldAddRejectionToCache = suggestion.getLifespan() > 500L
            } else if (suggestion.type == AutocompleteSuggestion.SuggestionType.POPUP) {
                shouldAddRejectionToCache = suggestion.getLifespan() > 500L
            } else if (suggestion.type == AutocompleteSuggestion.SuggestionType.GHOST_TEXT) {
                shouldAddRejectionToCache = suggestion.getLifespan() > 1000L
            }
        } else if (reason == AutocompleteDisposeReason.ESCAPE_PRESSED) {
            shouldAddRejectionToCache = suggestion.getLifespan() > 1500L
        }
        // Add if the suggestion was shown and it's not already in the map
        if (shouldAddRejectionToCache && suggestion.rejectionCacheKey() !in rejectionCache.map { it.first }) {
            rejectionCache.add(Pair(suggestion.rejectionCacheKey(), System.currentTimeMillis()))
        }
    }

    /**
     * Sweeper fork: does [typedText] (the fragment of the edit that cleared the suggestion)
     * agree with [suggestion]?
     *
     * Ghost text already follows exact character-by-character typing, but IntelliJ's own
     * edits break the exact match: auto-closed brackets/quotes ("(" becomes "()"), and
     * newline + auto-indent. Popup (replacement) suggestions don't follow typing at all.
     * Without this, typing the suggested code by hand records it as a soft rejection and
     * blocks the follow-up suggestions that contain it for 30 s.
     *
     * Deliberately lenient: a false "agrees" only means a suggestion may be shown again,
     * which shownCache still limits; Escape remains a hard rejection.
     */
    private fun isTypedThroughSuggestion(
        suggestion: AutocompleteSuggestion,
        typedText: String,
    ): Boolean {
        // Whitespace-only edits (Enter + auto-indent) and deletions: not a rejection.
        val typed = typedText.filterNot { it.isWhitespace() }.trimEnd(*AUTO_CLOSED_CHARS)
        if (typed.isEmpty()) return true
        val content = suggestion.content.filterNot { it.isWhitespace() }
        return when (suggestion) {
            is AutocompleteSuggestion.GhostTextSuggestion -> content.startsWith(typed)
            else -> content.contains(typed)
        }
    }

    fun getNumRejectionsInLastTimespan(timespanMs: Long): Int {
        val currentTime = System.currentTimeMillis()
        return rejectionCache.count { currentTime - it.second <= timespanMs }
    }

    /**
     * Clears the rejection cache
     */
    fun clearCache() {
        rejectionCache.clear()
        shownCache.clear()
        acceptanceCache.clear()
    }

    override fun dispose() {
        clearCache()
    }
}
