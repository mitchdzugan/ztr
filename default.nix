with import <nixpkgs> { };
mkShell {
  buildInputs = [
    at-spi2-atk
    atkmm
    cairo
    cargo
    cargo-tauri.hook
    gdk-pixbuf
    glib
    gobject-introspection
    gobject-introspection.dev
    gtk3
    harfbuzz
    librsvg
    libsoup_3
    openssl
    pango
    pkg-config
    rustc
    webkitgtk_4_1
    webkitgtk_4_1.dev
  ];
}
