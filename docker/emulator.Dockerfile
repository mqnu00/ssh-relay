# The Android emulator cannot run on this WSL distro directly: CentOS Stream 8 ships
# glibc 2.28, while the emulator's bundled Qt6 libraries require GLIBC_2.29/2.30
# (libm / libpthread). A container with a modern glibc is the clean way around it,
# and it comes with root, so the runtime libraries the emulator dlopens can just be
# apt-installed here instead of being scavenged from RPMs.
#
# Only the emulator itself runs here. adb, Gradle and the tests stay in WSL and talk
# to it over the host network (--network host), which keeps artifact ownership sane.
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Runtime libraries the emulator's QEMU/Qt binaries link against. `-no-window` does
# not remove the linkage, so the X11 and audio clients are still required.
#
# docker-clean is dropped first: its APT::Update::Post-Invoke `rm -f` hook fails on
# this WSL2/overlayfs setup and makes `apt-get update` exit non-zero even though the
# package lists were fetched fine.
RUN rm -f /etc/apt/apt.conf.d/docker-clean \
    && apt-get update && apt-get install -y --no-install-recommends \
        libpulse0 \
        libxkbfile1 \
        libxkbcommon-x11-0 \
        libxcb-cursor0 \
        libxcb-icccm4 \
        libxcb-image0 \
        libxcb-keysyms1 \
        libxcb-render-util0 \
        libxcb-util1 \
        libxcb-shm0 \
        libxcb-randr0 \
        libxcb-sync1 \
        libxcb-xfixes0 \
        libnss3 \
        libgl1 \
        libglu1-mesa \
        libglib2.0-0 \
        libx11-6 \
        libxext6 \
        libxrandr2 \
        libxcomposite1 \
        libxdamage1 \
        libxi6 \
        libxcursor1 \
        libxfixes3 \
        libxrender1 \
        libxtst6 \
        libasound2 \
        libdbus-1-3 \
        libsndfile1 \
        libasyncns0 \
        libbsd0 \
        libjpeg8 \
        libpng16-16 \
        unzip \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# /dev/kvm is passed through with `docker run --device /dev/kvm`. The emulator also
# wants a permissive /dev/shm and a real /tmp.
VOLUME ["/dev/shm"]

WORKDIR /work
