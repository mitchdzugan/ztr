{
  description = "🬷z🬛 text renderer";
  inputs.nixpkgs.url = "nixpkgs/nixos-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  # inputs.zn-nix.url = "path:/home/dz/Projects/zn.nix";
  inputs.zn-nix.url = "github:mitchdzugan/zn.nix";
  inputs.zn-nix.inputs.nixpkgs.follows = "nixpkgs";
  outputs = { self, nixpkgs, zn-nix, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        version = builtins.substring 0 8 self.lastModifiedDate;
        pkgs = nixpkgs.legacyPackages.${system};
        zn = zn-nix.mk-zn system;
        ztrRtDeps = with pkgs; [
          fontconfig.lib
          xorg.libX11
          xorg.libXxf86vm
          xorg.libXext
          xorg.libXtst
          xorg.libXi
          xorg.libXcursor
          xorg.libXrandr
          libGL
          stdenv.cc.cc.lib
        ];
        ztrBuildInputs = ztrRtDeps ++ [ pkgs.pkg-config ];
        rtLibPath = zn.mkLibPath ztrRtDeps;
        baseZtrModuleConfig = {
          projectSrc = ./.;
          name = "org.mitchdzugan/ztr";
          main-ns = "ztr.core";
          builder-extra-inputs = ztrBuildInputs;
          builder-preBuild = with pkgs; ''
            export LD_LIBRARY_PATH=${zn.mkLibPath [
              buildPackages.stdenv.cc.cc.lib fontconfig.lib xorg.libX11 libGL
            ]}
          '';
        };
        buildZtrApp = extraConfig: zn.mkCljApp {
          pkgs = pkgs;
          modules = [(extraConfig // baseZtrModuleConfig)];
        };
      in rec {
        packages.default = packages.ztr;
        packages._ztr = zn.writeBashScriptBin'
          "ztr"
          [pkgs.rlwrap packages.ztr-with-libs]
          ''
            "${pkgs.rlwrap}/bin/rlwrap" ${packages.ztr-with-libs}/bin/ztr "$@"
          '';
        packages.ztr = packages._ztr // { pname = "ztr"; };
        packages.ztr-with-libs = pkgs.stdenv.mkDerivation {
          pname = "ztr";
          inherit version;
          src = ./.;
          nativeBuildInputs = [ pkgs.makeWrapper ];
          propagatedBuildInputs = ztrBuildInputs ++ [ packages.ztr-unwrapped ];
          dontBuild = true;
          installPhase = with pkgs; ''
            runHook preInstall
            mkdir -p "$out/bin"
            makeWrapper "${packages.ztr-unwrapped}/bin/ztr" "$out/bin/ztr" \
              --unset WAYLAND_DISPLAY \
              --prefix LD_LIBRARY_PATH : "${rtLibPath}"
            runHook postInstall
          '';
        };
        packages.ztr-unwrapped = buildZtrApp {
          builder-extra-inputs = ztrBuildInputs;
          nativeImage.graalvm = pkgs.graalvmPackages.graalvm-ce;
          nativeImage.enable = true;
          nativeImage.extraNativeImageBuildArgs = [
            "--initialize-at-build-time"
            "-J-Dclojure.compiler.direct-linking=true"
            "-Dskija.staticLoad=false"
            "--initialize-at-run-time=io.github.humbleui.skija.impl.Cleanable"
            "--initialize-at-run-time=io.github.humbleui.skija.impl.RefCnt$_FinalizerHolder"
            "--initialize-at-run-time=io.github.humbleui.skija"
            "--initialize-at-build-time=io.github.humbleui.skija.BlendMode"
            "--initialize-at-run-time=org.lwjgl"
            "--native-image-info"
            "-march=compatibility"
            "-H:+JNI"
            "-H:JNIConfigurationFiles=${./.}/.graal-support/jni.json"
            "-H:ResourceConfigurationFiles=${./.}/.graal-support/resources.json"
            "-H:+ReportExceptionStackTraces"
            "--report-unsupported-elements-at-runtime"
            "--verbose"
            "-Dskija.logLevel=DEBUG"
            "-H:DashboardDump=target/dashboard-dump"
          ];
        };
        /* rest used for development */
        zflake-dev = {
          extra-deps = [zn.wait-for];
          singletons = [
            { pkg = packages.nrepl; pre-up = "rm .nrepl-port 2> /dev/null"; }
            { pkg = packages.watch-and-refresh; }
          ];
          post-up = "${zn.wait-for}/bin/wait-for -n nrepl [ -f .nrepl-port ]";
        };
        packages.build-uberjar = buildZtrApp {};
        packages.trace-run = zn.uuFlakeWrap (zn.writeBashScriptBin'
          "trace-run"
          (ztrBuildInputs ++ [ packages.build-uberjar pkgs.graalvmPackages.graalvm-ce ])
          ''
            export LD_LIBRARY_PATH="${rtLibPath}"
            gvmh="$GRAALVM_HOME"
            if [ ! -f "$gmvh/bin/java" ]; then
              gmvh="${pkgs.graalvmPackages.graalvm-ce}"
            fi
            jar_path=$(cat "${packages.build-uberjar}/nix-support/jar-path")
            $gmvh/bin/java \
              -agentlib:native-image-agent=config-merge-dir=./.graal-support \
              -jar $jar_path
          ''
        );
        packages.trace-normalize = zn.uuFlakeWrap (zn.writeBbScriptBin
          "trace-normalize"
          ''
            (require '[cheshire.core :as json]
                     '[clojure.string :as str])
            (println "normalizing trace data")
            (def trace-dir "./.graal-support/")
            (def trace-filename (partial str trace-dir))
            (def md (-> (trace-filename "reachability-metadata.json")
                        slurp
                        str/join
                        json/parse-string))
            (def rn-key #(-> %1 (dissoc %2) (assoc %3 (get %1 %2))))
            (defn md-out [l md]
              (spit (trace-filename (str l ".json")) (json/generate-string md)))
            (md-out "jni" (map #(rn-key %1 "type" "name") (get md "jni")))
            (md-out "resources" {"globs" (get md "resources")})
            (println "trace data normalized. namaste and good luck =^)")
          ''
        );
        packages.dev-run = zn.uuFlakeWrap (zn.writeBashScriptBin'
          "dev-run"
          (ztrBuildInputs ++ [pkgs.clojure])
          ''
            export LD_LIBRARY_PATH=${rtLibPath}
            cljargs="$@"
            if [ $# -eq 0 ]; then cljargs="-M -m ztr.core"; fi
            ${pkgs.clojure}/bin/clj $cljargs
          ''
        );
        packages.watch-and-refresh = zn.uuFlakeWrap (zn.writeBashScriptBin'
          "watch-and-refresh"
          [zn.rep pkgs.watchexec]
          ''
            clj1="(require '[clojure.tools.namespace.repl :as __R])"
            clj="(do $clj1 (__R/refresh))"
            watchexec -e clj,edn -- rep "\"$clj\""
          ''
        );
        packages.nrepl = (zn.mk-enhanced-nrepl
          "nrepl"
          "repl/conjure"
          ztrBuildInputs
          "export LD_LIBRARY_PATH=\"${rtLibPath}\""
        );
    });
}
