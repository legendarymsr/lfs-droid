#!/bin/sh
# The build host: an Alpine aarch64 minirootfs with just enough toolchain to
# start chapter 5. Android has no compiler, so this is the "host system" the
# LFS book asks for. Idempotent.
set -e
[ -f /etc/.lfsdroid-host ] && exit 0
echo "@@LFS step 0 0 host alpine-packages"
apk update
apk add bash binutils bison coreutils diffutils findutils gawk gcc g++ grep gzip m4 make patch perl \
  python3 sed tar texinfo xz wget util-linux file ncurses tzdata
ln -sf /bin/bash /bin/sh.bash
touch /etc/.lfsdroid-host
echo "@@LFS ok host:alpine-packages"
