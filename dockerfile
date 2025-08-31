# ---- Build (avec Maven déjà installé) ----
FROM maven:3.9.9-eclipse-temurin-22 AS build
WORKDIR /app
COPY . .
# Build Vaadin en mode production
RUN mvn -q -Pproduction -DskipTests clean package

# ---- Run (JRE seulement) ----
FROM eclipse-temurin:22-jre
WORKDIR /app
COPY --from=build /app/target/antix-*.jar /app/app.jar
ENV JAVA_OPTS="-Dspring.profiles.active=prod"
EXPOSE 8080
CMD ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
