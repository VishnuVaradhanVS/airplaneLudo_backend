FROM gradle:8-jdk11 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build --no-daemon -x test
FROM openjdk:11-slim
EXPOSE 10000
RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/*.jar /app/ludo-backend.jar
ENTRYPOINT ["java", "-jar", "/app/ludo-backend.jar"]