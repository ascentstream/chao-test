FROM amazoncorretto:8u472-al2023-jre
WORKDIR /app
ADD ./target/chao-test-1.0-SNAPSHOT-bin.tar.gz /app
RUN  #tail -f chao-test-1.0-SNAPSHOT/bin/runserver.sh
