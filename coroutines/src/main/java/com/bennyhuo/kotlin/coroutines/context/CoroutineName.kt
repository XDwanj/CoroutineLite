package com.bennyhuo.kotlin.coroutines.context

import kotlin.coroutines.CoroutineContext

/**
 * 协程名元素
 * @property name String
 * @property key Key
 * @constructor
 */
class CoroutineName(val name: String) : CoroutineContext.Element {
  companion object Key : CoroutineContext.Key<CoroutineName>

  override val key = Key

  override fun toString(): String {
    return name
  }
}