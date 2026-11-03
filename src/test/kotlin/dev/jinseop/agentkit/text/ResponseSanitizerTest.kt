package dev.jinseop.agentkit.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponseSanitizerTest {
    private fun strip(s: String) = ResponseSanitizer.strip(s)

    @Test
    fun `normal text is returned unchanged`() {
        for (s in listOf(
            "",
            "안녕하세요! 오늘 날씨는 맑아요.",
            "첫 문단\n\n\n\n둘째 문단", // 태그가 없으면 빈 줄도 건드리지 않는다
            "I was thinking about it > maybe a < b",
            "<b>굵게</b> 그리고 <thinker> 는 다른 태그",
            "  앞뒤 공백도 그대로  ",
        )) assertEquals(s, strip(s))
    }

    @Test
    fun `closed blocks are removed at front, middle and back`() {
        assertEquals("답변입니다.", strip("<thinking>사용자가 묻는 것은...</thinking>\n답변입니다."))
        assertEquals("앞 문단\n\n뒤 문단", strip("앞 문단\n\n<thinking>\n중간 추론\n</thinking>\n\n뒤 문단"))
        assertEquals("답변입니다.", strip("답변입니다.\n<thinking>끝에 붙은 추론</thinking>"))
        assertEquals("A\n\nB\n\nC", strip("<thinking>1</thinking>A\n\n<thinking_mode>2</thinking_mode>\n\nB\n\n<thinking>3</thinking>\n\nC"))
    }

    @Test
    fun `broken opening tag removes everything up to the closing tag`() {
        val leaked = "öthinking_mode>\nThe user asked how many won ... so the answer is 3,000.\n</thinking_mode>\n답변"
        assertEquals("답변", strip(leaked))
        assertEquals("3,000원이에요.\n\n더 궁금한 게 있나요?", strip("hinking>reasoning</thinking>\n\n3,000원이에요.\n\n\n\n더 궁금한 게 있나요?"))
    }

    @Test
    fun `opening tag without closing tag drops the tail`() {
        assertEquals("답변입니다.", strip("답변입니다.\n\n<thinking>그런데 다시 생각해 보면"))
        assertEquals("답변", strip("öthinking_mode>추론</thinking_mode>\n답변\n<thinking>또 추론"))
    }

    @Test
    fun `all-thinking text falls back to body with tags removed`() {
        assertEquals("본문만 있음", strip("<thinking>본문만 있음</thinking>"))
        assertEquals("시작만", strip("<thinking>시작만"))
        assertEquals("추론뿐", strip("öthinking_mode>추론뿐</thinking_mode>"))
        assertEquals("A\n\nB", strip("<thinking>A</thinking>\n\n\n\n<thinking>B</thinking>"))
        assertEquals("", strip("<thinking></thinking>")) // 호출부가 "(응답이 비어 있어요)" 로 대체
    }

    @Test
    fun `case and tag name variants`() {
        assertEquals("답", strip("<THINKING>x</THINKING>답"))
        assertEquals("답", strip("<Thinking_Mode>x</thinking_MODE>답"))
        assertEquals("답", strip("<thinking>x</thinking>답"))
        assertEquals("답", strip("<thinking type=\"internal\">x\ny</thinking>답"))
        assertEquals("답", strip("<redacted_thinking>x</redacted_thinking>답"))
        assertEquals("답", strip("x</THINKING>답"))
    }

    // ---- 툴 호출 마크업 누출

    @Test
    fun `tool call markup is detected with and without namespace prefix`() {
        for (s in listOf(
            "<invoke name=\"set_quick_replies\">",
            "</invoke>",
            "<parameter name=\"items\">[]</parameter>",
            "<function_calls>",
            "앞 문장\n<abc:invoke name=\"x\">",
            "</abc:function_calls>",
            "<ns:parameter name=\"items\">",
        )) assertTrue(ResponseSanitizer.hasToolCallLeak(s), s)
    }

    @Test
    fun `ordinary angle brackets and other tags are not a leak`() {
        for (s in listOf(
            "3 < 5 이고 7 > 2 예요.",
            "<b>굵게</b> 그리고 <thinker> 는 다른 태그",
            "invoke 라는 단어와 parameter 라는 단어",
            "<invoker> <parameters> <function_calls_x>",
            "",
        )) assertFalse(ResponseSanitizer.hasToolCallLeak(s), s)
    }

    @Test
    fun `strip keeps the text before the first marker`() {
        val leaked = "치과 예약을 메모했어요.\n\n<invoke name=\"set_quick_replies\">\n<parameter name=\"items\">[]</parameter>\n</invoke>\n<invoke name=\"set_quick_replies\">"
        assertEquals("치과 예약을 메모했어요.", ResponseSanitizer.stripToolCallLeak(leaked))
        assertEquals("앞", ResponseSanitizer.stripToolCallLeak("앞 <abc:parameter name=\"x\">뒤"))
        assertEquals("", ResponseSanitizer.stripToolCallLeak("<invoke name=\"x\">\n<parameter name=\"items\">[]</parameter>"))
        assertEquals("  누출 없음  ", ResponseSanitizer.stripToolCallLeak("  누출 없음  ")) // 표지가 없으면 원문 그대로
    }

    @Test
    fun `blank lines are collapsed after stripping`() {
        assertEquals("하나\n\n둘\n\n셋", strip("하나\n\n\n<thinking>x</thinking>\n\n\n둘\n \n\t\n\n셋\n\n"))
        assertEquals("들여쓰기\n\n  - 항목", strip("<thinking>x</thinking>\n\n들여쓰기\n\n\n\n  - 항목"))
    }
}
