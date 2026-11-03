package dev.jinseop.agentkit.chunk

/**
 * 청크 하나. title 은 문서 식별자, heading 은 청크에서 가장 가까운 헤딩(없으면 null),
 * text 는 청크 본문. path 는 "title > heading"(헤딩이 없으면 title) 형태의 표시용 경로.
 */
data class Chunk(val title: String, val heading: String?, val text: String) {
    val path: String get() = if (heading == null) title else "$title > $heading"
}
