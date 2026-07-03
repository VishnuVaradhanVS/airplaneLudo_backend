FROM gradle:8-jdk11 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle shadowJar --no-daemon -x test
FROM eclipse-temurin:11-jre-alpine
EXPOSE 10000
RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/*-all.jar /app/ludo-backend.jar
ENTRYPOINT ["java", "-jar", "/app/ludo-backend.jar"]