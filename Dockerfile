# Stage 1: build — compila o jar usando Maven + JDK completo
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copia só o necessário pra baixar dependências primeiro (cache de camada)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

# Só agora copia o código-fonte e compila
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: runtime — imagem final, só com JRE (sem JDK, sem Maven, sem código-fonte)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/payment-api-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
# Container grátis do Render tem 512 MB. Por padrão a JVM usa só 25% da RAM
# do container pra heap; num container pequeno e dedicado, 75% aproveita a
# memória e ainda deixa folga pro resto do processo (metaspace, threads).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
