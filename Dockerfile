FROM openjdk:8-jdk-alpine

COPY . /app/synthea  

WORKDIR /app/synthea 

RUN ./gradlew build -x test

CMD ./run_synthea
