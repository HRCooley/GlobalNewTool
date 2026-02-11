package com.globenews.di

import com.globenews.core.common.Constants
import com.globenews.data.source.geocoding.NominatimApi
import com.globenews.data.source.remote.gdelt.GdeltApi
import com.globenews.data.source.remote.gnews.GNewsService
import com.globenews.data.source.remote.newsapi.NewsApiService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGdeltApi(client: OkHttpClient, moshi: Moshi): GdeltApi {
        // Use shorter timeouts for GDELT to fail fast
        val gdeltClient = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(Constants.GDELT_BASE_URL)
            .client(gdeltClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi).asLenient())
            .build()
            .create(GdeltApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNominatimApi(client: OkHttpClient, moshi: Moshi): NominatimApi {
        val nominatimClient = client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "GlobeNews/3.0 (Android)")
                    .build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(Constants.NOMINATIM_BASE_URL)
            .client(nominatimClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NominatimApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNewsApiService(client: OkHttpClient, moshi: Moshi): NewsApiService {
        return Retrofit.Builder()
            .baseUrl(Constants.NEWSAPI_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NewsApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideGNewsService(client: OkHttpClient, moshi: Moshi): GNewsService {
        return Retrofit.Builder()
            .baseUrl(Constants.GNEWS_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GNewsService::class.java)
    }
}
