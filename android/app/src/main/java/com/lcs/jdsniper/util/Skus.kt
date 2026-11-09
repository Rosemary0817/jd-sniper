package com.lcs.jdsniper.util

/** 从粘贴板/分享链接里抠 SKU：纯数字、item.jd.com/xxx.html、item.m.jd.com/product/xxx.html 都吃。 */
object Skus {

    private val PURE = Regex("""^\d{5,}$""")
    private val WITH_PATH = Regex("""(?:product/|item\.m?\.jd\.com/)(\d{5,})""")
    private val ANY_NUM = Regex("""\d{6,}""")

    fun parseSku(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (PURE.matches(s)) return s
        WITH_PATH.find(s)?.let { return it.groupValues[1] }
        return ANY_NUM.find(s)?.value
    }

    fun urlFor(sku: String): String = "https://item.m.jd.com/product/$sku.html"
}
