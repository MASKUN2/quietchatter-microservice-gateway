# microservice-gateway 구현 스펙 (Spring Cloud Gateway MVC)

## 1. 서비스 역할 및 아키텍처

QuietChatter 마이크로서비스 시스템의 관문으로, **JDK 21 가상 스레드**를 활용하여 동기식 코드의 직관성과 고성능 비동기 처리의 장점을 결합했습니다.

- **프레임워크**: Spring Cloud Gateway MVC (Servlet 기반)
- **핵심 역할**: JWT 통합 인증, 동적 라우팅, CORS 처리, 보안 헤더 제어

## 2. 라우팅 규칙 (Spring Cloud LoadBalancer 통합)

| 경로 패턴 | 대상 서비스 ID | 인증 정책 |
|---|---|---|
| `/v1/auth/**` | microservice-member | Bypass (인증 불필요) |
| `/v1/me/**` | microservice-member | Required (인증 필수) |
| `/v1/books/**` | microservice-book | Optional (인증 선택) |
| `/v1/talks/**` | microservice-talk | Optional (인증 선택) |
| `/v1/reactions/**` | microservice-talk | Required (인증 필수) |
| `/v1/customer/**` | microservice-customer | Bypass (인증 불필요) |

- **Bypass**: 필터에서 즉시 통과
- **Required**: 유효한 토큰 필수 (없을 시 401)
- **Optional**: 토큰이 있으면 인증 수행 후 `X-Member-Id` 추가, 없어도 통과

## 3. 인증 처리 흐름 (Virtual Threads 기반)

가상 스레드 환경에서 동작하는 **OncePerRequestFilter**를 통해 다음 과정을 순차적으로 수행합니다.

1. **보안 필터링**: 외부에서 유입된 `X-Member-Id` 헤더를 강제 제거하여 헤더 인젝션 공격 방지
2. **토큰 추출**: 쿠키(`ACCESS_TOKEN`) 확인 후 없을 시 `Authorization: Bearer` 헤더 확인
3. **토큰 검증**:
   - **유효**: `X-Member-Id`에 회원 식별자를 담아 다운스트림 서비스로 전달
   - **만료**: `REFRESH_TOKEN` 쿠키를 확인하여 Redis와 대조 후 새 토큰 발급 및 자동 갱신
   - **무효**: 공통 에러 응답 규격(JSON)으로 401 반환

## 4. 에러 응답 규격 (GatewayErrorResponse)

모든 인증 실패 응답은 일관된 형식을 유지합니다.

```json
{
  "code": "UNAUTHORIZED",
  "message": "인증이 필요합니다."
}
```

- **UNAUTHORIZED**: 토큰 누락 또는 유효하지 않음
- **TOKEN_EXPIRED**: 토큰 및 갱신 토큰 모두 만료

## 5. 인프라 연동 설정 (Consul & Redis)

- **Consul**: 실시간 서비스 인벤토리 정보를 기반으로 `lb://` 라우팅 수행
- **Redis**: 리프레시 토큰의 영속성을 보장하고 멀티 노드 게이트웨이 환경에서도 상태 공유
- **Caffeine Cache**: 서비스 목록 조회 성능 향상을 위해 로컬 캐싱 적용
