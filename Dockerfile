FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home nordapp && mkdir -p /app/data && chown -R nordapp:nordapp /app
COPY --chown=nordapp:nordapp target/nord-dev-0.1.0.jar /app/app.jar
USER nordapp
VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]
