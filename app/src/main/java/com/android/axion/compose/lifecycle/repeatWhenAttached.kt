package com.android.axion.compose.lifecycle

import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.lifecycle.*
import androidx.savedstate.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

@MainThread
fun View.repeatWhenAttached(
    block: suspend LifecycleOwner.(View) -> Unit
): DisposableHandle {
    val view = this
    val lifecycleCoroutineContext = Dispatchers.Main + EmptyCoroutineContext
    var lifecycleOwner: ViewLifecycleOwner? = null

    val onAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            lifecycleOwner?.onDestroy()
            lifecycleOwner = createLifecycleOwnerAndRun(view, lifecycleCoroutineContext, block)
        }

        override fun onViewDetachedFromWindow(v: View) {
            lifecycleOwner?.onDestroy()
            lifecycleOwner = null
        }
    }

    addOnAttachStateChangeListener(onAttachListener)
    if (view.isAttachedToWindow) {
        lifecycleOwner = createLifecycleOwnerAndRun(view, lifecycleCoroutineContext, block)
    }

    return DisposableHandle {
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        view.removeOnAttachStateChangeListener(onAttachListener)
    }
}

private fun createLifecycleOwnerAndRun(
    view: View,
    coroutineContext: CoroutineContext,
    block: suspend LifecycleOwner.(View) -> Unit
): ViewLifecycleOwner {
    return ViewLifecycleOwner(view).apply {
        onCreate()
        lifecycleScope.launch(context = coroutineContext) { block(view) }
    }
}

class ViewLifecycleOwner(private val view: View) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val windowVisibleListener =
        ViewTreeObserver.OnWindowVisibilityChangeListener { updateState() }
    private val windowFocusListener =
        ViewTreeObserver.OnWindowFocusChangeListener { updateState() }
    private val savedStateRegistryOwner =
        object : SavedStateRegistryOwner {
            private val savedStateRegistryController =
                SavedStateRegistryController.create(this).apply { performRestore(null) }
            override val savedStateRegistry = savedStateRegistryController.savedStateRegistry
            override val lifecycle: Lifecycle
                get() = registry
        }

    fun onCreate() {
        registry.currentState = Lifecycle.State.CREATED
        view.viewTreeObserver.addOnWindowVisibilityChangeListener(windowVisibleListener)
        view.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
        updateState()
    }

    fun onDestroy() {
        view.viewTreeObserver.removeOnWindowVisibilityChangeListener(windowVisibleListener)
        view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
        view.setViewTreeLifecycleOwner(null)
        view.setViewTreeSavedStateRegistryOwner(null)
        registry.currentState = Lifecycle.State.DESTROYED
    }

    override val lifecycle: Lifecycle get() = registry

    private fun updateState() {
        registry.currentState = when {
            view.windowVisibility != View.VISIBLE -> Lifecycle.State.CREATED
            !view.hasWindowFocus() -> Lifecycle.State.STARTED
            else -> Lifecycle.State.RESUMED
        }
    }
}
