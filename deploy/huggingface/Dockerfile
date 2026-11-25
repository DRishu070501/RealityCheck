# One-container image for a Hugging Face Space (spec 11, task 15): PostgreSQL, the backend and nginx together,
# because a Space runs a single container. tools/deploy/hf_space.py builds app.jar and html/ from the normal
# Dockerfiles and puts them next to this file before uploading; the Space does not build source code.
FROM eclipse-temurin:21-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql nginx \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY app.jar /app/app.jar
COPY html /app/html
COPY nginx.conf nginx-proxy.conf /app/
COPY --chmod=755 start.sh /app/start.sh

# Spaces run the container as user 1000 with a writable /tmp; the database lives there and is reseeded at start.
USER 1000
ENV JAVA_TOOL_OPTIONS="-Xmx1536m"
EXPOSE 7860
CMD ["/app/start.sh"]
