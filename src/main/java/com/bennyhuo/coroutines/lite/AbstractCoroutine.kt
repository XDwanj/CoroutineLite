package com.bennyhuo.coroutines.lite

import com.bennyhuo.coroutines.utils.log
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

typealias OnCompleteT<T> = (Result<T>) -> Unit

interface Disposable {
    fun dispose()
}

class CompletionHandlerDisposable<T>(val job: Job, val onComplete: OnCompleteT<T>): Disposable{
    override fun dispose() {
        job.remove(this)
    }
}

class CancellationHandlerDisposable(val job: Job, val onCancel: OnCancel): Disposable{
    override fun dispose() {
        job.remove(this)
    }
}

sealed class DisposableList {
    object Nil: DisposableList()
    class Cons(val head: Disposable, val tail: DisposableList): DisposableList()
}

fun DisposableList.remove(disposable: Disposable): DisposableList {
    return when(this){
        DisposableList.Nil -> this
        is DisposableList.Cons -> {
            if(head == disposable){
                return tail
            } else {
                DisposableList.Cons(head, tail.remove(disposable))
            }
        }
    }
}

tailrec fun DisposableList.forEach(action: (Disposable) -> Unit): Unit = when(this){
    DisposableList.Nil ->Unit
    is DisposableList.Cons -> {
        action(this.head)
        this.tail.forEach(action)
    }
}

inline fun <reified T: Disposable> DisposableList.loopOn(crossinline action: (T) -> Unit) = forEach {
    when(it){
        is T -> action(it)
    }
}

sealed class CoroutineState {
    private var disposableList: DisposableList = DisposableList.Nil

    fun from(state: CoroutineState): CoroutineState {
        this.disposableList = state.disposableList
        return this
    }

    fun with(disposable: Disposable): CoroutineState {
        this.disposableList = DisposableList.Cons(disposable, this.disposableList)
        return this
    }

    fun without(disposable: Disposable): CoroutineState {
        this.disposableList = this.disposableList.remove(disposable)
        return this
    }

    fun <T> notifyCompletion(result: Result<T>) {
        this.disposableList.loopOn<CompletionHandlerDisposable<T>> {
            it.onComplete(result)
        }
    }

    fun notifyCancellation() {
        disposableList.loopOn<CancellationHandlerDisposable> {
            it.onCancel()
        }
    }

    fun clear() {
        this.disposableList = DisposableList.Nil
    }

    override fun toString(): String {
        return "CoroutineState.${this.javaClass.simpleName}"
    }

    class InComplete : CoroutineState()
    class Cancelling: CoroutineState()
    class Complete<T>(val value: T? = null, val exception: Throwable? = null) : CoroutineState()
}

abstract class AbstractCoroutine<T>(context: CoroutineContext) : Job, Continuation<T>, CoroutineScope {

    protected val state = AtomicReference<CoroutineState>()

    override val context: CoroutineContext

    override val coroutineContext: CoroutineContext
        get() = context

    protected val parentJob = context[Job]

    private var parentCancelDisposable: Disposable? = null

    init {
        state.set(CoroutineState.InComplete())
        this.context = context + this

        parentCancelDisposable = parentJob?.invokeOnCancel {
            cancel()
        }
    }

    val isCompleted
        get() = state.get() is CoroutineState.Complete<*>

    override val isActive: Boolean
        get() = when(state.get()){
            is CoroutineState.Complete<*>,
            is CoroutineState.Cancelling -> false
            else -> true
        }

    override fun resumeWith(result: Result<T>) {
        val newState = state.updateAndGet { prevState ->
            when(prevState){
                is CoroutineState.InComplete -> {
                    CoroutineState.Complete(result.getOrNull(), result.exceptionOrNull()).from(prevState)
                }
                is CoroutineState.Cancelling -> {
                    CoroutineState.Complete(null, CancellationException("Result arrived, but cancelled already.")).from(prevState)
                }
                is CoroutineState.Complete<*> -> {
                    throw IllegalStateException("Already completed!")
                }
            }
        }

        (newState as CoroutineState.Complete<T>).exception?.let(this::tryHandleException)

        newState.notifyCompletion(result)
        newState.clear()
        parentCancelDisposable?.dispose()
    }

    override suspend fun join() {
        when (state.get()) {
            is CoroutineState.InComplete,
            is CoroutineState.Cancelling -> return joinSuspend()
            is CoroutineState.Complete<*> -> {
                val parentJobState = this.parentJob?.isActive ?: return
                if(!parentJobState){
                    throw CancellationException("Parent cancelled.")
                }
                return
            }
        }
    }

    private suspend fun joinSuspend() = suspendCancellableCoroutine<Unit> { continuation ->
        doOnCompleted { result ->
            continuation.resume(Unit)
        }
    }

    override fun cancel() {
        val newState = state.updateAndGet { prev ->
            when (prev) {
                is CoroutineState.InComplete -> {
                    CoroutineState.Cancelling().from(prev)
                }
                is CoroutineState.Cancelling,
                is CoroutineState.Complete<*> -> prev
            }
        }

        if(newState is CoroutineState.Cancelling){
            newState.notifyCancellation()
        }
        parentCancelDisposable?.dispose()
    }

    protected fun doOnCompleted(block: (Result<T>) -> Unit): Disposable {
        val disposable = CompletionHandlerDisposable(this, block)
        val newState = state.updateAndGet { prev ->
            when (prev) {
                is CoroutineState.InComplete -> {
                    CoroutineState.InComplete().from(prev).with(disposable)
                }
                is CoroutineState.Cancelling -> {
                    CoroutineState.Cancelling().from(prev).with(disposable)
                }
                is CoroutineState.Complete<*> -> {
                    prev
                }
            }
        }
        (newState as? CoroutineState.Complete<T>)?.let {
            block(
                    when {
                        it.value != null -> Result.success(it.value)
                        it.exception != null -> Result.failure(it.exception)
                        else -> throw IllegalStateException("Won't happen.")
                    }
            )
        }
        return disposable
    }

    override fun invokeOnCancel(onCancel: OnCancel): Disposable {
        val disposable = CancellationHandlerDisposable(this, onCancel)
        val newState = state.updateAndGet { prev ->
            when (prev) {
                is CoroutineState.InComplete -> {
                    CoroutineState.InComplete().from(prev).with(disposable)
                }
                is CoroutineState.Cancelling,
                is CoroutineState.Complete<*> -> {
                    prev
                }
            }
        }
        (newState as? CoroutineState.Cancelling)?.let {
            // call immediately when complete.
            onCancel()
        }
        return disposable
    }

    override fun invokeOnCompletion(onComplete: OnComplete): Disposable {
        return doOnCompleted { _ -> onComplete() }
    }

    override fun remove(disposable: Disposable) {
        state.updateAndGet { prev ->
            when (prev) {
                is CoroutineState.InComplete -> {
                    CoroutineState.InComplete().from(prev).without(disposable)
                }
                is CoroutineState.Cancelling -> {
                    CoroutineState.Cancelling().from(prev).without(disposable)
                }
                is CoroutineState.Complete<*> -> {
                    prev
                }
            }
        }
    }

    private fun tryHandleException(e: Throwable): Boolean{
        return when(e){
            is CancellationException -> {
                false
            }
            else -> {
                (parentJob as? AbstractCoroutine<*>)?.handleChildException(e)?.takeIf { it }
                        ?: handleJobException(e)
            }
        }
    }

    protected open fun handleChildException(e: Throwable): Boolean{
        cancel()
        return tryHandleException(e)
    }

    protected open fun handleJobException(e: Throwable) = false

    override fun toString(): String {
        return context[CoroutineName].toString()
    }
}