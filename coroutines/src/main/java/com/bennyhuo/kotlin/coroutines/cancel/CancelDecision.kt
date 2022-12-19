package com.bennyhuo.kotlin.coroutines.cancel

/**
 * 取消决定的枚举
 */
enum class CancelDecision {
  /**
   * 犹豫
   */
  UNDECIDED,

  /**
   * 挂起
   */
  SUSPENDED,

  /**
   * 恢复
   */
  RESUMED
}