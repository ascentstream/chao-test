FROM openjdk:8
WORKDIR /app
ADD ./target/chao-test-1.0-SNAPSHOT-bin.tar.gz /app
RUN  #tail -f chao-test-1.0-SNAPSHOT/bin/runserver.sh