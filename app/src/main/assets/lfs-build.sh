#!/bin/bash
# LFS Droid: Linux From Scratch 13.1 (systemd book), aarch64, built on a phone.
# Runs inside the Alpine host chroot as root. Resumable: every finished step is
# a line in $LFS/sources/.lfsdroid-state, so a re-run skips straight to the
# first unfinished one. Progress lines for the app look like:
#   @@LFS step <n> <total> <chapter> <name>
#   @@LFS ok <name> | @@LFS fail <name> <code> | @@LFS done
# Adapted from legenddots' libre/setup (x86_64) for aarch64, minus the kernel,
# bootloader and everything BLFS. Tests are skipped: this is a phone.
set -o pipefail
BOOK=13.1-systemd
BOOK_URL=https://www.linuxfromscratch.org/lfs/downloads/$BOOK
CMAKE_VER=4.4.4
FASTFETCH_VER=2.69.0

INNER=0; [ "$1" = inner ] && { INNER=1; shift; }
if [ $INNER = 1 ]; then LFS=; SRC=/sources; else LFS=${LFS:-/mnt/lfs}; SRC=$LFS/sources; fi
STATE=$SRC/.lfsdroid-state
LOGDIR=$SRC/.logs
export LFS LC_ALL=POSIX LFS_TGT=aarch64-lfs-linux-gnu
export MAKEFLAGS="-j${JOBS:-$(nproc)}"
[ $INNER = 0 ] && export PATH=$LFS/tools/bin:/usr/bin:/usr/sbin:/bin:/sbin CONFIG_SITE=$LFS/usr/share/config.site
umask 022
export FORCE_UNSAFE_CONFIGURE=1  # we are root the whole way; the book uses an lfs user

say() { echo "== $*"; }
is_done() { [ -f "$STATE" ] && grep -qx "$1" "$STATE"; }
mark() { echo "$1" >> "$STATE"; }
tb() { ls "$SRC"/$1-[0-9]*.tar.* "$SRC"/$1[0-9]*.tar.* 2>/dev/null | grep -v -e docs -e html | head -n1; }
ver() { basename "$(tb "$1")" | sed -E "s/^$1-//; s/\.tar\..*//"; }

# ---------------------------------------------------------------- the plan
# chapter:name:tarball-prefix:function   (tarball "-" = no source)
PLAN="
4:layout:-:c4_layout
4:sources:-:c4_sources
5:binutils-pass1:binutils:c5_binutils
5:gcc-pass1:gcc:c5_gcc
5:linux-headers:linux:c5_headers
5:glibc:glibc:c5_glibc
5:libstdc++:gcc:c5_libstdcxx
6:m4:m4:c6_std
6:ncurses:ncurses:c6_ncurses
6:bash:bash:c6_bash
6:coreutils:coreutils:c6_coreutils
6:diffutils:diffutils:c6_diffutils
6:file:file:c6_file
6:findutils:findutils:c6_findutils
6:gawk:gawk:c6_gawk
6:grep:grep:c6_std
6:gzip:gzip:c6_std
6:make:make:c6_make
6:patch:patch:c6_std
6:sed:sed:c6_std
6:tar:tar:c6_std
6:xz:xz:c6_xz
6:binutils-pass2:binutils:c6_binutils
6:gcc-pass2:gcc:c6_gcc
7:chroot-prep:-:c7_prep
7:gettext:gettext:i7_gettext
7:bison:bison:i7_bison
7:perl:perl:i7_perl
7:python:Python:i7_python
7:texinfo:texinfo:i_std
7:util-linux:util-linux:i7_utillinux
7:cleanup:-:i7_cleanup
8:man-pages:man-pages:i8_manpages
8:iana-etc:iana-etc:i8_iana
8:glibc:glibc:i8_glibc
8:zlib:zlib:i8_zlib
8:bzip2:bzip2:i8_bzip2
8:xz:xz:i8_xz
8:lz4:lz4:i8_lz4
8:zstd:zstd:i8_zstd
8:file:file:i_std
8:readline:readline:i8_readline
8:m4:m4:i_std
8:bc:bc:i8_bc
8:flex:flex:i8_flex
8:tcl:tcl:i8_tcl
8:expect:expect:i8_expect
8:dejagnu:dejagnu:i8_dejagnu
8:pkgconf:pkgconf:i8_pkgconf
8:binutils:binutils:i8_binutils
8:gmp:gmp:i8_gmp
8:mpfr:mpfr:i8_mpfr
8:mpc:mpc:i8_mpc
8:attr:attr:i8_attr
8:acl:acl:i8_acl
8:libcap:libcap:i8_libcap
8:libxcrypt:libxcrypt:i8_libxcrypt
8:shadow:shadow:i8_shadow
8:gcc:gcc:i8_gcc
8:ncurses:ncurses:i8_ncurses
8:sed:sed:i_std
8:psmisc:psmisc:i_std
8:gettext:gettext:i8_gettext
8:bison:bison:i7_bison
8:grep:grep:i_std
8:bash:bash:i8_bash
8:libtool:libtool:i_std
8:gdbm:gdbm:i8_gdbm
8:gperf:gperf:i_std
8:expat:expat:i8_expat
8:inetutils:inetutils:i8_inetutils
8:less:less:i8_less
8:perl:perl:i8_perl
8:autoconf:autoconf:i_std
8:automake:automake:i_std
8:openssl:openssl:i8_openssl
8:libelf:elfutils:i8_libelf
8:libffi:libffi:i8_libffi
8:sqlite:sqlite-autoconf:i8_sqlite
8:python:Python:i8_python
8:flit-core:flit_core:i8_pip
8:packaging:packaging:i8_pip
8:wheel:wheel:i8_pip
8:setuptools:setuptools:i8_pip
8:ninja:ninja:i8_ninja
8:meson:meson:i8_pip
8:kmod:kmod:i8_kmod
8:coreutils:coreutils:i8_coreutils
8:diffutils:diffutils:i_std
8:gawk:gawk:i8_gawk
8:findutils:findutils:i8_findutils
8:groff:groff:i8_groff
8:gzip:gzip:i_std
8:iproute2:iproute2:i8_iproute2
8:kbd:kbd:i8_kbd
8:libpipeline:libpipeline:i_std
8:make:make:i_std
8:patch:patch:i_std
8:tar:tar:i8_tar
8:texinfo:texinfo:i_std
8:vim:vim:i8_vim
8:markupsafe:markupsafe:i8_pip
8:jinja2:jinja2:i8_pip
8:systemd:systemd:i8_systemd
8:dbus:dbus:i8_dbus
8:man-db:man-db:i8_mandb
8:procps-ng:procps-ng:i8_procps
8:util-linux:util-linux:i8_utillinux
8:e2fsprogs:e2fsprogs:i8_e2fs
8:config:-:i8_config
9:cmake:cmake:i9_cmake
9:fastfetch:fastfetch:i9_fastfetch
9:strip:-:i9_strip
"

# ---------------------------------------------------------------- helpers
std_cfg() { ./configure --prefix=/usr --host=$LFS_TGT --build=$(build-aux/config.guess 2>/dev/null || ./config.guess 2>/dev/null || sh support/config.guess) "$@"; }
c6_std() { std_cfg && make && make DESTDIR=$LFS install; }
i_std() { ./configure --prefix=/usr "$@" && make && make install; }
gcc_deps() { for p in mpfr gmp mpc; do tar -xf "$(tb $p)"; mv -v $p-* $p; done; }
gcc_aarch64() { sed -e '/mabi.lp64=/s/lib64/lib/' -i.orig gcc/config/aarch64/t-aarch64-linux; }
pipinst() { pip3 wheel -w dist --no-cache-dir --no-build-isolation --no-deps "$PWD" && pip3 install --no-index --no-user --find-links dist "$1"; }

# ---------------------------------------------------------------- chapter 4
c4_layout() {
  mkdir -pv $LFS/{etc,var,tools,sources} $LFS/usr/{bin,lib,sbin}
  for i in bin lib sbin; do [ -e $LFS/$i ] || ln -sv usr/$i $LFS/$i; done
  chmod -v a+wt $SRC
}
c4_sources() {
  cd $SRC
  wget -nv -O wget-list $BOOK_URL/wget-list && wget -nv -O md5sums $BOOK_URL/md5sums || return 1
  # SysV-only pieces and the docs aren't used here.
  grep -v -e sysvinit -e lfs-bootscripts -e udev-lfs -e sysklogd -e docs-html -e sqlite-doc -e tcl.*html -e /grub- wget-list > wget-list.droid
  echo "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VER/cmake-$CMAKE_VER.tar.gz" >> wget-list.droid
  echo "https://github.com/fastfetch-cli/fastfetch/archive/refs/tags/$FASTFETCH_VER.tar.gz" >> wget-list.droid
  local n=0 t; t=$(wc -l < wget-list.droid)
  while read -r url; do
    n=$((n+1)); f=${url##*/}
    case $url in *fastfetch*) f=fastfetch-$FASTFETCH_VER.tar.gz;; esac
    echo "@@LFS fetch $n $t $f"
    [ -s "$f" ] && continue
    wget -nv -c -O "$f.part" "$url" && mv "$f.part" "$f" || { echo "download failed: $url"; return 1; }
  done < wget-list.droid
  grep -v -e sysvinit -e lfs-bootscripts -e udev-lfs -e sysklogd -e docs-html -e sqlite-doc -e tcl.*html -e grub- md5sums | md5sum -c --quiet || return 1
}

# ---------------------------------------------------------------- chapter 5
c5_binutils() {
  mkdir -v build && cd build
  ../configure --prefix=$LFS/tools --with-sysroot=$LFS --target=$LFS_TGT --disable-nls \
    --enable-gprofng=no --disable-werror --enable-new-dtags --enable-default-hash-style=gnu &&
  make && make install
}
c5_gcc() {
  gcc_deps; gcc_aarch64
  mkdir -v build && cd build
  ../configure --target=$LFS_TGT --prefix=$LFS/tools --with-glibc-version=$(ver glibc) --with-sysroot=$LFS \
    --with-newlib --without-headers --enable-default-pie --enable-default-ssp --disable-nls --disable-shared \
    --disable-multilib --disable-threads --disable-libatomic --disable-libgomp --disable-libquadmath \
    --disable-libssp --disable-libvtv --disable-libstdcxx --enable-languages=c,c++ &&
  make && make install && cd .. &&
  cat gcc/limitx.h gcc/glimits.h gcc/limity.h > "$(dirname "$($LFS_TGT-gcc -print-libgcc-file-name)")/include/limits.h"
}
c5_headers() {
  make mrproper && make ARCH=arm64 headers &&
  find usr/include -type f ! -name '*.h' -delete && cp -rv usr/include $LFS/usr
}
c5_glibc() {
  patch -Np1 -i $SRC/glibc-fhs-1.patch
  mkdir -v build && cd build
  echo "rootsbindir=/usr/sbin" > configparms
  ../configure --prefix=/usr --host=$LFS_TGT --build=$(../scripts/config.guess) --disable-nscd \
    libc_cv_slibdir=/usr/lib --enable-kernel=5.4 &&
  make && make DESTDIR=$LFS install || return 1
  sed '/RTLDLIST=/s@/usr@@g' -i $LFS/usr/bin/ldd
  echo 'int main(){}' | $LFS_TGT-gcc -x c - -o /tmp/lfs-sanity &&
  readelf -l /tmp/lfs-sanity | grep -q 'ld-linux-aarch64.so.1' || { echo "toolchain sanity check failed"; return 1; }
  rm -f /tmp/lfs-sanity
}
c5_libstdcxx() {
  mkdir -v build && cd build
  ../libstdc++-v3/configure --host=$LFS_TGT --build=$(../config.guess) --prefix=/usr --disable-multilib \
    --disable-nls --disable-libstdcxx-pch --with-gxx-include-dir=/tools/$LFS_TGT/include/c++/$(ver gcc) &&
  make && make DESTDIR=$LFS install && rm -fv $LFS/usr/lib/lib{stdc++{,exp,fs},supc++}.la
}

# ---------------------------------------------------------------- chapter 6
c6_ncurses() {
  mkdir build && pushd build && ../configure --prefix=$LFS/tools AWK=gawk && make -C include &&
    make -C progs tic && install progs/tic $LFS/tools/bin && popd || return 1
  ./configure --prefix=/usr --host=$LFS_TGT --build=$(./config.guess) --mandir=/usr/share/man \
    --with-manpage-format=normal --with-shared --without-normal --with-cxx-shared --without-debug \
    --without-ada --disable-stripping AWK=gawk &&
  make && make DESTDIR=$LFS install && ln -sfv libncursesw.so $LFS/usr/lib/libncurses.so &&
  sed -e 's/^#if.*XOPEN.*$/#if 1/' -i $LFS/usr/include/curses.h
}
c6_bash() { std_cfg --without-bash-malloc && make && make DESTDIR=$LFS install && ln -sfv bash $LFS/bin/sh; }
c6_coreutils() {
  std_cfg --enable-install-program=hostname --enable-no-install-program=kill,uptime &&
  make && make DESTDIR=$LFS install && mv -v $LFS/usr/bin/chroot $LFS/usr/sbin
}
c6_diffutils() { std_cfg gl_cv_func_strcasecmp_works=y && make && make DESTDIR=$LFS install; }
c6_file() {
  mkdir build && pushd build && ../configure --disable-bzlib --disable-libseccomp --disable-xzlib --disable-zlib && make && popd || return 1
  ./configure --prefix=/usr --host=$LFS_TGT --build=$(./config.guess) && make FILE_COMPILE=$(pwd)/build/src/file &&
  make DESTDIR=$LFS install && rm -fv $LFS/usr/lib/libmagic.la
}
c6_findutils() { std_cfg --localstatedir=/var/lib/locate && make && make DESTDIR=$LFS install; }
c6_gawk() { sed -i 's/extras//' Makefile.in; c6_std; }
c6_make() { std_cfg --without-guile && make && make DESTDIR=$LFS install; }
c6_xz() { std_cfg --disable-static --docdir=/usr/share/doc/xz && make && make DESTDIR=$LFS install && rm -fv $LFS/usr/lib/liblzma.la; }
c6_binutils() {
  mkdir -v build && cd build
  ../configure --prefix=/usr --build=$(../config.guess) --host=$LFS_TGT --disable-nls --enable-shared \
    --enable-gprofng=no --disable-werror --enable-64-bit-bfd --enable-new-dtags --enable-default-hash-style=gnu &&
  make && make DESTDIR=$LFS install && rm -fv $LFS/usr/lib/lib{bfd,ctf,ctf-nobfd,opcodes,sframe}.{a,la}
}
c6_gcc() {
  gcc_deps; gcc_aarch64
  sed '/thread_header =/s/@.*@/gthr-posix.h/' -i libgcc/Makefile.in libstdc++-v3/include/Makefile.in
  mkdir -v build && cd build
  ../configure --build=$(../config.guess) --host=$LFS_TGT --target=$LFS_TGT \
    LDFLAGS_FOR_TARGET=-L$PWD/$LFS_TGT/libgcc --prefix=/usr --with-build-sysroot=$LFS \
    --enable-default-pie --enable-default-ssp --disable-nls --disable-multilib --disable-libatomic \
    --disable-libgomp --disable-libquadmath --disable-libsanitizer --disable-libssp --disable-libvtv \
    --enable-languages=c,c++ &&
  make && make DESTDIR=$LFS install && ln -sfv gcc $LFS/usr/bin/cc
}

# ---------------------------------------------------------------- chapter 7
c7_prep() {
  mkdir -pv $LFS/{dev,proc,sys,run,root,tmp,etc}
  enter_lfs
  # The virtual filesystems are mounted by enter_lfs; here only the base files.
  chroot $LFS /usr/bin/env -i PATH=/usr/bin:/usr/sbin HOME=/root /bin/bash -e -c '
    mkdir -pv /{boot,home,mnt,opt,srv} /etc/{opt,sysconfig} /lib/firmware /media/{floppy,cdrom}
    mkdir -pv /usr/{,local/}{include,src} /usr/lib/locale /usr/local/{bin,lib,sbin}
    mkdir -pv /usr/{,local/}share/{color,dict,doc,info,locale,man,misc,terminfo,zoneinfo}
    mkdir -pv /usr/{,local/}share/man/man{1..8} /var/{cache,local,log,mail,opt,spool} /var/lib/{color,misc,locate}
    ln -sfv /run /var/run; ln -sfv /run/lock /var/lock
    install -dv -m 0750 /root; install -dv -m 1777 /tmp /var/tmp
    ln -sfv /proc/self/mounts /etc/mtab
    printf "127.0.0.1 localhost lfs\n::1 localhost\n" > /etc/hosts
    cat > /etc/passwd <<P
root:x:0:0:root:/root:/bin/bash
bin:x:1:1:bin:/dev/null:/usr/bin/false
daemon:x:6:6:Daemon User:/dev/null:/usr/bin/false
messagebus:x:18:18:D-Bus Message Daemon User:/run/dbus:/usr/bin/false
systemd-journal-gateway:x:73:73:systemd Journal Gateway:/:/usr/bin/false
systemd-journal-remote:x:74:74:systemd Journal Remote:/:/usr/bin/false
systemd-journal-upload:x:75:75:systemd Journal Upload:/:/usr/bin/false
systemd-network:x:76:76:systemd Network Management:/:/usr/bin/false
systemd-resolve:x:77:77:systemd Resolver:/:/usr/bin/false
systemd-timesync:x:78:78:systemd Time Synchronization:/:/usr/bin/false
systemd-coredump:x:79:79:systemd Core Dumper:/:/usr/bin/false
uuidd:x:80:80:UUID Generation Daemon User:/dev/null:/usr/bin/false
systemd-oom:x:81:81:systemd Out Of Memory Daemon:/:/usr/bin/false
nobody:x:65534:65534:Unprivileged User:/dev/null:/usr/bin/false
P
    cat > /etc/group <<G
root:x:0:
bin:x:1:daemon
sys:x:2:
kmem:x:3:
tape:x:4:
tty:x:5:
daemon:x:6:
floppy:x:7:
disk:x:8:
lp:x:9:
dialout:x:10:
audio:x:11:
video:x:12:
utmp:x:13:
cdrom:x:15:
adm:x:16:
messagebus:x:18:
systemd-journal:x:23:
input:x:24:
mail:x:34:
kvm:x:61:
systemd-journal-gateway:x:73:
systemd-journal-remote:x:74:
systemd-journal-upload:x:75:
systemd-network:x:76:
systemd-resolve:x:77:
systemd-timesync:x:78:
systemd-coredump:x:79:
uuidd:x:80:
systemd-oom:x:81:
wheel:x:97:
users:x:999:
nogroup:x:65534:
G
    touch /var/log/{btmp,lastlog,faillog,wtmp}; chgrp -v utmp /var/log/lastlog; chmod -v 664 /var/log/lastlog; chmod -v 600 /var/log/btmp
  '
}
i7_gettext() { ./configure --disable-shared && make && cp -v gettext-tools/src/{msgfmt,msgmerge,xgettext} /usr/bin; }
i7_bison() { ./configure --prefix=/usr --docdir=/usr/share/doc/bison && make && make install; }
perl_cfg() {
  local pv; pv=$(ver perl | cut -d. -f1,2)
  sh Configure -des -D prefix=/usr -D vendorprefix=/usr -D useshrplib \
    -D privlib=/usr/lib/perl5/$pv/core_perl -D archlib=/usr/lib/perl5/$pv/core_perl \
    -D sitelib=/usr/lib/perl5/$pv/site_perl -D sitearch=/usr/lib/perl5/$pv/site_perl \
    -D vendorlib=/usr/lib/perl5/$pv/vendor_perl -D vendorarch=/usr/lib/perl5/$pv/vendor_perl "$@"
}
i7_perl() { perl_cfg && make && make install; }
i7_python() { ./configure --prefix=/usr --enable-shared --without-ensurepip --without-static-libpython && make && make install; }
i7_utillinux() {
  mkdir -pv /var/lib/hwclock
  ./configure --libdir=/usr/lib --runstatedir=/run --disable-chfn-chsh --disable-login --disable-nologin \
    --disable-su --disable-setpriv --disable-runuser --disable-pylibmount --disable-static --disable-liblastlog2 \
    --without-python ADJTIME_PATH=/var/lib/hwclock/adjtime --docdir=/usr/share/doc/util-linux &&
  make && make install
}
i7_cleanup() { rm -rf /usr/share/{info,man,doc}/*; find /usr/{lib,libexec} -name '*.la' -delete; rm -rf /tools; }

# ---------------------------------------------------------------- chapter 8
i8_manpages() { rm -v man3/crypt*; make -R GIT=false prefix=/usr install; }
i8_iana() { cp services protocols /etc; }
i8_glibc() {
  patch -Np1 -i /sources/glibc-fhs-1.patch
  for p in /sources/glibc-$(ver glibc)-upstream_fixes-*.patch; do [ -f "$p" ] && patch -Np1 -i "$p"; done
  mkdir -v build && cd build
  echo "rootsbindir=/usr/sbin" > configparms
  ../configure --prefix=/usr --disable-werror --disable-nscd libc_cv_slibdir=/usr/lib \
    --enable-stack-protector=strong --enable-kernel=5.4 && make || return 1
  touch /etc/ld.so.conf
  sed '/test-installation/s@$(PERL)@echo not running@' -i ../Makefile
  make install && sed '/RTLDLIST=/s@/usr@@g' -i /usr/bin/ldd || return 1
  localedef -i C -f UTF-8 C.UTF-8; localedef -i en_US -f UTF-8 en_US.UTF-8; localedef -i sv_SE -f UTF-8 sv_SE.UTF-8
  cat > /etc/nsswitch.conf <<N
passwd: files systemd
group: files systemd
shadow: files systemd
hosts: mymachines resolve [!UNAVAIL=return] files myhostname dns
networks: files
protocols: files
services: files
ethers: files
rpc: files
N
  mkdir tz && tar -xf "$(ls /sources/tzdata*.tar.gz)" -C tz && cd tz &&
  ZONEINFO=/usr/share/zoneinfo && mkdir -pv $ZONEINFO/{posix,right} &&
  for tz in etcetera southamerica northamerica europe africa antarctica asia australasia backward; do
    zic -L /dev/null -d $ZONEINFO ${tz}; zic -L /dev/null -d $ZONEINFO/posix ${tz}; zic -L leapseconds -d $ZONEINFO/right ${tz}
  done && cp -v zone.tab zone1970.tab iso3166.tab $ZONEINFO && zic -d $ZONEINFO -p America/New_York &&
  ln -sfv /usr/share/zoneinfo/${TZ:-UTC} /etc/localtime
  printf '/usr/local/lib\n/opt/lib\n' > /etc/ld.so.conf
}
i8_zlib() { ./configure --prefix=/usr && make && make install && rm -fv /usr/lib/libz.a; }
i8_bzip2() {
  patch -Np1 -i /sources/bzip2-1.0.8-install_docs-1.patch
  sed -i 's@\(ln -s -f \)$(PREFIX)/bin/@\1@' Makefile; sed -i "s@(PREFIX)/man@(PREFIX)/share/man@g" Makefile
  make -f Makefile-libbz2_so && make clean && make && make PREFIX=/usr install &&
  cp -av libbz2.so.* /usr/lib && ln -sfv libbz2.so.1.0.8 /usr/lib/libbz2.so && cp -v bzip2-shared /usr/bin/bzip2 &&
  for i in /usr/bin/{bzcat,bunzip2}; do ln -sfv bzip2 $i; done; rm -fv /usr/lib/libbz2.a
}
i8_xz() { i_std --disable-static --docdir=/usr/share/doc/xz; }
i8_lz4() { make BUILD_STATIC=no PREFIX=/usr && make BUILD_STATIC=no PREFIX=/usr install; }
i8_zstd() { make prefix=/usr && make prefix=/usr install && rm -fv /usr/lib/libzstd.a; }
i8_readline() {
  sed -i '/MV.*old/d' Makefile.in; sed -i '/{OLDSUFF}/c:' support/shlib-install
  sed -i 's/-Wl,-rpath,[^ ]*//' support/shobj-conf
  ./configure --prefix=/usr --disable-static --with-curses --docdir=/usr/share/doc/readline &&
  make SHLIB_LIBS="-lncursesw" && make install
}
i8_bc() { CC='gcc -std=c99' ./configure --prefix=/usr -G -O3 -r && make && make install; }
i8_flex() { i_std --docdir=/usr/share/doc/flex --disable-static && ln -sfv flex /usr/bin/lex; }
i8_tcl() {
  local v; v=$(ls /sources/tcl*-src.tar.gz | sed -E 's/.*tcl([0-9.]+)-src.*/\1/')
  cd unix && ./configure --prefix=/usr --mandir=/usr/share/man --disable-rpath && make &&
  make install && chmod -v u+w /usr/lib/libtcl${v%.*}.so && make install-private-headers &&
  ln -sfv tclsh${v%.*} /usr/bin/tclsh
}
i8_expect() {
  patch -Np1 -i /sources/expect-5.45.4-gcc15-1.patch
  ./configure --prefix=/usr --with-tcl=/usr/lib --enable-shared --disable-rpath --mandir=/usr/share/man \
    --with-tclinclude=/usr/include && make && make install && ln -sfv expect5.45.4/libexpect5.45.4.so /usr/lib
}
i8_dejagnu() { mkdir -v build && cd build && ../configure --prefix=/usr && make install; }
i8_pkgconf() {
  i_std --disable-static --docdir=/usr/share/doc/pkgconf && ln -sfv pkgconf /usr/bin/pkg-config &&
  ln -sfv pkgconf.1 /usr/share/man/man1/pkg-config.1
}
i8_binutils() {
  mkdir -v build && cd build
  ../configure --prefix=/usr --sysconfdir=/etc --enable-ld=default --enable-plugins --enable-shared \
    --disable-werror --enable-64-bit-bfd --enable-new-dtags --with-system-zlib --enable-default-hash-style=gnu &&
  make tooldir=/usr && make tooldir=/usr install && rm -rfv /usr/lib/lib{bfd,ctf,ctf-nobfd,gprofng,opcodes,sframe}.a
}
i8_gmp() { i_std --enable-cxx --disable-static --docdir=/usr/share/doc/gmp; }
i8_mpfr() { i_std --disable-static --enable-thread-safe --docdir=/usr/share/doc/mpfr; }
i8_mpc() { i_std --disable-static --docdir=/usr/share/doc/mpc; }
i8_attr() { i_std --disable-static --sysconfdir=/etc --docdir=/usr/share/doc/attr; }
i8_acl() { i_std --disable-static --docdir=/usr/share/doc/acl; }
i8_libcap() { sed -i '/install -m.*STA/d' libcap/Makefile && make prefix=/usr lib=lib && make prefix=/usr lib=lib install; }
i8_libxcrypt() {
  i_std --enable-hashes=strong,glibc --enable-obsolete-api=no --disable-static --disable-failure-tokens
}
i8_shadow() {
  sed -i 's/groups$(EXEEXT) //' src/Makefile.in
  find man -name Makefile.in -exec sed -i 's/groups\.1 / /' {} \;
  sed -e 's:#ENCRYPT_METHOD DES:ENCRYPT_METHOD YESCRYPT:' -e 's:/var/spool/mail:/var/mail:' \
      -e '/PATH=/{s@/sbin:@@;s@/bin:@@}' -i etc/login.defs
  touch /usr/bin/passwd
  ./configure --sysconfdir=/etc --disable-static --with-{b,yes}crypt --without-libbsd --with-group-name-max-length=32 &&
  make && make exec_prefix=/usr install && make -C man install-man &&
  pwconv && grpconv && mkdir -p /etc/default && useradd -D --gid 999 && sed -i '/MAIL/s/yes/no/' /etc/default/useradd &&
  echo 'root:lfs' | chpasswd
}
i8_gcc() {
  gcc_aarch64
  mkdir -v build && cd build
  ../configure --prefix=/usr LD=ld --enable-languages=c,c++ --enable-default-pie --enable-default-ssp \
    --enable-host-pie --disable-multilib --disable-bootstrap --disable-fixincludes --with-system-zlib &&
  make && make install || return 1
  local v; v=$(ver gcc)
  chown -R root:root /usr/lib/gcc/$(gcc -dumpmachine)/$v/include{,-fixed}
  ln -sfvr /usr/bin/cpp /usr/lib; ln -sfv gcc.1 /usr/share/man/man1/cc.1
  ln -sfv ../../libexec/gcc/$(gcc -dumpmachine)/$v/liblto_plugin.so /usr/lib/bfd-plugins/
  echo 'int main(){}' | cc -x c - -o /tmp/dummy && readelf -l /tmp/dummy | grep -q ld-linux-aarch64 || return 1
  mkdir -pv /usr/share/gdb/auto-load/usr/lib; mv -v /usr/lib/*gdb.py /usr/share/gdb/auto-load/usr/lib 2>/dev/null; true
}
i8_ncurses() {
  ./configure --prefix=/usr --mandir=/usr/share/man --with-shared --without-debug --without-normal \
    --with-cxx-shared --enable-pc-files --with-pkg-config-libdir=/usr/lib/pkgconfig && make &&
  make DESTDIR=$PWD/dest install && install -vm755 dest/usr/lib/libncursesw.so.6.* /usr/lib &&
  rm -v dest/usr/lib/libncursesw.so.6.* && sed -e 's/^#if.*XOPEN.*$/#if 1/' -i dest/usr/include/curses.h &&
  cp -av dest/* / || return 1
  for lib in ncurses form panel menu; do ln -sfv lib${lib}w.so /usr/lib/lib${lib}.so; ln -sfv ${lib}w.pc /usr/lib/pkgconfig/${lib}.pc; done
  ln -sfv libncursesw.so /usr/lib/libcurses.so
}
i8_gettext() { i_std --disable-static --docdir=/usr/share/doc/gettext && chmod -v 0755 /usr/lib/preloadable_libintl.so; }
i8_bash() { i_std --without-bash-malloc --with-installed-readline --docdir=/usr/share/doc/bash; }
i8_gdbm() { i_std --disable-static --enable-libgdbm-compat; }
i8_expat() { i_std --disable-static --docdir=/usr/share/doc/expat; }
i8_inetutils() {
  sed -i 's/def HAVE_TERMCAP_TGETENT/ 1/' telnet/telnet.c
  ./configure --prefix=/usr --bindir=/usr/bin --localstatedir=/var --disable-logger --disable-whois \
    --disable-rcp --disable-rexec --disable-rlogin --disable-rsh --disable-servers && make && make install &&
  mv -v /usr/{,s}bin/ifconfig
}
i8_less() { i_std --sysconfdir=/etc; }
i8_perl() { export BUILD_ZLIB=False BUILD_BZIP2=0; perl_cfg -D man1dir=/usr/share/man/man1 -D man3dir=/usr/share/man/man3 -D pager="/usr/bin/less -isR" && make && make install; }
i8_openssl() {
  ./config --prefix=/usr --openssldir=/etc/ssl --libdir=lib shared zlib-dynamic && make &&
  sed -i '/INSTALL_LIBS/s/libcrypto.a libssl.a//' Makefile && make MANSUFFIX=ssl install
}
i8_libelf() {
  ./configure --prefix=/usr --disable-debuginfod --enable-libdebuginfod=dummy && make &&
  make -C libelf install && install -vm644 config/libelf.pc /usr/lib/pkgconfig && rm -f /usr/lib/libelf.a
}
i8_libffi() { i_std --disable-static --with-gcc-arch=native; }
i8_sqlite() { i_std --disable-static --enable-fts{4,5} CPPFLAGS="-D SQLITE_ENABLE_COLUMN_METADATA=1 -D SQLITE_ENABLE_UNLOCK_NOTIFY=1 -D SQLITE_ENABLE_DBSTAT_VTAB=1 -D SQLITE_SECURE_DELETE=1"; }
i8_python() {
  for p in /sources/Python-$(ver Python)-*.patch; do [ -f "$p" ] && patch -Np1 -i "$p"; done
  ./configure --prefix=/usr --enable-shared --with-system-expat --enable-optimizations --without-static-libpython &&
  make && make install && mkdir -p /etc && printf '[global]\nroot-user-action = ignore\ndisable-pip-version-check = true\n' > /etc/pip.conf
}
i8_pip() { local n; n=$(basename "$PWD" | sed -E 's/-[0-9].*//'); pipinst "$n"; }
i8_ninja() {
  sed -i '/int Guess/a \  int   j = 0;\n  char* jobs = getenv( "NINJAJOBS" );\n  if ( jobs != NULL ) j = atoi( jobs );\n  if ( j > 0 ) return j;\n' src/ninja.cc
  python3 configure.py --bootstrap --verbose && install -vm755 ninja /usr/bin/
}
i8_kmod() { mkdir -p build && cd build && meson setup --prefix=/usr .. --buildtype=release -D manpages=false && ninja && ninja install; }
i8_coreutils() {
  patch -Np1 -i /sources/coreutils-$(ver coreutils)-i18n-1.patch && autoreconf -fv && automake -af &&
  FORCE_UNSAFE_CONFIGURE=1 ./configure --prefix=/usr --enable-no-install-program=kill,uptime && make && make install &&
  mv -v /usr/bin/chroot /usr/sbin
}
i8_gawk() { sed -i 's/extras//' Makefile.in; i_std; }
i8_findutils() { i_std --localstatedir=/var/lib/locate; }
i8_groff() { PAGE=A4 ./configure --prefix=/usr && make && make install; }
i8_iproute2() { sed -i /ARPD/d Makefile; rm -fv man/man8/arpd.8; make NETNS_RUN_DIR=/run/netns && make SBINDIR=/usr/sbin install; }
i8_kbd() {
  patch -Np1 -i /sources/kbd-$(ver kbd)-backspace-1.patch
  sed -i '/RESIZECONS_PROGS=/s/yes/no/' configure; sed -i 's/resizecons.8 //' docs/man/man8/Makefile.in
  i_std --disable-vlock
}
i8_tar() { FORCE_UNSAFE_CONFIGURE=1 i_std; }
i8_vim() {
  echo '#define SYS_VIMRC_FILE "/etc/vimrc"' >> src/feature.h
  i_std && ln -sfv vim /usr/bin/vi &&
  printf '" LFS Droid\nsource $VIMRUNTIME/defaults.vim\nlet skip_defaults_vim=1\nset nocompatible\nset backspace=2\nset mouse=\nsyntax on\nset background=dark\n' > /etc/vimrc
}
i8_systemd() {
  sed -e 's/GROUP="render"/GROUP="video"/' -e 's/GROUP="sgx", //' -i rules.d/50-udev-default.rules.in
  mkdir -p build && cd build
  meson setup .. --prefix=/usr --buildtype=release -D default-dnssec=no -D firstboot=false -D install-tests=false \
    -D ldconfig=false -D sysusers=false -D rpmmacrosdir=no -D homed=disabled -D man=disabled -D mode=release \
    -D pamconf=false -D dev-kvm-mode=0660 -D nobody-group=nogroup -D sysupdate=disabled -D ukify=disabled \
    -D docdir=/usr/share/doc/systemd && ninja && ninja install || return 1
  systemd-machine-id-setup || true; systemctl preset-all || true
}
i8_dbus() {
  mkdir -p build && cd build && meson setup --prefix=/usr --buildtype=release --wrap-mode=nofallback .. &&
  ninja && ninja install && ln -sfv /etc/machine-id /var/lib/dbus
}
i8_mandb() { i_std --docdir=/usr/share/doc/man-db --sysconfdir=/etc --disable-setuid --enable-cache-owner=bin --with-browser=/usr/bin/lynx --with-vgrind=/usr/bin/vgrind --with-grap=/usr/bin/grap; }
i8_procps() { i_std --docdir=/usr/share/doc/procps-ng --disable-static --disable-kill --enable-watch8bit --with-systemd; }
i8_utillinux() {
  ./configure --bindir=/usr/bin --libdir=/usr/lib --runstatedir=/run --sbindir=/usr/sbin --disable-chfn-chsh \
    --disable-login --disable-nologin --disable-su --disable-setpriv --disable-runuser --disable-pylibmount \
    --disable-liblastlog2 --disable-static --without-python ADJTIME_PATH=/var/lib/hwclock/adjtime \
    --docdir=/usr/share/doc/util-linux && make && make install
}
i8_e2fs() {
  mkdir -v build && cd build && ../configure --prefix=/usr --sysconfdir=/etc --enable-elf-shlibs \
    --disable-libblkid --disable-libuuid --disable-uuidd --disable-fsck && make && make install &&
  rm -fv /usr/lib/{libcom_err,libe2p,libext2fs,libss}.a
}
i8_config() {
  echo lfs > /etc/hostname
  printf 'NAME="Linux From Scratch"\nVERSION="%s (LFS Droid, aarch64)"\nID=lfs\nPRETTY_NAME="Linux From Scratch %s on a phone"\nVERSION_CODENAME="pointless"\n' "${BOOK%-*}" "${BOOK%-*}" > /etc/os-release
  echo "${BOOK%-*}-systemd" > /etc/lfs-release
  cat > /etc/profile <<'P'
export LANG=en_US.UTF-8
export PS1='\[\e[38;2;187;154;247m\]\u@lfs\[\e[0m\]:\[\e[38;2;122;162;247m\]\w\[\e[0m\]\$ '
P
  printf 'source /etc/profile\n' > /root/.bashrc
  printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf
  printf '# no kernel, no bootloader: this LFS lives in a chroot on a phone\n' > /etc/fstab
}
i9_cmake() {
  sed -i '/"lib64"/s/64//' Modules/GNUInstallDirs.cmake
  ./bootstrap --prefix=/usr --parallel=${JOBS:-$(nproc)} --no-system-libs -- -DCMAKE_USE_OPENSSL=ON -DBUILD_TESTING=OFF &&
  make && make install
}
i9_fastfetch() {
  cmake -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr -DBUILD_TESTS=OFF &&
  cmake --build build && cmake --install build
}
i9_strip() {
  # Keep the phone's storage alive: drop debug info like the book's optional stripping step.
  find /usr/lib /usr/libexec -type f -name '*.so*' ! -name '*dbg' -exec strip --strip-unneeded {} + 2>/dev/null
  find /usr/bin /usr/sbin /usr/libexec -type f -exec strip --strip-all {} + 2>/dev/null; true
}

# ---------------------------------------------------------------- running
enter_lfs() {  # mount the virtual filesystems the book's chapter 7 wants, once per namespace
  for d in dev proc sys run dev/pts; do mkdir -p $LFS/$d; done
  mountpoint -q $LFS/dev || mount -o bind /dev $LFS/dev
  mountpoint -q $LFS/dev/pts || mount -t devpts -o gid=5,mode=0620 devpts $LFS/dev/pts
  mountpoint -q $LFS/proc || mount -t proc proc $LFS/proc
  mountpoint -q $LFS/sys || mount -t sysfs sysfs $LFS/sys
  mountpoint -q $LFS/run || mount -t tmpfs tmpfs $LFS/run
  mkdir -p $LFS/dev/shm; mountpoint -q $LFS/dev/shm || mount -t tmpfs -o nosuid,nodev tmpfs $LFS/dev/shm
  cp -L /etc/resolv.conf $LFS/etc/resolv.conf 2>/dev/null; true
}
in_lfs() {  # run [args] with this script inside the LFS chroot
  enter_lfs
  cp -f "$0" $LFS/root/lfs-build.sh
  chroot $LFS /usr/bin/env -i HOME=/root TERM=${TERM:-xterm} PATH=/usr/bin:/usr/sbin JOBS=${JOBS:-} TZ=${TZ:-UTC} \
    /bin/bash /root/lfs-build.sh inner "$@"
}

# Builds one step: unpack, run its function, clean up. Called directly or via in_lfs.
build_one() {
  local name=$1 pkg=$2 fn=$3 dir
  dir=$(mktemp -d "${TMPDIR:-/tmp}/lfs.XXXX") || return 1
  cd "$dir" || return 1
  if [ "$pkg" != - ]; then
    local t; t=$(tb "$pkg"); [ -n "$t" ] || { echo "no source tarball for $pkg"; return 1; }
    tar -xf "$t" || return 1
    cd "$(ls -d */ | head -n1)" || return 1
  fi
  ( set -e; $fn ); local rc=$?
  cd /; rm -rf "$dir"
  return $rc
}

if [ $INNER = 1 ]; then build_one "$@"; exit $?; fi

[ "$1" = plan ] && { echo "$PLAN" | grep -v '^$'; exit 0; }
[ "$1" = shell ] && { enter_lfs; exec chroot $LFS /usr/bin/env -i HOME=/root TERM=${TERM:-xterm-256color} PATH=/usr/bin:/usr/sbin /bin/bash --login; }

mkdir -p "$SRC" "$LOGDIR"; touch "$STATE"
total=$(echo "$PLAN" | grep -c .)
n=0
for line in $PLAN; do
  n=$((n+1))
  IFS=: read -r ch name pkg fn <<< "$line"
  key="$ch:$name"
  is_done "$key" && continue
  echo "@@LFS step $n $total $ch $name"
  log=$LOGDIR/$(printf '%03d' $n)-ch$ch-$name.log
  case $fn in
    i*) in_lfs "$name" "$pkg" "$fn" 2>&1 | tee "$log" ;;
    *) build_one "$name" "$pkg" "$fn" 2>&1 | tee "$log" ;;
  esac
  rc=${PIPESTATUS[0]}
  if [ "$rc" -ne 0 ]; then echo "@@LFS fail $key $rc"; echo "log: $log"; exit "$rc"; fi
  mark "$key"; echo "@@LFS ok $key"
done
echo "@@LFS done"
