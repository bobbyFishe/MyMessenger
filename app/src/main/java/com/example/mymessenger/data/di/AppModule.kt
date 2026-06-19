package com.example.mymessenger.data.di

import androidx.room.Room
import com.example.mymessenger.data.local.AppDatabase
import com.example.mymessenger.data.repository.ChatRepositoryImpl
import com.example.mymessenger.data.repository.FirebaseAuthRepositoryImpl
import com.example.mymessenger.data.repository.UserRepositoryImpl
import com.example.mymessenger.domain.repository.AuthRepository
import com.example.mymessenger.domain.repository.ChatRepository
import com.example.mymessenger.domain.repository.UserRepository
import com.example.mymessenger.domain.usecases.CheckNameUseCase
import com.example.mymessenger.domain.usecases.LoginWithEmailUseCase
import com.example.mymessenger.domain.usecases.RegisterWithEmailUseCase
import com.example.mymessenger.domain.usecases.ResetPasswordUseCase
import com.example.mymessenger.ui.viewmodel.ChatDetailViewModel
import com.example.mymessenger.ui.viewmodel.LoginViewModel
import com.example.mymessenger.ui.viewmodel.MainViewModel
import com.example.mymessenger.ui.viewmodel.RegisterViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { FirebaseFirestore.getInstance() }
    single { FirebaseAuth.getInstance() }
    single {
        val db = Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "secure_messenger.db"
        ).fallbackToDestructiveMigration(false).build()

        db.openHelper.writableDatabase
        db
    }
    single<ChatRepository> {
        ChatRepositoryImpl(
            firestore = get(),
            messageDao = get(),
            chatKeyDao = get(),
            userRepository = get()
        )
    }
    single { get<AppDatabase>().chatDao() }
    single { get<AppDatabase>().chatKeyDao() }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().contactDao() }
    single<AuthRepository> { FirebaseAuthRepositoryImpl() }
    factory { RegisterWithEmailUseCase(get()) }
    factory { CheckNameUseCase(get()) }
    factory { ResetPasswordUseCase(get()) }
    factory { LoginWithEmailUseCase(get()) }
    viewModel { LoginViewModel(get(), get()) }
    viewModel { RegisterViewModel(get(), get(), get()) }
    single<UserRepository> { UserRepositoryImpl(firestore = get(), chatKeyDao = get(), get(), get()) }
    viewModel { MainViewModel(userRepository = get(), firebaseAuth = get(), chatRepository = get()) }
    viewModel { ChatDetailViewModel(chatRepository = get()) }
}