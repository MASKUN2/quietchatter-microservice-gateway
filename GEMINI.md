# AI Agent Guide - microservice-api-gateway

이 문서는 AI 에이전트가 microservice-api-gateway 프로젝트를 이해하고 개발을 돕기 위한 지침입니다.

## 1. 서비스 개요

- 역할: 모든 외부 HTTP 요청의 단일 진입점(API Gateway)
- 담당 레거시 기능: security 패키지의 JWT 검증 및 필터 로직
- 포트: 8080

## 2. 기술 스택

- 언어: Kotlin 1.9.x
- 프레임워크: Spring Boot 3.5.13, Spring Cloud Gateway (Reactive, WebFlux 기반)
- 의존성: spring-cloud-starter-gateway, consul-discovery, consul-config, jjwt
- 비동기: kotlinx-coroutines-reactor

## 3. 아키텍처

Spring Cloud Gateway는 필터 체인 기반으로 동작합니다.

```
외부 요청
  -> GlobalFilter (JWT 검증, X-Member-Id 주입)
  -> RouteLocator (Consul 기반 서비스 탐색 및 라우팅)
  -> 다운스트림 마이크로서비스
```

## 4. 에이전트 작업 지침

모든 작업 시작 전 및 작업 중에 superpowers 스킬 목록을 항상 확인하고 상황에 맞는 스킬을 활성화하여 사용하십시오.

### A. 공통 원칙

- 모든 서비스는 헥사고날 아키텍처를 따르며, 어댑터 패키지 명칭은 adaptor로 통일합니다.
- 마이크로서비스 환경에서 각 모듈의 독립성과 확장성을 고려하여 설계하십시오.

### B. 코드 작성 규칙

- Kotlin idiomatic 코드를 작성하십시오. Lombok은 사용하지 않습니다.
- Spring WebFlux 환경이므로 블로킹 코드를 작성하지 마십시오.
- JWT 검증 로직은 레거시의 security/adaptor/AuthFilter.java와 AuthTokenService.java를 참고하여 포팅하십시오.
- 새로운 코드를 작성하거나 수정할 때마다 반드시 단위 테스트(Unit Test)를 함께 작성하고 통과를 확인하십시오.

### C. 라우팅 규칙

- 모든 라우팅은 Consul 서비스 탐색을 통해 동적으로 이루어집니다.
- 정적 라우팅 설정보다 Consul LoadBalancer를 우선 사용하십시오.
- 인증이 필요 없는 경로(예: /v1/auth/**)는 JWT 검증을 건너뛰도록 설정하십시오.

### D. 보안 규칙

- 다운스트림 서비스는 Gateway를 통해서만 접근 가능해야 합니다.
- JWT 검증 성공 시 X-Member-Id 헤더에 회원 ID(UUID)를 추가합니다.
- 다운스트림 서비스에서 발생한 X-Member-Id 헤더를 외부에서 직접 주입하는 요청은 차단하십시오.

### E. 문서 규칙

- 마크다운 작성 시 굵게(bold)나 기울임(italics) 같은 강조 서식을 사용하지 않습니다.
- 마크다운 작성 시 이모티콘을 사용하지 않습니다.

## 5. 구현 스펙 참조

[docs/spec.md](./docs/spec.md)를 반드시 읽고 작업을 시작하십시오.
