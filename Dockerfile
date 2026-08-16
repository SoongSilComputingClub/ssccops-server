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

# 소스 복사 및 빌드
COPY src src
RUN ./gradlew bootJar -x checkstyleMain -x checkstyleTest -x spotlessCheck --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080

# 힙 상한을 명시해 메타스페이스·스레드 스택·GC 북키핑에 여유를 남긴다(ssccops-server#107).
# 플래그가 없으면 JDK 17 기본값(MaxRAMPercentage=25.0)이 힙만 제한하고 힙 밖 메모리는
# 그대로라, 512MB 컨테이너(Render 무료 티어)에서 Hibernate가 EntityManagerFactory를
# 만드는 도중(엔티티 26종 메타모델 로딩) OOM으로 죽었다(exit 137). SerialGC는 G1GC보다
# 북키핑 오버헤드가 적어 작은 힙에서의 GC 비용을 줄인다. 로컬 docker-compose도 이
# Dockerfile을 공유하지만 호스트 메모리가 넉넉해 이 상한이 문제가 되지 않는다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:+UseSerialGC", "-Xss512k", "-jar", "app.jar"]
