package com.example.mymessenger.data.di

import com.example.mymessenger.data.repository.FirebaseAuthRepositoryImpl
import com.example.mymessenger.domain.repository.AuthRepository
import com.example.mymessenger.domain.usecases.CheckNameUseCase
import com.example.mymessenger.domain.usecases.LoginWithEmailUseCase
import com.example.mymessenger.domain.usecases.RegisterWithEmailUseCase
import com.example.mymessenger.domain.usecases.ResetPasswordUseCase
import com.example.mymessenger.ui.viewmodel.LoginViewModel
import com.example.mymessenger.ui.viewmodel.RegisterViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single<AuthRepository> { FirebaseAuthRepositoryImpl() }
    factory { RegisterWithEmailUseCase(get()) }
    factory { CheckNameUseCase(get()) }
    factory { ResetPasswordUseCase(get()) }
    factory { LoginWithEmailUseCase(get()) }
    viewModel { LoginViewModel(get(), get()) }
    viewModel { RegisterViewModel(get(), get()) }
}