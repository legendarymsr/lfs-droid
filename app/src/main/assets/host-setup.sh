#!/bin/sh
# The build host: an Alpine aarch64 minirootfs. Android has no compiler, so this
# is the "host system" the LFS book asks for. Since 0.2.0 its gcc is also the
# only compiler: there's no cross toolchain, it builds straight into $LFS
# against the glibc we build first. Idempotent; v2 adds cmake/pkgconf.
set -e
[ -f /etc/.lfsdroid-host-2 ] && exit 0
echo "@@LFS step 0 0 host alpine-packages"
apk update
apk add bash binutils bison coreutils diffutils findutils gawk gcc grep gzip m4 make patch \
  python3 sed tar xz wget util-linux file ncurses tzdata cmake samurai pkgconf linux-headers
ln -sf /bin/bash /bin/sh.bash
touch /etc/.lfsdroid-host /etc/.lfsdroid-host-2
echo "@@LFS ok host:alpine-packages"
