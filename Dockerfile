FROM openjdk:16.0.2
EXPOSE 8000

COPY build/libs/*.jar .
CMD java -jar *.jar

