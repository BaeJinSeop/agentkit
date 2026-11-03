package dev.jinseop.agentkit.chunk

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * 표 형태 자료(CSV·TSV, JSON 레코드 배열, 마크다운 표) 청킹. 상태 없는 순수 함수만 둔다.
 * 청크마다 열·키 의미가 보이도록 CSV 는 헤더 줄을, 마크다운 표 조각은 헤더 줄과 구분선을 맨 위에 다시 넣는다.
 * 실패(따옴표가 끝나지 않는 CSV, 레코드 배열이 아닌 JSON)는 null 을 돌려주고, 호출부가 일반 텍스트 청킹으로 대체한다.
 */
object StructuredChunker {
    /** 청크 heading 에 쓰는 범위 구분자(en dash). */
    const val RANGE_DASH = "–"
    const val JSON_REST_HEADING = "기타"

    private const val BOM = "﻿"
    private val NEWLINES = Regex("\r\n|\r|\n")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val JSON = JsonMapper.builder().build()

    /** 묶음 하나. from·to 는 레코드 번호(1부터, 양끝 포함). */
    data class Packed(val from: Int, val to: Int, val text: String)

    // ---- CSV·TSV

    /** .tsv 는 탭. .csv 는 첫 줄에 쉼표가 없고 탭이 있으면 탭, 아니면 쉼표. */
    fun csvDelimiter(text: String, tsv: Boolean): Char {
        if (tsv) return '\t'
        val first = text.removePrefix(BOM).lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        return if (',' !in first && '\t' in first) '\t' else ','
    }

    /**
     * 따옴표를 처리하는 CSV 파서. 큰따옴표 필드, 필드 안 구분자·줄바꿈, `""` 이스케이프, CRLF, BOM 을 처리한다.
     * 여는 따옴표 앞의 공백은 버린다. 모든 필드가 비어 있는 행(빈 줄)은 건너뛴다.
     * 따옴표가 끝나지 않으면 null.
     */
    fun parseCsv(text: String, delimiter: Char = ','): List<List<String>>? {
        val s = text.removePrefix(BOM)
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var quoted = false // 현재 필드에서 따옴표 구간을 이미 한 번 열었음(닫힌 뒤 다시 여는 것은 글자로 취급)

        fun endField() { row += field.toString(); field.clear(); quoted = false }
        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) rows += row.toList()
            row.clear()
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when {
                c == '"' && !quoted && field.isBlank() -> { field.clear(); inQuotes = true; quoted = true }
                c == delimiter -> endField()
                c == '\r' -> { if (i + 1 < s.length && s[i + 1] == '\n') i++; endRow() }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (inQuotes) return null
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** 행 하나를 원본 CSV 한 줄 형태로 쓴다. 필드 안 줄바꿈은 공백으로, 구분자·따옴표가 든 필드는 따옴표로 감싼다. */
    fun formatCsvRow(fields: List<String>, delimiter: Char = ','): String =
        fields.joinToString(delimiter.toString()) { f ->
            val v = f.replace(NEWLINES, " ")
            if (delimiter in v || '"' in v) "\"" + v.replace("\"", "\"\"") + "\"" else v
        }

    /**
     * 청크 하나 = 헤더 줄 + 연속된 데이터 행 묶음(chunkSize 이하, chunkSize 보다 긴 행은 그 행만으로 한 청크).
     * heading 은 `행 a–b`(데이터 행 번호, 1부터). 데이터 행이 없으면 헤더만 한 청크. 파싱할 수 없으면 null.
     */
    fun csvChunks(title: String, text: String, delimiter: Char, chunkSize: Int): List<Chunk>? {
        val rows = parseCsv(text, delimiter) ?: return null
        if (rows.isEmpty()) return emptyList()
        val header = formatCsvRow(rows.first(), delimiter)
        val records = rows.drop(1).map { formatCsvRow(it, delimiter) }
        if (records.isEmpty()) return listOf(Chunk(title, null, header))
        return packRecords(records, header, chunkSize).map { Chunk(title, rangeHeading("행", it.from, it.to), it.text) }
    }

    // ---- JSON

    /**
     * JSON 레코드 청킹. 원소 하나 = compact JSON 한 줄, 원소 묶음을 chunkSize 이하로 자른다.
     * - 최상위가 객체 배열 → heading `항목 a–b`
     * - 최상위 객체의 값 중 객체 배열이 있으면 → 그 키별로 `키 > 항목 a–b`, 나머지 키는 pretty JSON 으로 묶어 heading `기타`
     * 그 밖의 형태(스칼라 배열, 객체 배열이 없는 객체 등)면 null(호출부가 pretty JSON 일반 청킹).
     */
    fun jsonChunks(title: String, root: JsonNode, chunkSize: Int): List<Chunk>? {
        if (isRecordArray(root)) return recordChunks(title, null, root, chunkSize)
        if (!root.isObject) return null
        val props = root.properties().toList()
        if (props.none { isRecordArray(it.value) }) return null

        val result = mutableListOf<Chunk>()
        val rest = JSON.createObjectNode()
        for ((key, value) in props) {
            if (isRecordArray(value)) result += recordChunks(title, key, value, chunkSize)
            else rest.set<JsonNode>(key, value)
        }
        if (rest.size() > 0) {
            val pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rest)
            splitLines(pretty.lines(), chunkSize).forEach { result += Chunk(title, JSON_REST_HEADING, it) }
        }
        return result
    }

    private fun isRecordArray(node: JsonNode): Boolean =
        node.isArray && node.size() > 0 && node.elements().asSequence().all { it.isObject }

    private fun recordChunks(title: String, key: String?, array: JsonNode, chunkSize: Int): List<Chunk> {
        val records = array.elements().asSequence().map { JSON.writeValueAsString(it) }.toList()
        return packRecords(records, null, chunkSize).map {
            val range = rangeHeading("항목", it.from, it.to)
            Chunk(title, if (key == null) range else "$key > $range", it.text)
        }
    }

    // ---- 공통 묶기

    /**
     * 한 줄짜리 레코드들을 prefix(예: CSV 헤더) 아래에 chunkSize 이하로 묶는다.
     * 레코드 하나만으로 chunkSize 를 넘으면 그 레코드만 한 묶음이다(자르지 않음).
     */
    fun packRecords(records: List<String>, prefix: String?, chunkSize: Int): List<Packed> {
        val out = mutableListOf<Packed>()
        val sb = StringBuilder()
        var start = 0
        var count = 0
        fun reset() { sb.clear(); if (prefix != null) sb.append(prefix) }
        reset()
        records.forEachIndexed { i, r ->
            val sep = if (sb.isEmpty()) 0 else 1
            if (count > 0 && sb.length + sep + r.length > chunkSize) {
                out += Packed(start + 1, i, sb.toString())
                reset(); start = i; count = 0
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(r)
            count++
        }
        if (count > 0) out += Packed(start + 1, records.size, sb.toString())
        return out
    }

    /** `행 21–40`, 한 행이면 `행 7`. */
    fun rangeHeading(label: String, from: Int, to: Int): String = if (from == to) "$label $from" else "$label $from$RANGE_DASH$to"

    // ---- 긴 단락·마크다운 표

    fun isTableRow(line: String): Boolean = line.trimStart().startsWith("|")

    fun isTableSeparator(line: String): Boolean = TABLE_SEPARATOR.matches(line)

    /**
     * 줄 목록을 chunkSize 이하 묶음으로 나눈다(줄 단위, 문장은 자르지 않음). 한 줄이 chunkSize 보다 길면 그 줄만 한 묶음.
     * 마크다운 표(`|` 로 시작하는 연속 줄, 두 번째 줄이 `|---|` 구분선)가 묶음 경계에서 나뉘면
     * 다음 묶음 맨 위에 헤더 줄과 구분선을 다시 넣는다. 표가 시작될 때는 헤더·구분선·첫 행이 한 묶음에 들어가게 한다.
     */
    fun splitLines(lines: List<String>, chunkSize: Int): List<String> {
        val out = mutableListOf<String>()
        val cur = mutableListOf<String>()
        var curLen = 0
        var repeated = 0 // cur 맨 앞에 다시 넣은 표 헤더 줄 수(이것만 남았으면 내보내지 않음)
        var tableHead: List<String>? = null // 지금 지나는 표의 [헤더, 구분선]

        fun add(line: String) { curLen += if (cur.isEmpty()) line.length else line.length + 1; cur += line }
        fun fits(extra: Int) = cur.isEmpty() || curLen + extra <= chunkSize
        fun flush() {
            if (cur.size > repeated) out += cur.joinToString("\n")
            cur.clear(); curLen = 0; repeated = 0
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (tableHead == null && isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                val head = listOf(line, lines[i + 1])
                val first = lines.getOrNull(i + 2)?.takeIf { isTableRow(it) }
                if (!fits(head.sumOf { it.length + 1 } + (first?.let { it.length + 1 } ?: 0))) flush()
                head.forEach(::add)
                tableHead = head
                i += 2
                continue
            }
            if (tableHead != null && !isTableRow(line)) tableHead = null
            if (!fits(line.length + 1)) {
                flush()
                tableHead?.let { head -> head.forEach(::add); repeated = head.size }
            }
            add(line)
            i++
        }
        flush()
        return out
    }
}
