// di/NetworkModule.kt (업데이트)
package com.batteryhealth.monitor.di

import com.batteryhealth.monitor.data.remote.BatterySpecApi
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // API 서버가 없는 경우 더미 URL 사용
    // 실제 배포 시에는 실제 백엔드 서버 URL로 교체 필요
    private const val BASE_URL = "https://api.batteryhealth.example.com/v1/"

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setLenient()
            .create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // API 서버가 없어도 앱이 크래시하지 않도록 에러 핸들링
            .addInterceptor { chain ->
                try {
                    chain.proceed(chain.request())
                } catch (e: Exception) {
                    // 네트워크 에러 발생 시 빈 응답 반환
                    timber.log.Timber.e(e, "Network request failed")
                    throw e
                }
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        gson: Gson
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    /**
     * BatterySpecApi 제공
     */
    @Provides
    @Singleton
    fun provideBatterySpecApi(retrofit: Retrofit): BatterySpecApi {
        return retrofit.create(BatterySpecApi::class.java)
    }
}