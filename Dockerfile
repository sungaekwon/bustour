# =====================================================================
# Dockerfile : 부산한바퀴(bustour) 앱 이미지 만들기 (10단계)
# [추가]2026.11.13권성애
#
# ★ 멀티 스테이지 빌드 = FROM 이 두 번 나온다
#   1단(build)  : JDK + Gradle 로 소스를 컴파일해서 jar 를 만든다 (무거움, 약 500MB)
#   2단(실행)    : JRE 만 있는 가벼운 이미지에 jar 하나만 복사한다
#   → 최종 이미지에는 소스 · Gradle · JDK 가 남지 않는다 (작고 안전함)
#
# ★ 누가 이 파일을 쓰나?
#   GitHub Actions(⑦단계)가 docker build → ghcr.io 에 push
#   서버(Lightsail)는 빌드하지 않고 pull 만 한다 (2GB 서버에서 Gradle 빌드는 느리고 메모리 부족 위험)
#
# ★ 내 PC 에서 시험해 보기 (Docker Desktop, 프로젝트 폴더에서)
#   docker build -t bustour:local .
#   ※ 맨 끝의 점(.) = "지금 폴더를 재료로 쓴다" (빌드 컨텍스트). .dockerignore 에 적은 것은 안 보냄
# =====================================================================


# ---------------------------------------------------------------------
# 1단: 빌드용 (JDK 21)
#   eclipse-temurin = 이클립스 재단이 배포하는 OpenJDK (가장 많이 쓰는 공식 자바 이미지)
#   AS build = 이 단계에 "build" 라는 이름을 붙임 → 2단에서 --from=build 로 파일을 꺼내 옴
# ---------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build

# 컨테이너 안에서 작업할 폴더 (없으면 만들어지고, 이후 명령은 이 폴더에서 실행)
WORKDIR /workspace

# (1) Gradle 설정 파일만 먼저 복사
#   ★ 도커는 명령 한 줄마다 "층(layer)"을 만들고, 재료가 안 바뀌면 지난번 층을 재사용(캐시)한다.
#     build.gradle 이 안 바뀌었으면 아래 라이브러리 다운로드 층을 다시 하지 않음 → 빌드가 빨라짐
#     src 를 먼저 복사하면 자바 파일 한 줄만 고쳐도 라이브러리를 매번 다시 받는다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle

# (2) gradlew 실행 권한 + 줄바꿈 정리
#   윈도우에서 복사된 gradlew 는 줄 끝이 CRLF(\r\n)일 수 있다 → 리눅스에서 "/bin/sh^M: not found" 오류
#   sed 로 \r 을 지워 LF 로 만든다 (.gitattributes 가 이미 LF 로 맞추지만 혹시 몰라 한 번 더)
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# (3) 라이브러리만 미리 받기 (캐시용 층)
#   --no-daemon : 한 번 쓰고 끝나는 빌드라 Gradle 데몬(상주 프로세스)을 띄우지 않음
#   || true     : 여기서 실패해도 다음 단계 bootJar 에서 다시 받으니 멈추지 않게
RUN ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

# (4) 소스 복사 후 실행 가능한 jar 만들기
#   bootJar  : 스프링 부트 실행 jar (내장 톰캣 + 라이브러리 전부 포함) → build/libs/bustour-0.0.1-SNAPSHOT.jar
#   -x test  : JUnit 은 건너뜀 (테스트는 bustour_test DB 가 필요한데 빌드 컨테이너에는 MySQL 이 없음)
COPY src src
RUN ./gradlew bootJar -x test --no-daemon


# ---------------------------------------------------------------------
# 2단: 실행용 (JRE 21 만)
#   jre = 실행만 가능 (javac 같은 컴파일 도구 없음) → 이미지가 작아짐
# ---------------------------------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app

# 컨테이너 시간대 = 한국 (로그에 찍히는 시각이 한국 시각이 됨)
#   ※ 앱의 "지금"은 이미 TimeConfig 의 Clock(Asia/Seoul)이 정하므로 기능에는 영향 없음 — 로그 보기 편하라고
ENV TZ=Asia/Seoul

# root 가 아닌 사용자로 실행 (앱이 털려도 컨테이너 안에서 할 수 있는 일이 줄어듦)
#   -r : 시스템 계정 (로그인용이 아님)
RUN useradd -r -s /usr/sbin/nologin spring

# 1단(build)에서 만든 jar 만 꺼내 와서 app.jar 라는 이름으로 저장
#   bootJar 만 실행했으므로 build/libs 에는 jar 가 하나뿐 (-plain.jar 는 안 생김)
COPY --from=build /workspace/build/libs/*.jar app.jar

# 여기서부터 spring 사용자로 실행
USER spring

# 앱이 쓰는 포트 "안내"(문서 역할). 실제로 밖에 여는 것은 docker-compose.yml 이 정함
EXPOSE 8282

# JVM 옵션 (docker-compose.yml 의 environment 에서 JAVA_OPTS 로 덮어쓸 수 있음)
#   -Xms256m -Xmx512m : 힙 메모리 시작 256MB, 최대 512MB
#     → 2GB 서버에 MySQL · Nginx 가 같이 있으므로 자바가 메모리를 다 먹지 않게 상한을 둔다
#   -Duser.timezone   : 자바 기본 시간대도 한국으로 (TZ 와 같은 목적, 확실하게 한 번 더)
ENV JAVA_OPTS="-Xms256m -Xmx512m -Duser.timezone=Asia/Seoul"

# 컨테이너가 시작될 때 실행할 명령
#   sh -c "..."  : 셸을 거쳐야 $JAVA_OPTS 같은 환경변수가 풀린다
#   exec         : 셸 대신 java 가 1번 프로세스가 됨 → docker stop 신호(SIGTERM)를 자바가 직접 받아
#                  스프링이 정상 종료(진행 중 요청 마무리, 커넥션 풀 닫기)를 할 수 있다
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
