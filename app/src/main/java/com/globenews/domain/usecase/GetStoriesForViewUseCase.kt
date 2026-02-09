package com.globenews.domain.usecase

import com.globenews.core.common.Result
import com.globenews.domain.model.GlobeView
import com.globenews.domain.model.NewsCategory
import com.globenews.domain.model.NewsStory
import com.globenews.domain.repository.NewsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetStoriesForViewUseCase @Inject constructor(
    private val repository: NewsRepository
) {
    operator fun invoke(view: GlobeView, category: NewsCategory): Flow<Result<List<NewsStory>>> {
        return repository.getStoriesForView(view, category)
    }
}
