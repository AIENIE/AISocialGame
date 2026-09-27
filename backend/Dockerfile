# protoc's Linux native executable needs a glibc Maven builder; pin the multi-platform index.
FROM maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS builder
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN env -u JAVA_OPTS -u JAVA_TOOL_OPTIONS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS \
    -u LD_PRELOAD -u LD_LIBRARY_PATH -u LD_AUDIT -u BASH_ENV \
    -u MAVEN_ARGS -u MAVEN_OPTS -u MAVEN_DEBUG_OPTS -u MAVEN_BASEDIR \
    -u JVM_CONFIG_MAVEN_PROPS -u INTERNAL_MAVEN_OPTS \
    MAVEN_SKIP_RC=1 mvn -DskipTests package

FROM eclipse-temurin:25.0.3_9-jre-alpine-3.23@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app
RUN addgroup -S -g 10001 aisocial \
    && adduser -S -D -H -u 10001 -G aisocial aisocial
COPY --chown=aisocial:aisocial --from=builder /app/target/ai-social-game-0.0.1-SNAPSHOT.jar app.jar
COPY --chown=aisocial:aisocial start-backend.sh /app/bin/start-backend.sh
COPY --chown=aisocial:aisocial runtime-process-environment.sh /app/bin/runtime-process-environment.sh
RUN chmod 0555 /app/bin/start-backend.sh /app/bin/runtime-process-environment.sh
EXPOSE 20030
USER aisocial:aisocial
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=6 \
    CMD ["/opt/java/openjdk/bin/java", "-Dloader.main=com.aisocialgame.health.StrictHttpHealthProbe", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher", "http://127.0.0.1:20030/actuator/health"]
ENTRYPOINT ["/app/bin/start-backend.sh"]
