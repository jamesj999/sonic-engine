# Linux desktop entry

Wayland has no client-side window-icon protocol, so `glfwSetWindowIcon` only reaches X11
sessions. Under Wayland the compositor pairs a window with an installed `.desktop` entry by
app id — the engine reports `openggf`, matching `StartupWMClass` below.

Install for the current user:

```bash
install -Dm644 openggf.png ~/.local/share/icons/hicolor/256x256/apps/openggf.png
install -Dm644 openggf.desktop ~/.local/share/applications/openggf.desktop
update-desktop-database ~/.local/share/applications
```

Edit the `Exec=` line to point at wherever the built
`OpenGGF-<version>-jar-with-dependencies.jar` actually lives.

## macOS

`glfwSetWindowIcon` is a no-op on macOS. Running from a plain JAR, the engine sets the Dock
icon through the AWT taskbar API instead. A distributable build should carry a real `.icns`
in its bundle — `jpackage --icon` — which supersedes the runtime call.
