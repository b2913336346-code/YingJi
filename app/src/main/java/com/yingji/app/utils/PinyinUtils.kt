package com.yingji.app.utils

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

object PinyinUtils {
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    fun full(value: String): String = convert(value, initialsOnly = false)

    fun initials(value: String): String = convert(value, initialsOnly = true)

    fun fullVariants(value: String): Set<String> = variants(value, initialsOnly = false)

    fun initialVariants(value: String): Set<String> = variants(value, initialsOnly = true)

    fun normalize(value: String): String = value
        .lowercase()
        .filter { !it.isWhitespace() }

    private fun convert(value: String, initialsOnly: Boolean): String = buildString {
        value.forEach { character ->
            val pinyin = PinyinHelper.toHanyuPinyinStringArray(character, format)?.firstOrNull()
            when {
                pinyin != null && initialsOnly -> append(pinyin.first())
                pinyin != null -> append(pinyin)
                character.isLetterOrDigit() -> append(character.lowercaseChar())
            }
        }
    }

    private fun variants(value: String, initialsOnly: Boolean): Set<String> {
        var results = setOf("")
        value.forEach { character ->
            val pinyinOptions = PinyinHelper.toHanyuPinyinStringArray(character, format)
                ?.distinct()
                ?.map { if (initialsOnly) it.first().toString() else it }
                ?: character.takeIf { it.isLetterOrDigit() }
                    ?.lowercaseChar()
                    ?.toString()
                    ?.let(::listOf)
                ?: emptyList()
            if (pinyinOptions.isNotEmpty()) {
                results = results
                    .flatMap { prefix -> pinyinOptions.map { option -> prefix + option } }
                    .distinct()
                    .take(MAX_VARIANTS)
                    .toSet()
            }
        }
        return results
    }

    private const val MAX_VARIANTS = 256
}
