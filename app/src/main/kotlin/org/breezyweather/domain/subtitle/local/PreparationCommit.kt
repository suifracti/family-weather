package org.breezyweather.domain.subtitle.local

import kotlinx.coroutines.CancellationException

/** Cancellation and atomic derived-result commit have one ordering point. */
class PreparationCommit {
    private var cancelled = false
    @Synchronized fun cancel() { cancelled = true }
    @Synchronized fun <T> protect(write: () -> T): T {
        if (cancelled) throw CancellationException("Preparation output cancelled")
        return write()
    }
}
