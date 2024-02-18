FROM clojure:tools-deps

WORKDIR /app/frontend
ENV NODE_VERSION=20
ENV RESOURCE_DIR=/app/resources/public/js
RUN apt-get update && apt-get install -y curl
RUN curl -fsSL https://deb.nodesource.com/setup_$NODE_VERSION.x | bash - && apt-get install -y nodejs
COPY frontend/package* .
RUN npm ci
RUN mkdir -p $RESOURCE_DIR
COPY frontend/ .
RUN npm run build
# NOTE: the symlink between frontend/dist and resources/public/js is how the file gets in place

WORKDIR /app
# TODO: this isn't the right way to cache the deps
# COPY deps.edn .
# RUN clj -X:deps prep
COPY . .
RUN mkdir classes && /app/build.sh

ENTRYPOINT [ "java", "-jar", "/app/target/app.jar" ]
