# Runtime image for the Auralis Discord bot. It runs the jar you built on your PC rather than building
# inside Docker: the repo's Gradle build also configures the Android app and needs its SDK and secrets.
#
#   copy discord-bot/build/libs/auralis-discord-bot.jar next to this file, then: docker compose up -d --build
#
# Works on x86-64 and ARM64 (the jar carries voice libraries for both).
FROM eclipse-temurin:25-jre

# The bot keeps its lyrics index and stream-lookup state under its home folder, kept in the /data volume.
RUN useradd --system --home-dir /data --create-home auralis-bot
WORKDIR /data
COPY auralis-discord-bot.jar /opt/auralis-discord-bot/auralis-discord-bot.jar

USER auralis-bot
ENV JAVA_OPTS="-Xms128m -Xmx768m -XX:+ExitOnOutOfMemoryError"
# exec, so the JVM gets Docker's stop signal and the bot says goodbye before it goes.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /opt/auralis-discord-bot/auralis-discord-bot.jar \"$@\"", "--"]
