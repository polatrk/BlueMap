# BlueMap for Bedrock Dedicated Server.
# Builds BlueMap from the "bedrock" branch of the fork, then runs it on a Java 25 runtime.
#
#   docker compose build            (see docker-compose.yml / README.md in this folder)
#
# Build args: REPO (git URL of BlueMap with Bedrock support), REF (branch, tag or commit).

FROM eclipse-temurin:21-jdk AS jdk21

FROM eclipse-temurin:25-jdk AS build
# BlueMap's build uses a Java 21 toolchain for the API build logic and Java 25 for the rest.
COPY --from=jdk21 /opt/java/openjdk /opt/jdk21
RUN apt-get update \
 && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/*
ARG REPO=https://github.com/polatrk/BlueMap.git
ARG REF=bedrock
# Full clone with tags: the BlueMap version is computed from git tags.
RUN git clone --recurse-submodules "$REPO" /src && cd /src && git checkout "$REF" && git submodule update --init
WORKDIR /src
RUN ./gradlew --no-daemon --console=plain \
      -Porg.gradle.java.installations.paths=/opt/jdk21,/opt/java/openjdk \
      -Porg.gradle.java.installations.auto-detect=false \
      -PonlyImplementations=bedrock \
      :bedrock:shadowJar \
 && cp "$(ls -t implementations/bedrock/build/libs/*-shadow.jar | head -1)" /bluemap-bedrock.jar

FROM eclipse-temurin:25-jre
# ssh: link to the BDS console on another machine (see bluemap-bridge.sh)
RUN apt-get update \
 && apt-get install -y --no-install-recommends openssh-client \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /bluemap-bedrock.jar /app/bluemap-bedrock.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
WORKDIR /data
VOLUME /data
EXPOSE 8100
ENTRYPOINT ["/app/entrypoint.sh"]
