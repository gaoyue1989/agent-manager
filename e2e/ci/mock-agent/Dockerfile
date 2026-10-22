FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod main.go ./
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /out/mock-agent .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates && adduser -D -u 10001 app
COPY --from=build /out/mock-agent /usr/local/bin/mock-agent
USER app
EXPOSE 8100
ENTRYPOINT ["mock-agent"]
