package kz.tenir.tv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

open class TenirApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.start()
        registerActivityLifecycleCallbacks(ScreenTracker { container.screen = it })
    }

    /** Тесты подменяют сеть своим транспортом. */
    protected open fun createContainer() = AppContainer(this)

    companion object {
        fun container(context: Context): AppContainer = (context.applicationContext as TenirApp).container
    }
}

/** Какой экран TENIR TV сейчас открыт; ни одного — гость во внешнем приложении. Передаётся в облако. */
private class ScreenTracker(private val set: (String) -> Unit) : Application.ActivityLifecycleCallbacks {
    private var resumed = 0

    override fun onActivityResumed(a: Activity) {
        resumed++
        set(a.javaClass.simpleName.removeSuffix("Activity").lowercase())
    }

    override fun onActivityPaused(a: Activity) {
        resumed--
        if (resumed <= 0) set("external")
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
