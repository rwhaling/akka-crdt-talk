FROM openjdk:8
RUN apt-get update; apt-get install jq; apt-get install httpie
ADD ./target/scala-2.11/uber.jar uber.jar
ENTRYPOINT ["java","-cp","uber.jar", "akkacrdt.SimpleClusterApp"]
