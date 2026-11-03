package dev.jinseop.agentkit.text

/**
 * 모델 최종 답변 텍스트에 섞여 나온 추론 블록(`<thinking>…</thinking>`, `<thinking_mode>…`, `<thinking>…` 등)을 제거하는 방어 필터.
 * 실제 호출에서 열림 태그가 깨진 채(`öthinking_mode>…</thinking_mode>`) 영어 추론 문단이 답변 앞에 붙어 나온 적이 있다.
 *
 * 규칙(대소문자 무시):
 *  1. 열림…닫힘 쌍으로 닫힌 블록은 통째로 제거(여러 개 가능).
 *  2. 짝 없는 닫는 태그가 남으면(열림 태그가 깨진 경우) 텍스트 시작부터 마지막 닫는 태그까지 제거.
 *  3. 짝 없는 열림 태그가 남으면 그 태그부터 끝까지 제거.
 *  4. 앞뒤 공백 제거, 3개 이상 연속 개행 → 2개.
 *  5. 결과가 비면 원문에서 태그만 지운 텍스트를 돌려준다(답변 전체를 날리지 않기 위한 안전장치).
 * 태그가 하나도 없으면 원문을 그대로 돌려준다(정상 응답의 빈 줄 등은 건드리지 않음).
 */
object ResponseSanitizer {
    /** 태그 이름에 "thinking" 이 들어간 것 전부: thinking, thinking_mode, antml:thinking, 속성 포함 등. 한 줄 안에서만. */
    private const val NAME = "[a-z0-9_.:-]*thinking[^<>\\n]*"
    private val OPEN = Regex("<$NAME>", RegexOption.IGNORE_CASE)
    private val CLOSE = Regex("</$NAME>", RegexOption.IGNORE_CASE)
    private val BLOCK = Regex("<$NAME>.*?</$NAME>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    /** 안전장치(5)에서만 쓰는, 텍스트 맨 앞의 깨진 열림 태그(`öthinking_mode>`). */
    private val BROKEN_OPEN_AT_START = Regex("^\\S?[a-z0-9_.:-]*thinking[^<>\\s]*>", RegexOption.IGNORE_CASE)
    private val EXTRA_BLANK_LINES = Regex("\\n(?:[ \\t\\r]*\\n){2,}")

    fun strip(text: String): String {
        if (!OPEN.containsMatchIn(text) && !CLOSE.containsMatchIn(text)) return text

        // 1. 닫힌 블록 제거. 이후 남은 닫는 태그는 모두 남은 열림 태그보다 앞에 있다(뒤에 닫는 태그가 있었다면 1 에서 짝지어졌을 것).
        var s = BLOCK.replace(text, "")
        // 2. 짝 없는 닫는 태그: 시작 ~ 마지막 닫는 태그까지 제거
        CLOSE.findAll(s).lastOrNull()?.let { s = s.substring(it.range.last + 1) }
        // 3. 짝 없는 열림 태그: 그 태그 ~ 끝까지 제거
        OPEN.find(s)?.let { s = s.substring(0, it.range.first) }

        val out = tidy(s)
        if (out.isNotEmpty()) return out

        // 5. 전부 추론 블록이었다면 태그만 지운 본문을 돌려준다
        val tagsOnly = CLOSE.replace(OPEN.replace(text, ""), "").trim()
        return tidy(BROKEN_OPEN_AT_START.replace(tagsOnly, ""))
    }

    private fun tidy(s: String): String = EXTRA_BLANK_LINES.replace(s, "\n\n").trim()

    /**
     * 툴 호출 마크업의 구조 표지: `<invoke …>`·`</invoke>`·`<parameter …>`·`<function_calls>` 와 네임스페이스 접두가 붙은 형태.
     * 실제 호출에서 모델이 툴을 부르는 대신 `<invoke name="set_quick_replies">…` 를 답 텍스트로 max_tokens 까지 반복한 적이 있다(실제 호출 관찰).
     */
    private val TOOL_CALL_MARKER = Regex("</?(?:\\w+:)?(?:invoke|function_calls|parameter)\\b")

    /** 텍스트에 툴 호출 마크업이 섞였는지. "3 < 5" 같은 일반 부등호·다른 태그는 해당하지 않는다. */
    fun hasToolCallLeak(text: String): Boolean = TOOL_CALL_MARKER.containsMatchIn(text)

    /** 첫 툴 호출 표지 앞까지만 남기고 trim 한다(누출은 보통 max_tokens 까지 이어져 그 뒤는 전부 쓰레기). 표지가 없으면 원문 그대로. */
    fun stripToolCallLeak(text: String): String =
        TOOL_CALL_MARKER.find(text)?.let { text.substring(0, it.range.first).trim() } ?: text
}
