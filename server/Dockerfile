# GameLodge Metadata Server – built from source on the Docker host (amd64 or arm64), from the
# repository root:
#   docker build -f server/Dockerfile -t gamelodge-metadata-server .
# Both architectures at once (buildx): add --platform linux/amd64,linux/arm64.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY protocol protocol
COPY host host
COPY server server
# The jars run on any architecture: built once, on the build machine's platform.
RUN ./gradlew --no-daemon --quiet -PserverOnly -Dorg.gradle.jvmargs=-Xmx1g :server:installDist

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --no-create-home gamelodge-server && mkdir /data && chown gamelodge-server /data
COPY --from=build /src/server/build/install/gamelodge-server /app
ENV GAMELODGE_DATA=/data
VOLUME /data
EXPOSE 47900 47901
USER gamelodge-server
ENTRYPOINT ["/app/bin/gamelodge-server"]
