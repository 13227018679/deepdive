# Dockerfile to build and test DeepDive inside a container
#
# `make build-in-container test-in-container` uses master image built by this.
# `util/build/docker/` contains utilities relevant to this.
FROM ubuntu
MAINTAINER deepdive-dev@googlegroups.com

# Install essential stuffs
RUN apt-get update && apt-get install -qy \
        coreutils \
        bash \
        curl \
        sudo \
        git \
        build-essential \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# Set up a non-superuser
ARG USER=user
ENV USER=$USER
RUN adduser --disabled-password --gecos "" $USER \
 && adduser $USER adm \
 && bash -c "echo '%adm ALL=(ALL:ALL) NOPASSWD: ALL' | tee -a /etc/sudoers"
USER $USER

# Get a fresh clone of deepdive
ARG BRANCH=master
ENV BRANCH=$BRANCH
WORKDIR /deepdive
RUN sudo chown -R $USER .
COPY .git .git
RUN git checkout -f

# Install deepdive build/runtime dependencies
RUN make depends \
 && sudo apt-get clean \
 && sudo rm -rf /var/lib/apt/lists/*

# Build deepdive
RUN make bundled-runtime-dependencies
RUN make
