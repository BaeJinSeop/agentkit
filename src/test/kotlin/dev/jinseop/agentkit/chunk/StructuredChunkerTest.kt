package dev.jinseop.agentkit.chunk

import com.fasterxml.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredChunkerTest {
    private val json = JsonMapper.builder().build()

    // ---- CSV 파서

    @Test
    fun `parses quoted fields with delimiters, newlines and escaped quotes`() {
        val text = "이름,설명,가격\n라테,\"우유, 에스프레소\",5000\n\"모닝빈 \"\"시그니처\"\"\",\"첫 줄\n둘째 줄\",6000"
        assertEquals(
            listOf(
                listOf("이름", "설명", "가격"),
                listOf("라테", "우유, 에스프레소", "5000"),
                listOf("모닝빈 \"시그니처\"", "첫 줄\n둘째 줄", "6000"),
            ),
            StructuredChunker.parseCsv(text),
        )
    }

    @Test
    fun `handles crlf, bom, blank lines and empty fields`() {
        val text = "﻿이름,가격\r\n라테,5000\r\n\r\n소금빵,\r\n\"\",\"\"\r\n"
        assertEquals(
            listOf(listOf("이름", "가격"), listOf("라테", "5000"), listOf("소금빵", "")),
            StructuredChunker.parseCsv(text),
        )
    }

    @Test
    fun `tsv uses tab and csv with tab-only header is detected as tab`() {
        assertEquals('\t', StructuredChunker.csvDelimiter("이름,가격\n라테,5000", tsv = true))
        assertEquals('\t', StructuredChunker.csvDelimiter("이름\t가격\n라테\t5,000원", tsv = false))
        assertEquals(',', StructuredChunker.csvDelimiter("이름,가격\t비고\n라테,5000\t-", tsv = false))
        assertEquals(',', StructuredChunker.csvDelimiter("이름\n라테", tsv = false))
        assertEquals(
            listOf(listOf("이름", "가격"), listOf("라테", "5,000원")),
            StructuredChunker.parseCsv("이름\t가격\n라테\t5,000원", '\t'),
        )
    }

    @Test
    fun `unterminated quote fails parsing`() {
        assertNull(StructuredChunker.parseCsv("이름,설명\n라테,\"끝나지 않는 따옴표\n소금빵,빵"))
        assertNull(StructuredChunker.csvChunks("menu", "이름,설명\n라테,\"끝나지 않음", ',', 800))
    }

    @Test
    fun `row formatting keeps one csv line per row`() {
        assertEquals("라테,\"우유, 에스프레소\",\"모닝빈 \"\"시그니처\"\"\",첫 줄 둘째 줄",
            StructuredChunker.formatCsvRow(listOf("라테", "우유, 에스프레소", "모닝빈 \"시그니처\"", "첫 줄\n둘째 줄")))
        assertEquals("라테\t5,000원", StructuredChunker.formatCsvRow(listOf("라테", "5,000원"), '\t'))
        assertEquals("\"a\tb\"\tc", StructuredChunker.formatCsvRow(listOf("a\tb", "c"), '\t'))
    }

    // ---- CSV 청킹

    private val header = "상품명,분류,가격,설명"
    private fun row(i: Int) = "상품$i,분류${i % 7},${1000 + i * 10},\"설명 $i, 비고\""
    private fun csv(n: Int) = (listOf(header) + (1..n).map(::row)).joinToString("\n")

    @Test
    fun `300 rows are chunked with header on top, within size, without gaps or overlaps`() {
        val chunks = StructuredChunker.csvChunks("상품목록", csv(300), ',', 800)!!
        assertTrue(chunks.size > 5, "chunks=${chunks.size}")
        var next = 1
        val seen = mutableListOf<String>()
        for (c in chunks) {
            assertTrue(c.text.startsWith("$header\n"), c.text.take(80))
            val lines = c.text.lines().drop(1)
            assertTrue(c.text.length <= 800 || lines.size == 1, "len=${c.text.length}")
            val m = Regex("^행 (\\d+)(?:–(\\d+))?$").matchEntire(c.heading!!)!!
            val from = m.groupValues[1].toInt()
            val to = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
            assertEquals(next, from, c.heading)
            assertEquals(to - from + 1, lines.size, c.heading)
            assertEquals("상품목록 > ${c.heading}", c.path)
            next = to + 1
            seen += lines
        }
        assertEquals(301, next)
        assertEquals((1..300).map(::row), seen)
    }

    @Test
    fun `row longer than chunk size gets its own chunk`() {
        val long = "긴상품,분류,1000," + "가".repeat(300)
        val text = listOf("이름,분류,가격,설명", "a,b,1,c", "d,e,2,f", long, "g,h,3,i").joinToString("\n")
        val chunks = StructuredChunker.csvChunks("t", text, ',', 100)!!
        assertEquals(listOf("행 1–2", "행 3", "행 4"), chunks.map { it.heading })
        assertEquals("이름,분류,가격,설명\n$long", chunks[1].text)
    }

    @Test
    fun `uneven column counts are kept as they are and header-only csv is one chunk`() {
        val chunks = StructuredChunker.csvChunks("t", "a,b,c\n1,2\n3,4,5,6", ',', 800)!!
        assertEquals(listOf("a,b,c\n1,2\n3,4,5,6"), chunks.map { it.text })
        assertEquals("행 1–2", chunks[0].heading)
        val headerOnly = StructuredChunker.csvChunks("t", "a,b,c\n", ',', 800)!!
        assertEquals(listOf(Chunk("t", null, "a,b,c")), headerOnly)
    }

    // ---- JSON

    @Test
    fun `top-level object array is chunked by records`() {
        val items = (1..50).map { mapOf("name" to "지점$it", "hours" to "09:00~21:00", "parking" to if (it % 2 == 0) "가능" else "불가") }
        val chunks = StructuredChunker.jsonChunks("stores", json.valueToTree(items), 400)!!
        assertTrue(chunks.size > 3)
        assertEquals("항목 1–", chunks.first().heading!!.take(5))
        val lines = chunks.flatMap { it.text.lines() }
        assertEquals(50, lines.size)
        assertEquals("""{"name":"지점1","hours":"09:00~21:00","parking":"불가"}""", lines.first())
        lines.forEach { assertTrue(json.readTree(it).isObject) }
        chunks.forEach { assertTrue(it.text.length <= 400) }
        assertTrue(Regex("항목 (\\d+–)?50").matches(chunks.last().heading!!), chunks.last().heading)
    }

    @Test
    fun `object arrays inside a top-level object are chunked per key and the rest goes to 기타`() {
        val doc = json.readTree(
            """{"brand":"모닝빈","stores":[{"name":"강남점"},{"name":"연희점"}],"meta":{"updated":"2026-10"},"menus":[{"name":"라테","price":5000}],"tags":["커피","디저트"]}""",
        )
        val chunks = StructuredChunker.jsonChunks("data", doc, 800)!!
        assertEquals(listOf("stores > 항목 1–2", "menus > 항목 1", "기타"), chunks.map { it.heading })
        assertEquals("{\"name\":\"강남점\"}\n{\"name\":\"연희점\"}", chunks[0].text)
        val rest = chunks[2].text
        assertTrue(rest.contains("\"brand\" : \"모닝빈\"") && rest.contains("\"updated\" : \"2026-10\"") && rest.contains("\"커피\""), rest)
        assertTrue(!rest.contains("강남점") && !rest.contains("라테"), rest)
    }

    @Test
    fun `scalar arrays and objects without object arrays take the plain path`() {
        assertNull(StructuredChunker.jsonChunks("t", json.readTree("""["커피","디저트"]"""), 800))
        assertNull(StructuredChunker.jsonChunks("t", json.readTree("""[{"a":1},2]"""), 800))
        assertNull(StructuredChunker.jsonChunks("t", json.readTree("""{"a":{"b":{"c":[1,2]}}}"""), 800))
        assertNull(StructuredChunker.jsonChunks("t", json.readTree("""[]"""), 800))
    }

    // ---- 긴 단락·마크다운 표

    @Test
    fun `lines are packed up to chunk size and a single long line stands alone`() {
        val lines = listOf("가".repeat(30), "나".repeat(30), "다".repeat(30), "라".repeat(150), "마".repeat(10))
        assertEquals(
            listOf("가".repeat(30) + "\n" + "나".repeat(30), "다".repeat(30), "라".repeat(150), "마".repeat(10)),
            StructuredChunker.splitLines(lines, 70),
        )
    }

    @Test
    fun `split markdown table repeats header and separator on every piece`() {
        val head = listOf("| 상품 | 기간 | 수수료 |", "|---|:---:|---|")
        val rows = (1..30).map { "| 상품$it | ${it}일 | ${it}% |" }
        val lines = listOf("표 앞 설명 줄입니다.") + head + rows + listOf("표 뒤 설명 줄입니다.")
        val pieces = StructuredChunker.splitLines(lines, 120)
        assertTrue(pieces.size > 3)
        val tableRows = mutableListOf<String>()
        pieces.forEach { p ->
            val ls = p.lines()
            val dataRows = ls.filter { it.startsWith("| 상품") && it != head[0] }
            if (dataRows.isNotEmpty()) {
                val at = ls.indexOf(head[0])
                assertTrue(at >= 0 && ls[at + 1] == head[1], p)
                assertTrue(ls.indexOf(dataRows.first()) > at, p)
            }
            tableRows += dataRows
            assertTrue(p.length <= 120, p)
        }
        assertEquals(rows, tableRows)
        assertTrue(pieces.first().startsWith("표 앞 설명 줄입니다."))
        assertTrue(pieces.last().endsWith("표 뒤 설명 줄입니다."))
    }

    @Test
    fun `table separator detection`() {
        assertTrue(StructuredChunker.isTableSeparator("|---|---|"))
        assertTrue(StructuredChunker.isTableSeparator("| :--- | ---: |"))
        assertTrue(StructuredChunker.isTableSeparator("|---|---"))
        assertTrue(!StructuredChunker.isTableSeparator("| a | b |"))
        assertTrue(!StructuredChunker.isTableSeparator("---"))
    }
}
