# microservice-api-gateway

QuietChatter 프로젝트의 API Gateway 서비스. 모든 외부 HTTP 요청의 단일 진입점으로 JWT 인증, 라우팅, CORS를 처리한다.

## 기술 스택

- 언어: Kotlin 1.9.25
- 프레임워크: Spring Boot 3.5.13, Spring Cloud Gateway MVC (Servlet 기반)
- 런타임: JDK 21 Virtual Threads 활성화
- 데이터 저장소: Redis (Refresh Token 관리)
- 포트: 8080

## 환경 변수 및 보안

모든 민감 정보는 k8s Secret(quietchatter-secrets)으로부터 환경 변수로 주입됩니다.

| 변수명 | 설명 | 비고 |
|---|---|---|
| JWT_SECRET_KEY | JWT 서명 및 검증용 비밀키 | |
| INTERNAL_SECRET | 서비스 간 통신용 공유 비밀키 | |
| MEMBER_SERVICE_URL | 회원 서비스 접속 URL | |
| BOOK_SERVICE_URL | 도서 서비스 접속 URL | |
| TALK_SERVICE_URL | 북톡 서비스 접속 URL | |
| SPRING_DATA_REDIS_HOST | Redis 호스트 주소 | |
| SPRING_DATA_REDIS_PORT | Redis 포트 번호 | |
| SPRING_PROFILES_ACTIVE | 활성 프로파일 | prod |

## 패키지 구조

```text
com.quietchatter.gateway/
  AppCorsProperties.kt
  AuthenticationFilter.kt
  CorsConfig.kt
  GatewayApplication.kt
  GatewayHeaderRequestWrapper.kt
  JwtTokenService.kt
  adaptor/in/web/       OpenApiController.kt
  application/          OpenApiAggregatorService.kt
```

## API 명세

| 경로 | 설명 |
|---|---|
| /api/docs/openapi.yaml | 여러 다운스트림 서비스(member, book, talk)의 OpenAPI 명세를 하나로 취합하여 반환 |

## 라우팅 규칙

라우팅은 k8s Service URL 환경변수 기반 정적 설정이다. application.yml에서 관리한다.

| 경로 패턴 | 대상 서비스 | 환경변수 |
|---|---|---|
| /api/auth/** | microservice-member | MEMBER_SERVICE_URL |
| /api/members/** | microservice-member | MEMBER_SERVICE_URL |
| /api/support/** | microservice-member | MEMBER_SERVICE_URL |
| /api/books/** | microservice-book | BOOK_SERVICE_URL |
| /api/talks/**, /api/reactions/** | microservice-talk | TALK_SERVICE_URL |

## 인증 정책

AuthenticationFilter(OncePerRequestFilter)가 모든 요청을 검사한다.

- Forbidden (외부 차단): /internal/** → 403. 서비스 간 내부 통신 전용 경로.
- 토큰 있음: X-Member-Id 헤더에 memberId(UUID)를 담아 다운스트림으로 전달.
- 토큰 없음: X-Member-Id 헤더를 전송하지 않음. 빈 문자열이 아닌 헤더 미포함.
- 토큰 만료: Refresh Token으로 갱신 후 통과. 갱신 불가(Redis TTL 만료 포함) 시 만료 쿠키 클리어 후 어나니머스로 통과.
- 토큰 무효: 401.

인증 필요 여부의 판단은 게이트웨이가 아닌 각 다운스트림 서비스가 담당한다. 인증 필수 엔드포인트는 X-Member-Id 헤더가 없을 때 GlobalExceptionHandler에서 401을 반환한다.

## 인증 흐름

1. 외부에서 유입된 X-Member-Id 헤더 강제 제거 (헤더 인젝션 방지)
2. ACCESS_TOKEN 쿠키 확인 후 없으면 Authorization: Bearer 헤더 확인
3. Access Token 유효: X-Member-Id에 memberId를 담아 다운스트림으로 전달
4. Access Token 없음: X-Member-Id 헤더 없이 다운스트림으로 전달 (어나니머스)
5. Access Token 만료: 멤버 서비스 POST /internal/auth/refresh 호출. 성공 시 신규 토큰 발급 및 쿠키 재발급
6. 갱신 토큰 없음 또는 세션 완전 만료(멤버 서비스 401 반환): 만료 쿠키 클리어 후 어나니머스로 통과
7. 토큰 서명 무효: JSON 에러 응답 (401 UNAUTHORIZED)

쿠키 속성은 COOKIE_DOMAIN / COOKIE_SECURE 환경변수로 제어한다(app.cookie.* 설정). 로컬 기본값은 domain 미설정, secure=false.

게이트웨이는 JWT 검증만 담당하며 Redis에 의존하지 않는다. 토큰 생명주기(발급·갱신·폐기)는 멤버 서비스가 단독 소유한다.

에러 응답 형식:

- 표준 에러: RFC 7807 (ProblemDetail) 형식을 따릅니다.
- 응답 예시:
```json
{
  "type": "about:blank",
  "title": "UNAUTHORIZED",
  "status": 401,
  "detail": "유효하지 않은 토큰입니다.",
  "instance": "/api/auth/me"
}
```

에러 코드 (title 필드): UNAUTHORIZED (토큰 서명/형식 무효), FORBIDDEN (내부 경로 접근)

## 로컬 실행

사전 요구 사항: Docker, JDK 21

```bash
./gradlew bootRun
```

로컬 실행 시 compose.yaml로 Redis가 자동 구동된다. 환경변수 미설정 시 application.yml의 기본값(localhost:808x)으로 동작한다.
