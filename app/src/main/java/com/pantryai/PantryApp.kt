package com.pantryai

import android.app.Application
import com.pantryai.data.Repository

class PantryApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
    }
}
