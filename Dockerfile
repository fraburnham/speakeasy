FROM clojure:tools-deps

WORKDIR /app

COPY deps.edn .

# TODO: this isn't the right way to cache the deps
RUN clj -X:deps prep

COPY . .

RUN mkdir classes && /app/build.sh

ENTRYPOINT [ "java", "-jar", "/app/speakeasy.jar" ]
