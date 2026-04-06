# microservice-gateway (QuietChatter API Gateway)

이 저장소는 QuietChatter 프로젝트의 API Gateway 서비스입니다.
JDK 21의 **가상 스레드(Virtual Threads)**와 **Spring Cloud Gateway MVC**를 기반으로 구축되어, 높은 처리 성능과 직관적인 동기식 코드를 동시에 제공합니다.

## 핵심 아키텍처 및 기술 스택

- **언어**: Kotlin 1.9.25
- **프레임워크**: Spring Boot 3.5.13, Spring Cloud Gateway MVC
- **런타임**: JDK 21 (Virtual Threads 활성화)
- **서비스 탐색 및 설정**: HashiCorp Consul
- **데이터 저장소**: Redis (JWT Refresh Token 관리)
- **인증**: 쿠키 및 헤더 기반 JWT 검증

## 주요 기능

1. **동적 라우팅**: Consul에 등록된 마이크로서비스들의 상태를 실시간으로 확인하여 `lb://` 프로토콜을 통한 로드밸런싱을 수행합니다.
2. **가상 스레드 기반 인증 필터**: 모든 요청에 대해 JWT 유효성을 검사하며, Access Token 만료 시 Refresh Token을 사용해 자동으로 토큰을 갱신합니다.
3. **보안**: 외부에서 유입되는 `X-Member-Id` 헤더를 차단하고, 인증된 사용자에게만 내부용 식별자 헤더를 추가하여 전달합니다.
4. **로컬 개발 지원**: `spring-boot-docker-compose`를 통해 로컬 실행 시 Consul과 Redis가 자동으로 구동됩니다.

## 로컬 실행 방법

1. **사전 요구 사항**: Docker 및 JDK 21 설치
2. **애플리케이션 실행**:
   ```bash
   ./gradlew bootRun
   ```
   - 실행 시 `compose.yaml` 설정에 따라 필요한 인프라(Consul, Redis)가 자동으로 시작됩니다.
3. **확인**:
   - Gateway API: http://localhost:8080
   - Consul 대시보드: http://localhost:8500

## 문서 정보

- [구현 명세서 (spec.md)](./docs/spec.md): 상세 라우팅 규칙 및 인증 흐름
- [작업 태스크 (v2.0.0-task.md)](./docs/tasks/v2.0.0-task.md): 현재 스프린트 진행 현황
