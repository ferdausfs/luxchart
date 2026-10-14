package com.ferdausfs.luxchart

/**
 * Pure, JVM-testable policy helpers for LuxChart.
 *
 * RULES for this file:
 *  - NO Android imports (must run in plain JVM unit tests).
 *  - Navigation allow-list will join this file as the single source of truth.
 */
object Policy {

    /**
     * Desktop user-agent built at runtime from the system WebView UA.
     *
     * Format mandated by the site: Windows Chrome desktop UA whose MAJOR
     * version tracks the device's actual WebView (so luxalgo always sees a
     * current browser — no hardcoded Chrome/131 anymore). When the system UA
     * carries no parseable Chrome major (practically impossible on devices
     * with WebView installed), a conservative static fallback is used.
     */
    fun desktopUa(systemUa: String?): String {
        val major = systemUa?.let { CHROME_MAJOR.find(it)?.groupValues?.get(1) }
            ?.takeIf { it.length in 1..3 && it.all { c -> c.isDigit() } }
        return UA_TEMPLATE.format(major ?: FALLBACK_CHROME_MAJOR)
    }

    private const val UA_TEMPLATE =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36"

    private val CHROME_MAJOR = Regex("""Chrome/(\d+)""")

    private const val FALLBACK_CHROME_MAJOR = "124"
}
