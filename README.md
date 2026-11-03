# agentkit

LLM 에이전트를 만들 때 자주 쓰는 **프레임워크 비의존 Kotlin 유틸리티 3종**을 모은 작은 라이브러리입니다.
특정 웹 프레임워크에 묶이지 않고 Kotlin stdlib + Jackson 2 + JDK HttpClient 만 씁니다.

- `dev.jinseop.agentkit.http.HttpFetcher` — 허용목록 기반 외부 GET 요청
- `dev.jinseop.agentkit.chunk.StructuredChunker` — 표 형태 자료 청킹
- `dev.jinseop.agentkit.text.ResponseSanitizer` — LLM 출력 정리

## HttpFetcher

모델 도구용 HTTP GET 처리기입니다. JDK `HttpClient` 로 요청하되 리다이렉트를 직접 처리해서, 3xx + `Location` 이 올 때마다 호출부가 넘긴 `resolveRedirect` 콜백에 **허용 여부를 다시 물어** 호스트 허용목록 밖으로는 따라가지 않습니다(최대 3회). 응답 바디는 지정한 바이트 상한까지만 읽어 큰 응답을 메모리에 다 올리지 않고, 글자 수 상한을 넘으면 뒤를 잘라 안내를 붙입니다. 문자셋은 `Content-Type` → (HTML 이면) `<meta charset>` → UTF-8 순으로 정하며 EUC-KR 계열은 상위 호환인 MS949(CP949)로 읽습니다. 콘텐츠 종류에 따라 JSON 은 공백을 없앤 compact 형태로, HTML 은 정규식만으로 제목·본문 텍스트를 뽑아, 일반 텍스트는 공백을 정리해 돌려줍니다. 비밀 쿼리 파라미터 이름을 지정하면 로그·에러 메시지에서 값을 `***` 로 가립니다. 비 2xx 응답은 예외 대신 `HTTP 404 Not Found` + 본문 앞부분 형태의 일반 출력으로 돌려, 모델이 URL 을 고칠 수 있게 합니다.

```kotlin
import dev.jinseop.agentkit.http.HttpFetcher
import dev.jinseop.agentkit.http.HttpTarget
import java.net.URI

val fetcher = HttpFetcher() // 기본: 5초 타임아웃, 1MB, 6000자
val allowed = setOf("api.example.com")
fun resolve(uri: URI): HttpTarget? = if (uri.host in allowed) HttpTarget(uri) else null

val text = fetcher.fetch(HttpTarget(URI("https://api.example.com/rates")), ::resolve)
```

## StructuredChunker

표 형태 자료를 "의미가 보이는 조각"으로 자르는 상태 없는 순수 함수 모음입니다. **CSV·TSV** 는 따옴표 필드(필드 안의 구분자·줄바꿈·`""` 이스케이프), CRLF, BOM 을 직접 처리하는 파서로 읽은 뒤, 헤더 줄을 매 조각 맨 위에 다시 넣으면서 연속한 데이터 행을 크기 상한까지 묶습니다(한 행이 상한보다 길면 그 행만 한 조각). **JSON 레코드** 는 최상위가 객체 배열이면 원소를 compact JSON 한 줄씩 묶고, 최상위 객체 안에 객체 배열이 있으면 그 키별로 묶은 뒤 나머지 키는 pretty JSON 으로 모읍니다. **마크다운 표** 가 조각 경계에서 나뉘면 다음 조각 맨 위에 헤더 줄과 구분선(`|---|`)을 다시 넣어 열 의미를 유지합니다. 각 조각은 `Chunk(title, heading, text)` 로 나오며 `heading` 은 `행 1–40`·`항목 1–20` 같은 범위 라벨입니다.

```kotlin
import dev.jinseop.agentkit.chunk.StructuredChunker

val csv = "이름,가격\n라테,5000\n아메리카노,4500"
val chunks = StructuredChunker.csvChunks("메뉴", csv, delimiter = ',', chunkSize = 800)
// chunks[*].text 는 "이름,가격\n…" 로 시작, heading 은 "행 1–2" 등
```

## ResponseSanitizer

LLM 최종 답변 텍스트에 섞여 나온 **추론 블록**(`<thinking>…</thinking>`, 깨진 열림 태그로 시작하는 영어 추론 문단 등)과 **툴 호출 마크업 누출**(`<invoke …>` / `<parameter …>` / `<function_calls>` 가 답 텍스트로 새어 나온 경우)을 걷어내는 방어 필터입니다. `strip` 은 닫힌 추론 블록을 통째로 지우고, 짝이 맞지 않는 태그(열림만·닫힘만 남은 경우)도 규칙에 따라 정리한 뒤 과한 빈 줄을 줄입니다. 결과가 통째로 비면 태그만 지운 본문을 돌려줘 답변 전체가 날아가지 않게 합니다. `hasToolCallLeak` 는 툴 호출 구조 표지가 섞였는지(일반 부등호 `3 < 5` 는 제외) 판별하고, `stripToolCallLeak` 는 첫 표지 앞까지만 남깁니다.

```kotlin
import dev.jinseop.agentkit.text.ResponseSanitizer

ResponseSanitizer.strip("<thinking>속으로 생각</thinking>\n안녕하세요!") // "안녕하세요!"
ResponseSanitizer.hasToolCallLeak("메모했어요 <invoke name=\"x\">")      // true
ResponseSanitizer.stripToolCallLeak("메모했어요\n<invoke name=\"x\">")   // "메모했어요"
```

## 빌드

```bash
./gradlew test       # 또는 Windows: .\gradlew.bat test
./gradlew build
```

- Kotlin/JVM, Java 21 toolchain, Gradle wrapper 포함.
- 의존: Jackson 2 (`jackson-databind`), SLF4J API. 테스트: JUnit 5 + kotlin-test.

## 라이선스

MIT License. 자세한 내용은 [LICENSE](LICENSE) 를 참고하세요.
