# Security policy

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Use GitHub's private reporting instead:
**Security tab → Report a vulnerability** on this repository. Include what you found, how to reproduce it, the Aster
version (Settings ▸ About), your Android version and device, and the server and its version.

Aster is maintained by one person, so a reply can take a few days. You'll hear back that the report arrived, then
what will be done about it. A fix is released before the details are made public, and you'll be credited in the
advisory unless you'd rather not be.

## What counts

Aster signs in to media servers, keeps those sign-ins on your device, and plays files and menus that come from
servers and from other people. These are in scope:

- **Sign-ins and tokens:** anything that exposes a server's access token, a password or a Plex sign-in, for example
  through a log, a crash report, a backup, a file other apps can read, or a request sent somewhere other than the
  server it belongs to. Sign-ins are encrypted with a key held in the Android Keystore.
- **Server responses:** a malicious or compromised server, or anyone between you and it, making the app run code,
  read or write files outside Aster's own storage, or reach other addresses on your network.
- **Media and menus:** a file, subtitle track or disc menu that makes the app run code, crash in a way an attacker can
  steer, or load something the menu isn't allowed to name.
- **Links:** an `aster://` link, or a link from another app, that makes Aster do something the person who opened it
  didn't mean to.
- **Installing and updating:** anything in the release APK, or how it's signed, that lets another app change what Aster
  runs.

Not in scope here:

- **Problems in Jellyfin, Emby or Plex themselves:** report those to their projects. Problems in Aster's server
  plugins go to [aster-companion-plugins](https://github.com/AsterMediaPlayer/aster-companion-plugins/security).
- **Plain `http://` servers:** Aster allows them, since many home servers have no certificate, and warns when one is
  reached over the internet. That the traffic isn't encrypted is the server's setup, not a flaw in Aster.
- **Someone with full control of your device:** with root, or an unlocked device in their hands, they can already read
  what the device can.

## Getting Aster safely

Download Aster only from this repository's [Releases](https://github.com/AsterMediaPlayer/aster-android/releases).
Those APKs are signed with the project's key. Be wary of a copy from anywhere else, since an APK from elsewhere can't be
checked against it.

## Supported versions

Aster is in beta. Fixes go to `main` and ship in the next release, so please use the latest one.
