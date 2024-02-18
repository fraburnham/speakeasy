FROM clojure:tools-deps

WORKDIR /app/frontend
ENV NODE_VERSION=20
RUN apt-get update && apt-get install -y curl
RUN curl -fsSL https://deb.nodesource.com/setup_$NODE_VERSION.x | bash - && apt-get install -y nodejs
COPY frontend/package* .
RUN npm ci
COPY frontend/ .
RUN npm run build

WORKDIR /app
# TODO: this isn't the right way to cache the deps
# COPY deps.edn .
# RUN clj -X:deps prep
COPY . .
RUN ln -s $PWD/frontend/dist resources/public/js
RUN mkdir classes && /app/build.sh

ENTRYPOINT [ "java", "-jar", "/app/target/app.jar" ]
