#!/bin/bash
# LFS Droid: Linux From Scratch 13.1, aarch64, built on a phone. The diet edition.
# Runs inside the Alpine host chroot as root. Resumable: every finished step is
# a line in $LFS/sources/.lfsdroid-state, so a re-run skips straight to the
# first unfinished one. Progress lines for the app look like:
#   @@LFS step <n> <total> <chapter> <name>
#   @@LFS ok <name> | @@LFS fail <name> <code> | @@LFS done
#
# What's different from the book (on purpose, this is a phone):
#  - musl instead of glibc, in the spirit of the musl-LFS forks. It builds in a
#    couple of minutes where glibc took an hour or more.
#  - No cross toolchain, no temporary tools (chapters 5-7 bootstrap). Alpine's
#    own gcc builds musl into $LFS, then every other package is compiled with
#    that same gcc pointed at $LFS as its sysroot (see s_toolwrap), so it all
#    links against the musl in $LFS, never Alpine's copy.
#  - Only what a root shell + fastfetch needs. No systemd (a chroot has no init),
#    no Perl/Python/Texinfo/gettext/man pages, no tests, no locales (musl's
#    C.UTF-8 is built in), and no compiler inside $LFS.
set -o pipefail
BOOK=13.1
BOOK_URL=https://www.linuxfromscratch.org/lfs/downloads/$BOOK-systemd
FASTFETCH_VER=2.69.0
MUSL_VER=1.2.6
NANO_VER=8.6
FORMAT=3

LFS=${LFS:-/mnt/lfs}
SRC=$LFS/sources
STATE=$SRC/.lfsdroid-state
LOGDIR=$SRC/.logs
TOOLS=$SRC/.toolbin                 # lfs-cc: Alpine's gcc aimed at $LFS
export LFS LC_ALL=POSIX
export CC=$TOOLS/lfs-cc CPP="$TOOLS/lfs-cc -E"
export PKG_CONFIG_LIBDIR=$LFS/usr/lib/pkgconfig:$LFS/usr/share/pkgconfig PKG_CONFIG_SYSROOT_DIR=$LFS
export MAKEFLAGS="-j${JOBS:-$(nproc)}"
export PATH=$TOOLS:/usr/bin:/usr/sbin:/bin:/sbin
export FORCE_UNSAFE_CONFIGURE=1     # root the whole way; the book uses an lfs user
umask 022

is_done() { [ -f "$STATE" ] && grep -qx "$1" "$STATE"; }
mark() { echo "$1" >> "$STATE"; }
tb() { ls "$SRC"/$1-[0-9]*.tar.* 2>/dev/null | grep -v -e docs -e html -e '\.patch' | head -n1; }
ver() { basename "$(tb "$1")" | sed -E "s/^$1-//; s/\.tar\..*//"; }

# ---------------------------------------------------------------- the plan
# chapter:name:tarball-prefix:function   (tarball "-" = no source)
PLAN="
4:layout:-:s_layout
4:sources:-:s_sources
5:toolwrap:-:s_toolwrap
5:linux-headers:linux:s_headers
5:musl:musl:s_musl
6:zlib:zlib:s_zlib
6:ncurses:ncurses:s_ncurses
6:bash:bash:s_bash
6:coreutils:coreutils:s_coreutils
6:grep:grep:s_std
6:sed:sed:s_std
6:gawk:gawk:s_gawk
6:findutils:findutils:s_findutils
6:diffutils:diffutils:s_diffutils
6:tar:tar:s_std
6:gzip:gzip:s_std
6:xz:xz:s_xz
6:file:file:s_file
6:less:less:s_std
6:util-linux:util-linux:s_utillinux
6:procps-ng:procps-ng:s_procps
6:nano:nano:s_nano
6:iana-etc:iana-etc:s_iana
7:config:-:s_config
9:fastfetch:fastfetch:s_fastfetch
9:strip:-:s_strip
9:check:-:s_check
"
# Sources we actually use out of the book's wget-list.
PKGS="linux zlib ncurses bash coreutils grep sed gawk findutils diffutils tar gzip xz file less util-linux procps-ng iana-etc"

# ---------------------------------------------------------------- helpers
# Host and target are both aarch64 musl, so plain native configures work:
# test programs link against $LFS's musl and run fine on the Alpine host.
std_cfg() { ./configure --prefix=/usr "$@"; }
s_std() { std_cfg && make && make DESTDIR=$LFS install; }

# ---------------------------------------------------------------- chapter 4
s_layout() {
  mkdir -pv $LFS/{etc,var,sources,root,tmp,dev,proc,sys,run} $LFS/usr/{bin,lib,sbin,include}
  for i in bin lib sbin; do [ -e $LFS/$i ] || ln -sv usr/$i $LFS/$i; done
  ln -sfv lib $LFS/usr/lib64; ln -sfv usr/lib $LFS/lib64 2>/dev/null; true
}
s_sources() {
  cd $SRC
  wget -nv -O wget-list $BOOK_URL/wget-list && wget -nv -O md5sums $BOOK_URL/md5sums || return 1
  local re; re=$(echo $PKGS | sed 's/ /|/g')
  grep -E "/($re)-[0-9][^/]*\.tar\." wget-list > wget-list.droid
  echo "https://musl.libc.org/releases/musl-$MUSL_VER.tar.gz" >> wget-list.droid
  echo "https://www.nano-editor.org/dist/v${NANO_VER%%.*}/nano-$NANO_VER.tar.xz" >> wget-list.droid
  echo "https://github.com/fastfetch-cli/fastfetch/archive/refs/tags/$FASTFETCH_VER.tar.gz" >> wget-list.droid
  local n=0 t f; t=$(wc -l < wget-list.droid)
  while read -r url; do
    n=$((n+1)); f=${url##*/}
    case $url in *fastfetch*) f=fastfetch-$FASTFETCH_VER.tar.gz;; esac
    echo "@@LFS fetch $n $t $f"
    [ -s "$f" ] && continue
    wget -nv -c -O "$f.part" "$url" && mv "$f.part" "$f" || { echo "download failed: $url"; return 1; }
  done < wget-list.droid
  # Only check the book's files we fetched.
  sed 's@.*/@@' wget-list.droid | grep -Fwf - md5sums | md5sum -c --quiet
}

# ---------------------------------------------------------------- chapter 5
# The whole "toolchain": Alpine's gcc, told to take headers, crt files and libc
# from $LFS and to use the static libgcc (Alpine's libgcc_s isn't in $LFS).
# --sysroot only moves the headers: Alpine's gcc finds crt files and libc
# relative to itself (= Alpine's /usr/lib), hence -B/-L. Its default loader,
# /lib/ld-musl-aarch64.so.1, is the same path in $LFS.
s_toolwrap() {
  mkdir -p $TOOLS
  cat > $TOOLS/lfs-cc <<W
#!/bin/sh
case " \$* " in *" -E "*|*" -c "*|*" -S "*|*" -M "*) exec gcc --sysroot=$LFS "\$@";; esac
exec gcc --sysroot=$LFS -B$LFS/usr/lib -L$LFS/usr/lib -static-libgcc -Wl,-rpath-link,$LFS/usr/lib "\$@"
W
  chmod 755 $TOOLS/lfs-cc
  # Alpine's gcc spec pulls in -lssp_nonshared; musl has the real stack
  # protector, so an empty one keeps the linker happy.
  mkdir -p $LFS/usr/lib; ar rc $LFS/usr/lib/libssp_nonshared.a
}
s_headers() {
  make mrproper && make ARCH=arm64 headers &&
  find usr/include -type f ! -name '*.h' -delete && cp -r usr/include $LFS/usr
}
s_musl() {
  # musl builds with -nostdinc/-nostdlib, so plain Alpine gcc is all it needs.
  ./configure --prefix=/usr --syslibdir=/usr/lib --disable-static CC=gcc &&
  make && make DESTDIR=$LFS install || return 1
  ln -sfv ../lib/libc.so $LFS/usr/bin/ldd
  # Sanity check, like the book's: does lfs-cc link against OUR musl?
  local log=$LFS/tmp/sanity.log
  mkdir -p $LFS/tmp
  echo 'int main(){return 0;}' | lfs-cc -x c - -o $LFS/tmp/sanity -Wl,-t > $log 2>&1 || { cat $log; return 1; }
  grep -q "^$LFS/.*crt1\.o" $log && grep -q "^$LFS/.*libc\.so" $log &&
  ! grep -E '^/(usr/)?lib/(libc|crt1)' $log &&
  readelf -l $LFS/tmp/sanity | grep -q 'ld-musl-aarch64.so.1' || { echo "toolchain sanity check failed"; cat $log; return 1; }
  rm -f $LFS/tmp/sanity $log
}

# ---------------------------------------------------------------- chapter 6
s_zlib() { ./configure --prefix=/usr && make && make DESTDIR=$LFS install && rm -f $LFS/usr/lib/libz.a; }
s_ncurses() {
  std_cfg --mandir=/usr/share/man --with-shared --without-normal --without-debug --without-cxx --without-cxx-binding \
    --without-ada --without-manpages --disable-stripping --enable-pc-files --with-pkg-config-libdir=/usr/lib/pkgconfig AWK=gawk &&
  make && make DESTDIR=$LFS install || return 1
  for lib in ncurses form panel menu; do ln -sfv lib${lib}w.so $LFS/usr/lib/lib${lib}.so; ln -sfv ${lib}w.pc $LFS/usr/lib/pkgconfig/${lib}.pc; done
  ln -sfv libncursesw.so $LFS/usr/lib/libcurses.so
  sed -e 's/^#if.*XOPEN.*$/#if 1/' -i $LFS/usr/include/curses.h
}
s_bash() { std_cfg --without-bash-malloc --disable-nls && make && make DESTDIR=$LFS install && ln -sfv bash $LFS/usr/bin/sh; }
s_coreutils() {
  std_cfg --disable-nls --enable-install-program=hostname --enable-no-install-program=kill,uptime &&
  make && make DESTDIR=$LFS install && mv -v $LFS/usr/bin/chroot $LFS/usr/sbin
}
s_gawk() { sed -i 's/extras//' Makefile.in; std_cfg --disable-nls && make && make DESTDIR=$LFS install; }
s_findutils() { std_cfg --disable-nls --localstatedir=/var/lib/locate && make && make DESTDIR=$LFS install; }
s_diffutils() { std_cfg --disable-nls && make && make DESTDIR=$LFS install; }
s_xz() { std_cfg --disable-static --disable-nls --disable-doc && make && make DESTDIR=$LFS install && rm -fv $LFS/usr/lib/liblzma.la; }
s_file() {
  # Native build, so the freshly built file compiles its own magic database.
  std_cfg --disable-libseccomp && make && make DESTDIR=$LFS install && rm -fv $LFS/usr/lib/libmagic.la
}
s_utillinux() {
  # Just the everyday tools, no daemons, no logins, no python.
  std_cfg --libdir=/usr/lib --runstatedir=/run --disable-all-programs --disable-nls --disable-static \
    --enable-libblkid --enable-libmount --enable-libsmartcols --enable-libuuid \
    --enable-lsblk --enable-findmnt --enable-mount --enable-dmesg --enable-uuidgen --enable-more \
    --without-python --without-systemd --without-udev --without-tinfo --without-readline \
    --disable-makeinstall-chown --disable-makeinstall-setuid &&
  make && make DESTDIR=$LFS install && find $LFS/usr/lib -name 'lib*.la' -delete
}
s_procps() {
  # musl has no real utmp, so w is useless; drop it like Alpine does.
  std_cfg --disable-static --disable-kill --disable-nls --without-systemd --enable-watch8bit --disable-w &&
  make && make DESTDIR=$LFS install && rm -fv $LFS/usr/lib/libproc2.la
}
s_nano() { std_cfg --sysconfdir=/etc --disable-nls --enable-utf8 && make && make DESTDIR=$LFS install; }
s_iana() { cp services protocols $LFS/etc; }

# ---------------------------------------------------------------- chapter 7: the base files
s_config() {
  mkdir -pv $LFS/{home,mnt,opt,srv} $LFS/var/{cache,lib,log,tmp} $LFS/usr/share/zoneinfo
  install -dv -m 0750 $LFS/root; install -dv -m 1777 $LFS/tmp $LFS/var/tmp
  ln -sfv /run $LFS/var/run; ln -sfv /proc/self/mounts $LFS/etc/mtab
  printf '127.0.0.1 localhost lfs\n::1 localhost\n' > $LFS/etc/hosts
  printf 'root:x:0:0:root:/root:/bin/bash\nbin:x:1:1:bin:/dev/null:/usr/bin/false\nnobody:x:65534:65534:Unprivileged User:/dev/null:/usr/bin/false\n' > $LFS/etc/passwd
  printf 'root:x:0:\nbin:x:1:\nsys:x:2:\ntty:x:5:\ndaemon:x:6:\ndisk:x:8:\nutmp:x:13:\nwheel:x:97:\nusers:x:999:\nnogroup:x:65534:\n' > $LFS/etc/group
  # musl's answer to ld.so.conf
  printf '/usr/lib\n/usr/local/lib\n/opt/lib\n' > $LFS/etc/ld-musl-aarch64.path
  # Time zone data is just data; the host's tzdata is the same upstream release.
  cp -a /usr/share/zoneinfo/. $LFS/usr/share/zoneinfo/ 2>/dev/null
  ln -sfv /usr/share/zoneinfo/${TZ:-UTC} $LFS/etc/localtime
  echo lfs > $LFS/etc/hostname
  printf 'NAME="Linux From Scratch"\nVERSION="%s (LFS Droid, aarch64, musl)"\nID=lfs\nPRETTY_NAME="Linux From Scratch %s on a phone"\nVERSION_CODENAME="pointless"\n' "$BOOK" "$BOOK" > $LFS/etc/os-release
  echo "$BOOK" > $LFS/etc/lfs-release
  printf 'DISTRIB_ID="Linux From Scratch"\nDISTRIB_RELEASE="%s"\nDISTRIB_CODENAME="pointless"\nDISTRIB_DESCRIPTION="Linux From Scratch on a phone"\n' "$BOOK" > $LFS/etc/lsb-release
  cat > $LFS/etc/profile <<'P'
export LANG=C.UTF-8
export PATH=/usr/bin:/usr/sbin
export PS1='\[\e[38;2;187;154;247m\]\u@lfs\[\e[0m\]:\[\e[38;2;122;162;247m\]\w\[\e[0m\]\$ '
P
  printf 'source /etc/profile\n' > $LFS/root/.bashrc
  printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > $LFS/etc/resolv.conf
  printf '# no kernel, no bootloader: this LFS lives in a chroot on a phone\n' > $LFS/etc/fstab
}

# ---------------------------------------------------------------- extras
s_fastfetch() {
  cat > tc.cmake <<T
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_SYSROOT $LFS)
set(CMAKE_C_COMPILER $TOOLS/lfs-cc)
set(CMAKE_FIND_ROOT_PATH $LFS)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
T
  # Fastfetch's optional GUI/GPU bits have nothing to find in here; zlib is there.
  # IS_MUSL skips the glibc-only bits (execinfo backtraces and friends).
  PKG_CONFIG_LIBDIR=$LFS/usr/lib/pkgconfig PKG_CONFIG_SYSROOT_DIR=$LFS \
  cmake -B build -G Ninja -DCMAKE_TOOLCHAIN_FILE=$PWD/tc.cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr \
    -DBUILD_TESTS=OFF -DBUILD_FLASHFETCH=OFF -DENABLE_SYSTEM_YYJSON=OFF -DIS_MUSL=ON -DENABLE_EMBEDDED_PCIIDS=OFF \
    -DCMAKE_JOB_POOLS=j=${JOBS:-$(nproc)} -DCMAKE_JOB_POOL_COMPILE=j &&
  cmake --build build && DESTDIR=$LFS cmake --install build
}
s_strip() {
  # From the host: there are no binutils inside this LFS.
  find $LFS/usr/lib -type f -name '*.so*' -exec strip --strip-unneeded {} + 2>/dev/null
  find $LFS/usr/bin $LFS/usr/sbin $LFS/usr/libexec -type f -exec strip --strip-all {} + 2>/dev/null
  rm -rf $LFS/usr/share/{doc,info,man,locale}/* $LFS/usr/lib/libssp_nonshared.a; true
}
s_check() {
  enter_lfs
  chroot $LFS /usr/bin/env -i HOME=/root PATH=/usr/bin:/usr/sbin /bin/bash -c \
    'ldd /usr/bin/bash | grep -q ld-musl && ls / >/dev/null && fastfetch --pipe -l lfs --structure Title:Separator:OS:Kernel:Shell:Memory'
}

# ---------------------------------------------------------------- running
enter_lfs() {  # mount the virtual filesystems, once per namespace
  for d in dev proc sys run dev/pts; do mkdir -p $LFS/$d; done
  mountpoint -q $LFS/dev || mount -o bind /dev $LFS/dev
  mountpoint -q $LFS/dev/pts || mount -t devpts -o gid=5,mode=0620 devpts $LFS/dev/pts
  mountpoint -q $LFS/proc || mount -t proc proc $LFS/proc
  mountpoint -q $LFS/sys || mount -t sysfs sysfs $LFS/sys
  mountpoint -q $LFS/run || mount -t tmpfs tmpfs $LFS/run
  mkdir -p $LFS/dev/shm; mountpoint -q $LFS/dev/shm || mount -t tmpfs -o nosuid,nodev tmpfs $LFS/dev/shm
  cp -L /etc/resolv.conf $LFS/etc/resolv.conf 2>/dev/null; true
}

# Builds one step: unpack, run its function, clean up.
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

# Builds from 0.1.x (full book) and 0.2.x (glibc) left a different state file.
# Mixing the two would be a mess, so start the LFS tree over and keep the
# downloaded tarballs: same book, same files.
migrate_old() {
  [ -s "$STATE" ] || return 0
  head -n1 "$STATE" | grep -qx "#format $FORMAT" && return 0
  echo "@@LFS migrate old-build"
  echo "== Found a build from an older LFS Droid. The 0.3 (musl) build is different, so the LFS tree"
  echo "== is wiped and started over. Downloaded sources in $SRC are kept."
  for m in dev/shm dev/pts dev proc sys run; do umount -l $LFS/$m 2>/dev/null; done
  find $LFS -mindepth 1 -maxdepth 1 ! -name sources -exec rm -rf {} +
  rm -rf "$STATE" "$LOGDIR" $TOOLS
}

[ "$1" = plan ] && { echo "$PLAN" | grep -v '^$'; exit 0; }
[ "$1" = shell ] && { enter_lfs; exec chroot $LFS /usr/bin/env -i HOME=/root TERM=${TERM:-xterm-256color} PATH=/usr/bin:/usr/sbin /bin/bash --login; }

mkdir -p "$SRC"; migrate_old
mkdir -p "$LOGDIR"; [ -s "$STATE" ] || echo "#format $FORMAT" > "$STATE"
total=$(echo "$PLAN" | grep -c .)
n=0
for line in $PLAN; do
  n=$((n+1))
  IFS=: read -r ch name pkg fn <<< "$line"
  key="$ch:$name"
  is_done "$key" && continue
  echo "@@LFS step $n $total $ch $name"
  log=$LOGDIR/$(printf '%03d' $n)-ch$ch-$name.log
  build_one "$name" "$pkg" "$fn" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  if [ "$rc" -ne 0 ]; then echo "@@LFS fail $key $rc"; echo "log: $log"; exit "$rc"; fi
  mark "$key"; echo "@@LFS ok $key"
done
echo "@@LFS done"
