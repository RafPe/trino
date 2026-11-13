# Build context: this directory after build.sh has put the static binary in bin/gate.
FROM alpine:3.22
RUN adduser -D -H -u 10001 gate
COPY bin/gate /usr/local/bin/gate
USER gate
EXPOSE 8095
HEALTHCHECK --interval=5s --timeout=2s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8095/healthz || exit 1
ENTRYPOINT ["/usr/local/bin/gate"]
