# LibertyWiki

MediaWiki 1.46.2의 코어·기본 배포 확장 기능을 독립 구현하는 AGPL-3.0-only 위키 프로젝트입니다. PostgreSQL 기반의 [내부 시험용 문서 생성·원문 편집·이력 및 원문 조회](wiki_design/source/15_PAGE_SOURCE_SLICE.md)를 실행할 수 있습니다. 제품 스킨은 라이트·다크 모드를 지원하는 [Liberty와 여백](wiki_design/source/03_EXTENSION_PARITY.md#3-제품-스킨-2종)으로 계획되어 있습니다. 정식 호환성과 출시 기준은 설계 문서에서 정의합니다.

현재 설계는 [문서 안내](wiki_design/source/00_README.md)에서 시작합니다. [제품 기획](wiki_design/source/01_PRODUCT_PLAN.md), [요구사항](wiki_design/source/02_REQUIREMENTS.md), [두 API 계약](wiki_design/source/14_DUAL_API_CONTRACT.md), [아키텍처](wiki_design/source/04_ARCHITECTURE.md), [결정 기록](docs/adr/), [용어집](GLOSSARY.md)을 순서대로 참고합니다.

2026-11-08에 확정한 [1.0 검증 프로파일](wiki_design/specs/release_profile_1_0.json)은 일반 문서 10만을 포함한 전체 문서 20만·등록 사용자 1만·DAU 100을 목표로 합니다. 리브레 위키는 이전 검증 대상이며 사이트의 모든 고유 기능이 구현 범위는 아닙니다. ENWIKI 인증과 실제 위키 전환 승인은 제품 출시 승인과 별도입니다.

LibertyWiki 실행물과 Docker 이미지는 ARM64·x86-64를 지원하도록 개발합니다. 외부 DBMS·OpenSearch·Redis 등 인프라의 실행환경은 프로젝트 실행환경과 독립적이며, 오케스트레이션은 사용자가 선택합니다.

저장소에는 설계문서·계약, 이슈트래커, 소스코드와 필요한 개발 설정만 보관합니다. 검증 결과·실행 로그·중간파일·생성된 통합본은 저장소에 추가하지 않습니다. 현재 결정은 `wiki_design/source/`, `wiki_design/specs/`, `docs/adr/`를 따릅니다.

## 내부 시험 환경 실행

JDK 21, Node.js 22.12 이상 및 UTF-8 PostgreSQL 17 데이터베이스를 준비합니다. 초기 테이블은 Flyway가 생성합니다. 아래 명령에서 연결 정보와 운영자 토큰을 시험 환경 값으로 바꿉니다.

```bash
npm ci --prefix web
npm run build --prefix web
export WIKI_DB_URL=jdbc:postgresql://127.0.0.1:5432/libertywiki
export WIKI_DB_USER=libertywiki
export WIKI_DB_PASSWORD='test-database-password'
export WIKI_HTTP_OPERATOR_TOKEN='replace-with-a-random-local-token'
./gradlew bootRun
```

[http://127.0.0.1:8080](http://127.0.0.1:8080)에서 운영자 토큰을 입력해 문서를 생성하거나 제목으로 조회한 뒤 원문을 편집합니다. 문서 화면의 이력에서 이전 리비전의 원문을 열거나, 부모가 있는 편집을 선택해 겹치지 않는 후속 변경을 유지하며 해당 편집을 취소할 수 있습니다. 충돌이 발생하면 작성 중인 원문과 요약을 유지한 상태에서 최신 문서를 확인할 수 있습니다. 기본값은 루프백 바인딩, 익명 읽기 허용, 익명 편집·생성 거부입니다. 내부 시험 정책은 `WIKI_ANONYMOUS_READ`, `WIKI_ANONYMOUS_EDIT`, `WIKI_ANONYMOUS_CREATE`로 설정합니다. 익명 편집에는 읽기와 편집을, 익명 생성에는 세 가지 정책을 모두 `true`로 설정해야 합니다. Windows PowerShell에서는 `export KEY=value` 대신 `$env:KEY = 'value'`, `./gradlew` 대신 `.\gradlew.bat`를 사용합니다.

## 검증 실행

```bash
./gradlew test check
export WIKI_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/libertywiki_test
export WIKI_TEST_DB_USER=libertywiki
export WIKI_TEST_DB_PASSWORD='test-database-password'
./gradlew postgresTest
```

`postgresTest`와 `mediaWikiTest`는 실행마다 임의의 PostgreSQL 스키마를 생성합니다. 스키마 생성 권한이 있는 계정과 폐기 가능한 시험 DB를 지정합니다. 차등 시험은 영어 첫 글자 대문자 규칙과 익명 읽기·편집·생성을 허용하는 별도의 MediaWiki 1.46.2 시험 인스턴스에 문서를 생성하고 편집합니다. 반복 실행에 필요한 편집 요청 한도는 시험 인스턴스의 [요청 한도 설정](https://www.mediawiki.org/wiki/Manual:$wgRateLimits)에서 조정합니다.

```bash
export WIKI_MW_BASE_URL=http://127.0.0.1:18763
export WIKI_MW_SCRIPT_PATH=/w
./gradlew mediaWikiTest
```

MediaWiki의 스크립트가 서버 루트에 있다면 `WIKI_MW_SCRIPT_PATH`를 빈 문자열로 지정합니다. 브라우저 시험은 기본 익명 정책으로 시작한 LibertyWiki 서버와 같은 운영자 토큰을 사용합니다.

```bash
npm exec --prefix web -- playwright install chromium
export WIKI_BROWSER_URL=http://127.0.0.1:8080
export WIKI_BROWSER_TOKEN="$WIKI_HTTP_OPERATOR_TOKEN"
npm test --prefix web
```

설치된 Chrome을 사용하는 경우 `WIKI_BROWSER_CHANNEL=chrome`을 지정합니다. 이력 화면에서 기준판과 대상판을 선택해 저장된 Wikitext의 [리비전 비교](wiki_design/source/15_PAGE_SOURCE_SLICE.md#저장된-리비전-비교)를 확인할 수 있습니다. 비교 차등 시험에는 MediaWiki 1.46.2와 Wikidiff2 1.14.2를 사용합니다. 시험 서버에서 `php --ri wikidiff2`로 설치 버전을 확인합니다.

프로젝트 소스는 [AGPL-3.0-only](LICENSE)를 따릅니다. [Gradle Wrapper](https://gradle.org/release-checksums/)와 [Spring Boot](https://github.com/spring-projects/spring-boot/blob/main/LICENSE.txt), [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)은 Apache-2.0, [PostgreSQL JDBC](https://github.com/pgjdbc/pgjdbc/blob/master/LICENSE)는 BSD-2-Clause, [React](https://github.com/facebook/react/blob/main/LICENSE)와 [Vite](https://github.com/vitejs/vite/blob/main/LICENSE)는 MIT 라이선스입니다.
