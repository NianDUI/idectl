package com.niandui.idectl.core.console

/** Thrown when regex matching blows the time budget (catastrophic backtracking / ReDoS). */
class SearchTimeoutException : RuntimeException("search deadline exceeded")

/**
 * A CharSequence that trips the deadline on every char access, so a malicious pattern like
 * `(a+)+$` cannot hang the search thread — the Matcher's backtracking hits [get] and aborts.
 */
class DeadlineCharSequence(
    private val base: CharSequence,
    private val deadlineNanos: Long,
) : CharSequence {
    override val length: Int get() = base.length

    override fun get(index: Int): Char {
        if (System.nanoTime() > deadlineNanos) throw SearchTimeoutException()
        return base[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(base.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = base.toString()
}
