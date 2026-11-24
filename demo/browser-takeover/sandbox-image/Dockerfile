ARG BASE=sandbox-registry.cn-zhangjiakou.cr.aliyuncs.com/opensandbox/chrome:latest
ARG NODE_IMAGE=node:22-bookworm-slim
ARG CHROME_DEVTOOLS_MCP_VERSION=1.13.0
ARG NPM_REGISTRY=https://registry.npmmirror.com

FROM ${NODE_IMAGE} AS node

FROM ${BASE}
ARG CHROME_DEVTOOLS_MCP_VERSION
ARG NPM_REGISTRY
USER root
ENV DEBIAN_FRONTEND=noninteractive \
    XDG_CONFIG_HOME=/etc
RUN sed -i 's#http://deb.debian.org/debian#http://mirrors.aliyun.com/debian#g' /etc/apt/sources.list.d/debian.sources \
 && apt-get update \
 && apt-get install -y --no-install-recommends novnc python3-websockify python3 fonts-wqy-microhei \
 && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
 && npm install -g --no-fund --no-audit --registry=${NPM_REGISTRY} chrome-devtools-mcp@${CHROME_DEVTOOLS_MCP_VERSION} \
 && npm cache clean --force
COPY --chmod=0755 entrypoint.sh /browser-entrypoint.sh
COPY --chmod=0644 cd4a.config.json /etc/cd4a/config.json
COPY --chmod=0755 site/ /opt/demo-site/
ENV DISPLAY=:1
ENTRYPOINT ["/browser-entrypoint.sh"]
