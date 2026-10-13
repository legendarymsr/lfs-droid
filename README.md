# LFS Droid

**Build Linux From Scratch on your phone. Why? No reason. None at all.**

LFS Droid is an Android app that compiles a complete [Linux From Scratch](https://www.linuxfromscratch.org/lfs/)
13.1 (systemd book) base system for aarch64, inside a Magisk-root chroot, on the phone in your pocket.
It takes a day or two, turns the phone into a hand warmer, eats about 18 GB of storage, and at the
end you get… a shell. And fastfetch. So you can screenshot it. That's the whole point.

It is completely pointless. It is also extremely fun.

## What it does

1. Downloads a tiny **Alpine aarch64 minirootfs** to act as the "host system" (Android has no compiler).
2. Installs gcc, make, bison, perl, python and friends into it with `apk`.
3. Runs the book, chapter by chapter: the cross toolchain (ch. 5), temporary tools (ch. 6),
   the chroot and extra tools (ch. 7) and the whole base system (ch. 8). No kernel, no bootloader:
   it lives in a chroot, it'll never boot, and it's at peace with that.
4. Builds **CMake** and **fastfetch** from source on top, because what is LFS without a screenshot.
5. Strips everything so your storage survives.

No BLFS, no Xorg, no desktop. Just the base system and a terminal.

## Features

- **Start / Resume** button; the build runs as a foreground service with a progress notification
  (and a Stop button), so it keeps going with the screen off.
- Shows the chapter, the package, step *N of M*, a progress bar and the live log tail.
- **Checkpoints**: every finished step is recorded, so after a failure, a Stop or a reboot it picks
  up at the first unfinished step. Logs per step live in `$LFS/sources/.logs`.
- Free-space check before starting and resuming.
- Built-in **terminal** (a small PTY-backed VT100 emulator with an extra-keys row) that opens a root
  shell in the finished LFS, or in the Alpine build host while the build is still going.
- Tokyo Night everything.

## Requirements

- An arm64 phone rooted with **Magisk** (KernelSU/APatch busybox are also picked up, untested).
- ~18 GB free, a charger, patience, and no particular reason.

## Warnings, sincerely

- The chroot runs as the phone's **real root**. It's not a sandbox. Mounts happen in a private mount
  namespace so nothing leaks into Android, but root is root.
- Tests from the book are skipped (`make check` on a phone is a war crime). It's also all
  run as root instead of an `lfs` user. The LFS editors would be disappointed. Sorry, Bruce.
- The root password in the result is `lfs`.

## Building the APK

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The build script itself is `app/src/main/assets/lfs-build.sh`, adapted from the x86_64 libre
flow in [legenddots](https://github.com/legendarymsr/legenddots) for aarch64.

## Licence

GPL-3.0. See [LICENSE](LICENSE). Linux From Scratch is © Gerard Beekmans and the LFS team; this app
just follows their book, badly, on a phone.
