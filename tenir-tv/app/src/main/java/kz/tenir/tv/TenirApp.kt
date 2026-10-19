package kz.tenir.tv

import android.app.Application
import android.content.Context

open class TenirApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.start()
    }

    /** Тесты подменяют сеть своим транспортом. */
    protected open fun createContainer() = AppContainer(this)

    companion object {
        fun container(context: Context): AppContainer = (context.applicationContext as TenirApp).container
    }
}
