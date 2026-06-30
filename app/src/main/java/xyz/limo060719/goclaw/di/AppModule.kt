package xyz.limo060719.goclaw.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * App-wide singletons that can't use constructor injection.
 * Everything else (stores, repositories, HTTP/WS clients, API) uses `@Inject` constructors.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true   // backend payloads carry many fields we don't model
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }
}
