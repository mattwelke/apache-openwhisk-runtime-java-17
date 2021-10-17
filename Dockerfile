# Build custom Java runtime using jlink
FROM mcr.microsoft.com/openjdk/jdk:17-ubuntu as runtime-build
RUN apt-get update && apt-get install -y binutils
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.se,java.security.jgss,java.security.sasl,java.smartcardio,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.accessibility,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.dynalink,jdk.httpserver,jdk.incubator.foreign,jdk.incubator.vector,jdk.internal.vm.ci,jdk.internal.vm.compiler,jdk.internal.vm.compiler.management,jdk.jdwp.agent,jdk.jfr,jdk.jsobject,jdk.localedata,jdk.management,jdk.management.agent,jdk.management.jfr,jdk.naming.dns,jdk.naming.rmi,jdk.net,jdk.nio.mapmode,jdk.sctp,jdk.security.auth,jdk.security.jgss,jdk.unsupported,jdk.xml.dom,jdk.zipfs \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=2 \
         --output /javaruntime


# Build app using Maven
FROM maven:3.8.3-eclipse-temurin-17 AS app-build
WORKDIR /app
COPY pom.xml .
RUN mvn -e -B dependency:resolve
COPY src ./src
RUN mvn -e -B package


# Copy runtime and app in from builders into small image for production
FROM debian:buster-slim AS release
ENV JAVA_HOME /usr/lib/jvm/msopenjdk-17-amd64
ENV PATH "${JAVA_HOME}/bin:${PATH}"
RUN apt-get update && apt-get install -y ca-certificates
COPY --from=runtime-build /javaruntime $JAVA_HOME
COPY --from=app-build /app/target/apache-openwhisk-runtime-java-17-1.0-SNAPSHOT.jar /
ENTRYPOINT ["java","-jar","/apache-openwhisk-runtime-java-17-1.0-SNAPSHOT.jar"]
