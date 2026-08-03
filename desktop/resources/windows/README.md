# Bundled core

`sing-box.exe` is dropped here by CI before packaging and ends up beside the
installed application, where `SingBoxProcess` launches it as a child process.

It is not committed: it is ~40 MB of prebuilt binary and comes straight from
the official SagerNet release for the pinned version.
