// плагины подключаются здесь один раз, чтобы :app и :core использовали общий загрузчик классов Kotlin
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}
