FROM elasticsearch:8.18.8
COPY ik.zip /tmp/ik.zip
RUN mkdir -p /usr/share/elasticsearch/plugins/analysis-ik && \
    cd /usr/share/elasticsearch/plugins/analysis-ik && \
    /usr/share/elasticsearch/jdk/bin/jar xf /tmp/ik.zip
