FROM eclipse-temurin:21-jre-jammy
ARG JAR_FILE
RUN groupadd --system taxiagent && useradd --system --gid taxiagent --home /app taxiagent
WORKDIR /app
COPY ${JAR_FILE} app.jar
RUN chown taxiagent:taxiagent app.jar
USER taxiagent
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
