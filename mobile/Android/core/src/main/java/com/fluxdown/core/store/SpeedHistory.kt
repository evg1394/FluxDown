package com.fluxdown.core.store

/**
 * 1 Hz 速度采样环（波形 60 点 = 60 秒）。不可变：每次记录返回新实例，便于 StateFlow / Compose 比较。
 * 采样按墙钟秒分桶：两次事件间隔 n 秒则以上一值补齐 n−1 个桶（事件稀疏时横轴仍是真实时间）。
 */
class SpeedHistory private constructor(
    private val down: LongArray,
    private val up: LongArray,
    /** 已写入的点数（≤ capacity）。 */
    val size: Int,
    /** 最近一个桶对应的 Unix 秒。 */
    val lastSecond: Long,
) {
    val capacity: Int get() = down.size

    /** 第 i 个点（0 = 最旧）。 */
    fun downAt(i: Int): Long = down[index(i)]
    fun upAt(i: Int): Long = up[index(i)]

    val latestDown: Long get() = if (size == 0) 0 else downAt(size - 1)
    val latestUp: Long get() = if (size == 0) 0 else upAt(size - 1)

    fun maxDown(): Long {
        var m = 0L
        for (i in 0 until size) m = maxOf(m, downAt(i))
        return m
    }

    private fun index(i: Int): Int = Math.floorMod(lastSecond - size + 1 + i, capacity.toLong()).toInt()

    fun record(nowMs: Long, downBps: Long, upBps: Long): SpeedHistory {
        val sec = nowMs / 1000
        if (size > 0 && sec < lastSecond) return this
        val d = down.copyOf()
        val u = up.copyOf()
        var n = size
        if (n > 0 && sec == lastSecond) {
            val slot = (sec % capacity).toInt()
            d[slot] = downBps
            u[slot] = upBps
            return SpeedHistory(d, u, n, sec)
        }
        val gap = if (n == 0) 1 else (sec - lastSecond).coerceAtMost(capacity.toLong()).toInt()
        val fillDown = if (n == 0) downBps else latestDown
        val fillUp = if (n == 0) upBps else latestUp
        for (k in gap - 1 downTo 1) {
            val slot = ((sec - k) % capacity).toInt()
            d[slot] = fillDown
            u[slot] = fillUp
        }
        val slot = (sec % capacity).toInt()
        d[slot] = downBps
        u[slot] = upBps
        n = (n + gap).coerceAtMost(capacity)
        return SpeedHistory(d, u, n, sec)
    }

    companion object {
        const val CAPACITY = 60
        val Empty = SpeedHistory(LongArray(CAPACITY), LongArray(CAPACITY), 0, 0)
    }
}
