package com.globenews.domain.usecase

import com.globenews.domain.model.GlobeView
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.repository.NewsRepository
import javax.inject.Inject

class RefreshStoriesUseCase @Inject constructor(
    private val repository: NewsRepository
) {
    suspend operator fun invoke(view: GlobeView, category: NewsCategory) {
        repository.refreshStories(view, category)
    }
}
