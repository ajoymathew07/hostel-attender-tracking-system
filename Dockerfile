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

# Two startup flags, and nothing more. A free instance sleeps after fifteen minutes and takes over
# two minutes to come back (§2.5, NFR-6), and almost all of that is a JVM doing class loading and
# proxy generation on a fraction of a CPU. Neither flag changes behaviour; both trade something
# this system does not need for startup time it does.
#
#   TieredStopAtLevel=1  Stops the JIT at C1. Peak throughput drops, which is irrelevant for eight
#                        users, and warmup gets shorter, which is the whole cost of a cold start.
#   MaxRAMPercentage=60  The default is 25%, so a 512 MB instance would cap the heap at 128 MB and
#                        leave the rest unused while garbage collection thrashes through Hibernate's
#                        startup. 60% is 307 MB, which leaves ~200 MB for metaspace, code cache and
#                        stacks — 75% would not, and the instance would be OOM-killed instead.
#
# UseSerialGC is deliberately absent: the JVM already chooses it unaided on a container this small,
# so writing it down would state a default rather than change one.
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
