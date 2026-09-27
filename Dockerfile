# Render has no native Java runtime — its native environments are Node, Python, Ruby, Go, Rust and
# Elixir — so a Dockerfile is not a preference here, it is the only option. It also pins the JRE
# version in the repository rather than in a dashboard setting nobody can review.
#
# Two stages: the first needs a JDK and the whole source tree, the second needs neither. Shipping
# the JRE image alone keeps the compiler, Maven and the sources out of the deployed container.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
# The Maven wrapper pins the build tool too, so the image builds with the same Maven as CI.
COPY . .
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/duty-tracker-*.jar app.jar

# The profile is baked in rather than left to the dashboard, because the one thing that must not
# happen is this image starting with developer settings (ProfileCheck, docs/runbook/deploy.md).
ENV SPRING_PROFILES_ACTIVE=cloud

# Documentation only. Render injects PORT and the app binds it; `server.port` in the cloud profile
# falls back to 8080, which is what the standby jar on the manager's desktop uses.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
