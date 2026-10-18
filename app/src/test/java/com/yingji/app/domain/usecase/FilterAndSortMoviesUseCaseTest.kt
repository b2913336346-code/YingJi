package com.yingji.app.domain.usecase

import com.yingji.app.data.entity.Movie
import com.yingji.app.data.entity.MovieCategory
import com.yingji.app.data.entity.MovieTag
import com.yingji.app.data.entity.WatchHistory
import com.yingji.app.domain.model.MovieSortOption
import com.yingji.app.domain.model.MovieStatus
import com.yingji.app.domain.model.WatchTimeField
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterAndSortMoviesUseCaseTest {
    private val useCase = FilterAndSortMoviesUseCase()
    private val movies = listOf(
        movie(1L, "碧蓝之海", MovieStatus.FINISHED, 100L),
        movie(2L, "碧蓝航线", MovieStatus.FINISHED, 200L),
        movie(3L, "其他作品", MovieStatus.WATCHING, 300L),
        movie(4L, "无记录作品", MovieStatus.FINISHED, 400L),
    )
    private val histories = listOf(
        history(1L, start = 1_000L, end = 2_000L),
        history(2L, start = 3_000L, end = 4_000L),
        history(3L, start = 1_500L, end = null),
    )
    private val categories = listOf(
        MovieCategory(movieId = 1L, categoryId = 10L),
        MovieCategory(movieId = 2L, categoryId = 10L),
        MovieCategory(movieId = 3L, categoryId = 10L),
    )
    private val tags = listOf(
        MovieTag(movieId = 1L, tagId = 20L),
        MovieTag(movieId = 2L, tagId = 21L),
        MovieTag(movieId = 3L, tagId = 20L),
    )

    @Test
    fun combinesKeywordClassificationStatusAndEndTimeRange() {
        val result = useCase(
            movies = movies,
            histories = histories,
            movieCategories = categories,
            movieTags = tags,
            criteria = MovieSearchCriteria(
                query = "bilan",
                categoryId = 10L,
                tagId = 20L,
                status = MovieStatus.FINISHED,
                timeField = WatchTimeField.END_TIME,
                fromTime = 1_900L,
                toTime = 2_100L,
            ),
        )

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun missingEndTimeAndMissingHistoryDoNotMatchEndTimeRange() {
        val result = useCase(
            movies = movies,
            histories = histories,
            movieCategories = categories,
            movieTags = tags,
            criteria = MovieSearchCriteria(
                timeField = WatchTimeField.END_TIME,
                fromTime = 0L,
                toTime = 10_000L,
            ),
        )

        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    @Test
    fun startTimeRangeIncludesUnfinishedHistoryWhenItsStartMatches() {
        val result = useCase(
            movies = movies,
            histories = histories,
            movieCategories = categories,
            movieTags = tags,
            criteria = MovieSearchCriteria(
                status = MovieStatus.WATCHING,
                timeField = WatchTimeField.START_TIME,
                fromTime = 1_400L,
                toTime = 1_600L,
            ),
        )

        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun watchTimeSortingKeepsMoviesWithoutCorrespondingTimeLast() {
        val byStart = useCase(
            movies, histories, categories, tags,
            MovieSearchCriteria(sortOption = MovieSortOption.WATCH_START_DESC),
        )
        val byEnd = useCase(
            movies, histories, categories, tags,
            MovieSearchCriteria(sortOption = MovieSortOption.WATCH_END_DESC),
        )

        assertEquals(listOf(2L, 3L, 1L, 4L), byStart.map { it.id })
        assertEquals(listOf(2L, 1L, 4L, 3L), byEnd.map { it.id })
    }

    @Test
    fun activeTimeFieldRequiresRecordedTimeEvenWithoutDateBounds() {
        assertEquals(listOf(3L, 2L, 1L), evaluate(MovieSearchCriteria(
            timeField = WatchTimeField.START_TIME,
        )))
        assertEquals(listOf(2L, 1L), evaluate(MovieSearchCriteria(
            timeField = WatchTimeField.END_TIME,
        )))
    }

    @Test
    fun timeBoundsAreInclusiveAndCanBeOneSided() {
        assertEquals(listOf(1L), evaluate(MovieSearchCriteria(
            timeField = WatchTimeField.END_TIME, fromTime = 2_000L, toTime = 2_000L,
        )))
        assertEquals(listOf(2L), evaluate(MovieSearchCriteria(
            timeField = WatchTimeField.START_TIME, fromTime = 3_000L,
        )))
        assertEquals(listOf(3L, 1L), evaluate(MovieSearchCriteria(
            timeField = WatchTimeField.START_TIME, toTime = 1_500L,
        )))
    }

    @Test
    fun boundsMustMatchTheSameHistoryAndUnrestrictedTimeIgnoresBounds() {
        val repeated = listOf(history(1L, 100L, 200L), history(1L, 900L, 1_000L))
        assertEquals(emptyList<Long>(), useCase(movies, repeated, categories, tags,
            MovieSearchCriteria(timeField = WatchTimeField.START_TIME, fromTime = 300L, toTime = 800L),
        ).map { it.id })
        assertEquals(listOf(4L, 3L, 2L, 1L), evaluate(MovieSearchCriteria(fromTime = 99_999L)))
    }

    @Test
    fun allSortModesHaveStableTieBreakersAndEmptyResultsStayEmpty() {
        val tied = listOf(movie(1L, "a", MovieStatus.FINISHED, 100L),
            movie(2L, "A", MovieStatus.FINISHED, 100L))
        MovieSortOption.entries.forEach { option ->
            val expected = if (option == MovieSortOption.CREATED_ASC) listOf(1L, 2L) else listOf(2L, 1L)
            assertEquals(option.name, expected, useCase(tied, emptyList(), emptyList(), emptyList(),
                MovieSearchCriteria(sortOption = option)).map { it.id })
        }
        assertEquals(emptyList<Long>(), evaluate(MovieSearchCriteria(query = "no-match")))
        assertEquals(emptyList<Movie>(), useCase(emptyList(), histories, categories, tags, MovieSearchCriteria()))
    }

    private fun evaluate(criteria: MovieSearchCriteria) =
        useCase(movies, histories, categories, tags, criteria).map { it.id }

    private fun movie(id: Long, name: String, status: MovieStatus, createTime: Long) = Movie(
        id = id,
        name = name,
        type = "电影",
        status = status.value,
        createTime = createTime,
    )

    private fun history(movieId: Long, start: Long, end: Long?) = WatchHistory(
        movieId = movieId,
        startTime = start,
        endTime = end,
        duration = if (end == null) 0L else end - start,
        watchNumber = 1,
    )
}
