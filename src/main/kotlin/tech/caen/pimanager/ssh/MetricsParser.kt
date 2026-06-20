package tech.caen.pimanager.ssh

import tech.caen.pimanager.model.MetricSample
import tech.caen.pimanager.nowIso

/**
 * Parse la sortie balisée de [SshService.readMetrics] en un [MetricSample].
 *
 * Format attendu :
 * ```
 * #MEM
 * MemTotal:        8062400 kB
 * MemAvailable:    6210000 kB
 * #LOAD
 * 0.42 0.55 0.60 1/180 1234
 * #CPU
 * cpu  123 4 56 7890 12 0 3 0 0 0
 * cpu  124 4 57 7900 12 0 3 0 0 0
 * ```
 *
 * Tolérant : renvoie `null` si la mémoire est illisible (sans elle l'échantillon n'a
 * pas d'intérêt) ; le %CPU et la charge retombent à 0 si absents.
 */
object MetricsParser {

    fun parse(output: String, at: String = nowIso()): MetricSample? {
        var memTotalKb: Long? = null
        var memAvailableKb: Long? = null
        var load1 = 0.0
        val cpuLines = mutableListOf<String>()
        var section = ""

        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when (line) {
                "#MEM", "#LOAD", "#CPU" -> { section = line; continue }
            }
            when (section) {
                "#MEM" -> {
                    val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                    when {
                        line.startsWith("MemTotal:") -> memTotalKb = kb
                        line.startsWith("MemAvailable:") -> memAvailableKb = kb
                    }
                }
                "#LOAD" -> load1 = line.substringBefore(' ').toDoubleOrNull() ?: 0.0
                "#CPU" -> if (line.startsWith("cpu ") || line.startsWith("cpu\t")) cpuLines += line
            }
        }

        val total = memTotalKb?.takeIf { it > 0 } ?: return null
        val available = memAvailableKb ?: return null
        val usedKb = (total - available).coerceAtLeast(0)
        val memUsedPercent = (usedKb.toDouble() / total * 100).clampPercent()

        return MetricSample(
            at = at,
            memUsedPercent = memUsedPercent,
            memUsedMb = usedKb / 1024,
            memTotalMb = total / 1024,
            cpuPercent = cpuPercent(cpuLines),
            load1 = load1,
        )
    }

    /**
     * %CPU à partir de deux instantanés de la ligne `cpu` agrégée de `/proc/stat`.
     * Champs : user nice system idle iowait irq softirq steal … ; le temps « oisif »
     * est idle + iowait. %CPU = 100 × (1 − Δidle / Δtotal).
     */
    private fun cpuPercent(cpuLines: List<String>): Double {
        if (cpuLines.size < 2) return 0.0
        val (idle1, total1) = cpuSplit(cpuLines.first()) ?: return 0.0
        val (idle2, total2) = cpuSplit(cpuLines.last()) ?: return 0.0
        val totalDelta = total2 - total1
        if (totalDelta <= 0) return 0.0
        val idleDelta = (idle2 - idle1).coerceAtLeast(0)
        return ((1.0 - idleDelta.toDouble() / totalDelta) * 100).clampPercent()
    }

    /** -> (idle = idle+iowait, total = somme de tous les champs) pour une ligne `cpu …`. */
    private fun cpuSplit(line: String): Pair<Long, Long>? {
        val fields = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        val idle = fields[3] + (fields.getOrNull(4) ?: 0)
        return idle to fields.sum()
    }

    private fun Double.clampPercent(): Double {
        val v = if (isNaN()) 0.0 else this
        return (Math.round(v.coerceIn(0.0, 100.0) * 10) / 10.0)
    }
}
