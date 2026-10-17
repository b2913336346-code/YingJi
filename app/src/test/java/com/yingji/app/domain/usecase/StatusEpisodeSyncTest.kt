package com.yingji.app.domain.usecase

import com.yingji.app.domain.model.MovieStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusEpisodeSyncTest {
    @Test
    fun enteringFinishedFillsCurrentEpisodeWhenTotalIsValid() {
        assertEquals(
            12,
            resolve(
                currentStatus = MovieStatus.WATCHING,
                newStatus = MovieStatus.FINISHED,
                currentEpisode = 8,
                totalEpisode = 12,
            ),
        )
        assertEquals(
            12,
            resolve(
                currentStatus = MovieStatus.WANT_TO_WATCH,
                newStatus = MovieStatus.FINISHED,
                currentEpisode = 0,
                totalEpisode = 12,
            ),
        )
    }

    @Test
    fun leavingFinishedKeepsCurrentEpisode() {
        assertEquals(
            12,
            resolve(
                currentStatus = MovieStatus.FINISHED,
                newStatus = MovieStatus.WATCHING,
                currentEpisode = 12,
                totalEpisode = 12,
            ),
        )
        assertEquals(
            12,
            resolve(
                currentStatus = MovieStatus.FINISHED,
                newStatus = MovieStatus.WANT_TO_WATCH,
                currentEpisode = 12,
                totalEpisode = 12,
            ),
        )
    }

    @Test
    fun selectingFinishedAgainDoesNotOverwriteManualProgress() {
        assertEquals(
            8,
            resolve(
                currentStatus = MovieStatus.FINISHED,
                newStatus = MovieStatus.FINISHED,
                currentEpisode = 8,
                totalEpisode = 12,
            ),
        )
    }

    @Test
    fun enteringFinishedWithoutValidTotalKeepsCurrentEpisode() {
        assertEquals(
            8,
            resolve(
                currentStatus = MovieStatus.WATCHING,
                newStatus = MovieStatus.FINISHED,
                currentEpisode = 8,
                totalEpisode = null,
            ),
        )
        assertEquals(
            8,
            resolve(
                currentStatus = MovieStatus.WATCHING,
                newStatus = MovieStatus.FINISHED,
                currentEpisode = 8,
                totalEpisode = 0,
            ),
        )
    }

    @Test
    fun switchingBetweenWantToWatchAndWatchingKeepsCurrentEpisode() {
        assertEquals(
            5,
            resolve(
                currentStatus = MovieStatus.WANT_TO_WATCH,
                newStatus = MovieStatus.WATCHING,
                currentEpisode = 5,
                totalEpisode = 12,
            ),
        )
        assertEquals(
            5,
            resolve(
                currentStatus = MovieStatus.WATCHING,
                newStatus = MovieStatus.WANT_TO_WATCH,
                currentEpisode = 5,
                totalEpisode = 12,
            ),
        )
    }

    @Test
    fun newFinishedMovieUsesTheSameFillRule() {
        assertEquals(
            12,
            resolve(
                currentStatus = null,
                newStatus = MovieStatus.FINISHED,
                currentEpisode = 0,
                totalEpisode = 12,
            ),
        )
    }

    private fun resolve(
        currentStatus: MovieStatus?,
        newStatus: MovieStatus,
        currentEpisode: Int,
        totalEpisode: Int?,
    ): Int = resolveCurrentEpisodeForStatusTransition(
        currentStatus = currentStatus,
        newStatus = newStatus,
        currentEpisode = currentEpisode,
        totalEpisode = totalEpisode,
    )
}
