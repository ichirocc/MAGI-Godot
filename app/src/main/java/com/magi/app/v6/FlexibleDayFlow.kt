package com.magi.app.v6

/**
 * 1日分の「職員→シフト」割当を、シフト人数を固定せずに最小費用で解く。
 *
 * staffShiftCost[i][k]: 職員iをシフトkへ置く費用。到達不能はINF。
 * shiftMarginalCost[k][q]: シフトkの(q+1)人目を置く限界費用。
 *
 * source -> staff(cap=1) -> shift(cap=1) -> sink(cap=1の並列辺×S)
 *
 * 必要人数を埋める辺は負費用になり得るため、各増加路をSPFAで厳密に解く。
 * 職員数S、シフト数Kに対し、実データ規模では概ねO(S^2*K)〜O(S^3*K)。
 */
internal object FlexibleDayFlow {
    const val INF: Long = 1_000_000_000_000L

    data class Result(val assignment: IntArray, val cost: Long)

    private data class Edge(
        val to: Int,
        val rev: Int,
        var cap: Int,
        val cost: Long,
    )

    fun solve(
        staffShiftCost: Array<LongArray>,
        shiftMarginalCost: Array<LongArray>,
        inf: Long = INF,
    ): Result? {
        val staffN = staffShiftCost.size
        if (staffN == 0) return Result(IntArray(0), 0L)
        val shiftN = staffShiftCost[0].size
        if (shiftN == 0 || staffShiftCost.any { it.size != shiftN }) return null
        if (shiftMarginalCost.size != shiftN || shiftMarginalCost.any { it.size < staffN }) return null

        val source = 0
        val staffBase = 1
        val shiftBase = staffBase + staffN
        val sink = shiftBase + shiftN
        val nodeN = sink + 1
        val graph = Array(nodeN) { ArrayList<Edge>() }

        fun addEdge(from: Int, to: Int, cap: Int, cost: Long): Int {
            val index = graph[from].size
            val reverseIndex = graph[to].size
            graph[from].add(Edge(to, reverseIndex, cap, cost))
            graph[to].add(Edge(from, index, 0, -cost))
            return index
        }

        val staffShiftEdge = Array(staffN) { IntArray(shiftN) { -1 } }
        for (i in 0 until staffN) {
            addEdge(source, staffBase + i, 1, 0L)
            for (k in 0 until shiftN) {
                val c = staffShiftCost[i][k]
                if (c >= inf / 2) continue
                staffShiftEdge[i][k] = addEdge(staffBase + i, shiftBase + k, 1, c)
            }
        }
        for (k in 0 until shiftN) {
            for (q in 0 until staffN) addEdge(shiftBase + k, sink, 1, shiftMarginalCost[k][q])
        }

        var flow = 0
        var totalCost = 0L
        val dist = LongArray(nodeN)
        val prevV = IntArray(nodeN)
        val prevE = IntArray(nodeN)
        val inQueue = BooleanArray(nodeN)
        // inQueueにより同時在籍は最大nodeN。リングのfull/empty衝突を避ける余裕を持たせる。
        val queue = IntArray(nodeN * 4 + 8)

        while (flow < staffN) {
            java.util.Arrays.fill(dist, inf)
            java.util.Arrays.fill(prevV, -1)
            java.util.Arrays.fill(prevE, -1)
            java.util.Arrays.fill(inQueue, false)
            var head = 0
            var tail = 0
            dist[source] = 0L
            queue[tail++] = source
            inQueue[source] = true

            while (head != tail) {
                val v = queue[head]
                head = (head + 1) % queue.size
                inQueue[v] = false
                for (ei in graph[v].indices) {
                    val e = graph[v][ei]
                    if (e.cap <= 0 || dist[v] >= inf / 2) continue
                    val nd = dist[v] + e.cost
                    if (nd >= dist[e.to]) continue
                    dist[e.to] = nd
                    prevV[e.to] = v
                    prevE[e.to] = ei
                    if (!inQueue[e.to]) {
                        queue[tail] = e.to
                        tail = (tail + 1) % queue.size
                        inQueue[e.to] = true
                    }
                }
            }
            if (dist[sink] >= inf / 2) return null

            var v = sink
            while (v != source) {
                val pv = prevV[v]
                val pe = prevE[v]
                if (pv < 0 || pe < 0) return null
                val e = graph[pv][pe]
                e.cap--
                graph[v][e.rev].cap++
                v = pv
            }
            flow++
            totalCost += dist[sink]
        }

        val assignment = IntArray(staffN) { -1 }
        for (i in 0 until staffN) {
            val node = staffBase + i
            for (k in 0 until shiftN) {
                val ei = staffShiftEdge[i][k]
                if (ei >= 0 && graph[node][ei].cap == 0) {
                    assignment[i] = k
                    break
                }
            }
            if (assignment[i] < 0) return null
        }
        return Result(assignment, totalCost)
    }
}
