package com.globenews.domain.usecase

import com.globenews.domain.model.NewsCategory
import com.globenews.domain.repository.NewsRepository
import javax.inject.Inject

class BackgroundFillUseCase @Inject constructor(
    private val repository: NewsRepository
) {
    suspend operator fun invoke(category: NewsCategory) {
        repository.backgroundFillGlobalGrid(category)
    }
}
