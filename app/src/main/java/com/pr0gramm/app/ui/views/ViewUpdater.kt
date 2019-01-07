package com.pr0gramm.app.ui.views

import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.pr0gramm.app.Duration
import com.pr0gramm.app.Instant
import com.pr0gramm.app.R
import com.pr0gramm.app.util.MainThreadScheduler
import com.pr0gramm.app.util.attachEvents
import com.pr0gramm.app.util.updateTextView
import rx.Observable
import rx.Subscription
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

object ViewUpdater {
    private val tickerSeconds: Observable<Unit> = Observable
            .interval(1, 1, TimeUnit.SECONDS, MainThreadScheduler)
            .map { Unit }
            .share()

    private val tickerMinute: Observable<Unit> = Observable
            .interval(1, 1, TimeUnit.MINUTES, MainThreadScheduler)
            .map { Unit }
            .share()

    private fun ofView(view: View, ticker: Observable<Unit>): Observable<Unit> {
        val currentlyAttached = ViewCompat.isAttachedToWindow(view)

        return view.attachEvents()
                .startWith(currentlyAttached)
                .switchMap { attached ->
                    val selectedTicker = if (attached) ticker else Observable.empty()
                    selectedTicker.startWith(Unit)
                }
    }

    private fun ofView(view: View, instant: Instant): Observable<Unit> {
        val delta = Duration.between(Instant.now(), instant)
                .convertTo(TimeUnit.SECONDS)
                .absoluteValue

        val ticker = when {
            delta > 3600 -> Observable.empty<Unit>()
            delta > 60 -> tickerMinute
            else -> tickerSeconds
        }

        return ofView(view, ticker)
    }

    fun replaceText(view: TextView, instant: Instant, text: () -> CharSequence) {
        val previousSubscription = view.getTag(R.id.date) as? Subscription
        previousSubscription?.unsubscribe()

        val subscription = ofView(view, instant).map { text() }.subscribe(updateTextView(view))
        view.setTag(R.id.date, subscription)
    }
}