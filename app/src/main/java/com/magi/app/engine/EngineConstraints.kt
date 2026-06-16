package com.magi.app.engine

/** Index-resolved constraint rows used by the native evaluator. */
data class C1(val day1: Int, val shiftIdx: Int, val day2: Int)
data class C2(val shiftIdx: Int, val count: Int)
data class C3(val seq: IntArray) {
    override fun equals(other: Any?): Boolean = other is C3 && seq.contentEquals(other.seq)
    override fun hashCode(): Int = seq.contentHashCode()
}
data class C41(val groupIdx: Int, val shiftIdx: Int, val min: Int, val max: Int)
data class C42(val groupA: Int, val shiftA: Int, val groupB: Int, val shiftB: Int)
