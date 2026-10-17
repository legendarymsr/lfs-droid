# LFS Droid

**Build Linux From Scratch on your phone. Why? No reason. None at all.**

LFS Droid is an Android app that compiles a [Linux From Scratch](https://www.linuxfromscratch.org/lfs/)
13.1 system for aarch64, inside a KernelSU-Next root chroot, on the phone in your pocket.
It takes an hour or two, turns the phone into a hand warmer, eats about 3 GB of storage, and at the
end you get… a shell. And fastfetch. So you can screenshot it. That's the whole point.

It is completely pointless. It is also extremely fun.

## What it does

1. Downloads a tiny **Alpine aarch64 minirootfs** to act as the "host system" (Android has no compiler).
2. Installs gcc, make, bison, python, cmake and friends into it with `apk`.
3. Builds the Linux API headers and **musl** into `$LFS` with Alpine's gcc.
4. Builds everything else with that same gcc pointed at `$LFS` as its sysroot, so it all links
   against the musl it just built, not Alpine's copy. Then **fastfetch**, because what is LFS
   without a screenshot.
5. Strips the ELF binaries and checks that bash is linked against musl and fastfetch runs in the chroot.

### The diet (0.2.0) and the musl (0.3.0)

The full book took a day or two and ~18 GB. Most of that was a cross toolchain, GCC twice, and a
load of packages that do nothing in a chroot. So now it skips:

- **The cross toolchain and temporary tools** (ch. 5–7 bootstrap). Alpine's gcc does the job.
- **The extras**: systemd (a chroot has no init), GCC/binutils inside `$LFS`, Perl, Python,
  Texinfo, gettext, man pages, tests, docs and locales (musl's `C.UTF-8` is built in).
- **glibc**, since 0.3.0. In the spirit of the musl-LFS forks it uses [musl](https://musl.libc.org/)
  1.2.6 instead, which compiles in minutes where glibc took an hour or more.

What gets built: linux headers, musl, zlib, ncurses, bash, coreutils, grep, sed, gawk, findutils,
diffutils, tar, gzip, xz, file, less, util-linux (lsblk, findmnt, mount, dmesg, more, uuidgen),
procps-ng, nano, iana-etc and fastfetch. 27 steps instead of 113. Roughly 1–2 hours at `-j4` and
~3 GB peak (most of it is the unpacked Linux tree for the headers; the result is ~0.2 GB).
It's still "Linux From Scratch" in `/etc/os-release`, just with a musl in it, from slightly less scratch.

No compiler in the result, so you can't build stuff inside it. No kernel or bootloader either:
it lives in a chroot, it'll never boot, and it's at peace with that.

**Coming from 0.1.x or 0.2.x?** An old build is detected on Start, the LFS tree is wiped and the new
build starts over. The downloaded sources are kept.

## Features

- **Start / Resume** button; the build runs as a foreground service with a progress notification
  (and a Stop button), so it keeps going with the screen off.
- Shows the chapter, the package, step *N of M*, a progress bar and the live log tail.
- **Checkpoints**: every finished step is recorded, so after a failure, a Stop or a reboot it picks
  up at the first unfinished step. Logs per step live in `$LFS/sources/.logs`.
- The build is a **detached root process** (its own session via `setsid`/`nohup` under KernelSU-Next `su`), so
  Android killing the app or the service doesn't kill the build. The app just follows its log and
  status files and reattaches when you open it again. Stop kills the whole process group.
- `make -j` is configurable; the default assumes ~2 GB RAM per job (max 4) so lmkd doesn't eat your phone.
- Crash reports and Android's "last exit reason" are shown in the app, with a copy button.
- Free-space check before starting and resuming.
- Built-in **terminal** (a small PTY-backed VT100 emulator with an extra-keys row) that opens a root
  shell in the finished LFS, or in the Alpine build host while the build is still going. It draws
  on an exact cell grid, resizes the shell when the keyboard opens, on rotation and on the fold, and
  pinch-to-zoom changes the font size (7–24 sp, remembered).
- Tokyo Night everything.

## Requirements

- An arm64 phone rooted with **KernelSU-Next** (grant LFS Droid in the KernelSU-Next manager; Magisk and APatch still work as fallbacks).
- ~3 GB free, a charger, patience, and no particular reason.

## Warnings, sincerely

- The chroot runs as the phone's **real root**. It's not a sandbox. Mounts happen in a private mount
  namespace so nothing leaks into Android, but root is root.
- Tests from the book are skipped (`make check` on a phone is a war crime). It's also all
  run as root instead of an `lfs` user. The LFS editors would be disappointed. Sorry, Bruce.
- There's no login and no password: the terminal drops you straight into a root shell.

## Building the APK

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The build script itself is `app/src/main/assets/lfs-build.sh`, adapted from the x86_64 libre
flow in [legenddots](https://github.com/legendarymsr/legenddots) for aarch64.

## Licence

GPL-3.0. See [LICENSE](LICENSE). Linux From Scratch is © Gerard Beekmans and the LFS team; this app
just follows their book, badly, on a phone.
