FROM eclipse-temurin:21-jre

WORKDIR /app

# 미리 빌드된 JAR 파일 복사
COPY build/libs/*.jar app.jar

# 포트 노출
EXPOSE 8081

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
