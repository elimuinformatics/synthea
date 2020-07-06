FROM openjdk:8-jdk-alpine

COPY . /app/synthea  

WORKDIR /app/synthea 

RUN apk add --update curl 

# Download and install Gradle
RUN \
    cd /usr/local && \
    curl -L https://services.gradle.org/distributions/gradle-6.4.1-bin.zip -o gradle-6.4.1-bin.zip && \
    unzip gradle-6.4.1-bin.zip && \
    rm gradle-6.4.1-bin.zip

# Export some environment variables
ENV GRADLE_HOME=/usr/local/gradle-6.4.1
ENV PATH=$PATH:$GRADLE_HOME/bin

RUN gradle build -x test

CMD gradle -PmainClass=App run --args='-m _bob* -p 10'
