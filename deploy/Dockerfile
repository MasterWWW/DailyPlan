# syntax=docker/dockerfile:1.7

FROM golang:1.27-bookworm AS api-build
WORKDIR /src
COPY server/go.mod server/go.sum* ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/dailyplan ./cmd/dailyplan

FROM debian:bookworm-slim AS whisper-build
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates cmake curl g++ make \
    && rm -rf /var/lib/apt/lists/*
ARG WHISPER_CPP_REF=v1.8.2
ARG WHISPER_CPP_SHA256=bcee25589bb8052d9e155369f6759a05729a2022d2a8085c1aa4345108523077
RUN curl -fsSL "https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/${WHISPER_CPP_REF}" \
       -o /tmp/whisper.cpp.tar.gz \
    && echo "${WHISPER_CPP_SHA256}  /tmp/whisper.cpp.tar.gz" | sha256sum -c - \
    && mkdir -p /whisper.cpp \
    && tar -xzf /tmp/whisper.cpp.tar.gz --strip-components=1 -C /whisper.cpp \
    && cmake -S /whisper.cpp -B /whisper.cpp/build \
       -DCMAKE_BUILD_TYPE=Release \
       -DBUILD_SHARED_LIBS=OFF \
       -DGGML_NATIVE=OFF \
       -DWHISPER_BUILD_TESTS=OFF \
       -DWHISPER_BUILD_EXAMPLES=ON \
    && cmake --build /whisper.cpp/build --config Release -j"$(nproc)"

FROM mysql:8.4
RUN microdnf install -y ca-certificates curl tzdata libgomp libstdc++ \
    && microdnf clean all
WORKDIR /app
COPY --from=api-build /out/dailyplan /usr/local/bin/dailyplan
COPY --from=whisper-build /whisper.cpp/build/bin/whisper-cli /usr/local/bin/whisper-cli
RUN mkdir -p /data/attachments /backups /models \
    && chown -R 999:999 /data /backups
USER 999:999
EXPOSE 8787
ENTRYPOINT ["/usr/local/bin/dailyplan"]
CMD ["serve"]

