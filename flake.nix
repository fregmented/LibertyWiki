# SPDX-License-Identifier: AGPL-3.0-only
{
  description = "Optional LibertyWiki development tools";
  # Nixpkgs 26.05 retains Intel macOS support; review this when updating.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/f4244ef33645badc8157562b5e80748929b396c2";
  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      eachSystem = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs { inherit system; }));
    in {
      devShells = eachSystem (pkgs: {
        default = pkgs.mkShellNoCC {
          packages = with pkgs; [ jdk21 nodejs_22 postgresql_17 ];
          JAVA_HOME = "${pkgs.jdk21}";
        };
      });
    };
}
