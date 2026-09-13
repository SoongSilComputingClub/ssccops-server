FROM eclipse-temurin:17-jdk AS builder

WORKDIR /app

# Gradle wrapper 복사
COPY gradlew .
COPY gradle gradle
RUN chmod +x gradlew

# 의존성 캐싱을 위해 build 파일 먼저 복사
COPY build.gradle settings.gradle ./
COPY config config
RUN ./gradlew dependencies --no-daemon || true

# 소스 복사 및 빌드.
#
# `.git` 은 복사하지 않는다 (#422). #413 이 git.properties(/actuator/info 의 git sha — deploy-history.yml 이
# «정말 이 커밋이 떠 있는가»를 확인하는 값, ssccops#340)를 위해 `COPY .git .git` 을 넣었는데 **Coolify 의
# 빌드 컨텍스트에는 `.git` 이 없어** 그 줄에서 빌드가 죽었고 dev 배포가 5건 연속 실패했다(COPY 는
# 없는 경로를 건너뛰지 못한다). 대신 Coolify 가 빌드 인자로 넣는 SOURCE_COMMIT 을 ARG 로 받아 RUN 의
# 환경으로 넘기면 build.gradle(generateGitProperties)이 그 값으로 git.commit.id 를 쓴다. 그 인자는 Coolify
# 앱 설정 «Source commit availability = Available during build» 일 때만 온다(#424 · 기본값은 캐시 보존을 위해
# 빌드에서 뺀다) — 로컬
# `docker compose` 처럼 그 인자도 없으면 git 정보 없이 뜨고 레코드는 unverified 가 된다(부팅은 막지
# 않는다). git.branch · git.commit.time 은 이 경로에서 나오지 않는다 — sha 하나면 확인에는 충분하다.
COPY src src
ARG SOURCE_COMMIT
RUN ./gradlew bootJar -x checkstyleMain -x checkstyleTest -x spotlessCheck --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app

# 이름이 고정돼 있다 — build.gradle 의 bootJar.archiveFileName 이 'app.jar' 로 못 박는다.
# 예전에는 `*-SNAPSHOT.jar` 글롭이었는데, 그러면 버전에서 -SNAPSHOT 을 떼는 순간 맞는 파일이
# 없어 이 줄에서 빌드가 죽는다. 버전은 /actuator/info 와 git 태그가 말하므로 파일명이 그것을
# 또 말할 이유가 없다.
COPY --from=builder /app/build/libs/app.jar app.jar

EXPOSE 8080

# JVM 메모리 플래그를 두지 않는다 — 기본값에 맡긴다 (#202).
# 예전에는 -XX:MaxRAMPercentage=60.0 -XX:+UseSerialGC -Xss512k 를 명시했는데(#107),
# Render 무료 티어(512MB)에서 부팅 중 Hibernate가 EntityManagerFactory를 만들다
# OOM으로 죽던 것(exit 137)을 막기 위해서였다. 배포가 Coolify(13.6GB)로 옮겨오며 그
# 제약이 사라졌다 — 기본값(힙 25%)이면 힙만으로도 죽던 시절의 수십 배다. SerialGC는
# 오히려 큰 힙에서 G1GC보다 GC 정지가 길어 손해이고, 좁힌 스택(512k)은 깊은 재귀에서
# StackOverflowError 위험만 남긴다.
# 나중에 컨테이너 메모리를 좁게 제한하게 되면 이 결정을 다시 볼 것.
ENTRYPOINT ["java", "-jar", "app.jar"]
