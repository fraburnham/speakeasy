FROM clojure:tools-deps

WORKDIR /app

# TODO: this isn't the right way to cache the deps
# COPY deps.edn .
# RUN clj -X:deps prep

COPY . .

RUN mkdir classes && /app/build.sh

ENTRYPOINT [ "java", "-jar", "/app/speakeasy.jar" ]
