FROM node:22-alpine
WORKDIR /app
COPY server.mjs .
ENV MOCK_SANDBOX_PORT=8090 MOCK_SANDBOX_ROOT=/tmp/sandboxes
RUN mkdir -p /tmp/sandboxes && chown -R node:node /tmp/sandboxes
USER node
EXPOSE 8090
CMD ["node", "server.mjs"]
