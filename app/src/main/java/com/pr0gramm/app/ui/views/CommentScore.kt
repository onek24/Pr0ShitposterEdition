package com.pr0gramm.app.ui.views

import kotlin.math.abs

/**
 * A comment score.
 */
data class CommentScore(val up: Int, val down: Int) {
    val score: Int get() = up - down
    val cringe: Int get() = up + abs(down)
}
